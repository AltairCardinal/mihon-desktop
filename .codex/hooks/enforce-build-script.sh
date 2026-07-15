#!/usr/bin/env bash
# PreToolUse hook：阻止常见的 Gradle Desktop 分发任务直调。
# stdin = {"tool_name":"Bash","tool_input":{"command":"..."}}
# exit 0 = 放行；exit 2 = 拦截。无法解析输入时 fail-open，避免误阻其他命令。
# 这是项目工作流门禁，不是不可绕过的 shell 安全沙箱。

INPUT="$(cat)"

if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="$(command -v python3)"
elif command -v python >/dev/null 2>&1; then
  PYTHON_BIN="$(command -v python)"
else
  exit 0
fi

printf '%s' "$INPUT" | "$PYTHON_BIN" -c '
import json
import re
import sys

try:
    data = json.load(sys.stdin)
except (json.JSONDecodeError, UnicodeDecodeError, TypeError, ValueError):
    raise SystemExit(0)

tool_input = data.get("tool_input", {}) if isinstance(data, dict) else {}
command = tool_input.get("command", "") if isinstance(tool_input, dict) else ""
if not isinstance(command, str):
    raise SystemExit(0)

gradle_invocation = re.compile(
    r"(?m)(?:^|[;&|])\s*"
    r"(?:(?:command|exec)\s+)?"
    r"(?:env(?:\s+[A-Za-z_][A-Za-z0-9_]*=[^\s;&|]+)*\s+)?"
    r"(?:(?:[^\s;&|\"\x27]*/)?gradlew(?:\.bat)?|gradle)(?=\s|$)",
)
distribution_task = re.compile(
    r"(?<![A-Za-z0-9_])(?:[:A-Za-z0-9_.-]+:)?(?:createDistributable|packageMsi)(?![A-Za-z0-9_])",
)

for invocation in gradle_invocation.finditer(command):
    tail = command[invocation.end():]
    separator = re.search(r"[;&|\n]", tail)
    segment = tail[: separator.start()] if separator else tail
    if distribution_task.search(segment):
        print(
            "禁止直接调用 Gradle Desktop 分发任务（createDistributable/packageMsi）；"
            "请使用 ./scripts/build-desktop.sh。",
            file=sys.stderr,
        )
        raise SystemExit(2)
'
STATUS=$?
if [ "$STATUS" -eq 2 ]; then
  exit 2
fi

exit 0
