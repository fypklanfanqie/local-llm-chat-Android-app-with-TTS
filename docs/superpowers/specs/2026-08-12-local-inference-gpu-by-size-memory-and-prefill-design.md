# 本地推理：按模型大小开 GPU、内存上限与 GPU prefill 优化 设计

**日期：** 2026-08-12
**状态：** 已获用户批准（A1 + B1 + C1 组合）
**目标设备：** 小米 15（SM8750 骁龙 8 Elite，16GB RAM，arm64-v8a），已装模型 Qwen3.5-2B/4B/9B-MNN。

## 背景与现状

- 用户诉求：① 只在模型大时才开 GPU，其余用 CPU；② 提高 App 可占用内存上限、让大模型“满血放入”且不被系统回收；③ GPU 推理时预填充（prefill）慢、很久出字（每次如此，长 prompt 更明显）。
- 现状事实（已核对源码与真机）：
  - `ModelInfo`（`data/model/LocalModel.kt`）有 `size`（字节）但**无参数字段**；内置清单唯一模型来源。
  - `InferenceProfileResolver.resolve`（`llm/profile/InferenceProfileResolver.kt:88-102`）：AUTO/MNN_GPU 且 OpenCL 健康时**先加 GPU attempt**（thread_num=68），再 CPU_OPTIMIZED/CPU_COMPATIBILITY。
  - `ModelAdmissionController` 只有测试引用，**未接入生产加载路径**（没有拒绝/降级逻辑在生效）。
  - `AndroidManifest.xml` **未开 `largeHeap`**。
  - `ModelResidencyController`（`llm/ModelResidencyController.kt`）：前台常驻；后台 15s（BALANCED）/60s（MAXIMUM_SPEED）后释放；trim 低档/热紧急立即释放；生成中绝不释放。
  - prefill 是 native `response(history,&os,"<eop>",0)` 单次阻塞调用（`mnn_jni.cpp` session_prefill）；config 固定键 `attention_mode=8`、`dynamic_option=0`、`precision/memory=low`（GPU/优化变体）、`use_mmap/reuse_kv=true`、`thread_num`（GPU=68 编码）、`cache_path`。
  - 遥测已有 `prefillMs/prefillTps/TTFT`（`InferenceTurnRecord`）；Task 4 已建 `LONG_PREFILL`/`FIXED_DECODE` 基准场景与 `evaluateRuntime` promotion 门禁。
  - 上游候选 runtime `75e53afe` 含 OpenCL `LinearAttentionBufExecution` 修改、新增 `FusedProjBufExecution`（prefill/decode 内核改进），受 promotion 门禁约束。

## 目标

1. **A：按参数量 ≥7B 才开 GPU**。AUTO 下 <7B 模型一律走 CPU；≥7B 模型保持“健康 GPU 优先、失败回退 CPU”的现有链。显式“强制 GPU”偏好始终尊重用户。
2. **B：提高内存上限 / 防回收**。抬高 App 堆上限（`largeHeap`）；优化驻留使大模型重进不卡、后台不被过早回收；设置页透明展示内存余量。
3. **C：GPU prefill 提速（先测后调）**。在目标真机上用量化数据（prefill 吞吐/TTFT）比较 CPU vs GPU，再决定配置/runtime 优化方向；不盲调。

## 设计

### Part A：按参数量 ≥7B 才开 GPU

**数据模型**
- `ModelInfo` 增加 `val paramsB: Float? = null`（@Serializable，默认 null 保持旧 JSON 兼容）。内置清单 `DEFAULT_MNN_MODELS` 全部填充：
  Qwen3.5-0.8B→0.8、Qwen3.5-2B→2.0、Qwen3.5-4B→4.0、Qwen3.5-9B→9.0、Qwen3.5-35B-A3B→35.0（MoE 按总参数量）、DeepSeek-R1-1.5B→1.5、Qwen3-4B→4.0、DeepSeek-R1-7B→7.0、DeepSeek-R1-0528-Qwen3-8B→8.0、Llama-3.2-1B→1.0、Llama-3.2-3B→3.0、gemma-2-2b→2.0、SmolLM2-360M→0.36。
