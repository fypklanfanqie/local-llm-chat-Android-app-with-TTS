package com.chatbyyourside.affinity

import androidx.room.withTransaction
import com.chatbyyourside.data.local.AppDatabase
import com.chatbyyourside.data.local.toDomain
import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.data.model.ChatProviderType
import com.chatbyyourside.data.model.SpecialEvent
import com.chatbyyourside.data.repository.CharacterRepository
import com.chatbyyourside.data.repository.ChatRepository
import com.chatbyyourside.data.repository.ConversationRepository
import com.chatbyyourside.data.repository.SettingsRepository

sealed interface SpecialEventLaunchResult {
    data class Ready(
        val event: SpecialEvent,
        val opening: String,
    ) : SpecialEventLaunchResult
    data class Existing(val event: SpecialEvent) : SpecialEventLaunchResult
    /** 已有 conversationId 指向不存在的会话，已新建并重建 opening。 */
    data class Rebuilt(
        val event: SpecialEvent,
        val opening: String,
    ) : SpecialEventLaunchResult
    data object Missing : SpecialEventLaunchResult
}

class SpecialEventConversationCoordinator(
    private val database: AppDatabase,
    private val conversations: ConversationRepository,
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val catalog: SpecialEventCatalog,
    private val characters: CharacterRepository,
    private val scriptStore: SpecialEventScriptStore,
) {
    suspend fun launch(characterId: String, threshold: Int): SpecialEventLaunchResult {
        // 事务外解析角色（DataStore I/O 不进 Room 事务），供脚本解析使用。
        val character = characters.getNow(characterId)
        val result = database.withTransaction {
            val event = database.affinityDao().getSpecialEvent(characterId, threshold)
                ?: return@withTransaction SpecialEventLaunchResult.Missing
            val existingConversationId = event.conversationId
            if (existingConversationId != null && conversations.getById(existingConversationId) != null) {
                return@withTransaction SpecialEventLaunchResult.Existing(event.toDomain())
            }
            val script = if (character != null) {
                scriptStore.resolve(character, threshold)
            } else {
                catalog.eventFor(characterId, threshold)
            }
            val conversationId = conversations.create(characterId, script.title)
            val openingId = chats.addMessage(
                characterId,
                conversationId,
                ChatMessage(role = "assistant", content = script.opening),
            )
            val rebuilt = existingConversationId != null
            if (rebuilt) {
                android.util.Log.w(
                    "SpecialEventCoordinator",
                    "special event $characterId#$threshold had dangling conversationId=$existingConversationId; rebuilt conversationId=$conversationId",
                )
            }
            val updated = event.copy(
                title = script.title,
                sceneKey = SpecialEventCatalog.keyOf(characterId, threshold),
                startedAt = event.startedAt ?: System.currentTimeMillis(),
                conversationId = conversationId,
                isRead = true,
                openingMessageId = openingId,
            )
            database.affinityDao().updateSpecialEvent(updated)
            if (rebuilt) {
                SpecialEventLaunchResult.Rebuilt(updated.toDomain(), script.opening)
            } else {
                SpecialEventLaunchResult.Ready(updated.toDomain(), script.opening)
            }
        }
        val conversationId = when (result) {
            is SpecialEventLaunchResult.Ready -> result.event.conversationId
            is SpecialEventLaunchResult.Rebuilt -> result.event.conversationId
            is SpecialEventLaunchResult.Existing -> result.event.conversationId
            SpecialEventLaunchResult.Missing -> null
        }
        if (conversationId != null) {
            // 不在 Room 事务中触碰 DataStore：避免文件 I/O 把数据库写事务长时间占住。
            settings.setActiveCharacter(characterId)
            settings.setActiveConversation(characterId, conversationId)
            settings.setActiveProvider(ChatProviderType.CLOUD)
        }
        return result
    }

    suspend fun markRead(eventId: Long) {
        val event = database.affinityDao().getSpecialEventById(eventId) ?: return
        if (!event.isRead) database.affinityDao().updateSpecialEvent(event.copy(isRead = true))
    }
}
