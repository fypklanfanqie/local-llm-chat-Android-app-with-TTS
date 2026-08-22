package com.chatbyyourside.data.remote

import com.chatbyyourside.config.normalizeBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 单条聊天消息（OpenAI 兼容格式）。
 * content 为纯文本 JsonPrimitive 或多模态数组（image_url + text），直接透传给对话商。
 */
@Serializable
data class ChatMessageDto(
    val role: String,
    val content: kotlinx.serialization.json.JsonElement,
)

/**
 * 直连对话商 OpenAI 兼容 API 客户端（不经任何服务器代理）。
 *
 * - [chatStream]：SSE 流式对话，逐 token 回调累积文本（与 ChatProvider 契约一致）。
 * - [chatOnce]：非流式一次性调用（翻译 / 文档提取用）。
 *
 * 取消：在当前协程 [Job] 上注册 invokeOnCompletion，取消时关闭底层 [Call]；
 * 调用方也可经 [chatStream] 的 onCall 持有 [Call] 主动 cancel。
 */
class DirectLlmClient(
    private val client: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    },
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** SSE 流式对话。onChunk 收到累积文本，返回完整文本。
     *  deepThinking=true 时解析 reasoning_content 并以 <think>...</think> 注入累积文本（复用本地思考展示），
     *  并对支持的供应商注入 enable_thinking。 */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        onChunk: (String) -> Unit,
        onCall: ((Call) -> Unit)? = null,
        deepThinking: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        // Anthropic 格式：/v1/messages + x-api-key 头 + content_block_delta 流式；
        // 其余端点一律 OpenAI 兼容格式。base 先归一化，剥离粘贴/输入法污染的尾 scheme 残渣。
        val base = normalizeBaseUrl(baseUrl)
        if (isAnthropicEndpoint(base)) {
            anthropicChatStream(base, apiKey, model, messages, onChunk, onCall)
        } else {
            val request = buildRequest(
                endpoint = buildEndpoint(base),
                apiKey = apiKey,
                body = buildBody(model, messages, stream = true, baseUrl = base, deepThinking = deepThinking),
                accept = "text/event-stream",
            )
            executeStreaming(request, onChunk, onCall, deepThinking)
        }
    }

    /** 非流式一次性对话。返回 choices[0].message.content。 */
    suspend fun chatOnce(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
    ): String = chatOnceInternal(baseUrl, apiKey, model, messages, responseFormatJson = false)

    /** 非流式一次性对话，可请求结构化 JSON 输出。
     *  仅当 [responseFormatJson]=true 且供应商在白名单内时才注入 response_format=json_object，
     *  否则与 [chatOnce] 完全一致（仍依赖严格文本 JSON 指令）。 */
    suspend fun chatOnceStructured(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        responseFormatJson: Boolean,
    ): String = chatOnceInternal(baseUrl, apiKey, model, messages, responseFormatJson)

    private suspend fun chatOnceInternal(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        responseFormatJson: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(baseUrl)
        if (isAnthropicEndpoint(base)) {
            anthropicChatOnce(base, apiKey, model, messages)
        } else {
            val request = buildRequest(
                endpoint = buildEndpoint(base),
                apiKey = apiKey,
                body = buildBody(
                    model = model,
                    messages = messages,
                    stream = false,
                    baseUrl = base,
                    deepThinking = false,
                    responseFormatJson = responseFormatJson && supportsJsonObjectResponse(base, model),
                ),
                accept = null,
            )
            val call = client.newCall(request)
            val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw Exception(parseError(response.code, raw))
                    parseFullContent(raw)
                }
            } catch (e: IOException) {
                coroutineContext.ensureActive() // 被取消（call.cancel 触发流关闭）时抛 CancellationException
                throw Exception("网络错误: ${e.message ?: "请求失败"}", e)
            } finally {
                handle?.dispose()
                call.cancel()
            }
        }
    }

    private suspend fun executeStreaming(
        request: Request,
        onChunk: (String) -> Unit,
        onCall: ((Call) -> Unit)?,
        deepThinking: Boolean,
    ): String {
        val call = client.newCall(request)
        onCall?.invoke(call)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        // 双缓冲：reasoningBuf 收推理内容（包装为 <think>），contentBuf 收正文。复用本地思考展示通道。
        val reasoningBuf = StringBuilder()
        val contentBuf = StringBuilder()
        var contentStarted = false
        try {
            call.execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    val raw = body?.string().orEmpty()
                    throw Exception(parseError(response.code, raw))
                }
                val isSse = body.contentType()?.subtype
                    ?.equals("event-stream", ignoreCase = true) == true
                if (isSse) {
                    val source = body.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        coroutineContext.ensureActive()
                        if (line.isBlank() || line.startsWith(":")) continue
                        if (!line.startsWith("data:", ignoreCase = true)) continue
                        val data = line.substringAfter("data:").trim()
                        if (data == "[DONE]") break
                        val (content, reasoning) = parseDelta(data)
                        if (!reasoning.isNullOrEmpty() && deepThinking) reasoningBuf.append(reasoning)
                        if (!content.isNullOrEmpty()) {
                            contentStarted = true
                            contentBuf.append(content)
                        }
                        onChunk(renderAccumulated(reasoningBuf, contentBuf, contentStarted))
                    }
                } else {
                    // 个别供应商忽略 stream:true，返回整段 JSON
                    val raw = body.string()
                    val content = parseFullContent(raw)
                    if (content.isNotEmpty()) {
                        contentStarted = true
                        contentBuf.append(content)
                        onChunk(renderAccumulated(reasoningBuf, contentBuf, contentStarted))
                    }
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive() // 被取消时抛 CancellationException
            throw Exception("网络错误: ${e.message ?: "请求失败"}", e)
        } finally {
            handle?.dispose()
            call.cancel()
        }
        return renderAccumulated(reasoningBuf, contentBuf, contentStarted)
    }

    internal fun buildEndpoint(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl).trimEnd('/')
        return if (base.endsWith("/chat/completions", ignoreCase = true)) {
            base
        } else {
            "$base/chat/completions"
        }
    }

    /**
     * 从 baseUrl 自动探测 Anthropic 格式端点（/v1/messages + x-api-key 头）：
     * - baseUrl 含 anthropic / claude（官方域或中转网关）→ Anthropic；
     * - baseUrl 以 /v1/messages 结尾 → Anthropic；
     * - 其余一律 OpenAI 兼容格式。
     * 判定前先 [normalizeBaseUrl]，避免污染的 `.../v1/messageshttps` 被误判为 OpenAI。
     */
    internal fun isAnthropicEndpoint(baseUrl: String): Boolean {
        val b = normalizeBaseUrl(baseUrl).lowercase()
        return b.contains("anthropic") || b.contains("claude") ||
            b.trim().trimEnd('/').endsWith("/v1/messages")
    }

    private fun buildBody(
        model: String,
        messages: List<ChatMessageDto>,
        stream: Boolean,
        baseUrl: String,
        deepThinking: Boolean,
        responseFormatJson: Boolean = false,
    ): String {
        val obj = buildJsonObject {
            // trim：粘贴带入的首尾空白会让模型名不匹配被上游拒 400。
            put("model", model.trim())
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
            put("stream", stream)
            // 深度思考：对支持开关的供应商注入 enable_thinking（开=请求思考，关=显式停止）
            if (supportsThinkingToggle(baseUrl, model)) {
                put("enable_thinking", deepThinking)
            }
            // 结构化输出：仅显式请求 JSON 模式时注入（白名单判定已在调用侧完成，见 supportsJsonObjectResponse）
            if (responseFormatJson) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }
        return obj.toString()
    }

    private fun buildRequest(endpoint: String, apiKey: String, body: String, accept: String?): Request {
        val reqBody = body.toRequestBody(jsonMediaType)
        return Request.Builder()
            .url(endpoint)
            // trim：粘贴带入的首尾空白会让 Bearer 头不合法被上游拒收（401/400）。
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .apply { if (accept != null) header("Accept", accept) }
            .post(reqBody)
            .build()
    }

    /** 从一条 SSE data 负载解析 choices[0].delta.content；无 content 返回 null。 */
    private fun parseDeltaContent(data: String): String? = try {
        val obj = json.parseToJsonElement(data).jsonObject
        val choices = obj["choices"]?.jsonArray ?: return null
        val delta = choices.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return null
        delta["content"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }

    /** 从一条 SSE data 负载解析 (content, reasoning)；DeepSeek/Qwen 用 reasoning_content，部分用 reasoning。 */
    private fun parseDelta(data: String): Pair<String?, String?> = try {
        val obj = json.parseToJsonElement(data).jsonObject
        val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
        val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
        content to reasoning
    } catch (e: Exception) {
        null to null
    }

    /** 渲染累积文本：有推理内容时包装为 <think>...</think>（未开始正文时不闭合 -> 流式思考段）。 */
    private fun renderAccumulated(
        reasoningBuf: StringBuilder,
        contentBuf: StringBuilder,
        contentStarted: Boolean,
    ): String {
        val reasoning = reasoningBuf.toString()
        val content = contentBuf.toString()
        if (reasoning.isEmpty()) return content
        return if (contentStarted) "<think>$reasoning</think>$content" else "<think>$reasoning"
    }

    /** 是否支持 enable_thinking 参数（仅 Qwen 系：dashscope / 硅基 / qwen3 / qwq）。
     *  DeepSeek/GLM/OpenAI/自定义端点不注入，避免未知参数导致 400。 */
    private fun supportsThinkingToggle(baseUrl: String, model: String): Boolean {
        val b = baseUrl.lowercase()
        val m = model.lowercase()
        return b.contains("dashscope") || b.contains("siliconflow") ||
            m.contains("qwen3") || m.contains("qwq")
    }

    /** 是否支持 response_format=json_object（结构化输出）。
     *  仅白名单供应商：OpenAI（api.openai.com / gpt-*）、DeepSeek（baseUrl 或模型含 deepseek）、
     *  Qwen（dashscope / siliconflow / qwen 系模型）。其余端点保守不注入，避免未知参数 400；
     *  生成器对返回内容仍严格解析，不依赖本白名单兜底。 */
    private fun supportsJsonObjectResponse(baseUrl: String, model: String): Boolean {
        val b = baseUrl.lowercase()
        val m = model.lowercase()
        return b.contains("api.openai.com") ||
            b.contains("deepseek") ||
            b.contains("dashscope") ||
            b.contains("siliconflow") ||
            m.startsWith("gpt-") ||
            m.startsWith("deepseek-") ||
            m.startsWith("qwen") ||
            m.startsWith("qwq")
    }

    /** 解析非流式 JSON 完整回复；若实为 SSE 文本则退化为逐行解析。 */
    private fun parseFullContent(raw: String): String = try {
        val obj = json.parseToJsonElement(raw).jsonObject
        val choices = obj["choices"]?.jsonArray ?: return parseSseToContent(raw)
        choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
    } catch (e: Exception) {
        parseSseToContent(raw)
    }

    /** 从一段 SSE 文本中拼接所有 delta.content（供应商忽略 stream 时兜底）。 */
    private fun parseSseToContent(raw: String): String {
        val sb = StringBuilder()
        raw.lineSequence().forEach { line ->
            if (!line.startsWith("data:", ignoreCase = true)) return@forEach
            val data = line.substringAfter("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return@forEach
            parseDeltaContent(data)?.let { sb.append(it) }
        }
        return sb.toString()
    }

    /** 从错误响应体提取人类可读信息：error.message / error / message / HTTP {code}。 */
    private fun parseError(code: Int, raw: String): String {
        val msg = try {
            val obj = json.parseToJsonElement(raw).jsonObject
            when (val err = obj["error"]) {
                is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> err.contentOrNull
                else -> obj["message"]?.jsonPrimitive?.contentOrNull
            }
        } catch (e: Exception) {
            null
        }
        return if (msg.isNullOrBlank()) "HTTP $code" else "HTTP $code: $msg"
    }
    // ===== Anthropic 格式（/v1/messages + x-api-key / anthropic-version）=====

    private val ANTHROPIC_VERSION = "2023-06-01"
    private val ANTHROPIC_MAX_TOKENS = 8192

    /** 兼容用户填写的 baseUrl 形态：`…/v1` / `…/v1/messages` 已含 / 裸域。先归一化剥尾 scheme 残渣。 */
    internal fun buildAnthropicEndpoint(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl).trimEnd('/')
        return when {
            base.endsWith("/v1/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            else -> "$base/v1/messages"
        }
    }

    private fun buildAnthropicRequest(endpoint: String, apiKey: String, body: String, accept: String?): Request {
        val reqBody = body.toRequestBody(jsonMediaType)
        return Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .apply { if (accept != null) header("Accept", accept) }
            .post(reqBody)
            .build()
    }

    private fun buildAnthropicBody(
        model: String,
        messages: List<ChatMessageDto>,
        stream: Boolean,
        maxTokens: Int,
    ): String {
        val system = messages.filter { it.role == "system" }
            .joinToString("\n") { anthropicTextOf(it.content) }
        val apiMessages = messages.filter { it.role != "system" }.map { m ->
            buildJsonObject {
                put("role", m.role)
                put("content", anthropicContent(m.content))
            }
        }
        return buildJsonObject {
            put("model", model.trim())
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            put("messages", buildJsonArray { apiMessages.forEach { add(it) } })
            put("stream", stream)
        }.toString()
    }

    /** 纯文本提取（system 拼接用）：字符串原样；多模态数组只取 text 块。 */
    private fun anthropicTextOf(content: JsonElement): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { b ->
            val obj = b.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "text") obj["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString("")
        else -> ""
    }

    /** 把 OpenAI 兼容 content 映射为 Anthropic content 块：image_url(data URI)→base64 图块，text 原样。 */
    private fun anthropicContent(content: JsonElement): JsonElement {
        if (content is JsonPrimitive) return content
        if (content !is JsonArray) return JsonPrimitive("")
        return buildJsonArray {
            content.forEach { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.content) {
                    "image_url" -> {
                        val url = obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: return@forEach
                        // url = "data:<mime>;base64,<data>"
                        val parts = url.removePrefix("data:").split(";base64,", limit = 2)
                        if (parts.size == 2 && parts[1].isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", parts[0])
                                    put("data", parts[1])
                                })
                            })
                        }
                    }
                    "text" -> add(buildJsonObject {
                        put("type", "text")
                        put("text", obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    })
                }
            }
        }
    }

    private suspend fun anthropicChatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        onChunk: (String) -> Unit,
        onCall: ((Call) -> Unit)?,
    ): String {
        val request = buildAnthropicRequest(
            endpoint = buildAnthropicEndpoint(baseUrl),
            apiKey = apiKey,
            body = buildAnthropicBody(model, messages, stream = true, maxTokens = ANTHROPIC_MAX_TOKENS),
            accept = "text/event-stream",
        )
        return executeAnthropicStreaming(request, onChunk, onCall)
    }

    private suspend fun executeAnthropicStreaming(
        request: Request,
        onChunk: (String) -> Unit,
        onCall: ((Call) -> Unit)?,
    ): String {
        val call = client.newCall(request)
        onCall?.invoke(call)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        val contentBuf = StringBuilder()
        try {
            call.execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    val raw = body?.string().orEmpty()
                    throw Exception(parseError(response.code, raw))
                }
                val isSse = body.contentType()?.subtype
                    ?.equals("event-stream", ignoreCase = true) == true
                if (isSse) {
                    val source = body.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        coroutineContext.ensureActive()
                        if (line.isBlank() || line.startsWith(":")) continue
                        if (!line.startsWith("data:", ignoreCase = true)) continue
                        val data = line.substringAfter("data:").trim()
                        if (data == "[DONE]") break
                        val text = parseAnthropicDelta(data)
                        if (!text.isNullOrEmpty()) contentBuf.append(text)
                        onChunk(contentBuf.toString())
                        if (isAnthropicStop(data)) break
                    }
                } else {
                    // 个别供应商忽略 stream:true，返回整段 JSON
                    val raw = body.string()
                    val content = parseAnthropicContent(raw)
                    if (content.isNotEmpty()) {
                        contentBuf.append(content)
                        onChunk(contentBuf.toString())
                    }
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive() // 被取消时抛 CancellationException
            throw Exception("网络错误: ${e.message ?: "请求失败"}", e)
        } finally {
            handle?.dispose()
            call.cancel()
        }
        return contentBuf.toString()
    }

    private suspend fun anthropicChatOnce(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
    ): String {
        val request = buildAnthropicRequest(
            endpoint = buildAnthropicEndpoint(baseUrl),
            apiKey = apiKey,
            body = buildAnthropicBody(model, messages, stream = false, maxTokens = ANTHROPIC_MAX_TOKENS),
            accept = null,
        )
        val call = client.newCall(request)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        return try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw Exception(parseError(response.code, raw))
                parseAnthropicContent(raw)
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw Exception("网络错误: ${e.message ?: "请求失败"}", e)
        } finally {
            handle?.dispose()
            call.cancel()
        }
    }

    /** 解析 Anthropic SSE data 负载中的 text_delta 增量；其它事件返回 null。 */
    private fun parseAnthropicDelta(data: String): String? = try {
        val obj = json.parseToJsonElement(data).jsonObject
        if (obj["type"]?.jsonPrimitive?.content == "content_block_delta") {
            val delta = obj["delta"]?.jsonObject
            if (delta?.get("type")?.jsonPrimitive?.content == "text_delta") {
                delta["text"]?.jsonPrimitive?.contentOrNull
            } else null
        } else null
    } catch (e: Exception) {
        null
    }

    /** 解析 Anthropic 非流式响应：content[] 中所有 text 块拼接。 */
    private fun parseAnthropicContent(raw: String): String = try {
        val obj = json.parseToJsonElement(raw).jsonObject
        obj["content"]?.jsonArray?.mapNotNull { block ->
            val b = block.jsonObject
            if (b["type"]?.jsonPrimitive?.content == "text") b["text"]?.jsonPrimitive?.contentOrNull else null
        }?.joinToString("").orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** Anthropic SSE 是否已到 message_stop（正常结束标记）。 */
    private fun isAnthropicStop(data: String): Boolean = runCatching {
        json.parseToJsonElement(data).jsonObject["type"]?.jsonPrimitive?.content == "message_stop"
    }.getOrDefault(false)
}

