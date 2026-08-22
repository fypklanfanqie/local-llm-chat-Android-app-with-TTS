#!/usr/bin/env bash
# Reproducible build of the MNN native runtime for Android (arm64-v8a).
#
# Produces the verified-prebuilt libraries consumed by the app:
#   libMNN.so            — MNN LLM engine (LLM + low-mem + ARM82 + OpenCL, QNN OFF)
#   libmnn_jni.so        — MNN backend JNI wrapper (app/src/main/cpp/mnn_jni.cpp)
#   libcpu_sys_jni.so    — CPU affinity JNI wrapper (app/src/main/cpp/cpu_affinity_jni.cpp)
#   libc++_shared.so     — matching 26.1.10909125 (r26b) libc++ runtime
#
# Every standard .so is built for Android 15 16 KiB pages (PT_LOAD p_align >= 0x4000)
# and is recorded in app/src/main/jniLibs/native-manifest.json, then verified by
# scripts/native/verify_native_bundle.py.
#
# Design reference: docs/superpowers/specs/2026-08-08-mnn-adaptive-inference-optimization-design.md
#
# Environment:
#   ANDROID_NDK_HOME (required) — path to NDK 26.1.10909125 (r26b)
#   HTTP_PROXY / HTTPS_PROXY    — optional; respected as-is, NEVER set by this script
#   MNN_BUILD_STAGING           — optional ASCII-only build dir (default: ~/mnn-build)
#                                 The repo path may contain non-ASCII chars which break
#                                 some NDK/CMake tools, so staging is ASCII-only.
#
# Run on a Linux x86_64 host (CI). This script does NOT run Gradle.
set -euo pipefail

# ---------------------------------------------------------------------------
# Pinned versions (single source of truth — keep in sync with native-manifest.json,
# CMakeLists.txt CHAT_MNN_COMMIT, and MnnBridge.EXPECTED_MNN_COMMIT).
#
# NDK 版本说明（Task 8 统一）：仓库内预编译 .so 全部为本机 NDK 26.1.10909125 (r26b)
# 重编产物（manifest note 与提交 11ddc83 "local NDK 26 rebuild" 为准；本地暂存区
# CMakeCache 的 ANDROID_NDK 亦指向 26.1.10909125）。脚本曾写 27.2.12479018 (r27c)，
# 但从未产出过任何入库二进制，故统一回 26.1.10909125，与 manifest ndkVersion 及
# buildId/sha256 保持单一事实源。
# 若未来升级 NDK 27 重编：必须同步更新 manifest 的 ndkVersion 与全部 buildId/sha256
# （本脚本第 5 步会以 --generate 依据实际产物自动重新生成 manifest，随后 verify 校验）。
# ---------------------------------------------------------------------------
MNN_COMMIT_DEFAULT="af0142bcc7b76b7a5128373e285683dc04f55f69"
NDK_VERSION="26.1.10909125"
ANDROID_API=24
ANDROID_ABI="arm64-v8a"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STAGING="${MNN_BUILD_STAGING:-$HOME/mnn-build}"
MNN_SRC="$STAGING/MNN"
MNN_BUILD="$STAGING/mnn-build"
MNN_INSTALL="$STAGING/mnn-install"
JNI_BUILD="$STAGING/jni-build"

JNI_LIBS_DIR="$REPO_ROOT/app/src/main/jniLibs/$ANDROID_ABI"
MANIFEST="$REPO_ROOT/app/src/main/jniLibs/native-manifest.json"
VERIFIER="$REPO_ROOT/scripts/native/verify_native_bundle.py"

