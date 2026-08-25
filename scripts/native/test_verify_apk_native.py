import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_apk_native as apk_gate  # noqa: E402
import verify_native_bundle as vnb  # noqa: E402
from test_verify_native_bundle import (  # noqa: E402
    REQUIRED_MNN_JNI_EXPORTS,
    build_minimal_elf,
)


class TestVerifyApkNative(unittest.TestCase):
    def _apk(self, jni, dex=b"nativeGenerateStreamUtf8"):
        fd, path = tempfile.mkstemp(suffix=".apk")
        os.close(fd)
        with zipfile.ZipFile(path, "w") as zf:
            zf.writestr("lib/arm64-v8a/libmnn_jni.so", jni)
            zf.writestr("classes.dex", dex)
        return path

    def test_accepts_arm64_exports_and_utf8_dex_reference(self):
        apk = self._apk(build_minimal_elf(dynamic_symbols=REQUIRED_MNN_JNI_EXPORTS))
        try:
            result = apk_gate.verify_apk(apk)
            self.assertTrue(result.ok, msg=result.errors)
        finally:
            os.unlink(apk)

    def test_rejects_missing_utf8_dex_reference(self):
        apk = self._apk(
            build_minimal_elf(dynamic_symbols=REQUIRED_MNN_JNI_EXPORTS),
            dex=b"nativeGenerateStream",
        )
        try:
            result = apk_gate.verify_apk(apk)
            self.assertFalse(result.ok)
            self.assertTrue(any("classes*.dex" in error for error in result.errors))
        finally:
            os.unlink(apk)

    def test_rejects_missing_utf8_dynamic_export_even_when_dex_has_name(self):
        apk = self._apk(
            build_minimal_elf(dynamic_symbols=REQUIRED_MNN_JNI_EXPORTS[:-1]),
        )
        try:
            result = apk_gate.verify_apk(apk)
            self.assertFalse(result.ok)
            self.assertTrue(any("missing dynamic export" in error for error in result.errors))
        finally:
            os.unlink(apk)

    def test_rejects_qnn_library_in_standard_apk(self):
        fd, path = tempfile.mkstemp(suffix=".apk")
        os.close(fd)
        with zipfile.ZipFile(path, "w") as zf:
            zf.writestr("lib/arm64-v8a/libmnn_jni.so",
                        build_minimal_elf(dynamic_symbols=REQUIRED_MNN_JNI_EXPORTS))
            zf.writestr("lib/arm64-v8a/libQnnSystem.so", b"qnn")
            zf.writestr("classes.dex", b"nativeGenerateStreamUtf8")
        try:
            result = apk_gate.verify_apk(path)
            self.assertFalse(result.ok)
            self.assertTrue(any("QNN" in error for error in result.errors))
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
