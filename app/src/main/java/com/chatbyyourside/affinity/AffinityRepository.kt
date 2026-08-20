package com.chatbyyourside.affinity

import androidx.room.withTransaction
import com.chatbyyourside.data.local.AffinityRewardEntity
import com.chatbyyourside.data.local.AppDatabase
import com.chatbyyourside.data.local.CharacterAffinityEntity
import com.chatbyyourside.data.local.CompanionWalletEntity
import com.chatbyyourside.data.local.DailyCheckinEntity
import com.chatbyyourside.data.local.DailyCheckinPromptEntity
import com.chatbyyourside.data.local.GiftDefinitionEntity
import com.chatbyyourside.data.local.GiftHistoryEntity
import com.chatbyyourside.data.local.GiftInventoryEntity
import com.chatbyyourside.data.local.SpecialEventEntity
import com.chatbyyourside.data.local.toDomain
import com.chatbyyourside.data.model.Character
import com.chatbyyourside.data.model.CharacterAffinity
import com.chatbyyourside.data.model.CompanionWallet
import com.chatbyyourside.data.model.GiftDefinition
import com.chatbyyourside.data.model.GiftHistory
import com.chatbyyourside.data.model.GiftInventory
import com.chatbyyourside.data.model.SpecialEvent
import com.chatbyyourside.data.repository.CharacterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface AffinityRewardResult {
    data class Applied(
        val affinity: CharacterAffinity,
        val unlockedThresholds: List<Int>,
    ) : AffinityRewardResult
    data object AlreadyApplied : AffinityRewardResult
}

sealed interface CheckinResult {
    data class Claimed(val wallet: CompanionWallet) : CheckinResult
    data class AlreadyClaimed(val wallet: CompanionWallet) : CheckinResult
}

sealed interface GiftPurchaseResult {
    data class Purchased(val wallet: CompanionWallet, val inventory: GiftInventory) : GiftPurchaseResult
    data object InsufficientFunds : GiftPurchaseResult
    data object GiftMissing : GiftPurchaseResult
}

sealed interface GiftSendResult {
    data class Sent(
        val history: GiftHistory,
        val affinity: CharacterAffinity,
        val unlockedThresholds: List<Int>,
    ) : GiftSendResult
    data object InventoryEmpty : GiftSendResult
    data object GiftMissing : GiftSendResult
}

