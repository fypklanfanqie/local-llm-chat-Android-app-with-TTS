# 本地推理：按模型大小开 GPU、内存上限与 GPU prefill 优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AUTO 只在参数量 ≥7B 时用 GPU、≤4B 走 CPU；抬高 App 内存上限并优化驻留使大模型“满血放入”且不被过早回收；在目标真机上用数据驱动 GPU prefill 提速。

**Architecture:** 在既有 `InferenceProfileResolver` 决策链加“按模型大小门禁”（AUTO 且 <7B 跳过 GPU attempt）；`ModelInfo` 增 `paramsB` 作为判定来源；`AndroidManifest` 开 `largeHeap`；`ModelResidencyController` 按注入的内存健康判定分档驻留时长；设置页加内存余量展示与 debug-only 的 CPU vs GPU prefill 基准入口；真机测量后用数据决定 prefill 配置调优，候选 runtime 升级仍走 promotion 门禁（本计划不升级 native bundle）。

**Tech Stack:** Kotlin, Jetpack Compose, Room/DataStore, MNN (libMNN.so), JUnit4, adb/真机（小米 15 / SM8750 / 16GB），Gradle（`JAVA_HOME=/d/ai/az/jbr` JDK21）。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-08-12-local-inference-gpu-by-size-memory-and-prefill-design.md`（已获用户批准）。
- 大模型判定：`paramsB >= 7.0`（`GPU_MIN_PARAMS_B = 7f`）；AUTO 门禁只作用于 AUTO，显式“强制 GPU”始终尊重用户。
- 模型元数据：`ModelInfo.paramsB: Float? = null`（默认 null 保旧 JSON 兼容）；内置清单全部填充。
- 内存：`AndroidManifest.xml` 开 `android:largeHeap="true"`；驻留后台释放按内存健康分档（健康：BALANCED 60s / MAXIMUM_SPEED 120s；`lowMemory`：维持现状 15s/60s）；`onTrimMemory`/热紧急仍立即释放；生成中绝不释放。
- 设置页内存余量为**只读提示**，不阻断加载（现状无准入拒绝，不加）。
- 基准入口**仅 debug 构建可见**（`BuildConfig.DEBUG` 门控），复用 `LocalInferenceBenchmarkRunner`，不做新 instrumentation runner。
- 候选 runtime（`75e53afe`）**不升级** native bundle；仅在真机数据证明后由后续独立计划经 `evaluateRuntime` 门禁处理。
- 执行方式（沿用本项目既定工作流）：**不自动提交**，改动留在工作树由用户统一审查；Kotlin 静态核对（`JAVA_HOME=/d/ai/az/jbr` 仅在 Task 6 真机测量时构建安装）；Python 可运行（如涉及）；Gradle 编译/单测在 Task 6 构建时一并验证，其余交给 CI/JDK17+。
- 代码注释与用户文案保持项目现有中文风格；不得夸大 `largeHeap` 效果（mmap 权重/原生 KV 不受其影响）。
- 目标真机：小米 15（SM8750，16GB，arm64-v8a），已装 Qwen3.5-2B/4B/9B-MNN。

---

## File Structure

### Existing files to modify

- `app/src/main/java/com/chatbyyourside/data/model/LocalModel.kt` — `ModelInfo.paramsB` + `isGpuModel` + `GPU_MIN_PARAMS_B`；填充内置清单。
- `app/src/main/java/com/chatbyyourside/llm/profile/ResolvedInferencePlan.kt` — 新增 `DowngradeReason.SMALL_MODEL_CPU_PREFERRED`。
- `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt` — `resolve(...)` 增 `gpuEligibleByModelSize` 参数；AUTO 小模型跳过 GPU。
- `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt` — 从活动模型查 `isGpuModel` 并传入 resolve；`DefaultLocalInferenceBenchmarkRunner.kt` 同步传参。
- `app/src/main/java/com/chatbyyourside/llm/benchmark/DefaultLocalInferenceBenchmarkRunner.kt` — `buildPlan` 传 `gpuEligibleByModelSize`。
- `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt` — `downgradeReasonText` 新映射；内存余量展示；debug-only 基准入口。
- `app/src/main/AndroidManifest.xml` — `largeHeap="true"`。
- `app/src/main/java/com/chatbyyourside/llm/ModelResidencyController.kt` — 内存健康分档驻留。
- `app/src/main/java/com/chatbyyourside/llm/LlmMemoryEstimator.kt` — 复用现有估算（不新增字段，仅确认口径）。

### Test files (create/modify)

- Create: `app/src/test/java/com/chatbyyourside/data/model/LocalModelTest.kt`
- Modify: `app/src/test/java/com/chatbyyourside/llm/profile/InferenceProfileResolverTest.kt`
- Modify: `app/src/test/java/com/chatbyyourside/ui/settings/BackendDiagnosticsTextTest.kt`
- Modify: `app/src/test/java/com/chatbyyourside/llm/ModelResidencyControllerTest.kt`

---

### Task 1: ModelInfo.paramsB 与 ≥7B 判定

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/data/model/LocalModel.kt`
- Test: `app/src/test/java/com/chatbyyourside/data/model/LocalModelTest.kt`

