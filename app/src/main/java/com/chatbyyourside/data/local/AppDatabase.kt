package com.chatbyyourside.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.data.model.MessageCompletionState
import kotlinx.coroutines.flow.Flow

/**
 * 会话实体
 * 每个角色可有多个会话；每个会话有独立的消息历史与模型上下文。
 * 按 (characterId, updatedAt) 索引以便「按角色列出 + 最近活跃在前」。
 */
@Entity(
    tableName = "conversation",
    indices = [Index(value = ["characterId", "updatedAt"])]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * Seedance 自动视频开关存储值（0/1，默认 0）。
     * v4->v5 迁移新增列，旧行默认关闭；新会话由 Repository 落默认值。
     */
    val autoVideoEnabled: Boolean = false,
    /**
     * 是否群聊会话（0/1，默认 0）。群聊 = 一行 `characterId = "group_chat"` 的 conversation，
     * 消息复用 chat_history（每行 characterId 记发言人）。
     * v5->v6 迁移新增列，旧行默认 0。
     */
    val isGroup: Boolean = false,
    /**
     * 群成员角色 id 列表（JSON 数组字符串；非群聊恒为空串）。
     * v5->v6 迁移新增列，旧行默认空串。
     */
    val memberIdsJson: String = "",
    /**
     * 群封面图 `file://` 路径（仅群聊有意义；null=未设置）。
     * 多群聊（v7）引入：一个群 = 一行带 isGroup=1 的 conversation，可设名称（title）与封面。
     */
    val coverImagePath: String? = null,
)

/**
 * 聊天记录实体
 * 按 conversationId 分桶，每个会话最多 MAX_HISTORY_PER_CONVERSATION 条。
 */
@Entity(
    tableName = "chat_history",
    indices = [Index(value = ["conversationId", "timestamp"])]
)
data class ChatHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,        // 冗余保留：便于按角色整体清理 / 审计
    val conversationId: Long,       // 所属会话
    val role: String,               // "user" | "assistant"
    val content: String,
    val imagesJson: String = "",    // JSON 数组
    val filesJson: String = "",     // JSON 数组
    val fileNamesJson: String = "", // JSON 数组
    val timestamp: Long,
    /**
     * 模型可见原始文本（Task 3）。本地助手消息存原始版本，重放历史时优先取它喂回模型，保证 KV 前缀精确；
     * 旧库行 / 用户消息 / 云端消息为 null，调用方回退 [content]。v2->v3 迁移新增列，默认 null。
     */
    val modelContent: String? = null,
    /**
     * 消息完成状态存储键（Task 6）：本地助手消息用户停止时记录；默认 'complete'。
     * v3->v4 迁移新增列，旧行回退 COMPLETE。
     */
    val completionState: String = MessageCompletionState.COMPLETE.storageKey,
)

@Dao
interface ChatDao {

    // 普通会话取最新 N 条（DESC）再由 Repository 反转为 ASC 显示；特殊邂逅会话不受
    // 100 条窗口限制。这里用 EXISTS 直接在 DAO SQL 层分流，避免 UI/Repository 漏掉保护。
    @Query(
        "SELECT * FROM chat_history WHERE conversationId = :conversationId " +
            "ORDER BY timestamp DESC " +
            "LIMIT (CASE WHEN EXISTS (SELECT 1 FROM special_event " +
            "WHERE conversationId = :conversationId) THEN 2147483647 ELSE ${AppConfig.MAX_HISTORY_PER_CONVERSATION} END)"
    )
    fun getHistory(conversationId: Long): Flow<List<ChatHistoryEntity>>

    @Query(
        "SELECT * FROM chat_history WHERE conversationId = :conversationId " +
            "ORDER BY timestamp DESC " +
            "LIMIT (CASE WHEN EXISTS (SELECT 1 FROM special_event " +
            "WHERE conversationId = :conversationId) THEN 2147483647 ELSE ${AppConfig.MAX_HISTORY_PER_CONVERSATION} END)"
    )
    suspend fun getHistoryList(conversationId: Long): List<ChatHistoryEntity>

