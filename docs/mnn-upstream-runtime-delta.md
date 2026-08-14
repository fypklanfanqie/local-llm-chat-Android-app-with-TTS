# MNN upstream runtime delta — `af0142bc` (pinned) → `75e53afe` (candidate)

> 本文件是 **Task 4（MNN runtime/GPU 升级基础设施）** 的差异证据：它区分「pinned base 已
> 具备的能力」与「candidate 新增/修改」，并明确哪些是 **未通过本项目模型/设备验证的声明**。
> 本文件**不**构成升级决定；升级只在该项目同设备/模型/配置的基准门禁通过后才发生
> （见 `app/src/main/java/com/chatbyyourside/llm/benchmark/ExperimentalPromotionPolicy.kt`
> 的 `evaluateRuntime` 与 `docs/mnn-device-matrix.md`）。
>
> **复现说明**：`compare_mnn_runtime.py --output` **只更新下面两个 marker 之间的自动生成段**，
> 手写的「base 已有 / candidate 新增 / 未验证 / 决策边界」段保持不变，因此可安全重跑：

```bash
python scripts/native/compare_mnn_runtime.py \
  --repo /path/to/MNN \
  --base af0142bcc7b76b7a5128373e285683dc04f55f69 \
  --candidate 75e53afe568f7b6fabb1adc34894fe9f331d52f8 \
  --output docs/mnn-upstream-runtime-delta.md
```



<!-- BEGIN MNN-RUNTIME-DELTA:auto -->
# MNN upstream runtime delta

- base:      `af0142bcc7b76b7a5128373e285683dc04f55f69`
- candidate: `75e53afe568f7b6fabb1adc34894fe9f331d52f8`

## Changed paths (base -> candidate)

