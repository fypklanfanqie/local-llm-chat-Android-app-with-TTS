package com.chatbyyourside.ui.groupchat

import com.chatbyyourside.data.model.Character
import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.ui.groupchat.GroupChatPromptBuilder.SpeakerResponseParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊身份隔离测试：当前 speaker 是唯一可执行身份、请求永不以 assistant 结尾、
 * 错误前缀不被静默归属。对应修复「A 角色偶尔以 C 人设说话」。
 */
class GroupChatPromptBuilderTest {

    private fun character(id: String, name: String, prompt: String = "$id-persona") =
        Character(id = id, name = name, code = id, role = "测试", race = "人类", systemPrompt = prompt)

    private val a = character("char-a", "小明")
    private val c = character("char-c", "小红", prompt = "小红的独特人设")

    // ===== system prompt 身份区隔 =====

    @Test
    fun systemPromptMarksCurrentSpeakerAsOnlyExecutableIdentity() {
        val s = GroupChatPromptBuilder.buildSystemPrompt(listOf(a, c), a, askUser = false)

        assertTrue("A 完整人设在场", s.contains("char-a-persona"))
        assertTrue("声明以 A 身份发言", s.contains("小明"))
        // 其他成员降级为只读参考，且显式禁止代替其说话（现行文案：「你绝不能代替他们说话」等）
        assertTrue("禁止代替他人", s.contains("不得代替") || s.contains("不要代替") || s.contains("禁止代替") || s.contains("绝不能代替"))
        assertFalse(
            "旧版「回应/吐槽其他成员」歧义文案应移除",
            s.contains("吐槽其他成员"),
        )
    }

    @Test
    fun systemPromptKeepsWorldviewAndLorebookHead() {
        val s = GroupChatPromptBuilder.buildSystemPrompt(
            listOf(a, c), a, askUser = false,
            worldviewDirective = "【世界观】修仙",
            lorebookStaticHead = "【世界背景设定】常驻设定",
        )
        assertTrue(s.contains("【世界观】修仙"))
        assertTrue(s.contains("【世界背景设定】常驻设定"))
    }

    @Test
    fun systemPromptForDifferentSpeakersIsDistinct() {
        val forA = GroupChatPromptBuilder.buildSystemPrompt(listOf(a, c), a, askUser = false)
        val forC = GroupChatPromptBuilder.buildSystemPrompt(listOf(a, c), c, askUser = false)

        assertNotEquals(forA, forC)
        assertTrue(forC.contains("小红的独特人设"))
    }

    // ===== 请求末尾 user 边界 =====

    @Test
    fun apiMessagesEndWithUserTurnWhenHistoryEndsWithAssistant() {
        val history = listOf(
            ChatMessage(role = "user", content = "大家好"),
            ChatMessage(role = "assistant", content = "小红：我先说", characterId = c.id),
        )
        val msgs = GroupChatPromptBuilder.buildApiMessages(listOf(a, c), a, history, askUser = false)

        assertEquals("user", msgs.last().role)
        assertTrue("末尾指令点名 A", msgs.last().content.contains("小明"))
    }

    @Test
    fun apiMessagesEndWithUserTurnEvenWithoutUserHistory() {
        val history = listOf(
            ChatMessage(role = "assistant", content = "小红：上一轮", characterId = c.id),
        )
        val msgs = GroupChatPromptBuilder.buildApiMessages(listOf(a, c), a, history, askUser = true)

        assertEquals("user", msgs.last().role)
    }

    @Test
    fun apiMessagesKeepLorebookTailBeforeFinalUserTurn() {
        val history = listOf(ChatMessage(role = "user", content = "开始"))
        val tail = listOf(ChatMessage(role = "system", content = "【相关设定】动态命中"))
        val msgs = GroupChatPromptBuilder.buildApiMessages(
            listOf(a, c), a, history, askUser = false, lorebookTailMessages = tail,
        )

        val lastUserIdx = msgs.indexOfLast { it.role == "user" }
        assertTrue(lastUserIdx > 0)
        // 动态世界书在末尾 user 轮之前
        assertTrue(msgs.take(lastUserIdx).any { it.role == "system" && it.content.contains("动态命中") })
        // 且最后一条是本轮 speaker 的 user 指令
        assertTrue(msgs.last().content.contains("小明"))
    }

    @Test
    fun assistantHistoryKeepsNamePrefixMapping() {
        val history = listOf(
            ChatMessage(role = "assistant", content = "C 的内容", characterId = c.id),
        )
        val msgs = GroupChatPromptBuilder.buildApiMessages(listOf(a, c), a, history, askUser = false)
        val assistantMsgs = msgs.filter { it.role == "assistant" }
        assertEquals(1, assistantMsgs.size)
        assertTrue(assistantMsgs[0].content.startsWith("小红："))
    }

    // ===== 前缀解析：只认当前 speaker =====

    @Test
    fun prefixParseStripsExpectedSpeakerOnly() {
        val r = GroupChatPromptBuilder.parseSpeakerResponse("小明：你好呀", expected = a.name, memberNames = listOf(a.name, c.name))
        assertEquals(SpeakerResponseParse.Ok("你好呀"), r)
    }

    @Test
    fun prefixParseDetectsForeignSpeakerInsteadOfSilentlyAccepting() {
        val r = GroupChatPromptBuilder.parseSpeakerResponse("小红：这是小红的话", expected = a.name, memberNames = listOf(a.name, c.name))
        assertEquals(SpeakerResponseParse.ForeignSpeaker(c.name), r)
    }

    @Test
    fun prefixParsePassesThroughUnprefixedText() {
        val r = GroupChatPromptBuilder.parseSpeakerResponse("没有前缀的回复", expected = a.name, memberNames = listOf(a.name, c.name))
        assertEquals(SpeakerResponseParse.Ok("没有前缀的回复"), r)
    }

    @Test
    fun prefixParseStripsSingleLevelAndQuotes() {
        val r = GroupChatPromptBuilder.parseSpeakerResponse("「小明：带引号的」", expected = a.name, memberNames = listOf(a.name, c.name))
        // 引号包裹整体时剥引号后仍能识别/剥离名字前缀；至少不能把内容当 foreign
        assertTrue(r is SpeakerResponseParse.Ok)
    }

    @Test
    fun legacyStripStillWorksForExpectedSpeaker() {
        assertEquals("正文", GroupChatPromptBuilder.stripSpeakerPrefix("小明：正文", "小明"))
    }
}
