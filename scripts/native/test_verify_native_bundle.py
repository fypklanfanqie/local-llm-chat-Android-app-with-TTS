#!/usr/bin/env python3
"""Tests for the MNN native bundle verifier.

Run from the repository root:

    python -m unittest scripts/native/test_verify_native_bundle.py -v

These tests are deterministic: pure-logic checks use crafted inputs (readelf
text, raw bytes, minimal in-memory ELF binaries), so they never depend on the
specific build IDs, hashes, or PT_LOAD alignments of the committed prebuilt
libraries (which are expected to change after the pinned-MNN rebuild). A small
set of smoke tests reads the real committed ``.so`` files in
``app/src/main/jniLibs/arm64-v8a`` and asserts only stable properties (machine
type, presence of a build id, DT_NEEDED contents); these are skipped when the
files are absent.
"""
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_native_bundle as vnb  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JNILIB_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "jniLibs", "arm64-v8a")


# ---------------------------------------------------------------------------
# Minimal ELF64 little-endian builder (test fixture factory).
# ---------------------------------------------------------------------------
EM_AARCH64 = 0xB7
EM_X86_64 = 0x3E
ET_DYN = 3

PT_LOAD = 1
PT_NOTE = 4
PT_DYNAMIC = 2

SHT_PROGBITS = 1
SHT_STRTAB = 3
SHT_DYNAMIC = 6
SHT_NOTE = 7

DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10

NT_GNU_BUILD_ID = 3


def _pack_ehdr(e_phoff, e_shoff, e_phnum, e_shnum, e_shstrndx, machine=EM_AARCH64):
    e_ident = b"\x7fELF" + bytes([2, 1, 1, 0]) + b"\x00" * 8  # 64-bit, LE, v1
    return e_ident + struct.pack(
        "<HHIQQQIHHHHHH",
        ET_DYN,          # e_type
        machine,         # e_machine
        1,               # e_version
        0,               # e_entry
        e_phoff,         # e_phoff
        e_shoff,         # e_shoff
        0,               # e_flags
        64,              # e_ehsize
        56,              # e_phentsize
        e_phnum,         # e_phnum
        64,              # e_shentsize
        e_shnum,         # e_shnum
        e_shstrndx,      # e_shstrndx
    )


def _pack_phdr(p_type, p_flags, p_offset, p_filesz, p_align, p_vaddr=0):
    return struct.pack(
        "<IIQQQQQQ",
        p_type, p_flags, p_offset, p_vaddr, p_vaddr, p_filesz, p_filesz, p_align,
    )


def _pack_shdr(sh_name, sh_type, sh_offset, sh_size, sh_link=0, sh_info=0,
               sh_addralign=1, sh_entsize=0, sh_flags=0, sh_addr=0):
    return struct.pack(
        "<IIQQQQIIQQ",
        sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size,
        sh_link, sh_info, sh_addralign, sh_entsize,
    )


def _build_note(build_id_hex):
    """A .note.gnu.build-id section body for the given hex build id."""
    desc = bytes.fromhex(build_id_hex)
    name = b"GNU\x00"
    namesz = len(name)
    descsz = len(desc)
    body = struct.pack("<III", namesz, descsz, NT_GNU_BUILD_ID)
    body += name
    # name padded to 4
    if namesz % 4:
        body += b"\x00" * (4 - namesz % 4)
    body += desc
    if descsz % 4:
        body += b"\x00" * (4 - descsz % 4)
    return body


