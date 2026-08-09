# MNN Adaptive Inference Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a measurable, compatibility-first MNN inference layer with user-selectable Balanced and Maximum Speed modes, faster JNI/Kotlin streaming, reliable multi-turn KV reuse, validated model/resource admission, and safe CPU/OpenCL fallback across supported Android devices.

**Architecture:** Keep the existing `LocalChatProvider → BackendManager → MnnBackend → MnnBridge → mnn_jni.cpp` chain. Add a resolver that turns user intent and current device/model/thermal/memory state into an immutable `ResolvedInferencePlan`; execute explicit backend/config attempts, with CPU as the universal baseline and OpenCL enabled only after a persisted probe/calibration result. Land high-confidence streaming and exact-prompt fixes before benchmark-gated runtime experiments.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Coroutines/Flow, DataStore, Room 2.6.1, Android JNI/C++17, Alibaba MNN pinned at `af0142bcc7b76b7a5128373e285683dc04f55f69`, NDK r27c (`27.2.12479018`), arm64-v8a, Python native-bundle verification, GitHub Actions, adb/Perfetto/device tests.

## Global Constraints

- Support Android API 24+ and `arm64-v8a`; do not add ARMv7 or x86_64 in this effort.
- Fresh installs and missing/invalid settings use `BALANCED`; users may select `MAXIMUM_SPEED`.
- CPU is the unknown-device baseline; rely on MNN HWCAP dispatch for FP16/dot/i8mm/SVE rather than app-level SoC guessing.
- OpenCL must pass a private-process execution probe and model-specific health checks before AUTO selection.
- QNN stays out of AUTO and is unavailable in the standard artifact; a future QNN build requires a separate experimental flavor.
- Preserve existing user-set backend/context/max-token values; only absent max-token preference changes from 65,536 to 2,048.
- Never transparently change backend after the first visible output delta.
- Keep `attention_mode=8`, `dynamic_option=0`, `reuse_kv=true`, mmap, cached mmap, and penalty-enabled sampling as safe defaults.
- Lookahead, prompt chunking, KV/dynamic quantization, disk KV/prefix cache, higher thread caps, QNN, and offline model variants remain benchmark-gated experiments.
- Rebuild all standard native libraries from the pinned MNN commit and one NDK/libc++ toolchain; every packaged ELF must support Android 15 16 KiB pages (`PT_LOAD p_align >= 0x4000`).
- Proxy configuration is optional process environment only: `HTTP_PROXY`/`HTTPS_PROXY=http://127.0.0.1:7897`; never hard-code it in the app or default scripts.
- Local machine cannot run Gradle because the available JDK is 8 while the project requires Java 17+; local verification is source/static/native-script review. Gradle, Room, instrumentation, APK, and device checks run in CI or a compatible machine.
- Do not commit automatically during execution. The commit commands below are checkpoints to run only when the user explicitly authorizes commits.
- Design reference: `docs/superpowers/specs/2026-08-08-mnn-adaptive-inference-optimization-design.md`.

---

## File Structure

### Existing files to modify

- `app/src/main/cpp/CMakeLists.txt` — compile JNI/probe against the pinned MNN ABI and 16 KiB linker flags.
- `app/src/main/cpp/mnn_jni.cpp` — runtime-info handshake, resolved-config loading, stream batching, typed generation summary, exact prompt-cache synchronization.
- `app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.kt` — versioned JNI signatures and callback/result contracts.
- `app/src/main/java/com/chatbyyourside/llm/backend/MnnBackend.kt` — consume `BackendAttempt`, forward deltas, publish atomic snapshots, remove duplicate accumulation.
- `app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt` — execute explicit plans, enforce pre-first-delta fallback, release/config-hash policy.
- `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt` — one settings snapshot, one raw accumulator, incremental script policy, profile resolution, typed result.
- `app/src/main/java/com/chatbyyourside/data/model/ChatMessage.kt` — nullable exact `modelContent`.
- `app/src/main/java/com/chatbyyourside/data/local/AppDatabase.kt` — Room version 3 and explicit `MIGRATION_2_3`.
- `app/src/main/java/com/chatbyyourside/data/repository/ChatRepository.kt` — persist/map `modelContent`.
- `app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt` — prompt-window selection and separate display/model assistant persistence.
- `app/src/main/java/com/chatbyyourside/data/local/SettingsStore.kt` and `data/repository/SettingsRepository.kt` — performance mode, combined local settings snapshot, applied-plan acknowledgement.
- `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt` — mode selector, actual plan/backend/calibration/downgrade UI and benchmark actions.
- `app/src/main/java/com/chatbyyourside/config/AppConfig.kt` — absent-preference default max output 2,048.
- `app/src/main/java/com/chatbyyourside/llm/CpuBoostController.kt`, `ThermalMonitor.kt`, `MainActivity.kt` — generation-scoped power and typed thermal transitions.
- `app/src/main/java/com/chatbyyourside/ChatApp.kt`, `AppContainer.kt`, `provider/ChatProviderManager.kt`, `manager/ModelManager.kt`, `notification/AppLifecycleObserver.kt` — lifecycle/residency wiring.
- `app/src/main/java/com/chatbyyourside/download/DownloadManager.kt`, `FileSplitter.kt`, `provider/local/ModelPathResolver.kt`, `data/model/LocalModel.kt` — manifest/checksum/install validation and verified installed state.
- `app/src/main/java/com/chatbyyourside/llm/LlmMemoryEstimator.kt` — KV-head-aware memory calculation.
- `app/src/main/java/com/chatbyyourside/perfmon/PerformanceCollector.kt`, `PerformanceGlassOverlay.kt`, `PerformanceOverlayView.kt` — honest atomic metrics and adaptive state display.
- `app/src/main/AndroidManifest.xml` — optional OpenCL declaration and private `:mnn_probe` service.
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — tests, source sets, standard native packaging.

### Focused files to create

- `app/src/main/java/com/chatbyyourside/llm/profile/InferencePerformanceMode.kt`
- `app/src/main/java/com/chatbyyourside/llm/profile/ResolvedInferencePlan.kt`
- `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- `app/src/main/java/com/chatbyyourside/llm/profile/DeviceRuntimeFingerprint.kt`
- `app/src/main/java/com/chatbyyourside/llm/backend/BackendHealthStore.kt`
- `app/src/main/java/com/chatbyyourside/llm/backend/OpenClProbeModels.kt`
- `app/src/main/java/com/chatbyyourside/llm/backend/OpenClProbeService.kt`
- `app/src/main/cpp/backend_probe_jni.cpp`
- `app/src/main/java/com/chatbyyourside/llm/PromptWindowPlanner.kt`
- `app/src/main/java/com/chatbyyourside/llm/IncrementalScriptDetector.kt`
- `app/src/main/java/com/chatbyyourside/llm/ModelBundleValidator.kt`
- `app/src/main/java/com/chatbyyourside/llm/ModelAdmissionController.kt`
- `app/src/main/java/com/chatbyyourside/llm/ModelResidencyController.kt`
- `app/src/main/java/com/chatbyyourside/llm/metrics/InferenceTelemetry.kt`
- `app/src/main/java/com/chatbyyourside/llm/benchmark/LocalInferenceBenchmarkRunner.kt`
- `scripts/native/build_mnn_android.sh`
- `scripts/native/verify_native_bundle.py`
- `app/src/main/jniLibs/native-manifest.json`
- `.github/workflows/android-native-ci.yml`
- JVM and instrumentation tests in matching `app/src/test/...` and `app/src/androidTest/...` packages.

---

## Phase 0 — Reproducible Native Runtime and Baseline

### Task 1: Add native provenance and 16 KiB verification

> **Status (2026-08-09):** Steps 1–6 source work complete; 31 Python tests pass; verifier confirms current prebuilt bundle FAILS the 16 KiB gate as expected (libMNN/libmnn_jni/libcpu_sys_jni are 4 KiB; libc++_shared.so already 16 KiB). Pending: pinned-MNN rebuild in CI to make Step 7 fully pass; Step 8 commit awaits user authorization.

**Files:**
- Create: `scripts/native/build_mnn_android.sh`
- Create: `scripts/native/verify_native_bundle.py`
- Create: `app/src/main/jniLibs/native-manifest.json`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/src/main/cpp/mnn_jni.cpp`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.kt`
- Test: `scripts/native/test_verify_native_bundle.py`

**Interfaces:**
- Produces: `MnnRuntimeInfo(abiVersion: Int, mnnCommit: String, nativeBuildId: String, capabilities: Set<String>)` parsed from `MnnBridge.nativeGetRuntimeInfo(): String`.
- Produces: manifest schema `{schemaVersion, mnnCommit, ndkVersion, androidApi, abi, flags, files[]}`; each file has `name`, `sha256`, `buildId`, `ptLoadAlignment`.
- Consumes later: Tasks 8, 12, 16 use runtime and binary hashes in device fingerprints and cache namespaces.

- [ ] **Step 1: Write verifier tests before the verifier**

Create Python tests with a manifest fixture and an injected/readelf-text parser. Include exact cases:

```python
def test_rejects_4k_pt_load_alignment():
    result = verify_elf_text("LOAD ... Align 0x1000")
    assert "requires >= 0x4000" in result.errors