**Interfaces:**
- Consumes: 现有 `ModelInfo`（@Serializable）。
- Produces: `ModelInfo.paramsB: Float? = null`；`ModelInfo.isGpuModel: Boolean`；`ModelInfo.GPU_MIN_PARAMS_B = 7f`；内置清单全部填充 `paramsB`。

- [ ] **Step 1: 写阈值测试**

创建 `LocalModelTest.kt`：

```kotlin
package com.chatbyyourside.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTest {

    @Test
    fun gpuModelThresholdBoundary() {
        val at = ModelInfo(id = "m", name = "m", size = 1L, paramsB = ModelInfo.GPU_MIN_PARAMS_B)
        val below = at.copy(paramsB = 6.9f)
        val none = at.copy(paramsB = null)
        assertTrue(at.isGpuModel)
        assertFalse(below.isGpuModel)
        assertFalse(none.isGpuModel)
    }

    @Test
    fun allBuiltinModelsHaveParamCount() {
        DEFAULT_MNN_MODELS.forEach { model ->
            assertTrue(
                "模型 ${model.id} 缺 paramsB（应填参数量）",
                model.paramsB != null && model.paramsB!! > 0f,
            )
        }
        // 按 ≥7B 划分：9B/35B/7B/8B 应判定为 GPU 模型，2B/4B/3B/1B/0.8B 不是。
        val byId = DEFAULT_MNN_MODELS.associateBy { it.id }
        assertTrue(byId.getValue("Qwen3.5-9B-MNN").isGpuModel)
        assertTrue(byId.getValue("Qwen3.5-35B-A3B-MNN").isGpuModel)
        assertTrue(byId.getValue("DeepSeek-R1-7B-Qwen-MNN").isGpuModel)
        assertTrue(byId.getValue("DeepSeek-R1-0528-Qwen3-8B-MNN").isGpuModel)
        assertFalse(byId.getValue("Qwen3.5-2B-MNN").isGpuModel)
        assertFalse(byId.getValue("Qwen3.5-4B-MNN").isGpuModel)
        assertFalse(byId.getValue("Qwen3.5-0.8B-MNN").isGpuModel)
        assertFalse(byId.getValue("SmolLM2-360M-Instruct-MNN").isGpuModel)
    }
}
```

- [ ] **Step 2: 静态确认测试为 RED**

本环境不运行 Gradle；静态核对：`ModelInfo` 尚无 `paramsB`/`isGpuModel`/`GPU_MIN_PARAMS_B`，测试引用这些符号即编译失败（RED 成立）。

- [ ] **Step 3: 实现 `paramsB` 与判定**

`LocalModel.kt` 的 `ModelInfo` 增加字段与派生属性：

```kotlin
@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val size: Long,
    val version: String = "mnn",
    val format: ModelFormat = ModelFormat.MNN,
    val repo: String = "",
    val altRepos: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val vendor: String = "",
    val recommended: Boolean = false,
    /** 模型参数量（B，十亿）；null=未知。用于「≥7B 才用 GPU」的门禁判定。 */
    val paramsB: Float? = null,
) {
    /** 是否 NPU 变体（既有）。 */
    val isNpuVariant: Boolean get() = tags.any { it.equals("NPU", ignoreCase = true) }

    /** 是否达到「大模型」门槛（≥[GPU_MIN_PARAMS_B] 才走 GPU；未知一律 false）。 */
    val isGpuModel: Boolean get() = (paramsB ?: 0f) >= GPU_MIN_PARAMS_B

    companion object {
        /** 大模型 GPU 门槛：参数量 ≥7B。 */
        const val GPU_MIN_PARAMS_B = 7f
    }
}
```

填充内置清单：给 `qwen35(...)` 与 `mnn(...)` 工厂加 `paramsB` 参数，并在 `DEFAULT_MNN_MODELS` 逐一传入：

