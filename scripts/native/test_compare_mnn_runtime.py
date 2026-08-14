#!/usr/bin/env python3
"""Tests for the MNN upstream runtime delta comparator.

Run from the repository root:

    python -m unittest scripts/native/test_compare_mnn_runtime.py -v

These tests are deterministic pure-logic checks: they feed in-memory
``base_files``/``candidate_files`` (path -> content) so no git repo is required.
One test exercises the CLI repo path by pointing at a throwaway directory with
unreachable commits and asserting the ValueError guard.
"""
import tempfile
import unittest
from pathlib import Path

from scripts.native.compare_mnn_runtime import (
    AUTO_SECTION_BEGIN,
    AUTO_SECTION_END,
    compare_features,
    render_markdown,
    write_output,
)


class RuntimeDeltaTest(unittest.TestCase):
    def test_reports_feature_added_only_in_candidate(self):
        report = compare_features(
            base_files={"source/a.cpp": "CPU path"},
            candidate_files={"source/a.cpp": "CPU path\nLinearAttentionSizeComputer"},
        )
        self.assertFalse(report.features["cpu_linear_attention"].present_in_base)
        self.assertTrue(report.features["cpu_linear_attention"].present_in_candidate)

    def test_reports_feature_already_present_in_base(self):
        files = {"source/shape/ShapeAttention.cpp": "LinearAttentionSizeComputer"}
        report = compare_features(base_files=files, candidate_files=files)
        feature = report.features["cpu_linear_attention"]
        self.assertTrue(feature.present_in_base)
        self.assertTrue(feature.present_in_candidate)

    def test_rejects_unknown_or_unfetched_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "commit"):
                compare_features(
                    repo=Path(directory),
                    base_commit="missing-base",
                    candidate_commit="missing-candidate",
                )

    def test_writes_stable_markdown_order(self):
        report = compare_features(base_files={}, candidate_files={})
        first = render_markdown(report)
        second = render_markdown(report)
        self.assertEqual(first, second)
        self.assertLess(first.index("cpu_linear_attention"), first.index("opencl_topkv2"))


class OutputMergeTest(unittest.TestCase):
    """--output 可重复运行：只更新 marker 之间的自动生成段，保留手写段。"""

    def test_output_merge_preserves_handwritten_sections(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "delta.md"
            handwritten_head = "# 手写标题\n\n保留的引言\n\n"
            handwritten_tail = "\n## 手写尾部\n仍然保留\n"
            old_auto = "旧的自动生成内容"
            path.write_text(
                handwritten_head
                + AUTO_SECTION_BEGIN + "\n" + old_auto + "\n"
                + AUTO_SECTION_END + "\n"
                + handwritten_tail,
                encoding="utf-8",
            )

            replaced = write_output(path, "新的自动生成内容")

            self.assertTrue(replaced, "应就地替换已存在的 marker 段")
            text = path.read_text(encoding="utf-8")
            self.assertIn("# 手写标题", text)
            self.assertIn("保留的引言", text)
            self.assertIn("## 手写尾部", text)
            self.assertIn("仍然保留", text)
            self.assertNotIn(old_auto, text)
            self.assertIn("新的自动生成内容", text)
            # 手写段位于 marker 段之外。
            self.assertLess(text.index("# 手写标题"), text.index(AUTO_SECTION_BEGIN))
            self.assertGreater(text.index("## 手写尾部"), text.index(AUTO_SECTION_END))

    def test_output_without_markers_appends_auto_section(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "delta.md"
            path.write_text("仅手写，无 marker\n", encoding="utf-8")

            replaced = write_output(path, "追加的自动段")

            self.assertFalse(replaced, "无 marker 时应追加而非替换")
            text = path.read_text(encoding="utf-8")
            self.assertIn("仅手写，无 marker", text)
            self.assertIn(AUTO_SECTION_BEGIN, text)
            self.assertIn("追加的自动段", text)
            self.assertIn(AUTO_SECTION_END, text)
            # 手写内容在 marker 段之前。
            self.assertLess(text.index("仅手写，无 marker"), text.index(AUTO_SECTION_BEGIN))

    def test_output_creates_file_when_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "delta.md"
            replaced = write_output(path, "新建的自动段")
            self.assertFalse(replaced, "新文件不应报告就地替换")
            text = path.read_text(encoding="utf-8")
            self.assertIn(AUTO_SECTION_BEGIN, text)
            self.assertIn("新建的自动段", text)
            self.assertIn(AUTO_SECTION_END, text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
