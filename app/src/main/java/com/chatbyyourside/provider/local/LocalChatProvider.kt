package com.chatbyyourside.provider.local

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.config.Characters
import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.data.model.ChatProviderType
import com.chatbyyourside.data.model.DEFAULT_MNN_MODELS
import com.chatbyyourside.data.repository.SettingsRepository
import com.chatbyyourside.llm.CpuBoostController
import com.chatbyyourside.llm.GenerationExecutionControl
import com.chatbyyourside.llm.ModelBundleValidator
import com.chatbyyourside.llm.GenerationSafetyPolicy
import com.chatbyyourside.llm.IncrementalScriptDetector
import com.chatbyyourside.llm.ThermalDecision
import com.chatbyyourside.llm.profile.InferencePerformanceMode
import com.chatbyyourside.llm.profile.InferenceProfileResolver
import com.chatbyyourside.llm.profile.OpenClHealthState
import com.chatbyyourside.llm.InferenceThreadOptimizer
import com.chatbyyourside.llm.PromptWindowPlanner
import com.chatbyyourside.llm.PromptWindowResult
import com.chatbyyourside.llm.ThermalMonitor
import com.chatbyyourside.llm.backend.BackendManager
import com.chatbyyourside.llm.backend.BackendType
import com.chatbyyourside.llm.metrics.CompletionReason
import com.chatbyyourside.perfmon.BackendType as PerfmonBackendType
import com.chatbyyourside.provider.ChatProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地聊天 Provider
 *
 * 调用 MNN 进行推理，支持原生 token 级流式输出。本地 AI 完全免费，无需 API Key。
 *
 * 后端选择：通过 [BackendManager] 按用户偏好（[com.chatbyyourside.llm.backend.BackendPreference]）
 * 在 MNN CPU / OpenCL GPU / QNN NPU 间选择，不可用/失败时按链回退到 MNN_CPU。聊天模板由 MNN 按
 * 各模型自带模板应用（Qwen=ChatML，Llama/Gemma/Phi 各异）。
 *
 * CPU 调度优化 / 温度监控：保留并改接到 MNN。线程数取 min(用户设定, 大核数, 温度上限) 喂给 MNN
 * 的 thread_num（加载时生效）；CPU 提频由 [CpuBoostController] 在 MnnBackend 内包住推理调用。
 */
