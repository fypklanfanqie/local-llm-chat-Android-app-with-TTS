package com.chatbyyourside.llm.benchmark

/**
 * 实验促进门禁（Task 15 Step 1）。
 *
 * 候选配置（如 lookahead / 线程数 / OpenCL 变体）相对基线能否 promotion：
 * - 正确性优先（UTF-8/EOS/重复/KV 前缀无失配）；
 * - 至少 ≥10% median decode 提升（或定义 TTFT 收益）；
 * - TTFT 与峰值 PSS 的劣化有界（≤30%）；
 * - 需冷启样本（拒绝热/噪声/单样本）。
 *
 * Task 6 认证衔接：Promote 决策经 [InferenceCertificationStore.toCertifiedOptions] 生成该
 * device+model+variant+native 组合的认证记录并落盘（基准触发与 UI 入口见 Task 7）；
 * [com.chatbyyourside.llm.profile.InferenceProfileResolver] 启用 lookahead / 多 token 步进前
 * 按组合查证该认证——没有基准证据，配置不全局启用。
 */
data class BenchmarkSample(
    val decodeTpsMedian: Float,
    val ttftMsMedian: Float? = null,
    val peakPssMb: Float? = null,
    val sampleCount: Int,
    val hotStart: Boolean = false,
    /** UTF-8 完整 / EOS 正常 / 无复读 / KV 前缀无失配 的总体正确性。 */
    val correctnessOk: Boolean = true,
)

sealed interface PromotionDecision {
    data object Promote : PromotionDecision
    data class Reject(val reasons: List<String>) : PromotionDecision
}

object ExperimentalPromotionPolicy {

    const val MIN_DECODE_IMPROVEMENT = 1.10f   // ≥10% decode 提升
    const val MAX_TTFT_REGRESSION = 1.30f      // TTFT 劣化 ≤30%
    const val MAX_PSS_REGRESSION = 1.30f       // 峰值 PSS 劣化 ≤30%
    const val MIN_SAMPLES = 3

    fun evaluate(baseline: BenchmarkSample, candidate: BenchmarkSample): PromotionDecision {
        val reasons = mutableListOf<String>()

        if (!candidate.correctnessOk) reasons += "候选正确性校验未通过（UTF-8/EOS/复读/KV 失配）"
        if (candidate.hotStart || baseline.hotStart) reasons += "热启动样本无效，需冷启重测"
        if (candidate.sampleCount < MIN_SAMPLES || baseline.sampleCount < MIN_SAMPLES) {
            reasons += "样本数不足（需 ≥$MIN_SAMPLES，候选=${candidate.sampleCount}，基线=${baseline.sampleCount}）"
        }
        if (candidate.decodeTpsMedian < baseline.decodeTpsMedian * MIN_DECODE_IMPROVEMENT) {
            reasons += "decode 提升不足 10%（候选=${candidate.decodeTpsMedian} vs 基线=${baseline.decodeTpsMedian}）"
        }
        if (candidate.ttftMsMedian != null && baseline.ttftMsMedian != null &&
            candidate.ttftMsMedian > baseline.ttftMsMedian * MAX_TTFT_REGRESSION
        ) {
            reasons += "TTFT 劣化超 30%（候选=${candidate.ttftMsMedian} vs 基线=${baseline.ttftMsMedian}）"
        }
        if (candidate.peakPssMb != null && baseline.peakPssMb != null &&
            candidate.peakPssMb > baseline.peakPssMb * MAX_PSS_REGRESSION
        ) {
            reasons += "峰值 PSS 劣化超 30%（候选=${candidate.peakPssMb} vs 基线=${baseline.peakPssMb}）"
        }

        return if (reasons.isEmpty()) PromotionDecision.Promote else PromotionDecision.Reject(reasons)
    }
}