- 派生：`ModelInfo.isGpuModel: Boolean get() = (paramsB ?: 0f) >= GPU_MIN_PARAMS_B`。
- 常量：`GPU_MIN_PARAMS_B = 7f`（放 `AppConfig.LLM` 或 `LocalModel` companion；单一来源）。

**解析器门禁**
- `InferenceProfileResolver.resolve(...)` 新增参数 `gpuEligibleByModelSize: Boolean`。
- AUTO 分支：`AUTO && !gpuEligibleByModelSize` → **不添加 GPU attempt**，加 `DowngradeReason.SMALL_MODEL_CPU_PREFERRED`（新增枚举，诊断页中文“模型较小，CPU 更优（GPU 仅 ≥7B 启用）”）。
- `MNN_GPU` 显式分支：**不**受门禁影响（用户显式选择优先）。
- `MNN_CPU`/`MNN_NPU` 分支不变。
- `BackendManager` 无需改动（attempt 链已由 resolver 产出）。

**Provider 接线**
- `LocalChatProvider`：由 `settings.getActiveLocalModelIdNow()` 查 `DEFAULT_MNN_MODELS.firstOrNull { it.id == id }`，取 `isGpuModel`；模型未知时默认 `true`（保持既有行为，不因元数据缺失收紧）。传入 `resolve(...)`。
- 诊断：`downgradeReasons` 已随遥测/诊断行显示，新增枚举在 `downgradeReasonText` 映射中文。

**测试**
- `InferenceProfileResolverTest`：AUTO + <7B → attempts 全 CPU、含 `SMALL_MODEL_CPU_PREFERRED`；AUTO + ≥7B → GPU 优先（健康时）；显式 MNN_GPU + <7B → 仍含 GPU。
- `ModelInfo`/`LocalModel` 测试：`isGpuModel` 阈值边界（7.0→true、6.9→false、null→false）。
- 设置页诊断文案测试补充该降级原因中文。

### Part B：提高内存上限 / 防回收

**Manifest**
- `<application android:largeHeap="true">`。作用：抬高 ART 堆上限，缓解 Java 堆 GC/ANR 导致的卡顿；对 mmap 权重/原生 KV 无直接影响（说明写入 spec 与注释，不夸大）。

**驻留策略调优（`ModelResidencyController`）**
- 现状：后台固定 15s/60s 释放。目标：**前台常驻不变；后台释放改为“更宽限 + 内存紧张才立即释放”**，缓解重进 reload 大模型的卡顿，降低“易被回收”观感。
- 具体：`onAppForegroundChanged(false)` 时按内存健康分档——**内存健康**（注入判定，`!lowMemory` 且 `availMem > 阈值`）用较长安全宽限（BALANCED 60s / MAXIMUM_SPEED 120s）**且** `onTrimMemory(TRIM_MEMORY_RUNNING_MODERATE/LOW/CRITICAL)` 或热紧急时立即释放；**`lowMemory`** 时用短宽限（BALANCED 15s / MAXIMUM_SPEED 60s，即现状）。安全宽限保证后台不无限驻留。
- 保留：生成中绝不释放、切云/provider 立即释放、模型变更重排。
- 注入内存健康判定（纯函数/接口，JVM 可测），不把 Android 运行时逻辑塞进状态机。

**KV 内存核对**
- 确认 GPU/优化变体 `precision=low`/`memory=low` 是否已把 KV 降到 fp16（`buildAttemptNativeConfig` 已设 low/low）。若 `memory=low` 对 KV 无作用且模型支持，评估显式 KV 精度键；**无证据不改**，记录在案。

