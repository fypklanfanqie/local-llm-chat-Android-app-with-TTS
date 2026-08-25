package com.chatbyyourside.ui.lorebook

import com.chatbyyourside.data.model.Character
import com.chatbyyourside.data.model.Conversation
import com.chatbyyourside.data.model.LorebookScopeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookTargetFilterTest {
    private val chars = listOf(
        Character("a-1", "Alice", "AL", "法师", "人类", systemPrompt = ""),
        Character("b-2", "小红", "RH", "骑士", "精灵", systemPrompt = ""),
    )
    private val groups = listOf(
        Conversation(7L, "group_chat", "冒险小队", 0L, 0L, isGroup = true),
        Conversation(8L, "group_chat", "夜谈", 0L, 0L, isGroup = true),
    )

    @Test
    fun blankQueryPreservesOrder() {
        assertEquals(chars, filterLorebookCharacterTargets(chars, "  "))
        assertEquals(listOf("7" to "冒险小队", "8" to "夜谈"), filterLorebookGroupTargets(groups, ""))
    }

    @Test
    fun characterSearchMatchesNameIdCodeRoleAndRaceCaseInsensitively() {
        assertEquals(listOf(chars[0]), filterLorebookCharacterTargets(chars, "alice"))
        assertEquals(listOf(chars[0]), filterLorebookCharacterTargets(chars, "A-1"))
        assertEquals(listOf(chars[0]), filterLorebookCharacterTargets(chars, "法师"))
        assertEquals(listOf(chars[1]), filterLorebookCharacterTargets(chars, "精灵"))
    }

    @Test
    fun groupSearchMatchesTitleAndId() {
        assertEquals(listOf("8" to "夜谈"), filterLorebookGroupTargets(groups, "夜谈"))
        assertEquals(listOf("7" to "冒险小队"), filterLorebookGroupTargets(groups, "7"))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(filterLorebookCharacterTargets(chars, "不存在").isEmpty())
        assertTrue(filterLorebookGroupTargets(groups, "不存在").isEmpty())
    }

    @Test
    fun scopeIdsAreSanitizedWhenTypeChanges() {
        val ids = setOf("a-1", "7", "stale")
        assertEquals(setOf("a-1"), sanitizeLorebookScopeIds(LorebookScopeType.CHARACTER, ids, setOf("a-1", "b-2"), setOf("7", "8")))
        assertEquals(setOf("7"), sanitizeLorebookScopeIds(LorebookScopeType.GROUP, ids, setOf("a-1"), setOf("7", "8")))
        assertTrue(sanitizeLorebookScopeIds(LorebookScopeType.ALL, ids, setOf("a-1"), setOf("7")).isEmpty())
    }
}
