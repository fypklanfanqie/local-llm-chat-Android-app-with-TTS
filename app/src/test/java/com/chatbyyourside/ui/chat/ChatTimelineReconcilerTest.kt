package com.chatbyyourside.ui.chat

import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.data.model.DisplayMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatTimelineReconciler] 协调契约测试（Task 2）。
 *
 * 核心断言：Room 在精确行 ID 确认前必须保留乐观完成消息，确认后只显示一次且绝不重复；
 * 不同会话的 pending 不得串台；流式气泡在生成期间持续保留。
 */
class ChatTimelineReconcilerTest {

    private fun user(id: Long?, content: String) = ChatMessage(
        role = "user", content = content, databaseId = id,
    )

    private fun assistant(id: Long?, content: String, modelContent: String? = null) = ChatMessage(
        role = "assistant", content = content, modelContent = modelContent, databaseId = id,
    )

    private fun pending(conv: Long, dbId: Long, content: String) = PendingFinal(
        conversationId = conv,
        databaseId = dbId,
        message = DisplayMessage(
            id = "msg-$dbId",
            role = "assistant",
            content = content,
            segments = emptyList(),
            sender = "AI",
        ),
    )

    private fun streaming(id: String = "streaming", content: String = "思考中") = DisplayMessage(
        id = id, role = "streaming", content = content, segments = emptyList(), sender = "AI", isStreaming = true,
    )

    @Test
    fun staleUserOnlyHistory_preservesPendingAssistant() {
        // Room 仍只有用户消息（延迟/旧快照），UI 已乐观展示完成回复：不得被覆盖。
        val history = listOf(user(id = 1, content = "你好"))
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = pending(conv = 1L, dbId = 99L, content = "这是回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertTrue("pending 回复被延迟快照覆盖", result.messages.any { it.id == "msg-99" })
        assertFalse("Room 尚未确认行 99", result.pendingResolved)
        assertEquals(2, result.messages.size)
        assertFalse(result.showWelcome)
    }

    @Test
    fun roomAcknowledgesPending_showsOnceAndResolves() {
        val history = listOf(
            user(id = 1, content = "你好"),
            assistant(id = 99, content = "这是回答", modelContent = "raw"),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = pending(conv = 1L, dbId = 99L, content = "这是回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertTrue("Room 已回填但 pending 未清除", result.pendingResolved)
        assertEquals("msg-99 出现次数 != 1", 1, result.messages.count { it.id == "msg-99" })
        assertEquals(2, result.messages.size)
    }

    @Test
    fun identicalAssistantTexts_areDistinguishedByRowId() {
        // 两次回复文本完全相同，必须按行 ID 区分，不能因文本相等而误清 pending。
        val history = listOf(
            user(id = 1, content = "问1"),
            assistant(id = 2, content = "相同的回答"),
            user(id = 3, content = "问2"),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = pending(conv = 1L, dbId = 4, content = "相同的回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertFalse(result.pendingResolved)
        assertEquals(2, result.messages.count { it.content == "相同的回答" })
        assertTrue(result.messages.any { it.id == "msg-2" })
        assertTrue(result.messages.any { it.id == "msg-4" })
    }

    @Test
    fun pendingForAnotherConversation_isNeverMerged() {
        // 当前活跃会话 B，pending 属于 A：必须丢弃且标记清除，绝不串台。
        val history = listOf(user(id = 1, content = "B 的问题"))
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 2L,
            pendingFinal = pending(conv = 1L, dbId = 99L, content = "A 的回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertTrue("A 会话 pending 串入 B 会话", result.messages.none { it.id == "msg-99" })
        assertTrue("跨会话 pending 未标记清除", result.pendingResolved)
        assertEquals(1, result.messages.size)
    }

    @Test
    fun roomBackfillBeforeOptimisticReplace_doesNotDuplicate() {
        // Room 回填先于乐观替换：两者同一行 ID，只显示一次。
        val history = listOf(
            user(id = 1, content = "你好"),
            assistant(id = 99, content = "回答", modelContent = "raw"),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = pending(conv = 1L, dbId = 99L, content = "回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertEquals(1, result.messages.count { it.id == "msg-99" })
        assertEquals(2, result.messages.size)
        assertTrue(result.pendingResolved)
    }

    @Test
    fun streamingBubble_isPreservedDuringGeneration() {
        val history = listOf(user(id = 1, content = "你好"))
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = null,
            streaming = streaming(),
            showThink = true,
            characterName = "AI",
        )
        assertTrue("流式气泡未保留", result.messages.any { it.id == "streaming" })
        assertEquals("streaming 应为最后一条", "streaming", result.messages.last().id)
        assertFalse(result.showWelcome)
    }

    @Test
    fun emptyHistory_showsWelcomeOnlyWithoutPendingOrStreaming() {
        val result = ChatTimelineReconciler.reconcile(
            history = emptyList(),
            activeConversationId = 1L,
            pendingFinal = null,
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertTrue(result.showWelcome)
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun persistedMessages_useDatabaseIdAsStableKey() {
        val history = listOf(user(id = 1, content = "你好"))
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = null,
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertEquals("msg-1", result.messages.first().id)
    }

    @Test
    fun nonPersistedMessage_fallsBackToTimestampIndexKey() {
        // 无 databaseId 的消息（旧库行/纯内存构造）用 timestamp-index 兜底，保持稳定。
        val legacy = ChatMessage(role = "user", content = "旧消息", timestamp = 500)
        val result = ChatTimelineReconciler.reconcile(
            history = listOf(legacy),
            activeConversationId = 1L,
            pendingFinal = null,
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertEquals("msg-500-0", result.messages.first().id)
    }

    @Test
    fun showThinkToggle_doesNotDropUnacknowledgedPending() {
        // 深度思考开关触发重渲染时，pending 尚未被 Room 确认也必须保留。
        val history = listOf(user(id = 1, content = "你好"))
        val on = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = pending(1L, 99L, "回答"), streaming = null,
            showThink = true, characterName = "AI",
        )
        assertTrue(on.messages.any { it.id == "msg-99" })
        val off = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = pending(1L, 99L, "回答"), streaming = null,
            showThink = false, characterName = "AI",
        )
        assertTrue("关掉思考后 pending 丢失", off.messages.any { it.id == "msg-99" })
    }
}
