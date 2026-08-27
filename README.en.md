# Chat by your side

**Languages: [简体中文](./README.md) · [English](./README.en.md)**

> An Apple-style liquid-glass **on-device LLM** roleplay chat app for Android (MNN local inference + cloud dual engine) · 一款苹果风液态玻璃 UI 的安卓 **本地 AI 角色扮演聊天**应用，端侧 MNN 大模型推理 + 云端双引擎

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![MNN](https://img.shields.io/badge/Local%20LLM-MNN-00C4A7?logo=alibabacloud)](https://github.com/alibaba/MNN)
[![Seedance](https://img.shields.io/badge/Video-Seedance%202.0-8A2BE2)](https://www.volcengine.com/product/video)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

<p align="center">
<img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-00-514_com chatbyyou" src="https://github.com/user-attachments/assets/12a05e87-fce0-4485-a3dd-179fec9db647" />
</p>

---

## ✨ What's New

- **🧠 On-device MNN adaptive inference engine** — auto CPU/OpenCL-GPU/QNN-NPU scheduling with per-message fallback chains, self-healing GPU health and one-tap preheat. Fully offline — your data never leaves the device.
  **端侧 MNN 自适应推理引擎** — CPU / OpenCL GPU / QNN NPU 三后端自适应调度、自动回退链、GPU 自愈健康、一键预热。完全离线推理，数据不出设备。
- **🩹 Local inference stability fix** — Fixed a native SIGSEGV crash when running the local MNN model: generation reverted to the stable JNI entry, plus hard download-integrity checks (missing weights / truncated files caught before load).
  **本地推理稳定性修复** — 修复本地 MNN 大模型「运行一会后闪退」的 native SIGSEGV：生成路径回退到稳定 JNI 入口；模型下载增加完整性硬校验。
- **🚀 Local deep thinking** — Thinking-depth levels (AUTO / SHORT / MEDIUM / LONG) with byte-budget control; reasoning rendered as collapsible blocks.
  **本地深度思考** — 思考分级 + 字节预算截断，推理过程以可折叠「思考过程」块展示。
- **🎬 Seedance character video** — Auto-generate a short Seedance video of your character acting out the reply — with a full Encounter timeline, playback, export and history.
  **角色视频生成 · Seedance** — 聊天回复自动触发角色短片生成（「邂逅」时间线：播放 / 导出 / 历史）。
  <img width="1080" height="2400" alt="Screenshot_2026-08-15-10-22-24-324_com chatbyyou" src="https://github.com/user-attachments/assets/5d365711-b091-40dc-8068-30d11643f77f" /> <img width="1080" height="2400" alt="Screenshot_2026-08-15-10-22-17-079_com chatbyyou" src="https://github.com/user-attachments/assets/ea74ffbb-ce8d-47dd-8431-64cddc73922d" />
- **🔊 Dual TTS engines** — Offline system TTS (default) plus Volcengine Doubao cloud voice cloning — per-character voices.
  **双 TTS 引擎** — 系统离线 TTS（默认）+ 火山引擎豆包云端声音复刻（每角色独立音色）。
- **🛡️ Memory admission & benchmark certification** — No OOM: context auto-downgrades to fit memory. Experimental accelerations only turn on after on-device benchmarks certify them.
  **内存准入 + 基准认证** — 大模型不 OOM：内存不足自动降上下文；实验性加速必须经设备端基准测试认证。
- **💬 Chat reliability** — Thinking-stream rendering, user-controlled bottom-following, stop preserves partial output, first answer never flickers away.
  **聊天可靠性重构** — 思考流渲染、用户控制底部跟随、「停止」保留部分输出、首答不再闪烁消失。
- **💝 Affinity / bond system** — Daily check-in coins, a user-authored gift economy with in-chat AI thank-yous, per-character affinity 0–200, and affinity-threshold special events bridged into real conversations — custom characters' event scripts are editable.
  **好感度 / 羁绊系统** — 每日签到领金币 + 自定义礼物经济 + 每角色好感度 0–200 + 好感阈值解锁「特殊邂逅」剧情。
- **📤 Conversation export** — Export a conversation as TXT or PNG (single long image or auto-paginated pages) via the system Storage Access Framework.
  **对话导出** — 把聊天记录导出为 TXT 或 PNG 长图 / 自动分页图片。

---

## 🧠 On-device Local LLM

> **Local model code source**: The on-device local model inference code in this app is built on **[Alibaba MNN](https://github.com/alibaba/MNN)** (an open-source project, MIT License) — the loading and inference implementations for local models originate from this open-source project.
>
> A full engineering loop built on MNN: device capability probing, memory admission, backend scheduling, self-healing health, benchmark certification and performance telemetry. All inference runs on-device — **conversation data never leaves your phone**.

### Adaptive backend scheduling

- **Auto backend selection among three backends**: `CPU` / `OpenCL GPU` / `QNN NPU`. The system recommends a primary backend based on device capability (SoC tier, number of big cores, total RAM, NPU support) and builds a per-message fallback chain from "user preference × model size × GPU readiness"; empty GPU output / load failure automatically falls back to CPU, which is always the final fallback.
- **Large-model GPU admission**: under AUTO mode, only models with **> 7B** total parameters attempt GPU (OpenCL); models ≤ 7B stay on CPU by default to avoid unnecessary GPU overhead for small models.

### Self-healing GPU health

- **Isolated-process OpenCL probing**: OpenCL is actually exercised in a separate `:mnn_probe` process (15s timeout), so a GPU crash can never take down the main process; results are reported back over a file channel.
- **Health state machine**: per "device × model × backend × variant" records of probe-ok / model-ok / cooldown / crash-blacklist; fingerprints auto-expire when the device, OS, model or native stack changes.
- **One-tap GPU preheat**: manually preload a >7B model and run one very short ≤8-token generation to precompile the OpenCL kernel cache, dramatically cutting first-message TTFT.
- **Idle-time probing**: lightweight OpenCL probing runs on demand while the app is idle in the foreground, so GPU admission is ready before your first message.

### Memory admission

- Every local message is admission-checked against system memory + process PSS; when memory is tight, the context **auto-halves per turn (down to 512)** instead of OOM-crashing or erroring, and user settings are never modified.
- KV-cache memory is estimated precisely from the model architecture (GQA-aware) and shown live next to the context slider.
- Real peak PSS is sampled and fed back to keep admission self-calibrating.

### Benchmark & certification

- **Six benchmark scenarios**: cold load / short TTFT / long prefill / fixed decode / second-turn KV reuse / empty-response check, covering the **CPU × GPU × thinking-toggle** four quadrants, with P95 stats, thermal rejection and reliability runs.
- **On-device certification gate**: experimental features such as lookahead and multi-token decoding **must** prove ≥10% decode improvement on real hardware — with no significant TTFT/PSS regression and all samples genuinely landing on GPU — before being written to DataStore and enabled.
- Certification status and latest verdicts are surfaced in the diagnostics page of the "inference engine" settings.

### Local deep thinking

- Thinking levels **AUTO / SHORT / MEDIUM / LONG** (local models only); AUTO classifies by question complexity.
- The thinking section has a soft target duration and a hard byte budget; over-budget output is truncated and "coalesced into a direct answer" rather than stalling the whole generation.
- Whether the toggle actually works is gated by **chat-template capability probing** (no false positive when the template has no `enable_thinking` branch).

### Performance & telemetry

- Two performance modes: **Balanced** (stable decoding, default) and **Maximum Speed** (peak decode throughput, auto-degrades on overheating / memory pressure).
- Non-root CPU boost (PerformanceHint API 31+ + thread priority + Sustained Performance Mode), thermal-aware thread throttling (halved on moderate heat / 2 threads when severe / 1 thread when critical), and big-core topology-aware thread counts.
- A liquid-glass **performance overlay** monitors token/s, CPU / GPU / NPU, temperature and memory in real time; each generation emits structured telemetry (load time, TTFT, prefill/decode, KV reuse, fallback chain, downgrade reasons, thinking classification).

### Local model management

- Built-in catalog of **13 MNN models** (no network model marketplace) with download / pause-resume / delete / switch, multi-file chunk merge + SHA-256 verification.
- Multi-mirror download fallback: **ModelScope (CN) → hf-mirror → HuggingFace**.
- Deleting / switching the active model releases the MNN native handle immediately (safely deferred while generating).

### Supported local models

| Model | Params | Size | Tags |
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

> ⭐ = officially recommended. Context length 512–32768 adjustable; max output 1024 / 2048 / 4096 / unlimited.

---

## Features

- **Liquid / frosted-glass UI**
  Reference to Cresto's Glasense design language: ice-blue Iris primary + neutral frosted-glass layering + a dynamic gradient-mesh background.<img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-03-339_com chatbyyou" src="https://github.com/user-attachments/assets/9db045f9-e7e0-4792-ad6b-8fe6ef6cead8" />

- **50 original character archetypes**
  Mixed-gender archetypes (tsundere, yandere, kouhai, butler, CEO, knight, villain…) each with a full system prompt. Create / import / export custom characters. All characters are original — no third-party copyrighted characters.
  <img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-04-991_com chatbyyou" src="https://github.com/user-attachments/assets/2f6457c6-96e5-4184-b3a7-c41dd0eb36b7" />

- **Cloud + local dual engine**
  Switch between a cloud OpenAI-compatible API (SSE streaming) and on-device MNN offline inference. Conversations are saved per character; custom cloud endpoints auto-detect both OpenAI and Anthropic request formats with user-defined base URL and model (full endpoints are accepted without duplicate path joining).

- **Built-in free cloud**
  A "Free Chat" provider (SiliconFlow free 7B, incl. a free DeepSeek-R1-7B reasoning model) that works out of the box with no API key; the key is injected by a Cloudflare server-side proxy, never embedded in the app or repo.

- **🎬 Character video generation**
  Auto-generate a Seedance 2.0 video of your character after a reply — director-style prompt generation, reference snapshots, submit/query/download pipeline, playback & export. Volcengine Ark and media-relay protocols; bounded retries with cost-confirmation before fee-bearing regeneration.

- **🔊 Dual TTS engines**
  Offline system TTS (default) + Volcengine Doubao cloud voice cloning (direct to the official endpoint, per-character zh/ja speaker IDs, zh/ja support). `<think>` blocks are stripped before reading; video playback pauses/resumes TTS.

- **🚀 Deep thinking / reasoning trace**
  Render and collapse the model's reasoning trace — with local-only thinking levels and budgets.

- **Music playback**
  Online playback via Netease Cloud Music search plus local file import, with seek / volume / lyrics.
  <img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-11-452_com chatbyyou" src="https://github.com/user-attachments/assets/d62e5ac6-a109-4b40-8f8c-72362400f228" />

- **Multimodal chat**
  Send images (up to 3), PDFs (first 6 pages), or text files straight to a multimodal model.

- **Performance overlay**
  Real-time liquid-glass overlay monitoring token/s, CPU, GPU, NPU, temperature, and memory during local inference.

- **Proactive character greetings**
  WorkManager-scheduled self-continuing notification chain — characters message you on their own, surviving app restarts (cloud only).

- **Chat polish**
  Throttled thinking-stream rendering, user-controlled bottom-following, stop preserves partial output, first-answer row-ID reconciliation.

- **💝 Affinity / bond system**
  Daily check-in coins, a user-authored gift shop (create → buy → send in chat → AI thank-you on the gift wall), per-character affinity 0–200 with an idempotent ledger, and affinity-threshold special events that launch real cloud-only conversations. Built-in characters ship with 4 original scripts each; custom characters' scripts are editable.

- **📤 Conversation export**
  Export any conversation as a full TXT log or a chat-wallpaper-style PNG (auto-paginated or single tall image), saved anywhere via the system Storage Access Framework.

## Tech Stack

| Category | Technology |
| --- | --- |
| Language | Kotlin 2.0.0 |
| UI | Jetpack Compose (Material3, BOM 2024.06) |
| Build | AGP 8.5.0, JDK 17+, compileSdk/targetSdk 34, minSdk 24 |
| **Local inference** | **MNN adaptive engine** (CPU / OpenCL GPU / QNN NPU), arm64-v8a only · NDK 27 prebuilt libs (`libMNN.so` + `libmnn_jni.so` + QNN series + `libcpu_sys_jni.so`) |
| **Thinking** | Local thinking levels + byte budgets + chat-template capability probing + incremental output classification |
| **Benchmark** | Six scenarios × four quadrants, DataStore certification store, on-device certification gate for experimental features |
| **Video** | Seedance 2.0 (Volcengine Ark / media-relay protocols), storyboard prompt generation, WorkManager pipeline, ExoPlayer playback |
| **TTS** | Android system TTS + Volcengine Doubao voice cloning |
| Data | Room 2.6.1, DataStore 1.1.1 |
| Network | Retrofit 2.11, OkHttp 4.12 |
| Media | Media3 1.3.1 (ExoPlayer), Coil 2.6 |
| Async | Coroutines 1.8.1, Serialization 1.6.3 |
| Background | WorkManager 2.9.1 |

## Project Structure

```
com.chatbyyourside/
├── config/          # App config, persona table, model providers, asset paths
├── data/            # model / local(Room,DataStore) / remote(Retrofit, Netease Cloud, Seedance) / repository
├── llm/             # ★ Local LLM core: backend(CPU/GPU/NPU scheduling, health, preheat), benchmark(benchmark+certification),
│                    #   metrics(telemetry), profile(perf mode/execution plan), template(template capability probing), thinking(levels)
├── provider/        # Chat provider (cloud / local) abstraction and switching
├── tts/             # Dual TTS engines (system TTS + Volcengine Doubao)
├── video/           # Seedance video pipeline: prompt generation, validation, state machine, reference/scene storage, export
├── download/        # MNN multi-mirror model download (resume, SHA-256)
├── manager/         # Audio / Model / Tts managers
├── perfmon/         # Liquid-glass performance overlay
├── notification/    # Proactive character greeting notifications
├── work/            # WorkManager scheduling (greeting chain / Seedance video pipeline)
├── ui/              # glass components, chat / characters / feed / music / models / settings / theme / video / navigation
└── util/            # Utilities
```

## Build

### Prerequisites

- Android SDK (compileSdk 34)
- NDK 27.2.12479018 (declared via AGP toolchain, matching `ndkVersion` in `app/build.gradle.kts`)
- JDK 17+

### Notes

- **Native libraries are prebuilt** and bundled in `app/src/main/jniLibs/arm64-v8a/` (`libMNN.so`, QNN series, `libmnn_jni.so`, `libcpu_sys_jni.so`). Gradle no longer invokes CMake, so **no `MNN_DIR` is required** for a normal build.
- To recompile the native libs, follow the comment block in `app/build.gradle.kts` and build `app/src/main/cpp/CMakeLists.txt` manually, then copy the `.so` into `jniLibs/arm64-v8a`.
- Only `arm64-v8a` is packaged, matching the prebuilt MNN/QNN libraries.

### Commands

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (debug-signed by default; configure your own signing before publishing)
./gradlew :app:assembleRelease
```

## Assets

- `app/src/main/assets/characters/` — AI-generated art for the 50 original archetypes. Generation prompts are in [`docs/character-art-prompts.md`](docs/character-art-prompts.md).
- UI design mockups are in [`docs/preview/`](docs/preview/).

## License

[MIT](LICENSE). All built-in characters and art are original — no third-party copyrighted characters.
