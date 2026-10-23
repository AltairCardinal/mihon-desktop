"""Re-resolve the working-tree role evidence anchors in the parity manifest.

`app-desktop/src/test/resources/parity/parity-manifest.json` pins production evidence by
file, line and symbol. `DesktopProductCapabilityContractTest.validateRoleEvidence`
resolves the role anchors (`CURRENT_ANDROID`, `SHARED_OR_ADAPTER`, `DESKTOP_CONSUMER`)
against the working tree, so every production edit that shifts code invalidates the
recorded line and turns the contract test red.

This tool performs exactly that maintenance, and nothing else:

  * only `roleEvidence` entries of the current roles are considered;
  * only the `line` field is rewritten, and only to the line that actually contains the
    recorded `symbol`; `path` and `symbol` are never changed;
  * capability status, fixed-original evidence and the `actionInventory` provenance
    matrix are never touched, because those are resolved against a pinned upstream
    revision rather than the working tree.

Usage:
    python scripts/refresh-parity-role-evidence.py [--check]

`--check` reports drift without writing the manifest.
"""

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
MANIFEST = REPO / "app-desktop" / "src" / "test" / "resources" / "parity" / "parity-manifest.json"

# Roles that the contract test resolves against the working tree.
WORKING_TREE_ROLES = ("CURRENT_ANDROID", "SHARED_OR_ADAPTER", "DESKTOP_CONSUMER")


def read_lines(path: str):
    source = REPO / path
    if not source.is_file():
        return None
    return source.read_text(encoding="utf-8").splitlines()


def resolve(entry, cache):
    """Return (new_line, status) for one role evidence entry."""
    path = entry.get("path")
    symbol = entry.get("symbol")
    line = entry.get("line")
    if not isinstance(path, str) or not isinstance(symbol, str) or not isinstance(line, int):
        return None, "SKIP"
    if path not in cache:
        cache[path] = read_lines(path)
    lines = cache[path]
    if lines is None:
        return None, "FILE_MISSING"
    if 0 < line <= len(lines) and symbol in lines[line - 1]:
        return None, "OK"
    candidates = [index + 1 for index, text in enumerate(lines) if symbol in text]
    if not candidates:
        return None, "UNRESOLVED"
    if len(candidates) == 1:
        return candidates[0], "MOVED"
    # Several occurrences: the recorded line drifted the least, so it identifies the
    # same production statement the evidence was written against.
    best = min(candidates, key=lambda candidate: (abs(candidate - line), candidate))
    return best, "AMBIGUOUS"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="report drift without writing")
    args = parser.parse_args()

    text = MANIFEST.read_text(encoding="utf-8")
    manifest = json.loads(text)
    cache = {}
    updates = []
    unresolved = []

    for item in manifest:
        cap_id = item.get("id")
        role_evidence = item.get("roleEvidence") or {}
        for role in WORKING_TREE_ROLES:
            for index, entry in enumerate(role_evidence.get(role) or []):
                if not isinstance(entry, dict) or "artifact" in entry:
                    continue
                new_line, status = resolve(entry, cache)
                if status == "MOVED" or status == "AMBIGUOUS":
                    updates.append((cap_id, role, index, entry, new_line, status))
                elif status in ("UNRESOLVED", "FILE_MISSING"):
                    unresolved.append((cap_id, role, index, entry, status))

    for cap_id, role, index, entry, new_line, status in updates:
        print(f"{status:>9} ID {cap_id} {role}[{index}] {entry['path']}:{entry['line']} -> {new_line}")
    for cap_id, role, index, entry, status in unresolved:
        print(f"{status:>9} ID {cap_id} {role}[{index}] {entry.get('path')}:{entry.get('line')} "
              f"symbol={entry.get('symbol')!r}")

    if args.check:
        print(f"check only: {len(updates)} movable anchors, {len(unresolved)} unresolved")
        return 1 if unresolved else 0

    for _, _, _, entry, new_line, _ in updates:
        entry["line"] = new_line

    MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"updated {len(updates)} role evidence anchors; {len(unresolved)} left untouched")
    return 1 if unresolved else 0


if __name__ == "__main__":
    sys.exit(main())
