# jniLibs 目录

标准发布包使用 `arm64-v8a` 下的 verified-prebuilt MNN 库：

- `libMNN.so`
- `libmnn_jni.so`
- `libcpu_sys_jni.so`
- `libbackend_probe.so`
- `libc++_shared.so`

`libQnn*.so` 仅作为未来实验的源文件保留，不得进入标准 APK。发布前必须运行：

```bash
python3 scripts/native/verify_native_bundle.py \
  --dir app/src/main/jniLibs/arm64-v8a \
  --manifest app/src/main/jniLibs/native-manifest.json
```

最终 APK 还必须通过：

```bash
python3 scripts/native/verify_apk_native.py path/to/app-debug.apk
```

构建身份、SHA-256、GNU build ID 与 16 KiB `PT_LOAD` 对齐均由脚本从实际二进制生成/验证；不得手工伪造 manifest 值。