def build_minimal_elf(pt_load_align=0x4000, build_id_hex="abcdef0123456789",
                      needed=("libc++_shared.so",), machine=EM_AARCH64):
    """Build a minimal but valid ELF64 LE shared object.

    Contains: one PT_LOAD, one PT_NOTE (build-id), one PT_DYNAMIC, and section
    headers for .note.gnu.build-id / .dynstr / .dynamic / .shstrtab. Used to
    exercise the pure-Python parser deterministically.
    """
    # 1. Section bodies.
    note_body = _build_note(build_id_hex)

    # .dynstr: leading empty string, then each needed name (NUL-terminated).
    dynstr = b"\x00"
    needed_offsets = []
    for n in needed:
        needed_offsets.append(len(dynstr))
        dynstr += n.encode() + b"\x00"

    # .dynamic: DT_NEEDED per entry + DT_STRTAB + DT_STRSZ + DT_NULL.
    # STRTAB vaddr is filled after we know the file offset; we patch below.
    dyn_entries = []
    for off in needed_offsets:
        dyn_entries.append((DT_NEEDED, off))
    dyn_entries.append((DT_STRTAB, 0))   # placeholder, patched
    dyn_entries.append((DT_STRSZ, len(dynstr)))
    dyn_entries.append((DT_NULL, 0))
    dynamic_body = b"".join(struct.pack("<qQ", t, v) for t, v in dyn_entries)

    # .shstrtab
    shstrtab = b"\x00"
    names = [".note.gnu.build-id", ".dynstr", ".dynamic", ".shstrtab"]
    name_offsets = {}
    for nm in names:
        name_offsets[nm] = len(shstrtab)
        shstrtab += nm.encode() + b"\x00"

    # 2. Layout: ehdr(64) | phdrs(3*56=168) | note | dynstr | dynamic | shstrtab | shdrs(5*64)
    phoff = 64
    phnum = 3
    phdrs_size = phnum * 56
    note_off = phoff + phdrs_size
    dynstr_off = note_off + len(note_body)
    dynamic_off = dynstr_off + len(dynstr)
    shstrtab_off = dynamic_off + len(dynamic_body)
    shoff = shstrtab_off + len(shstrtab)

    # Patch DT_STRTAB vaddr -> we use file offset as vaddr for the test fixture.
    strtab_vaddr = dynstr_off
    dynamic_body = b"".join(
        struct.pack("<qQ", DT_STRTAB, strtab_vaddr) if t == DT_STRTAB else struct.pack("<qQ", t, v)
        for t, v in dyn_entries
    )

    # 3. Program headers.
    load_end = shoff  # cover everything with one PT_LOAD
    phdrs = b""
    phdrs += _pack_phdr(PT_LOAD, 5, 0, load_end, pt_load_align)
    phdrs += _pack_phdr(PT_NOTE, 4, note_off, len(note_body), 4)
    phdrs += _pack_phdr(PT_DYNAMIC, 6, dynamic_off, len(dynamic_body), 8)

    # 4. Section headers (index 0 = SHN_UNDEF).
    shdrs = _pack_shdr(0, 0, 0, 0)  # SHN_UNDEF
    shdrs += _pack_shdr(name_offsets[".note.gnu.build-id"], SHT_NOTE, note_off, len(note_body), sh_addralign=4)
    shdrs += _pack_shdr(name_offsets[".dynstr"], SHT_STRTAB, dynstr_off, len(dynstr), sh_addralign=1)
    shdrs += _pack_shdr(name_offsets[".dynamic"], SHT_DYNAMIC, dynamic_off, len(dynamic_body), sh_link=name_offsets[".dynstr"], sh_entsize=16, sh_addralign=8)
    shdrs += _pack_shdr(name_offsets[".shstrtab"], SHT_STRTAB, shstrtab_off, len(shstrtab), sh_addralign=1)

    ehdr = _pack_ehdr(phoff, shoff, phnum, 5, 4, machine=machine)

    return ehdr + phdrs + note_body + dynstr + dynamic_body + shstrtab + shdrs