```text
M	.github/workflows/aone-pr-merge-trigger.yml
M	.gitignore
M	CMakeLists.txt
M	MNN.podspec
M	apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/chat/ChatActivity.kt
A	apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/chat/ChatHistoryPersistencePolicy.kt
M	apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/chat/ChatPresenter.kt
M	apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/chat/chatlist/ChatListComponent.kt
M	apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/chat/chatlist/ChatViewHolders.kt
A	apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/chat/chatlist/MarkdownBlockParser.kt
A	apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/chat/chatlist/MarkdownMessageView.kt
A	apps/Android/MnnLlmChat/app/src/main/res/drawable/bg_markdown_code_block.xml
M	apps/Android/MnnLlmChat/app/src/main/res/layout/item_holder_assistant.xml
A	apps/Android/MnnLlmChat/app/src/test/java/com/alibaba/mnnllm/android/chat/ChatHistoryPersistencePolicyTest.kt
M	apps/Android/MnnLlmChat/app/src/test/java/com/alibaba/mnnllm/android/chat/chatlist/AssistantMarkdownTableRenderTest.kt
A	apps/Android/MnnLlmChat/app/src/test/java/com/alibaba/mnnllm/android/chat/chatlist/MarkdownBlockParserTest.kt
M	docs/compile/cmake.md
M	docs/transformers/llm.md
M	express/Executor.cpp
M	express/module/PipelineModule.cpp
M	express/module/StaticModule.cpp
M	include/MNN/MNNForwardType.h
M	schema/current/MNN_generated.h
M	schema/default/MNN.fbs
M	skills/add-new-op/SKILL.md
M	skills/add-new-op/step1-schema.md
M	skills/general-debug/SKILL.md
M	skills/metal-optimize/SKILL.md
M	skills/metal-optimize/build-and-test.md
M	skills/metal-optimize/env-registry.md
A	skills/metal-optimize/graph-fusion.md
D	skills/metal-optimize/kernel-basics.md
A	skills/metal-optimize/kernel-dev-and-optimize.md
D	skills/metal-optimize/perf-playbook.md
A	skills/metal-optimize/runtime-scheduling.md
M	skills/support-new-llm/SKILL.md
M	skills/support-new-llm/common-pitfalls.md
M	skills/test-ci/ios-llm-bench.md
M	source/backend/cpu/CPUBackend.cpp
M	source/backend/cpu/CPULinearAttention.cpp
M	source/backend/cpu/CPULinearAttention.hpp
M	source/backend/cpu/CPUOPRegister.cpp
M	source/backend/cpu/compute/Convolution1x1Strassen.cpp
M	source/backend/cpu/compute/Convolution1x1Strassen.hpp
M	source/backend/cpu/compute/ImageProcessFunction.cpp
M	source/backend/cpu/compute/ImageProcessFunction.hpp
M	source/backend/cpu/riscv/rvv/MNNAccumulateSequenceNumber.cpp
M	source/backend/cuda/core/CUDABackend.cpp
M	source/backend/cuda/core/CUDABackend.hpp
A	source/backend/cuda/execution/FusedProjExecution.cu
A	source/backend/cuda/execution/FusedProjExecution.hpp
M	source/backend/cuda/execution/LinearAttentionExecution.cu
M	source/backend/cuda/execution/LinearAttentionExecution.hpp
M	source/backend/metal/ConvSimdGroupShader.hpp
M	source/backend/metal/MetalAttention.mm
M	source/backend/metal/MetalBackend.hpp
M	source/backend/metal/MetalBackend.mm
M	source/backend/metal/MetalBinary.hpp
M	source/backend/metal/MetalBinary.mm
M	source/backend/metal/MetalConvolution1x1.hpp
M	source/backend/metal/MetalConvolution1x1.mm
M	source/backend/metal/MetalConvolutionDepthwise.mm
M	source/backend/metal/MetalConvolutionWinograd.mm
M	source/backend/metal/MetalEnv.hpp
M	source/backend/metal/MetalExecution.mm
M	source/backend/metal/MetalFlashAttnShader.hpp
A	source/backend/metal/MetalFusedProj.mm
A	source/backend/metal/MetalGatedNormShader.hpp
A	source/backend/metal/MetalGatedRMSNorm.mm
M	source/backend/metal/MetalLayerNorm.hpp
M	source/backend/metal/MetalLayerNorm.mm
M	source/backend/metal/MetalLinearAttention.hpp
M	source/backend/metal/MetalLinearAttention.mm
M	source/backend/metal/MetalLinearAttentionShader.hpp
M	source/backend/metal/MetalOPRegister.mm
M	source/backend/metal/MetalRaster.mm
M	source/backend/metal/MetalReplay.hpp
M	source/backend/metal/MetalUnary.hpp
M	source/backend/metal/MetalUnary.mm
M	source/backend/opencl/core/OpenCLOPRegister.cpp
M	source/backend/opencl/core/runtime/OpenCLWrapper.cpp
A	source/backend/opencl/execution/buffer/FusedProjBufExecution.cpp
A	source/backend/opencl/execution/buffer/FusedProjBufExecution.hpp
M	source/backend/opencl/execution/buffer/LinearAttentionBufExecution.cpp
M	source/backend/opencl/execution/buffer/LinearAttentionBufExecution.hpp
M	source/backend/opencl/execution/cl/buffer_convert_quant.cl
M	source/backend/opencl/execution/cl/buffer_convert_quant_mnn_cl.cpp
M	source/backend/opencl/execution/cl/linear_attention_buf.cl
M	source/backend/opencl/execution/cl/linear_attention_buf_mnn_cl.cpp
M	source/backend/opencl/execution/cl/opencl_source_map.hpp
M	source/backend/qnn/backend/QNNBackend.cpp
M	source/backend/vulkan/buffer/backend/VulkanBackend.cpp
M	source/backend/vulkan/buffer/backend/VulkanBackend.hpp
M	source/backend/vulkan/buffer/compiler/AllShader.cpp
A	source/backend/vulkan/buffer/execution/VulkanFusedProj.cpp
A	source/backend/vulkan/buffer/execution/VulkanFusedProj.hpp
M	source/backend/vulkan/buffer/execution/VulkanLinearAttention.cpp
M	source/backend/vulkan/buffer/execution/VulkanLinearAttention.hpp
M	source/backend/vulkan/buffer/execution/glsl/linear_attn_gated_delta_rule_decode.comp
M	source/backend/vulkan/buffer/execution/glsl/linear_attn_gated_delta_rule_decode_nosubgroup.comp
M	source/backend/vulkan/buffer/execution/glsl/linear_attn_gated_delta_rule_prefill.comp
M	source/backend/vulkan/buffer/execution/glsl/linear_attn_gated_delta_rule_prefill_nosubgroup.comp
M	source/backend/vulkan/runtime/VulkanRuntime.cpp
A	source/core/FusedProjCommon.hpp
M	source/core/OpCommonUtils.cpp
M	source/core/OpCommonUtils.hpp
M	source/core/Pipeline.cpp
M	source/cv/ImageProcessUtils.cpp
M	source/geometry/GeometryComputer.cpp
M	source/geometry/GeometryComputer.hpp
A	source/geometry/GeometryFusedProj.cpp
A	source/geometry/GeometryGatedRMSNorm.cpp
M	source/geometry/GeometryOPRegister.cpp
A	source/shape/ShapeFusedProj.cpp
A	source/shape/ShapeGatedRMSNorm.cpp
M	source/shape/ShapeRegister.cpp
A	test/op/FusedProjTest.cpp
A	test/op/GatedRMSNormTest.cpp
M	test/op/LinearAttentionTest.cpp
M	tools/audio/source/audio.cpp
M	tools/converter/include/config.hpp
M	tools/converter/source/common/cli.cpp
M	tools/converter/source/optimizer/merge/FuseAttention.cpp
M	tools/converter/source/optimizer/postconvert/FuseTransformerC4.cpp
M	tools/converter/source/optimizer/postconvert/RemoveInvalidCast.cpp
M	tools/cpp/OpenCLProgramBuildTest.cpp
M	tools/script/metal_profile_gantt.py
A	tools/script/testTransformerC4Switches.py
M	transformers/llm/engine/CMakeLists.txt
M	transformers/llm/engine/demo/llm_demo.cpp
A	transformers/llm/engine/demo/llm_logits_diff.cpp
M	transformers/llm/engine/demo/tokenizer_demo.cpp
M	transformers/llm/engine/include/llm/llm.hpp
M	transformers/llm/engine/ios/ios_llm_bench.sh
M	transformers/llm/engine/ios/mnn-llm/mnn-llm/LLMInferenceEngineWrapper.mm
M	transformers/llm/engine/src/embedding.cpp
M	transformers/llm/engine/src/llm.cpp
M	transformers/llm/engine/src/llmconfig.hpp
M	transformers/llm/engine/src/omni.cpp
M	transformers/llm/engine/src/omni.hpp
M	transformers/llm/engine/src/tokenizer/jinja.hpp
M	transformers/llm/engine/src/tokenizer/tokenizer.cpp
M	transformers/llm/engine/src/tokenizer/tokenizer.hpp
M	transformers/llm/engine/test/test_tokenizer.cpp
M	transformers/llm/export/llmexport.py
M	transformers/llm/export/mnn_quant_ref.py
M	transformers/llm/export/utils/audio.py
M	transformers/llm/export/utils/config.py
M	transformers/llm/export/utils/custom_op.py
M	transformers/llm/export/utils/lora.py
M	transformers/llm/export/utils/mnn_converter.py
M	transformers/llm/export/utils/model.py
M	transformers/llm/export/utils/model_mapper.py
M	transformers/llm/export/utils/tokenizer.py
M	transformers/llm/export/utils/transformers.py
M	transformers/llm/export/utils/vision.py
```

