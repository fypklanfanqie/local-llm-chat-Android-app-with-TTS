# Android 本地 LLM 生成性能与对话可靠性优化计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在旗舰 Android 设备 + 大型 MNN 模型上提高本地生成速率，并修复两个对话 UI/状态问题：首轮回答生成完成后短暂显示又消失、深度思考生成时无法正常浏览内容。

**Architecture:** 可靠性先行 + 测量驱动 + 最后才动 Native。先补齐可信指标并修复两个 UI/状态问题，再把全文装饰与 Markdown 解析移出同步 JNI decode 回调，随后优化 prompt/KV 与后端健康准入；只有真机数据证明 native 逐 token 循环/JNI 开销占比显著时，才做可回滚的多 token step 实验。保留 `BackendManager.generationMutex`、`MnnBackend.mnnMutex` 与单模型驻留策略。

**Tech Stack:** Kotlin, Jetpack Compose, MNN (libMNN.so + libmnn_jni.so, NDK 26/r27c, 16 KiB page), Room, DataStore, kotlinx.coroutines.

## Global Constraints

- 目标设备：旗舰 Android（arm64-v8a）+ 大型 MNN 本地模型。
- 滚动策略：初始/用户位于底部时自动跟随；用户上滑后立即停止；提供「回到底部」按钮。
- Room 是最终持久化真源；在 Room 确认精确行 ID 前保留本轮乐观完成消息。不以回复文本相等判断已回填。
- 不在 token 回调中读取 `/proc`/`/sys`/PSS/温度；此类采样仅低频或基准模式启用。
- 不同时驻留 CPU/OpenCL 两份大型模型。
- 本机 JDK 8 不满足项目 Java 17 要求：本会话不运行 Gradle；验证交由 JDK 17+ CI / 真机。修改 `mnn_jni.cpp` 后必须重编 `.so`、更新 native manifest 并校验 16 KiB `p_align`。
- 语言：项目代码注释为中文，新增代码保持与周围一致的中文注释风格。

---

## Task 1: 建立可信性能基线与回归门槛

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBackend.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/metrics/InferenceTelemetry.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/benchmark/LocalInferenceBenchmarkRunner.kt`
- Create: `app/src/main/java/com/chatbyyourside/llm/benchmark/DefaultLocalInferenceBenchmarkRunner.kt`
- Create: `app/src/main/java/com/chatbyyourside/llm/benchmark/DataStoreBenchmarkResultStore.kt`
- Modify: `app/src/androidTest/java/com/chatbyyourside/llm/backend/MnnStreamingIntegrationTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/metrics/InferenceTelemetryTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/benchmark/BenchmarkStatisticsTest.kt`

**Implementation:**
1. 用一次 generation envelope 记录请求开始、attempt 选择、模型加载、prefill、首个可见 delta、decode 与 finalize；将 `ResolvedInferencePlan.requestedMode/effectiveMode`、实际 `BackendAttempt.loadConfigHash`、runtime variant、attempt trace 和完整 downgrade reasons 传入 `InferenceTelemetry`，不再写 `null` 或用路径哈希代替配置哈希。
2. 在 `ensureAttemptLoaded()` 周围记录加载时长并区分「已加载直接复用」「首次冷加载」「配置变化重载」；只在最终记录写入 load 时间。
3. TTFT 优先采用 native `firstDeltaUs`，同时保留 Kotlin 首回调时间用于一致性检查；生成速度统一使用 native `generatedTokens/decodeUs`，删除 `ChatViewModel` 中把 chunk 次数当 token 数的口径。
4. 低频记录 thermal start/max/end 与 peak PSS；基准运行期间暂停 `PerformanceGlassOverlay` 的 500ms `/proc`/`sysfs` 采样和玻璃动画，避免污染结果。
5. 实现已有 `LocalInferenceBenchmarkRunner`/`BenchmarkResultStore` 契约，按 device/runtime/native build/MNN commit/model bundle/config hash 归档 `COLD_LOAD`、`SHORT_TTFT`、`LONG_PREFILL`、`FIXED_DECODE`、`SECOND_TURN_KV_REUSE` 五类场景。过热不开始；1 次预热 + 5 次冷态记录；热态/噪声样本注明原因后丢弃。
6. 修正 `MnnStreamingIntegrationTest.fixture()` 仍调用旧 `initialize(contextLength, threads, ...)` 签名的问题，改为通过 `InferenceProfileResolver` 或 canonical native config 调用当前 `initialize(modelPath, nativeConfigJson, loadConfigHash)`。

**Acceptance:**
- 每轮日志/记录可同时看到 TTFT、prefill ms/TPS、decode ms/TPS、prompt/gen tokens、KV reuse、callback ratio、load 类型、backend variant、config hash、thermal/PSS 和 completion reason。
- 相同配置 5 次冷态样本可汇总中位数与标准差。
- 候选优化只有在 decode TPS 中位数提高至少 10%、TTFT 回退不超过 30%、PSS 回退不超过 30%、且无输出完整性回归时才允许启用。

## Task 2: 以 Room 行 ID 协调完成消息，修复首轮回答消失

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/data/model/ChatMessage.kt`
- Modify: `app/src/main/java/com/chatbyyourside/data/repository/ChatRepository.kt`
- Create: `app/src/main/java/com/chatbyyourside/ui/chat/ChatTimelineReconciler.kt`
- Modify: `app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt`
- Create: `app/src/test/java/com/chatbyyourside/ui/chat/ChatTimelineReconcilerTest.kt`
- Modify: `app/src/test/java/com/chatbyyourside/data/repository/ChatRepositoryMappingTest.kt`

