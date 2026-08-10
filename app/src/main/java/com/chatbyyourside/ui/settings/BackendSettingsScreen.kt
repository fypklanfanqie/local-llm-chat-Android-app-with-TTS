package com.chatbyyourside.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.chatbyyourside.AppContainer
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.llm.LlmMemoryEstimator
import com.chatbyyourside.llm.backend.BackendHealthCoordinator
import com.chatbyyourside.llm.backend.BackendManager
import com.chatbyyourside.llm.backend.BackendPreference
import com.chatbyyourside.llm.backend.BackendSelector
import com.chatbyyourside.llm.backend.BackendType
import com.chatbyyourside.llm.backend.MnnBridge
import com.chatbyyourside.llm.backend.NpuSupportDetector
import com.chatbyyourside.llm.backend.modelConfigFingerprint
import com.chatbyyourside.llm.benchmark.BenchmarkSample
import com.chatbyyourside.llm.benchmark.BenchmarkScenarioResult
import com.chatbyyourside.llm.benchmark.CandidateOverrides
import com.chatbyyourside.llm.benchmark.CertifiedInferenceOptions
import com.chatbyyourside.llm.benchmark.ExperimentalPromotionPolicy
import com.chatbyyourside.llm.benchmark.InferenceBackendQuadrant
import com.chatbyyourside.llm.benchmark.InferenceBenchmarkCase
import com.chatbyyourside.llm.benchmark.InferenceBenchmarkScenario
import com.chatbyyourside.llm.benchmark.InferenceCertificationStore
import com.chatbyyourside.llm.benchmark.LocalInferenceBenchmarkRunner
import com.chatbyyourside.llm.benchmark.PromotionDecision
import com.chatbyyourside.llm.metrics.InferenceTurnRecord
import com.chatbyyourside.llm.profile.DeviceRuntimeFingerprint
import com.chatbyyourside.llm.profile.DowngradeReason
import com.chatbyyourside.llm.profile.InferencePerformanceMode
import com.chatbyyourside.llm.profile.RuntimeVariant
import com.chatbyyourside.llm.template.ThinkingEffect
import com.chatbyyourside.llm.template.ThinkingTemplateCapability
import com.chatbyyourside.llm.template.ThinkingTemplateCapabilityResolver
import com.chatbyyourside.provider.local.ModelPathResolver
import com.chatbyyourside.ui.glass.GlassListRow
import com.chatbyyourside.ui.glass.GlassListSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 推理引擎设置页（独立路由）：性能模式、设备能力、后端选项、推理参数、高级（诊断）旧开关、回退链。
 */
