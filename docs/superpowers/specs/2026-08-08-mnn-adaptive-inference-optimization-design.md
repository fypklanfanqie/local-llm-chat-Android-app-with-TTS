# MNN 本地 LLM 自适应推理优化设计

## 1. 背景与目标

当前应用已经实现完整的 MNN 本地推理链路：

`ChatViewModel → LocalChatProvider → BackendManager → MnnBackend → MnnBridge → mnn_jni.cpp`

现有实现具备 CPU/OpenCL/QNN 后端、mmap、KV 复用、逐 token 解码、取消、温控、CPU 性能提示和运行时指标，但实际速度仍受以下问题影响：

- native 每个小片段都分配 `jbyteArray`，Kotlin 多层重复累积和扫描完整回复；
- 展示用 `<think>` 补全可能与 native `syncPromptCache()` 记录的原始回复不一致，导致后续轮次 KV 前缀复用失效；
- `AUTO` 在未知设备上直接尝试 OpenCL，驱动可达不等于执行正确或更快；
- 默认最多生成 65,536 token，且最多发送 100 条历史消息，可能放大失控生成和 prefill 开销；
- 缺少可复现的 native 构建、16 KiB 页支持、模型完整性校验、RAM/存储准入和持久设备画像；
- QNN 包、SoC 检测和模型变体并未形成可靠闭环；
- 缺少冷加载、首字延迟、稳定 tokens/s、KV 命中、PSS、温度等可比较基线。

本设计参考 Alibaba MNN `apps/Android/MnnLlmChat` 及其 LLM engine，固定研究基线为 MNN commit `af0142bcc7b76b7a5128373e285683dc04f55f69`。目标是在不重写现有推理架构的前提下：

1. 提升首字速度、持续生成速度和多轮对话速度；
2. 提供用户可选的“综合平衡”和“最高速度”模式，新安装默认综合平衡；
3. 在不同 ARM64 芯片、OpenCL 驱动和 Android API 24+ 上安全降级；
4. 避免残缺模型、内存不足、过热和驱动问题造成崩溃或错误输出；
5. 建立可复现、可测量、可逐步启用实验优化的基础设施。

## 2. 范围与非目标

### 2.1 首版范围

- ARM64-v8a、Android API 24+；
- MNN CPU 为所有未知设备的兼容基线；
- OpenCL 经探测、正确性验证和性能验证后进入自动选择；
- 综合平衡/最高速度双模式；
- JNI 流式热路径、KV 前缀一致性、上下文窗口和默认输出上限优化；
- native 16 KiB 页兼容与构建溯源；
- 模型包、RAM、存储、温控和生命周期准入；
- 本地基准、持久设备画像和后端健康记录。

### 2.2 非目标

- 不新增 ARMv7 或 x86_64 支持；
- 不将整个推理链路迁移至独立进程；仅 OpenCL 轻量探测使用私有辅助进程；
- 不在标准版自动启用 QNN；
- 不把 `attention_mode`、动态量化、磁盘 KV、原始 MNN JSON 等危险参数直接暴露给普通用户；
- 不在运行时改变模型权重量化位数；离线量化属于独立模型制品与质量评估流程；
- 不引入对话摘要模型来缩短上下文。

## 3. 方案选择

采用“用户意图模式 + 设备自适应执行计划”。

用户只选择：

- `BALANCED`：综合平衡，默认；
- `MAXIMUM_SPEED`：最高速度，但仍受内存、温度和后端健康约束。

模式不直接等于固定 MNN 参数。每轮推理前，由解析器结合用户模式、后端偏好、设备/运行时指纹、模型指纹、内存、温度、持久基准和失败记录，生成不可变的 `ResolvedInferencePlan`。

该方案优于固定双预设，因为 OpenCL、线程数、lookahead、动态量化等在不同设备和模型上可能反向降速；也优于全推理独立进程，因为后者会显著扩大 Binder、生命周期、KV 状态和 IPC 复制的改造范围。

## 4. 总体架构

### 4.1 保留的主链路

继续使用现有边界：

