# 本地思考语义与 MNN Runtime 对齐实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 取消本地推理模型的思考专属硬截断和二次补答，让思考与正文在一次 MNN 生成中共享用户设置的总输出上限；同时用可复现的上游差异、正确性测试和真机基准决定是否升级钉定 MNN runtime 及启用 GPU 优化。

**Architecture:** 保留现有 `LocalChatProvider → BackendManager → MnnBackend → MnnBridge → mnn_jni.cpp` 链、思考开关的 Jinja `enable_thinking` 语义、OpenCL 健康准入和 CPU 回退。Provider 改为单阶段生成：档位仅生成软收束提示与诊断元数据，不再拥有 token 硬上限；整轮唯一硬边界来自 `ResolvedInferencePlan.maxOutputTokens`/用户「最大生成长度」、安全 watchdog、用户停止和热停止。Runtime 升级与 GPU 配置不直接追随 master，而是先记录 pinned→candidate 源码差异，再在同设备/模型/配置下通过正确性和性能门禁后晋级。

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Coroutines, DataStore, kotlinx.serialization, Android JNI/C++17, Alibaba MNN, OpenCL, JUnit4, Android instrumentation, Python native bundle verifier.

## Global Constraints

- 当前工作树已有大量未提交修改；所有实现必须在现有状态上增量编辑，不得 reset、checkout、覆盖或删除用户修改。
- 本机 JDK 8 不满足项目 Java 17+ 要求：本会话不运行 Gradle，不得声称 JVM/Android 测试已通过；Gradle 验证交给 JDK 17+ CI/设备。
- 深度思考开关只控制模板 Jinja `enable_thinking`；思考档位只影响软提示和诊断，不再修改 `maxTokens`、不再触发第二次生成。
- 思考与正文共享用户「最大生成长度」总上限；达到该上限可以自然得到 `MAX_TOKENS`，但不得注入合成 `</think>` 或用第二轮文本冒充同一轮正文。
- 用户停止、热停止、watchdog、剧本策略截断和 EOS 语义保持不变；取消粒度仍以现有 native 逐 token 检查为准。
- 不把展示用 `<think>` 装饰写回 `modelContent`；原始输出仍作为 KV/prompt-cache 同步真源。
- 保留 `BackendManager.generationMutex`、`MnnBackend.mnnMutex`、单模型驻留、首个可见 delta 后禁止透明换后端和 OpenCL→CPU 安全回退。
- OpenCL 只有 `PROBE_OK/MODEL_OK` 才能入链；不得把库可加载或上游声称支持当作真机可用证据。
- 当前 native 单一事实源保持 `MNN_COMMIT=af0142bcc7b76b7a5128373e285683dc04f55f69`、NDK `26.1.10909125`，除非 Task 4 的候选 runtime 通过门禁并在一个原子变更中同步更新所有来源。
- 2026-08-11 上游基线 `75e53afe568f7b6fabb1adc34894fe9f331d52f8` 只作为候选研究点，不作为自动升级目标；代理地址仅可通过进程环境 `HTTP_PROXY`/`HTTPS_PROXY=http://127.0.0.1:7897` 使用，禁止写入项目。
- 标准 APK 继续排除 QNN；任何 runtime 升级都必须保留 arm64-v8a、API 24+、16 KiB `PT_LOAD p_align >= 0x4000`、manifest SHA/build ID 和单一 `libc++_shared.so` 校验。
- 代码注释与用户文案保持项目现有中文风格；避免新增与行为不符的“精确秒数”“原生思考预算”“强制短思考”承诺。
- 不自动 commit；每个 commit 步骤仅在用户明确授权后执行。

---

## File Structure

### Existing files to modify

- `app/src/main/java/com/chatbyyourside/llm/thinking/LocalThinkingPolicyResolver.kt` — 移除思考 token 硬上限字段，只产出软提示计划。
- `app/src/main/java/com/chatbyyourside/llm/thinking/ThinkingPolicyTelemetry.kt` — 记录单阶段/共享总预算语义，兼容旧 JSON。
- `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt` — 把两阶段硬截断改为一次 `BackendManager.generate`。
- `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt` — 删除“思考硬上限”诊断，明确共享总上限。
- `app/src/test/java/com/chatbyyourside/llm/thinking/LocalThinkingPolicyResolverTest.kt` — 固定档位仅改软提示的契约。
- `app/src/test/java/com/chatbyyourside/llm/metrics/InferenceTelemetryTest.kt` — 固定新 telemetry 的序列化/旧记录兼容。
- `app/src/test/java/com/chatbyyourside/ui/settings/BackendDiagnosticsTextTest.kt` — 固定设置页/诊断文案。
- `app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt` — 增加可测试的单次生成请求 seam，不改变现有生产回退链。
- `app/src/androidTest/java/com/chatbyyourside/llm/backend/MnnRuntimeIntegrationTest.kt` — 真 MNN 单阶段思考/上限验证。
- `app/src/main/java/com/chatbyyourside/llm/benchmark/LocalInferenceBenchmarkRunner.kt` — 给 runtime 候选结果增加明确 runtime identity/候选标识。
- `app/src/main/java/com/chatbyyourside/llm/benchmark/ExperimentalPromotionPolicy.kt` — 对 runtime/GPU 候选增加正确性、OpenCL 路径和 identity 门禁。
- `app/src/main/java/com/chatbyyourside/llm/benchmark/DefaultLocalInferenceBenchmarkRunner.kt` — 使用真正按场景区分的 fixture，并记录实际后端分布。
- `app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.kt` — 只在候选 runtime 晋级时更新 commit/capability 契约。
- `app/src/main/cpp/mnn_jni.cpp` — 只在候选 runtime 晋级时同步 runtime capability；保持 app 自有 streaming/summary ABI。
- `scripts/native/build_mnn_android.sh` — 参数化候选 commit 输出，不直接覆盖当前 bundle。
- `scripts/native/verify_native_bundle.py` — 验证候选 bundle identity/16 KiB/依赖闭包。
- `app/src/main/jniLibs/native-manifest.json` — 仅 Task 4 晋级时原子更新。
- `docs/mnn-device-matrix.md` — 增加 runtime candidate 与 GPU 优化验收记录格式。

