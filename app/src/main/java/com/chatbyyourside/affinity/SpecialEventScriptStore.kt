package com.chatbyyourside.affinity

import com.chatbyyourside.data.local.AppDatabase
import com.chatbyyourside.data.local.SpecialEventScriptEntity
import com.chatbyyourside.data.model.Character
import com.chatbyyourside.data.model.SpecialEventScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 自定义角色特殊邂逅脚本仓：把用户编辑的剧情脚本存到 `special_event_script` 表，
 * 并在解析时优先于目录/保底返回。
 *
 * 规则（定制需求）：
 * - **自定义角色**（[Character.isCustom]）：有存库脚本则用之，否则回退到目录/离线保底；
 * - **内置角色**：一律走目录/离线保底，不落库、不可编辑（此处不写入即天然只读）。
 */
class SpecialEventScriptStore(
    private val database: AppDatabase,
    private val catalog: SpecialEventCatalog,
) {
    private val dao get() = database.affinityDao()

    fun observeForCharacter(characterId: String): Flow<List<SpecialEventScriptEntity>> =
        dao.observeScriptsForCharacter(characterId)

    suspend fun get(characterId: String, threshold: Int): SpecialEventScriptEntity? =
        dao.getScript(characterId, threshold)

    suspend fun upsert(entity: SpecialEventScriptEntity) {
        dao.upsertScript(entity)
    }

    suspend fun delete(characterId: String, threshold: Int) {
        dao.deleteScript(characterId, threshold)
    }

    /**
     * 解析某角色某档位的剧情脚本：内置走目录/保底；自定义优先存库脚本。
     */
    suspend fun resolve(character: Character, threshold: Int): SpecialEventScript {
        if (character.isCustom) {
            dao.getScript(character.id, threshold)?.let { return it.toScript() }
        }
        return catalog.eventFor(character.id, threshold)
    }
}

internal fun SpecialEventScriptEntity.toScript(): SpecialEventScript = SpecialEventScript(
    characterId = characterId,
    threshold = threshold,
    title = title,
    scene = scene,
    opening = opening,
    systemPrompt = systemPrompt,
    memorySummary = "",
    toneTags = emptyList(),
)
