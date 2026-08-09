package com.chatbyyourside.llm.backend

import android.util.Log
import com.chatbyyourside.data.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * MNN 后端 JNI 桥接
 *
 * 对应 C 层 libmnn_jni.so（由 cpp/mnn_jni.cpp 编译，移植自 MNN `llm_session` + `llm_mnn_jni`）：
 *   Java_com_chatbyyourside_llm_backend_MnnBridge_nativeCreate
 *   Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGenerateStream
 *   Java_com_chatbyyourside_llm_backend_MnnBridge_nativeStop
 *   Java_com_chatbyyourside_llm_backend_MnnBridge_nativeRelease
 *   Java_com_chatbyyourside_llm_backend_MnnBridge_nativeGetMetrics
 *
 * 与 [com.chatbyyourside.llm.CpuSysBridge] 共用同一套静态回调流式机制：C 层每生成一个 token，按 UTF-8
 * 字符边界切分后通过 JNI 回调本类静态 [nativeCallback]，再由 [onToken] 分发到 Kotlin；
 * 中断用静态 [abort] 标志，C 层每轮轮询 [shouldAbort]。
 *
 * 模型格式：MNN `.mnn` 目录（config.json + llm.mnn + llm.mnn.weight + tokenizer.txt），
 * [nativeCreate] 传入目录里的 `config.json` 路径，由 MNN `Llm::createLLM` 加载。聊天模板
 * 由 MNN 按各模型自带的 `llm_config.json`/tokenizer 应用（Qwen=ChatML，Llama/Gemma/Phi 各异），
 * 故本后端接收**消息列表**（[nativeGenerateStream] 的 messagesJson）而非预格式化的 ChatML 串。
 *
 * 后端选择：MNN `set_config` 的 `backend_type` 字段--`"cpu"` / `"opencl"`（GPU）/ `"qnn"`（NPU）。
 * `"qnn"` 需 libMNN.so 含 QNN 后端构建 + 运行时加载 libQnnHtp*.so（见 QnnModule），否则 [nativeCreate] 失败。
 *
 * 库加载顺序：c++_shared -> libMNN.so -> libmnn_jni.so。libMNN.so 缺失时 [nativeAvailable]=false，
 * MNN 后端整体不可用、[BackendManager] 回退 llama.cpp（CPU/Vulkan）。
 */
class MnnBridge {

    companion object {
        private const val TAG = "MnnBridge"

        /** token 回调（由 C 层 JNI 调用，转发给当前 generateStream 调用方）。
         *  第二参数为当前真实已生成 token 数（native LlmContext::gen_seq_len），
         *  批处理后回调次数≠token 数，MnnBackend 据此计算实时 tps。 */
        @Volatile
        @JvmStatic
        internal var onToken: ((String, Int) -> Unit)? = null

        /** 中断标志（C 层每轮检查，true 则提前结束） */
        @Volatile
        @JvmStatic
        internal var abort: Boolean = false

        /** 依赖顺序加载：c++_shared -> MNN 引擎 -> 本工程 JNI 包装 libmnn_jni.so */
        private val LIBS = arrayOf(
            "c++_shared",
            "MNN",       // MNN 引擎（libMNN.so，含 LLM + OpenCL + ARM82 + transformer-fuse）
            "mnn_jni",   // 本工程 JNI 包装（含 MnnBridge_* 符号）
        )

        @Volatile
        private var bridgeLoaded = false

        @Volatile
        private var mnnLibLoaded = false

        /** 期望的 JNI ABI 版本与钉定 MNN commit（与 native-manifest.json、CMake 编译定义对齐）。 */
        private const val EXPECTED_JNI_ABI = 1
        private const val EXPECTED_MNN_COMMIT = "af0142bcc7b76b7a5128373e285683dc04f55f69"

        /** native 回传的运行时信息（库加载后解析一次）。null=握手缺席（旧构建）或解析失败。 */
        @Volatile
        internal var runtimeInfo: MnnRuntimeInfo? = null
            private set

        /** 运行时信息诊断：null=正常；非空=ABI/commit 不符或握手不可用（供 UI/日志展示）。 */
        @Volatile
        var runtimeDiagnostic: String? = null
            private set

        init {
            for (lib in LIBS) {
                try {
                    System.loadLibrary(lib)
                    when (lib) {
                        "mnn_jni" -> bridgeLoaded = true
                        "MNN" -> mnnLibLoaded = true
                    }
                    Log.i(TAG, "✓ $lib loaded")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "✗ $lib FAILED: ${e.message}")
                }
            }
            Log.i(TAG, "bridgeLoaded=$bridgeLoaded mnnLibLoaded=$mnnLibLoaded")
            // 加载后立即解析运行时信息，校验 ABI/commit 与本应用期望是否一致。
            parseRuntimeInfoOnce()
        }

