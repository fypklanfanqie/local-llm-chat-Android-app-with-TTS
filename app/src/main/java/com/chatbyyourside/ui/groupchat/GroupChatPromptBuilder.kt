package com.chatbyyourside.ui.groupchat

import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.data.model.Character
import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.util.MarkdownParser

/**
 * 群聊提示词构建（纯函数，JVM 可测）。
 *
 * OpenAI 兼容 chat 消息的 assistant 角色无法表达「谁在发言」——多说话人靠**名字织进正文**表达：
 * - system：当前 speaker 完整人设为唯一可执行身份；其他成员只作只读参考并禁止代替其说话。
 * - assistant 历史：改写为 `名字：内容`（剥离深度思考）。
 * - user 历史：保持 user（用户）。
 * - **末尾 user 轮**：每次请求最后一条固定是 user-role 的发言指令，保证多成员循环中
 *   请求永不以 `assistant C：…` 结尾被模型当作同一助手续写（「A 套 C 人设」主根因）。
 *
 * [buildApiMessages] 供 [com.chatbyyourside.ui.groupchat.GroupChatViewModel] 的流式路径
 * （`provider.chat(List<ChatMessage>)`）复用；后台 Worker 再映射为 `ChatMessageDto` 走 `chatOnce`。
 */
object GroupChatPromptBuilder {

    /** 未知成员（已被移出群）的名字兜底。 */
    const val FALLBACK_NAME = "群聊成员"

    /**
     * 构建完整 API 消息序列：system + 封装历史 + 尾部世界书 + 末尾当前 speaker 的 user 轮。
     *
     * 世界书参数（缓存友好布局）：[lorebookStaticHead] 为 constant 条目静态段，拼进 system
     * （内容只随条目编辑变化 → system 逐字节稳定 → 云端前缀缓存复用）；[lorebookTailMessages]
     * 为动态命中段——插在历史之后、**末尾 user 轮之前**，贴近对话且不触碰头部缓存。
     */
    fun buildApiMessages(
        members: List<Character>,
        speaker: Character,
        history: List<ChatMessage>,
        askUser: Boolean,
        userPersona: String? = null,
        userRelationship: String? = null,
        targeted: Boolean = false,
        worldviewDirective: String? = null,
        lorebookStaticHead: String = "",
        lorebookTailMessages: List<ChatMessage> = emptyList(),
    ): List<ChatMessage> {
        val nameById = members.associate { it.id to it.name }
        val mappedHistory = history.takeLast(AppConfig.GroupChat.MAX_CONTEXT_MESSAGES).mapNotNull { m ->
            val clean = MarkdownParser.stripThink(m.content).trim()
            if (clean.isEmpty()) return@mapNotNull null
            when (m.role) {
                "user" -> ChatMessage(role = "user", content = clean)
                "assistant" -> {
                    val name = m.characterId?.let { nameById[it] } ?: FALLBACK_NAME
                    ChatMessage(role = "assistant", content = "$name：$clean")
                }
                else -> null
            }
        }
        val systemMessage = ChatMessage(
            role = "system",
            content = buildSystemPrompt(members, speaker, askUser, userPersona, userRelationship, targeted, worldviewDirective, lorebookStaticHead),
        )
        // 末尾 user 轮（不落库）：显式的发言边界。没有它，第二个 speaker 的请求以
        // assistant 结尾，模型按续写沿用上一成员的语气/身份——串人设的确定性根因。
        val turnBoundary = ChatMessage(
            role = "user",
            content = buildTurnBoundary(speaker, targeted, askUser),
        )
        return buildList {
            add(systemMessage)
            if (lorebookTailMessages.isEmpty()) {
                addAll(mappedHistory)
            } else {
                // 动态世界书插在最后一条历史 user 消息之后、末尾指令之前（贴近对话、不触碰头部缓存）
                val lastUserIdx = mappedHistory.indexOfLast { it.role == "user" }
                if (lastUserIdx >= 0) {
                    addAll(mappedHistory.take(lastUserIdx + 1))
                    addAll(lorebookTailMessages)
                    addAll(mappedHistory.drop(lastUserIdx + 1))
                } else {
                    addAll(mappedHistory)
                    addAll(lorebookTailMessages)
                }
            }
            add(turnBoundary)
        }
    }

    /** 末尾 user 轮内容：点名当前 speaker，重申只以其身份输出。 */
    private fun buildTurnBoundary(speaker: Character, targeted: Boolean, askUser: Boolean): String = buildString {
        append("现在轮到【").append(speaker.name).append("】发言。")
        if (targeted) {
            append("用户这条消息 @ 了你，是专门对你说的，请务必回应。")
        }
        if (askUser) {
            append("请以「").append(speaker.name).append("」的身份直接向用户提问。")
        } else {
            append("请只以「").append(speaker.name).append("」的身份输出它要说的话本身：不要角色名前缀、不要引号、不要任何解释；不要代替其他成员说话。")
        }
    }

