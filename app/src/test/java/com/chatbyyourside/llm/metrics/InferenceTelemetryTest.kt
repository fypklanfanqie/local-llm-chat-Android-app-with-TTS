package com.chatbyyourside.llm.metrics

import com.chatbyyourside.llm.backend.BackendType
import com.chatbyyourside.llm.profile.InferencePerformanceMode
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InferenceTelemetry] 生命周期与序列化测试（Task 2 Step 1）。
 *
 * 纯 JVM 单测：不依赖 Android 运行时（遥测模型本身无 Android 引用）。
 */
class InferenceTelemetryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun beginGeneration_publishesPrefillSnapshotWithZeroProgress() {
        val t = InferenceTelemetry()
        t.beginGeneration(
            generationId = "g1",
            requestedMode = InferencePerformanceMode.BALANCED,
            effectiveMode = InferencePerformanceMode.BALANCED,
            backend = BackendType.MNN_CPU,
            startedElapsedMs = 1000L,
        )
        val snap = t.snapshot()
        assertNotNull(snap)
        assertEquals("g1", snap!!.generationId)
        assertEquals(InferenceStage.PREFILL, snap.stage)
        assertEquals(InferencePerformanceMode.BALANCED, snap.requestedMode)
        assertEquals(BackendType.MNN_CPU, snap.backend)
        assertEquals(0, snap.tokenCount)
        assertEquals(0, snap.callbackCount)
        assertEquals(0L, snap.callbackBytes)
        assertNull(snap.currentTps)
        assertEquals(1000L, snap.startedElapsedMs)
        assertEquals(1000L, snap.lastProgressElapsedMs)
        assertTrue(t.isActive)
    }

    @Test
    fun onDecodeToken_updatesCountsAndPublishesDecodeSnapshot() {
        val t = InferenceTelemetry()
        t.beginGeneration("g2", null, null, BackendType.MNN_GPU, 2000L)

        t.onDecodeToken(tokenCount = 1, callbackCount = 1, callbackBytes = 3L, currentTps = 5.0f, nowElapsedMs = 2100L)
        var snap = t.snapshot()!!
        assertEquals(InferenceStage.DECODE, snap.stage)
        assertEquals(1, snap.tokenCount)
        assertEquals(3L, snap.callbackBytes)
        assertEquals(5.0f, snap.currentTps!!, 0.0001f)
        assertEquals(2100L, snap.lastProgressElapsedMs)

        t.onDecodeToken(tokenCount = 2, callbackCount = 2, callbackBytes = 7L, currentTps = 9.5f, nowElapsedMs = 2200L)
        snap = t.snapshot()!!
        assertEquals(2, snap.tokenCount)
        assertEquals(7L, snap.callbackBytes)
        assertEquals(9.5f, snap.currentTps!!, 0.0001f)
        assertEquals(2200L, snap.lastProgressElapsedMs)
    }

    @Test
    fun finalize_buildsRecordFromNativeMetricsAndClearsSnapshot() {
        val t = InferenceTelemetry()
        t.beginGeneration("g3", InferencePerformanceMode.MAXIMUM_SPEED, InferencePerformanceMode.BALANCED,
            BackendType.MNN_NPU, 3000L)
        // 首 token 在 3100ms，第二个在 3200ms
        t.onDecodeToken(1, 1, 3L, 5f, 3100L)
        t.onDecodeToken(2, 2, 6L, 9f, 3200L)

        // native metrics: [tps=8, prefillUs=500000, decodeUs=200000, promptLen=50, genLen=16, reuseKv=1]
        val record = t.finalize(
            nowElapsedMs = 4000L,
            completionReason = CompletionReason.EOS,
            nativeMetrics = floatArrayOf(8.0f, 500000f, 200000f, 50f, 16f, 1f),
            peakPssMb = 1234L,
            thermalStart = 1, thermalMax = 2, thermalEnd = 2,
            configHash = "abc123",
            attemptTrace = listOf("NPU", "GPU"),
            downgradeReasons = listOf("thermal"),
            coldLoadMs = 800L,
        )

        assertNotNull(record)
        record!!
        assertEquals("g3", record.generationId)
        assertEquals(InferencePerformanceMode.MAXIMUM_SPEED, record.requestedMode)
        assertEquals(InferencePerformanceMode.BALANCED, record.effectiveMode) // 降级
        assertEquals(BackendType.MNN_NPU, record.backend)
        assertEquals(3000L, record.startedElapsedMs)
        assertEquals(4000L, record.endedElapsedMs)
        assertEquals(800L, record.coldLoadMs)
        assertEquals(100L, record.ttftMs) // 3100 - 3000
        assertEquals(500L, record.prefillMs) // 500000us -> 500ms
        assertEquals(200L, record.decodeMs) // 200000us -> 200ms
        assertEquals(50, record.promptTokens)
        assertEquals(16, record.generatedTokens)
        assertEquals(8.0f, record.decodeTps!!, 0.0001f)
        assertEquals(100f, record.prefillTps!!, 0.0001f) // 50 tokens / 0.5s
        assertTrue(record.kvReuse!!)
        assertEquals(1234L, record.peakPssMb)
        assertEquals(2, record.thermalMax)
        assertEquals(CompletionReason.EOS, record.completionReason)
        assertEquals(listOf("NPU", "GPU"), record.attemptTrace)
        assertEquals("abc123", record.configHash)
        assertEquals(listOf("thermal"), record.downgradeReasons)

        // finalize 后快照清空
        assertNull(t.snapshot())
        assertFalse(t.isActive)
    }

    @Test
    fun finalize_withoutActiveGeneration_returnsNullAndIsSafe() {
        val t = InferenceTelemetry()
        assertNull(t.finalize(nowElapsedMs = 0L, completionReason = CompletionReason.BACKEND_FAILURE))
        assertNull(t.snapshot())
    }

    @Test
    fun finalize_withoutNativeMetrics_fallsBackToCallbackCounts() {
        val t = InferenceTelemetry()
        t.beginGeneration("g4", null, null, BackendType.MNN_CPU, 0L)
        t.onDecodeToken(7, 7, 21L, 3.3f, 500L)
        val record = t.finalize(nowElapsedMs = 1000L, completionReason = CompletionReason.MAX_TOKENS)!!
        assertEquals(7, record.promptTokens) // 无 native -> 回落 tokenCount
        assertEquals(7, record.generatedTokens)
        assertEquals(3.3f, record.decodeTps!!, 0.0001f) // 回落 lastTps
        assertNull(record.kvReuse)
        assertNull(record.prefillMs)
        assertEquals(500L, record.ttftMs)
    }

    @Test
    fun reset_clearsActiveAndSnapshot() {
        val t = InferenceTelemetry()
        t.beginGeneration("g5", null, null, null, 0L)
        t.onDecodeToken(1, 1, 1L, 1f, 1L)
        assertNotNull(t.snapshot())
        assertTrue(t.isActive)
        t.reset()
        assertNull(t.snapshot())
        assertFalse(t.isActive)
    }

    @Test
    fun turnRecord_serializesRoundTrip() {
        val original = InferenceTurnRecord(
            generationId = "g-rt",
            requestedMode = InferencePerformanceMode.BALANCED,
            effectiveMode = InferencePerformanceMode.MAXIMUM_SPEED,
            backend = BackendType.MNN_GPU,
            startedElapsedMs = 100L,
            endedElapsedMs = 500L,
            coldLoadMs = 300L,
            warmLoadMs = null,
            ttftMs = 120L,
            prefillMs = 50L,
            decodeMs = 330L,
            promptTokens = 42,
            generatedTokens = 30,
            prefillTps = 840f,
            decodeTps = 90.9f,
            kvReuse = true,
            peakPssMb = 999L,
            thermalStart = 0,
            thermalMax = 3,
            thermalEnd = 2,
            completionReason = CompletionReason.USER_CANCEL,
            attemptTrace = listOf("GPU", "CPU"),
            configHash = "hash-xyz",
            downgradeReasons = listOf("battery"),
        )
        val s: String = json.encodeToString(original)
        val decoded: InferenceTurnRecord = json.decodeFromString(s)
        assertEquals(original, decoded)
    }

    @Test
    fun turnRecord_serializationToleratesUnknownFutureFields() {
        // 模拟未来版本新增字段：旧记录应能解码（ignoreUnknownKeys=true）
        val s = """{"generationId":"g-fwd","requestedMode":"BALANCED","effectiveMode":"BALANCED","backend":"MNN_CPU","startedElapsedMs":1,"endedElapsedMs":2,"futureField":99}"""
        val decoded: InferenceTurnRecord = json.decodeFromString(s)
        assertEquals("g-fwd", decoded.generationId)
        assertEquals(BackendType.MNN_CPU, decoded.backend)
        assertEquals(0, decoded.generatedTokens) // 默认值
    }
}