# ---------------------------------------------------------------------------
# Task 4：候选构建参数化（默认值 = 现状：构建 pinned commit 到生产路径）。
#
#   MNN_COMMIT_OVERRIDE   构建的 MNN commit（默认 = 上方 pinned $MNN_COMMIT_DEFAULT）
#   MNN_OUTPUT_DIR        标准 .so 输出目录（默认 = jniLibs/$ANDROID_ABI）
#   MNN_MANIFEST_OUT      manifest 输出路径（默认 = 生产 native-manifest.json）
#
# 规则（升级基础设施，不改默认行为）：
#   * 默认调用仍构建 pinned commit 到现有路径；
#   * 候选构建必须显式传入独立 ASCII staging 输出（如 $HOME/mnn-candidates/<commit>/arm64-v8a
#     与对应 manifest 路径），**绝不**写入生产 jniLibs 或生产 manifest；
#   * 本脚本永不改写 MnnBridge.EXPECTED_MNN_COMMIT / CMakeLists CHAT_MNN_COMMIT —— runtime
#     晋级由 Task 4 门禁通过后的独立变更完成（见 docs/mnn-upstream-runtime-delta.md §4）；
#   * 候选构建的 verifier 校验 manifest.mnnCommit == 请求的候选 commit（--expected-commit）。
# ---------------------------------------------------------------------------
MNN_COMMIT="${MNN_COMMIT_OVERRIDE:-$MNN_COMMIT_DEFAULT}"
OUTPUT_DIR="${MNN_OUTPUT_DIR:-$JNI_LIBS_DIR}"
MANIFEST_OUT="${MNN_MANIFEST_OUT:-$MANIFEST}"

if [[ "$MNN_COMMIT" != "$MNN_COMMIT_DEFAULT" || "$OUTPUT_DIR" != "$JNI_LIBS_DIR" || "$MANIFEST_OUT" != "$MANIFEST" ]]; then
    log "候选构建模式: commit=$MNN_COMMIT output=$OUTPUT_DIR manifest=$MANIFEST_OUT"
    log "候选构建绝不写入生产 jniLibs / 生产 manifest；MnnBridge.EXPECTED_MNN_COMMIT 不修改。"
fi

# 16 KiB page linker flag (also applied in cpp/CMakeLists.txt).
PAGE_FLAG="-Wl,-z,max-page-size=16384"

log() { printf '\033[1;34m[build_mnn]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[build_mnn error]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
[[ -n "${ANDROID_NDK_HOME:-}" ]] || die "ANDROID_NDK_HOME must point to NDK $NDK_VERSION (r26b)."
[[ -d "$ANDROID_NDK_HOME" ]] || die "ANDROID_NDK_HOME not found: $ANDROID_NDK_HOME"

# NDK 版本防漂移（fail-fast）：source.properties 的 Pkg.Revision 必须与 pinned
# NDK_VERSION 一致。Google 的 source.properties 格式为 "Pkg.Revision = 26.1.10909125"
# （纯点分版本号，无 r 前缀、无 "r26b" 简写），故精确匹配；一旦传入其它版本
# （如 CI 误装 27.2），在编译前即报错，避免产出与 manifest ndkVersion 不符的混源产物。
NDK_SOURCE_PROP="$ANDROID_NDK_HOME/source.properties"
[[ -f "$NDK_SOURCE_PROP" ]] || die "NDK source.properties not found: $NDK_SOURCE_PROP (expected NDK $NDK_VERSION)"
NDK_INSTALLED="$(sed -n 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$NDK_SOURCE_PROP" | head -n1)"
[[ -n "$NDK_INSTALLED" ]] || die "cannot read Pkg.Revision from $NDK_SOURCE_PROP (expected NDK $NDK_VERSION)"
[[ "$NDK_INSTALLED" == "$NDK_VERSION" ]] \
    || die "NDK version mismatch: $ANDROID_NDK_HOME is '$NDK_INSTALLED', expected '$NDK_VERSION' (r26b)"

NDK_TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
[[ -f "$NDK_TOOLCHAIN" ]] || die "NDK toolchain not found: $NDK_TOOLCHAIN (expected NDK $NDK_VERSION)"

