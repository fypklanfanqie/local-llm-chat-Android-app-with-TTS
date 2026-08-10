package com.chatbyyourside.llm.benchmark

import android.content.Context
import android.util.Log
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.data.local.LocalInferenceSettings
import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.data.repository.SettingsRepository
import com.chatbyyourside.llm.ThermalLevel
import com.chatbyyourside.llm.ThermalMonitor
import com.chatbyyourside.llm.backend.BackendManager
import com.chatbyyourside.llm.backend.BackendPreference
import com.chatbyyourside.llm.backend.EmptyOutputFallbackPolicy
import com.chatbyyourside.llm.backend.GenerationOutputPolicy
import com.chatbyyourside.llm.backend.MnnBridge
import com.chatbyyourside.llm.metrics.BenchmarkSummary
import com.chatbyyourside.llm.metrics.InferenceTurnRecord
import com.chatbyyourside.llm.metrics.summarize
import com.chatbyyourside.llm.profile.InferencePerformanceMode
import com.chatbyyourside.llm.profile.InferenceProfileResolver
import com.chatbyyourside.llm.profile.OpenClHealthState
import com.chatbyyourside.llm.profile.ResolvedInferencePlan
import com.chatbyyourside.llm.profile.RuntimeVariant
import com.chatbyyourside.llm.template.EmptyResponseClass
import com.chatbyyourside.llm.template.ThinkingOutputClassifier
import com.chatbyyourside.llm.template.ThinkingTemplateCapability
import com.chatbyyourside.llm.template.ThinkingTemplateCapabilityResolver
import com.chatbyyourside.provider.local.ModelPathResolver
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * 默认本地推理基准运行器（Task 5 Step 4/5，契约 + 核心循环实现）。
 *
 * 实现 [LocalInferenceBenchmarkRunner] 的采样循环：
 * - **热检查**：[isThermallyHot] 用注入的 [ThermalMonitor]（可空）判 SEVERE+ 为热；未注入时
 *   自建 monitor 并调 [ThermalMonitor.startThermalMonitoring] 进入采样状态（Task 5 review I-1）。
 *   API 29+/PowerManager 缺席（或注入实例未启动监控）时为 no-op，热读取恒为 NONE——基准默认
 *   不拒绝热态；需真实热防护的调用方（Task 7 UI 入口）请注入已启动监控的实例。
 * - **采样循环**：`warmupRounds` 预热轮（丢弃）+ `recordedRounds` 记录轮（入样本）；每轮调
 *   [BackendManager.generate]（固定中文探针 prompt + 固定采样参数），取
 *   [BackendManager.lastTurnRecord] 为样本；热态样本丢弃并记入 [BenchmarkScenarioResult.discardedReasons]。
 * - **象限**：被测象限由设置快照推导（[InferenceBackendQuadrant.of]，AUTO→GPU、NPU→CPU 口径见该函数）；
 *   思考开关随象限透传为 generate 的 enableThinking。
 * - **COLD_LOAD 场景**：先 [BackendManager.release] 使首轮为冷启动。
 * - **可靠性**（[runReliability]）：固定轮数逐轮如实记录空响应分类，失败样本**不重试替换**；
 *   GPU 象限开启 Task 4 的 CPU_BEFORE_FIRST_DELTA 回退策略，回退轮次计入
 *   [ReliabilityResult.fallbackCount]。
 *
 * 范围控制（本任务不实现）：UI 接入（Task 7）、自动调参（Task 6 认证门）、各场景专用
 * fixture（如两轮 KV 复用构造、长 prefill prompt）——本实现所有场景共用同一固定探针，
 * 场景主要决定冷启动行为与归档维度。
 *
 * final review I3（裁决：文档化延迟）：四象限归档（[run] 各象限 save）与 [runReliability]
 * 在**生产主源码无触发入口**——仅 Lookahead 认证基准经设置页接线。四象限/可靠性验证由
 * CI/真机验收执行；UI 入口留后续版本，本版本不新增（避免范围膨胀）。
 */