```kotlin
qwen35("Qwen3.5-0.8B-MNN", "Qwen3.5 0.8B", "…", 522.28 * MIB, paramsB = 0.8f),
qwen35("Qwen3.5-2B-MNN", "Qwen3.5 2B", "…", 1.29 * GIB, paramsB = 2.0f, recommended = true),
qwen35("Qwen3.5-4B-MNN", "Qwen3.5 4B", "…", 2.65 * GIB, paramsB = 4.0f, recommended = true),
qwen35("Qwen3.5-9B-MNN", "Qwen3.5 9B", "…", 6.78 * GIB, paramsB = 9.0f),
qwen35("Qwen3.5-35B-A3B-MNN", "Qwen3.5 35B-A3B", "…", 21.23 * GIB, paramsB = 35.0f),
mnn("DeepSeek-R1-1.5B-Qwen-MNN", "DeepSeek", 1.020644886, listOf("Think"), paramsB = 1.5f),
mnn("Qwen3-4B-MNN", "Qwen", 2.713766729, listOf("Think"), paramsB = 4.0f),
mnn("DeepSeek-R1-7B-Qwen-MNN", "DeepSeek", 4.647473365, listOf("Think"), paramsB = 7.0f),
mnn("DeepSeek-R1-0528-Qwen3-8B-MNN", "DeepSeek", 5.507637931, listOf("Think"), paramsB = 8.0f),
mnn("Llama-3.2-1B-Instruct-MNN", "Llama", 1.0, listOf("Chat"), paramsB = 1.0f),
mnn("Llama-3.2-3B-Instruct-MNN", "Llama", 3.0, listOf("Chat"), paramsB = 3.0f),
mnn("gemma-2-2b-it-MNN", "Gemma", 2.0, listOf("Chat"), paramsB = 2.0f),
mnn("SmolLM2-360M-Instruct-MNN", "Smol", 0.36, listOf("Chat"), paramsB = 0.36f),
```

工厂签名相应加 `paramsB` 透传：

```kotlin
private fun qwen35(
    id: String, displayName: String, description: String, sizeBytes: Double,
    recommended: Boolean = false, paramsB: Float,
): ModelInfo = ModelInfo(..., paramsB = paramsB)

private fun mnn(
    modelName: String, vendor: String, sizeGb: Double, tags: List<String>,
    recommended: Boolean = false, paramsB: Float,
): ModelInfo = ModelInfo(..., paramsB = paramsB)
```

- [ ] **Step 4: 静态核对 GREEN**

逐条核对：字段/属性/常量存在；`ModelInfo` 构造点（内置清单 + 其它 `ModelInfo(...)` 调用）因 `paramsB` 带默认值不受影响；`isGpuModel` 边界正确。

- [ ] **Step 5: CI 验证**

```bash
JAVA_HOME=/d/ai/az/jbr ./gradlew testDebugUnitTest --tests '*LocalModelTest'
```

Expected: PASS（本机在 Task 6 构建时一并运行；此前静态核对）。

- [ ] **Step 6: 记录（不提交）**

按工作流不提交；改动留在工作树。

---

### Task 2: Resolver 按模型大小门禁 + Provider 接线 + 诊断文案

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/llm/profile/ResolvedInferencePlan.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/benchmark/DefaultLocalInferenceBenchmarkRunner.kt`
- Modify: `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/profile/InferenceProfileResolverTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/ui/settings/BackendDiagnosticsTextTest.kt`

**Interfaces:**
- Consumes: `ModelInfo.isGpuModel`（Task 1）。
- Produces: `InferenceProfileResolver.resolve(..., gpuEligibleByModelSize: Boolean, ...)`；`DowngradeReason.SMALL_MODEL_CPU_PREFERRED`；`downgradeReasonText` 中文映射。

- [ ] **Step 1: 写解析器门禁测试（先 RED）**

在 `InferenceProfileResolverTest.kt` 增加（沿用该文件既有 `resolve(...)` 调用方式，补上新参数）：

```kotlin
@Test
fun autoSmallModelSkipsGpuAndAddsDowngradeReason() {
    val plan = resolver.resolve(
        mode = InferencePerformanceMode.BALANCED,
        backendPreference = BackendPreference.AUTO,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = 4,
        lookahead = false,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealth = OpenClHealthState.MODEL_OK,
        gpuEligibleByModelSize = false,
    )
    assertTrue(plan.attempts.none { it.backend == BackendType.MNN_GPU })
    assertTrue(plan.downgradeReasons.contains(DowngradeReason.SMALL_MODEL_CPU_PREFERRED))
    assertTrue(plan.attempts.first().variant == RuntimeVariant.CPU_OPTIMIZED)
}

@Test
fun autoLargeModelKeepsGpuFirstWhenHealthy() {
    val plan = resolver.resolve(
        mode = InferencePerformanceMode.BALANCED,
        backendPreference = BackendPreference.AUTO,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = 4,
        lookahead = false,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealth = OpenClHealthState.MODEL_OK,
        gpuEligibleByModelSize = true,
    )
    assertEquals(BackendType.MNN_GPU, plan.attempts.first().backend)
    assertTrue(plan.downgradeReasons.none { it == DowngradeReason.SMALL_MODEL_CPU_PREFERRED })
}

