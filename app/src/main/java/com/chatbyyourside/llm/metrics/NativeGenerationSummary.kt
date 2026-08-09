package com.chatbyyourside.llm.metrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * nativeGenerateStream 返回的紧凑版本化 `GenerationSummary` JSON 的 Kotlin 模型（Task 4 Step 1）。
 *
 * Wire 格式（native `mnn_jni.cpp` 产出，Kotlin 侧 [parse] 严格校验）：
 * ```json
 * {"v":1,"completionReason":"EOS","promptTokens":123,"generatedTokens":45,
 *  "prefillUs":1234567,"decodeUs":890123,"reuseKv":1,"callbackCount":12,"callbackBytes":456,
 *  "firstDeltaUs":890,"errorStage":null,"errorMessage":null}
 * ```
 *
 * 严格性：[parse] 对版本（!= [VERSION]）、未知 completionReason / errorStage 字符串、非法 JSON
 * 一律拒绝（返回 null，调用方按 [CompletionReason.BACKEND_FAILURE] 处理）；同版本新增字段宽容
 * （`ignoreUnknownKeys`），避免字段追加即契约破裂。
 */
@Serializable
data class NativeGenerationSummary(
    /** Wire 协议版本，必须等于 [VERSION]，否则拒收。 */
    @SerialName("v") val version: Int,
    /** 完成原因字符串，取值 [CompletionReason] 枚举名（native 侧 best-effort；Kotlin 侧有更高优先级推导）。 */
    val completionReason: String,
    /** 本轮 prefill 的 prompt token 数（远小于完整历史 = 前缀复用生效）。 */
    val promptTokens: Int,
    /** 本轮生成的 token 数。 */
    val generatedTokens: Int,
    val prefillUs: Long,
    val decodeUs: Long,
    /** 是否复用了 KV 前缀缓存：1=是 / 0=否 / -1=取不到。 */
    val reuseKv: Int,
    /** 流式回调次数（StreamBatcher 实际 flush 次数）。 */
    val callbackCount: Int,
    /** 流式回调累计 UTF-8 字节数。 */
    val callbackBytes: Long,
    /** 首 delta（首个可见字符回调）相对生成起点的时延（us）；未产生可见输出为 null。 */
    val firstDeltaUs: Long? = null,
    /** 出错阶段（[InferenceStage] 枚举名）；无错误为 null。 */
    val errorStage: String? = null,
    val errorMessage: String? = null,
) {
    /** [reuseKv] 原始值 → 语义值：1=true、0=false、其余（-1 取不到）=null。 */
    val kvReuse: Boolean?
        get() = when (reuseKv) {
            1 -> true
            0 -> false
            else -> null
        }

    /**
     * 把摘要转为 nativeGetMetrics 同构的指标数组
     * `[tps, prefillUs, decodeUs, promptLen, genLen, reuseKv]`（Task 4 Step 3）。
     *
     * tps 由摘要的 decode 耗时与生成 token 数推算（decode_us>0 才有意义），避免二次 native 调用；
     * reuseKv 语义映射与 [nativeGetMetrics] 一致（1→1、0→0、-1→0，下游按 !=0 判复用）。
     */
    fun toMetricsArray(): FloatArray = floatArrayOf(
        if (decodeUs > 0L) generatedTokens * 1_000_000f / decodeUs else 0f,
        prefillUs.toFloat(),
        decodeUs.toFloat(),
        promptTokens.toFloat(),
        generatedTokens.toFloat(),
        reuseKv.toFloat(),
    )

    companion object {
        /** 当前 wire 协议版本；native 与 Kotlin 必须一致。 */
        const val VERSION = 1

        /** 测试与 MnnBackend 复用（同模块 test 源集可访问）。 */
        internal val summaryJson = Json { ignoreUnknownKeys = true }

        /**
         * 严格解析 native 返回的摘要 JSON。
         * @return 校验通过的对象；版本不符 / 未知 reason / 未知 stage / 非法 JSON 返回 null。
         */
        fun parse(json: String): NativeGenerationSummary? = try {
            val raw = summaryJson.decodeFromString<NativeGenerationSummary>(json)
            if (raw.version != VERSION) return null
            if (CompletionReason.entries.none { it.name == raw.completionReason }) return null
            if (raw.errorStage != null &&
                InferenceStage.entries.none { it.name == raw.errorStage }) return null
            raw
        } catch (e: Exception) {
            null
        }
    }
}