# ---------------------------------------------------------------------------
# verify_elf_text (readelf -l output parser)
# ---------------------------------------------------------------------------
class TestVerifyElfText(unittest.TestCase):

    def test_rejects_4k_pt_load_alignment(self):
        result = vnb.verify_elf_text("LOAD ... Align 0x1000")
        self.assertFalse(result.ok)
        joined = " ".join(result.errors)
        self.assertIn("0x4000", joined)

    def test_accepts_16k_pt_load_alignment(self):
        result = vnb.verify_elf_text("LOAD ... Align 0x4000")
        self.assertTrue(result.ok, msg=result.errors)
        self.assertEqual(result.errors, [])

    def test_accepts_alignment_above_threshold(self):
        result = vnb.verify_elf_text("LOAD ... Align 0x8000")
        self.assertTrue(result.ok, msg=result.errors)

    def test_rejects_when_any_load_is_4k(self):
        text = (
            "LOAD ... Align 0x4000\n"
            "LOAD ... Align 0x1000\n"
        )
        result = vnb.verify_elf_text(text)
        self.assertFalse(result.ok)

    def test_rejects_when_no_load_entries_found(self):
        result = vnb.verify_elf_text("Nothing useful here")
        self.assertFalse(result.ok)
        self.assertTrue(any("LOAD" in e for e in result.errors))

    def test_parses_real_readelf_multiline_format(self):
        # Real `readelf -l` spans two lines per entry; the parser must still
        # associate each LOAD with its own Align value.
        text = (
            "Program Headers:\n"
            "  Type           Offset             VirtAddr           PhysAddr\n"
            "                 FileSiz            MemSiz              Flags  Align\n"
            "  LOAD           0x0000000000000000 0x0000000000000000 0x0000000000000000\n"
            "                 0x0000000000000560 0x0000000000000560  R     0x4000\n"
            "  LOAD           0x0000000000000560 0x0000000000000560 0x0000000000000560\n"
            "                 0x0000000000001000 0x0000000000001000  R E   0x4000\n"
        )
        result = vnb.verify_elf_text(text)
        self.assertTrue(result.ok, msg=result.errors)


# ---------------------------------------------------------------------------
# verify_hash
# ---------------------------------------------------------------------------
class TestVerifyHash(unittest.TestCase):

    def test_manifest_hash_mismatch_fails(self):
        self.assertFalse(vnb.verify_hash(b"abc", "00" * 32))

    def test_correct_hash_passes(self):
        import hashlib
        data = b"hello world"
        self.assertTrue(vnb.verify_hash(data, hashlib.sha256(data).hexdigest()))

    def test_lowercase_and_uppercase_both_accepted(self):
        import hashlib
        data = b"x"
        self.assertTrue(vnb.verify_hash(data, hashlib.sha256(data).hexdigest().upper()))


# ---------------------------------------------------------------------------
# Pure-Python ELF parser (parse_elf)
# ---------------------------------------------------------------------------
class TestParseElf(unittest.TestCase):

    def test_machine_aarch64(self):
        info = vnb.parse_elf_bytes(build_minimal_elf())
        self.assertEqual(info.machine, "aarch64")

    def test_machine_non_aarch64_detected(self):
        info = vnb.parse_elf_bytes(build_minimal_elf(machine=EM_X86_64))
        self.assertNotEqual(info.machine, "aarch64")

    def test_pt_load_alignments_extracted(self):
        info = vnb.parse_elf_bytes(build_minimal_elf(pt_load_align=0x4000))
        self.assertEqual(info.pt_load_alignments, [0x4000])

    def test_pt_load_4k_extracted(self):
        info = vnb.parse_elf_bytes(build_minimal_elf(pt_load_align=0x1000))
        self.assertEqual(info.pt_load_alignments, [0x1000])

    def test_build_id_extracted(self):
        info = vnb.parse_elf_bytes(build_minimal_elf(build_id_hex="deadbeefcafe"))
        self.assertEqual(info.build_id, "deadbeefcafe")

    def test_dt_needed_extracted(self):
        info = vnb.parse_elf_bytes(
            build_minimal_elf(needed=("libc++_shared.so", "liblog.so"))
        )
        self.assertEqual(info.dt_needed, ["libc++_shared.so", "liblog.so"])

    def test_verify_elf_accepts_16k(self):
        info = vnb.parse_elf_bytes(build_minimal_elf(pt_load_align=0x4000))
        result = vnb.verify_elf(info)
        self.assertTrue(result.ok, msg=result.errors)

    def test_verify_elf_rejects_4k(self):
        info = vnb.parse_elf_bytes(build_minimal_elf(pt_load_align=0x1000))
        result = vnb.verify_elf(info)
        self.assertFalse(result.ok)
        self.assertTrue(any("0x4000" in e for e in result.errors))

    def test_verify_elf_rejects_wrong_machine(self):
        info = vnb.parse_elf_bytes(build_minimal_elf(machine=EM_X86_64))
        result = vnb.verify_elf(info)
        self.assertFalse(result.ok)
        self.assertTrue(any("aarch64" in e.lower() for e in result.errors))

    def test_verify_elf_requires_build_id(self):
        info = vnb.parse_elf_bytes(build_minimal_elf(build_id_hex=""))
        # Empty build id -> not present.
        result = vnb.verify_elf(info)
        self.assertFalse(result.ok)
        self.assertTrue(any("build" in e.lower() for e in result.errors))


