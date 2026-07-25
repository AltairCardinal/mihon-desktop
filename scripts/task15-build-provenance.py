#!/usr/bin/env python3
"""Fail-closed source and artifact provenance for Task 15 platform acceptance."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
from typing import Any, Dict, List, Tuple, Union

ALGORITHM = "mihon-production-input-v1:sha256(mode<TAB>relative-path<TAB>raw-byte-sha256<LF>)"
APP_VERSION = pathlib.PurePosixPath(
    "app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt",
)
EXCLUSIONS = [
    "docs/**",
    "openspec/**",
    "test-desktop/**",
    "scripts/tests/**",
    "scripts/task15-platform-evidence-test.{ps1,sh}",
    ".codex/**",
    "**/src/*Test/**",
    "**/src/*test/**",
    "**/build/**",
]


def git(root: pathlib.Path, *args: str, text: bool = True) -> Union[str, bytes]:
    return subprocess.check_output(["git", "-C", str(root), *args], text=text)


def is_product_input(path: pathlib.PurePosixPath) -> bool:
    value = path.as_posix()
    parts = value.split("/")
    if value.startswith(("docs/", "openspec/", "test-desktop/", "scripts/tests/", ".codex/")):
        return False
    if value in {
        "scripts/task15-platform-evidence-test.ps1",
        "scripts/task15-platform-evidence-test.sh",
    }:
        return False
    if "build" in parts:
        return False
    return not any(
        part == "src" and index + 1 < len(parts) and "test" in parts[index + 1].lower()
        for index, part in enumerate(parts[:-1])
    )


def is_untracked_product_input(path: pathlib.PurePosixPath) -> bool:
    if not is_product_input(path):
        return False
    value = path.as_posix()
    parts = value.split("/")
    if "src" in parts:
        source_index = parts.index("src")
        if (
            source_index + 1 < len(parts)
            and "test" not in parts[source_index + 1].lower()
        ):
            return True
    return (
        value.startswith(("buildSrc/", "gradle/"))
        or value
        in {
            "gradle.properties",
            "scripts/build-desktop.sh",
            "scripts/build-windows.ps1",
            "scripts/task15-build-provenance.py",
        }
        or pathlib.PurePosixPath(value).name
        in {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}
    )


def assert_committed_inputs(root: pathlib.Path) -> None:
    changed = git(root, "diff", "--name-only", "HEAD", "--").splitlines()
    changed_product = [
        value for value in changed if is_product_input(pathlib.PurePosixPath(value))
    ]
    if changed_product:
        raise ValueError(f"modified product input(s): {', '.join(changed_product)}")
    untracked = git(
        root,
        "ls-files",
        "--others",
        "--exclude-standard",
        "-z",
        text=False,
    ).split(b"\0")
    untracked_product = [
        os.fsdecode(value)
        for value in filter(None, untracked)
        if is_untracked_product_input(pathlib.PurePosixPath(os.fsdecode(value)))
    ]
    if untracked_product:
        raise ValueError(f"untracked product input(s): {', '.join(untracked_product)}")


def version_fields(text: str) -> Tuple[int, int, int]:
    values = []
    for name in ("STAGE", "FEATURE", "BUILD"):
        match = re.search(rf"const val {name} = (\d+)", text)
        if not match:
            raise ValueError(f"cannot read AppVersion.{name}")
        values.append(int(match.group(1)))
    return tuple(values)  # type: ignore[return-value]


def assert_expected_version_allocation(root: pathlib.Path) -> Dict[str, str]:
    if not git(root, "rev-parse", "--verify", "HEAD^").strip():
        raise ValueError("version allocation requires a parent commit")
    previous = git(root, "show", f"HEAD^:{APP_VERSION.as_posix()}")
    current = (root / APP_VERSION).read_text(encoding="utf-8")
    previous_fields = version_fields(previous)
    current_fields = version_fields(current)
    expected = (previous_fields[0], previous_fields[1], previous_fields[2] + 1)
    if current_fields != expected:
        raise ValueError(
            "evidence build requires the committed AppVersion transition "
            f"{previous_fields} -> {expected}, got {current_fields}",
        )
    return {
        "kind": "BUILD_INCREMENT",
        "from": ".".join(map(str, previous_fields)),
        "to": ".".join(map(str, current_fields)),
    }


def source_identity(root: pathlib.Path, require_version_allocation: bool) -> Dict[str, Any]:
    assert_committed_inputs(root)
    transition = (
        assert_expected_version_allocation(root) if require_version_allocation else None
    )
    tracked = git(root, "ls-files", "-z", text=False).split(b"\0")
    records: List[str] = []
    for raw in sorted(filter(None, tracked)):
        path = pathlib.PurePosixPath(os.fsdecode(raw))
        if not is_product_input(path):
            continue
        absolute = root.joinpath(*path.parts)
        if not absolute.is_file():
            raise ValueError(f"missing product input: {path}")
        index = git(root, "ls-files", "-s", "--", path.as_posix())
        mode = index[:6]
        digest = hashlib.sha256(absolute.read_bytes()).hexdigest()
        records.append(f"{mode}\t{path.as_posix()}\t{digest}\n")
    canonical = "".join(records).encode()
    result: Dict[str, Any] = {
        "sourceCommit": git(root, "rev-parse", "HEAD").strip(),
        "sourceTree": git(root, "rev-parse", "HEAD^{tree}").strip(),
        "productSource": {
            "algorithm": ALGORITHM,
            "exclusions": EXCLUSIONS,
            "fileCount": len(records),
            "digest": hashlib.sha256(canonical).hexdigest(),
        },
    }
    if transition:
        result["versionAllocation"] = transition
    return result


def artifact_identity(path: pathlib.Path) -> Dict[str, Any]:
    if path.is_file():
        content = path.read_bytes()
        return {
            "algorithm": "sha256(raw-file)",
            "fileCount": 1,
            "sha256": hashlib.sha256(content).hexdigest(),
            "size": len(content),
        }
    if not path.is_dir():
        raise ValueError(f"artifact is missing: {path}")
    records: List[str] = []
    total_size = 0
    file_count = 0
    for item in sorted(path.rglob("*"), key=lambda value: value.relative_to(path).as_posix()):
        relative = item.relative_to(path).as_posix()
        if item.is_symlink():
            target = os.readlink(str(item))
            digest = hashlib.sha256(target.encode()).hexdigest()
            records.append(f"symlink\t{relative}\t{digest}\n")
        elif item.is_file():
            content = item.read_bytes()
            digest = hashlib.sha256(content).hexdigest()
            records.append(f"file\t{relative}\t{digest}\n")
            total_size += len(content)
            file_count += 1
    return {
        "algorithm": "mihon-artifact-tree-v1:sha256(kind<TAB>relative-path<TAB>content-sha256<LF>)",
        "fileCount": file_count,
        "sha256": hashlib.sha256("".join(records).encode()).hexdigest(),
        "size": total_size,
    }


def load(path: pathlib.Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write(path: pathlib.Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def seal(args: argparse.Namespace) -> Dict[str, Any]:
    root = args.repo.resolve()
    captured = load(args.source)
    current = source_identity(root, args.require_version_allocation)
    if captured != current:
        raise ValueError("committed source identity changed between capture and seal")
    result = {
        "schemaVersion": 1,
        "generatedBy": "scripts/task15-build-provenance.py",
        **current,
        "artifact": artifact_identity(args.artifact),
    }
    write(args.output, result)
    return result


def verify(args: argparse.Namespace) -> Dict[str, Any]:
    root = args.repo.resolve()
    provenance = load(args.provenance)
    current = source_identity(root, args.require_version_allocation)
    for key in ("sourceCommit", "sourceTree", "productSource"):
        if provenance.get(key) != current.get(key):
            raise ValueError(f"provenance {key} does not match committed source")
    if args.require_version_allocation and provenance.get("versionAllocation") != current.get(
        "versionAllocation",
    ):
        raise ValueError("provenance version allocation does not match committed source")
    if provenance.get("artifact") != artifact_identity(args.artifact):
        raise ValueError("provenance artifact hash/size mismatch")
    return provenance


def policy(args: argparse.Namespace) -> Dict[str, Any]:
    payload = json.load(sys.stdin) if str(args.input) == "-" else load(args.input)

    def pid_values(name: str) -> List[int]:
        raw = payload.get(name)
        if raw is None:
            return []
        values = raw if isinstance(raw, list) else [raw]
        return [int(value) for value in values if value is not None]

    if args.kind == "terminal":
        cursor = int(payload["cursor"])
        history = payload["history"]
        if len(history) < cursor:
            raise ValueError("action history cursor moved backwards")
        terminal = [
            record
            for record in history[cursor:]
            if record.get("action")
            in {
                "ExternalActionSucceeded",
                "ExternalActionRejected",
                "ExternalActionFailed",
            }
        ]
        if not terminal:
            return {"status": "PENDING"}
        if len(terminal) != 1:
            raise ValueError("expected exactly one terminal action after cursor")
        record = terminal[0]
        if record.get("action") != "ExternalActionRejected":
            raise ValueError("terminal action is not ExternalActionRejected")
        if record.get("params", {}).get("target") != "ParserRejected":
            raise ValueError("rejection target is not ParserRejected")
        return {"status": "VALID", "record": record}
    if args.kind == "screenshot":
        if payload.get("success") is not True:
            raise ValueError("screenshot result is not successful")
        return {"status": "VALID", "screenshot": payload}
    if args.kind == "pid-empty":
        pids = pid_values("pids")
        if pids:
            raise ValueError(f"existing owner PID(s): {pids}")
        return {"status": "VALID"}
    if args.kind == "pid-owned":
        owned = pid_values("owned")
        current = pid_values("current")
        if len(owned) != 1 or current != owned:
            raise ValueError(f"owner PID changed: owned={owned}, current={current}")
        return {"status": "VALID", "owner": owned[0]}
    if args.kind == "pid-cleanup":
        owned = set(pid_values("owned"))
        current = pid_values("current")
        return {
            "status": "VALID",
            "kill": [value for value in current if value in owned],
        }
    raise ValueError(f"unsupported policy kind: {args.kind}")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    subcommands = result.add_subparsers(dest="command", required=True)
    for name in ("source", "seal", "verify"):
        command = subcommands.add_parser(name)
        command.add_argument("--repo", type=pathlib.Path, required=True)
        command.add_argument("--require-version-allocation", action="store_true")
        if name in {"seal", "verify"}:
            command.add_argument("--artifact", type=pathlib.Path, required=True)
        if name == "source":
            command.add_argument("--output", type=pathlib.Path, required=True)
        elif name == "seal":
            command.add_argument("--source", type=pathlib.Path, required=True)
            command.add_argument("--output", type=pathlib.Path, required=True)
        else:
            command.add_argument("--provenance", type=pathlib.Path, required=True)
    policy_command = subcommands.add_parser("policy")
    policy_command.add_argument(
        "--kind",
        choices=("terminal", "screenshot", "pid-empty", "pid-owned", "pid-cleanup"),
        required=True,
    )
    policy_command.add_argument("--input", type=pathlib.Path, required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "source":
            value = source_identity(args.repo.resolve(), args.require_version_allocation)
            write(args.output, value)
        elif args.command == "seal":
            value = seal(args)
        elif args.command == "verify":
            value = verify(args)
        else:
            value = policy(args)
        print(json.dumps(value, ensure_ascii=False))
        return 0
    except (OSError, subprocess.CalledProcessError, ValueError, json.JSONDecodeError) as error:
        print(f"Task151 provenance rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