    /** 导出使用：按时间正序读取该会话全部仍保存在数据库中的消息，不受 UI 历史窗口限制。 */
    @Query("SELECT * FROM chat_history WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getAllHistoryList(conversationId: Long): List<ChatHistoryEntity>

    /** DAO 层事件判定；会话/消息的删除与裁剪都必须依赖此查询。 */
    @Query("SELECT EXISTS(SELECT 1 FROM special_event WHERE conversationId = :conversationId)")
    suspend fun isSpecialEventConversation(conversationId: Long): Boolean

    @Insert
    suspend fun insert(entity: ChatHistoryEntity): Long

    @Query(
        "DELETE FROM chat_history WHERE conversationId = :conversationId AND NOT EXISTS (" +
            "SELECT 1 FROM special_event WHERE special_event.conversationId = chat_history.conversationId" +
            ")"
    )
    suspend fun clearHistory(conversationId: Long): Int

    /** 普通 UI 删除入口；被特殊邂逅引用的会话永远不受此入口删除。 */
    @Query(
        "DELETE FROM chat_history WHERE id = :id AND NOT EXISTS (" +
            "SELECT 1 FROM special_event WHERE special_event.conversationId = chat_history.conversationId" +
            ")"
    )
    suspend fun deleteById(id: Long): Int

    /**
     * 仅供发送失败回滚的受控入口。调用方必须只传入本次刚创建的用户消息 id；不暴露给普通 UI。
     * 回滚不能走 [deleteById]，否则特殊邂逅消息会被保护逻辑拦截。
     */
    @Query("DELETE FROM chat_history WHERE id = :id")
    suspend fun forceDeleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM chat_history WHERE conversationId = :conversationId")
    suspend fun count(conversationId: Long): Int

    /** 删除最旧的记录；特殊会话由 DAO 层直接跳过，防止任何调用方绕过 insertAndTrim。 */
    @Query(
        "DELETE FROM chat_history WHERE conversationId = :conversationId AND id IN (" +
            "SELECT id FROM chat_history WHERE conversationId = :conversationId " +
            "ORDER BY timestamp ASC LIMIT :limit)"
    )
    suspend fun deleteOldest(conversationId: Long, limit: Int): Int

    @Transaction
    suspend fun trimOldest(conversationId: Long, limit: Int): Int {
        if (isSpecialEventConversation(conversationId)) return 0
        return deleteOldest(conversationId, limit)
    }

    /**
     * 原子地插入并修剪：普通会话保持最多 N 条，特殊邂逅会话全量保留。
     * Flow 只在事务提交后 emit，避免插入/裁剪中间态。
     */
    @Transaction
    suspend fun insertAndTrim(conversationId: Long, entity: ChatHistoryEntity): Long {
        val id = insert(entity)
        if (!isSpecialEventConversation(conversationId)) {
            val c = count(conversationId)
            if (c > AppConfig.MAX_HISTORY_PER_CONVERSATION) {
                trimOldest(conversationId, c - AppConfig.MAX_HISTORY_PER_CONVERSATION)
            }
        }
        return id
    }
}

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(entity: ConversationEntity): Long

