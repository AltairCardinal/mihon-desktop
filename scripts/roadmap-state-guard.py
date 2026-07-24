#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


FRONTMATTER_BOUNDARY = "---"
TASK_OVERVIEW = re.compile(r"^\s*-\s+\[([ xX])]\s+Task\s+([0-9]+[A-Za-z]?)\b")


def frontmatter(path: Path) -> dict[str, str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() != FRONTMATTER_BOUNDARY:
        return {}
    result: dict[str, str] = {}
    for line in lines[1:]:
        if line.strip() == FRONTMATTER_BOUNDARY:
            break
        if ":" in line:
            key, value = line.split(":", 1)
            result[key.strip()] = value.strip()
    return result


def first_unchecked_task(path: Path) -> str | None:
    for line in path.read_text(encoding="utf-8").splitlines():
        match = TASK_OVERVIEW.match(line)
        if match and match.group(1) == " ":
            return f"Task {match.group(2)}"
    return None


def resolve_pointer(value: str, parent: Path) -> Path:
    pointer = Path(value)
    if pointer.is_absolute():
        return pointer.resolve()
    from_cwd = (Path.cwd() / pointer).resolve()
    if from_cwd.exists():
        return from_cwd
    return (parent.parent / pointer).resolve()


def validate(parent: Path, execution: Path, children: list[Path]) -> list[str]:
    errors: list[str] = []
    parent_meta = frontmatter(parent)
    execution_meta = frontmatter(execution)

    pointer = parent_meta.get("active-child-plan")
    if not pointer:
        errors.append("parent plan must define exactly one active-child-plan")
    elif resolve_pointer(pointer, parent) != execution.resolve():
        errors.append(f"parent active-child-plan must point to {execution}")

    active_task = execution_meta.get("active-task")
    expected_task = first_unchecked_task(execution)
    if not active_task:
        errors.append("execution plan must define active-task")
    elif expected_task and active_task != expected_task:
        errors.append(
            f"execution active-task {active_task} must match first unchecked {expected_task}"
        )

    for child in children:
        child_meta = frontmatter(child)
        if "active-task" in child_meta:
            errors.append(f"{child}: child plan must not define active-task")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--parent", required=True, type=Path)
    parser.add_argument("--execution", required=True, type=Path)
    parser.add_argument("--child", action="append", default=[], type=Path)
    args = parser.parse_args()

    errors = validate(args.parent, args.execution, args.child)
    if errors:
        print("\n".join(f"ERROR: {error}" for error in errors), file=sys.stderr)
        return 1
    print(
        f"PASS: one active-task authority in {args.execution}; "
        f"{len(args.child)} child plans derive progress from checkboxes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
