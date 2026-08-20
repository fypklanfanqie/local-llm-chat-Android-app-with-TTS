# Chat by your side

> 一款苹果风液态玻璃 UI 的安卓 **本地 AI 角色扮演聊天**应用，端侧 MNN 大模型推理 + 云端双引擎 · An Apple-style liquid-glass **on-device LLM** roleplay chat app for Android (MNN local inference + cloud dual engine)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![MNN](https://img.shields.io/badge/Local%20LLM-MNN-00C4A7?logo=alibabacloud)](https://github.com/alibaba/MNN)
[![Seedance](https://img.shields.io/badge/Video-Seedance%202.0-8A2BE2)](https://www.volcengine.com/product/video)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

<p align="center">
<img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-00-514_com chatbyyou" src="https://github.com/user-attachments/assets/12a05e87-fce0-4485-a3dd-179fec9db647" />
</p>

---

## ✨ 新功能速览 · What's New

- **🧠 端侧 MNN 自适应推理引擎** — CPU / OpenCL GPU / QNN NPU 三后端自适应调度、自动回退链、GPU 自愈健康、一键预热。完全离线推理，数据不出设备。
  **On-device MNN adaptive inference** — auto CPU/OpenCL-GPU/QNN-NPU scheduling with fallback chains, self-healing GPU health and one-tap preheat. Fully offline.
- **🚀 本地深度思考 · Local deep thinking** — 思考分级（AUTO / SHORT / MEDIUM / LONG）＋字节预算截断，推理过程以可折叠「思考过程」块展示。
  Thinking-depth levels with byte-budget control; reasoning rendered as collapsible blocks.
- **🎬 角色视频生成 · Seedance video** — 聊天回复自动触发角色短片生成（「邂逅」时间线：播放 / 导出 / 历史），自定义参考图与场景。
  Auto-generate a short Seedance video of your character acting out the reply — with a full Encounter timeline, playback, export and history.
  <img width="1080" height="2400" alt="Screenshot_2026-08-15-10-22-24-324_com chatbyyou" src="https://github.com/user-attachments/assets/5d365711-b091-40dc-8068-30d11643f77f" /> <img width="1080" height="2400" alt="Screenshot_2026-08-15-10-22-17-079_com chatbyyou" src="https://github.com/user-attachments/assets/ea74ffbb-ce8d-47dd-8431-64cddc73922d" />
- **🔊 双 TTS 引擎 · Dual TTS** — 系统离线 TTS（默认）＋ 火山引擎豆包云端声音复刻（每角色独立音色）。
  Offline system TTS (default) plus Volcengine Doubao cloud voice cloning — per-character voices.
- **🛡️ 内存准入 + 基准认证 · Memory admission & benchmark certification** — 大模型不 OOM：内存不足自动降上下文；实验性加速（lookahead / 多 token 解码）必须在设备端基准测试中证明收益后才启用。
  No OOM: context auto-downgrades to fit memory. Experimental accelerations only turn on after on-device benchmarks certify them.
- **💬 聊天可靠性重构 · Chat reliability** — 思考流渲染、用户控制底部跟随、「停止」保留部分输出、首答不再闪烁消失。
  Thinking-stream rendering, user-controlled bottom-following, stop preserves partial output, first answer never flickers away.
- **💝 好感度 / 羁绊系统 · Affinity system** — 每日签到领金币 + 自定义礼物经济（造礼物 → 采购 → 聊天中赠送 → 角色 AI 当面道谢）+ 每角色好感度 0–200 + 好感阈值解锁「特殊邂逅」剧情（50 / 100 / 150 / 200，桥接为真实聊天会话）；自定义角色的特殊邂逅可自编辑文案。
  Daily check-in coins, a user-authored gift economy with in-chat AI thank-yous, per-character affinity 0–200, and affinity-threshold special events bridged into real conversations — custom characters' event scripts are editable.
- **📤 对话导出 · Conversation export** — 把聊天记录导出为 TXT 或 PNG 长图 / 自动分页图片（系统 SAF 保存）。
  Export a conversation as TXT or PNG (single long image or auto-paginated pages) via the system Storage Access Framework.

---

## 🧠 本地 LLM 推理 · On-device Local LLM

> 基于 [MNN](https://github.com/alibaba/MNN) 的自适应端侧推理栈：从设备能力探测、内存准入、后端调度、健康自愈，到基准认证、性能遥测的一整套工程化闭环。全部推理在设备本地完成，**对话数据不离开手机**。

### 自适应后端调度 · Adaptive backend scheduling

- **三后端自动选择**：`CPU` / `OpenCL GPU` / `QNN NPU`。系统根据设备能力（SoC 芯片等级、CPU 大核数、总内存、NPU 支持）推荐首选后端，并按「用户偏好 × 模型大小 × GPU 就绪度」生成每条消息的回退尝试链；GPU 空输出 / 加载失败会自动回退 CPU，CPU 永远是最终兜底。
  Auto backend selection with per-message fallback chains; empty GPU output falls back to CPU.
- **大模型 GPU 准入**：AUTO 模式下总参数量 **> 7B** 的模型才尝试 GPU（OpenCL），≤ 7B 默认走 CPU，避免小模型在 GPU 上的无谓开销。
  Models > 7B params are GPU-eligible under AUTO; smaller ones stay on CPU.

### GPU 自愈健康 · Self-healing GPU health

- **隔离进程 OpenCL 探测**：在独立 `:mnn_probe` 进程中真正执行 OpenCL（15s 超时），主进程永远不被 GPU 崩溃拖垮；支持文件通道跨进程回传。
  OpenCL is exercised in an isolated process so a GPU failure can never crash the app.
- **健康状态机**：每个「设备 × 模型 × 后端 × 变体」维护 probe-ok / model-ok / 冷却 / 崩溃黑名单记录；失败自动冷却或拉黑，设备 / 系统 / 模型 / native 栈变化后指纹自动过期。
  Per-device×model×backend health records with cooldown/blacklist; fingerprints auto-expire on changes.
- **一键 GPU 预热**：手动按钮预载 >7B 模型并跑一次 ≤8 token 的极短生成，预编译 OpenCL kernel 缓存，显著降低首条消息 TTFT。
  One-tap GPU preheat precompiles OpenCL kernels and slashes first-token latency.
- **空闲探测**：App 在前台空闲时按需跑轻量 OpenCL 探测，GPU 准入在你说第一句话之前就已就绪。
  Idle-time OpenCL probing keeps GPU admission ready before your first message.

### 内存准入 · Memory admission

- 每条本地消息生成前检查系统内存 + 进程 PSS；内存不足时**自动按轮减半上下文（最低 512）**而**不崩溃、不报错**，且不改动用户设置。
  Every local message is admission-checked; context auto-halves (down to 512) instead of OOM-crashing.
- KV 缓存按模型架构精确估算（GQA 感知），上下文滑杆旁实时显示对应内存占用。
  KV-cache memory is estimated from the model architecture and shown live next to the context slider.
- 进程真实峰值 PSS 被采样回灌，后续准入不断自我校准。
  Real peak PSS is measured and fed back to keep admission calibrated.

### 基准测试与认证 · Benchmark & certification

- **六场景基准**：冷加载 / 短 TTFT / 长 prefill / 固定 decode / 二轮 KV 复用 / 空响应检查，覆盖 **CPU × GPU × 思考开关** 四象限，P95 统计、热拒绝与可靠度运行。
  Six benchmark scenarios across the four backend×thinking quadrants with P95 stats and thermal rejection.
- **设备端认证门**：lookahead、多 token 解码等实验特性**必须**在真机上证明 ≥10% decode 提升、无 TTFT/PSS 明显回退、全部样本真实落在 GPU 上，才写入 DataStore 认证并被启用。
  Experimental features only enable after on-device benchmarks certify them.
- 认证状态与最近结论直接显示在「推理引擎设置」的诊断页。
  Certification status and verdicts are surfaced in the backend-settings diagnostics.

### 本地深度思考 · Local deep thinking

- 思考分级 **AUTO / SHORT / MEDIUM / LONG**（仅本地模型生效），AUTO 按问题复杂度自动分级。
  Local thinking levels with auto classification by question complexity.
- 思考区有软目标时长与硬字节预算；超预算自动截断并「合并直接作答」，不拖死整轮生成。
  The `<think>` section is byte-budgeted — overflow truncates and coalesces into a direct answer.
- 思考开关有效性由**聊天模板能力探测**判定（模板无 `enable_thinking` 分支时不会误报可用）。
  Whether the toggle actually works is gated by the model's chat-template capabilities.

### 性能与遥测 · Performance & telemetry

- 两种性能模式：**综合平衡**（稳定解码，默认）与**最高速度**（最大解码吞吐，过热 / 内存吃紧自动降级）。
  Balanced vs. Maximum-Speed performance modes.
- 非 root CPU 提速（PerformanceHint API 31+ + 线程优先级 + Sustained Performance Mode）、热感应降线程（中热减半 / 严重 2 线程 / 危急 1 线程）、大核拓扑感知选线程数。
  Non-root CPU boost, thermal-aware thread throttling, and big-core topology detection.
- 液态玻璃**性能浮窗**实时监控 token/s、CPU / GPU / NPU、温度、内存；每轮推理生成结构化遥测（加载耗时、TTFT、prefill/decode、KV 复用、回退链、降级原因、思考分类）。
  Real-time liquid-glass overlay + per-turn structured telemetry with downgrade reasons.

### 本地模型管理 · Local model management

- 内置 **13 款 MNN 模型清单**（无网络模型市场），支持下载 / 暂停续传 / 删除 / 切换，多文件分块合并 + SHA-256 校验。
  Built-in catalog of 13 MNN models with pause/resume download, chunk merge and SHA-256 verify.
- 下载多镜像自动回退：**ModelScope（国内）→ hf-mirror → HuggingFace**。
  Multi-mirror fallback: ModelScope → hf-mirror → HuggingFace.
- 删除 / 切换活动模型即时释放 MNN native 句柄（生成中安全延迟释放）。
  Active-model switch/delete releases the native handle safely.

### 支持的本地模型 · Local model catalog

| 模型 · Model | 参数量 · Params | 体积 · Size | 标签 · Tags |
| --- | --- | --- | --- |
| Qwen3.5-0.8B-MNN | 0.8B | ~522 MB | Think + Vision |
| **Qwen3.5-2B-MNN** ⭐ | 2B | ~1.29 GB | Think + Vision |
| **Qwen3.5-4B-MNN** ⭐ | 4B | ~2.65 GB | Think + Vision |
| Qwen3.5-9B-MNN | 9B | ~6.78 GB | Think + Vision |
| Qwen3.5-35B-A3B-MNN | 35B (MoE) | ~21.2 GB | Think + Vision |
| DeepSeek-R1-1.5B-Qwen-MNN | 1.5B | ~1.0 GB | Think |
| Qwen3-4B-MNN | 4B | ~2.7 GB | Think |
| DeepSeek-R1-7B-Qwen-MNN | 7B | ~4.6 GB | Think |
| DeepSeek-R1-0528-Qwen3-8B-MNN | 8B | ~5.5 GB | Think |
| Llama-3.2-1B-Instruct-MNN | 1B | ~1.0 GB | Chat |
| Llama-3.2-3B-Instruct-MNN | 3B | ~3.0 GB | Chat |
| gemma-2-2b-it-MNN | 2B | ~2.0 GB | Chat |
| SmolLM2-360M-Instruct-MNN | 360M | ~0.4 GB | Chat |

> ⭐ = 官方推荐 · officially recommended. 上下文长度 512–32768 可调，最大输出 1024 / 2048 / 4096 / 不限。

---

## 特性 · Features

- **液态玻璃 / 毛玻璃 UI** · **Liquid / frosted-glass UI**
  参考 [Cresto](https://github.com/Nevodev/Cresto) 的 Glasense 设计语言：冰蓝 Iris 主色 + 中性毛玻璃叠层 + 动态渐变网格背景。
  An Iris-tinted liquid-glass design language with frosted-glass layering and a dynamic gradient mesh background.<img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-03-339_com chatbyyou" src="https://github.com/user-attachments/assets/9db045f9-e7e0-4792-ad6b-8fe6ef6cead8" />

- **50 位原创人设** · **50 original character archetypes**
  男女混合的热门原型（傲娇、病娇、学姐、管家、霸总、骑士、反派……），每位带完整人格 system prompt；支持新建 / 导入 / 导出自定义角色。所有角色均为原创原型，无任何第三方版权角色。
  Mixed-gender archetypes (tsundere, yandere, kouhai, butler, CEO, knight, villain…) each with a full system prompt. Create / import / export custom characters. All characters are original — no third-party copyrighted characters.
  <img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-04-991_com chatbyyou" src="https://github.com/user-attachments/assets/2f6457c6-96e5-4184-b3a7-c41dd0eb36b7" />

- **云端 + 本地双引擎** · **Cloud + local dual engine**
  云端 OpenAI 兼容 API（SSE 流式）与本地 MNN 离线推理一键切换，对话按角色独立保存；自定义云端 LLM 支持 **OpenAI（/chat/completions）与 Anthropic（/v1/messages）双协议自动识别**、可填任意 base URL 与模型名（Base URL 也支持直接填完整端点，不会重复拼接）。
  Switch between a cloud OpenAI-compatible API (SSE streaming) and on-device MNN offline inference. Conversations are saved per character; custom cloud endpoints auto-detect both OpenAI and Anthropic request formats with user-defined base URL and model (full endpoints are accepted without duplicate path joining).

- **内置免费云端 · Built-in free cloud**
  内置「免费对话」供应商（硅基流动免费 7B 模型，含 DeepSeek-R1-7B 免费推理模型），开箱即用、无需 API Key；Key 由 Cloudflare 云端代理注入，App 端与仓库均不含明文 Key。
  Built-in "Free Chat" provider (SiliconFlow free 7B, incl. a free DeepSeek-R1-7B reasoning model) — works out of the box with no API key; the key is injected by a Cloudflare server-side proxy, never embedded in the app or repo.

- **🎬 角色视频生成 · Character video generation**
  对话回复完成后自动触发 Seedance 2.0 短片生成：LLM 生成导演级分镜提示词 → 角色立绘 / 背景参考图快照 → 提交 / 轮询 / 下载 → 校验后「邂逅」时间线播放与导出。同时支持火山方舟与媒体中继协议；失败自动有界重试，**计费 POST 永不自动重发**，重试前需二次确认。
  Auto-generate a Seedance 2.0 video of your character after a reply — director-style prompt generation, reference snapshots, submit/query/download pipeline, playback & export. Volcengine Ark and media-relay protocols; bounded retries with cost-confirmation before fee-bearing regeneration.

- **🔊 双 TTS 语音合成 · Dual TTS engines**
  **系统离线 TTS**（默认，免配置，按语言 + 音色模板选声）+ **火山引擎豆包云端声音复刻**（**直连官方接口**，每角色分别配置中/日 speaker_id，支持中/日文）。朗读时自动剥离 `<think>` 思考块；视频播放时自动暂停 / 恢复 TTS。
  Offline system TTS (default) + Volcengine Doubao cloud voice cloning (direct to the official endpoint, per-character zh/ja speaker IDs, zh/ja support). `<think>` blocks are stripped before reading; video playback pauses/resumes TTS.
  
- **🚀 深度思考 / 推理过程** · **Deep thinking / reasoning trace**
  展示并折叠模型推理过程（本地与云端均可）；本地端支持思考分级与预算控制。
  Render and collapse the model's reasoning trace — with local-only thinking levels and budgets.

- **音乐播放** · **Music playback**
  网易云音乐搜索在线播放 + 本地音乐导入，支持进度 / 音量 / 歌词。
  Online playback via Netease Cloud Music search plus local file import, with seek / volume / lyrics.
  <img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-11-452_com chatbyyou" src="https://github.com/user-attachments/assets/d62e5ac6-a109-4b40-8f8c-72362400f228" />

- **多模态对话** · **Multimodal chat**
  图片（最多 3 张）、PDF（前 6 页）、纯文本文件直连多模态模型。
  Send images (up to 3), PDFs (first 6 pages), or text files straight to a multimodal model.

- **性能浮窗** · **Performance overlay**
  本地推理时实时监控 Token 速率 / CPU / GPU / NPU / 温度 / 内存，液态玻璃风格浮窗。
  Real-time liquid-glass overlay monitoring token/s, CPU, GPU, NPU, temperature, and memory during local inference.

- **角色主动问候** · **Proactive character greetings**
  WorkManager 调度的自续链通知，角色会在你离开后主动发来消息（跨重启存活，限云端）。
  WorkManager-scheduled self-continuing notification chain — characters message you on their own, surviving app restarts (cloud only).

- **聊天体验打磨** · **Chat polish**
  思考流 30fps 节流渲染、用户控制底部跟随（上滑暂停 + 回到底部按钮）、停止生成保留部分输出并标注状态、首答 Room 行号对账不再闪烁消失。
  Throttled thinking-stream rendering, user-controlled bottom-following, stop preserves partial output, first-answer row-ID reconciliation.

- **💝 好感度 / 羁绊系统** · **Affinity / bond system**
  每日签到领金币（冷启动自动弹窗）；用户自制礼物档案 → 金币采购 → 聊天中赠送 → 角色 AI 生成当面道谢并记入礼物墙；每角色好感度 0–200（聊天 / 视频 / 送礼累加，幂等账本防重复刷分）；好感跨档解锁「特殊邂逅」剧情并桥接为真实聊天会话（仅云端、禁视频）。**内置 50 角色各配 4 档原创剧情文案；自定义角色的剧情文案可在档案页自编辑。**
  Daily check-in coins, a user-authored gift shop (create → buy → send in chat → AI thank-you on the gift wall), per-character affinity 0–200 with an idempotent ledger, and affinity-threshold special events that launch real cloud-only conversations. Built-in characters ship with 4 original scripts each; custom characters' scripts are editable.

- **📤 对话导出** · **Conversation export**
  把任意会话导出为 TXT 完整记录，或渲染成 PNG（自动分页多张 / 单张超长图，Canvas 直绘聊天壁纸风），经系统 SAF 保存到任意位置。
  Export any conversation as a full TXT log or a chat-wallpaper-style PNG (auto-paginated or single tall image), saved anywhere via the system Storage Access Framework.

## 技术栈 · Tech Stack

| 分类 · Category | 技术 · Technology |
| --- | --- |
| 语言 · Language | Kotlin 2.0.0 |
| UI | Jetpack Compose (Material3, BOM 2024.06) |
| 构建 · Build | AGP 8.5.0, JDK 17+, compileSdk/targetSdk 34, minSdk 24 |
| **本地推理 · Local inference** | **MNN 自适应引擎**（CPU / OpenCL GPU / QNN NPU），arm64-v8a only · NDK 27 预编译库（`libMNN.so` + `libmnn_jni.so` + QNN 系列 + `libcpu_sys_jni.so`） |
| **深度思考 · Thinking** | 本地思考分级 + 字节预算 + 聊天模板能力探测 + 增量输出分类 |
| **基准认证 · Benchmark** | 六场景四象限基准、DataStore 认证存储、实验特性设备端认证门 |
| **视频生成 · Video** | Seedance 2.0（火山方舟 / 媒体中继协议）、分镜提示词生成、WorkManager 管线、ExoPlayer 播放 |
| **TTS · Text-to-speech** | Android 系统 TTS ＋ 火山引擎豆包声音复刻 |
| 数据 · Data | Room 2.6.1, DataStore 1.1.1 |
| 网络 · Network | Retrofit 2.11, OkHttp 4.12 |
| 媒体 · Media | Media3 1.3.1 (ExoPlayer), Coil 2.6 |
| 异步 · Async | Coroutines 1.8.1, Serialization 1.6.3 |
| 后台 · Background | WorkManager 2.9.1 |

## 项目结构 · Project Structure

```
com.chatbyyourside/
├── config/          # 应用配置、人设表、模型 Provider、资源路径
├── data/            # model / local(Room,DataStore) / remote(Retrofit,网易云,Seedance) / repository
├── llm/             # ★ 本地 LLM 核心：backend(CPU/GPU/NPU 调度、健康、预热)、benchmark(基准+认证)、
│                    #   metrics(遥测)、profile(性能模式/执行计划)、template(模板能力探测)、thinking(思考分级)
├── provider/        # 聊天 Provider（cloud / local）抽象与切换
├── tts/             # 双 TTS 引擎（系统 TTS + 火山豆包）
├── video/           # Seedance 视频管线：提示词生成、校验、状态机、参考图/场景存储、导出
├── download/        # MNN 模型多镜像下载（断点续传、SHA-256）
├── manager/         # Audio / Model / Tts 管理器
├── perfmon/         # 液态玻璃性能浮窗
├── notification/    # 角色主动问候通知
├── work/            # WorkManager 调度（问候链 / Seedance 视频管线）
├── ui/              # glass 组件、chat / characters / feed / music / models / settings / theme / video / navigation
└── util/            # 工具类
```

## 构建 · Build

### 环境要求 · Prerequisites

- Android SDK（compileSdk 34）
- NDK 27.2.12479018（AGP 工具链声明，对应 `app/build.gradle.kts` 的 `ndkVersion`）
- JDK 17+

### 说明 · Notes

- **Native 库已预编译**并放入 `app/src/main/jniLibs/arm64-v8a/`（`libMNN.so`、QNN 系列、`libmnn_jni.so`、`libcpu_sys_jni.so`）。Gradle 构建时不再调用 CMake，**无需配置 `MNN_DIR`**。
  **Native libraries are prebuilt** and bundled in `app/src/main/jniLibs/arm64-v8a/`. Gradle no longer invokes CMake, so **no `MNN_DIR` is required** for a normal build.
- 如需重编 native 库，参照 `app/build.gradle.kts` 中的注释，用 `app/src/main/cpp/CMakeLists.txt` 手动编译后拷入 `jniLibs/arm64-v8a`。
  To recompile native libs, follow the comment block in `app/build.gradle.kts` and build `app/src/main/cpp/CMakeLists.txt` manually, then copy the `.so` into `jniLibs/arm64-v8a`.
- 仅打包 `arm64-v8a`：与预编译 MNN/QNN 库架构一致。
  Only `arm64-v8a` is packaged, matching the prebuilt MNN/QNN libraries.

### 命令 · Commands

```bash
# Debug 构建 · Debug build
./gradlew :app:assembleDebug

# Release 构建（默认 debug 签名，发布前请自行配置签名）· Release build (debug-signed by default)
./gradlew :app:assembleRelease
```

## 资源说明 · Assets

- `app/src/main/assets/characters/` — 50 位原创角色的立绘（AI 生成）。生成提示词见 [`docs/character-art-prompts.md`](docs/character-art-prompts.md)。
  Character art (AI-generated) for the 50 original archetypes. Generation prompts are in [`docs/character-art-prompts.md`](docs/character-art-prompts.md).
- UI 设计预览见 [`docs/preview/`](docs/preview/)。
  UI design mockups are in [`docs/preview/`](docs/preview/).

## 许可 · License

[MIT](LICENSE)。内置人设与立绘均为原创，无第三方版权角色。
MIT License. All built-in characters and art are original — no third-party copyrighted characters.