- `LocalChatProvider`：读取一次性设置快照、准备消息、组织流式输出；
- `BackendManager`：执行后端尝试链、句柄复用、失败回退和释放；
- `MnnBackend` / `MnnBridge`：Kotlin/JNI 会话边界；
- `mnn_jni.cpp`：MNN 配置、加载、prefill、decode、KV 同步；
- `InferenceThreadOptimizer`、`ThermalMonitor`、`CpuBoostController`、`LlmMemoryEstimator`、`PerformanceCollector`：扩展现有职责，不另造平行实现；
- `ModelPathResolver`、`DownloadManager`、`FileSplitter`：承载模型路径、下载和完整性增强。

### 4.2 新增组件

建议新增：

- `llm/profile/InferencePerformanceMode.kt`
- `llm/profile/ResolvedInferencePlan.kt`
- `llm/profile/InferenceProfileResolver.kt`
- `llm/profile/DeviceRuntimeFingerprint.kt`
- `llm/backend/BackendHealthStore.kt`
- `llm/ModelBundleValidator.kt`
- `llm/ModelAdmissionController.kt`
- `llm/PromptWindowPlanner.kt`
- `llm/ModelResidencyController.kt`
- `llm/metrics/InferenceTelemetry.kt`
- `llm/benchmark/LocalInferenceBenchmarkRunner.kt`
- `cpp/backend_probe_jni.cpp`
- 私有 `OpenClProbeService`，运行于 `:mnn_probe` 进程。

### 4.3 执行计划

`ResolvedInferencePlan` 至少包含：

- 请求模式与实际模式；
- 设备/运行时与模型指纹；
- 实际上下文和最大输出 token；
- 功耗、流式批处理和驻留策略；
- 有序 `BackendAttempt` 列表；
- 所有安全降级原因。

每个 `BackendAttempt` 包含后端、运行时配置 JSON、配置哈希、是否需要探测。`BackendManager` 按尝试项显式加载，不再由 JNI 隐式执行 CPU 安全重试。

典型 AUTO 尝试链：

1. 已探测且健康的 OpenCL；
2. CPU 优化配置；
3. CPU 兼容配置。

QNN 不进入 AUTO。

## 5. 双模式策略

| 策略 | 综合平衡 | 最高速度 |
|---|---|---|
| 未校准设备 | CPU 基线 | CPU 基线，后台校准后可升级 |
| OpenCL | 探测且正确、性能不差于 CPU 时使用 | 使用当前指纹下最快且健康的已校准后端 |
| CPU 线程 | 最多 4 个性能核，受温控限制 | 使用已基准的线程数；未校准最多 6 个性能核，受温控限制 |
| 性能提示 | 仅推理期间，温和目标 | 仅推理期间，更积极目标 |
| sustained mode | 关闭 | 只在本次本地生成期间开启，`finally` 恢复 |
| lookahead | 默认关闭 | 仅同设备/模型基准证明收益后开启 |
| prompt chunking | 内存准入要求时使用 | 默认关闭，内存不足时降级启用 |
| attention/dynamic | `attention_mode=8`、`dynamic_option=0` | 同左，实验通过后才允许其他值 |
| 模型驻留 | 后台/切云后约 15 秒释放 | 内存健康时最长约 60 秒 |
| 流式回调 | 首块立即，后续约 16ms/256B | 首块立即，后续约 24–32ms/512–1024B |

批处理阈值只影响 UI/桥接，不参与模型加载指纹。

## 6. 设备、运行时与模型指纹

设备/运行时指纹用于持久画像失效，不用于替代 MNN 的 HWCAP CPU 内核选择。包含：

- `Build` 的制造商、型号、设备、硬件、系统指纹、API、系统增量版本；
- ABI、SoC 信息（API 31+）和 CPU 性能核数量；
- OpenCL 平台、设备、厂商、驱动标识；
- 应用版本、策略版本、JNI ABI 版本；
- MNN commit、native build ID、`libMNN.so` 与 `libmnn_jni.so` 哈希。

模型指纹包含：

- 模型 ID；
- `config.json`、`llm_config.json`、tokenizer、embedding、graph、weight 的清单、大小和哈希；
- 安装清单版本。

系统 OTA、驱动变化、应用/native 更新、模型替换或策略版本变化都会自然失效旧基准和黑名单。

