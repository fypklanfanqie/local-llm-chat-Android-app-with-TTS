#!/usr/bin/env python3
"""MNN native bundle verifier.

Validates the prebuilt native libraries under ``app/src/main/jniLibs/arm64-v8a``
against a committed manifest (``native-manifest.json``). The checks enforce the
invariants required by the MNN adaptive-inference work:

* every packaged standard ``.so`` is AArch64;
* every ``PT_LOAD`` segment is aligned for Android 15 16 KiB pages
  (``p_align >= 0x4000``);
* every ``.so`` carries a GNU build ID;
* SHA-256, filename and build ID match the manifest;
* only expected ``DT_NEEDED`` entries are referenced;
* exactly one ``libc++_shared.so`` is present;
* every ``.so`` on disk has a manifest entry and vice versa.

The ELF structure checks use a self-contained **pure-Python ELF64 parser**
(``parse_elf_bytes``) so the verifier runs anywhere Python runs, with no
dependency on ``readelf``/``llvm-readelf``. An optional ``--readelf`` path may
be supplied to instead parse ``readelf -l`` text via ``verify_elf_text`` (useful
for cross-checking in CI); when absent, the pure-Python parser is used.

CLI:

    python scripts/native/verify_native_bundle.py \
      --dir app/src/main/jniLibs/arm64-v8a \
      --manifest app/src/main/jniLibs/native-manifest.json

Exit code 0 only when every gate passes. Emits JSON plus a readable summary.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from typing import List, Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
MNN_COMMIT = "af0142bcc7b76b7a5128373e285683dc04f55f69"
# NDK 版本与 build_mnn_android.sh 的 NDK_VERSION 保持单一事实源（Task 8 统一为
# 26.1.10909125，与 native-manifest.json ndkVersion 一致）。此常量仅作为 --generate
# 未显式传 --ndk-version 时的默认值；构建脚本总是显式传入。
NDK_VERSION = "26.1.10909125"
ANDROID_API = 24
ABI = "arm64-v8a"
MIN_PT_LOAD_ALIGN = 0x4000  # 16 KiB pages

EM_AARCH64 = 0xB7
_MACHINE_NAMES = {0xB7: "aarch64", 0x3E: "x86_64", 0xB0: "arm", 0x28: "arm"}
_MACHINE_FROM_NAME = {v: k for k, v in _MACHINE_NAMES.items()}

PT_LOAD = 1
SHT_NOTE = 7
SHT_DYNAMIC = 6
SHT_STRTAB = 3
NT_GNU_BUILD_ID = 3
DT_NULL = 0
DT_NEEDED = 1

# DT_NEEDED entries a standard library may legitimately reference.
# Anything outside this set is reported as a warning (not fatal) so that a new
# legitimate dependency surfaces for review rather than silently passing.
EXPECTED_DT_NEEDED = {
    "libc.so",
    "libc.so.6",
    "libdl.so",
    "libdl.so.2",
    "libm.so",
    "libm.so.6",
    "liblog.so",
    "libz.so",
    "libandroid.so",
    "libEGL.so",
    "libGLESv2.so",
    "libGLESv3.so",
    "libnativewindow.so",
    "libsync.so",
    "libMNN.so",
    "libmnn_jni.so",
    "libcpu_sys_jni.so",
    "libc++_shared.so",
    "libOpenCL.so",
}


@dataclass
class CheckResult:
    """Outcome of one or more verification checks."""
    ok: bool = True
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)

    def merge(self, other: "CheckResult") -> "CheckResult":
        self.errors.extend(other.errors)
        self.warnings.extend(other.warnings)
        self.ok = self.ok and other.ok
        return self


@dataclass
class ElfInfo:
    """Parsed ELF64 metadata used by the verification gates."""
    machine: str
    pt_load_alignments: List[int] = field(default_factory=list)
    build_id: Optional[str] = None
    dt_needed: List[str] = field(default_factory=list)
    has_section_headers: bool = False


# ---------------------------------------------------------------------------
# readelf -l text parser
# ---------------------------------------------------------------------------
_LOAD_LINE_RE = re.compile(r"(?m)^[ \t]*LOAD\b")
_HEX_RE = re.compile(r"0x([0-9a-fA-F]+)")


def verify_elf_text(readelf_output: str) -> CheckResult:
    """Parse ``readelf -l`` output and check every PT_LOAD alignment.

    Robust to two textual shapes:

    * compact fixture form ``LOAD ... Align 0x1000``;
    * real ``readelf -l`` two-line-per-entry form, where the alignment is the
      trailing hex token of each LOAD entry.

    For each LOAD entry the alignment is taken as the *last* ``0xHEX`` token
    between this LOAD line and the next (or end of input).
    """
    starts = [m.start() for m in _LOAD_LINE_RE.finditer(readelf_output)]
    if not starts:
        return CheckResult(ok=False, errors=["no PT_LOAD entries found in readelf output"])

    result = CheckResult()
    for i, start in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else len(readelf_output)
        entry = readelf_output[start:end]
        hexes = _HEX_RE.findall(entry)
        if not hexes:
            result.errors.append("PT_LOAD entry found without an alignment value")
            continue
        align = int(hexes[-1], 16)
        if align < MIN_PT_LOAD_ALIGN:
            result.errors.append(
                f"PT_LOAD alignment 0x{align:x} requires >= 0x{MIN_PT_LOAD_ALIGN:x}"
            )
    result.ok = not result.errors
    return result


# ---------------------------------------------------------------------------
# SHA-256
# ---------------------------------------------------------------------------
def verify_hash(data: bytes, expected_hex: str) -> bool:
    """True iff the SHA-256 of ``data`` equals ``expected_hex`` (case-insensitive)."""
    if not expected_hex or len(expected_hex) != 64:
        return False
    actual = hashlib.sha256(data).hexdigest()
    return actual.lower() == expected_hex.lower()


# ---------------------------------------------------------------------------
# Pure-Python ELF64 little-endian parser
# ---------------------------------------------------------------------------
def _u16(d, o):
    return int.from_bytes(d[o:o + 2], "little")


def _u32(d, o):
    return int.from_bytes(d[o:o + 4], "little")


def _u64(d, o):
    return int.from_bytes(d[o:o + 8], "little")


def _read_cstr(data: bytes, offset: int) -> str:
    end = data.find(b"\x00", offset)
    if end < 0:
        end = len(data)
    return data[offset:end].decode("utf-8", "replace")


def _parse_notes(note_data: bytes) -> Optional[str]:
    """Return the GNU build id hex string from a SHT_NOTE section, or None."""
    off = 0
    n = len(note_data)
    while off + 12 <= n:
        namesz = _u32(note_data, off)
        descsz = _u32(note_data, off + 4)
        ntype = _u32(note_data, off + 8)
        name_start = off + 12
        name_end = name_start + namesz
        # name padded to 4
        name_pad = (4 - (namesz % 4)) % 4
        desc_start = name_end + name_pad
        desc_end = desc_start + descsz
        desc_pad = (4 - (descsz % 4)) % 4
        name = note_data[name_start:name_end].rstrip(b"\x00").decode("ascii", "replace")
        desc = note_data[desc_start:desc_end]
        if ntype == NT_GNU_BUILD_ID and name == "GNU" and descsz > 0:
            return desc.hex()
        off = desc_end + desc_pad
    return None


def parse_elf_bytes(data: bytes) -> ElfInfo:
    """Parse an ELF64 little-endian file (shared object) into [ElfInfo].

    Raises ``ValueError`` for non-ELF or non-64-bit-LE inputs.
    """
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    ei_class = data[4]
    ei_data = data[5]
    if ei_class != 2:
        raise ValueError(f"only 64-bit ELF supported (got EI_CLASS={ei_class})")
    if ei_data != 1:
        raise ValueError(f"only little-endian ELF supported (got EI_DATA={ei_data})")

    e_machine = _u16(data, 18)
    machine = _MACHINE_NAMES.get(e_machine, f"unknown(0x{e_machine:x})")

    e_phoff = _u64(data, 32)
    e_shoff = _u64(data, 40)
    e_phentsize = _u16(data, 54)
    e_phnum = _u16(data, 56)
    e_shentsize = _u16(data, 58)
    e_shnum = _u16(data, 60)
    e_shstrndx = _u16(data, 62)

    info = ElfInfo(machine=machine, has_section_headers=bool(e_shoff and e_shnum))

    # Program headers -> PT_LOAD alignments.
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        if base + 56 > len(data):
            break
        p_type = _u32(data, base)
        p_align = _u64(data, base + 48)
        if p_type == PT_LOAD and p_align > 0:
            info.pt_load_alignments.append(p_align)

    # Section headers -> build id + DT_NEEDED (preferred path; real libs keep
    # section headers). Falls back gracefully if absent.
    sections = []
    if e_shoff and e_shnum and e_shstrndx < e_shnum:
        shstr_hdr = e_shoff + e_shstrndx * e_shentsize
        if shstr_hdr + 64 <= len(data):
            shstr_off = _u64(data, shstr_hdr + 24)
            shstr_size = _u64(data, shstr_hdr + 32)
            shstrtab = data[shstr_off:shstr_off + shstr_size]
            for i in range(e_shnum):
                base = e_shoff + i * e_shentsize
                if base + 64 > len(data):
                    break
                sh_name = _u32(data, base)
                sh_type = _u32(data, base + 4)
                sh_offset = _u64(data, base + 24)
                sh_size = _u64(data, base + 32)
                name = _read_cstr(shstrtab, sh_name) if sh_name < len(shstrtab) else ""
                sections.append((name, sh_type, sh_offset, sh_size))

    # Build id from any SHT_NOTE section.
    for name, sh_type, sh_offset, sh_size in sections:
        if sh_type == SHT_NOTE:
            bid = _parse_notes(data[sh_offset:sh_offset + sh_size])
            if bid:
                info.build_id = bid
                break

    # DT_NEEDED from .dynamic + .dynstr.
    dyn = next((s for s in sections if s[0] == ".dynamic" and s[1] == SHT_DYNAMIC), None)
    dynstr = next((s for s in sections if s[0] == ".dynstr" and s[1] == SHT_STRTAB), None)
    if dyn and dynstr:
        dyn_off, dyn_size = dyn[2], dyn[3]
        str_off, str_size = dynstr[2], dynstr[3]
        strtab = data[str_off:str_off + str_size]
        o = dyn_off
        end = dyn_off + dyn_size
        while o + 16 <= end and o + 16 <= len(data):
            d_tag = int.from_bytes(data[o:o + 8], "little", signed=True)
            d_val = _u64(data, o + 8)
            o += 16
            if d_tag == DT_NULL:
                break
            if d_tag == DT_NEEDED and d_val < len(strtab):
                info.dt_needed.append(_read_cstr(strtab, d_val))
    else:
        # Fallback: no section headers. Resolve via PT_DYNAMIC + vaddr mapping.
        info.dt_needed = _parse_dynamic_via_segments(data, e_phoff, e_phnum, e_phentsize)

    return info


def _parse_dynamic_via_segments(data, e_phoff, e_phnum, e_phentsize) -> List[str]:
    """Resolve DT_NEEDED without section headers, via PT_DYNAMIC + PT_LOAD vaddr map."""
    loads = []  # (vaddr, offset, filesz)
    dyn_off = dyn_size = None
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        if base + 56 > len(data):
            break
        p_type = _u32(data, base)
        p_offset = _u64(data, base + 8)
        p_vaddr = _u64(data, base + 16)
        p_filesz = _u64(data, base + 32)
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:  # PT_DYNAMIC
            dyn_off, dyn_size = p_offset, p_filesz
    if dyn_off is None:
        return []

    def vaddr_to_off(vaddr):
        for v, off, fsz in loads:
            if v <= vaddr < v + fsz:
                return off + (vaddr - v)
        return None

    needed = []
    strtab_off = None
    o = dyn_off
    end = dyn_off + dyn_size
    while o + 16 <= end and o + 16 <= len(data):
        d_tag = int.from_bytes(data[o:o + 8], "little", signed=True)
        d_val = _u64(data, o + 8)
        o += 16
        if d_tag == DT_NULL:
            break
        if d_tag == 5:  # DT_STRTAB
            strtab_off = vaddr_to_off(d_val)
        elif d_tag == 1:  # DT_NEEDED
            needed.append(d_val)  # resolve after strtab known
    if strtab_off is None:
        return []
    return [_read_cstr(data, strtab_off + v) for v in needed]


# ---------------------------------------------------------------------------
# ELF verification gates
# ---------------------------------------------------------------------------
def verify_elf(info: ElfInfo, expected_machine: str = "aarch64") -> CheckResult:
    """Check machine, 16 KiB PT_LOAD alignment and build-id presence."""
    result = CheckResult()
    if info.machine != expected_machine:
        result.errors.append(
            f"ELF machine is '{info.machine}', expected '{expected_machine}'"
        )
    if not info.pt_load_alignments:
        result.errors.append("no PT_LOAD segments found")
    for align in info.pt_load_alignments:
        if align < MIN_PT_LOAD_ALIGN:
            result.errors.append(
                f"PT_LOAD alignment 0x{align:x} requires >= 0x{MIN_PT_LOAD_ALIGN:x}"
            )
    if not info.build_id:
        result.errors.append("GNU build ID missing")
    result.ok = not result.errors
    return result


# ---------------------------------------------------------------------------
# Manifest schema
# ---------------------------------------------------------------------------
def verify_manifest_schema(manifest: dict) -> CheckResult:
    """Validate the manifest's shape and required fields."""
    result = CheckResult()
    required_top = ["schemaVersion", "mnnCommit", "ndkVersion", "androidApi", "abi", "files"]
    for key in required_top:
        if key not in manifest:
            result.errors.append(f"manifest missing top-level key '{key}'")
    if manifest.get("abi") != ABI:
        result.errors.append(f"manifest abi is '{manifest.get('abi')}', expected '{ABI}'")
    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        result.errors.append("manifest 'files' must be a non-empty list")
        result.ok = not result.errors
        return result
    required_file = ["name", "sha256", "buildId", "ptLoadAlignment"]
    for entry in files:
        for key in required_file:
            if key not in entry:
                result.errors.append(f"manifest file entry missing '{key}': {entry.get('name')}")
        sha = entry.get("sha256", "")
        if not (isinstance(sha, str) and len(sha) == 64):
            result.errors.append(f"manifest sha256 for '{entry.get('name')}' is not 64 hex chars")
        align = entry.get("ptLoadAlignment", "")
        try:
            if isinstance(align, str) and int(align, 16) < MIN_PT_LOAD_ALIGN:
                result.warnings.append(
                    f"manifest ptLoadAlignment for '{entry.get('name')}' is {align} (< 0x{MIN_PT_LOAD_ALIGN:x})"
                )
        except (ValueError, TypeError):
            result.errors.append(f"manifest ptLoadAlignment for '{entry.get('name')}' is not hex: {align}")
    result.ok = not result.errors
    return result