    @Query("UPDATE conversation SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long)

    /** 仅刷新 updatedAt（发消息后把会话顶到列表最前） */
    @Query("UPDATE conversation SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    /** 更新会话的 Seedance 自动视频开关；返回受影响行数（0 = 会话不存在）。 */
    @Query("UPDATE conversation SET autoVideoEnabled = :enabled WHERE id = :id")
    suspend fun updateAutoVideoEnabled(id: Long, enabled: Boolean): Int

    @Query("SELECT * FROM conversation WHERE characterId = :characterId AND NOT EXISTS (SELECT 1 FROM special_event WHERE special_event.conversationId = conversation.id) ORDER BY updatedAt DESC")
    fun observeByCharacter(characterId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE characterId = :characterId AND NOT EXISTS (SELECT 1 FROM special_event WHERE special_event.conversationId = conversation.id) ORDER BY updatedAt DESC")
    suspend fun listByCharacter(characterId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    /** DAO 层事件判定；getById 故意保留事件会话，供回忆路径读取。 */
    @Query("SELECT EXISTS(SELECT 1 FROM special_event WHERE conversationId = :conversationId)")
    suspend fun isSpecialEventConversation(conversationId: Long): Boolean

    /** 全部群聊会话（多群聊，最近活跃在前）。 */
    @Query("SELECT * FROM conversation WHERE isGroup = 1 ORDER BY updatedAt DESC")
    suspend fun listGroups(): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE isGroup = 1 ORDER BY updatedAt DESC")
    fun observeGroups(): Flow<List<ConversationEntity>>

    /** 标记某会话为群聊（create 后再调用，因为 create 只落普通字段）。 */
    @Query("UPDATE conversation SET isGroup = 1 WHERE id = :id")
    suspend fun markGroup(id: Long)

    /** 更新群成员列表（JSON 数组字符串）并刷新 updatedAt。 */
    @Query("UPDATE conversation SET memberIdsJson = :json, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateGroupMembers(id: Long, json: String, updatedAt: Long)

    /** 更新群封面路径并刷新 updatedAt（null=清除封面）。 */
    @Query("UPDATE conversation SET coverImagePath = :coverPath, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateGroupCover(id: Long, coverPath: String?, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM conversation WHERE characterId = :characterId")
    suspend fun count(characterId: String): Int

    @Query("DELETE FROM conversation WHERE id = :id AND NOT EXISTS (SELECT 1 FROM special_event WHERE special_event.conversationId = conversation.id)")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM chat_history WHERE conversationId = :conversationId AND NOT EXISTS (SELECT 1 FROM special_event WHERE special_event.conversationId = :conversationId)")
    suspend fun deleteMessages(conversationId: Long): Int

    /**
     * 事务性删除会话：特殊邂逅会话返回 false 且不删除消息；普通会话先删消息，再删会话。
     */
    @Transaction
    suspend fun deleteConversation(id: Long): Boolean {
        if (isSpecialEventConversation(id)) return false
        deleteMessages(id)
        return delete(id) == 1
    }

    /** 清空全部聊天记录，仅删除没有 special_event 软关联的会话及消息。 */
    @Transaction
    suspend fun clearAllConversations() {
        deleteAllOrdinaryMessages()
        deleteAllOrdinaryConversations()
    }

    @Query(
        "DELETE FROM chat_history WHERE conversationId IN (" +
            "SELECT conversation.id FROM conversation WHERE NOT EXISTS (" +
            "SELECT 1 FROM special_event WHERE special_event.conversationId = conversation.id))"
    )
    suspend fun deleteAllOrdinaryMessages(): Int

    @Query("DELETE FROM conversation WHERE NOT EXISTS (SELECT 1 FROM special_event WHERE special_event.conversationId = conversation.id)")
    suspend fun deleteAllOrdinaryConversations(): Int
}

@Database(
    entities = [
        ChatHistoryEntity::class,
        ConversationEntity::class,
        SeedanceVideoEntity::class,
        CharacterAffinityEntity::class,
        CompanionWalletEntity::class,
        DailyCheckinEntity::class,
        DailyCheckinPromptEntity::class,
        GiftDefinitionEntity::class,
        GiftInventoryEntity::class,
        GiftHistoryEntity::class,
        SpecialEventEntity::class,
        AffinityRewardEntity::class,
        SpecialEventScriptEntity::class,
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun conversationDao(): ConversationDao
    abstract fun seedanceVideoDao(): SeedanceVideoDao
    abstract fun affinityDao(): AffinityDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 -> v3：为 chat_history 新增可空列 modelContent（模型可见原始文本）。
         *
         * 旧行该列为 null，调用方以 `modelContent ?: content` 兼容。此路径不再用
         * fallbackToDestructiveMigration——历史消息含不可重建的用户对话，破坏性迁移会清空全部聊天记录。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chat_history ADD COLUMN modelContent TEXT")
            }
        }

        /**
         * v3 -> v4：为 chat_history 新增非空列 completionState（消息完成状态，Task 6）。
         *
         * 默认 'complete'：旧历史消息全部解释为正常完成；不丢失任何历史与 modelContent。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE chat_history ADD COLUMN completionState TEXT NOT NULL DEFAULT 'complete'"
                )
            }
        }

        /**
         * v4 -> v5：Seedance 自动视频（Task 2）。
         *
         * - conversation 新增非空列 autoVideoEnabled（默认 0，旧会话自动视频关闭）；
         * - 新建 seedance_video 表（列集/顺序/类型与 [SeedanceVideoEntity] 完全一致，
         *   索引名遵循 Room 命名 `index_<表>_<列...>`，Room 打开迁移库时按此做 schema 校验）；
         * - 不声明到 conversation/chat_history 的级联外键：删除聊天/会话不级联删除视频任务。
         *
         * 不使用破坏性回退：聊天历史与既有会话数据必须原样保留。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE conversation ADD COLUMN autoVideoEnabled INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `seedance_video` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskUuid` TEXT NOT NULL, " +
                        "`triggerType` TEXT NOT NULL, " +
                        "`sourceConversationId` INTEGER NOT NULL, " +
                        "`sourceUserMessageId` INTEGER, " +
                        "`sourceAssistantMessageId` INTEGER NOT NULL, " +
                        "`characterIdSnapshot` TEXT NOT NULL, " +
                        "`characterNameSnapshot` TEXT NOT NULL, " +
                        "`characterRoleSnapshot` TEXT NOT NULL, " +
                        "`characterSystemPromptSnapshot` TEXT NOT NULL, " +
                        "`userTextSnapshot` TEXT NOT NULL, " +
                        "`assistantTextSnapshot` TEXT NOT NULL, " +
                        "`sceneDescriptionSnapshot` TEXT NOT NULL, " +
                        "`promptBaseUrlSnapshot` TEXT NOT NULL, " +
                        "`promptModelSnapshot` TEXT NOT NULL, " +
                        "`promptJson` TEXT, " +
                        "`finalPrompt` TEXT, " +
                        "`characterImageSourceSnapshot` TEXT NOT NULL, " +
                        "`backgroundImageSourceSnapshot` TEXT, " +
                        "`characterImagePath` TEXT, " +
                        "`characterImageMime` TEXT, " +
                        "`characterImageSha256` TEXT, " +
                        "`backgroundImagePath` TEXT, " +
                        "`backgroundImageMime` TEXT, " +
                        "`backgroundImageSha256` TEXT, " +
                        "`modelVariant` TEXT NOT NULL, " +
                        "`resolution` TEXT NOT NULL, " +
                        "`ratio` TEXT NOT NULL, " +
                        "`durationSeconds` INTEGER NOT NULL, " +
                        "`generateAudio` INTEGER NOT NULL, " +
                        "`watermark` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`remoteStatus` TEXT, " +
                        "`generationAttempt` INTEGER NOT NULL, " +
                        "`submissionAttemptId` TEXT, " +
                        "`submissionStartedAt` INTEGER, " +
                        "`requestFingerprint` TEXT, " +
                        "`remoteTaskId` TEXT, " +
                        "`remoteVideoUrl` TEXT, " +
                        "`remoteVideoUrlObservedAt` INTEGER, " +
                        "`remoteVideoUrlExpiresAt` INTEGER, " +
                        "`remoteRequestId` TEXT, " +
                        "`previousRemoteTasksJson` TEXT NOT NULL, " +
                        "`localVideoPath` TEXT, " +
                        "`videoMime` TEXT, " +
                        "`videoByteSize` INTEGER, " +
                        "`videoSha256` TEXT, " +
                        "`downloadedAt` INTEGER, " +
                        "`automaticRetryCount` INTEGER NOT NULL, " +
                        "`nextRetryAt` INTEGER, " +
                        "`errorStage` TEXT, " +
                        "`errorCode` TEXT, " +
                        "`errorMessage` TEXT, " +
                        "`retryDisposition` TEXT, " +
                        "`requiresCostConfirmation` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL" +
                        ")"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_seedance_video_sourceAssistantMessageId_triggerType` " +
                        "ON `seedance_video` (`sourceAssistantMessageId`, `triggerType`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_seedance_video_sourceConversationId_createdAt` " +
                        "ON `seedance_video` (`sourceConversationId`, `createdAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_seedance_video_state_nextRetryAt` " +
                        "ON `seedance_video` (`state`, `nextRetryAt`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_seedance_video_remoteTaskId` " +
                        "ON `seedance_video` (`remoteTaskId`)"
                )
            }
        }

        /**
         * v5 -> v6：群聊（Task：多人角色同群聊天，仅云端可用）。
         *
         * - conversation 新增非空列 isGroup（默认 0）与 memberIdsJson（默认空串，JSON 数组）。
         *   群聊 = 一行 characterId = "group_chat" 的 conversation + 复用 chat_history（每行
         *   characterId 记发言人），故无需新表。
         * - 不声明级联外键（与 seedance_video 一致：删除普通会话不清群聊消息）。
         *
         * 不使用破坏性回退：聊天历史与既有会话数据必须原样保留。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE conversation ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE conversation ADD COLUMN memberIdsJson TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v6 -> v7：多群聊（群列表 + 群封面）。
         *
         * - conversation 新增可空列 coverImagePath（群封面 file:// 路径；旧群行 null=无封面）。
         * - 多群 = 多行 isGroup=1 的 conversation（名称复用 title 列）。
         * 不使用破坏性回退。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE conversation ADD COLUMN coverImagePath TEXT"
                )
            }
        }

        /**
         * v7 -> v8：好感度/羁绊系统（签到钱包、礼物经济、特殊邂逅、奖励账本、自定义剧情脚本）。
         *
         * 全部为新建表（非破坏），不触碰 conversation / chat_history / seedance_video。
         * 目标版此前从未有这些表，把源增量迁移链（7→11）合并为单条迁移：
         * 含 thankYouText（源 8→9）、openingMessageId（源 10→11）字段的最终形态。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `character_affinity` (`characterId` TEXT NOT NULL, `value` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`characterId`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `companion_wallet` (`id` INTEGER NOT NULL, `balance` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_checkin` (`dayKey` TEXT NOT NULL, `claimedAt` INTEGER NOT NULL, PRIMARY KEY(`dayKey`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_checkin_prompt` (`dayKey` TEXT NOT NULL, `shownAt` INTEGER NOT NULL, PRIMARY KEY(`dayKey`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gift_definition` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `imagePath` TEXT NOT NULL, `price` INTEGER NOT NULL, `affinityGain` REAL NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gift_inventory` (`giftId` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`giftId`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gift_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `characterId` TEXT NOT NULL, `giftId` INTEGER NOT NULL, `giftName` TEXT NOT NULL, `giftDescription` TEXT NOT NULL, `giftImagePath` TEXT NOT NULL, `price` INTEGER NOT NULL, `affinityGain` REAL NOT NULL, `sentAt` INTEGER NOT NULL, `conversationId` INTEGER NOT NULL, `thankYouText` TEXT NOT NULL)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_gift_history_characterId_sentAt` ON `gift_history` (`characterId`, `sentAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_gift_history_giftId` ON `gift_history` (`giftId`)"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `special_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `characterId` TEXT NOT NULL, `threshold` INTEGER NOT NULL, `title` TEXT NOT NULL, `sceneKey` TEXT NOT NULL, `unlockedAt` INTEGER NOT NULL, `startedAt` INTEGER, `conversationId` INTEGER, `isRead` INTEGER NOT NULL, `openingMessageId` INTEGER)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_special_event_characterId_threshold` ON `special_event` (`characterId`, `threshold`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_special_event_conversationId` ON `special_event` (`conversationId`)"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `affinity_reward` (`sourceKey` TEXT NOT NULL, `characterId` TEXT NOT NULL, `amount` REAL NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`sourceKey`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `special_event_script` (`characterId` TEXT NOT NULL, `threshold` INTEGER NOT NULL, `title` TEXT NOT NULL, `scene` TEXT NOT NULL, `opening` TEXT NOT NULL, `systemPrompt` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`characterId`, `threshold`))"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // 双重检查锁定：避免两个并发首次调用各建一个 RoomDatabase 实例，
                // 否则先到的调用方会持有孤儿实例，其 Flow 观察者收不到后续写入通知。
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rhodes_chat.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { INSTANCE = it }
            }
        }
    }
}