后端健康记录按“设备运行时指纹 + 模型指纹 + 后端 + 配置族”存储，区分探测、加载、prefill、decode 阶段。用户取消、温控停止和内存准入拒绝不计为后端故障。

## 7. OpenCL 与 QNN 策略

### 7.1 OpenCL

现有 `System.loadLibrary("OpenCL")` 只能证明链接可达。新增私有辅助进程探测：

1. 加载并解析 OpenCL；
2. 枚举平台和设备；
3. 创建 context/queue；
4. 运行一个极小 kernel；
5. 校验输出并返回驱动标识和耗时。

主进程设置超时并监听 Binder 死亡。探测前同步写入“进行中”日志，只有正常结果才清除；下次启动发现残留记录时，视为可能的驱动崩溃并按当前指纹禁用。轻量探测成功后，仍需记录该模型首次加载/生成是否成功。

透明后端回退只允许发生在首个可见输出之前。OpenCL 已输出部分文本后失败时，不得把 CPU 从头生成的内容拼在后面；应返回有类型的部分失败，并让下次请求使用 CPU。

### 7.2 QNN

标准版：

- 从 AUTO 排除；
- 隐藏或禁用 QNN 选择；
- 建议移除标准 APK 内不匹配的 QNN 库；
- 已保存 `MNN_NPU` 的旧设置不删除，但解析为 CPU 并展示不可用原因。

未来实验版必须使用独立 flavor，锁定 QNN SDK，打包与 SoC/HTP 对应的 Htp/System/Stub/Skel，要求 QNN 专用模型变体，并继续显式选择、不可由“最高速度”自动启用。

## 8. Native 配置与构建

### 8.1 JNI 配置接口

将不断增长的 `nativeCreate` 参数改为：

```text
nativeCreate(configPath, resolvedConfigJson)
```

JSON 仅由 `InferenceProfileResolver` 生成。JNI 校验长度和 schema，记录配置哈希，再交给 `Llm::set_config`。增加 `nativeGetRuntimeInfo()`，返回 JNI ABI 版本、MNN commit、native build ID 和支持的配置能力；Kotlin/native schema 不匹配时在加载模型前失败。

### 8.2 安全通用配置

默认保持：

- `use_mmap=true`
- `use_cached_mmap=true`
- `reuse_kv=true`
- `attention_mode=8`
- `dynamic_option=0`
- `mixed_samplers` 含 `penalty`

运行时缓存放入按运行时/模型指纹命名的应用私有缓存目录，不再写入下载模型目录，并设置配额与淘汰。

OpenCL 保持 MNN 要求的 `thread_num=68`。CPU 优化配置使用 low precision/memory 和高性能调度；CPU 兼容配置显式使用 pinned MNN 支持的保守 precision/memory/power 枚举，不依赖“省略字段后继承未知模型默认值”。具体枚举在实现时以 pinned commit 的 `llmconfig` 为准。

### 8.3 可复现 native 包

新增 `scripts/native/build_mnn_android.sh`、`scripts/native/verify_native_bundle.py` 和 `jniLibs/native-manifest.json`。构建要求：

- MNN commit 固定为 `af0142bcc7b76b7a5128373e285683dc04f55f69`；
- NDK `27.2.12479018`，同一 `libc++_shared.so`；
- Android API 24，arm64-v8a；
- 启用 LLM、low memory、CPU weight dequant GEMM、transformer fuse、ARM82、OpenCL；标准版 QNN 关闭；
- `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`；
- 链接 `-Wl,-z,max-page-size=16384`；
- 所有 `.so` 记录 SHA-256、build ID、编译器、NDK、API、flags。

代理只通过环境变量 `HTTP_PROXY`/`HTTPS_PROXY=http://127.0.0.1:7897` 传入构建或下载命令，不写入应用或脚本默认值。

## 9. 流式热路径优化

当前 native/Kotlin 存在每小块 JNI 分配、native 完整回复返回、MnnBackend 和 LocalChatProvider 重复累积、每块全量剧本扫描和 `<think>` 重渲染。

改造为：