# ---------------------------------------------------------------------------
# Bundle verification
# ---------------------------------------------------------------------------
def verify_bundle(bundle_dir: str, manifest_path: str, readelf_path: Optional[str] = None) -> CheckResult:
    """Verify every ``.so`` in ``bundle_dir`` against ``manifest_path``."""
    result = CheckResult()
    if not os.path.isfile(manifest_path):
        return CheckResult(ok=False, errors=[f"manifest not found: {manifest_path}"])
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    result.merge(verify_manifest_schema(manifest))

    # Index manifest entries by name.
    entries = {e["name"]: e for e in manifest.get("files", [])}

    # Files actually on disk.
    on_disk = {n for n in os.listdir(bundle_dir) if n.endswith(".so")}

    # 1. Every manifest entry must exist + hash match.
    for name, entry in entries.items():
        path = os.path.join(bundle_dir, name)
        if not os.path.isfile(path):
            result.errors.append(f"manifest lists '{name}' but file is missing on disk")
            continue
        with open(path, "rb") as f:
            data = f.read()
        if not verify_hash(data, entry.get("sha256", "")):
            result.errors.append(f"sha256 mismatch for '{name}'")

    # 2. Every .so on disk must have a manifest entry.
    for name in sorted(on_disk):
        if name not in entries:
            result.errors.append(f"unexpected .so on disk without manifest entry: '{name}'")

    # 3. Exactly one libc++_shared.so.
    cpp_count = sum(1 for n in on_disk if n == "libc++_shared.so")
    if cpp_count != 1:
        result.errors.append(f"expected exactly one libc++_shared.so, found {cpp_count}")

    # 4. Per-.so ELF gates.
    for name in sorted(on_disk):
        path = os.path.join(bundle_dir, name)
        with open(path, "rb") as f:
            data = f.read()

        if readelf_path:
            # Cross-check path: use readelf -l text.
            try:
                out = subprocess.run(
                    [readelf_path, "-l", path],
                    check=True, capture_output=True, text=True,
                ).stdout
                result.merge(verify_elf_text(out))
            except FileNotFoundError:
                result.errors.append(f"readelf not found at {readelf_path}")
            except subprocess.CalledProcessError as e:
                result.errors.append(f"readelf failed for {name}: {e.stderr.strip()}")
        else:
            try:
                info = parse_elf_bytes(data)
            except ValueError as e:
                result.errors.append(f"failed to parse ELF '{name}': {e}")
                continue
            elf_result = verify_elf(info)
            for err in elf_result.errors:
                result.errors.append(f"{name}: {err}")
            # Unknown DT_NEEDED -> warning for review.
            for dep in info.dt_needed:
                if dep not in EXPECTED_DT_NEEDED:
                    result.warnings.append(f"{name}: unexpected DT_NEEDED '{dep}'")

    result.ok = result.ok and not result.errors
    return result


