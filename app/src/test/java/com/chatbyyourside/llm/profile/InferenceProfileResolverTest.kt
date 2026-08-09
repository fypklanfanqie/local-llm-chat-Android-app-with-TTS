package com.chatbyyourside.llm.profile

import com.chatbyyourside.llm.backend.BackendPreference
import com.chatbyyourside.llm.backend.BackendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** InferenceProfileResolver 尝试链 / 原生 JSON / 指纹测试（Task 7 Steps 1–2）。 */
class InferenceProfileResolverTest {

    private lateinit var resolver: InferenceProfileResolver

    @Before
    fun setUp() {
        val dir = createTempDir()
        resolver = InferenceProfileResolver(cacheDir = dir, modelPath = dir.absolutePath + "/m/config.json")
    }

    private fun plan(
        preference: BackendPreference,
        mode: InferencePerformanceMode = InferencePerformanceMode.BALANCED,
        threads: Int = 4,
        opencl: Boolean = false,
        lookahead: Boolean = false,
    ): ResolvedInferencePlan = resolver.resolve(
        mode = mode,
        backendPreference = preference,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = threads,
        lookahead = lookahead,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealthy = opencl,
    )

    private fun variants(p: ResolvedInferencePlan): List<RuntimeVariant> = p.attempts.map { it.variant }

    @Test
    fun autoWithoutHealthyOpenclFallsBackToCpuOptimizedThenCompatibility() {
        val p = plan(BackendPreference.AUTO, opencl = false)

        assertEquals(listOf(RuntimeVariant.CPU_OPTIMIZED, RuntimeVariant.CPU_COMPATIBILITY), variants(p))
        assertTrue(p.attempts.none { it.backend == BackendType.MNN_GPU })
        assertFalse(p.attempts.any { it.backend == BackendType.MNN_NPU })
    }

    @Test
    fun autoWithHealthyOpenclPlacesOpenclFirstThenCpu() {
        val p = plan(BackendPreference.AUTO, opencl = true)

        assertEquals(
            listOf(RuntimeVariant.OPENCL, RuntimeVariant.CPU_OPTIMIZED, RuntimeVariant.CPU_COMPATIBILITY),
            variants(p),
        )
        assertEquals(BackendType.MNN_GPU, p.attempts.first().backend)
    }

    @Test
    fun qnnNeverAppearsInAutoEvenWithNpuPreference() {
        // 显式选 NPU 的标准版：QNN 不可用，解析为 CPU 并记录 UNSUPPORTED_SETTING。
        val p = plan(BackendPreference.MNN_NPU, opencl = false)

        assertTrue(p.attempts.none { it.backend == BackendType.MNN_NPU })
        assertTrue(p.downgradeReasons.contains(DowngradeReason.UNSUPPORTED_SETTING))
        assertEquals(RuntimeVariant.CPU_OPTIMIZED, p.attempts.first().variant)
    }

    @Test
    fun explicitGpuWithoutHealthyOpenclRecordsDowngradeAndFallsBackToCpu() {
        val p = plan(BackendPreference.MNN_GPU, opencl = false)

        assertTrue(p.downgradeReasons.contains(DowngradeReason.OPENCL_UNHEALTHY))
        assertTrue(p.attempts.none { it.backend == BackendType.MNN_GPU })
        assertEquals(RuntimeVariant.CPU_OPTIMIZED, p.attempts.first().variant)
    }

    @Test
    fun thermalDowngradeCannotBeBypassedByMaximumSpeed() {
        val balanced = plan(BackendPreference.MNN_CPU, InferencePerformanceMode.BALANCED, threads = 2)
        val speed = plan(BackendPreference.MNN_CPU, InferencePerformanceMode.MAXIMUM_SPEED, threads = 2)

        // 热准入后的线程数不受模式影响（MAXIMUM_SPEED 不绕过温控）。
        assertEquals(2, balanced.powerPolicy.cpuThreads)
        assertEquals(2, speed.powerPolicy.cpuThreads)
    }

    @Test
    fun openclAttemptUsesThreadNum68Encoding() {
        val p = plan(BackendPreference.AUTO, opencl = true)

        val openclJson = p.attempts.first { it.variant == RuntimeVariant.OPENCL }.nativeConfigJson
        assertTrue(openclJson.contains("\"thread_num\":68"))
        assertTrue(openclJson.contains("\"backend_type\":\"opencl\""))
    }

    @Test
    fun compatibilityVariantUsesConservativePrecisionMemoryAndPower() {
        val p = plan(BackendPreference.MNN_CPU)

        val compat = p.attempts.first { it.variant == RuntimeVariant.CPU_COMPATIBILITY }.nativeConfigJson
        assertTrue(compat.contains("\"precision\":\"normal\""))
        assertTrue(compat.contains("\"memory\":\"normal\""))
        assertTrue(compat.contains("\"power\":\"normal\""))

        val optimized = p.attempts.first { it.variant == RuntimeVariant.CPU_OPTIMIZED }.nativeConfigJson
        assertTrue(optimized.contains("\"precision\":\"low\""))
        assertTrue(optimized.contains("\"power\":\"high\""))
    }

    @Test
    fun loadConfigHashIsStableAndSensitiveToChanges() {
        val a = plan(BackendPreference.MNN_CPU, threads = 4)
        val b = plan(BackendPreference.MNN_CPU, threads = 4)
        val c = plan(BackendPreference.MNN_CPU, threads = 6)

        assertEquals(a.attempts.first().loadConfigHash, b.attempts.first().loadConfigHash)
        assertNotEquals(a.attempts.first().loadConfigHash, c.attempts.first().loadConfigHash)
    }

    @Test
    fun canonicalJsonSortsKeysRecursively() {
        val canonical = InferenceProfileResolver.canonicalJsonString(
            kotlinx.serialization.json.buildJsonObject {
                put("z", 1)
                put("a", kotlinx.serialization.json.buildJsonObject { put("y", 2); put("b", 3) })
            },
        )
        // 根键 a 在 z 前；嵌套对象 b 在 y 前。
        val aIndex = canonical.indexOf("\"a\"")
        val zIndex = canonical.indexOf("\"z\"")
        assertTrue(aIndex in 0 until zIndex)
        assertTrue(canonical.indexOf("\"b\"") < canonical.indexOf("\"y\""))
    }

    @Test
    fun streamAndResidencyPoliciesFollowMode() {
        val balanced = plan(BackendPreference.MNN_CPU, InferencePerformanceMode.BALANCED)
        val speed = plan(BackendPreference.MNN_CPU, InferencePerformanceMode.MAXIMUM_SPEED)

        assertEquals(256, balanced.streamPolicy.batchMaxBytes)
        assertEquals(16, balanced.streamPolicy.batchMaxMs)
        assertEquals(512, speed.streamPolicy.batchMaxBytes)
        assertTrue(speed.powerPolicy.sustainedMode)
        assertTrue(speed.powerPolicy.aggressiveHint)
        assertTrue(speed.residencyPolicy.keepAliveMs > balanced.residencyPolicy.keepAliveMs)
    }
}
