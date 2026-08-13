package com.chatbyyourside.video

import com.chatbyyourside.data.model.ApiConfig
import com.chatbyyourside.data.model.SeedanceModelVariant
import com.chatbyyourside.data.model.SeedanceRatio
import com.chatbyyourside.data.model.SeedanceResolution
import com.chatbyyourside.data.remote.ChatMessageDto
import com.chatbyyourside.data.remote.DirectLlmClient
import com.chatbyyourside.util.MarkdownParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Seedance 结构化视频提示词文档（Task 4）。
 *
 * 九个字段全部为字符串：前七项为导演式分镜描述（来自 LLM 结构化输出），
 * [technical] 与 [finalPrompt] 由生成器依据输入技术参数确定性覆盖，
 * 保证最终文档含模型型号 / 分辨率 / 画幅 / 时长 / 音频开启。
 */
@Serializable
data class SeedancePromptDocument(
    val subject: String = "",
    val action: String = "",
    val environment: String = "",
    val camera: String = "",
    val lighting: String = "",
    val audio: String = "",
    val continuity: String = "",
    val technical: String = "",
    val finalPrompt: String = "",
)

/**
 * Seedance 视频提示词生成输入（来自角色与对话快照 + 生成参数）。
 */
data class SeedancePromptInput(
    val characterName: String,
    val characterRole: String,
    val characterSystemPrompt: String,
    val userText: String,
    val assistantText: String,
    val sceneDescription: String,
    val variant: SeedanceModelVariant,
    val resolution: SeedanceResolution,
    val ratio: SeedanceRatio,
    val durationSeconds: Int,
)

/**
 * 提示词生成 LLM 一次性调用请求。
 */
data class SeedancePromptRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<ChatMessageDto>,
    val jsonMode: Boolean,
)

/**
 * 提示词生成 LLM 抽象（可注入假实现，JVM 测试零 HTTP）。
 */
interface SeedancePromptLlm {
    suspend fun complete(request: SeedancePromptRequest): String
}

/**
 * 生产实现：包装 [DirectLlmClient]，请求结构化 JSON 输出。
 */
class DirectLlmSeedancePromptLlm(
    private val client: DirectLlmClient,
) : SeedancePromptLlm {
    override suspend fun complete(request: SeedancePromptRequest): String =
        client.chatOnceStructured(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            model = request.model,
            messages = request.messages,
            responseFormatJson = request.jsonMode,
        )
}

/**
 * 结构化提示词解析失败（模型输出无法解析为合法 JSON 文档）。
 *
 * 解析失败即抛出本异常，绝不重试/二次调用、绝不拼凑伪造 JSON。
 */
class SeedancePromptParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Seedance 结构化视频提示词生成器。
 *
 * 流程：固定中文系统指令（单一可见角色 / 身份与服饰连续性 / 电影感运动 / 原生声音 / 严格 JSON）
 * + 单条纯文本用户消息（输入字段直白拼接，绝不携带图片或 base64）→ 一次性调用 [SeedancePromptLlm]
 * → 严格解析 JSON（容忍 ```json 围栏、裸 JSON、<think> 与首个 { 之前的引导语）→
 * 失败即抛 [SeedancePromptParseException]。
 */
class SeedancePromptGenerator(
    private val llm: SeedancePromptLlm,
) {
    suspend fun generate(apiConfig: ApiConfig, input: SeedancePromptInput): SeedancePromptDocument {
        val messages = listOf(
            ChatMessageDto("system", JsonPrimitive(SYSTEM_PROMPT)),
            ChatMessageDto("user", JsonPrimitive(buildUserMessage(input))),
        )
        val raw = llm.complete(
            SeedancePromptRequest(
                baseUrl = apiConfig.baseUrl,
                apiKey = apiConfig.apiKey,
                model = apiConfig.model,
                messages = messages,
                jsonMode = true,
            )
        )
        return parseDocument(raw, input)
    }

    private fun parseDocument(raw: String, input: SeedancePromptInput): SeedancePromptDocument {
        val candidate = extractJsonCandidate(MarkdownParser.stripThink(raw))
        val doc = try {
            json.decodeFromString(SeedancePromptDocument.serializer(), candidate)
        } catch (e: Exception) {
            throw SeedancePromptParseException("结构化提示词解析失败：模型输出不是合法 JSON", e)
        }
        // 拒绝空/占位 JSON：没有主体/动作/环境描述不得视为成功提示词。
        if (doc.subject.isBlank() && doc.action.isBlank() && doc.environment.isBlank()) {
            throw SeedancePromptParseException("结构化提示词缺少主体/动作/环境描述")
        }
        val technical = buildTechnical(input)
        return doc.copy(technical = technical, finalPrompt = buildFinalPrompt(doc, technical))
    }

    /** 提取 JSON 候选文本：去 ```json 围栏、截取首个 { 到最后一个 }（仅做必然安全的引导语裁剪）。 */
    private fun extractJsonCandidate(text: String): String {
        var t = text.trim()
        val jsonFence = t.indexOf("```json")
        if (jsonFence >= 0) {
            t = t.substring(jsonFence + "```json".length)
            val close = t.indexOf("```")
            if (close >= 0) t = t.substring(0, close)
        } else {
            val generic = t.indexOf("```")
            if (generic >= 0) {
                val close = t.indexOf("```", generic + 3)
                t = if (close >= 0) t.substring(generic + 3, close) else t.substring(generic + 3)
            }
        }
        t = t.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw SeedancePromptParseException("模型输出中未找到 JSON 对象")
        }
        return t.substring(start, end + 1)
    }

    /** 技术参数（确定性覆盖，保证含模型型号/分辨率/画幅/时长/音频）。 */
    private fun buildTechnical(input: SeedancePromptInput): String =
        "模型：${input.variant.modelId}；分辨率：${input.resolution.name}；" +
            "画幅：${input.ratio.apiValue}；时长：${input.durationSeconds}秒；音频：开启"

    /** 最终成片提示词 = 分镜描述 + 技术参数。 */
    private fun buildFinalPrompt(doc: SeedancePromptDocument, technical: String): String {
        val description = listOf(
            doc.subject, doc.action, doc.environment, doc.camera, doc.lighting, doc.audio, doc.continuity,
        ).filter { it.isNotBlank() }.joinToString("；")
        return if (description.isEmpty()) technical else "$description。$technical"
    }

    /** 单条用户消息：输入字段纯文本拼接，场景描述可选，绝不携带图片/base64。 */
    private fun buildUserMessage(input: SeedancePromptInput): String = buildString {
        appendLine("角色名称：${input.characterName}")
        appendLine("角色身份：${input.characterRole}")
        appendLine("角色设定：${input.characterSystemPrompt}")
        appendLine("用户发言：${input.userText}")
        appendLine("角色回复：${input.assistantText}")
        if (input.sceneDescription.isNotBlank()) {
            appendLine("场景补充：${input.sceneDescription}")
        }
        append("请生成结构化视频提示词 JSON。")
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** 固定中文系统指令：单一可见角色、身份/服饰连续、电影感运动、原生声音、严格 JSON。 */
        private const val SYSTEM_PROMPT = """你是 Seedance 视频提示词导演。根据提供的角色与对话内容，生成一段可直接用于视频生成的详细中文分镜提示词。

硬性要求：
1. 全片只有一个可见角色，即当前角色本人；不得出现第二人、路人或其他人物（仅允许环境中的非人物元素）。
2. 角色外貌、服装、发型、道具在全片保持完全一致（身份与服饰连续性）。
3. 动作描述要有电影感与连贯运动感（cinematic motion），镜头语言明确。
4. 视频必须包含原生声音与音效（native audio），音频描述需与画面动作一致。
5. 严格只输出一个 JSON 对象，不要输出任何解释或多余文字，不要输出思考过程。

JSON 字段（均为字符串，使用中文）：
- subject：画面主体（只能是当前这一个角色，含外貌与服装描述）
- action：角色的动作与运动过程
- environment：环境与场景
- camera：镜头与运镜
- lighting：光线与色调
- audio：原生声音与音效
- continuity：身份与服饰连续性说明
- technical：技术参数（模型/分辨率/画幅/时长/音频）
- finalPrompt：整合以上所有要素的最终成片提示词"""
    }
}
