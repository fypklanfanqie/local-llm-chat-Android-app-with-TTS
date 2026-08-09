package com.chatbyyourside.llm.benchmark

import com.chatbyyourside.llm.backend.BackendType
import com.chatbyyourside.llm.metrics.BenchmarkSummary
import com.chatbyyourside.llm.metrics.CompletionReason
import com.chatbyyourside.llm.metrics.InferenceTurnRecord
import com.chatbyyourside.llm.metrics.mean
import com.chatbyyourside.llm.metrics.median
import com.chatbyyourside.llm.metrics.sampleStandardDeviation
import com.chatbyyourside.llm.metrics.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 基准统计工具测试（Task 2 Step 4）。
 *
 * 使用固定值核对中位数与样本标准差，保证统计口径不变。
 */
class BenchmarkStatisticsTest {

    @Test
    fun median_oddCount_returnsMiddle() {
        assertEquals(2.0f, median(listOf(3f, 1f, 2f))!!, 0.0001f)
    }

    @Test
    fun median_evenCount_returnsAverageOfTwoMiddle() {
        assertEquals(2.5f, median(listOf(1f, 2f, 3f, 4f))!!, 0.0001f)
    }

    @Test
    fun median_unsortedInput_isSortedFirst() {
        assertEquals(3.0f, median(listOf(5f, 1f, 3f, 2f, 4f))!!, 0.0001f)
    }

    @Test
    fun median_empty_returnsNull() {
        assertNull(median(emptyList()))
    }

    @Test
    fun median_single_returnsItself() {
        assertEquals(7.0f, median(listOf(7f))!!, 0.0001f)
    }

    @Test
    fun mean_basic() {
        assertEquals(2.0f, mean(listOf(1f, 2f, 3f))!!, 0.0001f)
    }

    @Test
    fun mean_empty_returnsNull() {
        assertNull(mean(emptyList()))
    }

    @Test
    fun sampleStandardDeviation_classicDataset() {
        // 经典样本：[2,4,4,4,5,5,7,9]，均值=5，方差=32/7≈4.5714，σ≈2.1381
        val vals = listOf(2f, 4f, 4f, 4f, 5f, 5f, 7f, 9f)
        assertEquals(2.1381f, sampleStandardDeviation(vals)!!, 0.001f)
    }

    @Test
    fun sampleStandardDeviation_identicalValues_isZero() {
        assertEquals(0.0f, sampleStandardDeviation(listOf(5f, 5f, 5f))!!, 0.0001f)
    }

    @Test
    fun sampleStandardDeviation_singleValue_returnsNull() {
        assertNull(sampleStandardDeviation(listOf(5f)))
    }

    @Test
    fun sampleStandardDeviation_empty_returnsNull() {
        assertNull(sampleStandardDeviation(emptyList()))
    }

    @Test
    fun summarize_emptyRecords_returnsAllNullDefaults() {
        val s = summarize(emptyList())
        assertNull(s.medianTtftMs)
        assertNull(s.medianDecodeTps)
        assertNull(s.kvReuseRate)
    }

    @Test
    fun summarize_aggregatesMediansAndExtremes() {
        val records = listOf(
            turn(gid = "a", ttft = 100f, decodeTps = 10f, pss = 500L, thermal = 1, kvReuse = true),
            turn(gid = "b", ttft = 200f, decodeTps = 20f, pss = 700L, thermal = 3, kvReuse = false),
            turn(gid = "c", ttft = 300f, decodeTps = 30f, pss = 600L, thermal = 2, kvReuse = true),
        )
        val s: BenchmarkSummary = summarize(records)
        // TTFT 中位数 = 200
        assertEquals(200f, s.medianTtftMs!!, 0.0001f)
        // decode 中位数 = 20
        assertEquals(20f, s.medianDecodeTps!!, 0.0001f)
        // 样本标准差 σ([10,20,30])：均值20，方差=(100+0+100)/2=100，σ=10
        assertEquals(10f, s.decodeStdDev!!, 0.0001f)
        // 峰值 PSS = 700
        assertEquals(700L, s.peakPssMb)
        // 最大热档 = 3
        assertEquals(3, s.maxThermalStatus)
        // KV 复用率 = 2/3
        assertEquals(2f / 3f, s.kvReuseRate!!, 0.0001f)
    }

    @Test
    fun summarize_ignoresZeroOrMissingMetrics() {
        // 零值/缺失字段不应污染中位数（如失败轮次 ttft=0、decodeTps 缺失）
        val records = listOf(
            turn(gid = "ok1", ttft = 120f, decodeTps = 12f),
            turn(gid = "ok2", ttft = 180f, decodeTps = 18f),
            turn(gid = "fail", ttft = 0f, decodeTps = null),
        )
        val s = summarize(records)
        // 只应纳入 120/180 -> 中位数 150
        assertEquals(150f, s.medianTtftMs!!, 0.0001f)
        // 只应纳入 12/18 -> 中位数 15
        assertEquals(15f, s.medianDecodeTps!!, 0.0001f)
    }

    private fun turn(
        gid: String,
        ttft: Float? = null,
        decodeTps: Float? = null,
        pss: Long? = null,
        thermal: Int? = null,
        kvReuse: Boolean? = null,
    ): InferenceTurnRecord = InferenceTurnRecord(
        generationId = gid,
        requestedMode = null,
        effectiveMode = null,
        backend = BackendType.MNN_CPU,
        startedElapsedMs = 0L,
        endedElapsedMs = 0L,
        ttftMs = ttft?.toLong(),
        decodeTps = decodeTps,
        peakPssMb = pss,
        thermalMax = thermal,
        kvReuse = kvReuse,
        completionReason = CompletionReason.EOS,
    )
}
