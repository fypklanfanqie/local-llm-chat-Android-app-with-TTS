package com.chatbyyourside.llm

import com.chatbyyourside.llm.profile.DowngradeReason
import kotlin.math.max

/**
 * 模型/资源准入控制器（Task 13）。
 *
 * - 下载存储准入：required = bundle + 合并 headroom + runtime-cache reserve + max(512MiB, 10% bundle)，
 *   与磁盘实际可用量比对。
 * - 模型加载 RAM 准入：combine 模型工作集 + KV（GQA-aware）+ 激活 reserve + 后端开销 + 实测峰值 PSS，
 *   与 availMem/threshold/lowMemory 比对；解析顺序：禁实验 -> 有效 chunking（预留）-> 降 context 档 ->
 *   降内存 attempt（预留）-> 拒绝。
 *
 * 纯逻辑（注入量），可 JVM 测试；不覆盖用户配置的 context（只降 actualContext，不改配置）。
 */
object ModelAdmissionController {

    const val MIN_CONTEXT_STEP = 512
    const val STORAGE_HEADROOM_FIXED_BYTES = 512L * 1024 * 1024   // 512 MiB
    const val STORAGE_HEADROOM_FRACTION = 0.10                    // 10% bundle

    sealed interface AdmissionDecision {
        data class Allowed(val contextTokens: Int) : AdmissionDecision
        data class Downgraded(val actualContext: Int, val reasons: List<DowngradeReason>) : AdmissionDecision
        data class Rejected(val userMessage: String, val details: Map<String, Long>) : AdmissionDecision
    }

    // ===== 下载存储准入 =====

    /** 所需存储 = bundle + 合并 headroom + runtime-cache reserve + max(512MiB, 10% bundle)。 */
    fun storageRequiredBytes(
        bundleBytes: Long,
        mergeHeadroomBytes: Long = 0L,
        runtimeCacheReserveBytes: Long = 0L,
    ): Long = bundleBytes + mergeHeadroomBytes + runtimeCacheReserveBytes +
        max(STORAGE_HEADROOM_FIXED_BYTES, (bundleBytes * STORAGE_HEADROOM_FRACTION).toLong())

    /** 返回空 = 空间足够；否则 Rejected（含 required/available 供 UI 展示）。 */
    fun assessStorage(
        bundleBytes: Long,
        availableBytes: Long,
        mergeHeadroomBytes: Long = 0L,
        runtimeCacheReserveBytes: Long = 0L,
    ): AdmissionDecision {
        val required = storageRequiredBytes(bundleBytes, mergeHeadroomBytes, runtimeCacheReserveBytes)
        return if (availableBytes >= required) {
            AdmissionDecision.Allowed(contextTokens = 0)
        } else {
            AdmissionDecision.Rejected(
                userMessage = "存储空间不足，无法下载模型",
                details = mapOf("requiredBytes" to required, "availableBytes" to availableBytes),
            )
        }
    }

    // ===== 模型加载 RAM 准入 =====

    data class MemoryInputs(
        val modelWorkingSetBytes: Long,
        val configuredContext: Int,
        val kvBytesForContext: (Int) -> Long,
        val activationReserveBytes: Long,
        val backendOverheadBytes: Long,
        val measuredPeakPssBytes: Long?,
        val availMemBytes: Long,
        val thresholdBytes: Long,
        val lowMemory: Boolean,
    )

    /** context 降档序列（从配置值起逐级减半，最低 [MIN_CONTEXT_STEP]）。 */
    fun contextSteps(configured: Int): List<Int> {
        val steps = mutableListOf<Int>()
        var c = configured
        while (c >= MIN_CONTEXT_STEP) {
            steps += c
            c /= 2
        }
        return steps
    }

    fun decideMemory(inputs: MemoryInputs): AdmissionDecision {
        // 可用模型预算 = availMem - threshold - activation reserve - backend 开销 - 实测 PSS - lowMemory 余量。
        val guard = if (inputs.lowMemory) inputs.availMemBytes / 4 else 0L
        val availableForModel =
            inputs.availMemBytes - inputs.thresholdBytes - inputs.activationReserveBytes -
                inputs.backendOverheadBytes - (inputs.measuredPeakPssBytes ?: 0L) - guard
        if (availableForModel <= 0L) {
            return AdmissionDecision.Rejected(
                userMessage = "可用内存不足，无法加载模型",
                details = mapOf("availableForModel" to availableForModel),
            )
        }

        // 降 context 档，直到 KV + working set 放得进。
        for (ctx in contextSteps(inputs.configuredContext)) {
            val kv = inputs.kvBytesForContext(ctx)
            if (inputs.modelWorkingSetBytes + kv <= availableForModel) {
                return if (ctx == inputs.configuredContext) {
                    AdmissionDecision.Allowed(contextTokens = ctx)
                } else {
                    AdmissionDecision.Downgraded(
                        actualContext = ctx,
                        reasons = listOf(DowngradeReason.MEMORY),
                    )
                }
            }
        }

        return AdmissionDecision.Rejected(
            userMessage = "模型过大或上下文过长，无法在可用内存下运行",
            details = mapOf(
                "modelWorkingSetBytes" to inputs.modelWorkingSetBytes,
                "availableForModel" to availableForModel,
                "minContext" to (contextSteps(inputs.configuredContext).minOrNull() ?: 0).toLong(),
            ),
        )
    }
}
