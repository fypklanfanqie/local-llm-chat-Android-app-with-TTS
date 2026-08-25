package com.chatbyyourside.provider.local

import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.util.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地历史回放的深度思考剥离契约测试。
 *
 * [LocalChatProvider.chatTyped] 在规划窗口前把 assistant 消息规范化为
 * `content = MarkdownParser.stripThink(modelContent ?: content)`、`modelContent = null`——
 * 思考过程不进上下文（口径对齐云端 ChatViewModel stripThink）。本测试锁定该口径依赖的
 * stripThink 语义与实测 token（measuredAssistantText）比对逻辑的一致性。
 */
class ThinkStripReplayTest {

    /** 规范化逻辑与 LocalChatProvider 内实现保持同一表达式；此 helper 即其镜像。 */
    private fun normalize(msg: ChatMessage): ChatMessage = when (msg.role) {
        "assistant" -> msg.copy(content = MarkdownParser.stripThink(msg.modelContent ?: msg.content), modelContent = null)
        else -> msg
    }

    @Test
    fun assistantRawWithClosedThinkStripsToBody() {
        val msg = ChatMessage(role = "assistant", content = "正文", modelContent = "<think>推理过程</think>正文")
        val normalized = normalize(msg)
        assertEquals("正文", normalized.content)
        assertEquals(null, normalized.modelContent)
    }

    @Test
    fun unclosedThinkDropsTail() {
        val msg = ChatMessage(role = "assistant", content = "", modelContent = "<think>只写到一半的推理")
        assertEquals("", normalize(msg).content)
    }

    @Test
    fun nullModelContentFallsBackToContent() {
        val msg = ChatMessage(role = "assistant", content = "纯正文回复")
        assertEquals("纯正文回复", normalize(msg).content)
    }

    @Test
    fun multipleThinkBlocksAllRemoved() {
        val raw = "<think>第一段</think>开头<think>第二段</think>结尾"
        val msg = ChatMessage(role = "assistant", content = "开头结尾", modelContent = raw)
        assertEquals("开头结尾", normalize(msg).content)
    }

    @Test
    fun userMessageIsNoOp() {
        val msg = ChatMessage(role = "user", content = "你好")
        assertEquals("你好", normalize(msg).content)
    }

    /** 实测 token 比对：上一轮原始输出先剥离再与回放文本匹配，保证 knownTokenCounts 命中。 */
    @Test
    fun measuredRawTextMatchesAfterStrip() {
        val raw = "<think>思考</think>最终回答"
        val stored = ChatMessage(role = "assistant", content = "最终回答", modelContent = raw)
        val measuredText = MarkdownParser.stripThink(raw)
        val replayed = normalize(stored)
        // LocalChatProvider 的比对式：raw(=modelContent ?: content) == stripThink(measuredAssistantText)
        val lookupKey = replayed.modelContent ?: replayed.content
        assertEquals(measuredText, lookupKey)
    }
}