### Focused files to create

- `app/src/main/java/com/chatbyyourside/provider/local/LocalGenerationRequest.kt` — 单阶段请求值对象与 runner 接口，隔离 Provider 编排和 Android/native 实现。
- `app/src/test/java/com/chatbyyourside/provider/local/LocalGenerationRequestTest.kt` — 证明每条消息恰好一次生成且共享总上限。
- `docs/mnn-upstream-runtime-delta.md` — pinned commit 与候选 commit 的可复现差异清单。
- `scripts/native/compare_mnn_runtime.py` — 生成/校验 runtime feature delta 报告。
- `scripts/native/test_compare_mnn_runtime.py` — 差异脚本单测。

---

### Task 1: 把思考档位收敛为纯软提示策略

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/llm/thinking/LocalThinkingPolicyResolver.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/thinking/ThinkingPolicyTelemetry.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/thinking/LocalThinkingPolicyResolverTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/metrics/InferenceTelemetryTest.kt`

**Interfaces:**
- Consumes: `LocalThinkingLevel`, `QuestionComplexity`, `NativeThinkingBudgetCapability`（仅诊断证据；生产仍为 `UNVERIFIED`）。
- Produces: `LocalThinkingPlan(requestedLevel, effectiveLevel, complexity, controlMode, targetMinMs, targetMaxMs, checkpointBudget, systemInstruction)`；不含任何 token cap。
- Produces: `ThinkingPolicyTelemetry(requestedLevel, effectiveLevel, complexity, controlMode, targetMinMs, targetMaxMs, checkpointBudget, generationMode, thinkingCapTokens, nativeBudgetCapability)`；新记录固定 `generationMode="SINGLE_PASS_SHARED_LIMIT"`、`thinkingCapTokens=0`。`thinkingCapTokens` 仅作为旧 JSON 兼容字段保留，后续可在遥测 schema 版本升级时删除。

- [ ] **Step 1: 先把 resolver 测试改成“档位不产生硬预算”**

在 `LocalThinkingPolicyResolverTest.kt` 中删除以下旧断言：

```kotlin
assertTrue(short.thinkingCapTokens > 0)
assertTrue(short.thinkingCapTokens < medium.thinkingCapTokens)
assertTrue(medium.thinkingCapTokens < long.thinkingCapTokens)
```

改为：

```kotlin
@Test
fun allLevelsOnlyChangeSoftGuidance() {
    val plans = LocalThinkingLevel.entries.map { level ->
        resolver.resolve(true, level, standardQuestion, nativeBudgetAvailable = false)!!
    }

    assertEquals(4, plans.map { it.systemInstruction }.distinct().size)
    plans.forEach { plan ->
        assertEquals(ThinkingControlMode.PROMPT_FALLBACK, plan.controlMode)
        assertTrue(plan.systemInstruction.contains("不改变最终回答"))
        assertFalse(plan.systemInstruction.contains("token"))
        assertFalse(plan.systemInstruction.contains("硬上限"))
        assertFalse(plan.systemInstruction.contains("第二阶段"))
    }
}
```

同时保留 AUTO 复杂度路由、手动档 distinct、提示不改变最终答案的既有测试。

- [ ] **Step 2: 运行目标测试，确认旧实现为 RED**

CI/JDK 17+ 运行：

```bash
./gradlew testDebugUnitTest --tests '*LocalThinkingPolicyResolverTest'
```

Expected: 编译或断言失败，因为 `LocalThinkingPlan` 仍暴露 `thinkingCapTokens`，且实现仍生成 192/384/768。

本机仅做静态检查，不运行该命令。

- [ ] **Step 3: 删除 resolver 的硬上限模型**

将计划与 profile 改为：

```kotlin
data class LocalThinkingPlan(
    val requestedLevel: LocalThinkingLevel,
    val effectiveLevel: LocalThinkingLevel,
    val complexity: QuestionComplexity?,
    val controlMode: ThinkingControlMode,
    val targetMinMs: Long,
    val targetMaxMs: Long,
    val checkpointBudget: Int,
    val systemInstruction: String,
)