**Implementation:**
1. 给领域层 `ChatMessage` 增加只在应用内使用的 `databaseId: Long?`；`ChatHistoryEntity.toMessage()` 从现有 Room 主键赋值，`toEntity()` 仍让 Room 自动生成 ID，因此不需要数据库迁移。
2. 持久消息统一使用 `msg-<databaseId>` 作为 `DisplayMessage.id`；只对非持久测试/临时消息使用 timestamp fallback。
3. 新建纯 Kotlin `ChatTimelineReconciler`，输入 Room snapshot、当前 streaming bubble、可选 pending-final 与 active conversation ID，输出可显示时间线和 pending 是否已被 Room 确认。
4. `sendMessage()` 保存 assistant 后使用 `addMessage()` 返回的行 ID 构造 `PendingFinal(conversationId, databaseId, displayMessage)`，立即把 `streaming` 替换为该完成消息；Room snapshot 包含同一 `databaseId` 时才清除 pending。
5. `renderMessages()` 不再无条件用 history 覆盖 UI：先渲染 Room snapshot，再保留当前会话尚未获 Room ID 确认的 pending-final，生成中继续保留唯一 `streaming`。删除基于 `content == displayResponse` 的 `backfilled` 判断。
6. 在新建/切换/删除会话、切角色、切 provider 前同步清理旧 conversation 的 streaming/pending epoch，防止旧回复串入新会话；`finally` 只移除真正的 `streaming` 并重置生成标志，不移除 pending-final。
7. 深度思考开关重渲染也走同一个 reconciler，不直接用可能滞后的 `latestHistory` 覆盖当前完成态。

**Tests:**
- Room 先发 `[user]`、UI 已有 pending assistant：assistant 仍在。
- Room 后发包含相同行 ID 的 assistant：只显示一次并清 pending。
- 两次 assistant 文本完全相同：按 ID 正确区分。
- A 会话 pending 不得合并到 B 会话。
- Room 回填先于乐观替换也不得重复。
- 取消、插入失败和切换会话后不存在 orphan pending。
- 映射往返准确保留 `databaseId`/`modelContent`。

## Task 3: 实现「用户接管优先」的真实底部跟随

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/ui/chat/ChatScreen.kt`
- Create: `app/src/main/java/com/chatbyyourside/ui/chat/ChatAutoScrollPolicy.kt`
- Create: `app/src/test/java/com/chatbyyourside/ui/chat/ChatAutoScrollPolicyTest.kt`
- Modify: `gradle/libs.versions.toml`（仅加入 Compose UI 测试依赖）
- Modify: `app/build.gradle.kts`（仅加入 `ui-test-junit4`/`ui-test-manifest`）
- Create: `app/src/androidTest/java/com/chatbyyourside/ui/chat/ChatMessageListScrollTest.kt`

**Implementation:**
1. 将消息列表提取为可测试的 `ChatMessageList`，继续只使用一个 `LazyColumn`，不引入嵌套 `verticalScroll`。
2. 用像素位置而不是 item index 判断底部：`!listState.canScrollForward` 为精确底部；「接近底部」要求最后 item 已布局，且其 bottom 与 `viewportEndOffset` 的距离小于 96dp。单个超高 streaming item 仅「可见」不能算到底。
3. 维护明确 follow mode：进入/切换会话时开启；用户仍在底部时保持；通过 `interactionSource` 检测真实拖拽，用户上滑后关闭；用户手动回到底部或点击按钮后恢复。程序性 scroll 不得被误判为用户接管。
4. streaming 内容增长后等待下一帧布局；仅在 follow mode 开启且用户未拖拽时，滚到列表真实最大前进位置。不得再调用默认 offset=0 的 `scrollToItem(lastIndex)`；使用末项 end-oriented offset 或 `scrollBy` 到 `canScrollForward=false`。
5. token 更新期间使用即时滚动，不为每个 chunk 启动动画，避免 `LaunchedEffect` 反复取消动画。
6. 当下方仍有内容且 follow mode 暂停时，在输入栏上方显示至少 48dp 的向下按钮，`contentDescription="回到底部"`；点击后精确到底并恢复后续自动跟随，位置不能遮挡字幕条。

**Tests:**
- 初次进入已有历史时位于真实底部。
- 深度思考 item 高于多个视口时，跟随的是内容底部而非 item 顶部。
- 用户上滑后连续 10 次 streaming 更新不改变用户视口。
- 「回到底部」按钮出现、可访问、点击后 `canScrollForward=false` 并恢复跟随。
- 展开/折叠思考块、typing indicator 消失、切换会话均不跳到顶部。

## Task 4: 把全文装饰与 Markdown 解析移出同步 JNI decode 回调

**Files:**
- Create: `app/src/main/java/com/chatbyyourside/provider/local/LocalStreamRenderPump.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Modify: `app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt`
- Create: `app/src/test/java/com/chatbyyourside/provider/local/LocalStreamRenderPumpTest.kt`
- Modify: `app/src/androidTest/java/com/chatbyyourside/llm/backend/MnnStreamingIntegrationTest.kt`