@Test
fun explicitGpuHonoredEvenForSmallModel() {
    val plan = resolver.resolve(
        mode = InferencePerformanceMode.BALANCED,
        backendPreference = BackendPreference.MNN_GPU,
        contextTokens = 4096,
        maxOutputTokens = 2048,
        thermalAdmittedThreads = 4,
        lookahead = false,
        temperature = 0.8f,
        topP = 0.9f,
        repeatPenalty = 1.2f,
        openclHealth = OpenClHealthState.MODEL_OK,
        gpuEligibleByModelSize = false,
    )
    assertTrue(plan.attempts.any { it.backend == BackendType.MNN_GPU })
}
```

- [ ] **Step 2: 静态确认 RED**

`resolve` 尚无 `gpuEligibleByModelSize` 参数 → 测试编译失败（RED）。

- [ ] **Step 3: 加枚举**

`ResolvedInferencePlan.kt` 的 `DowngradeReason` 追加：

```kotlin
SMALL_MODEL_CPU_PREFERRED, // 模型参数量 <7B，AUTO 下 CPU 更优，不启用 GPU（Task 2）
```

- [ ] **Step 4: 改解析器签名与 AUTO 分支**

`InferenceProfileResolver.resolve` 签名在 `openclHealth` 后、`certifiedOptions` 前插入：

```kotlin
openclHealth: OpenClHealthState,
gpuEligibleByModelSize: Boolean,
certifiedOptions: CertifiedInferenceOptions? = null,
```

AUTO 分支改为（`MNN_GPU` 显式分支不动；AUTO 大模型但 OpenCL 不健康时与现状一致地静默走 CPU、不记降级）：

```kotlin
BackendPreference.AUTO -> {
    if (gpuEligibleByModelSize && openclEligible) {
        add(attempt(BackendType.MNN_GPU, RuntimeVariant.OPENCL, 68, contextTokens, lookahead = false, temperature, topP, repeatPenalty))
    } else if (!gpuEligibleByModelSize) {
        downgrades += DowngradeReason.SMALL_MODEL_CPU_PREFERRED
    }
    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_OPTIMIZED, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_COMPATIBILITY, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
}
BackendPreference.MNN_GPU -> {
    if (openclEligible) {
        add(attempt(BackendType.MNN_GPU, RuntimeVariant.OPENCL, 68, contextTokens, lookahead = false, temperature, topP, repeatPenalty))
    } else if (openclHealth != OpenClHealthState.UNKNOWN) {
        downgrades += DowngradeReason.OPENCL_UNHEALTHY
    }
    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_OPTIMIZED, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
    add(attempt(BackendType.MNN_CPU, RuntimeVariant.CPU_COMPATIBILITY, cpu, contextTokens, effectiveLookahead, temperature, topP, repeatPenalty))
}
```

`MNN_CPU`/`MNN_NPU` 分支不变。

- [ ] **Step 5: 改两处调用点**

`LocalChatProvider.kt`（约 400 行 `InferenceProfileResolver(...).resolve(`）在 `openclHealth = openclHealth` 后插入：

```kotlin
gpuEligibleByModelSize = gpuEligibleByModelSize(activeModelId),
```

并在类内新增私有方法（`activeModelId` 在方法内已有作用域；若在 companion 外，直接按需实现）：

```kotlin
/** AUTO 下是否按模型大小允许 GPU：参数量 ≥7B 才允许；未知模型默认允许（不因元数据缺失收紧）。 */
private fun gpuEligibleByModelSize(activeModelId: String?): Boolean {
    if (activeModelId.isNullOrBlank()) return true
    return DEFAULT_MNN_MODELS.firstOrNull { it.id == activeModelId }?.isGpuModel ?: true
}
```

`DefaultLocalInferenceBenchmarkRunner.buildPlan` 的 `resolve(...)`（约 274 行）同样在 `openclHealth` 后传 `gpuEligibleByModelSize = true`（基准按场景显式决定后端偏好，门禁不叠加）。

- [ ] **Step 6: 改诊断文案**

`BackendSettingsScreen.kt` 的 `downgradeReasonText` 增加：

```kotlin
DowngradeReason.SMALL_MODEL_CPU_PREFERRED.name -> "模型较小（<7B），CPU 更优（GPU 仅 ≥7B 启用）"
```

- [ ] **Step 7: 更新文案测试**

`BackendDiagnosticsTextTest.kt` 的 `knownDowngradeReasonsMapToChinese` 增加断言：

```kotlin
assertEquals("模型较小（<7B），CPU 更优（GPU 仅 ≥7B 启用）", downgradeReasonText(DowngradeReason.SMALL_MODEL_CPU_PREFERRED.name))
```

- [ ] **Step 8: 静态核对 GREEN**

逐条核对：所有 `resolve(...)` 调用点（Provider、BenchmarkRunner、测试）都传新参数；枚举/文案/断言一致；无悬挂引用。

- [ ] **Step 9: CI 验证**

```bash
JAVA_HOME=/d/ai/az/jbr ./gradlew testDebugUnitTest --tests '*InferenceProfileResolverTest' --tests '*BackendDiagnosticsTextTest'
```

Expected: PASS（本机 Task 6 构建时一并运行）。

---

### Task 3: largeHeap + 驻留按内存健康分档

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/chatbyyourside/llm/ModelResidencyController.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/ModelResidencyControllerTest.kt`

**Interfaces:**
- Consumes: `ResidencyPolicy.keepAliveMs`（既有）。
- Produces: `ModelResidencyController(..., memoryHealthy: () -> Boolean)`；健康分档驻留。

- [ ] **Step 1: 写驻留状态机测试（先 RED）**

`ModelResidencyControllerTest.kt` 增加（沿用 runTest 虚拟时间模式）：

```kotlin
@Test
fun healthyMemoryUsesLongGraceBeforeRelease() = runTest {
    val released = mutableListOf<Long>()
    val controller = ModelResidencyController(
        releaseAll = { released += currentTime },
        memoryHealthy = { true },
        scope = this,
    )
    controller.onModelChanged(ResidencyPolicy(keepAliveMs = 15_000L))
    controller.onAppForegroundChanged(false)
    advanceTimeBy(59_999L)
    assertEquals(0, released.size)   // 健康：60s 宽限内不释放
    advanceTimeBy(1L)
    assertEquals(1, released.size)
}

@Test
fun lowMemoryFallsBackToConfiguredGrace() = runTest {
    val released = mutableListOf<Long>()
    val controller = ModelResidencyController(
        releaseAll = { released += currentTime },
        memoryHealthy = { false },
        scope = this,
    )
    controller.onModelChanged(ResidencyPolicy(keepAliveMs = 15_000L))
    controller.onAppForegroundChanged(false)
    advanceTimeBy(15_000L)
    assertEquals(1, released.size)   // lowMemory：维持现状 15s
}

@Test
fun trimMemoryStillReleasesImmediatelyWhenHealthy() = runTest {
    val released = mutableListOf<Long>()
    val controller = ModelResidencyController(
        releaseAll = { released += currentTime },
        memoryHealthy = { true },
        scope = this,
    )
    controller.onAppForegroundChanged(false)
    controller.onTrimMemory(immediate = true)
    assertEquals(1, released.size)
}
```

- [ ] **Step 2: 静态确认 RED**

`ModelResidencyController` 尚无 `memoryHealthy` 参数/健康分档 → 测试编译失败（RED）。

- [ ] **Step 3: 实现内存健康分档**

`ModelResidencyController` 构造增加 `memoryHealthy: () -> Boolean = { true }`，并在 `scheduleRelease` 用健康分档宽限：

```kotlin
class ModelResidencyController(
    private val releaseAll: suspend () -> Unit,
    private val balancedKeepAliveMs: Long = DEFAULT_BALANCED_KEEP_ALIVE_MS,
    private val maxSpeedKeepAliveMs: Long = DEFAULT_MAX_SPEED_KEEP_ALIVE_MS,
    private val memoryHealthy: () -> Boolean = { true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    // ...
    /** 健康内存下后台释放宽限（BALANCED/MAXIMUM_SPEED）。 */
    private fun effectiveKeepAliveMs(): Long = if (memoryHealthy()) {
        if (residencyMs <= DEFAULT_BALANCED_KEEP_ALIVE_MS) HEALTHY_BALANCED_KEEP_ALIVE_MS
        else HEALTHY_MAX_SPEED_KEEP_ALIVE_MS
    } else {
        residencyMs
    }

    private fun scheduleRelease() {
        if (!canReside()) return
        cancelRelease()
        graceJob = scope.launch {
            delay(effectiveKeepAliveMs())
            releaseAll()
        }
    }

    companion object {
        const val DEFAULT_BALANCED_KEEP_ALIVE_MS = 15_000L
        const val DEFAULT_MAX_SPEED_KEEP_ALIVE_MS = 60_000L
        /** 内存健康时后台释放宽限：BALANCED 60s / MAXIMUM_SPEED 120s。 */
        const val HEALTHY_BALANCED_KEEP_ALIVE_MS = 60_000L
        const val HEALTHY_MAX_SPEED_KEEP_ALIVE_MS = 120_000L
    }
}
```

接线调用方：`AppContainer` 构造 `ModelResidencyController` 时注入 `memoryHealthy = { ... }`（读 `ActivityManager.MemoryInfo.lowMemory`，或先默认 true；具体内存判定在 Task 4 一起做）。本任务先注入 `{ !memoryInfo.lowMemory }`（`AppContainer` 读取一次 `ActivityManager` 的 `MemoryInfo`），确保“低内存时立即按短宽限”。

- [ ] **Step 4: 改 Manifest**

`AndroidManifest.xml` 的 `<application ...>` 加 `android:largeHeap="true"`。

- [ ] **Step 5: 静态核对 GREEN**

核对：构造参数顺序/默认值不破坏既有调用（`ModelResidencyControllerTest` 旧用例与 `AppContainer` 构造点）；`effectiveKeepAliveMs` 分支正确；Manifest 属性合法。

- [ ] **Step 6: CI 验证**

```bash
JAVA_HOME=/d/ai/az/jbr ./gradlew testDebugUnitTest --tests '*ModelResidencyControllerTest'
```

Expected: PASS。

---

### Task 4: 设置页内存余量展示 + 接线内存健康判定

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt`
- Modify: `app/src/main/java/com/chatbyyourside/AppContainer.kt`（注入 `memoryHealthy` 与余量读取）
- Modify: `app/src/main/java/com/chatbyyourside/llm/LlmMemoryEstimator.kt`（可选：加 `estimateTotalFootprint` 纯函数）
- Test: `app/src/test/java/com/chatbyyourside/ui/settings/BackendDiagnosticsTextTest.kt`

**Interfaces:**
- Consumes: `LlmMemoryEstimator.estimate`、`ModelInfo.size`、`ActivityManager.MemoryInfo`。
- Produces: 纯函数 `memoryHeadroomText(availMemBytes, modelBytes, kvBytes, overheadBytes): String`；`AppContainer` 注入 `memoryHealthy`。

- [ ] **Step 1: 写余量文案纯函数测试（先 RED）**

`BackendDiagnosticsTextTest.kt` 增加：

```kotlin
@Test
fun memoryHeadroomTextShowsSufficientAndTight() {
    val text = memoryHeadroomText(
        availMemBytes = 5_000L * 1024 * 1024,
        modelBytes = 6L * 1024 * 1024 * 1024,
        kvBytes = 512L * 1024 * 1024,
        overheadBytes = 256L * 1024 * 1024,
    )
    assertTrue(text.contains("不足"))   // 5GB 可用 < 6GB 模型 + 512MB KV + 256MB 开销
    assertTrue(text.contains("GB") || text.contains("MB"))
}
```

- [ ] **Step 2: 静态确认 RED**

`memoryHeadroomText` 不存在 → 编译失败（RED）。

- [ ] **Step 3: 实现纯函数**

`BackendSettingsScreen.kt`（诊断纯函数区）新增：

```kotlin
/**
 * 内存余量提示（只读，不阻断）：可用内存 vs 模型权重+KV+固定开销。
 * 纯函数，JVM 可测。
 */
fun memoryHeadroomText(
    availMemBytes: Long,
    modelBytes: Long,
    kvBytes: Long,
    overheadBytes: Long,
): String {
    val needed = modelBytes + kvBytes + overheadBytes
    val diff = availMemBytes - needed
    val prefix = if (diff >= 0L) "剩余约 " else "可能不足 "
    return prefix + LlmMemoryEstimator.formatMemory(diff.coerceAtLeast(0L)) +
        "（预计占用 " + LlmMemoryEstimator.formatMemory(needed) + "）"
}
```

（`LlmMemoryEstimator.formatMemory` 已存在，直接复用。）

- [ ] **Step 4: 设置页展示**

`BackendSettingsScreen` 的“推理参数/上下文长度”区，在现有 `memoryText`（KV 估算）行下方追加一行余量（用 `produceState` 读 `ActivityManager.MemoryInfo.availMem`，模型字节取 `ModelInfo.size`，KV 取 `memoryEstimate`，开销用 `256 * 1024 * 1024` 常量）：

```kotlin
val memoryInfo by produceState<ActivityManager.MemoryInfo?>(initialValue = null) {
    value = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi
    }
}
// ...
memoryInfo?.let { mi ->
    val modelBytes = DEFAULT_MNN_MODELS.firstOrNull { it.id == activeModelId }?.size ?: 0L
    val kv = (memoryEstimate as? LlmMemoryEstimator.MemoryEstimate.Value)?.bytes ?: 0L
    Text(
        memoryHeadroomText(mi.availMem, modelBytes, kv, MEMORY_OVERHEAD_RESERVE_BYTES),
        color = scheme.onSurfaceVariant, fontSize = 10.sp,
    )
}
```

并加常量 `private const val MEMORY_OVERHEAD_RESERVE_BYTES = 256L * 1024 * 1024`。

- [ ] **Step 5: 接线 memoryHealthy（AppContainer）**

`AppContainer` 构造 `ModelResidencyController` 时注入 `memoryHealthy`（读一次 `ActivityManager.MemoryInfo.lowMemory`；`AppContainer` 已有 `context`）：

```kotlin
memoryHealthy = {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    !mi.lowMemory
}
```

- [ ] **Step 6: 静态核对 GREEN**

核对：`memoryHeadroomText` 断言/格式一致；`ActivityManager` import；`AppContainer` 构造点不破坏既有测试；`memoryInfo` 读取不在主线程（IO）。

- [ ] **Step 7: CI 验证**

```bash
JAVA_HOME=/d/ai/az/jbr ./gradlew testDebugUnitTest --tests '*BackendDiagnosticsTextTest'
```

Expected: PASS。

---

### Task 5: debug-only CPU vs GPU prefill 基准入口

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt`
- Modify: `app/src/main/java/com/chatbyyourside/AppContainer.kt`（暴露 `benchmarkRunner`，若已有则跳过）
- Test: `app/src/test/java/com/chatbyyourside/ui/settings/BackendDiagnosticsTextTest.kt`