1. 保留 UTF-8 边界处理；
2. 增加 native `StreamBatcher`；
3. 首个可见 delta 立即发送；
4. 后续按时间、字节阈值、EOS、停止和错误 flush；
5. `nativeGenerateStream` 仅返回状态/摘要，不再返回完整回复；
6. `MnnBackend` 只转发 delta 和指标；
7. `LocalChatProvider` 成为唯一原始回复累积者；
8. 剧本检测只扫描新增区间和最长角色名重叠窗口；
9. `<think>` 装饰仅在 UI 真正刷新时进行；
10. UI 继续节流，但仅在刷新时创建完整字符串。

指标浮窗不再与 native decode 并发读取可变 `LlmContext`；生成期间读取 Kotlin 原子快照，精确 native 指标在受控回调或 JNI 返回后更新。

## 10. KV 前缀一致性与消息存储

为 `ChatMessage` 增加可空 `modelContent`：

- `content`：展示和落库文本，可含补全的 `<think>`；
- `modelContent`：原样回传给本地模型、且与 native `syncPromptCache()` 使用的 assistant 文本逐字节一致。

本地历史序列化使用 `modelContent ?: content`；云端保持现有处理。展示装饰永不进入 `MnnBridge.toMessagesJson()`。

Room 从当前版本迁移到下一版本，显式增加 nullable 列，保留旧消息；不得依赖 destructive migration。升级后的旧消息回退 `content`，后续新回复开始具备精确 KV 复用。

自然结束时同步 prompt cache；用户取消时回滚 decode KV 且不把半句当作可复用完整 assistant；剧本策略截断应使 prompt cache 失效或按最终原始文本重新同步，不能假装前缀仍匹配。

## 11. 上下文与输出安全

- 缺省最大输出从 65,536 改为 2,048 token；4,096 和 Unlimited 仅作为明确高级选项；
- 已存储的用户选择不被迁移覆盖；只有缺省键使用新值；
- 新增无进展 watchdog、模式对应的壁钟时限和明确完成原因；
- `PromptWindowPlanner` 保留 system prompt 和完整 user/assistant 轮次，按实际准入 context 选择最近最大后缀，并预留模板及输出空间；
- 上下文锚点变化应记录为合法 KV 失效事件；
- 不改写历史文本，不在首版引入摘要。

## 12. 模型完整性、存储与 RAM 准入

### 12.1 模型完整性

`ModelBundleValidator` 从 `config.json`/`llm_config.json` 推导 graph、weight、tokenizer、embedding、visual/audio 等必要文件，检查：

- 路径未逃逸模型目录；
- 文件存在、非空、非临时下载；
- JSON 可解析；
- 远端大小与公开校验和匹配；
- split metadata 的所有分片和 checksum 被实际验证；
- 合并文件验证完成后才删除分片；
- 安装清单记录 URL、大小、哈希和完成状态。

大权重完整哈希在下载/合并/修复时计算，日常加载使用安装清单、大小、mtime 和关键小文件哈希快速验证。

### 12.2 存储准入

下载前检查实际模型卷：剩余下载 + 合并输出空间 + runtime cache 配额 + 安全余量。安全余量至少为 512 MiB 或模型包 10% 中较大者。

### 12.3 RAM 准入

KV 估算使用 `num_key_value_heads × head_dim`，而非总 hidden size。预计峰值包含模型工作集、KV、prefill activation、JNI/Kotlin 和后端开销，并结合历史实测峰值 PSS。

内存不足时按顺序：

1. 关闭实验参数；
2. 必要且已验证时启用 prompt chunking；
3. 降低实际 context；
4. 选择更低内存后端/配置；
5. 仍不能安全装载则拒绝，并给出可操作说明。

自动降低的 context 必须在 UI 中显示。

## 13. 生命周期、温控与功耗

`ModelResidencyController` 监听 provider、前后台、模型切换/删除和 `onTrimMemory`：

- 综合平衡：切云或后台后约 15 秒释放；
- 最高速度：内存健康时最长约 60 秒；
- low/critical trim：在 JNI 安全返回后释放；
- 切换模型：加载新模型前立即释放旧模型；
- 始终避免双模型同时驻留。

`CpuBoostController` 改为每轮 `PowerPolicy`：

- 所有 hint 和线程优先级仅在推理期间有效；
- 最高速度可在生成期间启用 sustained mode，必须在 `finally` 关闭；
- 综合平衡不启用 sustained mode。

温控：

