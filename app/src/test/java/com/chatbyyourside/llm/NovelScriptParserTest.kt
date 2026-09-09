package com.chatbyyourside.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovelScriptParser] 契约测试：AI 续写输出 → 脚本行解析。
 */
class NovelScriptParserTest {

    @Test
    fun parse_narrationAndCharacterLines() {
        val raw = "旁白：夜色渐深，工作室的走廊安静下来。\n" +
            "苏晚：小满，还没休息吗？\n" +
            "旁白：她抱着一叠文件，脚步轻快。"
        val lines = NovelScriptParser.parse(raw, knownSpeakers = setOf("苏晚"), protagonistName = "小满")
        assertEquals(3, lines.size)
        assertEquals(NovelScriptParser.TYPE_NARRATION, lines[0].speakerType)
        assertEquals("夜色渐深，工作室的走廊安静下来。", lines[0].content)
        assertEquals(NovelScriptParser.TYPE_CHARACTER, lines[1].speakerType)
        assertEquals("苏晚", lines[1].speakerName)
        assertEquals(NovelScriptParser.TYPE_NARRATION, lines[2].speakerType)
    }

    @Test
    fun parse_protagonistLine() {
        val raw = "小满：我马上就睡，你也是。"
        val lines = NovelScriptParser.parse(raw, knownSpeakers = setOf("苏晚"), protagonistName = "小满")
        assertEquals(1, lines.size)
        assertEquals(NovelScriptParser.TYPE_USER, lines[0].speakerType)
        assertEquals("小满", lines[0].speakerName)
    }

    @Test
    fun parse_continuationLinesMergeIntoPrevious() {
        val raw = "旁白：风吹过甲板。\n海鸥在远处盘旋，一圈又一圈。\n薇拉：注意休息。"
        val lines = NovelScriptParser.parse(raw, knownSpeakers = setOf("薇拉"), protagonistName = "小满")
        assertEquals(2, lines.size)
        assertEquals("风吹过甲板。\n海鸥在远处盘旋，一圈又一圈。", lines[0].content)
        assertEquals(NovelScriptParser.TYPE_CHARACTER, lines[1].speakerType)
    }

    @Test
    fun parse_unknownPrefixTreatedAsNarration() {
        val raw = "某某角色：这句话的前缀不在已知列表里。"
        val lines = NovelScriptParser.parse(raw, knownSpeakers = setOf("苏晚"), protagonistName = "小满")
        // 未知前缀（不是主控/已知角色/旁白）→ 整行归旁白
        assertEquals(1, lines.size)
        assertEquals(NovelScriptParser.TYPE_NARRATION, lines[0].speakerType)
        assertTrue(lines[0].content.contains("某某角色"))
    }

    @Test
    fun parse_stripsThinkBlocks() {
        val raw = "<think>Let me think in English...</think>旁白：思考结束。"
        val lines = NovelScriptParser.parse(raw, knownSpeakers = emptySet(), protagonistName = "小满")
        assertEquals(1, lines.size)
        assertEquals("思考结束。", lines[0].content)
    }

    @Test
    fun parse_stripsMarkdownFenceAndHeaders() {
        val raw = "```\n旁白：开始。\n```\n**第 2 话**\n苏晚：你好。"
        val lines = NovelScriptParser.parse(raw, knownSpeakers = setOf("苏晚"), protagonistName = "小满")
        // markdown 围栏与粗体标题行被剔除
        assertEquals(2, lines.size)
        assertEquals("开始。", lines[0].content)
        assertEquals("你好。", lines[1].content)
    }

    @Test
    fun parse_emptyReturnsEmpty() {
        assertTrue(NovelScriptParser.parse("", emptySet(), "小满").isEmpty())
        assertTrue(NovelScriptParser.parse("<think>...</think>", emptySet(), "小满").isEmpty())
    }

    @Test
    fun parse_protagonistUnknownName_fallsBackToNarration() {
        // 主控名与输出前缀不一致时（模型用了「我：」），不误判为 user
        val raw = "我：随便说说。"
        val lines = NovelScriptParser.parse(raw, knownSpeakers = emptySet(), protagonistName = "小满")
        assertEquals(1, lines.size)
        assertEquals(NovelScriptParser.TYPE_NARRATION, lines[0].speakerType)
    }

    @Test
    fun parse_colonVariantsSupported() {
        val raw = "旁白: 半角冒号。\n苏晚：全角冒号。"
        val lines = NovelScriptParser.parse(raw, knownSpeakers = setOf("苏晚"), protagonistName = "小满")
        assertEquals(2, lines.size)
        assertEquals(NovelScriptParser.TYPE_NARRATION, lines[0].speakerType)
        assertEquals(NovelScriptParser.TYPE_CHARACTER, lines[1].speakerType)
    }
}