@Composable
fun BackendSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val pref by container.settingsRepository.llmBackend.collectAsState(initial = BackendPreference.AUTO)
    val performanceMode by container.settingsRepository.llmPerformanceMode
        .collectAsState(initial = InferencePerformanceMode.DEFAULT)

    val deviceCap by produceState(initialValue = null as BackendSelector.DeviceCapability?) {
        value = withContext(Dispatchers.IO) { container.backendManager.deviceCapability }
    }
    val mnnCpuReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { container.backendManager.mnnCpuSupported }
    }
    val mnnGpuReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { container.backendManager.mnnGpuSupported }
    }
    val mnnNpuReady by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { container.backendManager.mnnNpuSupported }
    }
    val fallbackChain by produceState(
        initialValue = emptyList<BackendType>(),
        pref, mnnCpuReady, mnnGpuReady, mnnNpuReady,
    ) {
        value = withContext(Dispatchers.IO) { container.backendManager.backendOrder(pref) }
    }
    val activeBackend = container.backendManager.lastUsedBackend

    val threads by container.settingsRepository.llmThreads.collectAsState(initial = AppConfig.LLM.DEFAULT_THREADS)
    val contextLen by container.settingsRepository.llmContextLen.collectAsState(initial = AppConfig.LLM.DEFAULT_CONTEXT_LEN)
    val maxTokens by container.settingsRepository.llmMaxTokens.collectAsState(initial = AppConfig.LLM.DEFAULT_MAX_TOKENS)
    val configChanged by container.settingsRepository.llmConfigChanged.collectAsState(initial = false)

    val context = LocalContext.current
    val activeModelId by container.settingsRepository.activeLocalModelId.collectAsState(initial = null)
    val memoryEstimate by produceState<LlmMemoryEstimator.MemoryEstimate>(
        initialValue = LlmMemoryEstimator.MemoryEstimate.Unavailable,
        activeModelId, contextLen,
    ) {
        value = LlmMemoryEstimator.estimate(context, container.settingsRepository, contextLen)
    }

    var contextInput by remember { mutableStateOf(contextLen.toString()) }
    var contextInputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(contextLen) {
        if (!contextInputFocused) contextInput = contextLen.toString()
    }

    // ===== Task 7：本次生成诊断 / 认证状态 =====
    // 模板能力（Step 5）：按 activeLocalModelId 的模型目录解析（IO；解析器进程内缓存按 mtime 失效）。
    val templateResolver = remember { ThinkingTemplateCapabilityResolver() }
    val templateCapability by produceState<ThinkingTemplateCapability?>(
        initialValue = null,
        activeModelId,
    ) {
        value = withContext(Dispatchers.IO) {
            val id = container.settingsRepository.getActiveLocalModelIdNow() ?: return@withContext null
            val path = ModelPathResolver.getLoadPath(context, id) ?: return@withContext null
            templateResolver.resolve(File(path).parentFile ?: File(path))
        }
    }
    // 认证状态：认证存储快照（基准认证落盘后自动刷新）→ 按当前 device+model+CPU 变体+native 组合
    // 查证（键派生与生产侧一致，Task 6 M-3）。
    val certRecords by container.inferenceCertificationStore.records.collectAsState(initial = emptyMap())
    val currentCert by produceState<CertifiedInferenceOptions?>(
        initialValue = null,
        activeModelId, certRecords,
    ) {
        value = withContext(Dispatchers.IO) {
            val id = container.settingsRepository.getActiveLocalModelIdNow() ?: return@withContext null
            val path = ModelPathResolver.getLoadPath(context, id) ?: return@withContext null
            val runtime = MnnBridge.runtimeInfo ?: return@withContext null
            val key = InferenceCertificationStore.certKey(
                deviceFingerprint = BackendHealthCoordinator.deviceFingerprintOf(),
                modelFingerprint = modelConfigFingerprint(path),
                variant = RuntimeVariant.CPU_OPTIMIZED.name,
                nativeBuildId = runtime.nativeBuildId,
                mnnCommit = runtime.mnnCommit,
            )
            certRecords[key]
        }
    }
    // 最近一次生成记录（跨会话存活于 BackendManager 实例；无记录时展示「暂无生成记录」）。
    // Task 7 review M-5：BackendManager 的记录是 @Volatile 字段（无 State/Flow 源），生成完成只写
    // 字段不会触发重组；produceState 以 generationId 为键 + 1s 轮询重读——本屏存续期间生成结束后
    // ≤1s 内「最近一次生成」自动刷新；回屏/其他重组时键变（新 generationId）立即重读。
    val lastTurn by produceState<InferenceTurnRecord?>(
        initialValue = container.backendManager.lastTurnRecord(),
        key1 = container.backendManager.lastTurnRecord()?.generationId,
    ) {
        while (true) {
            value = container.backendManager.lastTurnRecord()
            delay(1_000)
        }
    }

    // 基准认证入口状态（运行中禁用按钮；完成后在诊断区展示最近一次判定原因）。
    var benchmarkRunning by remember { mutableStateOf(false) }
    var benchmarkOutcome by remember { mutableStateOf<LookaheadCertificationDecision?>(null) }
    // 两个重置动作的确认对话框开关。
    var confirmResetHealth by remember { mutableStateOf(false) }
    var confirmResetCert by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp),
    ) {
        // 顶部：返回 + 标题
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = scheme.onSurface)
            }
            Text("推理引擎设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        }

        if (configChanged) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, scheme.tertiary, RoundedCornerShape(14.dp))
                    .background(scheme.tertiary.copy(alpha = 0.12f))
                    .padding(12.dp),
            ) {
                Text(
                    "推理参数已变更，下次发送消息时将自动重载模型以生效。",
                    color = scheme.tertiary, fontSize = 11.sp,
                )
            }
        }

        // ===== 设备能力 =====
        GlassListSection(title = "设备能力") {
            val cap = deviceCap
            if (cap == null) {
                GlassListRow(title = "探测中…", showDivider = false)
            } else {
                GlassListRow(title = "CPU 核心数", trailing = { ValueText("${cap.cpuCoreCount}") })
                GlassListRow(title = "总内存", trailing = { ValueText("${cap.totalRAMMB} MB") })
                GlassListRow(
                    title = "NPU (Hexagon)",
                    subtitle = if (cap.npuInfo.supported)
                        "支持 · ${cap.npuInfo.chipLevel.displayName} (${cap.npuInfo.socModel})"
                    else "不支持 (${cap.npuInfo.reason})",
                    showDivider = false,
                )
            }
        }

        // ===== 推理性能模式 =====
        GlassListSection(title = "推理性能模式") {
            InferencePerformanceMode.entries.forEachIndexed { idx, mode ->
                val (title, desc) = when (mode) {
                    InferencePerformanceMode.BALANCED -> "综合平衡（推荐）" to "兼顾速度、温度、功耗和稳定性"
                    InferencePerformanceMode.MAXIMUM_SPEED -> "最高速度" to "优先首字和生成速度，仍会在过热、内存不足或后端异常时自动降级"
                }
                BackendOptionRow(
                    title = title,
                    desc = desc,
                    selected = performanceMode == mode,
                    enabled = true,
                    isActive = false,
                    onClick = { scope.launch { container.settingsRepository.setLlmPerformanceMode(mode) } },
                    showDivider = idx == InferencePerformanceMode.entries.size - 1,
                )
            }
        }

        // ===== 后端选项 =====
        GlassListSection(title = "选择推理后端") {
            BackendPreference.entries.forEachIndexed { idx, entry ->
                val enabled = when (entry) {
                    BackendPreference.MNN_GPU -> mnnGpuReady
                    BackendPreference.MNN_NPU -> mnnNpuReady
                    else -> true
                }
                val selected = pref == entry
                val desc = when (entry) {
                    BackendPreference.AUTO -> when {
                        mnnGpuReady -> "自动选择（GPU 优先，回退 CPU）"
                        else -> "自动选择（回退 CPU）"
                    }
                    BackendPreference.MNN_CPU -> "兼容性最好，速度最慢"
                    BackendPreference.MNN_GPU -> if (mnnGpuReady) "MNN OpenCL GPU（.mnn 模型）" else "需 libMNN.so + OpenCL 运行时"
                    BackendPreference.MNN_NPU -> com.chatbyyourside.llm.backend.MnnSupportDetector.QNN_STANDARD_BUILD_UNAVAILABLE
                }
                BackendOptionRow(
                    title = entry.displayName,
                    desc = desc,
                    selected = selected,
                    enabled = enabled,
                    isActive = !selected && entry.name == activeBackend.name &&
                        pref == BackendPreference.AUTO,
                    onClick = {
                        if (enabled) scope.launch {
                            container.settingsRepository.setLlmBackend(entry)
                            container.backendManager.resetSessionFailures()
                        }
                    },
                    showDivider = idx == BackendPreference.entries.size - 1,
                )
            }
        }

        // ===== 推理参数 =====
        GlassListSection(title = "推理参数") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CPU 线程数", color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("$threads", color = scheme.primary, fontSize = 14.sp)
                }
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { v ->
                        val t = v.toInt().coerceIn(1, 8)
                        if (t != threads) scope.launch { container.settingsRepository.setLlmParams(threads = t) }
                    },
                    // Task 7 review I-2：基准运行期间冻结参数，保证基线 vs 候选在同一配置下测量。
                    enabled = !benchmarkRunning,
                    valueRange = 1f..8f,
                    steps = 6,
                )
                Text("实际生效取 min(设定值, 大核数, 温度上限)。超过大核数会跑到小核，反而变慢更耗电。", color = scheme.onSurfaceVariant, fontSize = 10.sp)

                Spacer(Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文长度", color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    BasicTextField(
                        value = contextInput,
                        onValueChange = { contextInput = it.filter { ch -> ch.isDigit() } },
                        enabled = !benchmarkRunning,
                        modifier = Modifier
                            .width(72.dp)
                            .onFocusChanged { state ->
                                contextInputFocused = state.isFocused
                                if (!state.isFocused) {
                                    val parsed = contextInput.toIntOrNull() ?: contextLen
                                    val coerced = coerceContextLen(parsed)
                                    contextInput = coerced.toString()
                                    if (coerced != contextLen) {
                                        scope.launch { container.settingsRepository.setLlmParams(contextLen = coerced) }
                                    }
                                }
                            },
                        textStyle = TextStyle(color = scheme.primary, fontSize = 14.sp, textAlign = TextAlign.End),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(" tokens", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Slider(
                    value = contextLen.toFloat(),
                    onValueChange = { v ->
                        val coerced = coerceContextLen(v.toInt())
                        if (coerced != contextLen) scope.launch { container.settingsRepository.setLlmParams(contextLen = coerced) }
                    },
                    enabled = !benchmarkRunning,
                    valueRange = MIN_CONTEXT_LEN.toFloat()..MAX_CONTEXT_LEN.toFloat(),
                    steps = (MAX_CONTEXT_LEN - MIN_CONTEXT_LEN) / CONTEXT_LEN_STEP - 1,
                )
                Text("越大越占内存；超出模型支持长度会加载失败。改值后下条消息自动重载。", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                val memoryText = when (val est = memoryEstimate) {
                    is LlmMemoryEstimator.MemoryEstimate.Value ->
                        "约 ${LlmMemoryEstimator.formatMemory(est.bytes)} KV cache（按当前模型结构估算）"
                    LlmMemoryEstimator.MemoryEstimate.Unavailable ->
                        if (activeModelId.isNullOrBlank()) "选择并下载模型后可显示内存估算"
                        else "无法读取模型结构，内存估算不可用"
                }
                Text(memoryText, color = scheme.onSurfaceVariant, fontSize = 10.sp)

                Spacer(Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最大生成长度", color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (maxTokens == AppConfig.LLM.MAX_TOKENS_UNLIMITED) "不限" else "$maxTokens",
                        color = scheme.primary, fontSize = 14.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1024, 2048, 4096, AppConfig.LLM.MAX_TOKENS_UNLIMITED).forEach { size ->
                        val selected = maxTokens == size
                        val label = if (size == AppConfig.LLM.MAX_TOKENS_UNLIMITED) "不限" else "$size"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surface.copy(alpha = 0.5f))
                                .border(1.dp, if (selected) scheme.primary else scheme.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable(enabled = !benchmarkRunning) { scope.launch { container.settingsRepository.setLlmParams(maxTokens = size) } }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = if (selected) scheme.primary else scheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
                Text("单次回复的 token 上限（约 1 token ≈ 0.6 汉字）。选「不限」则生成到模型自然结束（EOS）。改后下条消息即生效，无需重载。", color = scheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }

        // ===== 本次生成诊断 =====
        // Task 7 Step 2/5：最近一次生成的诊断摘要（模板能力 / 认证状态 / 思考 / 后端 / 计时）。
        GlassListSection(title = "本次生成诊断") {
            GlassListRow(
                title = "模型",
                subtitle = activeModelId ?: "未选择模型",
                showDivider = true,
            )
            GlassListRow(
                title = "模板思考能力",
                subtitle = templateCapabilityText(templateCapability),
                showDivider = true,
            )
            GlassListRow(
                title = "实验认证",
                subtitle = certificationStatusText(currentCert),
                showDivider = true,
            )
            benchmarkOutcome?.let { outcome ->
                val text = when (outcome) {
                    is LookaheadCertificationDecision.Certified ->
                        "已认证：lookahead 启用（本机已有基准证据）"
                    is LookaheadCertificationDecision.NotCertified ->
                        "未认证：${outcome.reasons.joinToString("；")}"
                }
                GlassListRow(
                    title = "最近一次认证判定",
                    subtitle = text,
                    showDivider = true,
                )
            }
            val rows = diagnosticRows(lastTurn, templateCapability)
            if (rows.isEmpty()) {
                GlassListRow(
                    title = "最近一次生成",
                    subtitle = "暂无生成记录（发送一条本地消息后展示诊断摘要）",
                    showDivider = false,
                )
            } else {
                rows.forEachIndexed { idx, row ->
                    GlassListRow(
                        title = row.label,
                        subtitle = row.value,
                        showDivider = idx == rows.lastIndex,
                    )
                }
            }
        }

        // ===== 高级（诊断）=====
        // legacy 开关：性能模式解析层接管前保留，供高级诊断；不再作为主设置展示。
        val cpuBoost by container.settingsRepository.llmCpuBoost.collectAsState(initial = true)
        val lookahead by container.settingsRepository.llmLookahead.collectAsState(initial = false)
        GlassListSection(title = "高级（诊断）") {
            GlassListRow(
                title = "推理提频（旧开关）",
                subtitle = "性能模式接管前的高级开关；非 root 用系统提频机制推高大核频率，会增加耗电/发热",
                trailing = {
                    Switch(
                        checked = cpuBoost,
                        onCheckedChange = { scope.launch { container.settingsRepository.setLlmCpuBoost(it) } },
                        enabled = !benchmarkRunning,
                    )
                },
                showDivider = true,
            )
            GlassListRow(
                title = "Lookahead 投机解码（旧开关）",
                subtitle = "旧开关（仅 CPU 生效）：需先经「运行基准并认证」取得本机认证后才生效，否则即使打开也不启用",
                trailing = {
                    Switch(
                        checked = lookahead,
                        onCheckedChange = { scope.launch { container.settingsRepository.setLlmLookahead(it) } },
                        enabled = !benchmarkRunning,
                    )
                },
                showDivider = true,
            )
            // Task 7 Step 3：基准触发与认证闭环入口（IO 执行；运行中禁用按钮）。
            GlassListRow(
                title = "运行基准并认证（Lookahead）",
                subtitle = "跑基线 vs lookahead 两轮固定解码对比：收益 ≥10% 且无 TTFT/内存回归才认证启用（约 1–2 分钟，期间请勿退出）",
                onClick = {
                    if (benchmarkRunning) return@GlassListRow
                    // Task 7 review M-1：同步置位防双击竞态——Compose 快照写入对同线程后续读取立即可见，
                    // 同帧第二次点击在此被拒，避免两个基准并发（浪费 1-2 分钟且 last-writer-wins）。
                    benchmarkRunning = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                runLookaheadCertification(context, container, container.benchmarkRunner)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: Exception) {
                                LookaheadCertificationDecision.NotCertified(listOf("基准异常：${e.message}"))
                            }
                        }
                        benchmarkOutcome = result
                        benchmarkRunning = false
                        when (result) {
                            is LookaheadCertificationDecision.Certified ->
                                Toast.makeText(context, "基准通过：lookahead 已认证（打开旧开关后生效）", Toast.LENGTH_SHORT).show()
                            is LookaheadCertificationDecision.NotCertified ->
                                Toast.makeText(
                                    context,
                                    "未认证：${result.reasons.firstOrNull() ?: "未知原因"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                    }
                },
                trailing = {
                    if (benchmarkRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("运行", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                },
                showDivider = true,
            )
            GlassListRow(
                title = "清除后端健康记录",
                subtitle = "删除全部 OpenCL 探测/冷却/黑名单记录，并重置本次会话的后端失败缓存",
                onClick = { confirmResetHealth = true },
                showDivider = true,
            )
            GlassListRow(
                title = "清除实验认证",
                subtitle = "删除全部 lookahead/步进认证；此后相关配置回落未认证默认（不生效）",
                onClick = { confirmResetCert = true },
                showDivider = false,
            )
        }

        // 清除后端健康记录确认对话框（Task 7 Step 3）：重置健康记录 + 会话失败缓存。
        if (confirmResetHealth) {
            AlertDialog(
                onDismissRequest = { confirmResetHealth = false },
                title = { Text("清除后端健康记录") },
                text = { Text("将删除全部后端健康记录（OpenCL 探测/冷却/黑名单），并重置本次会话的后端失败缓存。确定清除？") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmResetHealth = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                container.backendHealthStore.resetAll()
                                container.backendManager.resetSessionFailures()
                            }
                            Toast.makeText(context, "后端健康记录已清除", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("清除") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmResetHealth = false }) { Text("取消") }
                },
            )
        }

        // 清除实验认证确认对话框（Task 7 Step 3）：删除全部认证记录。
        if (confirmResetCert) {
            AlertDialog(
                onDismissRequest = { confirmResetCert = false },
                title = { Text("清除实验认证") },
                text = { Text("将删除全部 lookahead/步进基准认证记录；此后相关配置回落未认证默认（即使打开旧开关也不生效）。确定清除？") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmResetCert = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                container.inferenceCertificationStore.resetAll()
                            }
                            Toast.makeText(context, "实验认证已清除", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("清除") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmResetCert = false }) { Text("取消") }
                },
            )
        }

        // ===== 回退链 =====
        GlassListSection(title = "自动回退顺序") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    fallbackChain.joinToString("  ›  ") { it.displayName },
                    color = scheme.onSurface, fontSize = 13.sp,
                )
                Text("当前激活后端：${activeBackend.displayName}", color = scheme.primary, fontSize = 12.sp)
            }
        }

        // ===== 说明 =====
        GlassListSection(title = "说明") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("• MNN CPU 恒可用（libMNN.so 就绪）；OpenCL GPU 与 QNN NPU 视设备/运行时库就绪而定，不可用时自动回退 CPU。", color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 15.sp)
                Text("• QNN NPU 需骁龙设备 + libQnnHtp.so/Skel；且需解锁 bootloader 或 Root 关 SELinux——锁定量产机 SELinux 拒绝 app 访问 CDSP，会原生崩溃。AUTO 不含 NPU，仅显式选择时尝试。", color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun ValueText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
}