CMAKE_BIN="${CMAKE_BIN:-cmake}"
NINJA_BIN="${NINJA_BIN:-ninja}"
command -v "$CMAKE_BIN" >/dev/null || die "cmake not found"
command -v "$NINJA_BIN" >/dev/null || die "ninja not found"
command -v git >/dev/null || die "git not found"
command -v python3 >/dev/null || die "python3 not found"

# ASCII-only staging (repo path may be non-ASCII).
mkdir -p "$STAGING"
case "$STAGING" in
    *[![:print:]]*) die "STAGING must be ASCII-only: $STAGING" ;;
esac

# Complete feature flag set actually passed to CMake (BUILD_ID 必须包含完整 feature flags)。
# 与 MNN_CMAKE_FLAGS 对应：LLM / 低内存 / weight dequant / transformer fuse / ARM82 / OpenCL /
# QNN OFF / Vulkan OFF / 16 KiB pages。改动 flag 集 = 新 build id = 新 native 身份。
FEATURE_FLAGS="llm,low_mem,cpu_weight_dequant_gemm,transformer_fuse,arm82,opencl,qnn_off,vulkan_off,16k_pages"
# Deterministic build id: short hash of (MNN commit + NDK + ABI + API + full flags).
BUILD_ID="$(printf '%s|%s|%s|%s|%s' "$MNN_COMMIT" "$NDK_VERSION" "$ANDROID_ABI" "$ANDROID_API" "$FEATURE_FLAGS" \
    | sha256sum | cut -c1-16)"
log "build id: $BUILD_ID (commit=$MNN_COMMIT ndk=$NDK_VERSION abi=$ANDROID_ABI api=$ANDROID_API flags=$FEATURE_FLAGS)"

# ---------------------------------------------------------------------------
# 1. Fetch pinned MNN source
# ---------------------------------------------------------------------------
if [[ ! -d "$MNN_SRC/.git" ]]; then
    log "cloning MNN into $MNN_SRC"
    git clone https://github.com/alibaba/MNN.git "$MNN_SRC"
fi
log "checking out MNN @ $MNN_COMMIT"
git -C "$MNN_SRC" fetch --depth 1 origin "$MNN_COMMIT"
git -C "$MNN_SRC" checkout "$MNN_COMMIT"

# ---------------------------------------------------------------------------
# 2. Build libMNN.so (LLM + low-mem + ARM82 + OpenCL; QNN OFF; 16 KiB pages)
# ---------------------------------------------------------------------------
# Flag names follow MNN's CMake at the pinned commit; CI fails fast if a name
# drifts. QNN stays OFF in the standard artifact (see design §7.2 / Task 11).
MNN_CMAKE_FLAGS=(
    -DCMAKE_TOOLCHAIN_FILE="$NDK_TOOLCHAIN"
    -DANDROID_ABI="$ANDROID_ABI"
    -DANDROID_PLATFORM="android-$ANDROID_API"
    -DANDROID_NDK="$ANDROID_NDK_HOME"
    -DCMAKE_BUILD_TYPE=Release
    -GNinja
    -DCMAKE_INSTALL_PREFIX="$MNN_INSTALL"
    # MNN features
    -DMNN_BUILD_LLM=ON
    # 关键：上游默认 MNN_SEP_BUILD=ON，会把 llm 构建成独立库且不链入 libMNN.so，
    # --target MNN 时 transformers/llm 完全不编译 → JNI 链接期 Llm 符号全部 undefined。
    # OFF 让 llm 以 OBJECT 库链进 libMNN.so（与生产 jniLibs 的 7.4MB 产物一致）。
    -DMNN_SEP_BUILD=OFF
    # OBJECT 库不允许 POST_BUILD custom_command（Android 分支会挂头文件拷贝命令）。
    # 条件为 IF(Android AND NOT MNN_BUILD_FOR_ANDROID_COMMAND)：置 ON 使 NOT 为假，
    # 走 ELSE 的 INSTALL(DIRECTORY) 分支把 llm 头文件装进 CMAKE_INSTALL_PREFIX/include。
    -DMNN_BUILD_FOR_ANDROID_COMMAND=ON
    # 上游变量名是 MNN_BUILD_SHARED_LIBS（带 S）；此前传的 MNN_BUILD_SHARED_LIB 是
    # 拼写错误（CMake "Manually-specified variables were not used" 警告可证）。
    -DMNN_BUILD_SHARED_LIBS=ON
    -DMNN_LOW_MEMORY=ON
    -DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON
    -DMNN_TRANSFORMERS_FUSE=ON
    -DMNN_USE_ARM82=ON
    -DMNN_OPENCL=ON
    -DMNN_BUILD_QNN=OFF
    -DMNN_VULKAN=OFF
    # 16 KiB page support：NDK r26 无 ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES 变量
    # （那是 NDK r27+ 的开关），实际对齐由下方 PAGE_FLAG 链接器参数保证。
    -DCMAKE_SHARED_LINKER_FLAGS="$PAGE_FLAG"
    -DMNN_BUILD_DEMO=OFF
    -DMNN_BUILD_TESTS=OFF
    -DMNN_BUILD_TOOLS=OFF
)