**Interfaces:**
- Consumes: `LocalInferenceBenchmarkRunner.run(scenario, configFingerprint, deviceFingerprint, ...)`、`BenchmarkScenarioResult`。
- Produces: debug-only 按钮触发 CPU 与 GPU 两轮 `LONG_PREFILL`，结果 Log.i + UI 摘要；纯函数 `prefillComparisonText(cpu: BenchmarkScenarioResult, gpu: BenchmarkScenarioResult): String`。

- [ ] **Step 1: 写对比文案纯函数测试（先 RED）**

```kotlin
@Test
fun prefillComparisonTextShowsBothSides() {
    fun res(tps: Float, ms: Long) = BenchmarkScenarioResult(
        scenario = InferenceBenchmarkScenario.LONG_PREFILL,
        deviceFingerprint = "d", configFingerprint = "c",
        summary = BenchmarkSummary(medianPrefillTps = tps, medianTtftMs = ms),
        recordedSampleCount = 3, warmupSampleCount = 1, coolRun = true,
    )
    val text = prefillComparisonText(res(100f, 800L), res(200f, 500L))
    assertTrue(text.contains("CPU"))
    assertTrue(text.contains("GPU"))
    assertTrue(text.contains("100"))
    assertTrue(text.contains("200"))
}
```