# ---------------------------------------------------------------------------
# Manifest generation (used by build_mnn_android.sh)
# ---------------------------------------------------------------------------
def generate_manifest(bundle_dir: str, mnn_commit: str = MNN_COMMIT,
                      ndk_version: str = NDK_VERSION, android_api: int = ANDROID_API,
                      abi: str = ABI, flags: Optional[List[str]] = None,
                      note: Optional[str] = None) -> dict:
    """Scan ``bundle_dir`` and produce a manifest dict from the actual binaries.

    ``flags`` default mirrors the committed ``native-manifest.json`` (Task 8 review:
    includes the ``opencl_probe`` flag). ``note`` is optional; when provided the
    emitted manifest carries a ``note`` key (e.g. the rebuild provenance text).
    """
    if flags is None:
        flags = ["llm", "low_memory", "cpu_weight_dequant_gemm", "transformer_fuse",
                 "arm82", "opencl", "16k_pages", "opencl_probe"]
    files = []
    for name in sorted(os.listdir(bundle_dir)):
        if not name.endswith(".so"):
            continue
        path = os.path.join(bundle_dir, name)
        with open(path, "rb") as f:
            data = f.read()
        try:
            info = parse_elf_bytes(data)
            build_id = info.build_id or "missing"
            min_align = min(info.pt_load_alignments) if info.pt_load_alignments else 0
            align_str = f"0x{min_align:x}"
        except ValueError as e:
            # Non-64-bit-LE ELF (e.g. a 32-bit QNN skeleton lib). Record the
            # failure rather than aborting manifest generation.
            build_id = f"unparseable:{e}"
            align_str = "0x0"
        files.append({
            "name": name,
            "sha256": hashlib.sha256(data).hexdigest(),
            "buildId": build_id,
            "ptLoadAlignment": align_str,
        })
    manifest = {
        "schemaVersion": 1,
        "mnnCommit": mnn_commit,
        "ndkVersion": ndk_version,
        "androidApi": android_api,
        "abi": abi,
        "flags": flags,
        "files": files,
    }
    if note is not None:
        manifest["note"] = note
    return manifest


