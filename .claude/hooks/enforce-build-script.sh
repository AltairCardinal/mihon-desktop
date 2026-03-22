#!/usr/bin/env bash
# PreToolUse hook: 强制使用 build-desktop.sh 构建 Desktop
# stdin = {"tool_name":"Bash","tool_input":{"command":"..."}}
# exit 0 = 放行, exit 2 = 拦截

INPUT=$(cat)

BLOCKED=$(echo "$INPUT" | python3 -c "
import json, sys
data = json.loads(sys.stdin.read())
cmd = data.get('tool_input', {}).get('command', '')
if 'createDistributable' in cmd and 'build-desktop.sh' not in cmd:
    print('BLOCKED')
" 2>/dev/null)

if [ "$BLOCKED" = "BLOCKED" ]; then
  echo "⚠️ 禁止直接调用 gradlew createDistributable。请使用 ./scripts/build-desktop.sh (支持: hash/feature/stage)"
  exit 2
fi
exit 0