- [ ] **Step 2: 静态确认 RED**

`prefillComparisonText` 不存在 → 编译失败（RED）。

- [ ] **Step 3: 实现纯函数与 debug 入口**

纯函数：

```kotlin
/** CPU vs GPU prefill 对比摘要（debug 基准用）。 */
fun prefillComparisonText(cpu: BenchmarkScenarioResult, gpu: BenchmarkScenarioResult): String =
    "LONG_PREFILL\n" +
        "  CPU: prefill ${cpu.summary.medianPrefillTps} tps / TTFT ${cpu.summary.medianTtftMs}ms\n" +
        "  GPU: prefill ${gpu.summary.medianPrefillTps} tps / TTFT ${gpu.summary.medianTtftMs}ms"
```

debug 入口（`BackendSettingsScreen` 的“高级（诊断）”区，`BuildConfig.DEBUG` 门控）：

```kotlin
if (BuildConfig.DEBUG) {
    GlassListRow(
        title = "Debug：CPU vs GPU prefill 基准",
        subtitle = "对当前模型跑 LONG_PREFILL（各 1 预热+3 记录），结果写 logcat 与下方摘要（约 1-2 分钟）",
        onClick = {
            if (prefillBenchRunning) return@GlassListRow
            prefillBenchRunning = true
            scope.launch {
                val summary = withContext(Dispatchers.IO) {
                    runPrefillCpuVsGpu(context, container, container.benchmarkRunner)
                }
                prefillBenchmarkOutcome = summary
                prefillBenchRunning = false
                Log.i("PrefillBench", summary)
            }
        },
        trailing = { if (prefillBenchRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("运行", ...) },
    )
    prefillBenchmarkOutcome?.let { Text(it, color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(16.dp)) }
}
```