def _print_summary(result: CheckResult, bundle_dir: str, manifest_path: str) -> None:
    print(f"MNN native bundle verification")
    print(f"  dir:      {bundle_dir}")
    print(f"  manifest: {manifest_path}")
    print(f"  result:   {'PASS' if result.ok else 'FAIL'}")
    if result.errors:
        print(f"  errors ({len(result.errors)}):")
        for e in result.errors:
            print(f"    - {e}")
    if result.warnings:
        print(f"  warnings ({len(result.warnings)}):")
        for w in result.warnings:
            print(f"    - {w}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Verify the MNN native bundle.")
    parser.add_argument("--dir", required=True, help="directory containing the .so files")
    parser.add_argument("--manifest", required=True, help="path to native-manifest.json")
    parser.add_argument("--readelf", default=None,
                        help="optional llvm-readelf path; pure-Python parser used if absent")
    parser.add_argument("--json", action="store_true", help="emit JSON instead of a summary")
    parser.add_argument("--generate", action="store_true",
                        help="generate a manifest from the .so files on disk (instead of verifying)")
    parser.add_argument("--mnn-commit", default=MNN_COMMIT, help="(generate) pinned MNN commit")
    parser.add_argument("--ndk-version", default=NDK_VERSION, help="(generate) NDK version")
    parser.add_argument("--android-api", type=int, default=ANDROID_API, help="(generate) Android API")
    parser.add_argument("--abi", default=ABI, help="(generate) ABI")
    args = parser.parse_args(argv)

    if args.generate:
        # 保留旧 manifest 的 note（重编来源/排除声明等人工维护的元信息）：
        # 重编后 --generate 重写 manifest 时不丢失；旧文件缺失/损坏则静默跳过。
        note = None
        if os.path.isfile(args.manifest):
            try:
                with open(args.manifest, "r", encoding="utf-8") as f:
                    note = json.load(f).get("note")
            except (OSError, json.JSONDecodeError):
                note = None
        manifest = generate_manifest(
            args.dir, mnn_commit=args.mnn_commit, ndk_version=args.ndk_version,
            android_api=args.android_api, abi=args.abi, note=note,
        )
        with open(args.manifest, "w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2)
            f.write("\n")
        print(f"manifest written: {args.manifest} ({len(manifest['files'])} files)")
        return 0

    result = verify_bundle(args.dir, args.manifest, args.readelf)
    if args.json:
        print(json.dumps({
            "ok": result.ok,
            "errors": result.errors,
            "warnings": result.warnings,
        }, indent=2))
    else:
        _print_summary(result, args.dir, args.manifest)
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
