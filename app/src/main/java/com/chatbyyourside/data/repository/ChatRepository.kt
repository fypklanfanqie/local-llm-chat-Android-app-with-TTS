package com.chatbyyourside.data.repository

import com.chatbyyourside.data.local.ChatDao
import com.chatbyyourside.data.local.ChatHistoryEntity
import com.chatbyyourside.data.model.AttachedFile
import com.chatbyyourside.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 聊天记录仓库
 * 按会话（conversationId）分桶读写消息；会话本身见 [ConversationRepository]。
 * 对应小程序 storage.getHistory / setHistory / clearHistory。
 */
class ChatRepository(private val dao: ChatDao) {

    fun getHistoryFlow(conversationId: Long): Flow<List<ChatMessage>> =
        dao.getHistory(conversationId).map { entities ->
            // DAO 返回最新 N 条（DESC），反转为 ASC 以便按时间正序展示
            entities.map { it.toMessage() }.asReversed()
        }

    suspend fun getHistory(conversationId: Long): List<ChatMessage> =
        dao.getHistoryList(conversationId).map { it.toMessage() }.asReversed()

    suspend fun addMessage(characterId: String, conversationId: Long, message: ChatMessage): Long {
        // 事务性插入 + 修剪，避免 Flow 在中间状态 emit（详见 ChatDao.insertAndTrim）
        return dao.insertAndTrim(conversationId, message.toEntity(characterId, conversationId))
    }

    /** 按 id 删除单条消息（发送失败回滚用） */
    suspend fun deleteMessage(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clearHistory(conversationId: Long) {
        dao.clearHistory(conversationId)
    }
}

// ===== 转换（顶层 internal，便于单测；纯函数无 Android 依赖）=====

/**
 * 实体 -> 领域消息。[modelContent] 原样还原；旧行该列为 null，由调用方 `modelContent ?: content` 兼容。
 */
internal fun ChatHistoryEntity.toMessage(): ChatMessage = ChatMessage(
    role = role,
    content = content,
    images = decodeStringList(imagesJson),
    files = decodeFileList(filesJson),
    fileNames = decodeStringList(fileNamesJson),
    timestamp = timestamp,
    modelContent = modelContent,
)

/**
 * 领域消息 -> 实体。[modelContent] 持久化（本地助手消息存原始文本）；用户消息/云端消息为 null。
 * 注：[ChatMessage.multimodalImages] 运行时字段不持久化（仅发送给 API）。
 */
internal fun ChatMessage.toEntity(characterId: String, conversationId: Long): ChatHistoryEntity = ChatHistoryEntity(
    characterId = characterId,
    conversationId = conversationId,
    role = role,
    content = content,
    imagesJson = encodeStringList(images),
    filesJson = encodeFileList(files),
    fileNamesJson = encodeStringList(fileNames),
    timestamp = timestamp,
    modelContent = modelContent,
)

/** 实体字段编解码用的 JSON（宽松：容忍历史行多余/缺失字段）。 */
private val entityJson = Json { ignoreUnknownKeys = true }

private fun encodeStringList(list: List<String>): String =
    if (list.isEmpty()) "" else entityJson.encodeToString(ListSerializer(String.serializer()), list)

private fun decodeStringList(s: String): List<String> =
    if (s.isBlank()) emptyList()
    else runCatching { entityJson.decodeFromString(ListSerializer(String.serializer()), s) }.getOrDefault(emptyList())

private fun encodeFileList(list: List<AttachedFile>): String =
    if (list.isEmpty()) "" else entityJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(AttachedFile.serializer()), list)

private fun decodeFileList(s: String): List<AttachedFile> =
    if (s.isBlank()) emptyList()
    else runCatching { entityJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AttachedFile.serializer()), s) }.getOrDefault(emptyList())