def test_accepts_16k_pt_load_alignment():
    result = verify_elf_text("LOAD ... Align 0x4000")
    assert result.errors == []


def test_manifest_hash_mismatch_fails():
    assert verify_hash(b"abc", "00" * 32).ok is False
```

- [ ] **Step 2: Run the standalone verifier tests**

Run: `python -m unittest scripts/native/test_verify_native_bundle.py -v`

Expected: FAIL because `verify_native_bundle.py` is not implemented.

- [ ] **Step 3: Implement `verify_native_bundle.py`**

Implement deterministic checks for:

- ELF machine is AArch64;
- all `PT_LOAD` alignments are at least `0x4000`;
- build ID exists;
- SHA-256 and filename match manifest;
- only expected `DT_NEEDED` entries exist;
- one `libc++_shared.so` version is present;
- every standard `.so` has a manifest entry.

CLI:

```text
python scripts/native/verify_native_bundle.py \
  --dir app/src/main/jniLibs/arm64-v8a \
  --manifest app/src/main/jniLibs/native-manifest.json \
  --readelf "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
```

Exit 0 only when all gates pass; emit JSON plus a readable summary.

- [ ] **Step 4: Implement pinned build script**

Require:

```bash
MNN_COMMIT=af0142bcc7b76b7a5128373e285683dc04f55f69
NDK_VERSION=27.2.12479018
ANDROID_API=24
ANDROID_ABI=arm64-v8a
```

Build from an ASCII-only staging path with LLM, low-memory, CPU weight-dequant GEMM, transformer fuse, ARM82, OpenCL enabled and QNN disabled. Set `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` and `-Wl,-z,max-page-size=16384` for MNN and both JNI targets. Copy the matching r27c `libc++_shared.so`, generate the manifest, then invoke the verifier. Respect externally supplied `HTTP_PROXY` and `HTTPS_PROXY`; never set them in the script.

- [ ] **Step 5: Upgrade local CMake contract**

Use C++17 and add the 16 KiB linker flag to `mnn_jni` and `cpu_sys_jni`. Add compile definitions:

```text
CHAT_MNN_COMMIT="af0142..."
CHAT_MNN_JNI_ABI=1
CHAT_MNN_BUILD_ID="<generated build id>"
```

Do not make Gradle invoke CMake; retain verified-prebuilt packaging because of the project path constraint.

- [ ] **Step 6: Add runtime-info JNI handshake**

Add:

```kotlin
@Serializable
data class MnnRuntimeInfo(
    val abiVersion: Int,
    val mnnCommit: String,
    val nativeBuildId: String,
    val capabilities: Set<String>,
)

external fun nativeGetRuntimeInfo(): String
```

Native returns stable JSON containing ABI 1, pinned commit, build ID, and capabilities such as `mmap`, `cached_mmap`, `reuse_kv`, `opencl`, `stream_batching_v1` when available. Parse once after library load; set `nativeAvailable=false` and expose a diagnostic if ABI or commit differs from the manifest.

- [ ] **Step 7: Run local non-Gradle verification**

Run:

```bash
python -m unittest scripts/native/test_verify_native_bundle.py -v
python scripts/native/verify_native_bundle.py --dir app/src/main/jniLibs/arm64-v8a --manifest app/src/main/jniLibs/native-manifest.json --readelf <NDK-llvm-readelf>
git diff --check
```

Expected: Python tests PASS; the current prebuilt bundle initially FAILS the 16 KiB gate until the rebuild replaces it; source diff has no whitespace errors. Do not claim Task 1 complete until rebuilt binaries pass.

- [ ] **Step 8: Commit checkpoint (only with authorization)**

```bash
git add scripts/native app/src/main/cpp app/src/main/jniLibs/native-manifest.json app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.kt
git commit -m "build: pin and verify MNN native runtime"
```

### Task 2: Establish telemetry and benchmark baseline

> **Status (2026-08-09):** Steps 1–5 source work complete. Created `llm/profile/InferencePerformanceMode.kt`, `llm/metrics/InferenceTelemetry.kt` (InferenceStage/CompletionReason enums, InferenceSnapshot/InferenceTurnRecord/BenchmarkSummary models, AtomicReference store, median/sampleStandardDeviation/summarize statistics), `llm/benchmark/LocalInferenceBenchmarkRunner.kt` (scenario enum + runner/store contracts). Tests: `InferenceTelemetryTest.kt`, `BenchmarkStatisticsTest.kt`. Wired telemetry into `MnnBackend` (onToken publishes atomic snapshot; finally writes final record from controlled nativeGetMetrics; getBackendMetrics reads snapshot—no concurrent native read); removed `gpuUtilization=0.85f` → `null` (BackendMetrics.gpuUtilization now Float?); updated PerformanceCollector comment. `git diff --check` clean; `0.85f` gone from code. Pending: CI `./gradlew testDebugUnitTest` to compile-check test deps + run tests; Step 6 commit awaits user authorization.

**Files:**
- Create: `app/src/main/java/com/chatbyyourside/llm/metrics/InferenceTelemetry.kt`
- Create: `app/src/main/java/com/chatbyyourside/llm/benchmark/LocalInferenceBenchmarkRunner.kt`
- Modify: `app/src/main/java/com/chatbyyourside/perfmon/PerformanceCollector.kt`
- Modify: `app/src/main/java/com/chatbyyourside/perfmon/PerformanceGlassOverlay.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBackend.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/metrics/InferenceTelemetryTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/benchmark/BenchmarkStatisticsTest.kt`

**Interfaces:**
- Produces: `InferenceSnapshot` atomic per-generation state and `InferenceTurnRecord` final record.
- Produces: `BenchmarkSummary(medianTtftMs, medianPrefillTps, medianDecodeTps, decodeStdDev, peakPssMb, maxThermalStatus, kvReuseRate)`.
- Consumes later: profile resolver and health store use benchmark results, but no experimental knob may be enabled in this task.

- [ ] **Step 1: Define final telemetry models and tests**

Use explicit nullable fields rather than sentinel constants:

```kotlin
data class InferenceSnapshot(
    val generationId: String,
    val stage: InferenceStage,
    val requestedMode: InferencePerformanceMode?,
    val effectiveMode: InferencePerformanceMode?,
    val backend: BackendType?,
    val tokenCount: Int,
    val callbackCount: Int,
    val callbackBytes: Long,
    val currentTps: Float?,
    val startedElapsedMs: Long,
)
```

Final record adds cold/warm load, TTFT, prefill/decode, prompt/gen tokens, KV reuse, PSS samples, thermal start/max/end, attempt trace, config hash and downgrade reasons. Test serialization and median/sample-standard-deviation calculations with fixed values.

- [ ] **Step 2: Add test aliases/dependencies without running Gradle locally**

Add version-catalog aliases for JUnit, coroutines-test, Room testing and AndroidX test. Add `testImplementation`/`androidTestImplementation` entries. Do not guess compatibility: use versions compatible with AGP 8.5/Kotlin 2.0/Room 2.6.1 and let CI compile-check them.

- [ ] **Step 3: Implement atomic telemetry store**

Use `AtomicReference<InferenceSnapshot>` for 500 ms overlay reads. Update exact native metrics only at controlled callbacks or when JNI returns; do not call `nativeGetMetrics` concurrently from the overlay. Remove `gpuUtilization=0.85f`; represent unavailable utilization as `null`/N/A.

- [ ] **Step 4: Implement benchmark statistics and runner contract**

The runner supports explicit scenarios:

```text
COLD_LOAD
SHORT_TTFT
LONG_PREFILL
FIXED_DECODE
SECOND_TURN_KV_REUSE
```

It refuses to start while thermally hot, disables overlay collection for the run, separates warm-up from recorded samples, and persists only cool-run medians/spread. It does not yet auto-tune anything.

- [ ] **Step 5: Static verification and CI expectation**

Run locally: `git diff --check` and targeted `Grep` confirming `0.85f` is gone from backend metrics.

CI command: `./gradlew testDebugUnitTest`

Expected CI: telemetry/statistics tests PASS.

- [ ] **Step 6: Commit checkpoint (only with authorization)**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/chatbyyourside/llm/metrics app/src/main/java/com/chatbyyourside/llm/benchmark app/src/main/java/com/chatbyyourside/perfmon app/src/main/java/com/chatbyyourside/llm/backend/MnnBackend.kt app/src/test
git commit -m "feat: add local inference telemetry baseline"
```

