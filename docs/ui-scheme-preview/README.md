# 聊天界面 UI 方案预览

用浏览器打开 `index.html` 即可。无需联网、无需构建。

> **已选定：方案 C · 实体卡片** - 已落地到 `ChatScreen.kt` 的 `ChatGlassScheme`。
> 本页保留供后续对比 / 切换其他方案时参考。

## 怎么用

1. **右侧手机框**实时展示当前方案 + 背景下的聊天界面。
2. **背景**区切换 4 种背景：极光亮/暗（应用默认极光背板）、繁忙照片亮/暗（模拟用户上传的高对比图片，最考验可读性）。
3. **方案**区点选一套。底栏 dock 全程不变，作为“可读性基准”。
4. **当前参数**面板会显示所选方案对应的 Kotlin 取值。
5. 选定后把方案字母（A/B/C/D/E）告诉我，我据此落地到 `ChatScreen.kt` 的 `ChatGlassScheme`。

## 方案速览

| 方案 | 思路 | 适合 |
|------|------|------|
| A · 现状 | 当前透明度，仅对比 | — |
| **B · 清透 Iris（推荐）** | 主题感知：亮色白底 0.82 / 暗色深底 0.80，保留玻璃质感 | 兼顾美感与可读性 |
| C · 实体卡片 | 不透明度 0.94，可读性最强 | 极繁杂背景 |
| D · 深色磨砂 | 亮/暗都用深色玻璃 + 浅字，iOS Dark 风 | 统一深色观感 |
| E · 模糊照片底 | 直接模糊照片 + 中等透明度，最贴近 dock | 真·dock 同款，需 RenderEffect(API 31+) |

## 为什么照片上要靠“提高不透明度”而不是“模糊”

真机玻璃面（`frostedGlass`）采样的是**预模糊的极光背板**做真实模糊。
但用户上传照片时，背板采样被关闭（`ChatScreen.kt` 中 `LocalBackdropState = null`），
玻璃退化为**纯半透明色块**——没有真实模糊可用。所以在照片上只能靠提高不透明度保证文字可读。

方案 E 另辟蹊径：用 `RenderEffect.createBlurEffect`（API 31+）把照片本身模糊一张铺底，
再叠中等透明度玻璃，即可在不提高不透明度的前提下获得 dock 般的磨砂可读性。
低于 API 31 的设备自动回退到方案 B 的取值。

## 取值 → Kotlin 映射

| 预览变量 | Kotlin (`ChatGlassScheme`) |
|----------|----------------------------|
| `aiTint` | `aiTintLight` / `aiTintDark` |
| `inputTint` | `inputTintLight` / `inputTintDark` |
| `topBarTint` | `topBarTintLight` / `topBarTintDark` |
| `chipTint` | `chipTintLight` / `chipTintDark` |
| `photoScrim` | `photoScrimBaseLight` / `photoScrimBaseDark` |
| `blur` | `aiBlur` / `inputBlur` |
| `shadow` | `aiShadow` / `inputShadow` / `topBarShadow` |
| `textShadow` | AI 气泡文字 `TextStyle.shadow`（仅照片背景开启） |
| `blurPhotoBg` | 方案 E：`ChatBackground` 内 `RenderEffect` 模糊层 |

> 预览中 `px` 数值近似等于真机 `dp`，仅作视觉对照，落地时会以真机实测微调。
