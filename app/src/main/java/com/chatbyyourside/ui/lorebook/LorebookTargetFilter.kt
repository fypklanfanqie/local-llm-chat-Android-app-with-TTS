package com.chatbyyourside.ui.lorebook

import com.chatbyyourside.data.model.Character
import com.chatbyyourside.data.model.Conversation

/** Lorebook 生效范围选择器的目标过滤；只影响显示，不改变 scopeIds。 */
internal fun filterLorebookCharacterTargets(
    characters: List<Character>,
    query: String,
): List<Character> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return characters
    return characters.filter { c ->
        c.name.lowercase().contains(q) ||
            c.id.lowercase().contains(q) ||
            c.code.lowercase().contains(q) ||
            c.role.lowercase().contains(q) ||
            c.race.lowercase().contains(q)
    }
}

/** 返回 (id, 显示名)，按群标题或数字 id 模糊过滤。 */
internal fun filterLorebookGroupTargets(
    groups: List<Conversation>,
    query: String,
): List<Pair<String, String>> {
    val q = query.trim().lowercase()
    return groups.map { it.id.toString() to it.title.ifBlank { "群聊" } }
        .filter { (id, name) -> q.isEmpty() || name.lowercase().contains(q) || id.contains(q) }
}

/** 切换范围类型时只保留目标类型的 id；ALL 不需要绑定 id。 */
internal fun sanitizeLorebookScopeIds(
    type: com.chatbyyourside.data.model.LorebookScopeType,
    ids: Set<String>,
    characterIds: Set<String>,
    groupIds: Set<String>,
): Set<String> = when (type) {
    com.chatbyyourside.data.model.LorebookScopeType.ALL -> emptySet()
    com.chatbyyourside.data.model.LorebookScopeType.CHARACTER -> ids intersect characterIds
    com.chatbyyourside.data.model.LorebookScopeType.GROUP -> ids intersect groupIds
}
