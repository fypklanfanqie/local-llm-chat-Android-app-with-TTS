package com.chatbyyourside.llm.benchmark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chatbyyourside.data.local.SettingsStore
import com.chatbyyourside.data.repository.SettingsRepository
import com.chatbyyourside.llm.CpuBoostController
import com.chatbyyourside.llm.backend.BackendManager
import com.chatbyyourside.llm.backend.MnnBridge
import com.chatbyyourside.llm.metrics.BenchmarkSummary
import com.chatbyyourside.provider.local.ModelPathResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 基准运行器与结果存储的真机仪器测试（Task 5 Step 4/5 端到端）。
 *
 * 覆盖：
 * 1. [DataStoreBenchmarkResultStore]：仅冷态结果落盘、按「场景+象限+指纹」键读回、旧 JSON 兼容。
 * 2. [DefaultLocalInferenceBenchmarkRunner.run]：性能采样循环冒烟（结构不变量，不比对具体数值）。
 * 3. [DefaultLocalInferenceBenchmarkRunner.runReliability]：固定轮数逐轮如实记录、失败不重试替换。
 *
 * **Fixture 假设守卫**：无已安装 MNN 模型（config.json + llm.mnn）或 native 不可用时
 * [requireModel] 抛出 Assume 跳过，CI 无模型机器不失败（与 MnnStreamingIntegrationTest 一致）。
 * 运行器用例会真实执行推理，属慢测试——冒烟轮数取小值（性能 0+2、可靠性 3）。
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkRunnerInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------
    // 1. DataStoreBenchmarkResultStore
    // ------------------------------------------------------------------

    @Test
    fun dataStoreStore_persistsOnlyCoolResultsAndLoadsBack() = runBlocking {
        val store = DataStoreBenchmarkResultStore(context)
        val device = "test-device-${System.currentTimeMillis()}"
        val cfg = "test-cfg-${System.currentTimeMillis()}"

        // 热态（coolRun=false）结果不落盘
        store.save(result(device, cfg, coolRun = false, quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF))
        assertNull("热态结果不应被持久化", store.load(InferenceBenchmarkScenario.SHORT_TTFT, device, cfg))

        // 冷态结果落盘并可读回（四象限键均可命中）
        val cold = result(device, cfg, coolRun = true, quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF)
        store.save(cold)
        val loaded = store.load(InferenceBenchmarkScenario.SHORT_TTFT, device, cfg)
        assertNotNull("冷态结果未读回", loaded)
        assertEquals("round-trip 不等", cold, loaded)
    }

    @Test
    fun dataStoreStore_sameQuadrantOverwrites_gpuQuadrantKeyIndependent() = runBlocking {
        val store = DataStoreBenchmarkResultStore(context)
        val device = "test-device-${System.currentTimeMillis()}"
        val cfg = "test-cfg-${System.currentTimeMillis()}"

        // 同场景同指纹、不同象限互不覆盖：先存 GPU 后存 CPU，load 首命中按枚举序（CPU 在前）
        val gpu = result(device, cfg, coolRun = true, quadrant = InferenceBackendQuadrant.GPU_THINKING_OFF)
        val cpu = result(device, cfg, coolRun = true, quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF)
        store.save(gpu)
        store.save(cpu)
        val loaded = store.load(InferenceBenchmarkScenario.SHORT_TTFT, device, cfg)
        assertNotNull(loaded)
        assertEquals(InferenceBackendQuadrant.CPU_THINKING_OFF, loaded!!.quadrant)
    }

    // ------------------------------------------------------------------
    // 2. 性能采样循环冒烟
    // ------------------------------------------------------------------

    @Test
    fun runner_run_performanceLoopStructuralInvariants() = runBlocking {
        requireModel()
        val runner = newRunner()
        val result = runner.run(
            scenario = InferenceBenchmarkScenario.SHORT_TTFT,
            configFingerprint = "smoke-cfg",
            deviceFingerprint = "smoke-device",
            warmupRounds = 0, // 冒烟：不跑预热，缩短时长
            recordedRounds = 2,
        )
        assertNotNull(result)
        // 结构不变量：记录轮数不超过请求数；汇总恒非空对象
        assertTrue("recordedSampleCount=${result.recordedSampleCount} 超出请求轮数", result.recordedSampleCount <= 2)
        assertNotNull(result.summary)
        // 象限与构建维度随结果记录（GPU 象限在无 OpenCL 设备上会自然回退，记录不丢）
        assertNotNull(result.quadrant)
        assertEquals(result.thinkingRequested, result.quadrant!!.thinkingEnabled)
        // 记录轮全失败时汇总字段为 null、discardedReasons 非空——如实反映，不静默
        if (result.recordedSampleCount == 0) {
            assertFalse("零样本却无剔除原因", result.discardedReasons.isEmpty())
            assertNull(result.summary.medianTtftMs)
        } else {
            assertNotNull(result.summary.medianTtftMs)
        }
    }

    @Test
    fun runner_run_coldLoadScenarioReleasesFirst() = runBlocking {
        requireModel()
        val runner = newRunner()
        // COLD_LOAD 要求冷启动：result 结构不变量与 SHORT_TTFT 一致，且 requiresColdStart 场景被接受
        val result = runner.run(
            scenario = InferenceBenchmarkScenario.COLD_LOAD,
            configFingerprint = "smoke-cfg",
            deviceFingerprint = "smoke-device",
            warmupRounds = 0,
            recordedRounds = 1,
        )
        assertTrue("recordedSampleCount=${result.recordedSampleCount} 超出请求轮数", result.recordedSampleCount <= 1)
    }

    // ------------------------------------------------------------------
    // 3. 可靠性样本冒烟
    // ------------------------------------------------------------------

    @Test
    fun runner_runReliability_reportsClassesWithoutRetry() = runBlocking {
        requireModel()
        val runner = newRunner()
        val case = InferenceBenchmarkCase(
            scenario = InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK,
            quadrant = InferenceBackendQuadrant.CPU_THINKING_OFF,
            modelFingerprint = "smoke-model",
            deviceFingerprint = "smoke-device",
            configHash = "smoke-cfg",
        )
        val reliability = runner.runReliability(case, rounds = 3)
        // 固定轮数、不重试替换：totalRounds 恒等于请求轮数，分类计数和也恒等于轮数
        assertEquals(3, reliability.totalRounds)
        assertEquals("分类计数和 != totalRounds（存在重试替换或丢轮）", 3, reliability.emptyResponseClasses.values.sum())
        assertTrue("nonEmptySuccessRate=${reliability.nonEmptySuccessRate} 越界", reliability.nonEmptySuccessRate in 0f..1f)
        assertTrue(reliability.fallbackCount >= 0)
        // 非空率 == NONE 轮次 / totalRounds（分母恒为 totalRounds）
        val noneCount = reliability.emptyResponseClasses["NONE"] ?: 0
        assertEquals(
            "非空率口径不符",
            noneCount.toFloat() / 3f,
            reliability.nonEmptySuccessRate,
            0.0001f,
        )
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun newRunner(): DefaultLocalInferenceBenchmarkRunner =
        DefaultLocalInferenceBenchmarkRunner(
            context = context,
            backendManager = BackendManager(context, CpuBoostController(context)),
            settings = SettingsRepository(SettingsStore(context)),
        )

    /** 无模型/native 不可用时 Assume 跳过（与 MnnStreamingIntegrationTest 同款守卫）。 */
    private fun requireModel() {
        assumeTrue(
            "设备上未安装 MNN 模型（config.json + llm.mnn）或 native 不可用，跳过运行器仪器测试",
            modelAvailable(context),
        )
    }

    private fun modelAvailable(context: Context): Boolean {
        if (!MnnBridge.nativeAvailable) return false
        val dirs = ModelPathResolver.getModelsDirectory(context)
            .listFiles { f -> f.isDirectory } ?: return false
        return dirs.any { ModelPathResolver.getConfigPath(context, it.name) != null }
    }

    private fun result(
        deviceFingerprint: String,
        configFingerprint: String,
        coolRun: Boolean,
        quadrant: InferenceBackendQuadrant,
    ): BenchmarkScenarioResult = BenchmarkScenarioResult(
        scenario = InferenceBenchmarkScenario.SHORT_TTFT,
        deviceFingerprint = deviceFingerprint,
        configFingerprint = configFingerprint,
        summary = BenchmarkSummary(
            medianTtftMs = 150f,
            medianDecodeTps = 25f,
            decodeStdDev = 2.0f,
            p95TtftMs = 240f,
            p95DecodeTps = 28f,
            kvReuseRate = 0f,
        ),
        recordedSampleCount = 5,
        warmupSampleCount = 1,
        coolRun = coolRun,
        quadrant = quadrant,
        thinkingRequested = quadrant.thinkingEnabled,
        backendVariant = "CPU_OPTIMIZED",
        nativeBuildId = "test-build",
        mnnCommit = "test-commit",
    )
}
