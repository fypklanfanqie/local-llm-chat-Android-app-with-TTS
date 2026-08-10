# Chat by your side · 语畔拾光
> 一款苹果风液态玻璃 UI 的安卓 AI 角色扮演聊天应用 · An Apple-style liquid-glass AI roleplay chat app for Android

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![MNN](https://img.shields.io/badge/Local%20LLM-MNN-00C4A7?logo=alibabacloud)](https://github.com/alibaba/MNN)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

<p align="center">
<img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-00-514_com chatbyyou" src="https://github.com/user-attachments/assets/12a05e87-fce0-4485-a3dd-179fec9db647" />

</p>

---

## 特性 · Features

- **液态玻璃 / 毛玻璃 UI** · **Liquid / frosted-glass UI**
  参考 [Cresto](https://github.com/Nevodev/Cresto) 的 Glasense 设计语言：冰蓝 Iris 主色 + 中性毛玻璃叠层 + 动态渐变网格背景。
  An Iris-tinted liquid-glass design language with frosted-glass layering and a dynamic gradient mesh background.<img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-03-339_com chatbyyou" src="https://github.com/user-attachments/assets/9db045f9-e7e0-4792-ad6b-8fe6ef6cead8" />


- **50位原创人设** · **20 original character archetypes**
  男女混合的热门原型（傲娇、病娇、学姐、管家、霸总、骑士、反派……），每位带完整人格 system prompt；支持新建 / 导入 / 导出自定义角色。所有角色均为原创原型，无任何第三方版权角色。
  Mixed-gender archetypes (tsundere, yandere, kouhai, butler, CEO, knight, villain…) each with a full system prompt. Create / import / export custom characters. All characters are original — no third-party copyrighted characters.
<img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-04-991_com chatbyyou" src="https://github.com/user-attachments/assets/2f6457c6-96e5-4184-b3a7-c41dd0eb36b7" />

- **云端 + 本地双引擎** · **Cloud + local dual engine**
  云端 OpenAI 兼容 API（SSE 流式）与本地 MNN 离线推理一键切换，对话按角色独立保存。
  Switch between a cloud OpenAI-compatible API (SSE streaming) and on-device MNN offline inference. Conversations are saved per character.

- **TTS 语音合成** · **Text-to-speech**
  火山引擎豆包语音合成 + 声音复刻，角色消息一键朗读。
  Volcengine Doubao TTS with voice cloning — read any character message aloud in one tap.
  

- **音乐播放** · **Music playback**
  网易云音乐搜索在线播放 + 本地音乐导入，支持进度 / 音量 / 歌词。
  Online playback via Netease Cloud Music search plus local file import, with seek / volume / lyrics.
  <img width="1080" height="2400" alt="Screenshot_2026-08-07-18-20-11-452_com chatbyyou" src="https://github.com/user-attachments/assets/d62e5ac6-a109-4b40-8f8c-72362400f228" />


- **多模态对话** · **Multimodal chat**
  图片（最多 3 张）、PDF（前 6 页）、纯文本文件直连多模态模型。
  Send images (up to 3), PDFs (first 6 pages), or text files straight to a multimodal model.

- **深度思考** · **Deep thinking**
  展示并折叠模型推理过程。
  Render and collapse the model's reasoning trace.

- **性能浮窗** · **Performance overlay**
  本地推理时实时监控 Token 速率 / CPU / GPU / NPU / 温度 / 内存，液态玻璃风格浮窗。
  Real-time liquid-glass overlay monitoring token/s, CPU, GPU, NPU, temperature, and memory during local inference.

- **角色主动问候** · **Proactive character greetings**
  WorkManager 调度的自续链通知，角色会在你离开后主动发来消息（跨重启存活）。
  WorkManager-scheduled self-continuing notification chain — characters message you on their own, surviving app restarts.

## 技术栈 · Tech Stack

| 分类 · Category | 技术 · Technology |
| --- | --- |
| 语言 · Language | Kotlin 2.0.0 |
| UI | Jetpack Compose (Material3, BOM 2024.06) |
| 构建 · Build | AGP 8.5.0, JDK 17+, compileSdk/targetSdk 34, minSdk 24 |
| 本地推理 · Local inference | MNN (CPU / OpenCL GPU / QNN NPU)，arm64-v8a only · NDK 26.1（预编译库重编版本） |
| 数据 · Data | Room 2.6.1, DataStore 1.1.1 |
| 网络 · Network | Retrofit 2.11, OkHttp 4.12 |
| 媒体 · Media | Media3 1.3.1 (ExoPlayer), Coil 2.6 |
| 异步 · Async | Coroutines 1.8.1, Serialization 1.6.3 |
| 后台 · Background | WorkManager 2.9.1 |

## 项目结构 · Project Structure

```
com.chatbyyourside/
├── config/          # 应用配置、人设表、模型 Provider、资源路径
├── data/            # model / local(Room,DataStore) / remote(Retrofit,网易云,直连LLM) / repository
├── llm/             # MNN backend、CPU 加速、温度监控、推理线程优化
├── provider/        # 聊天 Provider（cloud / local）抽象与切换
├── tts/             # 火山引擎 TTS 客户端
├── manager/         # Audio / Model / Tts 管理器
├── perfmon/         # 液态玻璃性能浮窗
├── notification/    # 角色主动问候通知
├── work/            # WorkManager 问候调度
├── ui/              # glass 组件、chat / characters / feed / music / models / settings / theme / navigation
└── util/            # 工具类
```

## 构建 · Build

### 环境要求 · Prerequisites

- Android SDK（compileSdk 34）
- NDK 27.2.12479018（AGP 工具链声明，对应 `app/build.gradle.kts` 的 `ndkVersion`；与 native 重编的 NDK 26.1.10909125 独立，见下方「说明」）
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

- `app/src/main/assets/characters/` — 20 位原创角色的立绘（AI 生成）。生成提示词见 [`docs/character-art-prompts.md`](docs/character-art-prompts.md)。
  Character art (AI-generated) for the 20 original archetypes. Generation prompts are in [`docs/character-art-prompts.md`](docs/character-art-prompts.md).
- UI 设计预览见 [`docs/preview/`](docs/preview/)。
  UI design mockups are in [`docs/preview/`](docs/preview/).

## 许可 · License

[MIT](LICENSE)。内置人设与立绘均为原创，无第三方版权角色。
MIT License. All built-in characters and art are original — no third-party copyrighted characters.