        /** 解析 nativeGetRuntimeInfo 并校验 ABI/commit；握手缺席时宽放（不禁用），仅记录诊断。 */
        private fun parseRuntimeInfoOnce() {
            if (!bridgeLoaded) return
            try {
                val info = MnnRuntimeInfo.fromJson(nativeGetRuntimeInfo()) ?: run {
                    runtimeDiagnostic = "native 运行时信息解析失败（JSON 为空或格式错误）"
                    return
                }
                runtimeInfo = info
                runtimeDiagnostic = when {
                    info.abiVersion != EXPECTED_JNI_ABI ->
                        "JNI ABI 不匹配：native=${info.abiVersion}, 期望=$EXPECTED_JNI_ABI"
                    info.mnnCommit != EXPECTED_MNN_COMMIT ->
                        "MNN commit 不匹配：native=${info.mnnCommit}, 期望=$EXPECTED_MNN_COMMIT"
                    else -> null
                }
                Log.i(TAG, "runtimeInfo abi=${info.abiVersion} commit=${info.mnnCommit} buildId=${info.nativeBuildId} caps=${info.capabilities}")
            } catch (e: UnsatisfiedLinkError) {
                // 旧 native 构建无 nativeGetRuntimeInfo 符号——宽放（不禁用 native），仅记录诊断。
                runtimeDiagnostic = "native 运行时信息不可用（native 构建早于握手协议）"
                Log.w(TAG, "nativeGetRuntimeInfo 不可用: ${e.message}")
            } catch (e: Exception) {
                runtimeDiagnostic = "native 运行时信息解析异常: ${e.message}"
                Log.w(TAG, "nativeGetRuntimeInfo 解析异常: ${e.message}")
            }
        }

        /**
         * JNI 包装库是否可用（native 调用的前提）。
         *
         * 握手缺席（runtimeInfo==null，旧 native 构建或缺库）时宽放，等价于旧逻辑 bridgeLoaded && mnnLibLoaded；
         * 握手出席时额外要求 ABI 与 commit 均与期望一致——不匹配则视为不兼容 native 栈，禁用 MNN 后端。
         */
        val nativeAvailable: Boolean
            get() = bridgeLoaded && mnnLibLoaded && runtimeInfoCompatible()

        private fun runtimeInfoCompatible(): Boolean {
            val info = runtimeInfo ?: return true
            return info.abiVersion == EXPECTED_JNI_ABI && info.mnnCommit == EXPECTED_MNN_COMMIT
        }

        /** libMNN.so 是否加载成功 */
        val mnnAvailable: Boolean
            get() = mnnLibLoaded

        /**
         * C 层通过 JNI 调用此方法推送一段批处理文本（按 UTF-8 字符边界切分/聚合后的字节）。
         * @param bytes 批处理字节（Task 4 StreamBatcher 聚合，非单个 token）
         * @param generatedTokens 当前真实已生成 token 数（native 实时 gen_seq_len，供浮窗 tps）
         */
        @JvmStatic
        fun nativeCallback(bytes: ByteArray, generatedTokens: Int) {
            onToken?.invoke(String(bytes, Charsets.UTF_8), generatedTokens)
        }

        /** C 层轮询此标志决定是否中断 */
        @JvmStatic
        fun shouldAbort(): Boolean = abort

        /**
         * 查询 native 运行时信息（JNI ABI 版本、钉定 MNN commit、native build ID、能力集）。
         * 库加载后由 [parseRuntimeInfoOnce] 解析一次，用于校验 native 栈与本应用期望是否一致。
         * 对应 C 层 `Java_..._MnnBridge_nativeGetRuntimeInfo`（静态 native，返回稳定 JSON）。
         */
        @JvmStatic
        external fun nativeGetRuntimeInfo(): String