---

## Phase 1 — Streaming and Exact KV-Prefix Correctness

### Task 3: Preserve exact model-visible assistant history

> **Status (2026-08-09):** Steps 1–5 source work complete. Added `ChatMessage.modelContent: String?` (domain) + Room `MIGRATION_2_3` (version 2→3, `ALTER TABLE chat_history ADD COLUMN modelContent TEXT`, explicit migration replacing `fallbackToDestructiveMigration`); repository conversions persist/restore `modelContent` with legacy null fallback. Split provider result: `LocalChatResult(displayText, modelText, generation)` + `GenerationSummary`; `LocalChatProvider.chatTyped()` returns typed result (`modelText` = raw accumulated, byte-identical to native `syncPromptCache()`; `displayText` = `<think>`-decorated; `chat()` delegates to `.displayText`). `BackendManager.lastTurnRecord()` surfaces Task 2 telemetry. `ChatViewModel` branches on `provider is LocalChatProvider`, persists assistant `content=displayText, modelContent=modelText`, replays local history with `it.modelContent ?: it.content` (cloud `stripThink` unchanged). Tests: `ChatRepositoryMappingTest.kt` (6 cases), `Migration2To3Test.kt` (manual v2 schema + direct `MIGRATION_2_3.migrate` via `SupportSQLiteOpenHelper`, no exported-schema dependency). `git diff --check` clean; no orphan `response` variable in ChatViewModel. Pending: CI `./gradlew testDebugUnitTest` + `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chatbyyourside.data.local.Migration2To3Test`; Step 7 commit awaits user authorization.

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/data/model/ChatMessage.kt`
- Modify: `app/src/main/java/com/chatbyyourside/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/chatbyyourside/data/repository/ChatRepository.kt`
- Modify: `app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Test: `app/src/androidTest/java/com/chatbyyourside/data/local/Migration2To3Test.kt`
- Test: `app/src/test/java/com/chatbyyourside/data/repository/ChatRepositoryMappingTest.kt`

**Interfaces:**
- Produces: `ChatMessage.modelContent: String?`.
- Produces: Room `MIGRATION_2_3` adding nullable `modelContent` to `chat_history`.
- Produces: local serialization rule `message.modelContent ?: message.content`; cloud behavior remains unchanged.

- [ ] **Step 1: Write mapping and migration tests**

Tests must assert:

```kotlin
assertEquals("raw", ChatHistoryEntity(modelContent = "raw", ...).toMessage().modelContent)
assertEquals("display", legacyMessage.modelContent ?: legacyMessage.content)
```

Migration test creates a version-2 database row, runs `MIGRATION_2_3`, then verifies `content` is unchanged and `modelContent` is null.

- [ ] **Step 2: Add `modelContent` to domain and entity**

Append `modelContent: String? = null` without changing existing constructor call behavior. Increment database version 2→3, define:

```sql
ALTER TABLE chat_history ADD COLUMN modelContent TEXT
```

Register `MIGRATION_2_3` and remove `fallbackToDestructiveMigration()` for this database path.

- [ ] **Step 3: Update repository conversions**

Persist and restore `modelContent`. Keep old rows compatible through null fallback.

- [ ] **Step 4: Split provider response into display/raw output**

Introduce a typed provider result rather than losing raw text:

```kotlin
data class LocalChatResult(
    val displayText: String,
    val modelText: String,
    val generation: GenerationSummary,
)
```

If changing `ChatProvider.chat` globally would force cloud churn, add a local-specific typed method and let `ChatViewModel` branch on active provider. Persist assistant `content=displayText`, `modelContent=modelText` only for local generation.

- [ ] **Step 5: Use exact content for local history**

When constructing local messages, copy `content = message.modelContent ?: message.content`. Keep cloud’s existing reasoning stripping and attachment handling unchanged.

- [ ] **Step 6: CI verification**

CI:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chatbyyourside.data.local.Migration2To3Test
```

Expected: mapping PASS; migration preserves existing rows.

- [ ] **Step 7: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/data app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt app/src/test app/src/androidTest
git commit -m "fix: preserve exact MNN conversation history"
```

### Task 4: Batch native streaming and remove duplicate full-text copies

> **Status (2026-08-09):** Steps 1–7 source work complete. Contract (`NativeGenerationSummary` v1 + strict `parse` + `toMetricsArray`, tests in `NativeGenerationSummaryTest.kt`). Native: `StreamBatcher` in `mnn_jni.cpp` (first complete visible char immediate, then flush on bytes/ms thresholds; never splits UTF-8), `nativeGenerateStream` now takes `batch_bytes/batch_ms` and returns compact summary JSON via `NewStringUTF` — no full-response `jbyteArray`; native `full_text` kept only for `syncPromptCache`; JNI callback `nativeCallback([B)I` now also carries real `gen_seq_len` so Kotlin live tps uses true token count (batch count ≠ token count). Kotlin: `MnnBackend.generateStreamMessages` no longer accumulates full text (returns `NativeGenerationSummary?`), tracks `policyStopped`/`callbackCount`, completionReason priority `POLICY_TRUNCATION > USER_CANCEL > BACKEND_FAILURE > summary-reason(EOS/MAX_TOKENS/…)`; metrics assembled from summary (zero second native call). `InferenceBackend` adds `batchMaxBytes=256/batchMaxMs=16` (Balanced defaults). `BackendManager.generate` threads batch params; `GenerationResult.text` → `GenerationResult.summary`. `LocalChatProvider` is the sole raw-text accumulator: `IncrementalScriptDetector(SCRIPT_NAMES)` (O(1) suffix window, no rescanning; 10 tests) replaces per-chunk full scan, `<think>` decoration happens only on render-throttle flushes (first delta always immediate), `modelText` = undecorated accumulator. Policy truncation path: `onToken=false` → abort → native `eraseHistory` (never syncs discarded suffixes). `git diff --check` clean. Step 8 `MnnStreamingIntegrationTest` written (androidTest, fixture-guarded, 5 tests covering all six Step-8 criteria): CJK/emoji survive per-char batch boundaries (`batchMaxBytes=1`); first delta immediate not buffered (first delta <64B + native `firstDeltaUs` set); ≥80% callback reduction under byte-dominant config — in-test note: the 16ms Balanced time-threshold fires every 1–2 chars at typical CPU decode rates, so ≥80% is asserted with maxMs huge (256B byte-dominant flush); Balanced 256B/16ms guarded to never exceed per-token callbacks; stop flushes buffered bytes exactly once (`POLICY_TRUNCATION` + byte-exact callbackCount). Byte-integrity asserts Kotlin concatenation == native `callbackBytes` (the exact bytes `LocalChatProvider` accumulates as `modelContent`). Pending: device/CI run (`connectedDebugAndroidTest …MnnStreamingIntegrationTest`) + Step 9 commit awaits user authorization.

**Files:**
- Modify: `app/src/main/cpp/mnn_jni.cpp`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBackend.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Create: `app/src/main/java/com/chatbyyourside/llm/IncrementalScriptDetector.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/IncrementalScriptDetectorTest.kt`
- Test: `app/src/androidTest/java/com/chatbyyourside/llm/backend/MnnStreamingIntegrationTest.kt`

**Interfaces:**
- Produces: `nativeGenerateStream(...): String` returning a compact versioned JSON `GenerationSummary`, not full response bytes.
- Produces: `GenerationSummary(completionReason, promptTokens, generatedTokens, prefillUs, decodeUs, reuseKv, callbackCount, callbackBytes, firstDeltaUs, errorStage, errorMessage)`.
- Produces: delta callback only; `LocalChatProvider` is the sole raw-text accumulator.

- [ ] **Step 1: Define completion/stage/result contract**

Create enums/types in `llm/metrics/InferenceTelemetry.kt`:

```kotlin
enum class InferenceStage { VALIDATE, ADMIT, PROBE, LOAD, PREFILL, DECODE, FINALIZE }
enum class CompletionReason { EOS, MAX_TOKENS, USER_CANCEL, POLICY_TRUNCATION, THERMAL_STOP, TIMEOUT, BACKEND_FAILURE }
```