# ---------------------------------------------------------------------------
# Manifest schema validation
# ---------------------------------------------------------------------------
class TestManifestSchema(unittest.TestCase):

    def _valid_manifest(self):
        return {
            "schemaVersion": 1,
            "mnnCommit": "af0142bcc7b76b7a5128373e285683dc04f55f69",
            "ndkVersion": "26.1.10909125",
            "androidApi": 24,
            "abi": "arm64-v8a",
            "flags": ["llm", "low_memory", "arm82", "opencl"],
            "files": [
                {
                    "name": "libMNN.so",
                    "sha256": "a" * 64,
                    "buildId": "deadbeef",
                    "ptLoadAlignment": "0x4000",
                }
            ],
        }

    def test_valid_manifest_passes(self):
        result = vnb.verify_manifest_schema(self._valid_manifest())
        self.assertTrue(result.ok, msg=result.errors)

    def test_missing_schema_version_fails(self):
        m = self._valid_manifest()
        del m["schemaVersion"]
        self.assertFalse(vnb.verify_manifest_schema(m).ok)

    def test_missing_file_field_fails(self):
        m = self._valid_manifest()
        del m["files"][0]["sha256"]
        self.assertFalse(vnb.verify_manifest_schema(m).ok)

    def test_wrong_abi_fails(self):
        m = self._valid_manifest()
        m["abi"] = "armeabi-v7a"
        self.assertFalse(vnb.verify_manifest_schema(m).ok)


