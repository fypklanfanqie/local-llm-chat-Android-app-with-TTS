#!/usr/bin/env python3
"""Static APK gate for the production MNN JNI contract.

The gate intentionally checks APK structure rather than comparing an APK's
stripped library bytes to a source-tree manifest. It verifies the arm64
libmnn_jni.so dynamic exports and confirms the final DEX set references the
UTF-8 JNI method. QNN libraries are rejected because standard packaging must
exclude them.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import zipfile

# Permit execution both as a module and directly from the repository root.
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)
import verify_native_bundle as vnb  # noqa: E402

UTF8_METHOD = b"nativeGenerateStreamUtf8"


def _dex_contains(zf: zipfile.ZipFile, names: list[str], needle: bytes) -> bool:
    return any(needle in zf.read(name) for name in names)


def verify_apk(apk_path: str) -> vnb.CheckResult:
    result = vnb.CheckResult()
    if not os.path.isfile(apk_path):
        return vnb.CheckResult(ok=False, errors=[f"APK not found: {apk_path}"])

    try:
        with zipfile.ZipFile(apk_path, "r") as zf:
            names = zf.namelist()
            qnn = sorted(
                name for name in names
                if name.startswith("lib/") and "/libQnn" in name and name.endswith(".so")
            )
            if qnn:
                result.errors.append("standard APK contains QNN libraries: " + ", ".join(qnn))

            jni_name = "lib/arm64-v8a/libmnn_jni.so"
            if jni_name not in names:
                result.errors.append(f"APK missing {jni_name}")
            else:
                try:
                    info = vnb.parse_elf_bytes(zf.read(jni_name))
                    export_result = vnb.verify_mnn_jni_exports(info)
                    result.merge(export_result)
                    if info.machine != "aarch64":
                        result.errors.append(
                            f"{jni_name}: ELF machine is '{info.machine}', expected 'aarch64'"
                        )
                except ValueError as exc:
                    result.errors.append(f"failed to parse APK {jni_name}: {exc}")

            # Classes.dex is compactly encoded, so this is a conservative
            # static reference check rather than a full DEX parser.
            dex_names = sorted(
                name for name in names
                if name.startswith("classes") and name.endswith(".dex")
            )
            if not dex_names:
                result.errors.append("APK contains no classes*.dex")
            elif not _dex_contains(zf, dex_names, UTF8_METHOD):
                result.errors.append(
                    "classes*.dex does not contain nativeGenerateStreamUtf8"
                )
    except (OSError, zipfile.BadZipFile) as exc:
        return vnb.CheckResult(ok=False, errors=[f"failed to read APK: {exc}"])

    result.ok = not result.errors
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify final APK MNN JNI contract")
    parser.add_argument("apk", help="final APK path")
    parser.add_argument("--json", action="store_true", help="emit JSON")
    args = parser.parse_args(argv)
    result = verify_apk(args.apk)
    if args.json:
        print(json.dumps({"ok": result.ok, "errors": result.errors}, indent=2))
    else:
        print(f"APK native audit: {'PASS' if result.ok else 'FAIL'}")
        for error in result.errors:
            print(f"  - {error}")
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