## Feature presence (source-scan heuristic)

| feature | canonical path | marker | in base | in candidate |
|---|---|---|---|---|
| cpu_linear_attention | `source/shape/ShapeAttention.cpp` | `LinearAttentionSizeComputer` | YES | YES |
| arm82_linear_attention_fp16 | `source/backend/arm82/Arm82Functions.cpp` | `LinearAttention fp16 kernels` | YES | YES |
| opencl_topkv2 | `source/backend/opencl/execution/image/TopKV2Execution.cpp` | `(path existence)` | YES | YES |
| opencl_linear_attention | `source/backend/opencl/execution/buffer/LinearAttentionBufExecution.cpp` | `(path existence)` | YES | YES |
| thinking_template_compat | `apps/Android/MnnLlmChat` | `enable_thinking` | YES | YES |

> Source-scan results only. Presence in base means the capability is
> already available at the pinned commit — NOT an upgrade benefit.
> Presence in candidate only is a candidate gain; real promotion still
> requires model-graph usage and device benchmark gates.


<!-- END MNN-RUNTIME-DELTA:auto -->



---

## 1. base 已具备的能力（不能当作“升级才获得”）

上方自动生成的特征表（`## Feature presence (source-scan heuristic)`）显示：以下能力在
pinned `af0142bc` 的源码树中**已经存在**（candidate 中同样存在）——**这些特性名本身不是
升级理由。**

