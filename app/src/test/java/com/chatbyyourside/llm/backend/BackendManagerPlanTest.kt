package com.chatbyyourside.llm.backend

import com.chatbyyourside.llm.GenerationExecutionControl
import com.chatbyyourside.llm.GenerationSafetyPolicy
import com.chatbyyourside.llm.metrics.CompletionReason
import com.chatbyyourside.llm.profile.InferencePerformanceMode
import com.chatbyyourside.llm.profile.InferenceProfileResolver
import com.chatbyyourside.llm.profile.ResolvedInferencePlan
import com.chatbyyourside.llm.profile.RuntimeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BackendManager 执行语义测试（Task 7 Step 4）。
 *
 * BackendManager 依赖 Android Context（BackendSelector/MnnBackend），无法纯 JVM 实例化；
 * 本测试锁定其执行**输入**（resolvedPlan.attempts 的顺序/内容）与跨尝试执行语义
 * （CPU 优化失败推进兼容、首 delta 后不再换后端），这些由 [GenerationExecutionControl] 承载。
 */
class BackendManagerPlanTest {

    private lateinit var resolver: InferenceProfileResolver

    @Before
    fun setUp() {
        val dir = createTempDir()
        resolver = InferenceProfileResolver(dir, dir.absolutePath + "/m/config.json")
    }

    private fun plan(
        preference: BackendPreference,
        mode: InferencePerformanceMode = InferencePerformanceMode.BALANCED,
        openclHealth: OpenClHealthState = OpenClHealthState.UNKNOWN,
    ): ResolvedInferencePlan = resolver.resolve(
        mode = mode,
        backendPreference = preference,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = 4,
        lookahead = false,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealth = openclHealth,
    )

    @Test
    fun cpuExecutionSequenceIsOptimizedThenCompatibility() {
        val p = plan(BackendPreference.MNN_CPU)

        val sequence = p.attempts.map { it.variant }
        assertEquals(listOf(RuntimeVariant.CPU_OPTIMIZED, RuntimeVariant.CPU_COMPATIBILITY), sequence)
        // CPU 优化失败推进 CPU 兼容：BackendManager 不黑名单 CPU，两变体都在链中。
        assertTrue(p.attempts.all { it.backend == BackendType.MNN_CPU })
    }

    @Test
    fun healthyOpenclAttemptLeadsExecutionAndRequiresProbe() {
        val p = plan(BackendPreference.AUTO, openclHealth = OpenClHealthState.MODEL_OK)

        assertEquals(RuntimeVariant.OPENCL, p.attempts.first().variant)
        assertTrue(p.attempts.first().requiresProbe)
        assertFalse(p.attempts.first { it.variant == RuntimeVariant.CPU_OPTIMIZED }.requiresProbe)
    }

    @Test
    fun autoPlanNeverContainsQnn() {
        val p = plan(BackendPreference.AUTO)
        val npu = plan(BackendPreference.MNN_NPU)

        assertFalse(p.attempts.any { it.backend == BackendType.MNN_NPU })
        assertFalse(npu.attempts.any { it.backend == BackendType.MNN_NPU })
    }

    @Test
    fun firstDeltaDisablesTransparentFallbackAcrossAttempts() {
        // 模拟 BackendManager 执行序列：CPU_OPTIMIZED 产出 token 后失败。
        // 请求级 control 累计 token -> 不再允许下一尝试，返回 typed 终止原因。
        val control = GenerationExecutionControl(
            policy = GenerationSafetyPolicy(maxTokens = 2048, stallTimeoutMs = 1_000, wallClockTimeoutMs = 5_000),
            startedElapsedMs = 100,
        )
        control.onProgress("cpu-opt", generatedTokens = 50, progressElapsedMs = 200)
        // 失败发生在已有可见输出之后：首个 delta 后禁止透明换后端。
        control.requestStop(CompletionReason.BACKEND_FAILURE)

        assertFalse(control.canTryNextBackend())
        assertEquals(CompletionReason.BACKEND_FAILURE, control.reason())
        // 未产出任何 token 的失败（remainingTokens 未降）仍允许推进到下一尝试。
        val noOutput = GenerationExecutionControl(
            policy = GenerationSafetyPolicy(maxTokens = 2048, stallTimeoutMs = 1_000, wallClockTimeoutMs = 5_000),
            startedElapsedMs = 100,
        )
        assertTrue(noOutput.canTryNextBackend())
    }
}
