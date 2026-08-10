package com.chatbyyourside.llm.backend

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.llm.CpuBoostController
import com.chatbyyourside.llm.GenerationExecutionControl
import com.chatbyyourside.llm.backend.MnnBackend.MnnMode
import com.chatbyyourside.llm.metrics.CompletionReason
import com.chatbyyourside.llm.profile.BackendAttempt
import com.chatbyyourside.llm.profile.ResolvedInferencePlan
import com.chatbyyourside.llm.metrics.InferenceTurnRecord
import com.chatbyyourside.llm.metrics.NativeGenerationSummary
import com.chatbyyourside.llm.template.ThinkingOutputClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 后端管理器（统一推理管理器）
 *
 * 持有 MNN 侧三个后端实例（[MnnBackend] ×3：CPU/OpenCL/QNN），按用户偏好 [BackendPreference]
 * + 设备能力选择后端执行推理。
 *
 * 回退链：显式选 NPU 时 MNN_NPU(QNN) > MNN_GPU(OpenCL) > MNN_CPU；AUTO **不含 NPU**（直接 HTP 在非
 * root 锁定设备原生崩在 PipelineModule::load，见 [backendOrder] 与 memory mnn-qnn-htp-selinux-blocked），
 * 走 MNN_GPU > MNN_CPU。任一后端 [initialize] 返回 false 或 [generateStreamMessages] 抛异常时，
 * 记录日志并尝试链中的下一个后端。链末端的 MNN_CPU 为兜底。
 *
 * 内存：切换后端前先释放可能驻留的其他后端模型，避免两套模型同时占内存。
 *
 * @param cpuBoostController CPU 提频控制器（透传给 [MnnBackend]，MNN CPU 推理时开 hint session）
 */
