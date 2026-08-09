package com.chatbyyourside.llm.profile

import com.chatbyyourside.llm.backend.BackendPreference
import com.chatbyyourside.llm.backend.BackendType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 生成每轮不可变的 [ResolvedInferencePlan]（Task 7）。
 *
 * 关键职责：
 * - 由模式/后端偏好/设备与健康信息解析**有序后端尝试链**（QNN 永不进 AUTO）；
 * - 为每个 [BackendAttempt] 生成规范化 native set_config JSON（键排序），并计算
 *   [BackendAttempt.loadConfigHash] 作为唯一模型重载指纹；
 * - 产出流式/功耗/驻留策略与全部安全降级原因（类型化 [DowngradeReason]）。
 *
 * 热/内存降级不能被 MAXIMUM_SPEED 绕过：CPU 线程数一律取热准入值，模式只影响
 * sustained/性能提示/批处理阈值等非安全键。
 *
 * @param cacheDir 应用私有缓存目录（Context.cacheDir）；运行时缓存按模型指纹命名写入，
 *                 不再写入下载模型目录。
 * @param modelPath MNN 模型 `config.json` 绝对路径；用于 cache 命名空间与负载指纹。
 */
class InferenceProfileResolver(
    private val cacheDir: File,
    private val modelPath: String,
) {

    /**
     * @param openclHealth OpenCL 健康状态（Task 9，来自 BackendHealthStore）：PROBE_OK/MODEL_OK
     *        可进链；UNKNOWN 需先探测（Task 10），不入链；COOLDOWN/CRASH_BLACKLISTED 不入链。
     * @param thermalAdmittedThreads 热准入后的 CPU 线程数（min(用户, 大核, 温控上限)），
     *        由调用方已算好，MAXIMUM_SPEED 不能绕过。
     */
    fun resolve(
        mode: InferencePerformanceMode,
        backendPreference: BackendPreference,
        contextTokens: Int,
        maxOutputTokens: Int,
        thermalAdmittedThreads: Int,
        lookahead: Boolean,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        openclHealth: OpenClHealthState,
    ): ResolvedInferencePlan {
        val downgrades = mutableListOf<DowngradeReason>()

        // 尝试链：QNN 永不进 AUTO；标准版显式选 NPU 也解析为 CPU（保留已存设置但标不支持）。
        val openclEligible = openclHealth == OpenClHealthState.PROBE_OK ||
            openclHealth == OpenClHealthState.MODEL_OK
        val attempts = buildList {
            val cpu = thermalAdmittedThreads.coerceAtLeast(1)
            when (backendPreference) {
                BackendPreference.AUTO, BackendPreference.MNN_GPU -> {
                    if (openclEligible) {
                        add(attempt(BackendType.MNN_GPU, RuntimeVariant.OPENCL, 68, contextTokens, lookahead = false, temperature, topP, repeatPenalty))
                    } else if (backendPreference == BackendPreference.MNN_GPU &&
                        openclHealth != OpenClHealthState.UNKNOWN
                    ) {
                        downgrades += DowngradeReason.OPENCL_UNHEALTHY
                    }
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_OPTIMIZED, cpu, contextTokens, lookahead, temperature, topP, repeatPenalty))
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_COMPATIBILITY, cpu, contextTokens, lookahead, temperature, topP, repeatPenalty))
                }
                BackendPreference.MNN_CPU -> {
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_OPTIMIZED, cpu, contextTokens, lookahead, temperature, topP, repeatPenalty))
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_COMPATIBILITY, cpu, contextTokens, lookahead, temperature, topP, repeatPenalty))
                }
                BackendPreference.MNN_NPU -> {
                    // 标准构建不含 QNN 运行时：保留设置但解析为 CPU，显式降级原因（Task 11）。
                    downgrades += DowngradeReason.QNN_UNAVAILABLE_IN_STANDARD_BUILD
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_OPTIMIZED, cpu, contextTokens, lookahead, temperature, topP, repeatPenalty))
                    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_COMPATIBILITY, cpu, contextTokens, lookahead, temperature, topP, repeatPenalty))
                }
            }
        }

        // effectiveMode：热/内存受限时即使请求 MAXIMUM_SPEED 也回落 BALANCED（安全键不可绕过）。
        // Task 7 阶段调用方只传入已热准入的线程；此处保留模式，实际热降级由后续任务执行。
        val effectiveMode = mode

        return ResolvedInferencePlan(
            requestedMode = mode,
            effectiveMode = effectiveMode,
            contextTokens = contextTokens,
            maxOutputTokens = maxOutputTokens,
            streamPolicy = when (mode) {
                InferencePerformanceMode.BALANCED -> StreamPolicy(batchMaxBytes = 256, batchMaxMs = 16)
                InferencePerformanceMode.MAXIMUM_SPEED -> StreamPolicy(batchMaxBytes = 512, batchMaxMs = 32)
            },
            powerPolicy = PowerPolicy(
                cpuThreads = thermalAdmittedThreads.coerceAtLeast(1),
                // lookahead 保持用户设置：设计规格要求仅经基准证明收益后由 MAXIMUM_SPEED 自动开启，
                // 基准接入前不擅自改变用户选择。
                lookahead = lookahead,
                sustainedMode = mode == InferencePerformanceMode.MAXIMUM_SPEED,
                aggressiveHint = mode == InferencePerformanceMode.MAXIMUM_SPEED,
            ),
            residencyPolicy = ResidencyPolicy(
                keepAliveMs = when (mode) {
                    InferencePerformanceMode.BALANCED -> 15_000L
                    InferencePerformanceMode.MAXIMUM_SPEED -> 60_000L
                },
            ),
            attempts = attempts,
            downgradeReasons = downgrades,
        )
    }

    private fun attempt(
        backend: BackendType,
        variant: RuntimeVariant,
        threadNum: Int,
        contextTokens: Int,
        lookahead: Boolean,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
    ): BackendAttempt {
        val backendType = when (backend) {
            BackendType.MNN_CPU -> "cpu"
            BackendType.MNN_GPU -> "opencl"
            BackendType.MNN_NPU -> "qnn"
        }
        val json = buildAttemptNativeConfig(
            variant = variant,
            backendType = backendType,
            threadNum = threadNum,
            cachePath = runtimeCacheFile.absolutePath,
            contextTokens = contextTokens,
            lookahead = lookahead,
            temperature = temperature,
            topP = topP,
            repeatPenalty = repeatPenalty,
        )
        return BackendAttempt(
            backend = backend,
            variant = variant,
            nativeConfigJson = json,
            loadConfigHash = loadConfigHash(json),
            requiresProbe = variant == RuntimeVariant.OPENCL,
        )
    }

    private val runtimeCacheFile: File by lazy {
        File(cacheDir, "mnn_cache_${sha256(modelPath).take(8)}.bin")
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val HASH_HEX_LENGTH = 16

        /**
         * 生成规范化 native set_config JSON（键排序，供 JNI 原样透传给 Llm::set_config）。
         *
         * 安全通用键固定：use_mmap/reuse_kv/attention_mode=8/dynamic_option=0/mixed_samplers(penalty)。
         * CPU_OPTIMIZED 用 low precision/memory + Power_High；CPU_COMPATIBILITY 用保守
         * normal/normal + Power_Normal（不依赖省略字段继承未知模型默认）；OPENCL 保持 68 编码。
         */
        fun buildAttemptNativeConfig(
            variant: RuntimeVariant,
            backendType: String,
            threadNum: Int,
            cachePath: String,
            contextTokens: Int,
            lookahead: Boolean,
            temperature: Float,
            topP: Float,
            repeatPenalty: Float,
        ): String {
            val optimized = variant == RuntimeVariant.CPU_OPTIMIZED
            val isOpenCl = variant == RuntimeVariant.OPENCL
            val config = buildJsonObject {
                put("schemaVersion", SCHEMA_VERSION)
                put("backend_type", backendType)
                put("thread_num", threadNum)
                put("cache_path", cachePath)
                // precision/memory：CPU_OPTIMIZED=low/low；CPU_COMPATIBILITY=normal/normal；OpenCL=low/low。
                put("precision", if (optimized || isOpenCl) "low" else "normal")
                put("memory", if (optimized || isOpenCl) "low" else "normal")
                put("use_mmap", true)
                put("reuse_kv", true)
                put("attention_mode", 8)
                put("dynamic_option", 0)
                put("temperature", temperature)
                put("topP", topP)
                put("repetition_penalty", repeatPenalty)
                put(
                    "mixed_samplers",
                    buildJsonArray {
                        add(JsonPrimitive("penalty"))
                        add(JsonPrimitive("topK"))
                        add(JsonPrimitive("tfs"))
                        add(JsonPrimitive("typical"))
                        add(JsonPrimitive("topP"))
                        add(JsonPrimitive("min_p"))
                        add(JsonPrimitive("temperature"))
                    },
                )
                if (backendType == "cpu") {
                    // 功耗：CPU_OPTIMIZED=high（大核调度）；CPU_COMPATIBILITY=normal（保守）。
                    put("power", if (optimized) "high" else "normal")
                    if (contextTokens > 0) put("kv_max_length", contextTokens)
                    if (lookahead) {
                        put("speculative_type", "lookahead")
                        put("ngram_match_maxlen", 4)
                        put("draft_predict_length", 5)
                    }
                }
            }
            return canonicalJsonString(config)
        }

        /** 键递归排序的规范化 JSON 字符串；同一语义配置恒产生同一字节序列。 */
        fun canonicalJsonString(root: JsonObject): String {
            fun canon(element: JsonElement): JsonElement = when (element) {
                is JsonObject -> JsonObject(
                    element.entries.sortedBy { it.key }.associate { (k, v) -> k to canon(v) },
                )
                is JsonArray -> element  // 数组元素序确定性（resolver 固定构建），无需重建
                else -> element
            }
            return (canon(root) as JsonObject).toString()
        }

        /** loadConfigHash：规范化配置 JSON 的 SHA-256 前 16 位 hex，作为唯一重载指纹。 */
        fun loadConfigHash(canonicalJson: String): String =
            sha256(canonicalJson).take(HASH_HEX_LENGTH)

        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
