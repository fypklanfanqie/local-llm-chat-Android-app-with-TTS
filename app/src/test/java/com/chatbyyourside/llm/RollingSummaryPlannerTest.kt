package com.chatbyyourside.llm

import com.chatbyyourside.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RollingSummaryPlanner] 纯函数测试：折叠阈值边界、批次选取保序、转写渲染、提示词拼装、摘要截断。
 */
class RollingSummaryPlannerTest {

    private fun row(id: Long, role: String = "user", text: String = "消息$id") =
        RollingSummaryPlanner.SummaryRow(id, role, text)

    @Test
    fun foldOnlyWhenStrictlyAboveHighWater() {
        assertFalse(RollingSummaryPlanner.shouldFold(AppConfig.ContextCompression.HIGH_WATER_ROWS))
        assertTrue(RollingSummaryPlanner.shouldFold(AppConfig.ContextCompression.HIGH_WATER_ROWS + 1))
        assertFalse(RollingSummaryPlanner.shouldFold(0))
    }

    @Test
    fun selectBatchRequiresOverHighWaterAndKeepsOrder() {
        assertNull(RollingSummaryPlanner.selectBatch(List(160) { row(it.toLong()) })) // 未超高水位不折
        val rows = List(181) { row(it.toLong()) }
        val batch = RollingSummaryPlanner.selectBatch(rows)!!
        assertEquals(80, batch.size)
        assertEquals(0L, batch.first().id)   // 最旧 80 行
        assertEquals(79L, batch.last().id)
        assertEquals((0..79).map { it.toLong() }, batch.map { it.id }) // 升序不乱
    }

    @Test
    fun transcriptRendersRoleTagsInOrder() {
        val t = RollingSummaryPlanner.renderFoldedTranscript(
            listOf(row(1, "user", "你好呀"), row(2, "assistant", "你好！")),
        )
        assertTrue(t.contains("[用户] 你好呀"))
        assertTrue(t.contains("[角色] 你好！"))
        assertTrue(t.indexOf("[用户]") < t.indexOf("[角色]"))
    }

    @Test
    fun summaryPromptCarriesOldSummaryAndTranscriptAndWordBudget() {
        val msgs = RollingSummaryPlanner.buildSummaryMessages(
            oldSummary = "旧摘要：主角与旅伴结盟。",
            transcript = "[用户] 出发\n[角色] 好。",
        )
        assertEquals(1, msgs.size)
        assertEquals("system", msgs[0].role)
        val body = msgs[0].content.toString()
        assertTrue(body.contains("旧摘要：主角与旅伴结盟。"))
        assertTrue(body.contains("[用户] 出发"))
        assertTrue(body.contains("300")) // 字数预算出现在指令里
    }

    @Test
    fun clampSummaryTrimsWhitespaceThenHardCaps() {
        val marker = "，……（后略）"
        assertEquals("abc", RollingSummaryPlanner.clampSummary("  abc \n"))
        // 截断保留预算含省略标记：总长恒等于 SUMMARY_MAX_CHARS
        val clamped = RollingSummaryPlanner.clampSummary("字".repeat(500))
        assertEquals(AppConfig.ContextCompression.SUMMARY_MAX_CHARS, clamped.length)
        assertTrue(clamped.endsWith(marker))
    }

    @Test
    fun betweenFoldsWindowIsAppendOnly() {
        // 缓存契约仿真（与 performContextFoldLocked 同款循环）：
        // 稳态期仅尾部追加、水位固定 → 可见窗头部永不滑动（云端前缀缓存复用前提）；
        // 越过高水位才整批折叠一次，水位推进后与剩余原文严格相接（无空洞、无滑动残留）。
        var seq = 0L
        var dbRows = emptyList<RollingSummaryPlanner.SummaryRow>() // 数据库存量行（含已被摘要覆盖的）
        var watermark = 0L
        var folds = 0
        var lastVisibleHeadWhenStable = -1L

        repeat(400) {
            seq += 1L
            dbRows = dbRows + RollingSummaryPlanner.SummaryRow(seq, "user", "t$seq")
            val visible = dbRows.filter { it.id > watermark }
            if (!RollingSummaryPlanner.shouldFold(visible.size)) {
                // 稳态：本条只是尾部追加；头部若曾记录过则绝不改变
                if (lastVisibleHeadWhenStable != -1L) {
                    assertEquals(lastVisibleHeadWhenStable, visible.first().id)
                }
                lastVisibleHeadWhenStable = visible.first().id
            } else {
                val batch = RollingSummaryPlanner.selectBatch(visible)!!
                assertEquals(BATCH_SIZE, batch.size)                       // 整批折叠
                watermark = batch.last().id
                // 新窗口与水位严格相接：batch 之后第一条就是新头部
                assertEquals(watermark + 1, dbRows.first { it.id > watermark }.id)
                folds++
                lastVisibleHeadWhenStable = -1L
            }
        }
        // 400 行按可见计数越线（>160 行）：第 161/241/321 行各触发一批，恰好三次
        assertEquals(3, folds)
    }

    private companion object {
        const val BATCH_SIZE = 80
    }
}
