package com.chatbyyourside.llm.benchmark

import com.chatbyyourside.llm.profile.RuntimeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            decodeStepTokens = 1,
            // lookahead 基准（开 vs 关对比）-> 产生 lookahead 证据。
            lookaheadEvidence = true,
            configHash = "candidate-cfg",
            nowElapsedMs = 42_000L,
        )

        assertNotNull("Promote 应产出认证记录", record)
        record!!.let {
            // 身份分量来自用例 + 调用方传入的 native 构建身份（Task 6 review I-2：不再读 MnnBridge）。
            assertEquals("device-a", it.deviceFingerprint)
            assertEquals("model-a", it.modelFingerprint)
            assertEquals("CPU 象限应映射到 CPU_OPTIMIZED 变体", RuntimeVariant.CPU_OPTIMIZED.name, it.variant)
            assertEquals("build-1", it.nativeBuildId)
            assertEquals("abc123", it.mnnCommit)
            assertTrue("lookahead 基准应记录 lookahead 证据", it.lookahead)
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
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            decodeStepTokens = 1,
            lookaheadEvidence = true,
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
            nativeBuildId = "build-1",
            mnnCommit = "abc123",
            decodeStepTokens = 2,
            // 纯步进基准（step=2 vs 1）不产生 lookahead 证据（Task 6 review I-1）。
            lookaheadEvidence = false,
            configHash = null,
            nowElapsedMs = 1L,
        )

        assertNotNull(record)
        assertEquals("认证步长应原样记录", 2, record!!.decodeStepTokens)
        assertFalse("纯步进认证不得记录 lookahead 证据", record.lookahead)
    }

    @Test
    fun rejectedCandidateProducesNoRecord() {
        val d = ExperimentalPromotionPolicy.evaluate(baseline, candidate { copy(sampleCount = 1) })
        assertTrue(d is PromotionDecision.Reject)

        assertNull(
            "Reject 不应产出认证记录",
            InferenceCertificationStore.toCertifiedOptions(
                case(), d,
                nativeBuildId = "build-1",
                mnnCommit = "abc123",
                decodeStepTokens = 1,
                lookaheadEvidence = true,
                configHash = null,
                nowElapsedMs = 1L,
            ),
        )
    }

    @Test
    fun promotedCandidateWithoutNativeIdentityProducesNoRecord() {
        // Task 6 review I-2：握手缺席（nativeBuildId/mnnCommit 空/空白）时不得生成认证记录——
        // 否则认证键退化为 device+model+variant 三分量，native 重建后旧二进制证据继续启用
        // 步进/lookahead（证据错配）。无 native 身份证明不认证。
        assertNull(
            "nativeBuildId 为空不应认证",
            InferenceCertificationStore.toCertifiedOptions(
                case(), PromotionDecision.Promote,
                nativeBuildId = "",
                mnnCommit = "abc123",
                decodeStepTokens = 1,
                lookaheadEvidence = true,
                configHash = null,
                nowElapsedMs = 1L,
            ),
        )
        assertNull(
            "mnnCommit 空白不应认证",
            InferenceCertificationStore.toCertifiedOptions(
                case(), PromotionDecision.Promote,
                nativeBuildId = "build-1",
                mnnCommit = "  ",
                decodeStepTokens = 1,
                lookaheadEvidence = true,
                configHash = null,
                nowElapsedMs = 1L,
            ),
        )
    }
}