`runPrefillCpuVsGpu`（设置页文件内 suspend 函数，参照 `runLookaheadCertification` 模式）：

```kotlin
private suspend fun runPrefillCpuVsGpu(
    context: Context,
    container: AppContainer,
    runner: LocalInferenceBenchmarkRunner,
): String {
    val settings = container.settingsRepository
    val modelId = settings.getActiveLocalModelIdNow() ?: return "未选择本地模型"
    val fp = BackendHealthCoordinator.deviceFingerprintOf()
    val modelPath = ModelPathResolver.getLoadPath(context, modelId) ?: return "模型文件缺失"
    // CPU：切到 MNN_CPU 快照跑一轮；GPU：切到 MNN_GPU（健康通过才有效）
    val cpu = runWithPreference(container, runner, settings, modelPath, fp, BackendPreference.MNN_CPU)
    val gpu = runWithPreference(container, runner, settings, modelPath, fp, BackendPreference.MNN_GPU)
    val cpuRes = cpu ?: return "CPU 基准失败（日志见上）"
    val gpuRes = gpu ?: return "GPU 基准失败（可能 OpenCL 不健康；日志见上）"
    return prefillComparisonText(cpuRes, gpuRes)
}

private suspend fun runWithPreference(
    container: AppContainer,
    runner: LocalInferenceBenchmarkRunner,
    settings: SettingsRepository,
    modelPath: String,
    fp: String,
    pref: BackendPreference,
): BenchmarkScenarioResult? {
    val snap = settings.getLocalInferenceSettingsNow()
    // 临时把后端偏好写入设置并恢复；基准内部 buildPlan 按快照解析。
    settings.setLlmBackend(pref)
    return try {
        runner.run(
            scenario = InferenceBenchmarkScenario.LONG_PREFILL,
            configFingerprint = modelPath,
            deviceFingerprint = fp,
            warmupRounds = 1,
            recordedRounds = 3,
        )
    } finally {
        settings.setLlmBackend(snap.backend)
    }
}
```

> 说明：`settings.setLlmBackend` 为 DataStore 写，基准前后恢复快照，避免污染用户选择。