**Implementation:**
1. `LocalChatProvider` 保持唯一 authoritative raw `StringBuilder`；native 同步回调中只做 delta append、增量剧本检测、策略截断、真实 token/进度发布和 conflated render signal，然后立即返回 continuation Boolean。
2. `LocalStreamRenderPump` 使用容量 1/conflated 信号在独立 coroutine 中快照全文、执行 `renderLocalThink()` 并通知 UI；慢 UI 不得形成无界 token/channel 队列。读取 `StringBuilder` 时使用明确锁或不可变快照，不能并发裸读。
3. 首个完整可见 delta 立即发布；其余 balanced 最多约 30fps，maximum-speed 的 UI cadence 作为基准候选（先测试 50–66ms，不直接写死为更优）。native 返回后强制 final flush，再构造 `LocalChatResult`，确保节流尾段不丢失。
4. `ChatViewModel` 不再对每个 chunk 全量更新性能日志或用 chunk 计 token；性能浮窗读取 `MnnBackend` 原子 telemetry。Markdown 解析只在 render pump 放行的 UI 帧执行，最终落库前再完整解析一次。
5. 保留增量安全检测在同步回调内，因为它必须及时返回 `false`；保留最终乐观 assistant 替换和 Room reconciliation，不把持久化职责移入 render pump。

**Acceptance:**
- `callbackBytes` 与最终 `modelContent` UTF-8 字节数一致；CJK/emoji 无缺失、重复、乱序或 replacement char。
- 首 delta 回退小于 50ms；decode 取消仍在 1 token 内。
- 相同设备/模型/提示词的 decode TPS 中位数提升至少 10%，否则不以「提升生成速度」名义推广；若只降低 UI jank，则仅保留已证明改善流畅度且不增加复杂度的部分。

## Task 5: 提高 KV/prefill 命中率并接通真实后端健康准入

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/llm/PromptWindowPlanner.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/BackendHealthStore.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/OpenClProbeRunner.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/PromptWindowPlannerTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/profile/InferenceProfileResolverTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/backend/BackendHealthStoreTest.kt`

**Implementation:**
1. 用 Task 1 指标验证第二轮 `reuseKv=true` 且 `promptTokens` 主要对应新增 user turn；若 miss，逐字节对比上一轮 native `syncPromptCache(history)` 与下一轮 `modelMessages` 前缀，确保展示层 `<think>` 装饰不进入模型历史，system/response guide 和窗口 anchor 稳定。
2. PromptWindowPlanner 继续保留 system、最新 user 和完整最近轮次，不做摘要；通过真实测量 token 数替代可用的保守估算，减少不必要的历史裁剪/anchor 变化。输出 reserve 仍不得挤掉 mandatory 输入。
3. 将 `ResolvedInferencePlan.downgradeReasons` 与 prompt downgrade 一并传入 telemetry，区分 KV miss 是窗口变化、配置重载还是内容不一致。
4. 修复 `LocalChatProvider` 把 `mnnGpuSupported`（仅库可加载）直接当 `PROBE_OK` 的 wiring：`UNKNOWN` 先走隔离 `OpenClProbeRunner`；`PROBE_OK/MODEL_OK` 才允许 AUTO/OpenCL；cooldown/crash-blacklist 排除；模型成功加载后记录 `MODEL_OK`。
5. 只有真正的 probe/load/generation backend failure 才降低健康度；用户取消、策略 timeout、max tokens、thermal stop 不得污染后端健康。
6. 保留 CPU optimized -> CPU compatibility fallback 和 `releaseOthers()`；不得为了速度并行加载 CPU/OpenCL 模型。

**Acceptance:**
- 同一会话第二轮 KV reuse 率接近 100%（窗口 anchor 未变化且配置未重载的样本）。
- 长历史裁剪发生时有明确 `history_anchor_changed/history_trimmed` 原因，而非静默全量 prefill。
- AUTO 不会把「OpenCL 库存在」误当作「模型可稳定运行」；隔离 probe 崩溃不能带崩主进程。

## Task 6: 以基准门控旗舰机配置；Native 仅做条件实验

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/llm/benchmark/ExperimentalPromotionPolicy.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/profile/ResolvedInferencePlan.kt`
- Conditionally modify: `app/src/main/cpp/mnn_jni.cpp`
- Conditionally modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.kt`
- Conditionally modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBackend.kt`
- Modify: `app/src/androidTest/java/com/chatbyyourside/llm/backend/MnnStreamingIntegrationTest.kt`
- Modify: `app/src/main/jniLibs/native-manifest.json`（仅 Native 实验晋级时）