# ---------------------------------------------------------------------------
# Full bundle verification against a temp directory
# ---------------------------------------------------------------------------
class TestVerifyBundle(unittest.TestCase):

    def _write_bundle(self, files):
        tmp = tempfile.mkdtemp()
        for name, data in files.items():
            with open(os.path.join(tmp, name), "wb") as f:
                f.write(data)
        return tmp

    def test_passing_bundle(self):
        elf = build_minimal_elf(pt_load_align=0x4000, build_id_hex="cafe1234",
                                needed=("libc++_shared.so",))
        cpp = build_minimal_elf(pt_load_align=0x4000, build_id_hex="babe9876",
                                needed=("libc.so",))
        import hashlib
        files = {"libMNN.so": elf, "libc++_shared.so": cpp}
        tmp = self._write_bundle(files)
        manifest = {
            "schemaVersion": 1,
            "mnnCommit": "af0142b",
            "ndkVersion": "26.1.10909125",
            "androidApi": 24,
            "abi": "arm64-v8a",
            "flags": [],
            "files": [
                {
                    "name": "libMNN.so",
                    "sha256": hashlib.sha256(elf).hexdigest(),
                    "buildId": "cafe1234",
                    "ptLoadAlignment": "0x4000",
                },
                {
                    "name": "libc++_shared.so",
                    "sha256": hashlib.sha256(cpp).hexdigest(),
                    "buildId": "babe9876",
                    "ptLoadAlignment": "0x4000",
                },
            ],
        }
        mpath = os.path.join(tmp, "manifest.json")
        with open(mpath, "w") as f:
            import json
            json.dump(manifest, f)

        result = vnb.verify_bundle(tmp, mpath)
        self.assertTrue(result.ok, msg=result.errors)

    def test_hash_mismatch_fails(self):
        elf = build_minimal_elf(pt_load_align=0x4000)
        tmp = self._write_bundle({"libMNN.so": elf})
        manifest = {
            "schemaVersion": 1, "mnnCommit": "x", "ndkVersion": "x",
            "androidApi": 24, "abi": "arm64-v8a", "flags": [],
            "files": [{"name": "libMNN.so", "sha256": "0" * 64,
                       "buildId": "cafe1234", "ptLoadAlignment": "0x4000"}],
        }
        mpath = os.path.join(tmp, "manifest.json")
        with open(mpath, "w") as f:
            import json
            json.dump(manifest, f)
        result = vnb.verify_bundle(tmp, mpath)
        self.assertFalse(result.ok)
        self.assertTrue(any("sha256" in e.lower() or "hash" in e.lower() for e in result.errors))

    def test_4k_alignment_fails(self):
        elf = build_minimal_elf(pt_load_align=0x1000, build_id_hex="cafe1234")
        import hashlib
        tmp = self._write_bundle({"libMNN.so": elf})
        manifest = {
            "schemaVersion": 1, "mnnCommit": "x", "ndkVersion": "x",
            "androidApi": 24, "abi": "arm64-v8a", "flags": [],
            "files": [{"name": "libMNN.so", "sha256": hashlib.sha256(elf).hexdigest(),
                       "buildId": "cafe1234", "ptLoadAlignment": "0x1000"}],
        }
        mpath = os.path.join(tmp, "manifest.json")
        with open(mpath, "w") as f:
            import json
            json.dump(manifest, f)
        result = vnb.verify_bundle(tmp, mpath)
        self.assertFalse(result.ok)
        self.assertTrue(any("0x4000" in e for e in result.errors))

    def test_unexpected_file_flagged(self):
        elf = build_minimal_elf(pt_load_align=0x4000)
        tmp = self._write_bundle({"libMNN.so": elf, "libstray.so": elf})
        manifest = {
            "schemaVersion": 1, "mnnCommit": "x", "ndkVersion": "x",
            "androidApi": 24, "abi": "arm64-v8a", "flags": [],
            "files": [{"name": "libMNN.so", "sha256": "x", "buildId": "x",
                       "ptLoadAlignment": "0x4000"}],
        }
        mpath = os.path.join(tmp, "manifest.json")
        with open(mpath, "w") as f:
            import json
            json.dump(manifest, f)
        result = vnb.verify_bundle(tmp, mpath)
        self.assertFalse(result.ok)
        self.assertTrue(any("libstray" in e for e in result.errors))