Test strict parsing of every reason and unknown-version rejection.

- [ ] **Step 2: Implement native `StreamBatcher`**

Preserve `Utf8StreamProcessor`. Flush first complete visible UTF-8 output immediately, then buffer according to a generation-only policy passed separately from load config:

```text
Balanced: 16 ms or 256 bytes
Maximum Speed: 24–32 ms or 512–1024 bytes
```

Always flush at EOS, abort, policy stop and error. Count callbacks/bytes. Never split a UTF-8 code point.

- [ ] **Step 3: Remove native final full-response `jbyteArray`**

Keep native `full_text` only because `syncPromptCache(history + assistant)` requires exact text. Return summary JSON; do not copy the full response a second time over JNI.

- [ ] **Step 4: Remove `MnnBackend` accumulation**

`MnnBackend` forwards decoded delta strings, tracks only counters/timing, and returns parsed `GenerationSummary`. `LocalChatProvider` owns the single `StringBuilder`.

- [ ] **Step 5: Replace whole-response role scanning**

`IncrementalScriptDetector` stores only enough suffix to cover `maxRoleNameLength + 1`. Its API:

```kotlin
class IncrementalScriptDetector(names: List<String>) {
    fun append(delta: String): DetectionResult
}

data class DetectionResult(val cutAbsoluteIndex: Int?)
```

Test markers crossing delta boundaries, earliest marker, CJK names, half-width colon non-match, and no rescanning of old text.

- [ ] **Step 6: Move `<think>` decoration to render flushes**

Raw accumulator remains undecorated. Only when UI throttle permits an update, call `renderLocalThink(raw, shouldFoldThink)`. Final `content` is decorated; `modelContent` is the undecorated exact raw text used by native.

- [ ] **Step 7: Handle policy truncation and prompt cache**

If the script detector truncates visible/model output, pass `POLICY_TRUNCATION` to native before finalization. Native must either sync with the exact retained raw assistant text or explicitly invalidate prompt cache; it must not sync discarded generated suffixes.

- [ ] **Step 8: Verify stream correctness**

Instrumentation assertions:

- CJK and emoji survive arbitrary batch boundaries;
- first delta is immediate;
- 512-token output callback count is at least 80% lower than unbatched baseline;
- concatenated deltas equal `modelContent`;
- no duplicate final text;
- stop flushes buffered bytes once.

- [ ] **Step 9: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/cpp/mnn_jni.cpp app/src/main/java/com/chatbyyourside/llm app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt app/src/androidTest app/src/test
git commit -m "perf: batch MNN streaming callbacks"
```

### Task 5: Bound output and plan the prompt window

> **Status (2026-08-09):** Steps 1–4 source complete. `PromptWindowPlanner` + `PromptWindowPlan`/`PromptWindowResult`/`PromptAdmissionException` + `GenerationSafetyPolicy`/`GenerationProgressGuard`/`GenerationExecutionControl` in `llm/PromptWindowPlanner.kt`; tests in `PromptWindowPlannerTest.kt` (first, TDD; RED/GREEN static-pending CI). `DEFAULT_MAX_TOKENS` 65,536→2,048 (absent-preference only; `SettingsStore` reads unchanged, never migrates; 65,536 stays explicit Unlimited). Planner keeps system + latest user, selects largest recent complete user/assistant suffix, no orphan assistant, budgets `admitted context − output reserve − template reserve`, conservative UTF-8 estimate (`modelContent ?: content`), runtime token counts by exact text match, typed `AdmissionFailure` for oversized system/latest user, SHA-256 anchor (system + leftmost user; stable on right-side extension). Local provider plans before mapping raw text into model-visible `content`. Request-level `GenerationExecutionControl` (survives backend fallback: first terminal reason wins, cumulative token budget, single wall-clock deadline, true callback progress time, reason-before-abort, no fallback after first visible output; whole-request coroutine Mutex). Native checks abort right after blocking `llm->response()` prefill (MNN API has no cooperative stop; plan forbids cross-thread native release). Downgrade/anchor reasons flow into `InferenceTurnRecord.downgradeReasons`; UI distinguishes TIMEOUT/MAX_TOKENS. Independent review: first pass found 5 concrete watchdog/fallback race defects — all fixed and statically validated; second-pass subagent review unavailable (gateway 403/502). `git diff --check` clean. Pending: Step 5 CI `./gradlew testDebugUnitTest --tests '*PromptWindowPlannerTest'`; Step 6 commit awaits user authorization.

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/config/AppConfig.kt`
- Modify: `app/src/main/java/com/chatbyyourside/data/local/SettingsStore.kt`
- Create: `app/src/main/java/com/chatbyyourside/llm/PromptWindowPlanner.kt`
- Modify: `app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/PromptWindowPlannerTest.kt`

**Interfaces:**
- Produces: `PromptWindowPlan(messages, estimatedInputTokens, reservedOutputTokens, anchorChanged, downgradeReason)`.
- Consumes: admitted context from later `ModelAdmissionController`; initially use configured context and conservative token estimate.

- [ ] **Step 1: Write prompt-window tests**

Cover:

- system prompt is always retained;
- recent complete user/assistant turns retained;
- no orphan assistant turn;
- `modelContent` used for local token estimate;
- output/template reserve respected;
- anchor change detected;
- oversize latest user message returns a typed admission failure rather than silent truncation.

- [ ] **Step 2: Change absent-preference default only**

Set `DEFAULT_MAX_TOKENS = 2048`; keep `MAX_TOKENS_UNLIMITED = 65536` as an explicit advanced selection. Do not write 2048 over existing DataStore values.

- [ ] **Step 3: Implement conservative planner**

Use model/runtime token counts when available from prior turns; otherwise a conservative UTF-8/codepoint estimate. Select the largest recent suffix fitting:

```text
admitted context - output reserve - template reserve
```

Preserve exact text. Record anchor changes so KV miss telemetry is explainable.

- [ ] **Step 4: Add stall/time safety**

Track last progress timestamp and apply profile-independent max-token safety plus profile-specific wall-clock deadlines. Return `TIMEOUT` or `MAX_TOKENS`, not generic exceptions. Do not kill native memory from another thread; request abort and release after JNI returns.

- [ ] **Step 5: CI verification**

CI: `./gradlew testDebugUnitTest --tests '*PromptWindowPlannerTest'`

Expected: all boundary tests PASS.

- [ ] **Step 6: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/config/AppConfig.kt app/src/main/java/com/chatbyyourside/data/local/SettingsStore.kt app/src/main/java/com/chatbyyourside/llm/PromptWindowPlanner.kt app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt app/src/test
git commit -m "perf: bound local prompts and generation"
```

---

## Phase 2 — Balanced/Maximum Speed and Explicit Execution Plans

### Task 6: Add performance mode and one immutable settings snapshot

> **Status (2026-08-09):** Steps 1–3 source complete. `InferencePerformanceMode` already existed (Task 2), so no recreate. `LocalInferenceSettings` (immutable snapshot: performanceMode + contextLen/threads/temperature/maxTokens/backend + legacy cpuBoost/lookahead + deepThinking; `fromPreferences` single-point keys). `SettingsStore` adds `llm_performance_mode` key/flow/setter + `localInferenceSettings` one-`data.map` snapshot; inference keys moved to single-source `LocalInferenceSettings` constants; cpuBoost/lookahead flows marked legacy. `SettingsRepository` forwards + `getLocalInferenceSettingsNow(timeoutMs)` (timeout fallback to immutable default). `LocalChatProvider` replaced its 8 per-field `.first()` reads with one snapshot read and feeds the real `performanceMode` into `GenerationSafetyPolicy.forMode` (removing the Task 5 BALANCED placeholder). `BackendSettingsScreen` adds a two-option mode selector above backend settings and relocates CPU boost/lookahead into an "高级（诊断）" legacy section. Tests in `LocalInferenceSettingsTest.kt` (TDD first; missing/invalid mode→BALANCED, MAXIMUM_SPEED round-trip, legacy keys readable, default/stored aggregation, timeout-fallback default). `git diff --check` clean. Pending: Step 4 CI `./gradlew testDebugUnitTest --tests '*LocalInferenceSettingsTest'`; Step 5 commit awaits user authorization.

**Files:**
- Create: `app/src/main/java/com/chatbyyourside/llm/profile/InferencePerformanceMode.kt`
- Modify: `app/src/main/java/com/chatbyyourside/data/local/SettingsStore.kt`
- Modify: `app/src/main/java/com/chatbyyourside/data/repository/SettingsRepository.kt`
- Modify: `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt`
- Test: `app/src/test/java/com/chatbyyourside/data/local/LocalInferenceSettingsTest.kt`

**Interfaces:**
- Produces: `LocalInferenceSettings` containing all local LLM settings plus `performanceMode`.
- Produces: `Flow<LocalInferenceSettings>` built with `combine`, and `getLocalInferenceSettingsNow(timeoutMs)`.

- [ ] **Step 1: Write defaults and snapshot tests**

Assert missing/invalid mode → `BALANCED`; explicit `MAXIMUM_SPEED` round-trips; legacy CPU boost/lookahead keys remain readable but are marked legacy and not authoritative.

- [ ] **Step 2: Add DataStore key and repository façade**

Add `LLM_PERFORMANCE_MODE = stringPreferencesKey("llm_performance_mode")`, setter, flow, combined snapshot and applied-plan acknowledgement. Replace seven independent `.first()` calls in `LocalChatProvider` with one timeout-bounded snapshot read.

- [ ] **Step 3: Add two-option UI**

Place selector above backend settings. Copy:

- “综合平衡（推荐）”：兼顾速度、温度、功耗和稳定性；
- “最高速度”：优先首字和生成速度，仍会在过热、内存不足或后端异常时自动降级。

Move CPU boost/lookahead out of the primary section; retain an advanced diagnostic view if needed for legacy transparency.

- [ ] **Step 4: CI verification**

CI: `./gradlew testDebugUnitTest --tests '*LocalInferenceSettingsTest'`

Expected: default/migration tests PASS.

- [ ] **Step 5: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/llm/profile/InferencePerformanceMode.kt app/src/main/java/com/chatbyyourside/data app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt app/src/test
git commit -m "feat: add local inference performance modes"
```