        /**
         * 把消息列表序列化为 MNN JNI 可解析的 JSON：
         * `[{"role":"system","content":"..."},{"role":"user","content":"..."},...]`
         */
        fun toMessagesJson(messages: List<ChatMessage>): String {
            val arr = JSONArray()
            for (msg in messages) {
                val role = when (msg.role) {
                    "system", "user", "assistant" -> msg.role
                    else -> "user"
                }
                arr.put(JSONObject().apply {
                    put("role", role)
                    put("content", msg.content)
                })
            }
            return arr.toString()
        }
    }

    /**
     * 加载 MNN 模型并初始化引擎。
     * @param configPath 模型目录里的 `config.json` 绝对路径
     * @param backendType `"cpu"` / `"opencl"` / `"qnn"`
     * @param threads CPU 线程数（OpenCL 后端 MNN 默认忽略，由其内部调度）
     * @param contextLen 上下文长度（CPU 后端用于钉 `kv_max_length` KV 上限）
     * @param lookahead CPU 模式下是否启用 lookahead n-gram 投机解码（1.5–3×，无需 draft 模型）；
     *        非 cpu 后端忽略。仅在 cpu 后端于 set_config 追加 `speculative_type=lookahead`。
     * @param temperature 采样温度。MNN 采样器在 load() 内一次性构建，故必须在此（load 前）传入才能
     *        保证生效；改值由 [BackendManager] 纳入重载指纹，触发下次重载。
     * @param topP top-p 采样（MNN 配置键为 `topP`，非 top_p）。AppConfig 常量。
     * @param repeatPenalty 重复惩罚（MNN 配置键为 `repetition_penalty`，非 repeat_penalty）。AppConfig 常量。
     * @return 引擎句柄（非 0 成功，0 失败）
     */
    external fun nativeCreate(
        configPath: String,
        backendType: String,
        threads: Int,
        contextLen: Int,
        lookahead: Boolean,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
    ): Long

    /**
     * 流式推理（阻塞，内部经 StreamBatcher 批处理回调 [nativeCallback]）。
     * @param handle [nativeCreate] 返回的句柄
     * @param messagesJson 消息列表 JSON（[toMessagesJson]），MNN 据此应用模型自带 chat 模板
     * @param maxTokens 最大生成 token 数
     * @param temperature 采样温度
     * @param topP top-p 采样
     * @param repeatPenalty 重复惩罚
     * @param enableThinking 是否启用深度思考。经 set_config 注入 jinja context `enable_thinking`，
     *        控制推理模型（Qwen3/R1）chat 模板是否插入 `<think>` 前缀；运行时生效，无需重载。
     *        false 时模型跳过推理直接作答；true 时生成 reasoning 后再作答。
     *        无 enable_thinking 分支的模板（Llama/Gemma）忽略，无害。
     * @param batchMaxBytes 流式批处理缓冲上限（字节）。首个完整可见字符立即回调（首 delta 即时性），
     *        其余按「字节或时间达标即批量 flush」。Task 6 性能模式接入前用 Balanced 默认 256。
     * @param batchMaxMs 流式批处理缓冲时间上限（ms）。Balanced 16；Maximum Speed 24–32。
     * @return 紧凑版本化 GenerationSummary JSON（[NativeGenerationSummary.parse]），**非** 全量文本。
     *         全量回复不再整份拷贝回 Kotlin；文本由流式回调拼接（provider 是唯一累加器）。
     */
    external fun nativeGenerateStream(
        handle: Long,
        messagesJson: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        enableThinking: Boolean,
        batchMaxBytes: Int,
        batchMaxMs: Int,
    ): String

    /** 中断当前生成（下一轮 token 前检测） */
    external fun nativeStop(handle: Long)

    /** 释放引擎（模型/上下文/KV 缓存） */
    external fun nativeRelease(handle: Long)

    /**
     * 性能指标：[tokensPerSecond, prefillUs, decodeUs, promptLen, genLen, reuseKv]。
     * 取自 MNN `LlmContext` 的 prefill_us/decode_us/gen_seq_len；reuseKv 取自 `Llm::reuse_kv()`
     * （1=本轮复用了 KV 前缀/0=否/-1=取不到），用于验证多轮前缀复用是否生效。
     */
    external fun nativeGetMetrics(handle: Long): FloatArray

    /**
     * 最近一次 [nativeCreate] 的加载失败原因（对应 C 层 g_last_load_error）。
     * 在 [nativeCreate] 返回 0 后立即调用，取真实失败原因（如 `Llm::load 异常: ...`、
     * `Llm::load() 失败 (backend=cpu)`，含 CPU 安全配置重试的结果）填入 [MnnBackend.lastErrorMessage]，
     * 再由 [BackendManager] 汇总上报，定位「所有后端均加载失败」的芯片相关根因。
     * 空串表示无错误/上次加载成功。
     */
    external fun nativeGetLastError(): String
}

/**
 * Native 运行时信息（由 [MnnBridge.nativeGetRuntimeInfo] 返回的 JSON 解析）。
 *
 * 用于加载后握手校验：[abiVersion] 与 [MnnBridge] 期望的 JNI ABI 不符即 native 契约不兼容；
 * [mnnCommit] 与钉定 commit 不符意味着 native 栈来自不同 MNN 构建。[capabilities] 反映本
 * libMNN.so 编译期特性（mmap/cached_mmap/reuse_kv/opencl/arm82 等），供上层决定可用功能。
 *
 * 用 org.json 解析（与本文件既有风格一致），不引入 kotlinx.serialization 依赖。
 */
data class MnnRuntimeInfo(
    val abiVersion: Int,
    val mnnCommit: String,
    val nativeBuildId: String,
    val capabilities: Set<String>,
) {
    companion object {
        /** 解析 nativeGetRuntimeInfo 的 JSON；格式错误返回 null（由调用方记诊断）。 */
        fun fromJson(json: String): MnnRuntimeInfo? = try {
            val o = JSONObject(json)
            val caps = HashSet<String>()
            val arr = o.optJSONArray("capabilities")
            if (arr != null) {
                for (i in 0 until arr.length()) caps.add(arr.getString(i))
            }
            MnnRuntimeInfo(
                abiVersion = o.optInt("abiVersion", 0),
                mnnCommit = o.optString("mnnCommit", ""),
                nativeBuildId = o.optString("nativeBuildId", ""),
                capabilities = caps,
            )
        } catch (e: Exception) {
            null
        }
    }
}