**Implementation:**
1. 对同一设备/runtime/native build/model 指纹分别测试：大核数附近的 CPU threads、lookahead off/on、CPU optimized/compatibility、以及通过健康准入的 OpenCL；独立看 SHORT_TTFT、LONG_PREFILL、FIXED_DECODE、SECOND_TURN_KV_REUSE，不用一个综合分掩盖回退。
2. 仅由 `ExperimentalPromotionPolicy` 晋级达到 Task 1 门槛的候选；运行时缓存晋级结果，不在 token 路径读取 DataStore。热/内存安全策略仍可覆盖 maximum-speed。
3. 不直接修改阻塞 `llm->response(..., 0)` 的跨线程中断。当前 MNN API 下 prefill 期间释放/变更 native handle 不安全；优先依靠更短且稳定的 prompt、KV reuse、适当线程和已验证 backend 降低 TTFT。
4. 只有 profiler 显示逐步循环/JNI abort poll 占 decode 的显著比例时，才给 `ResolvedInferencePlan` 增加类型化 `decodeStepTokens`：balanced 固定 1；maximum-speed 对 2、4 分别做实验。必须验证 EOS、max tokens、UTF-8、KV sync、策略截断，并明确取消最坏粒度变为 `k` tokens。
5. 不解除 MNN 全局锁，不并行请求，不删除 native `full_text`（它当前用于 `syncPromptCache`）；若 MNN 后续提供可从 Kotlin 原文安全同步 cache 的 API，再单独评估减少 native/Kotlin 双份全文。

**Acceptance:**
- 仅启用在同指纹设备上达到性能门槛且通过完整性/稳定性测试的 profile。
- Native step 候选若 decode TPS 提升不足 10% 或出现 EOS、停止延迟、KV、文本完整性回归，则保留 `generate(1)`。
- 连续长回复和多轮对话不得增加 crash、ANR、热停或内存峰值回归。

## Verification

### JDK 17+ CI/JVM

```bash
java -version
./gradlew testDebugUnitTest --console=plain
./gradlew assembleDebug --console=plain
```

预期：Java 版本至少 17；所有 reconciliation、scroll policy、render pump、prompt/KV、telemetry 测试通过，debug APK 可构建。本机当前 JDK 8 环境跳过这些命令，不能据此声称测试已通过。

### Compose/Room instrumentation

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.chatbyyourside.ui.chat.ChatMessageListScrollTest \
  --console=plain
```

### arm64 真机 MNN

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.chatbyyourside.llm.backend.MnnRuntimeIntegrationTest \
  --console=plain
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.chatbyyourside.llm.backend.MnnStreamingIntegrationTest \
  --console=plain
```

辅助证据：

```bash
adb logcat -s MnnJni MnnBackend BackendManager LocalChatProvider
adb shell dumpsys meminfo com.chatbyyourside
adb shell dumpsys gfxinfo com.chatbyyourside reset
adb shell dumpsys gfxinfo com.chatbyyourside framestats
adb shell dumpsys thermalservice
```

### Native 变更（仅 Task 6 条件成立时）

```bash
ANDROID_NDK_HOME=/path/to/android-ndk-r27c \
MNN_BUILD_STAGING="$HOME/mnn-build" \
bash scripts/native/build_mnn_android.sh
python3 scripts/native/verify_native_bundle.py \
  --dir app/src/main/jniLibs/arm64-v8a \
  --manifest app/src/main/jniLibs/native-manifest.json
```

## Critical files

- `app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt`
- `app/src/main/java/com/chatbyyourside/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- `app/src/main/java/com/chatbyyourside/llm/backend/MnnBackend.kt`
- `app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt`
- `app/src/main/java/com/chatbyyourside/llm/PromptWindowPlanner.kt`
- `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- `app/src/main/cpp/mnn_jni.cpp`
