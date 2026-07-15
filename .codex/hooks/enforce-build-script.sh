#!/usr/bin/env bash
# PreToolUse hook：强制通过 build-desktop.sh 构建 Desktop。
# stdin = {"tool_name":"Bash","tool_input":{"command":"..."}}
# exit 0 = 放行；exit 2 = 拦截。无法解析输入时 fail-open，避免误阻其他命令。

INPUT="$(cat)"

if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="$(command -v python3)"
elif command -v python >/dev/null 2>&1; then
  PYTHON_BIN="$(command -v python)"
else
  exit 0
fi

BLOCKED="$(printf '%s' "$INPUT" | "$PYTHON_BIN" -c '
import json
import sys

try:
    data = json.load(sys.stdin)
except (json.JSONDecodeError, UnicodeDecodeError, TypeError, ValueError):
    raise SystemExit(0)

tool_input = data.get("tool_input", {}) if isinstance(data, dict) else {}
command = tool_input.get("command", "") if isinstance(tool_input, dict) else ""
if isinstance(command, str) and "createDistributable" in command and "build-desktop.sh" not in command:
    print("BLOCKED")
' 2>/dev/null || true)"

if [ "$BLOCKED" = "BLOCKED" ]; then
  echo "禁止直接调用 gradlew createDistributable。请使用 ./scripts/build-desktop.sh（支持 hash/feature/stage/msi）。"
  exit 2
fi

exit 0