@Composable
private fun BackendOptionRow(
    title: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 单选圆点
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .border(2.dp, if (selected) scheme.primary else scheme.outline, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(10.dp).background(scheme.primary, androidx.compose.foundation.shape.CircleShape))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = when {
                            selected -> scheme.onSurface
                            enabled -> scheme.onSurface
                            else -> scheme.onSurfaceVariant
                        },
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Text("使用中", color = scheme.tertiary, fontSize = 10.sp)
                    }
                }
                Text(desc, color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (!enabled) {
                Text("不可用", color = scheme.error, fontSize = 10.sp)
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 48.dp),
                thickness = 0.6.dp,
                color = scheme.outline.copy(alpha = 0.5f),
            )
        }
    }
}

// ===== 上下文长度参数 =====
private const val MIN_CONTEXT_LEN = 512
private const val MAX_CONTEXT_LEN = 32768
private const val CONTEXT_LEN_STEP = 512

private fun coerceContextLen(value: Int): Int {
    val snapped = ((value + CONTEXT_LEN_STEP / 2) / CONTEXT_LEN_STEP) * CONTEXT_LEN_STEP
    return snapped.coerceIn(MIN_CONTEXT_LEN, MAX_CONTEXT_LEN)
}

// ==========================================================================
// Task 7：诊断摘要与认证闭环的纯逻辑（全部 JVM 可测；UI 只做渲染，不做分支）
// ==========================================================================