- `cpu_linear_attention`（`source/shape/ShapeAttention.cpp` 的 `LinearAttentionSizeComputer`）
- `arm82_linear_attention_fp16`（`Arm82Functions.cpp` 的 `LinearAttention fp16 kernels`）
- `opencl_topkv2`（`execution/image/TopKV2Execution.cpp`）
- `opencl_linear_attention`（`execution/buffer/LinearAttentionBufExecution.cpp`）
- `thinking_template_compat`（`apps/Android/MnnLlmChat` 的 `enable_thinking`）

已进一步核实（非仅特征表）：

- `source/backend/cpu/CPULinearAttention.{cpp,hpp}` 在 base 存在（CPU LinearAttention 实现早已入库）。
- base 的 `apps/Android/MnnLlmChat/README.md` 已包含 **Version 0.8.2.2** 一节，其中声称
  「Refresh the bundled MNN runtime with the latest CPU LinearAttention and Arm82 fp16 optimization
  path」「thinking-mode prompts」「TopKV2 backend coverage for OpenCL and Metal execution paths」——
  **这些 README 声明先于 pinned base**，是描述 base 已含能力的文档，不是 candidate 的收益。

结论：以上 5 个特性名均不得被表述为「升级才获得」。

---

## 2. candidate 新增/修改（相对 base 的可观察源码变化）

`base..candidate` 的完整路径清单见上方自动生成段的 `## Changed paths`（共 156 个变更路径）。
本节按与本项目 runtime/GPU 路径的相关度分组（节选）。

### 2.1 OpenCL（本项目的 GPU 路径，重点）

```
M  source/backend/opencl/core/OpenCLOPRegister.cpp
M  source/backend/opencl/core/runtime/OpenCLWrapper.cpp
A  source/backend/opencl/execution/buffer/FusedProjBufExecution.cpp
A  source/backend/opencl/execution/buffer/FusedProjBufExecution.hpp
M  source/backend/opencl/execution/buffer/LinearAttentionBufExecution.cpp
M  source/backend/opencl/execution/buffer/LinearAttentionBufExecution.hpp
M  source/backend/opencl/execution/cl/buffer_convert_quant.cl
M  source/backend/opencl/execution/cl/buffer_convert_quant_mnn_cl.cpp
M  source/backend/opencl/execution/cl/linear_attention_buf.cl
M  source/backend/opencl/execution/cl/linear_attention_buf_mnn_cl.cpp
M  source/backend/opencl/execution/cl/opencl_source_map.hpp
```

- **新增 `FusedProjBufExecution`**（OpenCL buffer 模式 FusedProj op）。
- `LinearAttentionBufExecution` 与 `linear_attention_buf.*` 内核、`buffer_convert_quant.*`
  均被修改（注意：这些文件在 **base 已存在**，candidate 是对既有实现的修改，而非新增能力）。

### 2.2 CPU / 其它后端（相关但非本项目主路径）

```
M  source/backend/cpu/CPULinearAttention.cpp
M  source/backend/cpu/CPULinearAttention.hpp
M  source/backend/cpu/CPUBackend.cpp / CPUOPRegister.cpp
A  source/backend/cuda/execution/FusedProjExecution.{cu,hpp}
A  source/backend/metal/MetalFusedProj.mm
A  source/backend/metal/MetalGatedRMSNorm.mm
A  source/backend/vulkan/buffer/execution/VulkanFusedProj.{cpp,hpp}
M  source/backend/vulkan/buffer/execution/glsl/linear_attn_gated_delta_rule_{decode,prefill}{,_nosubgroup}.comp
M  source/core/Pipeline.cpp / OpCommonUtils.cpp / OpCommonUtils.hpp
A  source/geometry/GeometryFusedProj.cpp / GeometryGatedRMSNorm.cpp
A  source/shape/ShapeFusedProj.cpp / ShapeGatedRMSNorm.cpp
A  source/core/FusedProjCommon.hpp
M  schema/current/MNN_generated.h / schema/default/MNN.fbs / include/MNN/MNNForwardType.h
M  transformers/llm/engine/src/llm.cpp / llmconfig.hpp / tokenizer/{jinja.hpp,tokenizer.cpp,tokenizer.hpp}
A  test/op/FusedProjTest.cpp / GatedRMSNormTest.cpp
```

