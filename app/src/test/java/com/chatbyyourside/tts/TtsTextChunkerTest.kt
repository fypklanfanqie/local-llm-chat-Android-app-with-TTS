package com.chatbyyourside.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 长文本切分器单测：句界贪心打包、超长硬切、边界输入。
 */
class TtsTextChunkerTest {

    @Test
    fun `短文本不切分`() {
        val text = "你好，世界。"
        assertEquals(listOf(text), chunkTtsText(text, 100))
    }

    @Test
    fun `maxLen 非正时原文返回`() {
        val text = "一段话"
        assertEquals(listOf(text), chunkTtsText(text, 0))
        assertEquals(listOf(text), chunkTtsText(text, -5))
    }

    @Test
    fun `多句文本按句末标点贪心打包且不超限`() {
        // 每句 10 字符，maxLen=25 -> 每段最多装 2 句（20 字符）而非硬切到 25
        val sentence = "一二三四五六七八九。"
        val text = sentence.repeat(5) // 50 chars
        val chunks = chunkTtsText(text, 25)
        assertTrue("应切成多段: $chunks", chunks.size >= 2)
        chunks.forEach { assertTrue("每段不超限: ${it.length}", it.length <= 25) }
        assertEquals("拼接后还原全文（trim 不丢字符）", text, chunks.joinToString(""))
    }

    @Test
    fun `无句界的长串在 maxLen 处硬切`() {
        val text = "a".repeat(250)
        val chunks = chunkTtsText(text, 100)
        assertEquals(listOf("a".repeat(100), "a".repeat(100), "a".repeat(50)), chunks)
    }

    @Test
    fun `混合内容先句界后硬切`() {
        val head = "第一句话。第二句话。"           // 10 chars，句界
        val tail = "b".repeat(95)                  // 无句界长串
        val text = head + tail                     // 105 chars
        val chunks = chunkTtsText(text, 100)
        assertEquals(2, chunks.size)
        assertEquals(head, chunks[0])              // 在最后句界处断开
        assertEquals("b".repeat(95), chunks[1])
    }

    @Test
    fun `换行也算句界`() {
        val line = "甲乙丙丁\n"                 // 5 chars
        val text = line.repeat(30)              // 150 chars
        val chunks = chunkTtsText(text, 40)
        assertTrue(chunks.size >= 3)
        chunks.forEach { assertTrue(it.length <= 40) }
        // 无内容丢失：join 后仅少了被段尾 trim() 去掉的空白字符（每段至多一个段尾 \n），
        // 其余字符（含段中保留的换行）必须原样保留。
        val removedCount = text.length - chunks.joinToString("").length
        assertTrue("被移除的应为纯空白，实际移除 $removedCount 字符", removedCount >= 0)
        assertEquals(
            text.count { it == '\n' },
            removedCount + chunks.joinToString("").count { it == '\n' },
        )
        // 更强的等价断言：把每段重新补回段尾换行后应能重建原文的字符多重集。
        // 直接验证：所有非空白字符按序不变。
        assertEquals(text.replace(Regex("\\s"), ""), chunks.joinToString("").replace(Regex("\\s"), ""))
    }

    @Test
    fun `全空白输入回退为单段避免空列表`() {
        val text = "   "
        val chunks = chunkTtsText(text, 100)
        // 全空白被 filter 掉后回退为单段（原文前 maxLen 字符），结果恒非空
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `恰好等于 maxLen 时不切分`() {
        val text = "x".repeat(50)
        assertEquals(listOf(text), chunkTtsText(text, 50))
    }
}