- MODERATE：最高速度降为实际平衡，撤销 sustained，下一轮降线程；
- SEVERE：立即撤销加速，当前句柄标记为本轮后释放，下一轮最多 2 线程；
- CRITICAL/EMERGENCY：请求停止，返回 thermal stop，不计后端失败，JNI 返回后释放。

MNN 线程数在 load 时固定，因此中途只撤销 boost 或停止，不声称动态改变当前 worker 数量。

## 14. 指标、基准与实验门禁

### 14.1 每轮指标

记录：

- 请求/实际模式；
- 后端尝试链、实际后端、配置哈希、降级原因；
- 冷加载/热复用、load、TTFT、prefill、decode、采样耗时；
- prompt/gen token、tokens/s、KV reuse；
- JNI 回调次数与字节；
- 进程 PSS（加载前、加载后、prefill 峰值、decode 峰值、释放后）；
- 可用内存与 low-memory 状态；
- 开始/最高/结束温度状态；
- 设备、runtime、模型指纹。

不再把常量 `0.85` 或不可访问 sysfs 值标作真实 GPU/NPU 利用率。遥测仅本地、有限保留，并提供用户主动导出。

### 14.2 本地基准

高级设置提供基准入口，测量：

- 冷加载；
- 短提示 TTFT；
- 长提示 prefill；
- 固定长度 decode；
- 第二轮 KV 复用；
- 峰值 PSS 和温度变化。

正式基准时关闭性能浮窗和无关动画，热机时拒绝开始；多次运行报告中位数与离散度。只有正确性通过且相对基线有稳定收益的实验配置才持久晋升。

实验晋升建议门槛：decode 中位数至少提升 10%，或 TTFT 有明确收益；非目标指标回退受限；无乱码、UTF-8 错误、重复失控、EOS 异常或 KV 失配。

### 14.3 实验参数

以下均默认关闭、逐项独立验证：

- lookahead；
- prompt chunk size；
- attention/KV 量化模式；
- runtime dynamic quantization；
- disk KV/prefix cache；
- mmap cache 大小；
- 更高线程数；
- 离线权重量化模型；
- QNN。

不得把多个参数打包成一个实验，否则无法归因。

## 15. 错误与回退语义

引入阶段：`VALIDATE`、`ADMIT`、`PROBE`、`LOAD`、`PREFILL`、`DECODE`、`FINALIZE`。

引入完成原因：`EOS`、`MAX_TOKENS`、`USER_CANCEL`、`POLICY_TRUNCATION`、`THERMAL_STOP`、`TIMEOUT`、`BACKEND_FAILURE`。

规则：

- 验证失败不尝试后端；
- 内存不足先尝试安全降级，仍不足则失败；
- OpenCL 探测/加载失败持久记录并回退 CPU；
- CPU 优化加载失败转 CPU 兼容配置，不把 CPU 整体拉黑；
- CPU 兼容仍失败才终止本地推理，并保留全部尝试原因；
- 首个可见 delta 后禁止透明切后端；
- 取消和 thermal stop 不计后端故障；
- native 内部 prefill/decode 异常必须传回 Kotlin，不能把部分文本伪装成正常成功。

## 16. 设置与用户体验

设置页在后端设置上方提供两选一模式：

- 综合平衡（推荐）；
- 最高速度。

后端选择进入高级区。主界面不再把独立 CPU boost/lookahead 作为与模式并列的普通开关；保留旧 DataStore key 以兼容，但执行计划不再直接听从旧值。

展示：请求模式、实际模式、实际后端、是否已校准、降级原因、实际 context。提供“重新测试此后端/清除当前画像”动作。

## 17. 迁移与向后兼容

- 新模式键缺失或非法时使用 `BALANCED`；
- 已存 max token、context、后端偏好不被覆盖；
- 旧 CPU boost/lookahead key 保留但不再是主策略来源；
- 旧 `MNN_NPU` 设置解析为安全 CPU 并显示说明；
- Room 显式迁移新增 `modelContent`，旧记录为空时回退 `content`；
- Android API 24–28 不调用 Thermal API 29+ 或 PerformanceHint API 31+；
- native runtime-info 不匹配时在模型加载前失败并给出版本诊断。