private data class Profile(
    val targetMinMs: Long,
    val targetMaxMs: Long,
    val checkpointBudget: Int,
)
```

`targetProfile()` 继续保留软目标时间与核验点差异，但删除 `THINKING_CAP_SHORT/MEDIUM/LONG` 常量和全部“两阶段硬上限”注释。`resolve()` 不再把 `thinkingCapTokens` 写入计划。

`ThinkingControlMode.NATIVE_BUDGET` 保留为未来经过真实 runtime adapter + fingerprint certification 后才可用的枚举；当前 `NativeThinkingBudgetCapabilityResolver` 仍恒 `UNVERIFIED`，不得把用户设置的总 `maxTokens` 当成“原生思考预算”。

- [ ] **Step 4: 让 telemetry 明确记录单阶段共享上限**

在 `ThinkingPolicyTelemetry.kt` 增加兼容字段：

```kotlin
@Serializable
data class ThinkingPolicyTelemetry(
    val requestedLevel: String,
    val effectiveLevel: String,
    val complexity: String?,
    val controlMode: String,
    val targetMinMs: Long,
    val targetMaxMs: Long,
    val checkpointBudget: Int,
    val generationMode: String = SINGLE_PASS_SHARED_LIMIT,
    @Deprecated("旧两阶段记录兼容；新记录恒为 0")
    val thinkingCapTokens: Int = 0,
    val nativeBudgetCapability: String,
) {
    companion object {
        const val SINGLE_PASS_SHARED_LIMIT = "SINGLE_PASS_SHARED_LIMIT"
    }
}
```

`from()` 不再读取 plan cap，显式写 `generationMode = SINGLE_PASS_SHARED_LIMIT`、`thinkingCapTokens = 0`。保留默认值以解码旧记录；`Json { ignoreUnknownKeys = true }` 语义不变。

- [ ] **Step 5: 更新 telemetry 测试**

将 `InferenceTelemetryTest.kt` 的 policy fixture 改为：

```kotlin
val policy = ThinkingPolicyTelemetry(
    requestedLevel = "auto",
    effectiveLevel = "short",
    complexity = "SIMPLE",
    controlMode = "PROMPT_FALLBACK",
    targetMinMs = 5_000L,
    targetMaxMs = 8_000L,
    checkpointBudget = 2,
    generationMode = ThinkingPolicyTelemetry.SINGLE_PASS_SHARED_LIMIT,
    nativeBudgetCapability = "UNVERIFIED",
)
assertEquals(0, policy.thinkingCapTokens)
```

新增旧 JSON 兼容测试：旧记录含 `"thinkingCapTokens":384` 时仍能解码；新 `from()` 记录恒为 0。

- [ ] **Step 6: CI 验证**

```bash
./gradlew testDebugUnitTest \
  --tests '*LocalThinkingPolicyResolverTest' \
  --tests '*InferenceTelemetryTest'
```

Expected: PASS；AUTO/手动档仍有不同软提示，新记录不存在非零思考 cap。

- [ ] **Step 7: Commit checkpoint（仅用户授权后）**

```bash
git add app/src/main/java/com/chatbyyourside/llm/thinking \
  app/src/test/java/com/chatbyyourside/llm/thinking \
  app/src/test/java/com/chatbyyourside/llm/metrics/InferenceTelemetryTest.kt
git commit -m "refactor: make local thinking levels prompt-only"
```

---

### Task 2: 把 Provider 改为一次生成并共享总输出上限

**Files:**
- Create: `app/src/main/java/com/chatbyyourside/provider/local/LocalGenerationRequest.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt`
- Test: `app/src/test/java/com/chatbyyourside/provider/local/LocalGenerationRequestTest.kt`

**Interfaces:**
- Produces: `LocalGenerationRequest(messages, maxTokens, enableThinking, downgradeReasons, resolvedPlan, thinkingPolicy, outputPolicy, decodeStepTokens)`。
- Produces: `LocalGenerationRunner.generate(request, executionControl, onToken): BackendManager.GenerationResult`。
- Consumes: Task 1 的 prompt-only `LocalThinkingPlan`、现有 `ResolvedInferencePlan.maxOutputTokens`、`GenerationExecutionControl`、`ThinkingOutputClassifier`、`LocalStreamRenderPump`。

- [ ] **Step 1: 先写单阶段请求测试**

创建 `LocalGenerationRequestTest.kt`，用 fake runner 记录调用：

```kotlin
@Test
fun thinkingRequestRunsExactlyOnceWithResolvedTotalLimit() = runTest {
    val calls = mutableListOf<LocalGenerationRequest>()
    val runner = LocalGenerationRunner { request, _, onToken ->
        calls += request
        onToken("分析过程</think>最终答案")
        fakeGenerationResult(CompletionReason.EOS)
    }
    val request = localGenerationRequest(
        maxTokens = 2048,
        enableThinking = true,
    )

    runner.generate(request, control(maxTokens = 2048)) { true }

    assertEquals(1, calls.size)
    assertEquals(2048, calls.single().maxTokens)
    assertTrue(calls.single().enableThinking)
}
```

再覆盖：

```kotlin
@Test fun thinkingOffStillRunsOnceWithSameResolvedLimit()
@Test fun maxTokensIsNeverReplacedByThinkingLevelCap()
@Test fun requestMessagesAreNotAppendedWithDirectAnswerGuide()
```

fake 结果 helper 写在测试文件内，字段使用 `BackendManager.GenerationResult(summary, usedBackend, reloaded, completionReason)` 的当前签名。

- [ ] **Step 2: 运行目标测试，确认接口尚不存在**

CI/JDK 17+：

```bash
./gradlew testDebugUnitTest --tests '*LocalGenerationRequestTest'
```

Expected: FAIL because `LocalGenerationRequest`/`LocalGenerationRunner` do not exist.

- [ ] **Step 3: 创建请求值对象与 runner seam**

`LocalGenerationRequest.kt`：

```kotlin
internal data class LocalGenerationRequest(
    val modelPath: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
    val repeatPenalty: Float,
    val enableThinking: Boolean,
    val downgradeReasons: List<String>,
    val resolvedPlan: ResolvedInferencePlan,
    val thinkingRequested: Boolean,
    val templateCapability: String,
    val thinkingClassifier: ThinkingOutputClassifier,
    val thinkingPolicy: ThinkingPolicyTelemetry?,
    val outputPolicy: GenerationOutputPolicy,
    val decodeStepTokens: Int,
)

