#!/usr/bin/env bash

set -u

usage() {
  echo "Usage: $0 plan <plan-file>" >&2
}

if [ "$#" -ne 2 ] || [ "$1" != "plan" ]; then
  usage
  exit 2
fi

PLAN_FILE="$2"
if [ ! -f "$PLAN_FILE" ]; then
  echo "ERROR: plan file not found: $PLAN_FILE" >&2
  exit 2
fi

if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="$(command -v python3)"
elif command -v python >/dev/null 2>&1; then
  PYTHON_BIN="$(command -v python)"
else
  echo "ERROR: plan guard requires python3 or python" >&2
  exit 2
fi

"$PYTHON_BIN" - "$PLAN_FILE" <<'PY'
from collections import Counter, defaultdict
from pathlib import Path
import re
import sys


plan_file = Path(sys.argv[1])
try:
    source_lines = plan_file.read_text(encoding="utf-8").splitlines()
except (OSError, UnicodeError) as exc:
    print(f"ERROR: cannot read plan as UTF-8: {plan_file}: {exc}", file=sys.stderr)
    raise SystemExit(2)


def visible_markdown(lines):
    in_comment = False
    fence_char = None
    fence_length = 0

    for line_number, raw_line in enumerate(lines, start=1):
        if fence_char is not None:
            closing = re.match(r"^ {0,3}([`~]+)[ \t]*$", raw_line)
            if closing and closing.group(1)[0] == fence_char and len(closing.group(1)) >= fence_length:
                fence_char = None
                fence_length = 0
            continue

        visible = ""
        cursor = 0
        while cursor < len(raw_line):
            if in_comment:
                end = raw_line.find("-->", cursor)
                if end < 0:
                    cursor = len(raw_line)
                else:
                    in_comment = False
                    cursor = end + 3
            else:
                start = raw_line.find("<!--", cursor)
                if start < 0:
                    visible += raw_line[cursor:]
                    cursor = len(raw_line)
                else:
                    visible += raw_line[cursor:start]
                    in_comment = True
                    cursor = start + 4

        opening = re.match(r"^ {0,3}(`{3,}|~{3,}).*$", visible)
        if opening:
            fence_char = opening.group(1)[0]
            fence_length = len(opening.group(1))
            continue
        yield line_number, visible


heading_pattern = re.compile(r"^ {0,3}(#{1,6})[ \t]+(.*?)[ \t]*#*[ \t]*$")
task_title_pattern = re.compile(r"^Task[ \t]+([0-9]+[A-Za-z]?)(?=[^0-9A-Za-z]|$)")
overview_pattern = re.compile(r"^ {0,3}-[ \t]+\[([ xX])\][ \t]+Task[ \t]+([0-9]+[A-Za-z]?)(?=[^0-9A-Za-z]|$)")
metadata_pattern = re.compile(
    r"^ {0,3}\*\*(Risk axis|Platform boundary|Estimated scope|Verification|Split waiver):\*\*[ \t]*(.*?)[ \t]*$"
)

overview_count = Counter()
pending = set()
body_count = Counter()
metadata_count = Counter()
metadata_value = {}
current_task = None
saw_task_body = False

for line_number, line in visible_markdown(source_lines):
    heading = heading_pattern.match(line)
    if heading:
        level = len(heading.group(1))
        if level <= 3:
            current_task = None
        task_title = task_title_pattern.match(heading.group(2)) if level == 3 else None
        if task_title:
            task = task_title.group(1)
            body_count[task] += 1
            current_task = task
            saw_task_body = True
        continue

    if not saw_task_body:
        overview = overview_pattern.match(line)
        if overview:
            state, task = overview.groups()
            overview_count[task] += 1
            if state == " ":
                pending.add(task)
            continue

    if current_task is not None:
        metadata = metadata_pattern.match(line)
        if metadata:
            field, value = metadata.groups()
            metadata_count[(current_task, field)] += 1
            metadata_value[(current_task, field)] = value.strip()

errors = []


def error(task, message):
    errors.append(f"ERROR: Task {task}: {message}")


if not overview_count:
    errors.append("ERROR: no visible top-level Task overview found before Task bodies")

for task, count in overview_count.items():
    if count != 1:
        error(task, "appears more than once in the visible top-level overview")

required_fields = ("Risk axis", "Platform boundary", "Estimated scope", "Verification")
allowed_boundaries = {
    "shared",
    "android",
    "desktop",
    "shared+android",
    "shared+desktop",
    "verification",
    "docs",
    "tooling",
}

for task in sorted(pending):
    if body_count[task] != 1:
        error(task, "must map to exactly one visible Task 正文")
        continue

    for field in required_fields:
        count = metadata_count[(task, field)]
        value = metadata_value.get((task, field), "")
        if count != 1 or not value:
            error(task, f"requires exactly one non-empty **{field}:** field in its Task 正文")

    risk = metadata_value.get((task, "Risk axis"), "")
    if risk and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", risk):
        error(task, "Risk axis 必须是单个非空 slug（只含字母、数字、_ 或 -，不得使用多值分隔符）")

    boundary = metadata_value.get((task, "Platform boundary"), "")
    if boundary and boundary not in allowed_boundaries:
        error(task, "Platform boundary must be one allowed value; android+desktop is forbidden")

    scope = metadata_value.get((task, "Estimated scope"), "")
    scope_match = re.fullmatch(r"([0-9]+) files, ([0-9]+) lines", scope)
    if scope and not scope_match:
        error(task, "Estimated scope must use: N files, M lines")
    elif scope_match:
        files, lines = map(int, scope_match.groups())
        if files > 8 or lines > 400:
            waiver_count = metadata_count[(task, "Split waiver")]
            waiver = metadata_value.get((task, "Split waiver"), "")
            if waiver_count != 1 or not waiver:
                error(task, "scope exceeds 8 files or 400 lines and requires one non-empty **Split waiver:**")

    if metadata_count[(task, "Split waiver")] > 1:
        error(task, "Split waiver may appear at most once")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)

print(f"PASS: 已检查 {len(pending)} 个待办 Task 正文：{plan_file}")
PY