**设置页内存余量展示**
- 现有内存估算（`LlmMemoryEstimator`，KV 部分）基础上，加**可用内存余量**：`ActivityManager.MemoryInfo.availMem` vs「模型权重（ModelInfo.size）+ KV 估算 + 固定开销」；展示“剩余可用/预计占用”，帮助用户选能放下的模型+上下文。只读提示，不阻断。
- 文案纯函数 JVM 可测。

### Part C：GPU prefill 先测后调

**测量（依赖真机 + 已构建代码）**
- 前置：用 `JAVA_HOME=/d/ai/az/jbr ./gradlew assembleDebug` 构建当前工作树（顺带验证计划代码可编译）→ `adb install` → 设备（SM8750）上跑。
- 测量方式：加一个**仅 debug 构建可见的设置页基准入口按钮**（复用 `LocalInferenceBenchmarkRunner` 的 `LONG_PREFILL`/`FIXED_DECODE` 场景 + `CandidateOverrides`；不做新的 instrumentation runner），对 9B 模型分别跑 CPU（CPU_OPTIMIZED）与 GPU（OPENCL，健康通过时），读 `InferenceTurnRecord` 的 `prefillMs/prefillTps/TTFT/decodeTps`。
- 输出：CPU vs GPU 的 prefill 吞吐/TTFT 对照（同 prompt、同 context、同温度）。

**优化方向（由数据决定，不盲调）**
- 若 GPU prefill 显著慢于 CPU：依次评估（每步一条数据证据）：
  1. `attention_mode` 变体（候选经 `CandidateOverrides`/promotion 测量）；
  2. 候选 runtime `75e53afe`（OpenCL LinearAttention/FusedProj，**必须**经 Task 4 `evaluateRuntime` 门禁：≥10% decode 提升、TTFT/PSS 有界、实际 GPU 样本、KV/正确性无回归）后才晋级；
  3. 若均无效且 GPU 无优势，在 spec 记录结论并建议大模型也回落 CPU 默认（不自动改，交由用户决定）。
- 不做：盲目调 `thread_num`/`precision` 而不带基准；未经 promotion 直接换 runtime。

## 验证

- JVM 单测：resolver 门禁、ModelInfo 阈值、驻留状态机（虚拟时间）、设置页文案/内存余量。
- 本机/真机：`assembleDebug` 编译通过；adb 安装；`LONG_PREFILL`/`FIXED_DECODE` CPU vs GPU 对照（9B 模型）。
- 回归：既有 `InferenceProfileResolverTest`/`ModelResidencyControllerTest`/`BackendDiagnosticsTextTest`/`ExperimentalPromotionPolicyTest` 不红；native bundle 不升级（Part C 只在门禁通过后由后续计划处理）。
- 环境约束：Gradle 用 `JAVA_HOME=/d/ai/az/jbr`（JDK21）；本机 `java` 为 JDK8；不改变既有 no-commit 工作流。

## 范围外（明确不做）

- 升级 native bundle（`MnnBridge.kt`/`mnn_jni.cpp`/`native-manifest.json`）——Part C 若证明候选 runtime 有效，作为后续独立计划经 promotion 处理。
- 给 `ModelAdmissionController` 接拒绝逻辑——用户现状“能加载”，无需新增阻断；只做透明展示。
- 修改模型清单来源/增加用户自建模型入口。
- 盲调 prefill 配置或跨设备通用“最优值”。

## 验收标准

1. AUTO 下 2B/4B 模型 attempts 无 GPU；9B/35B 保持 GPU 优先；显式强制 GPU 对小模型仍可。
2. 设置页能看到该降级原因与内存余量。
3. `largeHeap` 生效；后台驻留不再 15s 即释放（内存健康时）。
4. 真机 9B 模型 CPU vs GPU 的 prefill 对照有量化数据；若有优化落地，有 ≥10% prefill/TTFT 提升或明确“调参无效”结论。
5. 全部既有测试不红；计划内新增测试全绿（CI/JDK17+）。
