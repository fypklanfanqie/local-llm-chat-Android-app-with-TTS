package com.chatbyyourside.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 特殊邂逅回忆保护的 Room 仪器测试。
 *
 * 重点验证保护发生在 DAO/事务层，而不是依赖 Chat UI：事件会话不参与普通删除与
 * 100 条裁剪，普通会话仍保留原有删除/裁剪语义。Room 版本固定为当前 v8，
 * 不引入迁移测试或新 schema。
 */
@RunWith(AndroidJUnit4::class)
class SpecialEventConversationDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun specialEventHistory_keepsOpeningAndReadsMoreThanOneHundredRows() = runBlocking {
        val conversationId = insertConversation("event-character")
        db.affinityDao().insertSpecialEvent(
            SpecialEventEntity(
                characterId = "event-character",
                threshold = 50,
                title = "回忆",
                sceneKey = "event-character#50",
                unlockedAt = 1L,
                conversationId = conversationId,
            ),
        )

        repeat(101) { index ->
            db.chatDao().insertAndTrim(
                conversationId,
                ChatHistoryEntity(
                    characterId = "event-character",
                    conversationId = conversationId,
                    role = if (index == 0) "assistant" else "user",
                    content = if (index == 0) "opening" else "message-$index",
                    timestamp = index.toLong() + 1L,
                ),
            )
        }

        val history = db.chatDao().getHistoryList(conversationId)
        assertEquals(101, history.size)
        assertEquals("opening", history.minBy { it.timestamp }.content)
        assertEquals(101, db.chatDao().count(conversationId))
    }

    @Test
    fun ordinaryHistory_stillTrimsToOneHundredRows() = runBlocking {
        val conversationId = insertConversation("ordinary-character")
        repeat(101) { index ->
            db.chatDao().insertAndTrim(
                conversationId,
                ChatHistoryEntity(
                    characterId = "ordinary-character",
                    conversationId = conversationId,
                    role = "user",
                    content = "message-$index",
                    timestamp = index.toLong() + 1L,
                ),
            )
        }

        val history = db.chatDao().getHistoryList(conversationId)
        assertEquals(100, history.size)
        assertTrue(history.none { it.content == "message-0" })
        assertTrue(history.any { it.content == "message-100" })
    }

    @Test
    fun specialEventDeleteAndClearAll_areNoOps_butOrdinaryRowsRemainDeletable() = runBlocking {
        val eventConversationId = insertConversation("event-character")
        val ordinaryConversationId = insertConversation("ordinary-character")
        db.affinityDao().insertSpecialEvent(
            SpecialEventEntity(
                characterId = "event-character",
                threshold = 50,
                title = "回忆",
                sceneKey = "event-character#50",
                unlockedAt = 1L,
                conversationId = eventConversationId,
            ),
        )
        val eventMessageId = db.chatDao().insert(
            ChatHistoryEntity("event-character", eventConversationId, "assistant", "opening", timestamp = 1L),
        )
        val ordinaryMessageId = db.chatDao().insert(
            ChatHistoryEntity("ordinary-character", ordinaryConversationId, "user", "ordinary", timestamp = 1L),
        )

        assertFalse(db.conversationDao().deleteConversation(eventConversationId))
        assertEquals(0, db.chatDao().deleteById(eventMessageId))
        assertEquals(1, db.chatDao().deleteById(ordinaryMessageId))
        assertTrue(db.conversationDao().deleteConversation(ordinaryConversationId))

        // 重新写一条普通消息，验证 clearAll 只清普通会话/消息。
        val ordinaryAgain = insertConversation("ordinary-character")
        db.chatDao().insert(ChatHistoryEntity("ordinary-character", ordinaryAgain, "user", "ordinary-2", timestamp = 2L))
        db.conversationDao().clearAllConversations()

        assertTrue(db.conversationDao().getById(eventConversationId) != null)
        assertEquals(1, db.chatDao().count(eventConversationId))
        assertEquals(0, db.conversationDao().getById(ordinaryAgain)?.let { 1 } ?: 0)
        assertEquals(0, db.chatDao().count(ordinaryAgain))
    }

    @Test
    fun controlledForceDelete_canRollbackJustCreatedEventMessage() = runBlocking {
        val conversationId = insertConversation("event-character")
        db.affinityDao().insertSpecialEvent(
            SpecialEventEntity(
                characterId = "event-character",
                threshold = 50,
                title = "回忆",
                sceneKey = "event-character#50",
                unlockedAt = 1L,
                conversationId = conversationId,
            ),
        )
        val messageId = db.chatDao().insert(
            ChatHistoryEntity("event-character", conversationId, "user", "rollback", timestamp = 1L),
        )

        assertEquals(1, db.chatDao().forceDeleteById(messageId))
        assertEquals(0, db.chatDao().count(conversationId))
    }

    private suspend fun insertConversation(characterId: String): Long =
        db.conversationDao().insert(
            ConversationEntity(
                characterId = characterId,
                title = "对话",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
}