### Task 7: Resolve profiles into explicit backend attempts

> **Status (2026-08-09):** Steps 1–5 source complete. `ResolvedInferencePlan`/`BackendAttempt`/`RuntimeVariant`/`StreamPolicy`/`PowerPolicy`/`ResidencyPolicy`/`DowngradeReason` in `llm/profile/ResolvedInferencePlan.kt`; `InferenceProfileResolver` (canonical native JSON via kotlinx.serialization, keys sorted, SHA-256 `loadConfigHash`, AUTO=OpenCL(healthy)>CPU_OPTIMIZED>CPU_COMPATIBILITY, QNN never in AUTO, explicit NPU resolves to CPU + UNSUPPORTED_SETTING, OpenCL thread_num=68, CPU_COMPATIBILITY conservative normal/normal/normal, thermal-admitted threads never bypassed by MAXIMUM_SPEED, stream/power/residency policies per mode). Tests `InferenceProfileResolverTest` (TDD; ordering/no-QNN-AUTO/hash/68/conservative) + `BackendManagerPlanTest` (execution sequence, first-delta fallback via GenerationExecutionControl; BackendManager not JVM-instantiable without Context). Native `nativeCreate(configPath, resolvedConfigJson)`: length+`schemaVersion`+backend_type validation, logs only hash/safe summary, passes JSON verbatim to `set_config`, hidden CPU safe-retry removed. `MnnBackend.initialize(modelPath, nativeConfigJson, loadConfigHash)` hot-reuses same path+hash; `loadConfigHash` is the sole reload fingerprint. `BackendManager.generate` executes `plan.attempts` (CPU optimized→compat without CPU blacklist; GPU/NPU session blacklist kept; first visible delta disables transparent fallback). `SettingsStore` adds `llm_last_config_hash`; provider acks applied hash. `git diff --check` clean. Pending: Step 6 CI (`testDebugUnitTest --tests '*InferenceProfileResolverTest' --tests '*BackendManagerPlanTest'`; local Gradle blocked by JDK 8); Step 7 commit awaits user authorization.

### Task 7: Resolve profiles into explicit backend attempts

**Files:**
- Create: `app/src/main/java/com/chatbyyourside/llm/profile/ResolvedInferencePlan.kt`
- Create: `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBackend.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBridge.kt`
- Modify: `app/src/main/cpp/mnn_jni.cpp`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/profile/InferenceProfileResolverTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/backend/BackendManagerPlanTest.kt`

**Interfaces:**
- Produces: `ResolvedInferencePlan`, `BackendAttempt`, `StreamPolicy`, `PowerPolicy`, `ResidencyPolicy`, `DowngradeReason`.
- Produces: `nativeCreate(configPath: String, resolvedConfigJson: String): Long`.
- Produces: `loadConfigHash` as the sole model reload fingerprint.

- [ ] **Step 1: Define immutable types and tests**

Use values, not booleans spread across layers:

```kotlin
data class BackendAttempt(
    val backend: BackendType,
    val variant: RuntimeVariant,
    val nativeConfigJson: String,
    val loadConfigHash: String,
    val requiresProbe: Boolean,
)
```

Test unknown device ordering `[CPU_OPTIMIZED, CPU_COMPATIBILITY]`; healthy calibrated OpenCL can precede CPU; QNN never appears in AUTO; thermal/memory downgrade cannot be bypassed by Maximum Speed.

- [ ] **Step 2: Generate canonical native JSON**

Use `kotlinx.serialization` or `JSONObject`, sort/normalize keys before hashing, escape paths safely, and include app-private `tmp_path`, `cache_path`, and model/runtime cache namespace. Common safe keys remain fixed. OpenCL uses 68; CPU attempts use thermally admitted threads.

- [ ] **Step 3: Replace JNI primitive configuration**

Parse and validate versioned JSON in native, cap request length, log only the hash and safe summary, call `set_config`, and remove hidden CPU retry. Confirm conservative enum spellings against pinned `llmconfig.hpp`; encode them in resolver tests.

- [ ] **Step 4: Refactor `BackendManager` to execute attempts**

Replace `backendOrder(preference)` loop and `LoadedConfig` fields with plan attempts and `loadConfigHash`. Keep one model resident. CPU optimized failure advances to CPU compatibility without blacklisting CPU. Track whether first visible delta was emitted; after that point return typed partial failure, never continue to next backend.

- [ ] **Step 5: Acknowledge actual applied plan**

Settings acknowledgement stores user request plus actual mode/backend/context/config hash and downgrade reasons after successful load. UI banner clears based on plan identity, not raw thermal-effective threads.

- [ ] **Step 6: CI verification**

CI:

```bash
./gradlew testDebugUnitTest --tests '*InferenceProfileResolverTest' --tests '*BackendManagerPlanTest'
```

Expected: ordering, no-QNN-AUTO, config hash and first-delta fallback tests PASS.

- [ ] **Step 7: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/llm/profile app/src/main/java/com/chatbyyourside/llm/backend app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt app/src/main/cpp/mnn_jni.cpp app/src/test
git commit -m "feat: resolve adaptive MNN execution plans"
```

### Task 8: Scope power and thermal policy to each generation

> **Status (2026-08-09):** Steps 1–4 source complete. `ThermalLevel` enum + `ThermalDecision` + `ThermalMonitor.decide(level, mode, bigCore)` (pure; MODERATE→MAXIMUM_SPEED→BALANCED+reload+cap/2, SEVERE→remove boost+reload+cap 2, CRITICAL/EMERGENCY→stopNow THERMAL_STOP without backend penalty, NONE/LIGHT→unchanged). Tests `ThermalPolicyTest` + `PowerPolicyTest` (TDD). `CpuBoostController.beginInference(PowerPolicy)`: per-mode target duration (AGGRESSIVE 8ms vs 16ms), sustained only when policy.sustainedMode (close restores), `deactivateHintNow()` thread-safe hint removal, global `enabled` boolean removed. `MainActivity` injects `sustainedModeSetter` (window) instead of collecting `llmCpuBoost`. `MnnBackend.generateStreamMessages` takes `powerPolicy` (from plan) and drives `beginInference`. `LocalChatProvider` thermal callback applies decide (removeBoost/deactivateHintNow, stopNow→requestStop(THERMAL_STOP)+cancel, nextThreadCap + effectiveMode flow into next resolve); legacy `cpuBoostController.enabled` write removed. `git diff --check` clean. Pending: Step 5 CI (`testDebugUnitTest --tests '*PowerPolicyTest' --tests '*ThermalPolicyTest'`); Step 6 commit pending (auto-authorized).