# ---------------------------------------------------------------------------
# Manifest generation (generate_manifest / CLI --generate)
# ---------------------------------------------------------------------------
class TestGenerateManifest(unittest.TestCase):

    def _bundle_with_libmnn(self):
        tmp = tempfile.mkdtemp()
        with open(os.path.join(tmp, "libMNN.so"), "wb") as f:
            f.write(build_minimal_elf(pt_load_align=0x4000))
        return tmp

    def test_default_generation_matches_pinned_versions(self):
        # 固化 Task 8 审查修复：默认生成必须与单一事实源一致，并保留 opencl_probe
        # 旗标；不传 note 时产出物不写 note 键。
        m = vnb.generate_manifest(self._bundle_with_libmnn())
        self.assertEqual(m["ndkVersion"], "26.1.10909125")
        self.assertEqual(m["mnnCommit"], "af0142bcc7b76b7a5128373e285683dc04f55f69")
        self.assertIn("opencl_probe", m["flags"])
        self.assertNotIn("note", m)
        self.assertEqual(len(m["files"]), 1)
        self.assertEqual(m["files"][0]["name"], "libMNN.so")
        self.assertEqual(m["files"][0]["ptLoadAlignment"], "0x4000")

    def test_note_param_is_emitted(self):
        m = vnb.generate_manifest(self._bundle_with_libmnn(),
                                  note="rebuilt with NDK 26.1.10909125")
        self.assertEqual(m["note"], "rebuilt with NDK 26.1.10909125")

    def test_cli_generate_carries_old_manifest_note(self):
        # --generate 重写 manifest 时保留旧 manifest 的 note（重编来源等人工元信息）。
        import json
        tmp = self._bundle_with_libmnn()
        mpath = os.path.join(tmp, "native-manifest.json")
        with open(mpath, "w", encoding="utf-8") as f:
            json.dump({"schemaVersion": 1, "mnnCommit": "old", "ndkVersion": "old",
                       "androidApi": 24, "abi": "arm64-v8a", "flags": [],
                       "note": "legacy rebuild note", "files": []}, f)
        rc = vnb.main(["--generate", "--dir", tmp, "--manifest", mpath])
        self.assertEqual(rc, 0)
        with open(mpath, "r", encoding="utf-8") as f:
            new = json.load(f)
        self.assertEqual(new["note"], "legacy rebuild note")
        self.assertEqual(new["ndkVersion"], "26.1.10909125")
        self.assertIn("opencl_probe", new["flags"])


# ---------------------------------------------------------------------------
# Smoke tests against the real committed prebuilt libraries (stable properties)
# ---------------------------------------------------------------------------
@unittest.skipUnless(os.path.isdir(JNILIB_DIR), "jniLibs/arm64-v8a not present")
class TestRealBundleSmoke(unittest.TestCase):

    def _lib(self, name):
        p = os.path.join(JNILIB_DIR, name)
        return p if os.path.isfile(p) else None

    def test_libmnn_machine_is_aarch64(self):
        p = self._lib("libMNN.so")
        if not p:
            self.skipTest("libMNN.so absent")
        with open(p, "rb") as f:
            info = vnb.parse_elf_bytes(f.read())
        self.assertEqual(info.machine, "aarch64")

    def test_libmnn_has_build_id(self):
        p = self._lib("libMNN.so")
        if not p:
            self.skipTest("libMNN.so absent")
        with open(p, "rb") as f:
            info = vnb.parse_elf_bytes(f.read())
        self.assertIsNotNone(info.build_id)
        self.assertGreater(len(info.build_id), 0)

    def test_libmnn_jni_needs_libmnn(self):
        p = self._lib("libmnn_jni.so")
        if not p:
            self.skipTest("libmnn_jni.so absent")
        with open(p, "rb") as f:
            info = vnb.parse_elf_bytes(f.read())
        self.assertIn("libMNN.so", info.dt_needed)

    def test_every_standard_so_parses(self):
        # Only the standard (non-QNN) libraries are 64-bit AArch64. The QNN
        # skeleton libs (libQnnHtpV*Skel.so) are 32-bit ARM ELFs and are slated
        # for removal in Task 11; they are intentionally not asserted here.
        standard = ("libMNN.so", "libmnn_jni.so", "libcpu_sys_jni.so",
                    "libc++_shared.so")
        for name in standard:
            p = self._lib(name)
            if not p:
                self.skipTest(f"{name} absent")
            with open(os.path.join(JNILIB_DIR, name), "rb") as f:
                info = vnb.parse_elf_bytes(f.read())
            self.assertEqual(info.machine, "aarch64", msg=name)


if __name__ == "__main__":
    unittest.main(verbosity=2)