class AffinityRepository(
    private val database: AppDatabase,
    private val specialEventCatalog: SpecialEventCatalog,
    private val characters: CharacterRepository,
    private val scriptStore: SpecialEventScriptStore,
) {
    private val dao get() = database.affinityDao()

    fun observeAffinity(characterId: String): Flow<CharacterAffinity> =
        dao.observeAffinity(characterId).map { entity ->
            entity?.toDomain() ?: CharacterAffinity(characterId, 0f, 0L)
        }

    fun observeWallet(): Flow<CompanionWallet> = dao.observeWallet().map { entity ->
        entity?.toDomain() ?: CompanionWallet(0L, 0L)
    }

    fun observeCheckinClaimed(dayKey: String = todayKey()): Flow<Boolean> = dao.observeCheckinClaimed(dayKey)

    fun observeGifts(): Flow<List<GiftDefinition>> = dao.observeGifts().map { list -> list.map { it.toDomain() } }

    fun observeOwnedGifts(): Flow<List<OwnedGift>> = dao.observeOwnedGifts().map { rows ->
        rows.map { row ->
            OwnedGift(
                definition = GiftDefinition(row.id, row.name, row.description, row.imagePath, row.price, row.affinityGain, row.createdAt),
                inventory = GiftInventory(row.id, row.quantity, row.inventoryUpdatedAt),
            )
        }
    }

    fun observeGiftHistory(characterId: String): Flow<List<GiftHistory>> =
        dao.observeGiftHistory(characterId).map { list -> list.map { it.toDomain() } }

    fun observeSpecialEvents(characterId: String): Flow<List<SpecialEvent>> =
        dao.observeSpecialEvents(characterId).map { list -> list.map { it.toDomain() } }

    fun observeUnreadUnlockCount(characterId: String): Flow<Int> = dao.observeUnreadUnlockCount(characterId)

    fun observeUnreadEventCharacterIds(): Flow<Set<String>> =
        dao.observeUnreadEventCharacterIds().map { it.toSet() }

    suspend fun addChatAffinity(characterId: String, messageId: Long): AffinityRewardResult =
        addReward(characterId, CHAT_AFFINITY_GAIN, "chat:$messageId", "chat")

    suspend fun addVideoAffinity(characterId: String, videoId: Long): AffinityRewardResult =
        addReward(characterId, VIDEO_AFFINITY_GAIN, "video:$videoId", "video")

    suspend fun shouldShowDailyCheckinPrompt(dayKey: String = todayKey()): Boolean = database.withTransaction {
        if (dao.isCheckinClaimed(dayKey) || dao.hasShownCheckinPrompt(dayKey)) return@withTransaction false
        dao.insertCheckinPrompt(DailyCheckinPromptEntity(dayKey, System.currentTimeMillis())) != -1L
    }

    suspend fun claimDailyCheckin(dayKey: String = todayKey()): CheckinResult = database.withTransaction {
        val now = System.currentTimeMillis()
        val claimed = dao.claimCheckinIfAvailable(dayKey, now, DAILY_CHECKIN_REWARD)
        val wallet = (dao.getWallet() ?: CompanionWalletEntity(balance = 0L, updatedAt = now)).toDomain()
        if (claimed) CheckinResult.Claimed(wallet) else CheckinResult.AlreadyClaimed(wallet)
    }

    suspend fun createGift(name: String, description: String, imagePath: String, price: Long): GiftDefinition {
        val gain = requireNotNull(affinityGainForGiftPrice(price)) { "礼物价格必须在 5000 至 20000 金币的有效档位内" }
        require(name.isNotBlank()) { "礼物名称不能为空" }
        require(imagePath.isNotBlank()) { "请选择礼物图片" }
        val now = System.currentTimeMillis()
        val id = dao.insertGift(
            GiftDefinitionEntity(
                name = name.trim(),
                description = description.trim(),
                imagePath = imagePath,
                price = price,
                affinityGain = gain,
                createdAt = now,
            ),
        )
        return requireNotNull(dao.getGift(id)).toDomain()
    }

    suspend fun buyGift(giftId: Long): GiftPurchaseResult = database.withTransaction {
        val gift = dao.getGift(giftId) ?: return@withTransaction GiftPurchaseResult.GiftMissing
        val now = System.currentTimeMillis()
        val wallet = dao.getWallet() ?: CompanionWalletEntity(balance = 0L, updatedAt = now)
        if (wallet.balance < gift.price) return@withTransaction GiftPurchaseResult.InsufficientFunds
        val inventory = dao.getInventory(giftId) ?: GiftInventoryEntity(giftId, 0, now)
        dao.upsertWallet(wallet.copy(balance = wallet.balance - gift.price, updatedAt = now))
        val newInventory = inventory.copy(quantity = inventory.quantity + 1, updatedAt = now)
        dao.upsertInventory(newInventory)
        GiftPurchaseResult.Purchased((dao.getWallet() ?: error("钱包写入失败")).toDomain(), newInventory.toDomain())
    }

    suspend fun sendGift(characterId: String, giftId: Long, conversationId: Long): GiftSendResult {
        // 事务外解析角色（DataStore I/O 不进 Room 事务），供阈值解锁时解析剧情脚本。
        val character = characters.getNow(characterId)
        return database.withTransaction {
            val gift = dao.getGift(giftId) ?: return@withTransaction GiftSendResult.GiftMissing
            val now = System.currentTimeMillis()
            if (dao.decrementInventoryIfAvailable(giftId, now) != 1) return@withTransaction GiftSendResult.InventoryEmpty
            val historyId = dao.insertGiftHistory(
                GiftHistoryEntity(
                    characterId = characterId,
                    giftId = gift.id,
                    giftName = gift.name,
                    giftDescription = gift.description,
                    giftImagePath = gift.imagePath,
                    price = gift.price,
                    affinityGain = gift.affinityGain,
                    sentAt = now,
                    conversationId = conversationId,
                ),
            )
            val reward = applyRewardInTransaction(characterId, gift.affinityGain, "gift:$historyId", "gift", now, character)
            when (reward) {
                is AffinityRewardResult.Applied -> {
                    val history = requireNotNull(dao.getGiftHistory(historyId)).toDomain()
                    GiftSendResult.Sent(history, reward.affinity, reward.unlockedThresholds)
                }
                AffinityRewardResult.AlreadyApplied -> error("礼物奖励键冲突")
            }
        }
    }

    suspend fun saveGiftThankYouText(historyId: Long, text: String) {
        dao.updateGiftThankYouText(historyId, text.take(1_000))
    }

    private suspend fun addReward(
        characterId: String,
        amount: Float,
        sourceKey: String,
        source: String,
    ): AffinityRewardResult {
        val character = characters.getNow(characterId)
        return database.withTransaction {
            applyRewardInTransaction(characterId, amount, sourceKey, source, System.currentTimeMillis(), character)
        }
    }

    private suspend fun applyRewardInTransaction(
        characterId: String,
        amount: Float,
        sourceKey: String,
        source: String,
        now: Long,
        character: Character?,
    ): AffinityRewardResult {
        if (dao.insertReward(AffinityRewardEntity(sourceKey, characterId, amount, source, now)) == -1L) {
            return AffinityRewardResult.AlreadyApplied
        }
        val previous = dao.getAffinity(characterId) ?: CharacterAffinityEntity(characterId, 0f, now)
        val currentValue = clampAffinity(previous.value + amount)
        val unlocked = dao.unlockedThresholds(characterId).toSet()
        val thresholds = crossedAffinityThresholds(previous.value, currentValue, unlocked)
        dao.upsertAffinity(previous.copy(value = currentValue, updatedAt = now))
        thresholds.forEach { threshold ->
            val script = if (character != null) {
                scriptStore.resolve(character, threshold)
            } else {
                specialEventCatalog.eventFor(characterId, threshold)
            }
            dao.insertSpecialEvent(
                SpecialEventEntity(
                    characterId = characterId,
                    threshold = threshold,
                    title = script.title,
                    sceneKey = SpecialEventCatalog.keyOf(characterId, threshold),
                    unlockedAt = now,
                ),
            )
        }
        return AffinityRewardResult.Applied(CharacterAffinity(characterId, currentValue, now), thresholds)
    }

    companion object {
        /** 当天日期键（yyyy-MM-dd）：与问候配额等既有实现一致，避免 minSdk<26 无 java.time 的崩溃风险。 */
        private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        fun todayKey(): String = dayKeyFmt.format(Date(System.currentTimeMillis()))
    }
}

data class OwnedGift(
    val definition: GiftDefinition,
    val inventory: GiftInventory,
)