## 18. 验证策略

### 18.1 JVM 单元测试

覆盖：

- 双模式、温度、内存和后端健康下的计划解析；
- CPU 优化/兼容顺序，QNN 排除；
- 指纹稳定和失效；
- 黑名单、探测日志和重置；
- native JSON、配置哈希、转义；
- PromptWindowPlanner；
- `modelContent` 与旧消息回退；
- 增量剧本检测和流式 batch 边界；
- 安装清单、split checksum、KV 内存计算；
- 首块输出后禁止透明 fallback；
- Room migration。

### 18.2 Instrumentation/native 集成测试

覆盖：

- JNI schema handshake；
- API 24/25 类加载；
- CPU 短生成；
- prefill/decode 取消；
- CJK/emoji 跨 batch UTF-8；
- 两轮 KV reuse；
- OpenCL 探测成功、普通失败、超时、辅助进程死亡；
- 云端切换、后台、trim-memory 释放；
- thermal stop；
- 释放前后 PSS；
- 4 KiB 与 16 KiB 页设备。

### 18.3 CI 与真机

CI：

1. JVM 测试和静态检查；
2. 构建 pinned native；
3. 校验 SHA、build ID、ABI、`PT_LOAD p_align >= 0x4000`；
4. 组装 APK/AAB；
5. 校验 ZIP/native alignment；
6. API 24、29、31、34、35+ emulator smoke；
7. 发布 native manifest/SBOM。

本机受 JDK 8 与项目 JDK 17+ 不兼容约束，不运行 Gradle；本地使用静态审查和 native/ELF 验证，Gradle 与设备测试由 CI/合适环境执行。

真机至少覆盖高通新旧平台、联发科/Mali、Exynos、API 24–28、29–30、31–34、Android 15 16 KiB 页，以及 6/8/12GB RAM。每组记录冷/热加载、首/二轮、TTFT、prefill、decode、PSS、温度、取消和 fallback。

## 19. 分阶段交付

### 阶段 0：构建溯源与基线

- pinned MNN 构建、16 KiB、native manifest/runtime-info；
- 基础 telemetry 和本地基准；
- 记录现有 CPU/OpenCL 基线。

### 阶段 1：流式与 KV 热路径

- native batching、删除最终全文返回；
- 单一 Kotlin 累积、增量剧本检测、渲染时装饰；
- `modelContent` 和 Room migration；
- 2048 默认输出、完成原因、PromptWindowPlanner。

### 阶段 2：双模式与执行计划

- 模式设置/UI；
- `ResolvedInferencePlan`；
- 显式 CPU 优化/兼容尝试；
- generation-scoped power 与 thermal downgrade。

### 阶段 3：OpenCL 探测和持久健康

- 辅助探测进程；
- 设备/runtime/model 指纹；
- crash journal、黑名单与失效；
- 首块输出后禁止透明 fallback；
- 标准版 QNN 禁用。

### 阶段 4：模型与资源准入

- 模型包/校验和；
- 存储合并空间；
- RAM/context 准入；
- 驻留与 trim-memory。

### 阶段 5：实验优化

在稳定基准基础上逐项评估 lookahead、chunking、KV/动态量化、disk cache、线程和离线量化模型。

## 20. 验收标准

- 新安装默认综合平衡，用户可切最高速度；
- 未知设备始终能走 CPU 兼容路径；
- OpenCL 未验证时不会直接承担正式生成；
- QNN 不进入标准版 AUTO；
- 所有打包 `.so` 具备 16 KiB 对齐和可查构建来源；
- 512 token 回复的 JNI 回调次数较现状显著下降，目标至少 80%；
- 首块仍立即可见，CJK/emoji 无损；
- 正常第二轮 `reuse_kv=1`，且只 prefill 新增轮次；
- 无 GPU 部分输出与 CPU 重试内容拼接；
- 残缺模型和明显内存/存储不足在 native 调用前被拦截；
- sustained mode、线程优先级和 hint session 在所有退出路径恢复；
- API 24+ 不发生因新 API 直接引用导致的类加载错误；
- Android 15 16 KiB 页设备可加载；
- 每轮能解释实际模式、后端与降级原因；
- 实验优化没有基准与正确性证据时保持关闭。
