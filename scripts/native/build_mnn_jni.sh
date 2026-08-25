#!/bin/bash
# =============================================================================
# libmnn_jni.so / libcpu_sys_jni.so / libbackend_probe.so 离线重编 + 部署
# （Wave 2：sampler_hot_update 能力串 + BUILD_ID=pinned-af0142b-samplerhot-20260824）
#
# 该 helper 仅保留作 Windows 本地候选构建；发布/生产身份统一由
# build_mnn_android.sh（NDK 27.2.12479018、manifest 自动生成、四个标准 .so）管理。
# =============================================================================
set -euo pipefail

PROJECT="/d/ai/cc Programm/本地ai聊天大众版"
MNN_DIR="D:/mnn-matched"
# NDK 27.2.12479018 (r27c) is the canonical release toolchain; the helper
# accepts ANDROID_NDK_HOME so it cannot silently use a different installation.
NDK="${ANDROID_NDK_HOME:-D:/android-ndk-r27c}"
CMAKE_BIN="C:/Users/Lfq06/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
NINJA_BIN="C:/Users/Lfq06/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe"
BUILD_DIR="D:/ai-build/mnn-jni-public"   # ASCII 路径（中文路径下 CMake 崩溃，见项目记忆）
JNI_LIBS="$PROJECT/app/src/main/jniLibs/arm64-v8a"

echo "== [1/5] 同步新 libMNN.so 到 $MNN_DIR/lib =="
cp /d/ai-build/mnn-build-wave2/libMNN.so "$MNN_DIR/lib/libMNN.so"
ls -la "$MNN_DIR/lib/"

echo "== [2/5] CMake configure =="
"$CMAKE_BIN" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
  -DCMAKE_SYSTEM_NAME=Android \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_NDK="$NDK" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_STL=c++_shared \
  -DMNN_DIR="$MNN_DIR" \
  -DCHAT_MNN_COMMIT=af0142bcc7b76b7a5128373e285683dc04f55f69 \
  -DCHAT_MNN_JNI_ABI=1 \
  -DCHAT_MNN_BUILD_ID=pinned-af0142b-samplerhot-20260824 \
  -S "$PROJECT/app/src/main/cpp" -B "$BUILD_DIR"

echo "== [3/5] Build =="
"$CMAKE_BIN" --build "$BUILD_DIR" -j 8

echo "== [4/5] 部署到 jniLibs =="
for so in libmnn_jni.so libcpu_sys_jni.so libbackend_probe.so; do
  if [ -f "$BUILD_DIR/$so" ]; then
    cp "$BUILD_DIR/$so" "$JNI_LIBS/$so"
    ls -la "$JNI_LIBS/$so"
  else
    echo "WARN: 未找到 $so（跳过）"
  fi
done

echo "== [5/5] 字符串验证 =="
python - << 'PYEOF'
import sys
data = open(r'D:/ai/cc Programm/本地ai聊天大众版/app/src/main/jniLibs/arm64-v8a/libmnn_jni.so','rb').read()
checks = {
    'sampler_hot_update': True,
    'pinned-af0142b-samplerhot-20260824': True,
    'summary_v2': True,
}
ok = True
for probe, expected in checks.items():
    found = data.count(probe.encode()) > 0
    status = 'OK' if found == expected else 'FAIL'
    if found != expected:
        ok = False
    print(f'  {probe}: {"存在" if found else "缺失"} [{status}]')
sys.exit(0 if ok else 1)
PYEOF
echo "== 全部完成 =="