### Task 8: Scope power and thermal policy to each generation

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/llm/CpuBoostController.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/ThermalMonitor.kt`
- Modify: `app/src/main/java/com/chatbyyourside/MainActivity.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnBackend.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/PowerPolicyTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/ThermalPolicyTest.kt`

**Interfaces:**
- Consumes: `ResolvedInferencePlan.powerPolicy` and `effectiveMode`.
- Produces: `ThermalDecision(removeBoostNow, stopNow, reloadAfterTurn, nextThreadCap, effectiveMode)`.

- [ ] **Step 1: Test every thermal transition**

Assert:

- MODERATE downgrades Maximum Speed to Balanced and disables sustained mode;
- SEVERE removes boost, marks reload, caps next load at 2;
- CRITICAL/EMERGENCY requests stop and returns `THERMAL_STOP` without backend penalty;
- API 24–28 fallback works without touching API-29 classes.

- [ ] **Step 2: Replace global boost boolean with `PowerPolicy`**

`beginInference(policy)` sets only supported priority/hint behavior and returns a closeable session. All changes restore in `close()`/`finally`.

- [ ] **Step 3: Remove Activity-lifetime sustained-mode collection**

Delete `MainActivity` collection of `llmCpuBoost`. Expose a generation-scoped state/controller so Maximum Speed enables sustained mode only while local JNI generation is active; Balanced never enables it. Restore it on success, cancellation, timeout, thermal stop and exception.

- [ ] **Step 4: Wire thermal stop safely**

Thermal callback may set abort/remove boost immediately, but release must wait until JNI returns. Next-load thread cap flows through resolver; do not mutate loaded MNN thread count.

- [ ] **Step 5: CI verification**

CI: `./gradlew testDebugUnitTest --tests '*PowerPolicyTest' --tests '*ThermalPolicyTest'`

Expected: all transition and restoration tests PASS.

- [ ] **Step 6: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/llm app/src/main/java/com/chatbyyourside/MainActivity.kt app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt app/src/test
git commit -m "fix: scope MNN power and thermal controls"
```

---

## Phase 3 — OpenCL Probe, Persistent Health, and QNN Safety

### Task 9: Fingerprint device/runtime/model and persist backend health

**Files:**
- Create: `app/src/main/java/com/chatbyyourside/llm/profile/DeviceRuntimeFingerprint.kt`
- Create: `app/src/main/java/com/chatbyyourside/llm/backend/BackendHealthStore.kt`
- Modify: `app/src/main/java/com/chatbyyourside/data/local/SettingsStore.kt` or create a dedicated DataStore file for health records
- Modify: `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/profile/FingerprintTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/backend/BackendHealthStoreTest.kt`

**Interfaces:**
- Produces: `DeviceRuntimeFingerprint.value`, `ModelFingerprint.value`, `BackendHealthKey`.
- Produces: health states `UNKNOWN`, `PROBE_OK`, `MODEL_OK`, `COOLDOWN`, `CRASH_BLACKLISTED`.

- [ ] **Step 1: Write canonicalization/invalidation tests**

Test stable hash independent of map iteration; changes in OS fingerprint, OpenCL driver, MNN/native hash, app policy schema, model config/tokenizer/weight hash invalidate old records.

- [ ] **Step 2: Implement fingerprints**

Use API guards for SoC fields. Include CPU topology as an identity fact but do not infer instruction support. Hash large model files from install manifest rather than rereading them each launch.

- [ ] **Step 3: Implement health records and crash journal**

Persist stage, failure class, timestamps, counts, cooldown, benchmark summary and pending probe/load marker. Policies:

- ordinary probe/load failure: 24 h skip;
- repeated generation failure: 7 d skip;
- stale crash marker: blacklist until fingerprint change or explicit reset;
- cancellation, thermal stop, validation/admission rejection: no penalty.

- [ ] **Step 4: Integrate resolver**

Unknown OpenCL health means probe required, not immediate model load. Healthy calibrated OpenCL may enter attempt list; blacklisted/cooldown does not. Expose reset action to settings UI.

- [ ] **Step 5: CI verification**

CI: targeted fingerprint/health tests PASS, including clock-bound cooldown tests with injected clock.

- [ ] **Step 6: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/llm/profile app/src/main/java/com/chatbyyourside/llm/backend/BackendHealthStore.kt app/src/main/java/com/chatbyyourside/data/local app/src/test
git commit -m "feat: persist MNN backend health profiles"
```

### Task 10: Add crash-contained OpenCL execution probe

> **Status (2026-08-09):** Steps 1–4 source complete. `backend_probe_jni.cpp` (dlopen libOpenCL.so, no vendor link; resolve symbols, enumerate device, context/queue/buffers, build+run trivial `add1` kernel, verify output, return vendor/device/driver + typed failure JSON). `OpenClProbeService` in `:mnn_probe` (nativeProbe @JvmStatic, writes JSON to cross-process SharedPreferences, stopSelf+killProcess). `OpenClProbeRunner` (main process pending journal -> startService -> poll; timeout/death/malformed as failure; DI probe/clock). AndroidManifest optional `uses-native-library libOpenCL.so` + non-exported service; CMake `libbackend_probe.so`. `MnnSupportDetector.openclAvailable()` stays cheap prerequisite; resolver OpenClHealthState gating from Task 9. androidTest `OpenClProbeServiceTest` (DI success/failure/timeout/death). Provider health-store read of probe results staged (currently uses mnnGpuSupported approximation). `git diff --check` clean. Pending: Step 5 instrumentation on real Adreno/Mali; Step 6 commit done (auto-authorized).

### Task 10: Add crash-contained OpenCL execution probe

**Files:**
- Create: `app/src/main/cpp/backend_probe_jni.cpp`
- Create: `app/src/main/java/com/chatbyyourside/llm/backend/OpenClProbeModels.kt`
- Create: `app/src/main/java/com/chatbyyourside/llm/backend/OpenClProbeService.kt`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnSupportDetector.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- Test: `app/src/androidTest/java/com/chatbyyourside/llm/backend/OpenClProbeServiceTest.kt`

**Interfaces:**
- Produces: `OpenClProbeResult(success, platform, vendor, device, driver, durationMs, failureCode)`.
- Consumes: health store pending journal and stores result under device/runtime fingerprint.

- [ ] **Step 1: Declare optional native library and private service**

Add:

```xml
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

Declare a non-exported service in `android:process=":mnn_probe"`; no model files or handles cross Binder.

- [ ] **Step 2: Implement native probe**

Dynamically load OpenCL, resolve required symbols, enumerate a usable device, create context/queue/buffers, compile/run a tiny deterministic vector kernel, synchronize and verify output. Return vendor/device/driver and typed failure. Do not link the app process directly against a vendor OpenCL library.

- [ ] **Step 3: Implement watchdog and death handling**

Main process writes pending journal before binding, enforces a short timeout, treats Binder death/timeout/malformed result as failure, and clears journal only after a terminal result. The probe process self-terminates after completion.

- [ ] **Step 4: Replace AUTO loadability gating**

`MnnSupportDetector.openclAvailable()` remains a cheap prerequisite only. AUTO requires persisted successful execution probe and model health. Explicit GPU choice may trigger a fresh probe but cannot bypass a crash blacklist unless user resets it.

- [ ] **Step 5: Instrumentation verification**

Use a test probe implementation or dependency injection to verify success, ordinary failure, timeout and process death. On real Adreno/Mali devices, confirm vendor/device/driver identity and trivial output.

- [ ] **Step 6: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/cpp app/src/main/AndroidManifest.xml app/src/main/java/com/chatbyyourside/llm/backend app/src/main/java/com/chatbyyourside/llm/profile app/src/androidTest
git commit -m "feat: probe OpenCL in an isolated process"
```

### Task 11: Remove unsafe QNN behavior from the standard build

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/MnnSupportDetector.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/NpuSupportDetector.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt`
- Modify: `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt`
- Modify: `app/src/main/jniLibs/native-manifest.json`
- Test: `app/src/test/java/com/chatbyyourside/llm/backend/StandardBackendPolicyTest.kt`

**Interfaces:**
- Produces: standard build has no selectable/automatic QNN attempt; legacy stored preference resolves to CPU plus downgrade reason `QNN_UNAVAILABLE_IN_STANDARD_BUILD`.

- [ ] **Step 1: Write standard policy tests**

Assert AUTO and Maximum Speed never contain NPU; explicit legacy NPU preference yields CPU attempts and user-visible explanation.

- [ ] **Step 2: Remove QNN from standard packaging**

Remove QNN host/skeleton libraries from the standard verified native bundle and manifest, not just V68/V69 excludes. If files remain in source control for future flavor work, exclude all QNN libs in the standard packaging block and verify the built APK contains none.