log "configuring MNN"
"$CMAKE_BIN" -S "$MNN_SRC" -B "$MNN_BUILD" "${MNN_CMAKE_FLAGS[@]}"
log "building MNN"
"$CMAKE_BIN" --build "$MNN_BUILD" --target MNN -j
log "installing MNN (headers + libMNN.so)"
"$CMAKE_BIN" --install "$MNN_BUILD" --component headers 2>/dev/null || true
# Ensure lib + include are where the JNI build expects them.
mkdir -p "$MNN_INSTALL/lib" "$MNN_INSTALL/include"
# 部分上游 CMake 布局会把产物放进多配置子目录（如 OFF/arm64-v8a/、Release/），
# 单配置路径 $MNN_BUILD/libMNN.so 未必存在 —— find 兜底取最新链接产物。
BUILT_LIBMNN="$(find "$MNN_BUILD" -name 'libMNN.so' -type f -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)"
[[ -n "$BUILT_LIBMNN" ]] || die "libMNN.so not found under $MNN_BUILD after build"
log "copying libMNN.so from ${BUILT_LIBMNN#$STAGING/}"
cp -f "$BUILT_LIBMNN" "$MNN_INSTALL/lib/libMNN.so"
# MNN installs headers under include/; if install did not copy them, copy manually.
if [[ ! -f "$MNN_INSTALL/include/llm/llm.hpp" ]]; then
    log "copying MNN headers manually"
    # llm.hpp 位于 transformers/llm/engine/include/llm/llm.hpp —— 其 include 根是
    # engine/include。直接按全相对路径安装会落到 include/transformers/...，
    # JNI 侧（MNN_DIR/include/llm/llm.hpp）找不到；这里以 engine/include 为根对齐安装。
    LLM_INCLUDE_ROOT="$MNN_SRC/transformers/llm/engine/include"
    if [[ -f "$LLM_INCLUDE_ROOT/llm/llm.hpp" ]]; then
        cp -rf "$LLM_INCLUDE_ROOT/." "$MNN_INSTALL/include/"
    fi
    # 兜底：其余 llm 相关头（如其它路径布局）+ MNN 主 include 全量
    (cd "$MNN_SRC" && find . -name '*.hpp' -path '*/include/llm/*' -print0 \
        | while IFS= read -r -d '' f; do rel="${f#./}"; rel="${rel#*include/}"; install -Dm644 "$f" "$MNN_INSTALL/include/$rel"; done)
    cp -rf "$MNN_SRC/include/." "$MNN_INSTALL/include/" 2>/dev/null || true
