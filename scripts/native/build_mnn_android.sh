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
MNN_COMMIT="af0142bcc7b76b7a5128373e285683dc04f55f69"
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

# 16 KiB page linker flag (also applied in cpp/CMakeLists.txt).
PAGE_FLAG="-Wl,-z,max-page-size=16384"

log() { printf '\033[1;34m[build_mnn]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[build_mnn error]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
[[ -n "${ANDROID_NDK_HOME:-}" ]] || die "ANDROID_NDK_HOME must point to NDK $NDK_VERSION (r26b)."
[[ -d "$ANDROID_NDK_HOME" ]] || die "ANDROID_NDK_HOME not found: $ANDROID_NDK_HOME"

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

# Deterministic build id: short hash of (MNN commit + NDK + ABI + API + flags).
BUILD_ID="$(printf '%s|%s|%s|%s|%s' "$MNN_COMMIT" "$NDK_VERSION" "$ANDROID_ABI" "$ANDROID_API" "llm,low_mem,arm82,opencl,16k" \
    | sha256sum | cut -c1-16)"
log "build id: $BUILD_ID"

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
    -DMNN_LOW_MEMORY=ON
    -DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON
    -DMNN_TRANSFORMERS_FUSE=ON
    -DMNN_USE_ARM82=ON
    -DMNN_OPENCL=ON
    -DMNN_BUILD_QNN=OFF
    -DMNN_VULKAN=OFF
    # 16 KiB page support (Android 15 flexible page sizes)
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    -DCMAKE_SHARED_LINKER_FLAGS="$PAGE_FLAG"
    -DMNN_BUILD_SHARED_LIB=ON
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
cp -f "$MNN_BUILD"/libMNN.so "$MNN_INSTALL/lib/libMNN.so"
# MNN installs headers under include/; if install did not copy them, copy manually.
if [[ ! -f "$MNN_INSTALL/include/llm/llm.hpp" ]]; then
    log "copying MNN headers manually"
    (cd "$MNN_SRC" && find . -name '*.hpp' -path '*/llm/*' -print0 \
        | while IFS= read -r -d '' f; do install -Dm644 "$f" "$MNN_INSTALL/include/$f"; done)
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
# 4. Copy standard .so into jniLibs (QNN libs are NOT copied — Task 11)
# ---------------------------------------------------------------------------
log "copying standard .so into $JNI_LIBS_DIR"
mkdir -p "$JNI_LIBS_DIR"
cp -f "$MNN_INSTALL/lib/libMNN.so"      "$JNI_LIBS_DIR/libMNN.so"
cp -f "$JNI_BUILD/libmnn_jni.so"        "$JNI_LIBS_DIR/libmnn_jni.so"
cp -f "$JNI_BUILD/libcpu_sys_jni.so"    "$JNI_LIBS_DIR/libcpu_sys_jni.so"

# Matching 26.1.10909125 (r26b) libc++_shared.so (same toolchain as libMNN/JNI — ABI consistency).
LIBCPP_SRC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
[[ -f "$LIBCPP_SRC" ]] || LIBCPP_SRC="$ANDROID_NDK_HOME/sources/cxx-stl/llvm-libc++/libs/$ANDROID_ABI/libc++_shared.so"
[[ -f "$LIBCPP_SRC" ]] || die "libc++_shared.so not found in NDK"
cp -f "$LIBCPP_SRC" "$JNI_LIBS_DIR/libc++_shared.so"

# Remove any stale QNN libraries from the standard bundle (Task 11).
rm -f "$JNI_LIBS_DIR"/libQnn*.so

# ---------------------------------------------------------------------------
# 5. Generate manifest from the actual built binaries, then verify
# ---------------------------------------------------------------------------
log "generating $MANIFEST"
python3 "$VERIFIER" --generate \
    --dir "$JNI_LIBS_DIR" --manifest "$MANIFEST" \
    --mnn-commit "$MNN_COMMIT" --ndk-version "$NDK_VERSION" \
    --android-api "$ANDROID_API" --abi "$ANDROID_ABI"

log "verifying native bundle"
python3 "$VERIFIER" --dir "$JNI_LIBS_DIR" --manifest "$MANIFEST"

log "done. standard .so in $JNI_LIBS_DIR"
ls -la "$JNI_LIBS_DIR"