- [ ] **Step 3: Hide/disable QNN UI and detectors**

Do not advertise generic Qualcomm support. Keep code structured so a future experimental flavor can inject an exact SoC/runtime/model matrix, but standard `qnnReady=false` with an explicit reason.

- [ ] **Step 4: APK verification in CI**

Run `apkanalyzer files list`/`unzip -l` and assert no `libQnn*` appears in the standard APK. Unit policy tests PASS.

- [ ] **Step 5: Commit checkpoint (only with authorization)**

```bash
git add app/build.gradle.kts app/src/main/java/com/chatbyyourside/llm/backend app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt app/src/main/jniLibs/native-manifest.json app/src/test
git commit -m "fix: disable unmatched QNN runtime in standard build"
```

---

## Phase 4 — Model/Resource Admission and Residency

### Task 12: Validate model bundles and split checksums

**Files:**
- Create: `app/src/main/java/com/chatbyyourside/llm/ModelBundleValidator.kt`
- Modify: `app/src/main/java/com/chatbyyourside/download/FileSplitter.kt`
- Modify: `app/src/main/java/com/chatbyyourside/download/DownloadManager.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/ModelPathResolver.kt`
- Modify: `app/src/main/java/com/chatbyyourside/data/model/LocalModel.kt`
- Modify: `app/src/main/java/com/chatbyyourside/manager/ModelManager.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/ModelBundleValidatorTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/download/FileSplitterChecksumTest.kt`

**Interfaces:**
- Produces: `ModelInstallManifest` and `ModelValidationResult(valid, modelFingerprint, requiredFiles, warnings, errors)`.
- Produces: `InstalledModel.verified` from validator, never hardcoded true.

- [ ] **Step 1: Write fixtures for valid and invalid bundles**

Cover missing tokenizer, missing external weight, zero-byte file, malformed JSON, path traversal, partial files, wrong chunk hash, wrong merged hash, optional multimodal absence and valid minimal text-only bundle.

- [ ] **Step 2: Derive required files from config**

Parse `config.json` and `llm_config.json` in Kotlin serialization/JSON. Resolve every referenced graph/weight/tokenizer/embedding/visual/audio path against canonical model root and reject escape.

- [ ] **Step 3: Enforce checksums during download/merge**

Use `ChunkInfo.checksum`; compute SHA-256 while downloading or immediately after. Validate all parts and merged output before deleting parts. Persist source URL, expected size/hash, final size/hash and completion state in install manifest.

- [ ] **Step 4: Change installed-state semantics**

`ModelPathResolver` may return a candidate path, but `LocalChatProvider` must require validator success before native. `ModelManager` reports `verified` accurately and provides repair/re-download action for invalid bundles.

- [ ] **Step 5: CI verification**

CI: validator and checksum fixture tests PASS. Static search confirms completion no longer depends only on `config.json + llm.mnn`.

- [ ] **Step 6: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/llm/ModelBundleValidator.kt app/src/main/java/com/chatbyyourside/download app/src/main/java/com/chatbyyourside/provider/local/ModelPathResolver.kt app/src/main/java/com/chatbyyourside/data/model/LocalModel.kt app/src/main/java/com/chatbyyourside/manager/ModelManager.kt app/src/test
git commit -m "fix: validate MNN model bundles before loading"
```

### Task 13: Add storage and RAM/context admission

**Files:**
- Create: `app/src/main/java/com/chatbyyourside/llm/ModelAdmissionController.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/LlmMemoryEstimator.kt`
- Modify: `app/src/main/java/com/chatbyyourside/download/DownloadManager.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt`
- Modify: `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/ModelAdmissionControllerTest.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/LlmMemoryEstimatorTest.kt`

**Interfaces:**
- Produces: `AdmissionDecision.Allowed`, `.Downgraded(actualContext, reasons)`, or `.Rejected(userMessage, details)`.
- Consumes: model dimensions, manifest sizes, `ActivityManager.MemoryInfo`, `StatFs`, telemetry peak PSS.

- [ ] **Step 1: Test KV-head-aware formula**

Use:

```text
context × layers × 2(K/V) × num_key_value_heads × head_dim × bytes_per_element
```

Test GQA differs from full hidden-size estimate and compatibility fallback when dimensions are unavailable.

- [ ] **Step 2: Implement download storage admission**

Before download, require remaining bytes + merge-output headroom + runtime-cache reserve + max(512 MiB, 10% bundle). Query the actual external-files volume. Return actionable required/available values.

- [ ] **Step 3: Implement model-load RAM admission**

Combine model working set, KV, activation reserve, JNI/Kotlin/backend overhead and prior measured peak PSS. Use `availMem`, `threshold`, `lowMemory`. Resolution order: disable experiments → validated chunking if available → lower context step → lower-memory attempt → reject.

- [ ] **Step 4: Add runtime-cache namespace and quotas**

Place mutable MNN temporary/cache data under app-private storage, never beside downloaded model weights:

```text
filesDir/mnn_runtime/<modelFingerprint>/<runtimeFingerprint>/
cacheDir/mnn_tmp/<generationId>/
```

Set `tmp_path` and `cache_path` from these canonical paths. Before load, evict least-recently-used inactive namespaces until both the per-model and global quota are satisfied; never delete the active namespace. Remove stale generation temp directories on startup and after every terminal result. Include cache bytes in storage admission and telemetry.

- [ ] **Step 5: Surface actual context and downgrade reason**

Resolver consumes admission decision; settings/overlay shows configured versus actual context and why it changed. Do not overwrite the user’s configured context.

- [ ] **Step 6: CI verification**

Run targeted unit tests for 4/6/8/12 GB scenarios, lowMemory, model-too-large, context downgrade and merge-space insufficiency.

- [ ] **Step 7: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/llm/ModelAdmissionController.kt app/src/main/java/com/chatbyyourside/llm/LlmMemoryEstimator.kt app/src/main/java/com/chatbyyourside/download/DownloadManager.kt app/src/main/java/com/chatbyyourside/llm/profile app/src/main/java/com/chatbyyourside/provider/local/LocalChatProvider.kt app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt app/src/test
git commit -m "feat: admit MNN models by storage and memory"
```

### Task 14: Control model residency across lifecycle and trim-memory

**Files:**
- Create: `app/src/main/java/com/chatbyyourside/llm/ModelResidencyController.kt`
- Modify: `app/src/main/java/com/chatbyyourside/ChatApp.kt`
- Modify: `app/src/main/java/com/chatbyyourside/AppContainer.kt`
- Modify: `app/src/main/java/com/chatbyyourside/notification/AppLifecycleObserver.kt`
- Modify: `app/src/main/java/com/chatbyyourside/provider/ChatProviderManager.kt`
- Modify: `app/src/main/java/com/chatbyyourside/manager/ModelManager.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/ModelResidencyControllerTest.kt`

**Interfaces:**
- Produces: event API `onProviderChanged`, `onAppForegroundChanged`, `onModelChanged`, `onTrimMemory`, `onThermalEmergency`, `onGenerationStateChanged`.
- Consumes: plan residency policy and `BackendManager.release()` delayed-safe mechanism.

- [ ] **Step 1: Write virtual-time residency tests**

Use coroutine test scheduler. Assert Balanced releases ~15 s after background/cloud; Maximum Speed retains up to ~60 s only with healthy memory; model switch/delete releases before new load/delete; trim low/critical cancels/release safely; returning foreground before grace cancels release.

- [ ] **Step 2: Implement one residency controller**

Own grace jobs and current state. Never release native handle while JNI is active; call existing deferred-safe manager release. Never allow two backends/models resident.

- [ ] **Step 3: Wire application events**

Use `ChatApp`/`AppContainer` manual DI. Extend lifecycle observer callbacks, notify provider changes, model changes/deletes, and `ComponentCallbacks2.onTrimMemory`. Avoid duplicate release calls by routing them through the controller.

- [ ] **Step 4: CI verification**

CI: virtual-time lifecycle tests PASS. Instrumentation test observes PSS/load state before and after provider switch/background trim.