- **新增算子族**：`FusedProj`（OpenCL/Metal/CUDA/Vulkan + core/shape/geometry 全链）、
  `GatedRMSNorm`。**这些才是 candidate 相对 base 真正的“新增”，也是 README/文档未直接声称的**。
- **schema / FlatBuffer 变更**（`MNN.fbs`、`MNN_generated.h`、`MNNForwardType.h`）：candidate
  的 runtime 导出图/op 注册与 base 可能不同——**升级 base→candidate 后旧导出模型可能加载即失败
  （新 op/新字段），这正是必须整体原子晋级的理由**。

### 2.3 App 侧（上游 MnnLlmChat demo 应用，非本项目）

```
A  apps/Android/MnnLlmChat/.../chatlist/MarkdownBlockParser.kt
A  apps/Android/MnnLlmChat/.../chatlist/MarkdownMessageView.kt
A  apps/Android/MnnLlmChat/.../chat/ChatHistoryPersistencePolicy.kt
M  apps/Android/MnnLlmChat/.../chat/ChatActivity.kt / ChatPresenter.kt
M  apps/Android/MnnLlmChat/.../chatlist/ChatListComponent.kt / ChatViewHolders.kt
A  apps/Android/MnnLlmChat/.../test/.../MarkdownBlockParserTest.kt 等
```

- 这些是上游 demo 应用的 UI/持久化改动（Markdown 渲染、历史持久化策略），**与本项目 App 无关**
  （本项目不消费上游 app 代码），仅说明「上游自带 APK」的观感差异，**不能作为本项目 runtime 收益证据**。

---

## 3. 未验证声明（不得据此宣称收益）

以下为本项目**尚未验证**的内容，仅作候选审查清单：

1. **README 0.8.2.2 声称的能力**：CPU LinearAttention、Arm82 fp16、thinking tokenizer/template、
   OpenCL/Metal TopKV2 —— 已在 §1 核实 **base 已含源码实现**，且未在真机/本项目模型上验证吞吐提升。
2. **candidate 的 FusedProj / GatedRMSNorm / LinearAttention 修改**是否被**本项目的模型图**使用：
   - FusedProj / GatedRMSNorm 是候选新增 op——只有导出图包含这些 op 时才会走新执行路径；本项目的
     已打包模型（config.json + llm.mnn）是否使用未知。
   - LinearAttention 修改只有模型含 LinearAttention 结构才有收益；CPU 实现 base 已有，candidate 是增量修改。
3. **实际收益**必须来自同设备/模型/配置基准：median/P95 TTFT、prefill/decode TPS、PSS、KV reuse、
   empty-response、actualBackendCounts、completion reason、commit/build ID（见 Step 7 矩阵）。
   单靠源码 diff 或 README 声明**不得**推动 promotion。

---

## 4. 升级决策边界（本任务不执行）

- 生产仍固定 `af0142bcc7b76b7a5128373e285683dc04f55f69`。
- candidate 构建必须落在独立 staging（`MNN_OUTPUT_DIR`/`MNN_MANIFEST_OUT`），不得覆盖
  `app/src/main/jniLibs` 或生产 manifest；verifier 校验 `manifest.mnnCommit == candidate`、16 KiB、
  无 QNN、依赖闭包完整。
- 仅当 `ExperimentalPromotionPolicy.evaluateRuntime` 门禁（正确性/KV 无回归、decode ≥ 1.10×、
  TTFT/PSS 回退 ≤ 30%、GPU 证据全部 `MNN_GPU` 无 CPU fallback 混入、至少一台目标设备有明确收益）
  通过后，才在**同一变更**里原子更新 `MNN_COMMIT`/`CHAT_MNN_COMMIT`/`EXPECTED_MNN_COMMIT`/
  `native-manifest.json`/`mnn_jni.cpp` 能力。门禁不通过则保留现状并归档失败证据。
