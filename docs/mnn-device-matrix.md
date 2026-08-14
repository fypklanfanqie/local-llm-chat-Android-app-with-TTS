# MNN 自适应推理设备矩阵（Task 16）

真实设备验证覆盖矩阵。目标：在 CI 可用的物理设备/模拟器上覆盖关键芯片族、Android 版本、内存档位与 16 KiB 页，确保自适应降级链（OpenCL probe → CPU optimized → CPU compat）与 KV 复用在不同运行时可靠。

## 设备类别

| 类别 | 示例 | 关注点 |
|---|---|---|
| 现代骁龙 | 8 Gen2/Gen3/Elite（SM8550/8650/8750） | OpenCL probe、QNN 排除（标准构建）、sustained |
| 老骁龙 | 888 / 8 Gen1（SM8350/8450） | CPU optimized 兼容、OpenCL 慢速回退 |
| MediaTek | Dimensity 8300/9200（Mali） | OpenCL（Mali 驱动）、CPU optimized |
| Exynos | 2400/2400e（Xclipse） | OpenCL、CPU 兼容回退 |
| API 24–28 | Android 7.0–9 | 类加载隔离（无 API29+/31+ 引用崩）、JNI handshake |
| API 29–30 | Android 10–11 | 温控监听（ThermalApi29） |
| API 31–34 | Android 12–14 | PerformanceHint、sustained、SoC 字段 |
| Android 15 16KiB | Pixel 9 / 兼容 | 全部 `.so` `p_align>=0x4000` 加载 |
| 内存档位 | 6 / 8 / 12 GB | RAM 准入降档、模型驻留、lowMemory |

## 固定测量方法

- **模型/提示/config**：固定一个 MNN 模型（如 Qwen3.5-2B-MNN）、固定中英混合提示、固定 context=2048/threads=4/temp=0.8。
- **冷启程序**：清应用进程 → 首次加载（记录 coldLoadMs/peakPss）→ 生成 → 完全退到后台等释放。
- **上传统计**：TTFT、prefill/decode tps（中位数+离散度）、peakPss（加载前/后/峰值/释放后）、KV reuse、完成原因分布、热状态、OpenCL probe 结果、实际后端/变体/loadConfigHash。

## 上报格式

每个设备一份 JSON：`deviceRuntimeFingerprint`、`modelFingerprint`、每个 scenario 的 `BenchmarkSummary`、`attemptTrace`、`downgradeReasons`、native manifest hash 对齐。

## 门禁

- 所有 JVM 单测通过；
- `verify_native_bundle.py` 对每个标准 `.so` 断言 `p_align >= 0x4000`；
- 标准 APK 不含任何 `libQnn*`；
- runtime-info manifest 哈希与打包一致；
- 无真实模型 fixture 的模拟器测试以明确原因跳过（不静默通过）；
- 16 KiB 设备能加载全部 native 库。
- GPU 候选的 decoded 样本必须全部实际跑在 `MNN_GPU`；全回退候选不得作为 GPU 收益证据晋级。

## 单阶段思考验收
- 同一请求日志只有一个 generationId/一次 model load attempt chain；不得出现“阶段 2”。
- maxTokens=128/512/2048 时 generatedTokens 不超过总上限。
- 思考自然闭合时 `reasoningEndUs <= firstBodyDeltaUs`；未闭合时 completionReason 必须如实为 MAX_TOKENS/USER_CANCEL/THERMAL_STOP。
- 原始 modelContent 不含应用合成的 `</think>`。
- 用户点击停止后不触发第二次直接作答。
