package com.chatbyyourside.data.repository

import com.chatbyyourside.config.Characters
import com.chatbyyourside.data.model.Character
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 角色仓库：合并预设干员（Characters.ALL）与用户自定义角色。
 * 自定义角色持久化于 SettingsStore，可增删改 / 导入导出。
 */
class CharacterRepository(private val settings: SettingsRepository) {

    /** 自定义 + 预设角色（自定义在前，预设按展示顺序在后） */
    val characters: Flow<List<Character>> = settings.customCharacters.map { custom ->
        // getOrderedList() 已返回按展示顺序排好的 List<Character>，无需再用 ALL 重新索引
        // （旧写法 mapNotNull { Characters.ALL[it] } 中 it 是 Character，而 ALL 的 key 是 String，
        //  类型不匹配会导致编译错误）
        custom + Characters.getOrderedList()
    }

    suspend fun getNow(id: String): Character? {
        Characters.ALL[id]?.let { return it }
        // 使用超时保护：国产 ROM DataStore 文件 I/O 可能被拦截导致 .first() 永久挂起
        return settings.getCustomCharactersNow().firstOrNull { it.id == id }
    }

    suspend fun addCustom(character: Character) {
        // 原子读-改-写，避免并发导入/新建时 lost update
        settings.updateCustomCharacters { current ->
            val result = current.toMutableList()
            result.removeAll { it.id == character.id }
            result.add(character.copy(isCustom = true))
            result
        }
    }

    suspend fun removeCustom(id: String) {
        settings.updateCustomCharacters { current -> current.filterNot { it.id == id } }
    }

    /** 更新自定义角色：id 保持不变（即使改名），避免会话/群成员/好感度等按 id 的引用断裂。原子写。 */
    suspend fun updateCustom(updated: Character) {
        // map 替换语义：id 不存在时 no-op，不会误插入
        settings.updateCustomCharacters { current ->
            current.map { if (it.id == updated.id) updated.copy(isCustom = true) else it }
        }
    }

    suspend fun importCustom(list: List<Character>) {
        settings.updateCustomCharacters { current ->
            val result = current.toMutableList()
            for (c in list) {
                result.removeAll { it.id == c.id }
                result.add(c.copy(isCustom = true))
            }
            result
        }
    }

    suspend fun exportCustom(): List<Character> = settings.getCustomCharactersNow()
}