open class DefaultLocalInferenceBenchmarkRunner(
    private val context: Context,
    private val backendManager: BackendManager,
    private val settings: SettingsRepository,
    private val thermalMonitor: ThermalMonitor? = null,
) : LocalInferenceBenchmarkRunner {
    // open 仅供仪器测试桩化 isThermallyHot 验证热守卫（Task 5 review M-4）；生产不子类化。

    private val effectiveThermalMonitor: ThermalMonitor = thermalMonitor
        ?: ThermalMonitor(context) { DEFAULT_BIG_CORE_COUNT }.apply {
            // Task 5 review I-1：自建 monitor 也必须启动采样，否则 currentLevel 恒为 NONE、
            // isThermallyHot 恒 false——热态样本不会被丢弃（「rejecting hot runs」默认不生效）。
            // startThermalMonitoring 可重复调用安全（内部 started 守卫）、无主线程要求；
            // API<29 或 PowerManager 缺席时为 no-op，热读取恒为 NONE（基准默认不拒绝，见类 KDoc）。
            startThermalMonitoring { /* 基准只读取热档位，不消费降频回调 */ }
        }

    private val templateResolver = ThinkingTemplateCapabilityResolver()

    /** 当前是否过热（SEVERE 及以上视为热，基准样本会污染，拒绝运行/采样）。 */
    override fun isThermallyHot(): Boolean = when (effectiveThermalMonitor.currentLevel()) {
        ThermalLevel.SEVERE, ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY -> true
        else -> false
    }

    override suspend fun run(
        scenario: InferenceBenchmarkScenario,
        configFingerprint: String,
        deviceFingerprint: String,
        warmupRounds: Int,
        recordedRounds: Int,
        candidateOverrides: CandidateOverrides? = null,
    ): BenchmarkScenarioResult {
        if (isThermallyHot()) {
            return rejectedResult(
                scenario, configFingerprint, deviceFingerprint,
                listOf(REASON_THERMALLY_HOT),
            )
        }
        val snapshot = settings.getLocalInferenceSettingsNow()
        var quadrant = InferenceBackendQuadrant.of(snapshot.backend, snapshot.deepThinking)
        // Task 7 M-4：候选旁路只对 CPU 变体有意义（lookahead / 多 token 步进仅 CPU 生效）——
        // 强制 CPU 象限测量，防止 GPU/AUTO 偏好下把 OPENCL 样本当作候选证据（证据错配，
        // 与 Task 6 review I-3 的「步进证据按变体守卫」同源约束）。思考开关沿用设置快照推导值。
        if (candidateOverrides != null) {
            quadrant = if (quadrant.thinkingEnabled) {
                InferenceBackendQuadrant.CPU_THINKING_ON
            } else {
                InferenceBackendQuadrant.CPU_THINKING_OFF
            }
        }

        // COLD_LOAD：先卸载模型，让首轮成为真实冷启动（mmap + load）。
        if (scenario.requiresColdStart) {
            Log.i(TAG, "场景 ${scenario.storageKey} 需要冷启动，先 release 模型")
            runCatching { backendManager.release() }
        }

        val modelPath = resolveModelPath(snapshot)
        if (modelPath == null) {
            return rejectedResult(scenario, configFingerprint, deviceFingerprint, listOf(REASON_NO_MODEL))
        }
        val plan = buildPlan(snapshot, quadrant, modelPath, candidateOverrides)
        val templateCapability = templateResolver.resolve(File(modelPath).parentFile ?: File(modelPath))

        val discardedReasons = mutableListOf<String>()
        val samples = mutableListOf<InferenceTurnRecord>()
        var warmupDone = 0
        val totalRounds = warmupRounds + recordedRounds
        for (round in 0 until totalRounds) {
            if (isThermallyHot()) {
                discardedReasons += REASON_THERMALLY_HOT
                break
            }
            val record = runOneRound(modelPath, snapshot, plan, quadrant, templateCapability)
            if (record == null) {
                discardedReasons += "NO_RECORD_ROUND_${round + 1}"
                continue
            }
            if (round < warmupRounds) warmupDone++ else samples += record
        }

        val summary = summarize(samples)
        // Task 5 review I-2：实际后端分布按样本级 record.backend 统计——样本级字段不落盘，
        // 归档后经 actualBackendCounts 可追溯「真 GPU」与「回退 CPU」（backendVariant 只记
        // 计划首个尝试，OpenCL 加载失败落到 CPU attempt 时无法分辨）。
        val actualBackendCounts = samples
            .mapNotNull { it.backend?.name }
            .groupingBy { it }
            .eachCount()
        val coolRun = samples.isNotEmpty() && discardedReasons.none { it.startsWith(REASON_THERMALLY_HOT) }
        return BenchmarkScenarioResult(
            scenario = scenario,
            deviceFingerprint = deviceFingerprint,
            configFingerprint = configFingerprint,
            summary = summary,
            recordedSampleCount = samples.size,
            warmupSampleCount = warmupDone,
            coolRun = coolRun,
            discardedReasons = discardedReasons,
            quadrant = quadrant,
            thinkingRequested = quadrant.thinkingEnabled,
            backendVariant = plan.firstAttempt?.variant?.name,
            actualBackendCounts = actualBackendCounts,
            nativeBuildId = MnnBridge.runtimeInfo?.nativeBuildId,
            mnnCommit = MnnBridge.runtimeInfo?.mnnCommit,
        )
    }

    override suspend fun runReliability(case: InferenceBenchmarkCase, rounds: Int): ReliabilityResult {
        require(rounds >= 0) { "rounds 必须 >= 0" }
        // Task 5 review M-3：热守卫入口早退——热降频同样污染可靠性样本。ReliabilityResult 无
        // coolRun 拒绝通道（与 run() 的 rejectedResult 语义不同型，返回全 NO_RECORD 会被误当
        // 伪有效结果归档），故抛异常让调用方明确感知（本函数既有 require 亦为抛错风格）；
        // 调用方（Task 7 UI 入口）应先用 isThermallyHot() 查询或捕获本异常。
        if (isThermallyHot()) {
            throw IllegalStateException("设备过热，可靠性基准未执行")
        }
        val snapshot = settings.getLocalInferenceSettingsNow()
        val modelPath = resolveModelPath(snapshot)
        val classes = mutableMapOf<String, Int>()
        var fallbackCount = 0
        var nonEmptyCount = 0
        if (modelPath != null) {
            val plan = buildPlan(snapshot, case.quadrant, modelPath)
            val templateCapability = templateResolver.resolve(File(modelPath).parentFile ?: File(modelPath))
            for (round in 0 until rounds) {
                // 失败样本如实记录，绝不用重试替换（每轮只执行一次）。
                val record = runOneRound(
                    modelPath, snapshot, plan, case.quadrant, templateCapability,
                    allowCpuFallback = true,
                )
                val cls = record?.emptyResponseClass ?: NO_RECORD_CLASS
                classes[cls] = (classes[cls] ?: 0) + 1
                if (cls == EmptyResponseClass.NONE.name) nonEmptyCount++
                if (record?.downgradeReasons?.contains(BackendManager.EMPTY_GPU_OUTPUT_FALLBACK) == true) fallbackCount++
            }
        } else if (rounds > 0) {
            classes[NO_MODEL_CLASS] = rounds
        }
        return ReliabilityResult(
            emptyResponseClasses = classes,
            fallbackCount = fallbackCount,
            nonEmptySuccessRate = if (rounds > 0) nonEmptyCount.toFloat() / rounds else 0f,
            totalRounds = rounds,
        )
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 一次探针生成：固定 prompt + 固定采样参数，返回本轮的最终遥测记录（异常返回 null）。 */
    private suspend fun runOneRound(
        modelPath: String,
        snapshot: LocalInferenceSettings,
        plan: ResolvedInferencePlan,
        quadrant: InferenceBackendQuadrant,
        templateCapability: ThinkingTemplateCapability,
        allowCpuFallback: Boolean = false,
    ): InferenceTurnRecord? {
        val classifier = ThinkingOutputClassifier(
            thinkingRequested = quadrant.thinkingEnabled,
            templateCapability = templateCapability,
        )
        // 性能基准用默认策略（DISABLED，不引入回退偏置）；可靠性基准在 GPU 象限开启
        // CPU_BEFORE_FIRST_DELTA，使 EMPTY_GPU_OUTPUT_FALLBACK 可被观察计数。
        val outputPolicy = GenerationOutputPolicy(
            emptyOutputFallback = if (allowCpuFallback && quadrant.usesGpu) {
                EmptyOutputFallbackPolicy.CPU_BEFORE_FIRST_DELTA
            } else {
                EmptyOutputFallbackPolicy.DISABLED
            },
        )
        try {
            backendManager.generate(
                modelPath = modelPath,
                messages = PROBE_MESSAGES,
                maxTokens = plan.maxOutputTokens,
                temperature = snapshot.temperature,
                topP = AppConfig.LLM.DEFAULT_TOP_P,
                repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
                enableThinking = quadrant.thinkingEnabled,
                onToken = { true }, // 基准只测速，不截断
                thinkingRequested = quadrant.thinkingEnabled,
                templateCapability = templateCapability.name,
                thinkingClassifier = classifier,
                resolvedPlan = plan,
                outputPolicy = outputPolicy,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 后端异常：本轮如实记为失败（记录可能不存在），不重试替换。
            Log.w(TAG, "基准轮生成异常（本轮如实计失败）: ${e.message}")
            return null
        }
        // MnnBackend 在 generateStreamMessages 的 finally 内收口遥测记录，generate 返回后必然可读。
        return backendManager.lastTurnRecord()
    }

    /**
     * 构建固定参数的计划：平衡档、象限决定后端偏好、OpenCL 健康强制按可用入链。
     *
     * @param candidateOverrides Task 7 M-4 候选配置旁路（仅认证基准流程传入；null=生产语义）：
     *        非 null 时以候选值为「用户请求」输入并构造合成 [CertifiedInferenceOptions]（variant 恒
     *        [RuntimeVariant.CPU_OPTIMIZED]——lookahead/步进认证只对 CPU 变体有意义，resolver 门禁
     *        matchesCpuVariant 只认该变体），使 resolver 门禁放行候选配置；device/model 指纹留空
     *        （resolver 只匹配 variant，不读指纹）；native 身份取运行时握手（MnnBridge.runtimeInfo）
     *        供归档。KDoc 约束：**仅供基准流程**，生产路径（LocalChatProvider）不传。
     */
    private fun buildPlan(
        snapshot: LocalInferenceSettings,
        quadrant: InferenceBackendQuadrant,
        modelPath: String,
        candidateOverrides: CandidateOverrides? = null,
    ): ResolvedInferencePlan = InferenceProfileResolver(context.cacheDir, modelPath).resolve(
        // 基准固定平衡档：性能模式不是本任务的控制变量（Task 6 认证门再做调参）。
        mode = InferencePerformanceMode.BALANCED,
        // GPU 象限强制 MNN_GPU 偏好；OpenCL 健康强制按可用入链——基准要测 GPU 路径本身，
        // 实际加载/生成失败仍会自然回退 CPU，并在记录 backend/attemptTrace 中如实体现。
        backendPreference = if (quadrant.usesGpu) BackendPreference.MNN_GPU else BackendPreference.MNN_CPU,
        contextTokens = snapshot.contextLen,
        maxOutputTokens = snapshot.maxTokens,
        thermalAdmittedThreads = snapshot.threads.coerceAtLeast(1),
        // 旁路时以候选值为「用户请求」输入（与合成认证同源，使 lookahead && cert.lookahead 门禁
        // 放行候选配置）；生产路径仍取设置快照（用户请求只是使用既有认证的许可）。
        lookahead = candidateOverrides?.lookahead ?: snapshot.lookahead,
        temperature = snapshot.temperature,
        topP = AppConfig.LLM.DEFAULT_TOP_P,
        repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
        openclHealth = if (quadrant.usesGpu) OpenClHealthState.PROBE_OK else OpenClHealthState.UNKNOWN,
        certifiedOptions = candidateOverrides?.let { overrides ->
            CertifiedInferenceOptions(
                deviceFingerprint = "",
                modelFingerprint = "",
                variant = RuntimeVariant.CPU_OPTIMIZED.name,
                nativeBuildId = MnnBridge.runtimeInfo?.nativeBuildId ?: "",
                mnnCommit = MnnBridge.runtimeInfo?.mnnCommit ?: "",
                lookahead = overrides.lookahead,
                decodeStepTokens = overrides.decodeStepTokens,
            )
        },
    )

    /** 解析当前选中模型的 config.json 路径；未选模型/文件缺失返回 null。 */
    private suspend fun resolveModelPath(snapshot: LocalInferenceSettings): String? {
        val activeModelId = settings.getActiveLocalModelIdNow()
        if (activeModelId.isNullOrBlank()) {
            Log.w(TAG, "未选择本地模型，无法运行基准")
            return null
        }
        val path = ModelPathResolver.getLoadPath(context, activeModelId)
        if (path == null) Log.w(TAG, "模型文件缺失: $activeModelId")
        return path
    }

    private fun rejectedResult(
        scenario: InferenceBenchmarkScenario,
        configFingerprint: String,
        deviceFingerprint: String,
        reasons: List<String>,
    ): BenchmarkScenarioResult = BenchmarkScenarioResult(
        scenario = scenario,
        deviceFingerprint = deviceFingerprint,
        configFingerprint = configFingerprint,
        summary = BenchmarkSummary(),
        recordedSampleCount = 0,
        warmupSampleCount = 0,
        coolRun = false,
        discardedReasons = reasons,
    )

    companion object {
        private const val TAG = "DefaultLocalInferenceBenchmarkRunner"

        /** 固定中文探针 prompt（与 MnnStreamingIntegrationTest.probeMessages 同源，保证口径一致）。 */
        val PROBE_MESSAGES: List<ChatMessage> = listOf(
            ChatMessage(
                role = "system",
                content = "你是中文测试助手。你的每条回复都必须以中文为主，可以适当包含 emoji 表情符号。",
            ),
            ChatMessage(
                role = "user",
                content = "请用三句话介绍你自己，必须包含中文，并带上一个 emoji。",
            ),
        )

        /** 热态拒绝原因（isThermallyHot 命中时写入 discardedReasons）。 */
        private const val REASON_THERMALLY_HOT = "THERMALLY_HOT"

        /** 无模型/模型文件缺失拒绝原因。 */
        private const val REASON_NO_MODEL = "NO_MODEL"

        /** 可靠性轮次未产出遥测记录时的分类占位（后端异常/取消等）。 */
        private const val NO_RECORD_CLASS = "NO_RECORD"

        /** 未选模型时全部轮次的分类占位。 */
        private const val NO_MODEL_CLASS = "NO_MODEL"

        /** 默认构造 ThermalMonitor 时的大核数兜底（仅用于 recommendedThreadCount 比例，不影响本类逻辑）。 */
        private const val DEFAULT_BIG_CORE_COUNT = 4
    }
}