/** 最近一次生成的诊断行（label -> value）。 */
data class TurnDiagnosticRow(val label: String, val value: String)

/**
 * 模板能力展示文案（Task 7 Step 5）。
 *
 * **约束**：UNKNOWN / UNSUPPORTED 时绝不声称「思考已关闭」——UNKNOWN 是信息不足（模板不可见/
 * 解析失败），开关可能仍然有效也可能无效；UNSUPPORTED 是「开关必然被忽略」，但也不等于模型不
 * 支持思考（可能无条件思考）。两者文案都只陈述能力事实。
 */
fun templateCapabilityText(cap: ThinkingTemplateCapability?): String = when (cap) {
    ThinkingTemplateCapability.SUPPORTED -> "模板含思考分支（开关可生效）"
    ThinkingTemplateCapability.UNSUPPORTED -> "模板不含思考分支（开关无效）"
    ThinkingTemplateCapability.UNKNOWN -> "模板能力未知：思考开关可能无效"
    null -> "未选择模型/无法解析"
}

/**
 * 思考开关的请求/实际效果合并文案（Task 7 Step 2）。
 *
 * 把 thinkingRequested（用户请求）与 thinkingEffective（实际观察到的效果）按模板能力合并展示：
 * - 请求开启 + 模板不支持 -> 「开关无效」；
 * - 请求开启 + 模板能力未知 -> 「开关可能无效」（不猜测）；
 * - 请求开启 + 观察到思考段（ENABLED）-> 「已生效」；
 * - 未请求但出现完整思考段（THINKING_DISABLE_NOT_EFFECTIVE）-> 「关闭未生效」（Task 2 硬性要求口径）；
 * - 请求关闭但效果 UNKNOWN（截断/失败/空响应生成）-> 「未能确认生效」，不声称「已生效」；
 * - 其余（请求开启未确认 / 请求关闭）-> 如实陈述，不声称「已关闭」之外的事实。
 */