fi
[[ -f "$MNN_INSTALL/lib/libMNN.so" ]] || die "libMNN.so not built"
[[ -f "$MNN_INSTALL/include/llm/llm.hpp" ]] || die "llm/llm.hpp not found in install"

# ---------------------------------------------------------------------------
# 3. Build JNI wrappers (libmnn_jni.so + libcpu_sys_jni.so) via cpp/CMakeLists.txt
# ---------------------------------------------------------------------------
# Invokes the project's own CMakeLists (which applies CHAT_MNN_* defs and the
# 16 KiB linker flag). NOT run through Gradle — verified-prebuilt packaging.
JNI_CMAKE_FLAGS=(
    -DCMAKE_TOOLCHAIN_FILE="$NDK_TOOLCHAIN"
    -DANDROID_ABI="$ANDROID_ABI"
    -DANDROID_PLATFORM="android-$ANDROID_API"
    -DANDROID_NDK="$ANDROID_NDK_HOME"
    -DCMAKE_BUILD_TYPE=Release
    -GNinja
    -DMNN_DIR="$MNN_INSTALL"
    -DCHAT_MNN_COMMIT="$MNN_COMMIT"
    -DCHAT_MNN_JNI_ABI=1
    -DCHAT_MNN_BUILD_ID="$BUILD_ID"
)

log "configuring JNI wrappers"
"$CMAKE_BIN" -S "$REPO_ROOT/app/src/main/cpp" -B "$JNI_BUILD" "${JNI_CMAKE_FLAGS[@]}"
log "building JNI wrappers"
"$CMAKE_BIN" --build "$JNI_BUILD" -j

# ---------------------------------------------------------------------------
# 4. Copy standard .so into OUTPUT_DIR (QNN libs are NOT copied — Task 11)
#    Task 4：候选构建输出到独立 staging（$OUTPUT_DIR），不覆盖生产 jniLibs。
# ---------------------------------------------------------------------------
log "copying standard .so into $OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
cp -f "$MNN_INSTALL/lib/libMNN.so"      "$OUTPUT_DIR/libMNN.so"
cp -f "$JNI_BUILD/libmnn_jni.so"        "$OUTPUT_DIR/libmnn_jni.so"
cp -f "$JNI_BUILD/libcpu_sys_jni.so"    "$OUTPUT_DIR/libcpu_sys_jni.so"

# Matching 26.1.10909125 (r26b) libc++_shared.so (same toolchain as libMNN/JNI — ABI consistency).
LIBCPP_SRC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
[[ -f "$LIBCPP_SRC" ]] || LIBCPP_SRC="$ANDROID_NDK_HOME/sources/cxx-stl/llvm-libc++/libs/$ANDROID_ABI/libc++_shared.so"
[[ -f "$LIBCPP_SRC" ]] || die "libc++_shared.so not found in NDK"
cp -f "$LIBCPP_SRC" "$OUTPUT_DIR/libc++_shared.so"

# Remove any stale QNN libraries from the standard bundle (Task 11).
rm -f "$OUTPUT_DIR"/libQnn*.so

# ---------------------------------------------------------------------------
# 5. Generate manifest from the actual built binaries, then verify
#    Task 4：verifier 校验 manifest.mnnCommit == 本次实际构建的 commit（候选归属防混淆）。
# ---------------------------------------------------------------------------
log "generating $MANIFEST_OUT"
python3 "$VERIFIER" --generate \
    --dir "$OUTPUT_DIR" --manifest "$MANIFEST_OUT" \
    --mnn-commit "$MNN_COMMIT" --ndk-version "$NDK_VERSION" \
    --android-api "$ANDROID_API" --abi "$ANDROID_ABI"

log "verifying native bundle"
python3 "$VERIFIER" --dir "$OUTPUT_DIR" --manifest "$MANIFEST_OUT" \
    --expected-commit "$MNN_COMMIT"

log "done. standard .so in $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"
