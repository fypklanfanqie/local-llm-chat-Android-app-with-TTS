package com.chatbyyourside.llm.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实验促进门禁测试（Task 15 Step 1）。 */
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
}