internal fun interface LocalGenerationRunner {
    suspend fun generate(
        request: LocalGenerationRequest,
        executionControl: GenerationExecutionControl,
        onToken: (String) -> Boolean,
    ): BackendManager.GenerationResult
}
```

在 `BackendManager.kt` 增加内部 adapter，不改 public `generate` 的现有回退实现：

```kotlin
internal fun asLocalGenerationRunner(): LocalGenerationRunner =
    LocalGenerationRunner { request, control, onToken ->
        generate(
            modelPath = request.modelPath,
            messages = request.messages,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            topP = request.topP,
            repeatPenalty = request.repeatPenalty,
            enableThinking = request.enableThinking,
            onToken = onToken,
            downgradeReasons = request.downgradeReasons,
            thinkingRequested = request.thinkingRequested,
            templateCapability = request.templateCapability,
            thinkingClassifier = request.thinkingClassifier,
            thinkingPolicy = request.thinkingPolicy,
            executionControl = control,
            resolvedPlan = request.resolvedPlan,
            outputPolicy = request.outputPolicy,
            decodeStepTokens = request.decodeStepTokens,
        )
    }
```

生产 Provider 默认使用该 adapter；测试只测编排，不实例化 Android `Context`/真实 native。

- [ ] **Step 4: 删除 Provider 的两阶段控制流**

在 `LocalChatProvider.kt` 删除：

```text
thinkingCapActive
phase1CapTokens
phase1ProducedOutput
phase2Result
BODY_CAP_TOKENS
DIRECT_ANSWER_GUIDE
THINK_CLOSE（仅合成注入用途）
hasFinalBody()
directAnswerMessages()
```

将生成段改为一次调用：

```kotlin
val generationControl = GenerationExecutionControl(
    policy = GenerationSafetyPolicy.forMode(
        performanceMode,
        resolvedPlan.maxOutputTokens,
    ),
    startedElapsedMs = SystemClock.elapsedRealtime(),
)
activeExecutionControl.set(generationControl)
lastControl = generationControl

val request = LocalGenerationRequest(
    modelPath = modelPath,
    messages = modelMessages,
    maxTokens = resolvedPlan.maxOutputTokens,
    temperature = temperature,
    topP = AppConfig.LLM.DEFAULT_TOP_P,
    repeatPenalty = AppConfig.LLM.DEFAULT_REPEAT_PENALTY,
    enableThinking = deepThinking,
    downgradeReasons = downgradeReasons,
    resolvedPlan = resolvedPlan,
    thinkingRequested = deepThinking,
    templateCapability = templateCapability.name,
    thinkingClassifier = thinkingClassifier,
    thinkingPolicy = thinkingPolicyTelemetry,
    outputPolicy = outputPolicy,
    decodeStepTokens = resolvedPlan.decodeStepTokens,
)

val result = localGenerationRunner.generate(request, generationControl) { token ->
    thinkingClassifier.append(token)
    if (!truncated) {
        renderPump.append(token)
        val cutPos = scriptDetector.append(token).cutAbsoluteIndex
        if (cutPos != null) {
            renderPump.truncateTo(cutPos)
            truncated = true
        }
    }
    !truncated
}
```

watchdog 始终只观察同一个 `generationControl`；`finally` 的 `activeExecutionControl.compareAndSet(lastControl, null)`、render pump final flush、原始 `modelText` 和 `LocalChatResult` 逻辑保持。

- [ ] **Step 5: 保持未闭合思考的真实语义**

`renderLocalThink()` 继续允许 `MAX_TOKENS`、用户停止或热停止时未闭合的思考显示为可折叠“思考中/已停止”内容，但禁止修改 `finalRaw`：

```kotlin
val finalRaw = renderPump.snapshot()
val finalText = renderLocalThink(finalRaw, shouldFoldThink)
val modelText = finalRaw
```

不得 append 合成 `</think>`。如果整轮在思考中达到总上限而没有正文，UI 如实展示该结果与 `MAX_TOKENS`，用户可提高「最大生成长度」或再次提问；不能发起隐藏的第二次推理。

- [ ] **Step 6: 静态搜索删除所有旧机制**

```bash
rg -n "thinkingCapActive|phase1CapTokens|phase2Result|BODY_CAP_TOKENS|DIRECT_ANSWER_GUIDE|directAnswerMessages|hasFinalBody|两阶段硬上限|思考硬上限" \
  app/src/main/java/com/chatbyyourside/provider/local \
  app/src/main/java/com/chatbyyourside/llm/thinking
```

Expected: no matches；允许测试/迁移说明中出现“旧两阶段”字样，但生产代码不得存在旧 helper/常量。

- [ ] **Step 7: CI 验证**

```bash
./gradlew testDebugUnitTest \
  --tests '*LocalGenerationRequestTest' \
  --tests '*LocalThinkingPolicyResolverTest' \
  --tests '*InferenceTelemetryTest'
```

Expected: PASS；fake runner 对思考开/关都只收到一次调用，`maxTokens == resolvedPlan.maxOutputTokens`。

- [ ] **Step 8: Commit checkpoint（仅用户授权后）**

```bash
git add app/src/main/java/com/chatbyyourside/provider/local \
  app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt \
  app/src/test/java/com/chatbyyourside/provider/local
git commit -m "fix: generate local thinking responses in one pass"
```

---

### Task 3: 对齐设置文案、诊断与真 MNN 单阶段行为

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt`
- Modify: `app/src/test/java/com/chatbyyourside/ui/settings/BackendDiagnosticsTextTest.kt`
- Modify: `app/src/androidTest/java/com/chatbyyourside/llm/backend/MnnRuntimeIntegrationTest.kt`
- Modify: `docs/mnn-device-matrix.md`

