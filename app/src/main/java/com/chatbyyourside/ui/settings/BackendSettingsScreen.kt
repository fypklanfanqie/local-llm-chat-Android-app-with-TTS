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
import com.chatbyyourside.AppContainer
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.llm.LlmMemoryEstimator
import com.chatbyyourside.llm.backend.BackendPreference
import com.chatbyyourside.llm.backend.BackendSelector
import com.chatbyyourside.llm.backend.BackendType
import com.chatbyyourside.llm.backend.NpuSupportDetector
import com.chatbyyourside.llm.profile.InferencePerformanceMode
import com.chatbyyourside.ui.glass.GlassListRow
import com.chatbyyourside.ui.glass.GlassListSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    BackendPreference.MNN_NPU -> if (mnnNpuReady)
                        "MNN QNN NPU（需解锁/Root 关 SELinux，否则会崩）"
                    else "不可用：需骁龙 + libQnnHtp.so + 解锁/Root（SELinux 限制 CDSP）"
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
                                .clickable { scope.launch { container.settingsRepository.setLlmParams(maxTokens = size) } }
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
                    )
                },
                showDivider = true,
            )
            GlassListRow(
                title = "Lookahead 投机解码（旧开关）",
                subtitle = "性能模式接管前的高级开关；仅 CPU 后端生效，重复/代码类文本 1.5–3×，首轮无历史时反而拖慢",
                trailing = {
                    Switch(
                        checked = lookahead,
                        onCheckedChange = { scope.launch { container.settingsRepository.setLlmLookahead(it) } },
                    )
                },
                showDivider = false,
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