fun thinkingStatusText(
    thinkingRequested: Boolean?,
    thinkingEffective: String?,
    templateCapability: ThinkingTemplateCapability?,
): String = when {
    thinkingRequested == true && templateCapability == ThinkingTemplateCapability.UNSUPPORTED ->
        "请求开启 → 模板不支持（开关无效）"
    thinkingRequested == true && templateCapability == ThinkingTemplateCapability.UNKNOWN ->
        "请求开启 → 模板能力未知（开关可能无效）"
    thinkingRequested == true && thinkingEffective == ThinkingEffect.ENABLED.name ->
        "请求开启 → 已生效"
    thinkingRequested == true -> "请求开启 → 未确认生效"
    thinkingEffective == ThinkingEffect.THINKING_DISABLE_NOT_EFFECTIVE.name ->
        "请求关闭 → 关闭未生效（仍出现思考段）"
    // Task 7 review M-3：效果 UNKNOWN（截断/失败/空响应生成）不是「已生效」的证据，只陈述未能确认。
    thinkingEffective == ThinkingEffect.UNKNOWN.name ->
        "请求关闭 → 未能确认生效"
    else -> "请求关闭 → 已生效"
}

/**
 * 回退/降级原因的可读文案：已知枚举映射中文，未知字符串原样保留（不做猜测，也不崩溃）。
 */