**Interfaces:**
- Consumes: Task 1 的 `ThinkingPolicyTelemetry.generationMode`、现有 `NativeGenerationSummary` 的 `thinkingConfigAccepted/reasoningEndUs/firstBodyDeltaUs/completionReason/generatedTokens`。
- Produces: 诊断行“单次生成 · 思考与正文共享最大生成长度”；不再显示思考 cap。
- Produces: 真机证据：思考开/关各一次 JNI generation、总 token 上限、正文边界和停止语义。

- [ ] **Step 1: 先改诊断文案测试**

`BackendDiagnosticsTextTest.kt` 把旧断言：

```kotlin
assertTrue(target.value.contains("思考硬上限 384 tokens"))
```

替换为：

```kotlin
assertTrue(target.value.contains("提示策略"))
assertTrue(target.value.contains("单次生成"))
assertTrue(target.value.contains("共享最大生成长度"))
assertTrue(!target.value.contains("硬上限"))
assertTrue(!target.value.contains("tokens"))
```

把 AUTO 文案测试从精确 `5–15 秒` 承诺改为策略描述：

```kotlin
assertTrue(thinkingLevelDesc(LocalThinkingLevel.AUTO).contains("按问题复杂度"))
assertTrue(!thinkingLevelDesc(LocalThinkingLevel.AUTO).contains("强制"))
```

- [ ] **Step 2: 运行文案测试，确认旧实现为 RED**

```bash
./gradlew testDebugUnitTest --tests '*BackendDiagnosticsTextTest'
```

Expected: FAIL because current diagnostic still renders `思考硬上限 N tokens`.

- [ ] **Step 3: 更新设置页与诊断纯函数**

建议文案：

```kotlin
fun thinkingLevelDesc(level: LocalThinkingLevel): String = when (level) {
    LocalThinkingLevel.AUTO -> "按问题复杂度选择思考深度，完成后自然给出答案"
    LocalThinkingLevel.SHORT -> "只做必要核验，尽快收束到答案"
    LocalThinkingLevel.MEDIUM -> "平衡分析深度与响应速度"
    LocalThinkingLevel.LONG -> "覆盖更多方案、边界与自检"
}
```

`thinkingPolicyRows()` 删除 `capText`，第二行改为：

```kotlin
TurnDiagnosticRow(
    label = "思考策略",
    value = "约 ${policy.targetMinMs / 1000}–${policy.targetMaxMs / 1000} 秒软目标 · " +
        "${policy.checkpointBudget} 个核验点 · $control · 单次生成，共享最大生成长度",
)
```

设置页「最大生成长度」说明补充：

```text
单次回复的总 token 上限；开启深度思考时，思考与最终答案共同使用该上限。
```

不要声称 EOS 一定能在“不限”前出现；保留现有模型自然结束说明。

- [ ] **Step 4: 增加真 MNN 单阶段思考集成测试**

在 `MnnRuntimeIntegrationTest.kt` 增加 helper：

```kotlin
private fun generateThinking(
    backend: MnnBackend,
    maxTokens: Int,
    enableThinking: Boolean,
): Pair<String, NativeGenerationSummary> {
    val raw = StringBuilder()
    val summary = runBlocking {
        backend.generateStreamMessages(
            messages = messages(),
            maxTokens = maxTokens,
            temperature = 0.8f,
            topP = 0.9f,
            repeatPenalty = 1.2f,
            enableThinking = enableThinking,
            onToken = { delta -> raw.append(delta); true },
            batchMaxBytes = 256,
            batchMaxMs = 16,
            downgradeReasons = emptyList(),
            executionControl = null,
            powerPolicy = PowerPolicy.DEFAULT,
            requestedMode = null,
            effectiveMode = null,
            loadConfigHash = null,
            attemptTrace = emptyList(),
            coldLoadMs = null,
            warmLoadMs = null,
            decodeStepTokens = 1,
            thinkingRequested = enableThinking,
            templateCapability = null,
            thinkingClassifier = null,
            thinkingPolicy = null,
        )
    }
    return raw.toString() to requireNotNull(summary)
}
```

它只能调用一次 `generateStreamMessages`，收集原始 delta，返回非空 summary；不能通过 Provider helper 注入第二次调用。

测试：

```kotlin
@Test
fun thinkingAndBodyShareOneMaxTokenLimit() {
    val fx = requireHandle()
    val (raw, summary) = generateThinking(fx.backend, maxTokens = 128, enableThinking = true)
    assertTrue(summary.generatedTokens <= 128)
    assertTrue(summary.completionReason == "EOS" || summary.completionReason == "MAX_TOKENS")
    assertTrue(raw.isNotEmpty() || summary.generatedTokens == 0)
}

@Test
fun thinkingToggleUsesSameGenerationContract() {
    val fx = requireHandle()
    val (_, off) = generateThinking(fx.backend, maxTokens = 128, enableThinking = false)
    val (_, on) = generateThinking(fx.backend, maxTokens = 128, enableThinking = true)
    assertEquals(1, off.decodeStepTokens)
    assertEquals(1, on.decodeStepTokens)
    assertTrue(off.generatedTokens <= 128)
    assertTrue(on.generatedTokens <= 128)
}
```

仅当 fixture 的 `ThinkingTemplateCapabilityResolver` 为 `SUPPORTED` 时再断言 `thinkingConfigAccepted == true`、`reasoningEndUs/firstBodyDeltaUs` 的顺序；模板未知/不支持时用 `assumeTrue` 明确跳过该子断言，不把“模型没有 think 标签”误判成 runtime 失败。

- [ ] **Step 5: 更新设备矩阵的手工验收**

在 `docs/mnn-device-matrix.md` 增加：