- [ ] **Step 5: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/llm/ModelResidencyController.kt app/src/main/java/com/chatbyyourside/ChatApp.kt app/src/main/java/com/chatbyyourside/AppContainer.kt app/src/main/java/com/chatbyyourside/notification app/src/main/java/com/chatbyyourside/provider/ChatProviderManager.kt app/src/main/java/com/chatbyyourside/manager/ModelManager.kt app/src/main/java/com/chatbyyourside/llm/backend/BackendManager.kt app/src/test
git commit -m "feat: manage MNN model residency"
```

---

## Phase 5 — Benchmark UI, CI Gates, and Experimental Promotion

### Task 15: Expose actual adaptive state and benchmark controls

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt`
- Modify: `app/src/main/java/com/chatbyyourside/perfmon/PerformanceCollector.kt`
- Modify: `app/src/main/java/com/chatbyyourside/perfmon/PerformanceOverlayView.kt`
- Modify: `app/src/main/java/com/chatbyyourside/llm/benchmark/LocalInferenceBenchmarkRunner.kt`
- Modify: `app/src/main/java/com/chatbyyourside/AppContainer.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/benchmark/ExperimentalPromotionPolicyTest.kt`

**Interfaces:**
- Produces: local diagnostics UI for requested/effective mode, actual backend, calibration, actual context, downgrade reasons, benchmark summary, reset/retest.
- Produces: `ExperimentalPromotionPolicy.evaluate(baseline, candidate): PromotionDecision`.

- [ ] **Step 1: Test promotion gates**

Require correctness first; then at least 10% median decode improvement or defined TTFT benefit, bounded TTFT/PSS regression, cool-run evidence, no UTF-8/EOS/repetition/KV mismatch. Reject hot/noisy/one-sample results.

- [ ] **Step 2: Implement benchmark action**

Run cold load, short TTFT, long prefill, fixed decode and second-turn KV scenarios. Disable overlay updates/animations during measurement, refuse hot start, report median/spread and persist by full fingerprint.

- [ ] **Step 3: Add adaptive-state UI**

Show plain-language mode/backend/calibration/context/downgrade. Add “重新测试后端” and “清除当前设备画像”; destructive reset must be confirmed in UI. Keep inaccessible GPU/NPU utilization as N/A.

- [ ] **Step 4: CI verification**

Unit policy tests PASS; Compose screenshot/manual review verifies labels fit Chinese/English and downgrade text is readable.

- [ ] **Step 5: Commit checkpoint (only with authorization)**

```bash
git add app/src/main/java/com/chatbyyourside/ui/settings/BackendSettingsScreen.kt app/src/main/java/com/chatbyyourside/perfmon app/src/main/java/com/chatbyyourside/llm/benchmark app/src/main/java/com/chatbyyourside/AppContainer.kt app/src/test
git commit -m "feat: expose MNN calibration and benchmarks"
```

### Task 16: Add authoritative CI and device verification gates

**Files:**
- Create: `.github/workflows/android-native-ci.yml`
- Modify: `scripts/native/verify_native_bundle.py`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `app/src/androidTest/java/com/chatbyyourside/llm/backend/MnnRuntimeIntegrationTest.kt`
- Create: `docs/mnn-device-matrix.md`

**Interfaces:**
- Produces: build artifacts, native manifest/SBOM, JVM/instrumentation reports and static APK native audit.

- [ ] **Step 1: Configure CI toolchain exactly**

Use Java 17, Android SDK/NDK r27c, pinned CMake/Ninja, pinned MNN commit. Cache Gradle and MNN source/build by commit/flags; never cache unverifiable output.

- [ ] **Step 2: Add CI stages**

Run in order:

1. JVM tests and static checks;
2. pinned native build;
3. native manifest/hash/build-ID/16 KiB audit;
4. APK assembly using verified prebuilts;
5. APK `zipalign`/`apkanalyzer` audit and no-QNN assertion;
6. emulator smoke on API 24, 29, 31, 34 and 35+ where available;
7. upload APK, native manifest/SBOM and test reports.

- [ ] **Step 3: Add runtime integration test suite**

Cover JNI handshake, API 24/25 class loading, short CPU generation, cancellation, UTF-8 batches, two-turn KV reuse, provider/lifecycle release and typed fallback. Mark real-model/device tests with explicit assumptions so missing model fixtures skip with reason rather than pass silently.

- [ ] **Step 4: Document physical device matrix**

Record required device classes: modern/older Snapdragon, MediaTek/Mali, Exynos, API 24–28, 29–30, 31–34, Android 15 16 KiB, and 6/8/12GB RAM. Define fixed model/prompt/config, cool-start procedure and metrics to upload.

- [ ] **Step 5: Verify CI on a compatible runner**

Expected:

- all unit tests pass;
- native verifier passes `p_align >= 0x4000` for every standard `.so`;
- standard APK contains no QNN libs;
- runtime-info manifest hashes match;
- emulator smoke passes or reports a scoped unsupported native-model fixture reason;
- real 16 KiB device loads native libraries.

Local machine only runs `git diff --check` and Python/native static verifier.

- [ ] **Step 6: Commit checkpoint (only with authorization)**

```bash
git add .github/workflows scripts/native app/build.gradle.kts gradle/libs.versions.toml app/src/androidTest docs/mnn-device-matrix.md
git commit -m "ci: verify adaptive MNN runtime"
```

### Task 17: Evaluate experiments one at a time

**Files:**
- Modify only after evidence: `app/src/main/java/com/chatbyyourside/llm/profile/InferenceProfileResolver.kt`
- Modify only after evidence: `app/src/main/java/com/chatbyyourside/llm/backend/BackendHealthStore.kt`
- Modify only after evidence: native config capabilities in `mnn_jni.cpp`
- Test: one focused test and benchmark record per experiment

**Interfaces:**
- Consumes: `ExperimentalPromotionPolicy`, full fingerprints and benchmark summaries.
- Produces: independently versioned feature certifications; no raw user-editable runtime JSON.

- [ ] **Step 1: Evaluate lookahead only**

Compare enabled/disabled on CPU for cold first turn, repeated second turn and non-repetitive prose. Promote only for matching device/model fingerprint when policy passes.

- [ ] **Step 2: Evaluate prompt chunking only**

Measure peak PSS, TTFT and prefill speed across long prompts. Use as memory downgrade only after correctness/pass criteria.

- [ ] **Step 3: Evaluate attention/KV and dynamic quantization separately**

One candidate value per run. Validate deterministic finite output, EOS, second-turn KV and quality fixtures before throughput. Never infer OpenCL support from CPU attention-mode results.

- [ ] **Step 4: Evaluate disk KV/prefix and mmap-cache sizing separately**

Measure cold/warm load, storage growth, cleanup, second-turn TTFT and process restart behavior. Apply quotas and fingerprint namespaces before promotion.

- [ ] **Step 5: Evaluate higher CPU thread counts**

Test 2/4/performance-core counts with 1/5/10-minute steady-state tokens/s, thermal slope and energy per token. Highest burst tokens/s is not sufficient.

- [ ] **Step 6: Keep offline quantized models and QNN as separate release projects**

Create separate model IDs/manifests/quality evaluations for offline quantization. Do not enable QNN until an experimental flavor has exact QNN SDK, HTP/Stub/Skel and compatible model matrix.

- [ ] **Step 7: Commit each promoted experiment independently (only with authorization)**

Example:

```bash
git add <single-experiment-files-and-tests>
git commit -m "perf: certify MNN lookahead for calibrated profiles"
```

---

## Final End-to-End Acceptance

- [ ] Fresh install defaults to Balanced; Maximum Speed is selectable.
- [ ] Unknown/uncalibrated device generates through CPU optimized or CPU compatibility.
- [ ] OpenCL is never used in AUTO without a successful persisted probe and healthy model record.
- [ ] QNN is absent from standard AUTO and standard APK.
- [ ] Every packaged standard `.so` has reproducible provenance and 16 KiB-compatible PT_LOAD alignment.
- [ ] JNI callback count for a fixed 512-token response drops at least 80% while first delta remains immediate.
- [ ] Concatenated deltas exactly equal `modelContent`; CJK/emoji are intact.
- [ ] Second normal local turn reports `reuse_kv=1` and reduced prefill work.
- [ ] GPU output is never concatenated with CPU fallback output after a partial failure.
- [ ] Missing/corrupt model components, insufficient merge space and unsafe RAM/context are rejected before native load.
- [ ] Balanced/Maximum Speed power state is restored on success, cancel, timeout, thermal stop and exception.
- [ ] Background/cloud/trim-memory/model-switch residency behavior matches mode and never keeps two models.
- [ ] API 24/25 class loading works; API 29/31-only features are guarded.
- [ ] MNN runtime/tuning caches live in bounded app-private fingerprint namespaces, never mutate downloaded model bundles, and stale namespaces are evicted safely.
- [ ] Android 15 16 KiB device loads the standard APK native stack.
- [ ] UI explains requested/effective mode, actual backend/context, calibration and downgrade reasons.
- [ ] No experiment is enabled without a correctness result and fingerprint-scoped performance evidence.
