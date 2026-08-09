package com.chatbyyourside.llm.metrics

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NativeGenerationSummary wire 契约测试（Task 4 Step 1）。
 *
 * 覆盖：每个 CompletionReason / InferenceStage 严格解析、未知版本/未知 reason/未知 stage
 * 拒收、非法 JSON 拒收、可选字段缺省、reuseKv 语义映射、序列化 round-trip。
 */
class NativeGenerationSummaryTest {

    private fun sampleJson(reason: String): String =
        """{"v":1,"completionReason":"$reason","promptTokens":120,"generatedTokens":45,"""" +
            """"prefillUs":900000,"decodeUs":450000,"reuseKv":1,"callbackCount":9,"callbackBytes":360,"""" +
            """"firstDeltaUs":950000,"errorStage":null,"errorMessage":null}"""

    @Test
    fun parsesEveryCompletionReason() {
        for (reason in CompletionReason.entries) {
            val s = NativeGenerationSummary.parse(sampleJson(reason.name))
            assertNotNull("应能解析 $reason", s)
            assertEquals(reason.name, s!!.completionReason)
        }
    }

    @Test
    fun parsesAllInferenceStages() {
        for (stage in InferenceStage.entries) {
            val json = sampleJson("EOS").replace(
                "\"errorStage\":null",
                "\"errorStage\":\"${stage.name}\"",
            )
            val s = NativeGenerationSummary.parse(json)
            assertNotNull("应能解析 errorStage=$stage", s)
            assertEquals(stage.name, s!!.errorStage)
        }
    }

    @Test
    fun unknownVersionRejected() {
        assertNull(NativeGenerationSummary.parse(sampleJson("EOS").replace("\"v\":1", "\"v\":2")))
    }

    @Test
    fun unknownCompletionReasonRejected() {
        assertNull(NativeGenerationSummary.parse(sampleJson("DEFINITELY_NOT_A_REASON")))
    }

    @Test
    fun unknownErrorStageRejected() {
        assertNull(NativeGenerationSummary.parse(
            sampleJson("EOS").replace("\"errorStage\":null", "\"errorStage\":\"BOGUS\""),
        ))
    }

    @Test
    fun malformedJsonRejected() {
        assertNull(NativeGenerationSummary.parse("{not json"))
        assertNull(NativeGenerationSummary.parse(""))
        assertNull(NativeGenerationSummary.parse("null"))
    }

    @Test
    fun missingOptionalFieldsTolerated() {
        val json = """{"v":1,"completionReason":"EOS","promptTokens":1,"generatedTokens":1,"""" +
            """"prefillUs":1,"decodeUs":1,"reuseKv":0,"callbackCount":1,"callbackBytes":4}"""
        val s = NativeGenerationSummary.parse(json)
        assertNotNull(s)
        assertNull(s!!.firstDeltaUs)
        assertNull(s.errorStage)
        assertNull(s.errorMessage)
    }

    @Test
    fun kvReuseSemanticMapping() {
        assertTrue(NativeGenerationSummary.parse(sampleJson("EOS"))!!.kvReuse == true)
        assertTrue(NativeGenerationSummary.parse(
            sampleJson("EOS").replace("\"reuseKv\":1", "\"reuseKv\":0"),
        )!!.kvReuse == false)
        assertNull(NativeGenerationSummary.parse(
            sampleJson("EOS").replace("\"reuseKv\":1", "\"reuseKv\":-1"),
        )!!.kvReuse)
    }

    @Test
    fun roundTripSerialization() {
        val s = NativeGenerationSummary.parse(sampleJson("MAX_TOKENS"))!!
        val encoded = NativeGenerationSummary.summaryJson.encodeToString(s)
        val decoded = NativeGenerationSummary.parse(encoded)
        assertNotNull(decoded)
        assertEquals("MAX_TOKENS", decoded!!.completionReason)
        assertEquals(s.generatedTokens, decoded.generatedTokens)
        assertEquals(s.prefillUs, decoded.prefillUs)
    }

    // ---- toMetricsArray()：摘要 -> nativeGetMetrics 同构数组 [tps, prefillUs, decodeUs, promptLen, genLen, reuseKv] ----

    @Test
    fun toMetricsArrayShapeAndTpsDerivation() {
        // sampleJson: prompt=120 gen=45 prefillUs=900000 decodeUs=450000 reuseKv=1
        // tps = genLen * 1e6 / decodeUs = 45 * 1e6 / 450000 = 100
        val m = NativeGenerationSummary.parse(sampleJson("EOS"))!!.toMetricsArray()
        assertEquals(6, m.size)
        assertEquals(100f, m[0], 0.001f)
        assertEquals(900000f, m[1], 0.001f)
        assertEquals(450000f, m[2], 0.001f)
        assertEquals(120f, m[3], 0.001f)
        assertEquals(45f, m[4], 0.001f)
        assertEquals(1f, m[5], 0.001f)   // reuseKv 原样透传（nativeGetMetrics 同构，下游按 !=0 判复用）
    }

    @Test
    fun toMetricsArrayZeroDecodeUsYieldsZeroTps() {
        val s = NativeGenerationSummary.parse(
            sampleJson("EOS").replace("\"decodeUs\":450000", "\"decodeUs\":0"),
        )!!
        assertEquals(0f, s.toMetricsArray()[0], 0.001f)
    }
}