```markdown
## 单阶段思考验收
- 同一请求日志只有一个 generationId/一次 model load attempt chain；不得出现“阶段 2”。
- maxTokens=128/512/2048 时 generatedTokens 不超过总上限。
- 思考自然闭合时 `reasoningEndUs <= firstBodyDeltaUs`；未闭合时 completionReason 必须如实为 MAX_TOKENS/USER_CANCEL/THERMAL_STOP。
- 原始 modelContent 不含应用合成的 `</think>`。
- 用户点击停止后不触发第二次直接作答。
```

- [ ] **Step 6: CI/设备验证**

```bash
./gradlew testDebugUnitTest --tests '*BackendDiagnosticsTextTest'
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.chatbyyourside.llm.backend.MnnRuntimeIntegrationTest
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.chatbyyourside.ui.chat.ChatStopGenerationTest
```

Expected: JVM 文案测试 PASS；有思考模型 fixture 的设备验证单阶段共享上限；无 fixture 时以既有明确原因 skip；停止按钮测试保持 PASS。

- [ ] **Step 7: Commit checkpoint（仅用户授权后）**

```bash
git add app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt \
  app/src/test/java/com/chatbyyourside/ui/settings/BackendDiagnosticsTextTest.kt \
  app/src/androidTest/java/com/chatbyyourside/llm/backend/MnnRuntimeIntegrationTest.kt \
  docs/mnn-device-matrix.md
git commit -m "test: verify single-pass local thinking semantics"
```

---

### Task 4: 以可复现差异和基准门禁升级 MNN runtime/GPU 路径

**Files:**
- Create: `scripts/native/compare_mnn_runtime.py`
- Create: `scripts/native/test_compare_mnn_runtime.py`
- Create: `docs/mnn-upstream-runtime-delta.md`
- Modify: `scripts/native/build_mnn_android.sh`
- Modify: `scripts/native/verify_native_bundle.py`
- Modify: `app/src/main/java/com/chatbyyourside/llm/benchmark/LocalInferenceBenchmarkRunner.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/benchmark/ExperimentalPromotionPolicy.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/benchmark/DefaultLocalInferenceBenchmarkRunner.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/benchmark/ExperimentalPromotionPolicyTest.kt`
- Test: `app/src/androidTest/java/com/chatbyyourside/llm/backend/MnnStreamingIntegrationTest.kt`
- Conditionally modify after promotion: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.kt`
- Conditionally modify after promotion: `app/src/main/cpp/mnn_jni.cpp`
- Conditionally modify after promotion: `app/src/main/jniLibs/native-manifest.json`

**Interfaces:**
- Produces: `RuntimeDeltaReport(baseCommit, candidateCommit, changedPaths, requiredFeatures, presentInBase, presentInCandidate)`。
- Produces: candidate bundle under external staging, never directly in `app/src/main/jniLibs` before promotion。
- Produces: benchmark identity containing `mnnCommit`, `nativeBuildId`, model/device/config fingerprint and actual backend counts。
- Consumes: 2026-08-11 upstream `75e53afe568f7b6fabb1adc34894fe9f331d52f8` only as a candidate snapshot；后续候选必须显式传入完整 commit。

- [ ] **Step 1: 为源码差异脚本写测试**

`test_compare_mnn_runtime.py` 使用临时 git repo/fixture，覆盖：

```python
import tempfile
import unittest
from pathlib import Path

from scripts.native.compare_mnn_runtime import compare_features, render_markdown


class RuntimeDeltaTest(unittest.TestCase):
    def test_reports_feature_added_only_in_candidate(self):
        report = compare_features(
            base_files={"source/a.cpp": "CPU path"},
            candidate_files={"source/a.cpp": "CPU path\nLinearAttentionSizeComputer"},
        )
        self.assertFalse(report.features["cpu_linear_attention"].present_in_base)
        self.assertTrue(report.features["cpu_linear_attention"].present_in_candidate)

    def test_reports_feature_already_present_in_base(self):
        files = {"source/shape/ShapeAttention.cpp": "LinearAttentionSizeComputer"}
        report = compare_features(base_files=files, candidate_files=files)
        feature = report.features["cpu_linear_attention"]
        self.assertTrue(feature.present_in_base)
        self.assertTrue(feature.present_in_candidate)

    def test_rejects_unknown_or_unfetched_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "commit"):
                compare_features(
                    repo=Path(directory),
                    base_commit="missing-base",
                    candidate_commit="missing-candidate",
                )

    def test_writes_stable_markdown_order(self):
        report = compare_features(base_files={}, candidate_files={})
        first = render_markdown(report)
        second = render_markdown(report)
        self.assertEqual(first, second)
        self.assertLess(first.index("cpu_linear_attention"), first.index("opencl_topkv2"))
```

测试允许 `compare_features` 接受内存 `base_files/candidate_files` 以便纯单测；CLI 路径使用 `repo/base_commit/candidate_commit` 读取真实 git tree。

特征表至少包含：

```python
FEATURES = {
    "cpu_linear_attention": ["source/shape/ShapeAttention.cpp", "LinearAttentionSizeComputer"],
    "arm82_linear_attention_fp16": ["source/backend/arm82/Arm82Functions.cpp", "LinearAttention fp16 kernels"],
    "opencl_topkv2": ["source/backend/opencl/execution/image/TopKV2Execution.cpp"],
    "opencl_linear_attention": ["source/backend/opencl/execution/buffer/LinearAttentionBufExecution.cpp"],
    "thinking_template_compat": ["apps/Android/MnnLlmChat", "enable_thinking"],
}
```

- [ ] **Step 2: 运行 Python 测试，确认 RED**

```bash
python -m unittest scripts/native/test_compare_mnn_runtime.py -v
```

Expected: FAIL because comparator does not exist.

- [ ] **Step 3: 实现差异报告并记录当前证据**

CLI：

```bash
python scripts/native/compare_mnn_runtime.py \
  --repo /path/to/MNN \
  --base af0142bcc7b76b7a5128373e285683dc04f55f69 \
  --candidate 75e53afe568f7b6fabb1adc34894fe9f331d52f8 \
  --output docs/mnn-upstream-runtime-delta.md
