package com.chatbyyourside.tts

import android.util.Base64
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.data.model.TtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 火山引擎 TTS 客户端（双端点）。
 *
 * 两种接入方式，请求体同为 HTTP V3 原生格式（BidirectionalTTS），仅 endpoint、鉴权头、响应解析不同：
 * - [proxyUrl]（默认）：CloudBase Web 函数透明代理，支持新版 apiKey 或旧版 appId+accessKey；
 *   响应为 NDJSON（每行一个 JSON），base64 音频在 "data" 字段。
 * - [directUrl]（可选，直连开关开启时）：官方 `/api/v3/tts/unidirectional` Chunked endpoint，
 *   仅支持新版控制台 X-Api-Key 鉴权；逐行读取官方 Chunked JSON，不支持 SSE `data:` 封装。
 *
 * API 参考: https://www.volcengine.com/docs/6561/1598757
 */
class VolcTtsClient(
    private val proxyUrl: String,
    private val client: OkHttpClient,
    private val directUrl: String? = null,
) {

    companion object {
        /** 火山引擎豆包 2.0 即时克隆资源 ID */
        private const val RESOURCE_ID = "seed-icl-2.0"

        /** 默认音色（未匹配到角色时使用，对齐网页版 VOICE_IDS） */
        private val DEFAULT_VOICES = mapOf(
            "zh" to "S_c1jmOCG72",
            "ja" to "S_d1jmOCG72",
        )

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }

    // ===== V3 请求/响应模型 =====

    @Serializable
    private data class V3Request(
        val user: V3User,
        val namespace: String,
        val req_params: V3ReqParams,
    )

    @Serializable
    private data class V3User(val uid: String)

    @Serializable
    private data class V3ReqParams(
        val text: String,
        val speaker: String,
        val audio_params: V3AudioParams,
        val additions: String? = null,
    )

    @Serializable
    private data class V3AudioParams(
        val format: String = "mp3",
        val sample_rate: Int = 24000,
    )

    // ===== Public API =====

    /**
     * 合成语音，返回 mp3 字节数组。
     *
     * @param text        待合成文本
     * @param language    语言（zh / ja），用于选择默认音色
     * @param characterId 角色 ID（用于日志）
     * @param ttsConfig   TTS 配置（含 apiKey / appId / accessKey）
     * @param voice       火山引擎音色 ID（S_xxx 格式）；null 则用语言默认音色
     * @param useDirect   为 true 且配置了 [directUrl] 时直连官方 Chunked endpoint（需新版 apiKey）
     */
    suspend fun synthesize(
        text: String,
        language: String,
        characterId: String,
        ttsConfig: TtsConfig,
        voice: String? = null,
        useDirect: Boolean = false,
    ): ByteArray = withContext(Dispatchers.IO) {
        // 音色选择：优先传入 > 语言默认
        val speaker = voice?.takeIf { it.isNotBlank() }
            ?: DEFAULT_VOICES[language]
            ?: DEFAULT_VOICES["zh"]!!

        // 构建火山引擎 V3 请求体（对齐网页版 synthesize()）
        val v3Body = V3Request(
            user = V3User(uid = "mrfz-talk-terminal"),
            namespace = "BidirectionalTTS",
            req_params = V3ReqParams(
                text = text.trim(),
                speaker = speaker,
                audio_params = V3AudioParams(format = "mp3", sample_rate = 24000),
                additions = """{"disable_markdown_filter":true}""",
            ),
        )

        val requestBody = json.encodeToString(V3Request.serializer(), v3Body)
            .toRequestBody("application/json".toMediaType())

        if (useDirect && directUrl != null) {
            return@withContext synthesizeDirect(directUrl, requestBody, ttsConfig)
        }

        synthesizeViaProxy(requestBody, ttsConfig)
    }

    /** 检查 TTS 凭据是否已配置（对齐网页版 hasCredentials()；直连额外在合成时校验新版 apiKey） */
    fun hasCredentials(config: TtsConfig): Boolean {
        return config.apiKey.isNotBlank() || (config.appId.isNotBlank() && config.accessKey.isNotBlank())
    }

    // ===== 代理路径（默认，CloudBase 透明转发）=====

    private suspend fun synthesizeViaProxy(
        requestBody: okhttp3.RequestBody,
        ttsConfig: TtsConfig,
    ): ByteArray {
        // 构建 HTTP 请求（鉴权在 Header，对齐网页版 + 代理透传逻辑）
        val request = Request.Builder()
            .url(proxyUrl)
            .post(requestBody)
            .apply {
                if (ttsConfig.apiKey.isNotBlank()) {
                    addHeader("X-Api-Key", ttsConfig.apiKey)
                    addHeader("X-Api-Resource-Id", RESOURCE_ID)
                    addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
                }
                if (ttsConfig.appId.isNotBlank()) {
                    addHeader("X-Api-App-Key", ttsConfig.appId)
                }
                if (ttsConfig.accessKey.isNotBlank()) {
                    addHeader("X-Api-Access-Key", ttsConfig.accessKey)
                }
            }
            .build()

        // 协程取消时中断阻塞的 OkHttp 合成：否则 ttsJob 取消后合成仍在跑直到读超时。
        val call = client.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { call.cancel() } }

        // 执行请求（OkHttp execute 是阻塞的，在 IO 调度器运行）
        val response = call.execute()
        return response.use { resp ->
            val body = resp.body ?: throw Exception("TTS 代理返回空响应体")
            if (!resp.isSuccessful) {
                // 错误响应体通常很小，有界读；只取前 500 字符，不整读大错误体。
                val errorSnippet = String(body.bytes()).take(500)
                throw Exception("TTS HTTP ${resp.code}: $errorSnippet")
            }
            // 流式解析 NDJSON（对齐网页版 synthesize() 的 JSON 行解析逻辑）：
            // 逐行解码 base64 音频，只保留解码后字节，避免整读 + String + base64 StringBuilder + decode 四重副本。
            parseV3ResponseStream(body)
        }
    }

    // ===== 直连路径（官方 Chunked endpoint，仅新版 API Key）=====

    private suspend fun synthesizeDirect(
        endpoint: String,
        requestBody: okhttp3.RequestBody,
        ttsConfig: TtsConfig,
    ): ByteArray {
        if (ttsConfig.apiKey.isBlank()) {
            throw Exception("直连火山引擎需要新版 API Key 凭据（代理模式才支持旧版 App ID + Access Key）")
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .header("X-Api-Key", ttsConfig.apiKey)
            .header("X-Api-Resource-Id", AppConfig.TTS_VOICE_CLONE_RESOURCE_ID)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .build()

        val call = client.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { call.cancel() } }

        return call.execute().use { response ->
            val body = response.body ?: throw Exception("火山引擎 TTS 返回空响应体")
            val logId = response.header("X-Tt-Logid")
            if (!response.isSuccessful) {
                val snippet = body.bytes().decodeToString().take(500)
                throw Exception("TTS HTTP ${response.code}: $snippet${logId?.let { "（LogID: $it）" } ?: ""}")
            }
            parseDirectChunkedResponse(body, logId)
        }
    }

    /** 解析官方 Chunked JSON：code 0=音频块、20000000=结束，均视为成功；SSE data:/event: 前缀即拒绝。 */
    private fun parseDirectChunkedResponse(body: ResponseBody, logId: String?): ByteArray {
        val output = ByteArrayOutputStream()
        val source = body.source()
        var errorInfo: JsonObject? = null

        while (true) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("data:") || trimmed.startsWith("event:")) {
                throw Exception("火山引擎返回 SSE 格式；当前客户端仅支持 HTTP Chunked")
            }

            val obj = try {
                json.parseToJsonElement(trimmed).jsonObject
            } catch (_: Exception) {
                throw Exception("火山引擎返回了无法解析的 Chunked JSON")
            }
            val code = obj["code"]?.jsonPrimitive?.intOrNull
            when (code) {
                null, 0, 20_000_000 -> Unit
                else -> if (errorInfo == null) errorInfo = obj
            }

            val data = obj["data"]?.jsonPrimitive?.contentOrNull
            if (!data.isNullOrEmpty()) {
                try {
                    output.write(Base64.decode(data.replace(Regex("\\s"), ""), Base64.DEFAULT))
                } catch (_: IllegalArgumentException) {
                    throw Exception("火山引擎返回了非法 Base64 音频数据")
                }
            }
        }

        errorInfo?.let { error ->
            val errCode = error["code"]?.jsonPrimitive?.intOrNull ?: "未知"
            val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            throw Exception("火山引擎错误 $errCode: $message${logId?.let { "（LogID: $it）" } ?: ""}")
        }
        if (output.size() == 0) throw Exception("火山引擎返回无音频数据${logId?.let { "（LogID: $it）" } ?: ""}")
        return output.toByteArray()
    }

    // ===== Private（代理 NDJSON 解析）=====

    /** 流式解析火山引擎 V3 NDJSON：逐行读取，逐块解码 base64 音频，只保留解码后字节（单副本）。 */
    private fun parseV3ResponseStream(body: ResponseBody): ByteArray {
        val output = ByteArrayOutputStream()
        val source = body.source()
        var errorInfo: JsonObject? = null

        var line = source.readUtf8Line()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val obj = try {
                    json.parseToJsonElement(trimmed).jsonObject
                } catch (_: Exception) {
                    null
                }
                if (obj != null) {
                    // 收集音频 data（空字符串也判掉，对齐网页版 != null && !== ''）
                    val data = obj["data"]?.jsonPrimitive?.contentOrNull
                    if (!data.isNullOrEmpty()) {
                        val decoded = Base64.decode(data.replace(Regex("\\s"), ""), Base64.DEFAULT)
                        output.write(decoded)
                    }

                    // 捕获错误（code != 0 或 message != success，对齐网页版逻辑）
                    val code = obj["code"]?.jsonPrimitive?.intOrNull
                    val message = obj["message"]?.jsonPrimitive?.contentOrNull
                    val error = obj["error"]?.jsonPrimitive?.booleanOrNull
                    val isError = (code != null && code != 0) || error == true ||
                        (message != null && message != "success")

                    if (errorInfo == null && isError) {
                        errorInfo = obj
                        // 不立即抛异常：后续行可能仍含音频，先全部收集
                    }
                }
            }
            line = source.readUtf8Line()
        }

        // 有错误记录且无音频数据 → 抛出火山引擎错误
        if (output.size() == 0 && errorInfo != null) {
            val err = errorInfo!!
            val errCode = err["code"]?.jsonPrimitive?.intOrNull?.toString() ?: ""
            val errMsg = err["message"]?.jsonPrimitive?.contentOrNull
                ?: err["error"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val desc = listOf(errCode, errMsg).filter { it.isNotBlank() }.joinToString(": ")
            throw Exception("火山引擎错误: ${desc.ifBlank { err.toString() }}")
        }

        if (output.size() == 0) {
            throw Exception("火山引擎返回无音频数据")
        }

        return output.toByteArray()
    }
}