class BackendManager(
    context: Context,
    private val cpuBoostController: CpuBoostController,
    /** Task 3：后端健康协调器（AppContainer 注入真实实例；测试可传 null 或 fake）。 */
    private val healthCoordinator: BackendHealthCoordinator? = null,
    /** 后端工厂（Task 3 review M-3 测试注入点）：默认真实 [MnnBackend]；JVM 单测注入 fake
     *  [InferenceBackend] 以驱动 attempt 成功/失败路径（真实 MnnBackend 依赖 native，无法纯 JVM 构造）。 */
    private val backendFactory: (MnnMode) -> InferenceBackend = { mode ->
        MnnBackend(context, mode, cpuBoostController)
    },
) {
    private val selector = BackendSelector(context)
    /** 整次请求（加载 + fallback + JNI）串行，防新请求改写旧请求共享的 abort/lifecycle 状态。 */
    private val generationMutex = Mutex()
    private val mnnCpuBackend: InferenceBackend = backendFactory(MnnMode.CPU)
    private val mnnGpuBackend: InferenceBackend = backendFactory(MnnMode.GPU_OPENCL)
    private val mnnNpuBackend: InferenceBackend = backendFactory(MnnMode.NPU_QNN)

    /** 设备能力（惰性计算一次） */
    val deviceCapability: BackendSelector.DeviceCapability by lazy { selector.collectDeviceInfo() }

    /** MNN 各模式是否可用（惰性） */
    val mnnCpuSupported: Boolean by lazy { mnnCpuBackend.isSupported }
    val mnnGpuSupported: Boolean by lazy { mnnGpuBackend.isSupported }
    val mnnNpuSupported: Boolean by lazy { mnnNpuBackend.isSupported }

    /** 最近一次实际使用的后端类型（供 UI/浮窗展示） */
    @Volatile
    var lastUsedBackend: BackendType = BackendType.MNN_CPU
        private set

    /** MNN NPU 初始化失败缓存（会话级）：QNN 不可用/非 QNN 模型变体/库缺失时，首次失败后不再重试 */
    @Volatile
    private var mnnNpuFailed: Boolean = false

    /** MNN GPU 初始化失败缓存（会话级）：OpenCL 不可达时，首次失败后回退 MNN_CPU */
    @Volatile
    private var mnnGpuFailed: Boolean = false

    /** 是否有推理正在进行（[generate] 已进入未结束）。供 [release] 判定是否需延迟释放、
     *  性能浮窗决定是否取 native 实时 tps。*/
    @Volatile
    private var generating: Boolean = false

    /** 生成期间收到 [release] 请求时置位，由 [generate] 的 finally 在生成结束后统一释放。
     *  nativeGenerateStream 现用 stepping（prefill + generate(1) 循环），shouldAbort 命中后 1 token
     *  内即退出，故此延迟释放多为安全网、极少实际触发；保留以应对 prefill 阶段（不可中断）收到 release 的边角。*/
    @Volatile
    private var releasePending: Boolean = false

    /**
     * 各后端"当前已加载模型所用的"配置（路径 / 上下文 / 线程 / 温度 / lookahead）。供 [ensureLoaded] 判定是否需要重载：
     * 同模型同后端但线程/上下文/温度变了（用户在设置页改过）也必须重载。
     *
     * temperature 纳入指纹：MNN 采样器在 load() 内一次性构建，温度改值须重载才生效（见 mnn_jni.cpp）。
     * topP/repeatPenalty 为 AppConfig 常量、不会变，故不纳入指纹（但仍随 initialize 传入在 load 时设置）。
     */
    /** 本次 generate 是否触发了模型(重新)加载。 */
    private var reloadedThisCall: Boolean = false

    /** 加载结果类型（Task 1 遥测）：复用 / 首次冷加载 / 配置变化重载。 */
    private enum class LoadKind { REUSE, COLD, WARM }

    /** 最近一次 ensureAttemptLoaded 的结果（generationMutex 单飞内读取，无需同步）。 */
    private var lastLoadKind: LoadKind = LoadKind.REUSE
    private var lastLoadMs: Long = 0L

    /** 后端类型 -> 实例 */
    private fun backendFor(type: BackendType): InferenceBackend = when (type) {
        BackendType.MNN_CPU -> mnnCpuBackend
        BackendType.MNN_GPU -> mnnGpuBackend
        BackendType.MNN_NPU -> mnnNpuBackend
    }

    /** 该类型在运行时是否为候选（MNN_CPU 恒保留作兜底） */
    private fun isBackendCandidate(type: BackendType): Boolean = when (type) {
        BackendType.MNN_CPU -> mnnCpuSupported
        BackendType.MNN_GPU -> mnnGpuSupported
        BackendType.MNN_NPU -> mnnNpuSupported
    }

    /** 偏好映射到具体后端类型 */
    private fun preferredType(preference: BackendPreference): BackendType? = when (preference) {
        BackendPreference.AUTO -> null
        BackendPreference.MNN_CPU -> BackendType.MNN_CPU
        BackendPreference.MNN_GPU -> BackendType.MNN_GPU
        BackendPreference.MNN_NPU -> BackendType.MNN_NPU
    }

    /**
     * 按偏好解析「尝试顺序」。
     *
     * NPU 仅在用户**显式选择** [BackendPreference.MNN_NPU] 时进入链；AUTO 不含 NPU--直接 QNN HTP
     * 在非 root 锁定设备会原生崩在 `PipelineModule::load`（SIGSEGV 不可 catch，回退链失效，详见
     * memory `mnn-qnn-htp-selinux-blocked`），故 AUTO 默认走 [MNN_GPU, MNN_CPU]，避免每条消息都崩。
     * 显式选 NPU 时：NPU 就绪则 [NPU, GPU, CPU]，否则被 [isBackendCandidate] 过滤降级到 [GPU, CPU]。
     * 偏好类型若在链中则置首，其余按链兜底；MNN_CPU 恒在列。
     */
    fun backendOrder(preference: BackendPreference): List<BackendType> {
        val preferred = preferredType(preference)
        val baseChain = if (preferred == BackendType.MNN_NPU) {
            listOf(BackendType.MNN_NPU, BackendType.MNN_GPU, BackendType.MNN_CPU)
        } else {
            listOf(BackendType.MNN_GPU, BackendType.MNN_CPU)
        }
        val chain = baseChain.filter { it == BackendType.MNN_CPU || isBackendCandidate(it) }
        return if (preferred != null && chain.contains(preferred)) {
            listOf(preferred) + chain.filter { it != preferred }
        } else {
            chain
        }
    }

    /** 期望后端（尝试顺序中的首个） */
    fun desiredBackend(preference: BackendPreference): BackendType =
        backendOrder(preference).first()

    /** 会话级失败缓存是否命中（命中则跳过该后端，避免每条消息重载多 GB 模型再回退） */
    private fun isSessionFailed(type: BackendType): Boolean = when (type) {
        BackendType.MNN_NPU -> mnnNpuFailed
        BackendType.MNN_GPU -> mnnGpuFailed
        else -> false
    }

    private fun markSessionFailed(type: BackendType) {
        when (type) {
            BackendType.MNN_NPU -> mnnNpuFailed = true
            BackendType.MNN_GPU -> mnnGpuFailed = true
            else -> {}
        }
    }

    /**
     * 执行一次流式推理（含回退链）。
     *
     * @param modelPath `.mnn` 目录的 config.json 路径
     * @param messages 完整对话历史（MNN 后端由模型自带模板格式化）
     * @param preference 用户后端偏好
     * @param enableThinking 是否启用深度思考。透传给 MNN set_config 的 jinja context `enable_thinking`，
     *        运行时生效（无需重载）：false 时推理模型跳过 `<think>` 推理段直接作答。
     * @param batchMaxBytes native 流式批处理缓冲上限（字节）；Balanced 默认 256，Task 6 性能模式接入后
     *        由 [com.chatbyyourside.llm.profile.InferencePerformanceMode] 解析覆盖。
     * @param batchMaxMs native 流式批处理缓冲时间上限（ms）；Balanced 16。
     */
    /**
     * 执行一次流式推理（Task 7）：按 [ResolvedInferencePlan.attempts] 显式执行后端尝试。
     *
     * 运行时配置（线程/上下文/采样/变体枚举）全部由 plan 的各 [BackendAttempt] 承载；
     * 流式批处理阈值取 plan.streamPolicy。CPU 优化失败推进到 CPU 兼容（不黑名单 CPU）；
     * 首个可见 delta 后禁止透明换后端（见 [GenerationExecutionControl]）。
     *
     * @param modelPath `.mnn` 目录的 config.json 路径
     * @param resolvedPlan [InferenceProfileResolver] 生成的不可变执行计划（必填）
     * @param onToken 流式回调；返回 false 触发策略截断
     */
    suspend fun generate(
        modelPath: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        enableThinking: Boolean,
        onToken: (String) -> Boolean,
        downgradeReasons: List<String> = emptyList(),
        // Task 2：思考请求 / 模板能力（生成前已知，信封透传）+ 分类器实例（MnnBackend 在 finally
        // 内收口分类并入 finalize；取代原 provider 侧补记路径，避免加载期/首 attempt 前取消时
        // 补记到上一轮记录）。带默认值，旧调用方不受影响。
        thinkingRequested: Boolean? = null,
        templateCapability: String? = null,
        thinkingClassifier: ThinkingOutputClassifier? = null,
        executionControl: GenerationExecutionControl? = null,
        resolvedPlan: ResolvedInferencePlan? = null,
    ): GenerationResult = generationMutex.withLock {
        val plan = resolvedPlan ?: throw IllegalStateException("Task 7 起 generate 必须提供 resolvedPlan")
        val attempts = plan.attempts
        if (attempts.isEmpty()) throw IllegalStateException("resolvedPlan.attempts 为空")
        // Task 3：健康记录键的模型指纹（config.json 内容 SHA-256 前 16 hex）；模型替换 -> 新指纹 ->
        // 旧健康记录自然失效。仅 GPU 失败/成功路径消费，CPU 恒兜底不记录。
        val modelFingerprint = modelConfigFingerprint(modelPath)
        Log.i(TAG, "执行计划: attempts=${attempts.joinToString { it.variant.name }} req=${plan.requestedMode}")
        var lastError: Exception? = null
        // 各尝试失败原因（变体名 + 诊断信息），全失败时汇总报错。
        val failureReasons = mutableListOf<String>()
        val effectiveBatchBytes = plan.streamPolicy.batchMaxBytes
        val effectiveBatchMs = plan.streamPolicy.batchMaxMs
        reloadedThisCall = false
        synchronized(lifecycleLock) {
            generating = true
            // LocalChatProvider 在调用本方法前已注册 request control：提前取消体现在 reason；否则清上轮残留 abort。
            MnnBridge.abort = executionControl?.reason() != null
        }
        try {
            for (attempt in attempts) {
                if (executionControl?.canTryNextBackend() == false) break
                // 会话级失败黑名单（GPU/NPU；CPU 不黑名单）：命中则跳过该尝试，避免每轮重载再失败。
                if (isSessionFailed(attempt.backend)) continue

                // 切到此后端前，释放可能驻留的其他后端模型，避免两套模型同时占内存。
                releaseOthers(keep = attempt.backend)

                val ok = try {
                    ensureAttemptLoaded(attempt, modelPath)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    lastError = e
                    false
                }

                if (!ok) {
                    val reason = backendFor(attempt.backend).lastErrorMessage ?: lastError?.message ?: "初始化失败"
                    failureReasons += "${attempt.variant.name}: $reason"
                    Log.w(TAG, "${attempt.variant.name} 初始化失败: $reason")
                    // CPU 优化失败推进到 CPU 兼容（下一变体），不黑名单 CPU；GPU/NPU 失败记会话级黑名单。
                    if (attempt.backend == BackendType.MNN_GPU) markSessionFailed(BackendType.MNN_GPU)
                    if (attempt.backend == BackendType.MNN_NPU) markSessionFailed(BackendType.MNN_NPU)
                    // Task 3：加载失败叠加持久 LOAD 类别健康记录（非 CPU 后端；CPU 恒兜底不记，
                    // 与 markSessionFailed 的「CPU 不黑名单」语义一致）。Task 3 review M-4：
                    // 健康记录是旁路，写失败（如 DataStore I/O）不得使整次 generate 失败。
                    if (attempt.backend != BackendType.MNN_CPU) {
                        recordHealthWrite("afterLoadFailure") {
                            healthCoordinator?.afterLoadFailure(attempt.backend, attempt.variant, modelFingerprint)
                        }
                    }
                    runCatching { releaseBackend(attempt.backend) }
                    continue
                }

                if (executionControl?.canTryNextBackend() == false) break
                val attemptMaxTokens = executionControl?.remainingTokens() ?: maxTokens
                if (attemptMaxTokens <= 0) break

                try {
                    val backend = backendFor(attempt.backend)
                    // 提前标记当前后端：供性能浮窗在生成中查询此后的 native 指标（tps 等）。
                    lastUsedBackend = attempt.backend
                    val summary = backend.generateStreamMessages(
                        messages, attemptMaxTokens, temperature, topP, repeatPenalty, enableThinking, onToken,
                        effectiveBatchBytes, effectiveBatchMs, downgradeReasons, executionControl,
                        plan.powerPolicy,
                        // Task 1 遥测：性能模式 / 实际配置指纹 / 尝试链 / 加载耗时（冷/热区分）。
                        requestedMode = plan.requestedMode,
                        effectiveMode = plan.effectiveMode,
                        loadConfigHash = attempt.loadConfigHash,
                        attemptTrace = plan.attempts.map { it.variant.name },
                        coldLoadMs = if (lastLoadKind == LoadKind.COLD) lastLoadMs else null,
                        warmLoadMs = if (lastLoadKind == LoadKind.WARM) lastLoadMs else null,
                        // Task 2：思考请求 / 模板能力信封透传 + 分类器实例（分类在 finally 内收口并入 finalize）。
                        thinkingRequested = thinkingRequested,
                        templateCapability = templateCapability,
                        thinkingClassifier = thinkingClassifier,
                    )
                    val completionReason = executionControl?.reason()
                        ?: (backend as? MnnBackend)?.lastTurnRecord?.completionReason
                        ?: summary?.completionReason?.let(CompletionReason::valueOf)
                    // Task 3 review I-1：仅「完成一次非错误生成」的字面语义才升 MODEL_OK——
                    // 完成原因须在 {EOS, MAX_TOKENS, POLICY_TRUNCATION} 内。USER_CANCEL/TIMEOUT/
                    // THERMAL_STOP 是中断（requestStop 提前返回、不抛异常），不代表后端已证明可用；
                    // 若也标记，持续挂起（watchdog 超时但从不抛异常）的 OpenCL 每轮都被重标可用、
                    // 永不进入冷却升级，健康记录恒为「已证明可用」的谎言状态。null/其它原因同样不记。
                    // M-1：与失败路径一致，CPU 恒兜底不记录（CPU-only 设备不再每轮白写 CPU 键
                    // MODEL_OK，DataStore 全量重编码 + 磁盘写位于 generationMutex 内、返回前）。
                    // M-4：健康记录是旁路，写失败不得被误判为生成失败（否则会触发回退 + 黑名单）。
                    if (completionReason in COMPLETED_REASONS && attempt.backend != BackendType.MNN_CPU) {
                        recordHealthWrite("markModelOk") {
                            healthCoordinator?.markModelOk(attempt.backend, attempt.variant, modelFingerprint)
                        }
                    }
                    return@withLock GenerationResult(
                        summary = summary,
                        usedBackend = attempt.backend,
                        reloaded = reloadedThisCall,
                        completionReason = completionReason,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // Task 3：生成异常才记持久 GENERATION 失败（非 CPU 后端；CPU 恒兜底不记）。
                    // 取消/超时/热停是 requestStop 提前返回的路径，不抛异常，不会进入本分支；
                    // CancellationException 已在上面单独 rethrow——故此处只可能是真实后端异常。
                    // Task 3 review M-4：健康记录是旁路，写失败不得让整次 generate 失败。
                    if (attempt.backend != BackendType.MNN_CPU) {
                        recordHealthWrite("afterGenerationFailure") {
                            healthCoordinator?.afterGenerationFailure(attempt.backend, attempt.variant, modelFingerprint)
                        }
                    }
                    if (executionControl != null && executionControl.remainingTokens() < maxTokens) {
                        // 已有可见输出后禁止透明换后端，否则两个模型的 delta 会拼成一条且 KV/语义均失配。
                        executionControl.requestStop(CompletionReason.BACKEND_FAILURE)
                    }
                    if (executionControl?.canTryNextBackend() == false) {
                        return@withLock GenerationResult(
                            usedBackend = attempt.backend,
                            reloaded = reloadedThisCall,
                            completionReason = executionControl.reason(),
                        )
                    }
                    Log.w(TAG, "${attempt.variant.name} 生成失败，尝试下一后端: ${e.message}")
                    failureReasons += "${attempt.variant.name}: 生成失败 - ${e.message}"
                    if (attempt.backend == BackendType.MNN_GPU) markSessionFailed(BackendType.MNN_GPU)
                    if (attempt.backend == BackendType.MNN_NPU) markSessionFailed(BackendType.MNN_NPU)
                    lastError = e
                    runCatching { releaseBackend(attempt.backend) }
                }
            }

            executionControl?.reason()?.let { reason ->
                return@withLock GenerationResult(
                    usedBackend = lastUsedBackend,
                    reloaded = reloadedThisCall,
                    completionReason = reason,
                )
            }

            // 所有尝试均失败：汇总各变体原因详细报错。
            val detail = if (failureReasons.isEmpty()) "所有后端尝试均初始化失败"
                else "本地模型加载失败（所有后端尝试均失败）。${failureReasons.joinToString("；")}"
            Log.e(TAG, detail)
            throw lastError?.let { IllegalStateException(detail, it) } ?: IllegalStateException(detail)
        } finally {
            // 与 [release] 互斥：原子地清 generating 并取走 releasePending，决定是否本轮释放。
            val pending: Boolean
            synchronized(lifecycleLock) {
                generating = false
                MnnBridge.abort = false
                pending = releasePending
                releasePending = false
            }
            if (pending) {
                runCatching { doReleaseAll() }
            }
        }
    }

    /** 中断当前推理（所有 MNN 后端都设置 abort 标志） */
    suspend fun stopGeneration() {
        mnnCpuBackend.stopGeneration()
        mnnGpuBackend.stopGeneration()
        mnnNpuBackend.stopGeneration()
    }

    /** 非挂起中断：原因由请求级 control 先行写入；这里只在活跃生成期发布 abort，不释放 native。 */
    fun cancel() {
        if (!generating) return
        // 后端实例可为注入的 fake（Task 3 review M-3）：cancelNow 是 MnnBackend 独有，
        // 非 MnnBackend 的注入实例无需 abort（fake 无 native 生成）。
        (mnnCpuBackend as? MnnBackend)?.cancelNow()
        (mnnGpuBackend as? MnnBackend)?.cancelNow()
        (mnnNpuBackend as? MnnBackend)?.cancelNow()
    }

    /** 当前活跃后端的指标（按 [lastUsedBackend] 取） */
    fun getActiveMetrics(): BackendMetrics = backendFor(lastUsedBackend).getBackendMetrics()

    /** 当前活跃后端最近一次生成的遥测记录（Task 2）；供 LocalChatResult 汇总。三类后端均为 MnnBackend。 */
    fun lastTurnRecord(): InferenceTurnRecord? =
        (backendFor(lastUsedBackend) as? MnnBackend)?.lastTurnRecord

    /** 当前是否有推理在进行（供性能浮窗决定取 native 实时 tps 还是归零）*/
    fun isGenerating(): Boolean = generating

    /** 释放所有 MNN 后端资源。
     *
     * 推理进行中时**延迟释放**作为安全网：[nativeGenerateStream] 现用 stepping 解码（prefill + generate(1)
     * 循环），shouldAbort 命中后 1 token 内退出，decode 阶段中断极快；但 prefill 阶段（单次阻塞）不可
     * 中断，其进行中收到 release 仍需等其返回。故生成中仅置 [releasePending]，由 [generate] 的 finally
     * 在生成结束（JNI 已返回）后执行 [doReleaseAll]。典型场景：用户在流式回复进行中到模型管理页删除当前
     * 模型——删除立即返回（文件可删，mmap 的 inode 仍在），句柄在当前回复跑完后释放。
     * 非生成态立即释放。*/
    fun release() {
        // 与 [generate] 的 finally 互斥：要么见 generating=true 置 pending（由 generate finally 释放），
        // 要么见 generating=false 立即释放。二者原子，避免「release 见生成中置 pending、但 generate
        // finally 已读过 pending=false」的漏释放竞态。
        val defer: Boolean
        synchronized(lifecycleLock) {
            if (generating) {
                releasePending = true
                defer = true
            } else {
                defer = false
            }
        }
        if (defer) {
            Log.i(TAG, "release: 推理进行中，延迟释放（生成结束后执行）")
        } else {
            doReleaseAll()
        }
    }

    private val lifecycleLock = Any()

    /** 实际释放全部后端 + 清配置。synchronized 防并发 release（如 delete + 再次 delete）双重释放。*/
    private fun doReleaseAll() {
        synchronized(lifecycleLock) {
            mnnCpuBackend.release()
            mnnGpuBackend.release()
            mnnNpuBackend.release()
        }
    }

    /** 重置会话级后端失败缓存（[mnnGpuFailed]/[mnnNpuFailed]）。
     *  用户显式切换后端偏好时调用，让被选后端重新尝试——否则一旦某后端失败，整个会话期都不再重试，
     *  即便用户主动切到它也只会被跳过。显式切换本身是用户意图，承担一次可能的失败重试开销合理。*/
    fun resetSessionFailures() {
        mnnNpuFailed = false
        mnnGpuFailed = false
        Log.i(TAG, "会话级后端失败缓存已重置")
    }

    // ===== 内部：加载确保 / 释放 =====

    /** 确保指定后端按指定尝试加载（同路径同 loadConfigHash 热复用，否则重载）。失败返回 false。 */
    private suspend fun ensureAttemptLoaded(attempt: BackendAttempt, modelPath: String): Boolean {
        val backend = backendFor(attempt.backend)
        if (backend.isModelLoaded && (backend as? MnnBackend)?.isLoadedWithConfigHash(modelPath, attempt.loadConfigHash) == true) {
            // 热复用：零加载耗时，不纳入冷/热统计。
            lastLoadKind = LoadKind.REUSE
            lastLoadMs = 0L
            return true
        }
        // 区分首次冷加载（此前未加载）与配置变化重载（此前加载过别的配置）。
        val wasLoaded = backend.isModelLoaded
        val t = SystemClock.elapsedRealtime()
        val ok = backend.initialize(modelPath, attempt.nativeConfigJson, attempt.loadConfigHash)
        lastLoadMs = SystemClock.elapsedRealtime() - t
        lastLoadKind = when {
            !ok -> LoadKind.REUSE
            wasLoaded -> LoadKind.WARM
            else -> LoadKind.COLD
        }
        if (ok) reloadedThisCall = true
        return ok
    }

    /** 释放 [keep] 以外的已加载后端模型，避免切换后两套模型同时占内存 */
    private suspend fun releaseOthers(keep: BackendType) {
        if (keep != BackendType.MNN_CPU && mnnCpuBackend.isModelLoaded) {
            runCatching { mnnCpuBackend.release() }
        }
        if (keep != BackendType.MNN_GPU && mnnGpuBackend.isModelLoaded) {
            runCatching { mnnGpuBackend.release() }
        }
        if (keep != BackendType.MNN_NPU && mnnNpuBackend.isModelLoaded) {
            runCatching { mnnNpuBackend.release() }
        }
    }

    private fun releaseBackend(type: BackendType) {
        when (type) {
            BackendType.MNN_CPU -> mnnCpuBackend.release()
            BackendType.MNN_GPU -> mnnGpuBackend.release()
            BackendType.MNN_NPU -> mnnNpuBackend.release()
        }
    }

    /**
     * Task 3 review M-4：健康记录写入统一旁路。
     *
     * 健康记录是旁路数据，任何写入异常（DataStore I/O 失败、协程取消竞态等）都不得影响推理本身：
     * - [afterLoadFailure] 原在 try 外——DataStore edit 抛 IOException 会直接使整次 generate 失败；
     * - [markModelOk] 原在 try 内——抛异常会被下方 catch 误判为 GENERATION 失败，触发回退 + 黑名单；
     * - [afterGenerationFailure] 原在 catch 块内——抛异常会改写整次 generate 的失败形态。
     * 三处一律经本方法包裹，失败仅记日志。
     */
    private suspend fun recordHealthWrite(tag: String, block: suspend () -> Unit) {
        runCatching { block() }
            .onFailure { Log.w(TAG, "健康记录写入失败（忽略，不影响推理）[$tag]: ${it.message}") }
    }

    data class GenerationResult(
        /** native 返回的 GenerationSummary（null=摘要解析失败/未走 native）。全文不再整份携带，
         *  由 LocalChatProvider 作为唯一累加器拼接；此摘要仅供指标/完成原因上报。 */
        val summary: NativeGenerationSummary? = null,
        val usedBackend: BackendType,
        /** 本次推理是否触发了模型(重新)加载（冷启动首条 / 配置变更 / 后端切换均为 true）。 */
        val reloaded: Boolean = false,
        /** 请求级明确终止原因（跨后端 fallback）；优先于单 attempt 摘要。 */
        val completionReason: CompletionReason? = null,
    )

    companion object {
        private const val TAG = "BackendManager"

        /** Task 3 review I-1：「完成一次非错误生成」的完成原因集合——仅这些记 MODEL_OK。
         *  中断（USER_CANCEL/TIMEOUT/THERMAL_STOP）与后端错误不是完成，不得证明后端可用。 */
        private val COMPLETED_REASONS = setOf(
            CompletionReason.EOS,
            CompletionReason.MAX_TOKENS,
            CompletionReason.POLICY_TRUNCATION,
        )
    }
}