```

报告必须区分：

1. base 已存在的能力（不能重复宣称“升级才获得”）；
2. candidate 新增/修改的路径；
3. App 侧兼容修复与 MNN engine/backend 优化；
4. 尚未通过本项目模型/设备验证的声明。

当前已核对事实写入报告：上游 README 0.8.2.2 声称 CPU LinearAttention、Arm82 fp16、thinking tokenizer/template、OpenCL/Metal TopKV2；但 pinned `af0142bcc7b76b7a5128373e285683dc04f55f69` 已存在 `ShapeAttention.cpp` 的 LinearAttention、Arm82 LinearAttention fp16 字样和 OpenCL TopKV2 路径，因此不能把这些名字本身当成升级理由。当前 base→candidate 的已观察重点包括 OpenCL `LinearAttentionBufExecution`/kernel 修改和新增 `FusedProjBufExecution`；实际收益必须看模型图是否使用相应 op 与真机基准。

- [ ] **Step 4: 参数化 build script，候选构建不覆盖生产 bundle**

给 `build_mnn_android.sh` 增加显式参数/环境变量：

```bash
MNN_COMMIT_OVERRIDE="${MNN_COMMIT_OVERRIDE:-$MNN_COMMIT}"
OUTPUT_DIR="${MNN_OUTPUT_DIR:-$JNI_LIBS_DIR}"
MANIFEST_OUT="${MNN_MANIFEST_OUT:-$MANIFEST}"
```

规则：

- 默认调用仍构建 pinned commit 到现有路径；
- candidate 必须指定独立 ASCII staging/output，例如 `$HOME/mnn-candidates/75e53afe/arm64-v8a`；
- `BUILD_ID` 必须包含实际 commit、NDK、ABI、API、完整 feature flags；
- candidate 绝不写 `MnnBridge.EXPECTED_MNN_COMMIT` 或生产 manifest；
- verifier 必须验证 `manifest.mnnCommit == requested candidate commit`、所有 ELF 16 KiB、无 QNN、依赖闭包完整。

示例：

```bash
MNN_COMMIT_OVERRIDE=75e53afe568f7b6fabb1adc34894fe9f331d52f8 \
MNN_OUTPUT_DIR="$HOME/mnn-candidates/75e53afe/arm64-v8a" \
MNN_MANIFEST_OUT="$HOME/mnn-candidates/75e53afe/native-manifest.json" \
ANDROID_NDK_HOME=/path/to/android-ndk-r26b \
bash scripts/native/build_mnn_android.sh
```

- [ ] **Step 5: 先补 runtime promotion policy 测试**

在 `ExperimentalPromotionPolicyTest.kt` 增加：

```kotlin
@Test fun runtimeCandidateRequiresDifferentNonBlankIdentity()
@Test fun gpuCandidateRequiresActualGpuSamples()
@Test fun candidateFailsWhenCorrectnessOrKvReuseRegresses()
@Test fun candidateFailsWhenDecodeGainIsBelowTenPercent()
@Test fun candidateFailsWhenTtftOrPssRegressesOverThirtyPercent()
```

保留现有 `BenchmarkSample` API，并新增 `RuntimeBenchmarkCandidate`：

```kotlin
data class RuntimeBenchmarkCandidate(
    val sample: BenchmarkSample,
    val mnnCommit: String,
    val nativeBuildId: String,
    val actualBackendCounts: Map<String, Int>,
    val kvReuseRate: Float?,
    val emptyResponseRate: Float,
)
```

GPU 候选要求 `actualBackendCounts[BackendType.MNN_GPU.name] == sample.sampleCount`；任一 CPU fallback 样本不得计入“GPU 更快”的证据。

- [ ] **Step 6: 修正 benchmark 场景 fixture，避免所有场景共用短探针**

`DefaultLocalInferenceBenchmarkRunner` 当前类注释明确所有场景共用同一固定 prompt；这不足以评估 runtime/GPU。将 fixture 变为：

```kotlin
private fun messagesFor(scenario: InferenceBenchmarkScenario): List<ChatMessage> = when (scenario) {
    COLD_LOAD, SHORT_TTFT -> shortPrompt()
    LONG_PREFILL -> longDeterministicPrompt(targetEstimatedTokens = 1024)
    FIXED_DECODE -> fixedDecodePrompt(targetOutputTokens = 256)
    SECOND_TURN_KV_REUSE -> secondTurnMessages()
    EMPTY_RESPONSE_CHECK -> emptyResponseProbe()
}
```

`SECOND_TURN_KV_REUSE` 必须在同一已加载 backend 上先跑第一轮并用精确 assistant raw text 构造第二轮，再记录第二轮；不得只给一段看起来像多轮的静态消息。`FIXED_DECODE` 通过总 maxTokens=256 和固定提示约束输出长度，不用应用层思考 cap。

- [ ] **Step 7: 运行 baseline 与 candidate 的同条件矩阵**

每个 runtime 在每台目标设备上：

```text
CPU_THINKING_OFF: SHORT_TTFT / LONG_PREFILL / FIXED_DECODE / SECOND_TURN_KV_REUSE
CPU_THINKING_ON:  SHORT_TTFT / LONG_PREFILL / FIXED_DECODE / SECOND_TURN_KV_REUSE
GPU_THINKING_OFF: 同上（仅健康准入后）
GPU_THINKING_ON:  同上（仅健康准入后）
```

每格 1 次预热 + 至少 5 次冷态/合格样本；记录 median/P95 TTFT、prefill/decode TPS、PSS、thermal、KV reuse、empty response、completion reason、actualBackendCounts、commit/build ID。设备过热、fallback、fixture 不满足的样本注明原因后丢弃，不能用重试替换失败样本。

- [ ] **Step 8: 仅在门禁通过后原子晋级 runtime**

晋级要求：

- 所有 streaming/UTF-8/EOS/maxTokens/cancel/KV tests 通过；
- 候选 decode TPS 中位数 ≥ baseline 1.10×；
- TTFT/PSS 回退均 ≤30%；
- `SECOND_TURN_KV_REUSE` 无回归；
- GPU 证据全部实际跑在 `MNN_GPU`，无 CPU fallback 混入；
- 至少一台目标设备有明确收益；没有收益的设备继续使用 CPU 或旧 runtime，不能用一个全局平均掩盖；
- candidate bundle verifier 全绿。

通过后在**同一个变更**更新：

```text
scripts/native/build_mnn_android.sh MNN_COMMIT
app/src/main/cpp/CMakeLists.txt CHAT_MNN_COMMIT 默认值
app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.EXPECTED_MNN_COMMIT
app/src/main/jniLibs/native-manifest.json mnnCommit/ndkVersion/files hashes/build IDs
app/src/main/cpp/mnn_jni.cpp runtime capabilities（仅真实编译/测试能力）
docs/mnn-upstream-runtime-delta.md promotion evidence
```

若门禁不通过：保留当前 pinned runtime，归档报告和基准结果；不要改生产 commit 或 manifest。

- [ ] **Step 9: 验证命令**

本机可运行：

```bash
python -m unittest scripts/native/test_compare_mnn_runtime.py -v
python -m unittest scripts/native/test_verify_native_bundle.py -v
git diff --check
```

JDK 17+ CI/设备：

```bash
./gradlew testDebugUnitTest --tests '*ExperimentalPromotionPolicyTest'
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.chatbyyourside.llm.backend.MnnStreamingIntegrationTest
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.chatbyyourside.llm.backend.MnnRuntimeIntegrationTest
```

候选 native：

```bash
python scripts/native/verify_native_bundle.py \
  --dir "$HOME/mnn-candidates/<commit>/arm64-v8a" \
  --manifest "$HOME/mnn-candidates/<commit>/native-manifest.json"
