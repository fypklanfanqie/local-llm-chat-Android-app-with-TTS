package com.chatbyyourside.llm.benchmark

import com.chatbyyourside.llm.profile.RuntimeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实验促进门禁测试（Task 15 Step 1）+ 认证记录映射（Task 6）。 */
class ExperimentalPromotionPolicyTest {

    private val baseline = BenchmarkSample(decodeTpsMedian = 10f, ttftMsMedian = 800f, peakPssMb = 1000f, sampleCount = 3)
    private fun candidate(overrides: BenchmarkSample.() -> BenchmarkSample) =
        BenchmarkSample(decodeTpsMedian = 11.5f, ttftMsMedian = 850f, peakPssMb = 1050f, sampleCount = 3).overrides()

    @Test
    fun qualifiedCandidatePromotes() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { this })
        assertEquals(PromotionDecision.Promote, d)
    }

    @Test
    fun correctnessFailureRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(correctnessOk = false) })
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("正确性") })
    }

    @Test
    fun insufficientDecodeImprovementRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(decodeTpsMedian = 10.9f) })
        assertTrue(d is PromotionDecision.Reject)
    }

    @Test
    fun singleSampleRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(sampleCount = 1) })
        assertTrue(d is PromotionDecision.Reject)
        assertTrue((d as PromotionDecision.Reject).reasons.any { it.contains("样本数") })
    }

    @Test
    fun hotStartRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(hotStart = true) })
        assertTrue(d is PromotionDecision.Reject)
    }

    @Test
    fun ttftRegressionRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(ttftMsMedian = 1100f) })
        assertTrue(d is PromotionDecision.Reject)
    }

    @Test
    fun pssRegressionRejects() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(peakPssMb = 1500f) })
        assertTrue(d is PromotionDecision.Reject)
    }

    // ===== Task 6：PromotionDecision -> 认证记录映射（InferenceCertificationStore.toCertifiedOptions）=====

    private fun case(quadrant: InferenceBackendQuadrant = InferenceBackendQuadrant.CPU_THINKING_OFF) =
        InferenceBenchmarkCase(
            scenario = InferenceBenchmarkScenario.FIXED_DECODE,
            quadrant = quadrant,
            modelFingerprint = "model-a",
            deviceFingerprint = "device-a",
            configHash = "case-config-hash",
        )

    @Test
    fun promotedCandidateProducesCertificationRecord() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { this })
        val record = InferenceCertificationStore.toCertifiedOptions(
            case = case(),
            decision = d,
            decodeStepTokens = 1,
            configHash = "candidate-cfg",
            nowElapsedMs = 42_000L,
        )

        assertNotNull("Promote 应产出认证记录", record)
        record!!.let {
            // 身份分量来自用例；native 构建身份取 MnnBridge.runtimeInfo（JVM 测试为握手缺席 -> 空串）。
            assertEquals("device-a", it.deviceFingerprint)
            assertEquals("model-a", it.modelFingerprint)
            assertEquals("CPU 象限应映射到 CPU_OPTIMIZED 变体", RuntimeVariant.CPU_OPTIMIZED.name, it.variant)
            assertTrue("lookahead 认证路径应记录 lookahead 证据", it.lookahead)
            assertEquals("未测步进时记录 1", 1, it.decodeStepTokens)
            assertEquals("candidate-cfg", it.certifiedConfigHash)
            assertEquals(42_000L, it.certifiedAtElapsedMs)
        }
    }

    @Test
    fun gpuQuadrantMapsToOpenclVariant() {
        val record = InferenceCertificationStore.toCertifiedOptions(
            case = case(InferenceBackendQuadrant.GPU_THINKING_ON),
            decision = PromotionDecision.Promote,
            decodeStepTokens = 1,
            configHash = null,
            nowElapsedMs = 1L,
        )

        assertNotNull(record)
        assertEquals(RuntimeVariant.OPENCL.name, record!!.variant)
    }

    @Test
    fun certifiedSteppingRecordsStepValue() {
        val record = InferenceCertificationStore.toCertifiedOptions(
            case = case(),
            decision = PromotionDecision.Promote,
            decodeStepTokens = 2,
            configHash = null,
            nowElapsedMs = 1L,
        )

        assertNotNull(record)
        assertEquals("认证步长应原样记录", 2, record!!.decodeStepTokens)
    }

    @Test
    fun rejectedCandidateProducesNoRecord() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(sampleCount = 1) })
        assertTrue(d is PromotionDecision.Reject)

        assertNull(
            "Reject 不应产出认证记录",
            InferenceCertificationStore.toCertifiedOptions(case(), d, decodeStepTokens = 1, configHash = null, nowElapsedMs = 1L),
        )
    }
}
