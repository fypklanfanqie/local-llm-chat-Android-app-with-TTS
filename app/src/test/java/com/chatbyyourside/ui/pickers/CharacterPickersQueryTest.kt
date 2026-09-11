package com.chatbyyourside.ui.pickers

import com.chatbyyourside.data.model.Character
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选角搜索口径契约：全部「添加/选择角色」入口共用同一条匹配规则，
 * 这里锁住「匹配哪些字段」与「空查询返回原列表」两个关键行为。
 */
class CharacterPickersQueryTest {

    private fun char(
        id: String = "id1",
        name: String = "名字",
        code: String = "Code",
        role: String = "职位",
        race: String = "种族",
    ) = Character(
        id = id,
        name = name,
        code = code,
        role = role,
        race = race,
        systemPrompt = "prompt",
    )

    @Test
    fun matchesByNameCodeIdRoleRace_caseInsensitive() {
        val c = char(id = "senpai", name = "苏晚", code = "Senpai", role = "学姐", race = "人类")
        assertTrue(characterMatchesQuery(c, "苏晚"))
        assertTrue(characterMatchesQuery(c, "senpai"))   // 代号（忽略大小写）
        assertTrue(characterMatchesQuery(c, "SENPAI"))
        assertTrue(characterMatchesQuery(c, "senpai"))   // id 与代号同名也算命中
        assertTrue(characterMatchesQuery(c, "学姐"))      // 职位：用户常按定位词筛人
        assertTrue(characterMatchesQuery(c, "人类"))      // 种族
    }

    @Test
    fun blankQueryMatchesEverything_andFilterReturnsOriginalList() {
        val list = listOf(char(id = "a"), char(id = "b", name = "另一个"))
        assertTrue(characterMatchesQuery(list[0], "   "))
        assertEquals(list, filterCharactersByQuery(list, ""))
        assertEquals(list, filterCharactersByQuery(list, "  "))
    }

    @Test
    fun nonMatchingQueryExcludesCharacter() {
        val list = listOf(char(id = "a", name = "苏晚"), char(id = "b", name = "阿橙", code = "Healing"))
        val filtered = filterCharactersByQuery(list, "阿橙")
        assertEquals(listOf("b"), filtered.map { it.id })
        assertFalse(characterMatchesQuery(list[0], "阿橙"))
    }

    @Test
    fun queryIsTrimmedBeforeMatching() {
        val list = listOf(char(id = "a", name = "苏晚"))
        assertEquals(listOf("a"), filterCharactersByQuery(list, "  苏晚  ").map { it.id })
    }
}