fun downgradeReasonText(reason: String): String = when (reason) {
    BackendManager.EMPTY_GPU_OUTPUT_FALLBACK -> "GPU 空输出回退 CPU"
    DowngradeReason.LOOKAHEAD_UNCERTIFIED.name -> "lookahead 未认证（未启用）"
    DowngradeReason.OPENCL_UNHEALTHY.name -> "OpenCL 健康异常（未入链）"
    DowngradeReason.QNN_UNAVAILABLE_IN_STANDARD_BUILD.name -> "标准构建不含 QNN（解析为 CPU）"
    DowngradeReason.THERMAL.name -> "高温降级"
    DowngradeReason.MEMORY.name -> "内存受限"
    DowngradeReason.BACKEND_UNAVAILABLE.name -> "后端不可用"
    DowngradeReason.UNSUPPORTED_SETTING.name -> "设置不再支持"
    else -> reason
}

/**
 * 当前模型+变体（CPU_OPTIMIZED）的认证状态文案（Task 7 Step 2 认证状态行）。
 * null = 该组合无认证记录：lookahead / 多 token 步进均关闭（resolver 门禁默认）。
 */
fun certificationStatusText(cert: CertifiedInferenceOptions?): String = when {
    cert == null -> "未认证（lookahead / 步进均关闭）"
    cert.lookahead && cert.decodeStepTokens > 1 -> "已认证：lookahead + 多 token 步进 ${cert.decodeStepTokens}"
    cert.lookahead -> "已认证：lookahead"
    cert.decodeStepTokens > 1 -> "已认证：多 token 步进 ${cert.decodeStepTokens}"
    else -> "已认证（逐 token 基线）"
}

/**
 * 由最近一次生成记录 + 模板能力派生诊断行（Task 7 Step 2）。
 *
 * 纯函数（JVM 可测）：无记录返回空列表（UI 显示「暂无生成记录」占位）；行内容覆盖
 * 思考（请求/实际）、实际后端 + 尝试轨迹、回退原因、阶段计时。认证状态行由调用方单独渲染
 * （数据源是认证存储而非生成记录，见 [certificationStatusText]）。
 */