```

- [ ] **Step 10: Commit checkpoint（仅用户授权后）**

差异/基准基础设施可独立提交：

```bash
git add scripts/native/compare_mnn_runtime.py \
  scripts/native/test_compare_mnn_runtime.py \
  docs/mnn-upstream-runtime-delta.md \
  app/src/main/java/com/chatbyyourside/llm/benchmark \
  app/src/test/java/com/chatbyyourside/llm/benchmark
git commit -m "perf: gate MNN runtime upgrades with device evidence"
```

runtime 晋级若通过，另做独立提交：

```bash
git add scripts/native/build_mnn_android.sh \
  app/src/main/cpp \
  app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.kt \
  app/src/main/jniLibs/native-manifest.json \
  docs/mnn-upstream-runtime-delta.md
git commit -m "build: promote verified MNN runtime candidate"
```

---

## Final End-to-End Acceptance

- [ ] 开启深度思考后，每条本地消息只有一次 `BackendManager.generate` / 一个 generation envelope。
- [ ] SHORT/MEDIUM/LONG/AUTO 不再产生 192/384/768 token 硬上限，也不触发 256 token 二次补答。
- [ ] 思考与正文共享 `ResolvedInferencePlan.maxOutputTokens`；`generatedTokens` 从不超过该总上限。
- [ ] 生产代码不存在 `BODY_CAP_TOKENS`、`DIRECT_ANSWER_GUIDE`、`directAnswerMessages()`、`hasFinalBody()` 或合成 `</think>` 注入。
- [ ] `modelContent` 保留 native 原始输出；展示层可补起始 `<think>`，但不修改原始 KV/prompt-cache 文本。
- [ ] 用户停止、热停止、timeout、剧本截断、EOS 和 MAX_TOKENS 的 completion reason 仍如实上报；停止后不启动第二轮。
- [ ] 设置页说明“单次生成，共享最大生成长度”，不再显示“思考硬上限 N tokens”。
- [ ] 旧 telemetry JSON 可解码；新记录 `generationMode=SINGLE_PASS_SHARED_LIMIT` 且 `thinkingCapTokens=0`。
- [ ] CPU/OpenCL 健康准入、首 delta 后不换后端、单模型驻留、QNN 排除和 16 KiB native 约束无回归。
- [ ] 上游 runtime 差异报告区分“pinned 已有”与“candidate 新增/修改”，不以 README 特性名直接宣称收益。
- [ ] 候选 MNN runtime/GPU 优化只有在同设备/模型/配置的正确性和 ≥10% 性能门禁通过后才晋级。
- [ ] 若候选不通过门禁，生产仍固定 `af0142bcc7b76b7a5128373e285683dc04f55f69`，并保留失败证据而不是盲目升级。

## Verification Summary

本机当前只执行 Python/native 静态测试与 `git diff --check`。以下命令必须在 JDK 17+ CI 或真实 Android 设备完成后，才能声称实现通过：

```bash
./gradlew testDebugUnitTest --console=plain
./gradlew assembleDebug --console=plain
./gradlew connectedDebugAndroidTest --console=plain
```