- [ ] **Step 4: 静态核对 GREEN**

核对：`BuildConfig.DEBUG` import；`container.benchmarkRunner` 存在（`AppContainer` 已暴露，参照既有 `runLookaheadCertification` 调用）；`BackendHealthCoordinator.deviceFingerprintOf()` 可复用；`settings.setLlmBackend` 存在；纯函数断言与实现一致。

- [ ] **Step 5: CI 验证**

```bash
JAVA_HOME=/d/ai/az/jbr ./gradlew testDebugUnitTest --tests '*BackendDiagnosticsTextTest'
```

Expected: PASS（`prefillComparisonText` 用例）。

---

### Task 6: 真机构建、安装与 prefill 测量（本设备）

**Files:**
- 无源码改动；记录测量结果到 `.superpowers/sdd/prefill-measurement.md`。

**Interfaces:**
- Consumes: Task 1-5 的代码；`LocalInferenceBenchmarkRunner.LONG_PREFILL`；`InferenceTurnRecord` 的 `prefillMs/prefillTps/TTFT`。
- Produces: 9B 模型 CPU vs GPU prefill 对照数据；决策记录（调参或维持）。

- [ ] **Step 1: 构建当前工作树**

```bash
cd "D:/ai/cc Programm/本地ai聊天大众版"
JAVA_HOME=/d/ai/az/jbr ./gradlew assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL（若编译失败，先修 Task 1-5 的静态核对疏漏，再重试）。APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 2: 安装到设备**

```bash
ADB="/c/Users/Lfq06/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: 设置活动模型为 9B 并进入设置页**

```bash
"$ADB" shell am force-stop com.chatbyyourside
"$ADB" shell monkey -p com.chatbyyourside 1   # 启动 App
```

在设置页（推理引擎设置）手动把活动模型切到 Qwen3.5-9B-MNN（若需，用 `uiautomator dump` 定位坐标；或用现有模型切换界面），再点“Debug：CPU vs GPU prefill 基准”。

- [ ] **Step 4: 抓取结果**

```bash
"$ADB" logcat -d -s PrefillBench:* MnnBackend:* | tail -60
```

Expected: `PrefillBench` 行含 CPU/GPU 的 prefill tps 与 TTFT；`MnnBackend` 的“生成结束”行含 `prefill_us`/`reuse_kv`。

- [ ] **Step 5: 记录数据与决策**

把结果写入 `.superpowers/sdd/prefill-measurement.md`（模型/设备/CPU vs GPU 的 prefill tps/TTFT/实际后端/attemptTrace）。决策分支：
- 若 GPU prefill 不慢于 CPU：结论“GPU prefill 无需调参”，记录并结束本计划对 C 部分的工作。
- 若 GPU prefill 显著慢（如 CPU 快 ≥30%）：下一步评估 `attention_mode` 候选（经 `CandidateOverrides`，在 debug 入口扩一个候选档）再测一轮；**不**在本计划升级 runtime（候选 `75e53afe` 由后续独立计划经 `evaluateRuntime` 门禁处理）。
- 记录到 spec 的“未验证声明”归为已验证或未验证。

- [ ] **Step 6: 汇报**

把测量结论作为本任务交付（不进代码提交；供用户与后续计划使用）。

---

## Final End-to-End Acceptance

- [ ] AUTO 下 2B/4B 模型 `attempts` 无 GPU、诊断显示 `SMALL_MODEL_CPU_PREFERRED`；9B/35B/7B/8B 保持 GPU 优先（健康时）。
- [ ] 显式“强制 GPU”对小模型仍可（attempts 含 GPU）。
- [ ] `AndroidManifest` 含 `android:largeHeap="true"`。
- [ ] 后台驻留：内存健康时 BALANCED 60s / MAXIMUM_SPEED 120s 才释放；`lowMemory` 维持 15s/60s；trim/热紧急立即释放；生成中不释放。
- [ ] 设置页显示内存余量（只读，不阻断）。
- [ ] debug 构建有 CPU vs GPU prefill 基准入口，且仅在 `BuildConfig.DEBUG` 下可见。
- [ ] 真机（小米 15/SM8750）9B 模型有 CPU vs GPU prefill 对照数据；有结论（调参或维持），写入 `.superpowers/sdd/prefill-measurement.md`。
- [ ] native bundle 未升级；候选 runtime 未经 promotion 门禁不进。
- [ ] 全部既有 JVM 测试不红；新增测试经 `JAVA_HOME=/d/ai/az/jbr ./gradlew testDebugUnitTest`（或 CI）通过。

## Verification Summary

本机：`JAVA_HOME=/d/ai/az/jbr` 用于 Task 6 构建/安装；Task 1-5 以静态核对为主，CI 单测命令如上。adb 路径 `C:/Users/Lfq06/AppData/Local/Android/Sdk/platform-tools/adb.exe`。设备为小米 15（SM8750/16GB），已装 2B/4B/9B 模型。