fun diagnosticRows(
    record: InferenceTurnRecord?,
    templateCapability: ThinkingTemplateCapability?,
): List<TurnDiagnosticRow> {
    if (record == null) return emptyList()
    val rows = mutableListOf<TurnDiagnosticRow>()
    rows += TurnDiagnosticRow(
        label = "深度思考",
        value = thinkingStatusText(record.thinkingRequested, record.thinkingEffective, templateCapability),
    )
    val backend = record.backend?.displayName ?: "未知"
    val trace = if (record.attemptTrace.isEmpty()) "" else " · 尝试: ${record.attemptTrace.joinToString(" → ")}"
    rows += TurnDiagnosticRow(label = "实际后端", value = "$backend$trace")
    if (record.downgradeReasons.isNotEmpty()) {
        rows += TurnDiagnosticRow(
            label = "回退/降级",
            value = record.downgradeReasons.joinToString("；") { downgradeReasonText(it) },
        )
    }
    val timings = buildList {
        record.prefillMs?.let { add("prefill ${it}ms") }
        record.decodeMs?.let { add("decode ${it}ms") }
        record.ttftMs?.let { add("TTFT ${it}ms") }
        record.decodeTps?.let { add("${String.format(Locale.US, "%.1f", it)} tok/s") }
        record.kvReuse?.let { add(if (it) "KV 复用" else "KV 未复用") }
    }
    if (timings.isNotEmpty()) rows += TurnDiagnosticRow(label = "阶段计时", value = timings.joinToString(" · "))
    return rows
}

/** 由一轮基准结果构造 [BenchmarkSample]（认证闭环的 evaluate 输入）。 */
fun benchmarkSampleFrom(result: BenchmarkScenarioResult): BenchmarkSample = BenchmarkSample(
    decodeTpsMedian = result.summary.medianDecodeTps ?: 0f,
    ttftMsMedian = result.summary.medianTtftMs,
    peakPssMb = result.summary.peakPssMb?.toFloat(),
    sampleCount = result.recordedSampleCount,
    hotStart = !result.coolRun,
)

/** 认证闭环判定结果（evaluate → toCertifiedOptions 的纯映射；落盘由调用方执行）。 */
sealed interface LookaheadCertificationDecision {
    /** 判定 Promote 且 native 身份齐备：可落盘的认证记录。 */
    data class Certified(val options: CertifiedInferenceOptions) : LookaheadCertificationDecision

    /** 判定 Reject（或无法认证）：展示原因。 */
    data class NotCertified(val reasons: List<String>) : LookaheadCertificationDecision
}

/**
 * Lookahead 认证闭环的纯判定链（Task 7 Step 3）：基线（lookahead=false）vs 候选（lookahead=true）
 * 两轮 FIXED_DECODE 结果 → [ExperimentalPromotionPolicy.evaluate] → Promote 时
 * [InferenceCertificationStore.toCertifiedOptions]（lookaheadEvidence=true、native 身份由调用方
 * 传入）→ [LookaheadCertificationDecision.Certified]；Reject / native 身份缺失 → NotCertified(原因)。
 *
 * 纯函数 JVM 可测（不触 Android）；落盘（save）由调用方（UI 入口）在 IO 线程执行。
 * **键派生一致性约束（Task 6 M-3）**：本函数产出的认证记录键
 * （[InferenceCertificationStore.certKey] = device+model+variant+native 五分量）必须与生产查证
 * 侧（[com.chatbyyourside.provider.local.LocalChatProvider] 按相同五分量查证）一致——调用方构造
 * [case] 时 device/model 指纹必须与生产侧同源（[BackendHealthCoordinator.deviceFingerprintOf] /
 * [modelConfigFingerprint]），本函数不校验指纹真实性。
 */
fun decideLookaheadCertification(
    baseline: BenchmarkScenarioResult,
    candidate: BenchmarkScenarioResult,
    case: InferenceBenchmarkCase,
    nativeBuildId: String,
    mnnCommit: String,
    nowElapsedMs: Long,
): LookaheadCertificationDecision {
    val decision = ExperimentalPromotionPolicy.evaluate(
        benchmarkSampleFrom(baseline),
        benchmarkSampleFrom(candidate),
    )
    if (decision is PromotionDecision.Reject) {
        return LookaheadCertificationDecision.NotCertified(decision.reasons)
    }
    val options = InferenceCertificationStore.toCertifiedOptions(
        case = case,
        decision = decision,
        nativeBuildId = nativeBuildId,
        mnnCommit = mnnCommit,
        // Task 7 范围只做 lookahead 对比实验：候选步长恒 1（步进认证留未来实验）。
        decodeStepTokens = 1,
        // 本基准即 lookahead 开 vs 关对比，产生 lookahead 证据（Task 6 I-1 调用纪律）。
        lookaheadEvidence = true,
        configHash = case.configHash,
        nowElapsedMs = nowElapsedMs,
    )
    return if (options != null) {
        LookaheadCertificationDecision.Certified(options)
    } else {
        LookaheadCertificationDecision.NotCertified(
            listOf("native 构建身份缺失（握手缺席），无法认证"),
        )
    }
}