    fun buildSystemPrompt(
        members: List<Character>,
        speaker: Character,
        askUser: Boolean,
        userPersona: String? = null,
        userRelationship: String? = null,
        targeted: Boolean = false,
        worldviewDirective: String? = null,
        lorebookStaticHead: String = "",
    ): String = buildString {
        append("这是一个角色群聊。你本次只能扮演「").append(speaker.name).append("」。其他成员的信息仅供参考，你绝不能代替他们说话或输出他们的内容。\n")
        // 当前 speaker：完整人设，唯一可执行身份
        append("【你的身份】\n")
        append("- ").append(speaker.name).append("（").append(speaker.role).append("）：")
        append(speaker.systemPrompt.trim())
        append("\n")
        // 其他成员：只读参考（截断），明确不可扮演
        append("【群内其他成员（仅了解，不可扮演）】\n")
        members.filter { it.id != speaker.id }.forEach { m ->
            append("- ").append(m.name).append("（").append(m.role).append("）：")
            append(m.systemPrompt.take(AppConfig.GroupChat.PERSONA_MAX_CHARS))
            append("\n")
        }
        // 世界观（GROUP 目标）注入：全员信息之后、对话规则之前
        if (!worldviewDirective.isNullOrBlank()) {
            append(worldviewDirective)
            append("\n")
        }
        // 世界书静态头（constant 条目，只随条目编辑变化）：世界观之后、对话规则之前
        if (lorebookStaticHead.isNotBlank()) {
            append(lorebookStaticHead)
            append("\n")
        }
        append("对话规则：\n")
        append("- user 发言是用户说的。\n")
        append("- assistant 消息均以「名字：」开头，表示该成员发言。\n")
        append("- 你只能以「").append(speaker.name).append("」的身份说话，不得模仿/扮演任何其他成员。\n")
        if (!userPersona.isNullOrBlank() || !userRelationship.isNullOrBlank()) {
            append("用户的信息：")
            if (!userPersona.isNullOrBlank()) append("人设：", userPersona.trim(), "。")
            if (!userRelationship.isNullOrBlank()) append("他与群成员的关系：", userRelationship.trim(), "。")
            append("\n")
        }
    }

    /**
     * 从用户消息文本里按出现顺序提取被 @ 的成员名（只看群成员；`@名字` 后须是边界，避免 `@名字X` 误匹配）。
     * 同名成员由调用方的 nameToId 解析（重复名不命中，见 GroupChatViewModel）。
     */
    fun extractMentions(text: String, memberNames: List<String>): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        memberNames.forEach { name ->
            var idx = text.indexOf("@$name")
            while (idx >= 0) {
                val after = idx + 1 + name.length
                val boundaryOk = after >= text.length || !text[after].isLetterOrDigit()
                if (boundaryOk) found.add(idx to name)
                idx = text.indexOf("@$name", idx + 1)
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * 模型回复的结构化解析结果。[parseSpeakerResponse] 产出：
     * - [Ok]：无前缀或正确的前缀，[Ok.text] 已剥前缀与包裹引号；
     * - [ForeignSpeaker]：输出了**其他成员**的名字前缀——不得静默归属当前 speaker，
     *   调用方应跳过/重试，绝不落库为当前 speaker。
     */
    sealed interface SpeakerResponseParse {
        data class Ok(val text: String) : SpeakerResponseParse
        data class ForeignSpeaker(val detectedName: String) : SpeakerResponseParse
    }

    /**
     * 只剥 [expected] 名字的前缀；检测到其他成员前缀返回 [SpeakerResponseParse.ForeignSpeaker]
     * （保留原文，便于诊断）。旧 [stripSpeakerPrefix] 会静默剥任意成员名——那是「A 说 C 的话」
     * 被无声入库的直接原因，本函数取代其调用点语义。
     */
    fun parseSpeakerResponse(text: String, expected: String, memberNames: List<String>): SpeakerResponseParse {
        // 先去掉整段包裹引号，再判定名字前缀；否则「C：内容」会绕过 foreign 检测。
        var result = text.trim()
        if (result.length >= 2 && ((result.startsWith("\"") && result.endsWith("\"")) ||
                (result.startsWith("「") && result.endsWith("」")))
        ) {
            result = result.substring(1, result.length - 1).trim()
        }
        // 先判 foreign（更长的成员名优先匹配，避免短名误吞长名前缀）。
        val sortedNames = memberNames.filter { it != expected }.sortedByDescending { it.length }
        for (name in sortedNames) {
            for (colon in listOf("：", ":")) {
                if (result.startsWith(name + colon)) {
                    return SpeakerResponseParse.ForeignSpeaker(name)
                }
            }
        }
        // 只剥 expected 前缀；重复一层仅用于兼容「A："正文"」这类输出。
        repeat(2) {
            for (colon in listOf("：", ":")) {
                val prefix = "$expected$colon"
                if (result.startsWith(prefix)) {
                    result = result.removePrefix(prefix).trim()
                }
            }
        }
        return SpeakerResponseParse.Ok(result.trim())
    }

    /**
     * 兼容入口：仅剥 [expectedSpeakerName] 前缀与包裹引号（旧签名收窄到单名），
     * 供不需要区分 foreign 的调用点使用。
     */
    fun stripSpeakerPrefix(text: String, expectedSpeakerName: String): String =
        when (val r = parseSpeakerResponse(text, expectedSpeakerName, listOf(expectedSpeakerName))) {
            is SpeakerResponseParse.Ok -> r.text
            is SpeakerResponseParse.ForeignSpeaker -> text.trim() // 单名模式下不可能命中，防御返回原文
        }
}
