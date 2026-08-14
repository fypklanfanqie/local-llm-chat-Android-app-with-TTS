#!/usr/bin/env python3
"""Compare two MNN upstream commits and report runtime-relevant source deltas.

This is the **upgrade infrastructure** for the MNN runtime/GPU promotion gate
(Task 4 of the 2026-08-11 local-thinking-and-mnn-runtime-alignment plan). It
does NOT upgrade anything: it produces a reproducible diff report so a later
decision ("promote candidate to pinned") is based on observable source changes
plus device benchmarks, not on README feature names.

Usage:

    python scripts/native/compare_mnn_runtime.py \
      --repo /path/to/MNN \
      --base af0142bcc7b76b7a5128373e285683dc04f55f69 \
      --candidate 75e53afe568f7b6fabb1adc34894fe9f331d52f8 \
      --output docs/mnn-upstream-runtime-delta.md

Produces a [RuntimeDeltaReport] with:

* ``changedPaths``: name-status lines for the base..candidate diff,
* ``features``: presence (base/candidate) of each capability in [FEATURES].

**Honesty boundary.** Presence is a *source-scan heuristic*: a needle appearing
at the canonical path means "the capability is compiled into that commit" — it
does NOT mean the app's model graph uses it, nor that device benchmarks improve.
Both are required before promotion (see the promotion gate in
``ExperimentalPromotionPolicy.evaluateRuntime`` and the device matrix). The
report explicitly separates "present in base" (already available, not an upgrade
benefit), "present in candidate only" (new/modified), and unverified README
claims (hand-written into the delta document).

For efficiency the git path restricts each needle grep to the feature's
canonical path (file or directory); whole-tree greps on the MNN repo are far too
slow under Windows git.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

# ---------------------------------------------------------------------------
# Marker-based output merge.
#
# ``--output`` must be safely re-runnable: it only replaces the auto-generated
# section between the two markers below, preserving any handwritten sections
# (e.g. base-已有 / candidate-新增 / 未验证 / 决策边界 in the delta document).
# ---------------------------------------------------------------------------
AUTO_SECTION_BEGIN = "<!-- BEGIN MNN-RUNTIME-DELTA:auto -->"
AUTO_SECTION_END = "<!-- END MNN-RUNTIME-DELTA:auto -->"


# ---------------------------------------------------------------------------
# Feature spec table (key -> [path, needle]).
#
# ``path`` is the canonical location (file or directory) inside the MNN tree;
# ``needle`` is a distinctive source marker. A one-element spec is a pure
# path-existence check. Presence = the needle is found under that path (or the
# path exists). This is a heuristic, not proof of runtime capability — see the
# module docstring.
# ---------------------------------------------------------------------------
FEATURES: Dict[str, List[str]] = {
    "cpu_linear_attention": ["source/shape/ShapeAttention.cpp", "LinearAttentionSizeComputer"],
    "arm82_linear_attention_fp16": ["source/backend/arm82/Arm82Functions.cpp", "LinearAttention fp16 kernels"],
    "opencl_topkv2": ["source/backend/opencl/execution/image/TopKV2Execution.cpp"],
    "opencl_linear_attention": ["source/backend/opencl/execution/buffer/LinearAttentionBufExecution.cpp"],
    "thinking_template_compat": ["apps/Android/MnnLlmChat", "enable_thinking"],
}


@dataclass
class FeatureStatus:
    """Presence of one capability in base and candidate, with match evidence."""
    key: str
    path: str
    needle: Optional[str]
    present_in_base: bool
    present_in_candidate: bool
    base_matches: List[str] = field(default_factory=list)
    candidate_matches: List[str] = field(default_factory=list)


@dataclass
class RuntimeDeltaReport:
    """The ``RuntimeDeltaReport`` produced by the comparator (see module doc)."""
    baseCommit: Optional[str]
    candidateCommit: Optional[str]
    changedPaths: List[str]
    requiredFeatures: List[str]
    presentInBase: Dict[str, bool]
    presentInCandidate: Dict[str, bool]
    features: Dict[str, FeatureStatus]

    def added_or_modified(self) -> List[FeatureStatus]:
        """Features present in candidate (new in candidate or present in both)."""
        return [f for f in self.features.values() if f.present_in_candidate]

    def only_in_candidate(self) -> List[FeatureStatus]:
        """Features present in candidate but absent from base (upgrade gains)."""
        return [f for f in self.features.values()
                if f.present_in_candidate and not f.present_in_base]

    def only_in_base(self) -> List[FeatureStatus]:
        """Features present in base but absent from candidate (removals)."""
        return [f for f in self.features.values()
                if f.present_in_base and not f.present_in_candidate]


# ---------------------------------------------------------------------------
# Git helpers
# ---------------------------------------------------------------------------

def _require_commit(repo: Path, commit: Optional[str]) -> None:
    """Raise ValueError unless ``commit`` resolves to a commit object in ``repo``."""
    if not commit:
        raise ValueError("commit not given")
    proc = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "--verify", f"{commit}^{{commit}}"],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise ValueError(
            f"commit not found or unavailable in repo {repo}: {commit}"
        )


def _ls_tree(repo: Path, commit: str) -> List[str]:
    proc = subprocess.run(
        ["git", "-C", str(repo), "ls-tree", "-r", "--name-only", commit],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        return []
    return [p for p in proc.stdout.splitlines() if p]


def _path_exists(repo: Path, commit: str, path: str) -> bool:
    proc = subprocess.run(
        ["git", "-C", str(repo), "cat-file", "-e", f"{commit}:{path}"],
        capture_output=True, text=True,
    )
    return proc.returncode == 0


def _git_grep(repo: Path, commit: str, needle: str, path: str) -> List[str]:
    """Grep ``needle`` in ``commit`` restricted to ``path`` (file or dir).

    ``git grep`` prints ``<commit>:<path>``; we strip the commit prefix. Empty
    stdout (no match / pathspec absent) yields [] — exit codes are not relied on
    (git grep returns non-zero both for "no match" and "bad pathspec").
    """
    # A single pathspec works for both a file (matches that file) and a
    # directory (recursively matches everything under it).
    spec = [path] if path else []
    cmd = ["git", "-C", str(repo), "--no-pager", "grep", "-l", "--no-color", "-e", needle, commit]
    if spec:
        cmd += ["--"] + spec
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        return []
    out = []
    prefix = commit + ":"
    for ln in proc.stdout.splitlines():
        ln = ln.strip()
        if ln.startswith(prefix):
            ln = ln[len(prefix):]
        if ln:
            out.append(ln)
    return out


def _changed_paths(repo: Path, base: str, candidate: str) -> List[str]:
    """name-status lines (``<status>\\t<path>``) for base..candidate."""
    proc = subprocess.run(
        ["git", "-C", str(repo), "--no-pager", "diff", "--name-status", base, candidate],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        return []
    return [ln for ln in proc.stdout.splitlines() if ln]


# ---------------------------------------------------------------------------
# In-memory scan (pure unit tests)
# ---------------------------------------------------------------------------

def _scan_memory(files: Dict[str, str], spec: List[str]) -> List[str]:
    """Match a feature spec against an in-memory {path: content} map."""
    if len(spec) == 1:
        path = spec[0]
        return [path] if path in files else []
    needle = spec[1]
    return [p for p, content in files.items() if content and needle in content]


def _memory_changed_paths(base_files: Dict[str, str], candidate_files: Dict[str, str]) -> List[str]:
    lines = []
    for p in sorted(base_files):
        if p not in candidate_files:
            lines.append(f"D\t{p}")
        elif base_files[p] != candidate_files[p]:
            lines.append(f"M\t{p}")
    for p in sorted(candidate_files):
        if p not in base_files:
            lines.append(f"A\t{p}")
    return lines


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def compare_features(
    repo: Optional[Path] = None,
    base_commit: Optional[str] = None,
    candidate_commit: Optional[str] = None,
    base_files: Optional[Dict[str, str]] = None,
    candidate_files: Optional[Dict[str, str]] = None,
) -> RuntimeDeltaReport:
    """Compare two MNN snapshots and report feature presence + changed paths.

    Two call shapes:
    * in-memory: pass ``base_files``/``candidate_files`` as ``{path: content}``
      (pure unit tests; no git needed);
    * repo path: pass ``repo`` + ``base_commit`` + ``candidate_commit`` and the
      real git trees at those commits are scanned.
    """
    base_files = base_files or {}
    candidate_files = candidate_files or {}

    if repo is not None:
        _require_commit(repo, base_commit)
        _require_commit(repo, candidate_commit)
        assert base_commit is not None and candidate_commit is not None
        base_files = {p: None for p in _ls_tree(repo, base_commit)}
        candidate_files = {p: None for p in _ls_tree(repo, candidate_commit)}
        changed = _changed_paths(repo, base_commit, candidate_commit)
    else:
        changed = _memory_changed_paths(base_files, candidate_files)

    features: Dict[str, FeatureStatus] = {}
    for key, spec in FEATURES.items():
        if repo is not None:
            base_matches = _scan_git(repo, base_commit, spec)
            cand_matches = _scan_git(repo, candidate_commit, spec)
        else:
            base_matches = _scan_memory(base_files, spec)
            cand_matches = _scan_memory(candidate_files, spec)
        features[key] = FeatureStatus(
            key=key,
            path=spec[0],
            needle=spec[1] if len(spec) > 1 else None,
            present_in_base=bool(base_matches),
            present_in_candidate=bool(cand_matches),
            base_matches=base_matches,
            candidate_matches=cand_matches,
        )

    return RuntimeDeltaReport(
        baseCommit=base_commit,
        candidateCommit=candidate_commit,
        changedPaths=changed,
        requiredFeatures=list(FEATURES.keys()),
        presentInBase={k: f.present_in_base for k, f in features.items()},
        presentInCandidate={k: f.present_in_candidate for k, f in features.items()},
        features=features,
    )


def _scan_git(repo: Path, commit: str, spec: List[str]) -> List[str]:
    if len(spec) == 1:
        return [spec[0]] if _path_exists(repo, commit, spec[0]) else []
    return _git_grep(repo, commit, spec[1], spec[0])


# ---------------------------------------------------------------------------
# Markdown rendering
# ---------------------------------------------------------------------------

def render_markdown(report: RuntimeDeltaReport) -> str:
    """Render a stable, ordered Markdown report (deterministic for a given report)."""
    lines: List[str] = []
    lines.append("# MNN upstream runtime delta")
    lines.append("")
    lines.append(f"- base:      `{report.baseCommit or 'n/a'}`")
    lines.append(f"- candidate: `{report.candidateCommit or 'n/a'}`")
    lines.append("")
    lines.append("## Changed paths (base -> candidate)")
    lines.append("")
    if report.changedPaths:
        lines.append("```text")
        lines.extend(report.changedPaths)
        lines.append("```")
    else:
        lines.append("(none — in-memory scan without a repo)")
    lines.append("")
    lines.append("## Feature presence (source-scan heuristic)")
    lines.append("")
    lines.append("| feature | canonical path | marker | in base | in candidate |")
    lines.append("|---|---|---|---|---|")
    # FEATURES insertion order => stable, and cpu_linear_attention always
    # precedes opencl_topkv2 (asserted by test_writes_stable_markdown_order).
    for key, feat in report.features.items():
        marker = feat.needle or "(path existence)"
        lines.append(
            f"| {key} | `{feat.path}` | `{marker}` | "
            f"{'YES' if feat.present_in_base else 'no'} | "
            f"{'YES' if feat.present_in_candidate else 'no'} |"
        )
    lines.append("")
    lines.append("> Source-scan results only. Presence in base means the capability is")
    lines.append("> already available at the pinned commit — NOT an upgrade benefit.")
    lines.append("> Presence in candidate only is a candidate gain; real promotion still")
    lines.append("> requires model-graph usage and device benchmark gates.")
    lines.append("")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Output merge
# ---------------------------------------------------------------------------

def write_output(path: Path, auto_section: str) -> bool:
    """Merge ``auto_section`` into ``path``, preserving handwritten content.

    Re-runnable ``--output`` semantics:

    * file exists and contains both markers -> replace **only** the content
      between ``[AUTO_SECTION_BEGIN]`` and ``[AUTO_SECTION_END]`` with the new
      section, leaving everything else (handwritten prose) untouched;
    * file exists but has no markers -> append a new marker-wrapped section at
      the end;
    * file does not exist -> create it with just the marker-wrapped section.

    Returns True when an existing marked section was replaced in place, False
    when the section was appended or the file was created.
    """
    payload = f"\n{AUTO_SECTION_BEGIN}\n{auto_section}\n{AUTO_SECTION_END}\n"
    if not path.exists():
        path.write_text(payload, encoding="utf-8")
        return False

    text = path.read_text(encoding="utf-8")
    if AUTO_SECTION_BEGIN in text and AUTO_SECTION_END in text:
        start = text.index(AUTO_SECTION_BEGIN)
        end = text.index(AUTO_SECTION_END) + len(AUTO_SECTION_END)
        merged = text[:start] + payload + text[end:]
        path.write_text(merged, encoding="utf-8")
        return True

    if not text.endswith("\n"):
        text += "\n"
    path.write_text(text + payload, encoding="utf-8")
    return False


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compare two MNN upstream commits and write a runtime delta report.",
    )
    parser.add_argument("--repo", required=True, help="path to the MNN git clone")
    parser.add_argument("--base", required=True, help="base (pinned) commit SHA")
    parser.add_argument("--candidate", required=True, help="candidate commit SHA")
    parser.add_argument("--output", required=True, help="output markdown path")
    args = parser.parse_args(argv)

    report = compare_features(
        repo=Path(args.repo),
        base_commit=args.base,
        candidate_commit=args.candidate,
    )
    md = render_markdown(report)
    out = Path(args.output)
    replaced = write_output(out, md)

    only_new = [f.key for f in report.only_in_candidate()]
    mode = "replaced in-place" if replaced else ("appended" if out.exists() else "created")
    print(
        f"{mode}: {out} ({len(report.changedPaths)} changed paths, "
        f"{len(report.features)} features; candidate-only: {only_new or 'none'})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
