#!/usr/bin/env python3
"""Replicate the working-tree roleEvidence anchor check from
DesktopProductCapabilityContractTest.validateRoleEvidence for every manifest item.

The Kotlin assertion short-circuits at the first failure, so this script reports
*all* anchors so drift hidden behind an earlier failure is visible.

Usage:
    python check-role-evidence.py worktree   # current files
    python check-role-evidence.py head       # git show HEAD:<path>
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app-desktop" / "src" / "test" / "resources" / "parity" / "parity-manifest.json"


def read_worktree(path: str):
    f = ROOT / path
    if not f.is_file():
        return None
    return f.read_text(encoding="utf-8").splitlines()


def read_head(path: str):
    proc = subprocess.run(
        ["git", "show", f"HEAD:{path}"],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if proc.returncode != 0:
        return None
    return proc.stdout.splitlines()


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "worktree"
    reader = read_worktree if mode == "worktree" else read_head
    items = json.loads(MANIFEST.read_text(encoding="utf-8"))
    total = 0
    failures = []
    for item in items:
        cap_id = item.get("id")
        role_evidence = item.get("roleEvidence") or {}
        for role, entries in role_evidence.items():
            if role == "FIXED_ORIGINAL":
                continue
            for idx, entry in enumerate(entries or []):
                if "artifact" in entry:
                    continue
                path = entry.get("path")
                symbol = entry.get("symbol")
                line = entry.get("line")
                if not path or symbol is None or not isinstance(line, int):
                    continue
                total += 1
                lines = reader(path)
                if lines is None:
                    failures.append((cap_id, role, idx, path, line, symbol, "FILE_MISSING"))
                    continue
                if line < 1 or line > len(lines):
                    failures.append((cap_id, role, idx, path, line, symbol, "LINE_OUT_OF_RANGE"))
                    continue
                if symbol not in lines[line - 1]:
                    failures.append((cap_id, role, idx, path, line, symbol, "SYMBOL_MISSING"))
    print(f"mode={mode} anchors_checked={total} failures={len(failures)}")
    for f in failures:
        print("FAIL id=%s role=%s[%d] path=%s line=%s symbol=%r reason=%s" % f)
    return 0


if __name__ == "__main__":
    sys.exit(main())
