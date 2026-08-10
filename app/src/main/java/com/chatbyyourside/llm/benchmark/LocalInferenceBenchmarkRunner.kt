package com.chatbyyourside.llm.benchmark

import com.chatbyyourside.llm.metrics.BenchmarkSummary
import kotlinx.serialization.Serializable

/**
 * 本地推理基准场景（Task 2 Step 4 + Task 5 Step 2 追加）。
 *
 * 每个场景对应一种需独立测量的性能/可靠性维度；基准运行按场景逐项执行，结果按「设备指纹 + 配置指纹 + 象限」归档。
 */
enum class InferenceBenchmarkScenario(
    val storageKey: String,
    val displayName: String,
    /** 该场景是否需要冷启动（卸载模型后重新 mmap），用于决定是否先 release。 */
    val requiresColdStart: Boolean,
) {
    /** 冷启动加载：首字前的模型加载+首 token 全程，关注 coldLoadMs 与 TTFT。 */
    COLD_LOAD("COLD_LOAD", "冷启动加载", requiresColdStart = true),

    /** 短首字延迟：极短 prompt 的 TTFT，反映交互响应感。 */
    SHORT_TTFT("SHORT_TTFT", "短首字延迟", requiresColdStart = false),

    /** 长 prefill：长 prompt 的 prefill 吞吐（promptTokens/prefillMs）。 */
    LONG_PREFILL("LONG_PREFILL", "长前缀填充", requiresColdStart = false),

    /** 固定解码：固定长度纯解码吞吐（decodeTps）与稳定性（decodeStdDev）。 */
    FIXED_DECODE("FIXED_DECODE", "固定长度解码", requiresColdStart = false),

    /** 第二轮 KV 复用：同会话第二轮的 TTFT 与复用率（kvReuse）。 */
    SECOND_TURN_KV_REUSE("SECOND_TURN_KV_REUSE", "第二轮 KV 复用", requiresColdStart = false),

    /** 空回答检查（Task 5）：固定 prompt 跑固定轮数，统计空响应分类分布与 GPU→CPU 回退率
     *  （可靠性维度，不做吞吐）；不要求冷启动。 */
    EMPTY_RESPONSE_CHECK("EMPTY_RESPONSE_CHECK", "空回答检查", requiresColdStart = false);

    companion object {
        fun fromStorageKey(key: String?): InferenceBenchmarkScenario? =
            entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * 单场景基准结果。
 *
 * @param scenario 场景。
 * @param deviceFingerprint 设备指纹（Task 9 定义；此处用字符串占位，含 SoC/Android/ABI）。
 * @param configFingerprint 配置指纹（模型+线程+上下文长度+模式等哈希）。
 * @param summary 中位数/P95/离散度汇总（仅纳入合格样本）。
 * @param recordedSampleCount 纳入汇总的有效样本数。
 * @param warmupSampleCount 预热样本数（不计入汇总）。
 * @param coolRun 是否冷态运行（未过热）；仅 coolRun=true 的结果会被持久化。
 * @param discardedReasons 被剔除样本的原因（如 hot/noisy/one-sample），保证不静默截断。
 * @param quadrant 被测象限（CPU/GPU × 思考开/关，Task 5 Step 3）；旧构造点/旧记录为 null。
 * @param thinkingRequested 本轮是否请求了深度思考。
 * @param backendVariant 计划首个尝试的运行时变体名（如 OPENCL / CPU_OPTIMIZED / CPU_COMPATIBILITY）。
 * @param nativeBuildId native 构建 ID（MnnBridge 运行时握手信息；握手缺席为 null）。
 * @param mnnCommit 钉定 MNN commit（native 握手信息；握手缺席为 null）。
 */
@Serializable
data class BenchmarkScenarioResult(
    val scenario: InferenceBenchmarkScenario,
    val deviceFingerprint: String,
    val configFingerprint: String,
    val summary: BenchmarkSummary,
    val recordedSampleCount: Int,
    val warmupSampleCount: Int,
    val coolRun: Boolean,
    val discardedReasons: List<String> = emptyList(),
    // ---- Task 5：四象限与构建维度（随结果持久化，保证按象限/构建可比）----
    val quadrant: InferenceBackendQuadrant? = null,
    val thinkingRequested: Boolean? = null,
    val backendVariant: String? = null,
    val nativeBuildId: String? = null,
    val mnnCommit: String? = null,
)

/**
 * 可靠性样本汇总（Task 5 Step 5/6）。
 *
 * @param emptyResponseClasses 空响应分类名 -> 轮次计数（键含 "NONE"=有正文、具体失败分类、以及
 *        "NO_RECORD"=未产出遥测记录；全部来自 [com.chatbyyourside.llm.metrics.InferenceTurnRecord]）。
 * @param fallbackCount GPU→CPU 空输出回退轮次（downgradeReasons 含 EMPTY_GPU_OUTPUT_FALLBACK 计数）。
 * @param nonEmptySuccessRate 有正文（emptyResponseClass==NONE）轮次 / totalRounds。
 * @param totalRounds 实际执行的总轮次；失败样本**绝不**用重试替换，分母恒为 [totalRounds]。
 */
@Serializable
data class ReliabilityResult(
    val emptyResponseClasses: Map<String, Int>,
    val fallbackCount: Int,
    val nonEmptySuccessRate: Float,
    val totalRounds: Int,
)

/**
 * 本地推理基准运行器契约（Task 2 Step 4）。
 *
 * 约束（实现需遵守，Task 15 接入 UI 时落地）：
 * 1. **拒绝热启动**：[isThermallyHot] 为真时不开始采样（热降频会污染结果），返回 coolRun=false。
 * 2. **关闭浮窗采集**：运行期间禁用 [com.chatbyyourside.perfmon.PerformanceCollector] 的周期采集与浮窗动画，
 *    避免采样线程与推理争抢 CPU/GPU。
 * 3. **预热与样本分离**：先跑若干预热轮（结果丢弃），再跑记录轮；记录轮才纳入 [BenchmarkSummary]。
 * 4. **仅持久化冷态结果**：coolRun=true 的中位数/离散度按「设备指纹+配置指纹」归档；热态/噪声/单样本结果丢弃并记录原因。
 * 5. **不自动调参**：本任务只测量与归档，绝不据此改变运行时配置（自动调参见 Task 17 实验门）。
 */
interface LocalInferenceBenchmarkRunner {

    /**
     * 当前是否过热（不可开始基准）。
     *
     * Task 8 提供热状态读取口径前，实现可保守返回 false（视为可运行）或接入现有 PowerManager 旁路。
     */
    fun isThermallyHot(): Boolean

    /**
     * 运行单个场景，返回结果（含汇总与剔除原因）。
     *
     * 被测象限由当前设置快照推导（[InferenceBackendQuadrant.of]），随结果记录。
     *
     * @param scenario 场景。
     * @param configFingerprint 当前生效配置指纹，用于归档分组。
     * @param deviceFingerprint 设备指纹。
     * @param warmupRounds 预热轮数（默认 1）。
     * @param recordedRounds 记录轮数（默认 5）。
     */
    suspend fun run(
        scenario: InferenceBenchmarkScenario,
        configFingerprint: String,
        deviceFingerprint: String,
        warmupRounds: Int = 1,
        recordedRounds: Int = 5,
    ): BenchmarkScenarioResult

    /**
     * 运行可靠性样本（[InferenceBenchmarkScenario.EMPTY_RESPONSE_CHECK] 语义，Task 5 Step 5）。
     *
     * 固定 [InferenceBenchmarkCase.quadrant] 跑 [rounds] 轮固定 prompt，逐轮如实记录空响应分类；
     * **失败样本不得用重试替换**——每轮只执行一次，[ReliabilityResult.totalRounds] 恒等于实际
     * 执行的轮数，[ReliabilityResult.nonEmptySuccessRate] 以 [totalRounds] 为分母。
     *
     * @param case 用例坐标（场景/象限/模型/设备/配置指纹）。
     * @param rounds 固定轮数（默认 20）。
     */
    suspend fun runReliability(case: InferenceBenchmarkCase, rounds: Int = 20): ReliabilityResult
}

/**
 * 基准结果持久化契约（Task 2 Step 4）。实现见 Task 15（DataStore/JSON）。
 *
 * 仅存 [BenchmarkScenarioResult.coolRun]=true 的结果；按场景+指纹覆盖式更新。
 */
interface BenchmarkResultStore {
    suspend fun save(result: BenchmarkScenarioResult)
    suspend fun load(
        scenario: InferenceBenchmarkScenario,
        deviceFingerprint: String,
        configFingerprint: String,
    ): BenchmarkScenarioResult?
}
