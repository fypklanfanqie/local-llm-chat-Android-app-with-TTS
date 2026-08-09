# 实验认证协议（Task 17）

原则：**一次只评估一个实验**，仅在满足正确性 + 指纹级性能证据后独立 promotion；任何实验在被 [ExperimentalPromotionPolicy](../app/src/main/java/com/chatbyyourside/llm/benchmark/ExperimentalPromotionPolicy.kt) 认证前不得改动生产配置。不暴露可编辑的原始运行时 JSON。

## 通用流程

1. 冷启程序：清进程 → 冷加载 → 固定中英混合提示 → 生成 → 完全退后台等释放。
2. 每实验一次一个候选值；对同一 `deviceRuntimeFingerprint + modelFingerprint` 记录基线 + 候选 `BenchmarkSample`（correctnessOk、decodeTpsMedian、ttftMsMedian、peakPssMb、sampleCount≥3、非 hotStart）。
3. `ExperimentalPromotionPolicy.evaluate(baseline, candidate)` 通过才 promotion；否则留候选配置待改。
4. 认证后**独立提交**（信息含实验名 + 指纹范围），不改其它实验。

## 各实验评估要点

| 实验 | 场景 | 认证门槛（叠加通用门禁） |
|---|---|---|
| **Lookahead（Step 1）** | CPU 冷首轮 / 重复二轮 / 非重复散文 | 正确性 + ≥10% decode；仅匹配的 device/model 指纹 promotion |
| **Prompt chunking（Step 2）** | 长 prompt 的峰值 PSS / TTFT / prefill 速度 | 仅作内存降级；正确性通过后才评估吞吐 |
| **Attention/KV / 动态量化（Step 3）** | 每个候选值一次运行 | 确定有限输出、EOS、二轮 KV、质量 fixture 通过后才看吞吐；**绝不以 CPU attention 结果推断 OpenCL** |
| **磁盘 KV / mmap 缓存（Step 4）** | 冷/热加载、存储增长、清理、二轮 TTFT、进程重启 | 配额 + 指纹 namespace 就位后才 promotion |
| **CPU 线程数（Step 5）** | 2/4/性能核 × 1/5/10 分钟稳态 tokens/s、热斜率、每 token 能耗 | 最高突发 tokens/s 不充分；需稳态 + 热 + 能耗 |
| **离线量化模型 / QNN（Step 6）** | 独立模型 ID / 清单 / 质量评估 | 独立发布项目；QNN 需实验 flavor 的精确 QNN SDK + HTP/Stub/Skel + 兼容模型矩阵后才启用 |

## 反模式（禁止）

- 多实验同时改（无法归因）；
- 热启动 / 单样本 / 噪声结果当证据；
- 无正确性结果的吞吐 promotion；
- 在标准构建暴露 QNN 或未认证的量化。