class LocalChatProvider(
    private val context: Context,
    private val backendManager: BackendManager,
    private val settings: SettingsRepository,
    private val cpuBoostController: CpuBoostController,
) : ChatProvider {

    // ===== CPU 调度优化 / 温度监控（不改 MNN 加载逻辑，仅优化线程数与提频）=====
    // threadOptimizer 须先于 thermalMonitor 初始化（thermalMonitor 的 bigCoreCountProvider 引用它）。
    private val threadOptimizer = InferenceThreadOptimizer()
    private val thermalMonitor = ThermalMonitor(context) { threadOptimizer.getBigCoreCount() }
    private val promptWindowPlanner = PromptWindowPlanner()

    @Volatile
    private var previousPromptAnchor: String? = null
    /** 当前整次本地生成的请求级控制面；CAS 清理避免旧请求 finally 抹掉新请求。 */
    private val activeExecutionControl = AtomicReference<GenerationExecutionControl?>(null)
    /** 上一轮可复用的实测 assistant token 数；仅在原始文本精确匹配时用于下一轮估算。 */
    @Volatile
    private var measuredAssistantText: String? = null
    @Volatile
    private var measuredAssistantTokens: Int = 0
    @Volatile
    private var thermalMonitoringStarted = false
    /** 最近一次热状态决策（Task 8）；影响下一轮有效模式与线程 cap。 */
    @Volatile
    private var thermalDecision: ThermalDecision? = null
    /** 当前请求的性能模式（热降级决策的参考模式；由 chatTyped 每轮更新）。 */
    @Volatile
    private var currentRequestedMode: InferencePerformanceMode = InferencePerformanceMode.DEFAULT

    /** 启动温度监控（幂等）。热回调按 [ThermalMonitor.decide] 决策：撤销 hint / 请求 THERMAL_STOP /
     *  记录下一轮模式与线程 cap。MNN 已加载线程数不可中途改变，线程下调走下一轮 resolve。 */
    private fun ensureThermalMonitoring() {
        if (thermalMonitoringStarted) return
        thermalMonitoringStarted = true
        thermalMonitor.startThermalMonitoring { _ ->
            val level = thermalMonitor.currentLevel()
            val decision = ThermalMonitor.decide(
                level = level,
                requestedMode = currentRequestedMode,
                bigCoreCount = threadOptimizer.getBigCoreCount(),
            )
            thermalDecision = decision
            Log.w(
                TAG,
                "Thermal level=$level -> mode=${decision.effectiveMode} cap=${decision.nextThreadCap} " +
                    "boost=${!decision.removeBoostNow} stop=${decision.stopNow}",
            )
            if (decision.removeBoostNow) cpuBoostController.deactivateHintNow()
            if (decision.stopNow) {
                // 热停止：先原子锁定原因（不计后端失败），再请求全局 abort；JNI 返回后由 finally 收尾。
                activeExecutionControl.get()?.requestStop(CompletionReason.THERMAL_STOP)
                backendManager.cancel()
            }
        }
    }

    override val type: ChatProviderType = ChatProviderType.LOCAL

    /** ChatProvider 接口：返回展示文本（本地历史精确复用走 [chatTyped] 取 modelText）。 */
    override suspend fun chat(
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit,
    ): String = chatTyped(messages, onChunk).displayText

    /**
     * 本地聊天（类型化结果，Task 3 Step 4）：分离展示文本与模型原始文本。
     *
     * - [LocalChatResult.displayText]：经 `<think>` 折叠装饰的展示文本，存 `content`、驱动 UI。
     * - [LocalChatResult.modelText]：模型原始输出（与 native `syncPromptCache()` 逐字节一致），存 `modelContent`，
     *   重放本地历史时优先取它喂回 MNN，保证 KV 前缀复用精确命中。展示装饰永不进入 toMessagesJson。
     */
    suspend fun chatTyped(
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit,
    ): LocalChatResult {
        // 1. 确保模型已选定并解析路径（MNN 目录的 config.json）
        val activeModelId = settings.getActiveLocalModelIdNow()
        if (activeModelId.isNullOrBlank()) {
            throw Exception("未选择本地模型，请先在模型管理页下载并选择模型")
        }

        val modelPath = ModelPathResolver.getLoadPath(context, activeModelId)
            ?: throw Exception("模型文件未找到，请先下载模型: $activeModelId")

        // 2a. 模型包完整性校验（Task 12）：config 派生必需文件（graph/weight/tokenizer/...）存在、
        //     非空、非分片、路径不逃逸。校验失败拒绝进入 native（绝不硬编码 verified=true）。
        val validation = ModelBundleValidator.validate(File(modelPath).parentFile ?: File(modelPath))
        if (!validation.valid) {
            throw Exception("模型包校验失败：${validation.errors.joinToString("；")}")
        }

        // 2. 检查 MNN 引擎 native 就绪（libMNN.so）
        if (!backendManager.mnnCpuSupported) {
            throw Exception("MNN 引擎未就绪。当前版本未集成 libMNN.so，请等待后续版本。")
        }

        // native 加载与推理均为阻塞调用，必须切到 IO 调度器，否则在主线程上会 ANR。
        // onChunk 回调会跨线程调用 StateFlow.update，但 update 是 CAS 线程安全的。
        return withContext(Dispatchers.IO) {
            ensureThermalMonitoring()

            // 3. 读取推理参数（Task 6：一次 DataStore 快照读，替换逐字段 8 个 .first()；
            //    国产 ROM DataStore I/O 被拦截时整体超时回退不可变默认快照，避免逐字段多次挂起点）。
            val settingsNow = settings.getLocalInferenceSettingsNow()
            val contextLen = settingsNow.contextLen
            val userThreads = settingsNow.threads
            val temperature = settingsNow.temperature
            val maxTokens = settingsNow.maxTokens
            val preference = settingsNow.backend
            val lookahead = settingsNow.lookahead
            val performanceMode = settingsNow.performanceMode
            currentRequestedMode = performanceMode
            // Task 8：不再设置全局 boost 开关——提频由 PowerPolicy 驱动（Balanced 温和/MAXIMUM_SPEED 激进+sustained）。
            // 深度思考开关：透传给 MNN jinja context enable_thinking（运行时生效，无需重载）。
            // 关闭时推理模型跳过 <think> 推理段直接作答（修复「关闭开关仍深度思考」）。
            val deepThinking = settingsNow.deepThinking
            // 仅推理模型（Think 标签）的输出需要折叠包装：其 chat 模板把起始 <think> 放在 generation
            // prompt 前缀（非输出流），故 native 输出缺起始 <think>，parseWithThink 无法折叠（修复「本地
            // 思考过程不可折叠」）。非推理模型（Llama/Gemma/SmolLM）不产生 <think>，无需包装。
            val shouldFoldThink = deepThinking && isThinkingModel(activeModelId)

            // 有效线程数 = min(用户设定, 大核数, 温度上限)。
            // - 不超过大核数：多了会跑到小核，反而变慢且更耗电发热。
            // - 温度上限：当前若已高温（MODERATE/SEVERE/CRITICAL），开箱即降频。
            val opt = threadOptimizer.optimizeThreadAffinity()
            // 大核探测失败（cpu_sys_jni 未加载或返回空）时回退到 availableProcessors（封顶 4、至少 2），
            // 不能让线程数塌缩到 1——单线程跑数 GB 模型 prefill 可达数分钟级（5 分钟无输出即此症状）。
            val bigCount = opt.bigCoreIds.size.let {
                if (it > 0) it else Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            }
            val thermalCap = thermalMonitor.recommendedThreadCount(bigCount) // -1 = 不限制
            val baseThreads = (if (thermalCap > 0) {
                minOf(userThreads, bigCount, thermalCap)
            } else {
                minOf(userThreads, bigCount)
            }).coerceAtLeast(1)
            // Task 8：热降级决策的下一轮线程 cap（MODERATE=大核半、SEVERE=2、CRITICAL=1）再收紧。
            val decision = thermalDecision
            val effectiveThreads = if (decision != null && decision.nextThreadCap > 0) {
                minOf(baseThreads, decision.nextThreadCap).coerceAtLeast(1)
            } else baseThreads

            // 模型加载（阻塞 native）期间若被取消，立即抛 CancellationException，不进入生成。
            ensureActive()

            Log.i(
                TAG, "infer: user=$userThreads big=$bigCount thermalCap=$thermalCap " +
                    "-> threads=$effectiveThreads, pref=$preference, lookahead=$lookahead"
            )

            // 4. 统一走 BackendManager：按偏好选 MNN 后端，失败按链回退。
            //    MNN 后端由模型自带 chat 模板格式化消息列表。topP/repeatPenalty 沿用默认值。
            // 本地小模型专属防「上头」：给 system prompt 追加输出规范约束（仅本地，云端大模型走
            // CloudChatProvider 不受影响），压制角色扮演滑向编造多角色剧本并无限生成。
            val enhancedMessages = messages.mapIndexed { idx, msg ->
                if (idx == 0 && msg.role == "system") {
                    msg.copy(content = msg.content + RESPONSE_GUIDE)
                } else msg
            }
            // Task 5：先在保留 modelContent 的原始消息上规划窗口（估算用 modelContent ?: content），
            // 再把选中 assistant 的原始模型文本映射到 content 喂 MNN。绝不摘要/改写历史文本。
            val measuredText = measuredAssistantText
            val knownTokenCounts = if (measuredText != null && measuredAssistantTokens > 0) {
                enhancedMessages.mapIndexedNotNull { index, message ->
                    val raw = message.modelContent ?: message.content
                    if (message.role == "assistant" && raw == measuredText) index to measuredAssistantTokens else null
                }.toMap()
            } else emptyMap()
            val promptResult = promptWindowPlanner.plan(
                messages = enhancedMessages,
                admittedContextTokens = contextLen,
                requestedOutputTokens = maxTokens,
                previousAnchor = previousPromptAnchor,
                knownMessageTokenCounts = knownTokenCounts,
            )
            val promptPlan = when (promptResult) {
                is PromptWindowResult.Success -> promptResult.plan
                is PromptWindowResult.AdmissionFailure -> throw com.chatbyyourside.llm.PromptAdmissionException(promptResult)
            }
            val modelMessages = promptPlan.messages.map { message ->
                val raw = message.modelContent ?: message.content
                message.copy(content = raw, modelContent = null)
            }
            if (promptPlan.anchorChanged || promptPlan.downgradeReason != null) {
                Log.i(
                    TAG,
                    "prompt window: input=${promptPlan.estimatedInputTokens} output=${promptPlan.reservedOutputTokens} " +
                        "anchorChanged=${promptPlan.anchorChanged} reason=${promptPlan.downgradeReason}",
                )
            }
            // Task 4 Step 3：本地是**唯一**原始回复累加器——native 不再返回全文，[LocalStreamRenderPump]
            // 持有 [accumulated] 由流式 delta 拼接；BackendManager 只带回 GenerationSummary（指标/完成原因）。
            var truncated = false  // onToken 截断后置位，后续 token 不再累积、持续返回 false 让 native abort
            // 增量剧本检测（Task 4 Step 5）：每 token 只扫新增区间 + 最长角色名重叠窗口（O(1) 空间、
            // 无重扫），替代旧实现每块对全文 [indexOf] 的 O(n²)。cutAbsoluteIndex 与全文累加器下标
            // 对齐（同序从空喂入，由解码线程串行调用）。
            val scriptDetector = IncrementalScriptDetector(SCRIPT_NAMES)
            // Task 4 Step 6：`<think>` 装饰与 UI 回调移入独立渲染协程（LocalStreamRenderPump），
            // 解码回调只做增量 append + 剧本检测 + conflated 信号，不再整串 toString + renderLocalThink，
            // 避免同步工作直接推迟下一次 generate(1)。首块立即渲染保证即时性；末帧由 finish 兜底。
            val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val renderPump = LocalStreamRenderPump(scope = renderScope, minIntervalMs = RENDER_THROTTLE_MS)
            renderPump.decorate = { renderLocalThink(it, shouldFoldThink) }
            renderPump.onChunk = { onChunk(it) }
            renderPump.start()
            val safetyPolicy = GenerationSafetyPolicy.forMode(
                performanceMode,
                promptPlan.reservedOutputTokens,
            )
            val executionControl = GenerationExecutionControl(
                policy = safetyPolicy,
                startedElapsedMs = SystemClock.elapsedRealtime(),
            )
            activeExecutionControl.set(executionControl)
            // Task 7：由性能模式/后端偏好/设备/热准入线程解析不可变执行计划（含每变体 native 配置）。
            val resolvedPlan = InferenceProfileResolver(context.cacheDir, modelPath).resolve(
                // Task 8：热降级后的有效模式（MODERATE+ 恒 BALANCED，撤销 sustained）。
                mode = decision?.effectiveMode ?: performanceMode,
                backendPreference = preference,
                contextTokens = contextLen,
                maxOutputTokens = promptPlan.reservedOutputTokens,
                thermalAdmittedThreads = effectiveThreads,
                lookahead = lookahead,
                temperature = temperature,
                topP = AppConfig.LLM.DEFAULT_TOP_P,
                repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
                openclHealth = if (backendManager.mnnGpuSupported) OpenClHealthState.PROBE_OK else OpenClHealthState.UNKNOWN,
            )
            val result = try {
                coroutineScope {
                    // 请求级 watchdog：进度由 MnnBackend 回调按真实时间直接写入 control；本协程只判 deadline。
                    // timeout 先原子锁定原因，再请求全局 abort；绝不跨线程释放 native。
                    val watchdog = launch {
                        while (isActive) {
                            delay(WATCHDOG_POLL_MS)
                            if (executionControl.completionReason(SystemClock.elapsedRealtime()) == CompletionReason.TIMEOUT) {
                                Log.w(TAG, "generation watchdog timeout -> request abort")
                                backendManager.cancel()
                                break
                            }
                        }
                    }
                    try {
                        backendManager.generate(
                            modelPath = modelPath,
                            messages = modelMessages,
                            maxTokens = resolvedPlan.maxOutputTokens,
                            temperature = temperature,
                            topP = AppConfig.LLM.DEFAULT_TOP_P,
                            repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
                            enableThinking = deepThinking,
                            // Task 5：把类型化计划降级原因并入遥测，区分 KV miss 是窗口变化、配置重载还是后端健康降级。
                            downgradeReasons = (listOfNotNull(promptPlan.downgradeReason) +
                                resolvedPlan.downgradeReasons.map { it.name }).distinct(),
                            executionControl = executionControl,
                            resolvedPlan = resolvedPlan,
                            onToken = { token ->
                                if (!truncated) {
                                    // 同步回调只做三件事：append、剧本检测截断、并发渲染信号——不做任何字符串
                                    // 整段拷贝/装饰/UI 更新，让 generate(1) 尽快回到 MNN 解码。
                                    renderPump.append(token)
                                    // 兜底截断：增量检测「角色名：」多角色剧本标记 -> 截到标记前并停止。
                                    val cutPos = scriptDetector.append(token).cutAbsoluteIndex
                                    if (cutPos != null) {
                                        renderPump.truncateTo(cutPos)
                                        truncated = true
                                    }
                                }
                                // false -> abort + POLICY_TRUNCATION；max-token 由 native 硬边界返回 MAX_TOKENS。
                                !truncated
                            },
                        )
                    } finally {
                        watchdog.cancel()
                    }
                }
            } finally {
                // 取消渲染协程并同步渲染最终帧；renderScope 随后回收。
                try {
                    renderPump.finish()
                } catch (e: Exception) {
                    Log.w(TAG, "renderPump.finish 异常（忽略）: ${e.message}")
                }
                renderScope.cancel()
                activeExecutionControl.compareAndSet(executionControl, null)
            }
            previousPromptAnchor = promptPlan.anchor

            // 配置变更检测：本次推理成功后，把"本次生效的"用户配置写回 last_applied，使设置页横幅归位。
            if (result.reloaded) {
                Log.i(
                    TAG,
                    "本次推理触发模型加载/重载: userThreads=$userThreads ctx=$contextLen pref=$preference " +
                        "lookahead=$lookahead (effectiveThreads=$effectiveThreads, backend=${result.usedBackend.displayName})"
                )
            }
            settings.acknowledgeLlmConfig(userThreads, contextLen, preference, lookahead, temperature)
            // Task 7：ack 实际应用的 plan 配置哈希（重载指纹），供诊断/后续健康记录。
            settings.setLlmLastConfigHash(resolvedPlan.firstAttempt?.loadConfigHash)

            Log.i(TAG, "生成完成，使用后端: ${result.usedBackend.displayName}")

            // 本地是唯一累加器：全文来自流式拼接（native 不再返回全文），空则占位文案。
            // 折叠包装落库：与流式展示共用同一 [renderLocalThink] 逻辑，使历史消息重新渲染时仍可折叠
            // （修复「输出中可折叠、输出完不可折叠」--之前未见 </think> 时落库不补起始 <think>，
            // parseWithThink 失配变纯文本）。被 max_tokens 截断在思考中途时保留未闭合 <think>，
            // 半截思考仍可折叠查看、不泄漏到正文。
            val finalRaw = renderPump.snapshot()
            val finalText = renderLocalThink(finalRaw, shouldFoldThink)
            val displayText = finalText.ifBlank { "(本地模型未生成回复)" }
            // 原始模型输出（与 native syncPromptCache 逐字节一致）：本地累加器即最终原始文本。
            val modelText = finalRaw
            val record = backendManager.lastTurnRecord()
            if (modelText.isNotEmpty() && record != null && record.generatedTokens > 0) {
                measuredAssistantText = modelText
                measuredAssistantTokens = record.generatedTokens
            }
            LocalChatResult(
                displayText = displayText,
                modelText = modelText,
                generation = GenerationSummary(
                    backend = result.usedBackend,
                    reloaded = result.reloaded,
                    generatedTokens = record?.generatedTokens ?: 0,
                    decodeTps = record?.decodeTps,
                    kvReuse = record?.kvReuse,
                    completionReason = result.completionReason ?: record?.completionReason,
                ),
            )
        }
    }

    override fun cancel() {
        // 原因必须先于全局 abort 发布，避免 active backend 先返回而把取消误记成其他原因。
        activeExecutionControl.get()?.requestStop(CompletionReason.USER_CANCEL)
        backendManager.cancel()
    }

    // ===== 供性能浮窗调用的接口 =====
    /** 最快大核当前频率（GHz），读不到返回 0 */
    fun getBigCoreFreqGHz(): Float = threadOptimizer.getBigCoreFreqGHz()

    /** 当前温度状态文案（正常/轻微发热/中等发热/...） */
    fun getThermalStatus(): String = thermalMonitor.getThermalStatusText()

    /** CPU 拓扑 JSON */
    fun getCpuTopology(): String = threadOptimizer.getCpuTopologyJson()

    /** 当前实际使用的后端类型（供浮窗「引擎」栏高亮，映射到 perfmon.BackendType）*/
    fun getActiveBackend(): PerfmonBackendType = when (backendManager.lastUsedBackend) {
        BackendType.MNN_GPU -> PerfmonBackendType.GPU
        BackendType.MNN_NPU -> PerfmonBackendType.NPU
        BackendType.MNN_CPU -> PerfmonBackendType.CPU
    }

    /** 当前是否正在推理（供性能浮窗决定取 native 实时 tps 还是归零）*/
    fun isGenerating(): Boolean = backendManager.isGenerating()

    /** 当前活跃后端的 native 实时 tps（gen_seq_len/decode_us，精确；MNN 边 decode 边更新）。
     *  未加载/未生成返回 0。供性能浮窗在生成中显示精确速率，替代按 flush 近似计数的偏差。*/
    fun getActiveTps(): Float = backendManager.getActiveMetrics().tokensPerSecond

    companion object {
        private const val TAG = "LocalChatProvider"

        /** 仅轮询 Kotlin 原子进度快照；不读取/释放 native。 */
        private const val WATCHDOG_POLL_MS = 1000L

        /** 流式渲染节流（ms）：`<think>` 装饰与 onChunk 仅在该间隔放行时构造（Task 4 Step 6）。
         *  与 ChatViewModel.STREAM_THROTTLE_MS(30) 对齐；首 delta 无条件放行保证即时性。 */
        private const val RENDER_THROTTLE_MS = 30L

        /** 本地小模型输出规范：约束单角色简短回复、禁剧本格式。追加到 system prompt（仅本地）。
         *  针对小模型角色扮演「上头」编多角色剧本并无限生成的根因（见 .claude/plans/fix-llm-not-stopping.md）。 */
        private const val RESPONSE_GUIDE = "\n\n【输出规范（严格遵守）】\n" +
            "- 每次只回复一两句话，简短自然，回复完立即停止。\n" +
            "- 只以你自己的角色身份说话，不要扮演、模拟或代言其他角色。\n" +
            "- 禁止使用「名字：」格式的对话剧本/台词录，禁止自问自答、不要连续生成多个角色的台词。\n" +
            "- 不要写大段括号心理活动旁白。"

        /**
         * 剧本标记检测用角色名集合：全部人设名。模型滑向多角色剧本时会生成
         * 「角色名：台词」格式；正常单角色回复不用此格式（角色对用户说话用逗号或直说）。
         */
        private val SCRIPT_NAMES: List<String> = buildList {
            addAll(Characters.ALL.values.map { it.name })
        }

        /**
         * 判断模型是否为推理模型（产生 `<think>...</think>` 思考段）。
         *
         * 推理模型（Qwen3 / DeepSeek-R1 等）的 chat 模板把起始 `<think>` 放在 generation prompt 前缀
         * （非输出流），故 native 输出缺起始 `<think>`，[renderLocalThink] 据此补回以供折叠。
         *
         * 判定优先级：① 内置清单 [DEFAULT_MNN_MODELS] 的 Think 标签（权威）；② 清单外模型按 modelId
         * 关键词兜底（qwen3/qwq/deepseek-r1/reason/think），使自行添加的推理模型也能折叠。即便两者都
         * 未命中，[renderLocalThink] 仍会在输出含 `</think>` 时补起始标签折叠--本判定仅决定流式中
         * （尚未出现 `</think>` 时）是否补 `<think>` 显示「思考中…」。非推理模型不产生 `<think>`，无需处理。
         */
        private fun isThinkingModel(modelId: String?): Boolean {
            if (modelId.isNullOrBlank()) return false
            if (DEFAULT_MNN_MODELS.firstOrNull { it.id == modelId }
                    ?.tags?.any { it.equals("Think", ignoreCase = true) } == true) return true
            val id = modelId.lowercase()
            return id.contains("qwen3") || id.contains("qwq") || id.contains("deepseek-r1") ||
                id.contains("reason") || id.contains("think")
        }

        /**
         * 把本地推理模型的输出包装为可折叠的 `<think>...</think>` 结构，供 [MarkdownParser.parseWithThink] 折叠。
         *
         * 背景：Qwen3/R1 的 chat 模板把起始 `<think>` 放在 generation prompt 前缀（非输出流），故 native
         * 输出缺起始 `<think>`，[MarkdownParser.parseWithThink] 需 `<think>` 才能识别为思考段，否则推理过程
         * 以纯文本（夹一个孤立 `</think>`）显示、不可折叠。
         *
         * 包装规则（流式与落库共用同一逻辑，保证「输出中」与「输出完」折叠一致）：
         * - 含 `</think>`：补起始 `<think>`（[stripLeadingThink] 防模型自输出时双标签）-> 可折叠。
         * - 不含 `</think>` 但含 `<think>`：模型自输出起始标签（流式未闭合），保持原样即可折叠为「思考中…」。
         * - 两者都没有：仅当 [foldIfNoClose]（预判推理模型，其起始 `<think>` 在前缀故输出流缺）时补
         *   `<think>` 显示「思考中…」（含被 max_tokens 截断在思考中途，半截思考仍可折叠、不泄漏正文）；
         *   否则不补，避免把正常回复困在「思考中」折叠块。
         */
        private fun renderLocalThink(raw: String, foldIfNoClose: Boolean): String {
            val closeTag = "</think>"
            val closeIdx = raw.indexOf(closeTag)
            if (closeIdx >= 0) {
                val reasoning = stripLeadingThink(raw.substring(0, closeIdx))
                val content = raw.substring(closeIdx + closeTag.length)
                return "<think>$reasoning</think>$content"
            }
            if (raw.contains("<think>")) return raw  // 模型自输出起始标签，未闭合但已可折叠
            return if (foldIfNoClose) "<think>" + stripLeadingThink(raw) else raw
        }

        /** 去掉开头的 `<think>` 标签（trim 后匹配），防止模型自行输出 `<think>` 时与 [renderLocalThink] 补的重复。 */
        private fun stripLeadingThink(s: String): String {
            val t = s.trimStart()
            return if (t.startsWith("<think>")) t.substring("<think>".length) else s
        }
    }
}