/**
 * 运行 lookahead 认证闭环（Task 7 Step 3 编排；UI 入口在 IO 线程调用）。
 *
 * 流程：热检查 → 取设置/模型路径/指纹 → 基线（lookahead=false）与候选（lookahead=true）两轮
 * FIXED_DECODE 对比基准（预热 1 轮 + 记录 3 轮 = 策略 MIN_SAMPLES）→ [decideLookaheadCertification]
 * 判定 → Promote 时落盘 [InferenceCertificationStore]。Reject / 前置失败仅返回原因（不落盘）。
 *
 * 指纹口径与生产查证侧一致（Task 6 M-3）：device = deviceFingerprintOf、model = config.json 哈希、
 * 变体由 CPU 象限推导（lookahead 只对 CPU 变体有意义；runner 候选旁路同样强制 CPU 象限）。
 */
private suspend fun runLookaheadCertification(
    context: Context,
    container: AppContainer,
    runner: LocalInferenceBenchmarkRunner,
): LookaheadCertificationDecision {
    // Task 7 review I-1：生成进行中禁止并发跑基准——两轮 generate 与聊天生成并发跑同一
    // BackendManager/共享 native 模型，releaseOthers/ensureAttemptLoaded 会中途换/释放已加载
    // 模型，聊天回复损坏或基准样本无效。
    if (container.backendManager.isGenerating()) {
        return LookaheadCertificationDecision.NotCertified(listOf("当前有生成任务进行中，请稍后再试"))
    }
    if (runner.isThermallyHot()) {
        return LookaheadCertificationDecision.NotCertified(listOf("设备过热，基准未执行（请降温后重试）"))
    }
    val settings = container.settingsRepository
    val snapshot = settings.getLocalInferenceSettingsNow()
    val activeModelId = settings.getActiveLocalModelIdNow()
    val modelPath = if (activeModelId.isNullOrBlank()) null else ModelPathResolver.getLoadPath(context, activeModelId)
    if (activeModelId.isNullOrBlank() || modelPath == null) {
        return LookaheadCertificationDecision.NotCertified(listOf("未选择本地模型或模型文件缺失"))
    }
    // Task 7 review M-2：native 握手缺席快速失败——此前跑完 2×4 轮才在判定链发现身份缺失，
    // 白费 1-2 分钟。文案与判定链 Reject 原因一致。
    val runtime = MnnBridge.runtimeInfo ?: return LookaheadCertificationDecision.NotCertified(
        listOf("native 构建身份缺失（握手缺席），无法认证"),
    )
    // 指纹与认证记录键同源（Task 6 M-3）：device = deviceFingerprintOf，model = config.json 内容哈希。
    val deviceFingerprint = BackendHealthCoordinator.deviceFingerprintOf()
    val modelFingerprint = modelConfigFingerprint(modelPath)
    val configHash = DeviceRuntimeFingerprint.compute(
        buildMap {
            put("threads", snapshot.threads.toString())
            put("contextLen", snapshot.contextLen.toString())
            put("maxTokens", snapshot.maxTokens.toString())
            put("mode", snapshot.performanceMode.storageKey)
            put("deepThinking", snapshot.deepThinking.toString())
        },
    )
    // lookahead 只对 CPU 变体有意义：强制 CPU 象限（与 runner 候选旁路口径一致，见 runner KDoc）。
    val quadrant = if (snapshot.deepThinking) {
        InferenceBackendQuadrant.CPU_THINKING_ON
    } else {
        InferenceBackendQuadrant.CPU_THINKING_OFF
    }
    // 预热 1 轮 + 记录 3 轮（ExperimentalPromotionPolicy.MIN_SAMPLES=3）；两轮对比同指纹。
    val baseline = runner.run(
        scenario = InferenceBenchmarkScenario.FIXED_DECODE,
        configFingerprint = configHash,
        deviceFingerprint = deviceFingerprint,
        warmupRounds = 1,
        recordedRounds = 3,
        candidateOverrides = CandidateOverrides(lookahead = false),
    )
    val candidate = runner.run(
        scenario = InferenceBenchmarkScenario.FIXED_DECODE,
        configFingerprint = configHash,
        deviceFingerprint = deviceFingerprint,
        warmupRounds = 1,
        recordedRounds = 3,
        candidateOverrides = CandidateOverrides(lookahead = true),
    )
    val case = InferenceBenchmarkCase(
        scenario = InferenceBenchmarkScenario.FIXED_DECODE,
        quadrant = quadrant,
        modelFingerprint = modelFingerprint,
        deviceFingerprint = deviceFingerprint,
        configHash = configHash,
    )
    val decision = decideLookaheadCertification(
        baseline = baseline,
        candidate = candidate,
        case = case,
        nativeBuildId = runtime.nativeBuildId,
        mnnCommit = runtime.mnnCommit,
        nowElapsedMs = SystemClock.elapsedRealtime(),
    )
    // Promote 才落盘（toCertifiedOptions 已保证 native 身份齐备）；Reject 仅展示原因。
    (decision as? LookaheadCertificationDecision.Certified)?.let {
        container.inferenceCertificationStore.save(it.options)
    }
    return decision
}
