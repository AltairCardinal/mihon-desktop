#!/usr/bin/env bash

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GUARD="$REPO_ROOT/scripts/comet-project-guard.sh"
HOOK="$REPO_ROOT/.codex/hooks/enforce-build-script.sh"
HOOKS_JSON="$REPO_ROOT/.codex/hooks.json"
REAL_PLAN="$REPO_ROOT/docs/superpowers/plans/2026-07-15-mihon-source-extension-shared-core.md"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/mihon-comet-guard.XXXXXX")"
PASS_COUNT=0
FAIL_COUNT=0

cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS: %s\n' "$1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  printf 'FAIL: %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" >&2
  fi
}

expect_status() {
  local name="$1"
  local expected="$2"
  local pattern="$3"
  shift 3

  local output status
  output="$({ "$@"; } 2>&1)"
  status=$?
  if [ "$status" -ne "$expected" ]; then
    fail "$name（期望退出码 $expected，实际 $status）" "$output"
    return
  fi
  if [ -n "$pattern" ] && ! printf '%s\n' "$output" | grep -E "$pattern" >/dev/null; then
    fail "$name（输出未匹配：$pattern）" "$output"
    return
  fi
  pass "$name"
}

write_plan() {
  local path="$1"
  local overview_state="$2"
  local body="$3"
  {
    printf '# Fixture plan\n\n'
    printf '## 执行状态\n\n'
    printf -- '- [x] Task 0：已完成旧任务\n'
    printf -- '- [%s] Task 1：待检查任务\n\n' "$overview_state"
    printf '### Task 0: 已完成旧任务\n\n'
    printf '旧任务没有新门禁元数据。\n\n'
    printf '### Task 1: 待检查任务\n\n'
    printf '%s\n' "$body"
  } > "$path"
}

valid_body() {
  cat <<'EOF'
**Risk axis:** state-transition
**Platform boundary:** shared+desktop
**Estimated scope:** 4 files, 120 lines
**Verification:** 运行共享契约与 Desktop wiring 测试。
EOF
}

find_python() {
  if command -v python3 >/dev/null 2>&1; then
    command -v python3
  elif command -v python >/dev/null 2>&1; then
    command -v python
  else
    return 1
  fi
}

expect_hook_case() {
  local name="$1"
  local payload="$2"
  local expected_status="$3"
  local stderr_pattern="$4"
  local stdout_file="$TMP_ROOT/hook-stdout"
  local stderr_file="$TMP_ROOT/hook-stderr"

  : >"$stdout_file"
  : >"$stderr_file"
  (
    cd "$REPO_ROOT/docs" || exit 1
    printf '%s' "$payload" | bash -c "$HOOK_COMMAND"
  ) >"$stdout_file" 2>"$stderr_file"
  local status=$?

  if [ "$status" -ne "$expected_status" ]; then
    fail "$name（期望退出码 $expected_status，实际 $status）" "stderr: $(cat "$stderr_file")"
    return
  fi
  if [ -s "$stdout_file" ]; then
    fail "$name（stdout 应为空）" "$(cat "$stdout_file")"
    return
  fi
  if [ -n "$stderr_pattern" ]; then
    if ! grep -E "$stderr_pattern" "$stderr_file" >/dev/null; then
      fail "$name（stderr 未匹配：$stderr_pattern）" "$(cat "$stderr_file")"
      return
    fi
  elif [ -s "$stderr_file" ]; then
    fail "$name（stderr 应为空）" "$(cat "$stderr_file")"
    return
  fi
  pass "$name"
}

if [ ! -f "$GUARD" ]; then
  fail "项目门禁脚本存在" "缺少 $GUARD"
  printf '\nRESULT: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
  exit 1
fi

VALID_PLAN="$TMP_ROOT/valid.md"
write_plan "$VALID_PLAN" " " "$(valid_body)"
expect_status "合法计划通过" 0 "PASS.*1.*待办" bash "$GUARD" plan "$VALID_PLAN"

MISSING_PLAN="$TMP_ROOT/missing.md"
write_plan "$MISSING_PLAN" " " "$(valid_body | grep -v '^\*\*Verification:')"
expect_status "缺少字段失败" 1 "Task 1.*Verification" bash "$GUARD" plan "$MISSING_PLAN"

BLANK_PLAN="$TMP_ROOT/blank.md"
write_plan "$BLANK_PLAN" " " "$(valid_body | awk '/^\*\*Verification:/ { print "**Verification:**   "; next } { print }')"
expect_status "空白字段失败" 1 "Task 1.*Verification" bash "$GUARD" plan "$BLANK_PLAN"

FENCED_PLAN="$TMP_ROOT/fenced.md"
cat >"$FENCED_PLAN" <<EOF
# Fenced fixture

## 执行状态

- [ ] Task 1：待检查任务

\`\`\`markdown
### Task 1: 伪造正文
$(valid_body)
\`\`\`
EOF
expect_status "代码块伪造正文失败" 1 "Task 1.*正文|Task 1.*body" bash "$GUARD" plan "$FENCED_PLAN"

COMMENT_PLAN="$TMP_ROOT/comment.md"
cat >"$COMMENT_PLAN" <<EOF
# Comment fixture

## 执行状态

- [ ] Task 1：待检查任务

<!--
### Task 1: 伪造正文
$(valid_body)
-->
EOF
expect_status "HTML comment 伪造正文失败" 1 "Task 1.*正文|Task 1.*body" bash "$GUARD" plan "$COMMENT_PLAN"

APPENDIX_PLAN="$TMP_ROOT/appendix.md"
cat >"$APPENDIX_PLAN" <<EOF
# Appendix fixture

## 执行状态

- [ ] Task 1：待检查任务

### Task 1: 可见正文

正文缺少元数据。

### Appendix

$(valid_body)
EOF
expect_status "Appendix 元数据不能补足 Task" 1 "Task 1.*Risk axis" bash "$GUARD" plan "$APPENDIX_PLAN"

DOUBLE_SPACE_PLAN="$TMP_ROOT/double-space.md"
cat >"$DOUBLE_SPACE_PLAN" <<EOF
# Double-space fixture

## 执行状态

- [ ] Task 1：待检查任务

###  Task 1: 合法正文

$(valid_body)
EOF
expect_status "CommonMark 双空格 Task heading 通过" 0 "PASS.*1.*待办" bash "$GUARD" plan "$DOUBLE_SPACE_PLAN"

DUPLICATE_HEADING_PLAN="$TMP_ROOT/duplicate-heading.md"
cat >"$DUPLICATE_HEADING_PLAN" <<EOF
# Duplicate heading fixture

## 执行状态

- [ ] Task 1：待检查任务

### Task 1: 第一正文

$(valid_body)

### Task 1: 第二正文

$(valid_body)
EOF
expect_status "重复 Task heading 失败" 1 "Task 1.*正文|Task 1.*body" bash "$GUARD" plan "$DUPLICATE_HEADING_PLAN"

DUPLICATE_FIELD_PLAN="$TMP_ROOT/duplicate-field.md"
write_plan "$DUPLICATE_FIELD_PLAN" " " "$(valid_body)
**Verification:** 重复字段。"
expect_status "重复 metadata 字段失败" 1 "Task 1.*Verification" bash "$GUARD" plan "$DUPLICATE_FIELD_PLAN"

MULTI_RISK_PLAN="$TMP_ROOT/multi-risk.md"
write_plan "$MULTI_RISK_PLAN" " " "$(valid_body | sed 's/state-transition/state-transition, security/')"
expect_status "多个 risk axis 失败" 1 "Task 1.*Risk axis.*单个" bash "$GUARD" plan "$MULTI_RISK_PLAN"

CROSS_PLATFORM_PLAN="$TMP_ROOT/android-desktop.md"
write_plan "$CROSS_PLATFORM_PLAN" " " "$(valid_body | sed 's/shared+desktop/android+desktop/')"
expect_status "android+desktop 边界失败" 1 "Task 1.*Platform boundary" bash "$GUARD" plan "$CROSS_PLATFORM_PLAN"

OVERSIZE_PLAN="$TMP_ROOT/oversize.md"
write_plan "$OVERSIZE_PLAN" " " "$(valid_body | sed 's/4 files, 120 lines/9 files, 401 lines/')"
expect_status "超限无 waiver 仅警告并通过" 0 "WARNING.*Task 1.*9 files.*401 lines" bash "$GUARD" plan "$OVERSIZE_PLAN"

OVERSIZE_WAIVER_PLAN="$TMP_ROOT/oversize-waiver.md"
write_plan "$OVERSIZE_WAIVER_PLAN" " " "$(valid_body | sed 's/4 files, 120 lines/9 files, 401 lines/')
**Split waiver:** 单一迁移切面必须同时更新生成模型、映射器和共享契约，拆开会留下不可编译中间态。"
expect_status "超限有 waiver 通过" 0 "PASS.*1.*待办" bash "$GUARD" plan "$OVERSIZE_WAIVER_PLAN"

EMPTY_WAIVER_PLAN="$TMP_ROOT/empty-waiver.md"
write_plan "$EMPTY_WAIVER_PLAN" " " "$(valid_body | sed 's/4 files, 120 lines/9 files, 401 lines/')
**Split waiver:**   "
expect_status "超限空 waiver 仍仅警告并通过" 0 "WARNING.*Task 1.*9 files.*401 lines" bash "$GUARD" plan "$EMPTY_WAIVER_PLAN"

COMPLETED_PLAN="$TMP_ROOT/completed.md"
write_plan "$COMPLETED_PLAN" "x" "正文也没有新门禁元数据。"
expect_status "已完成旧 Task 可跳过" 0 "PASS.*0.*待办" bash "$GUARD" plan "$COMPLETED_PLAN"

expect_status "当前真实计划通过" 0 "PASS.*0.*待办" bash "$GUARD" plan "$REAL_PLAN"

TASK3_PLANNED_FILES="$TMP_ROOT/task3-planned-files"
TASK3_COMMIT_FILES="$TMP_ROOT/task3-commit-files"
awk '
  /^###  Task 3:|^### Task 3:/ { in_task = 1; next }
  in_task && /^###/ { exit }
  in_task && /^\*\*Files:\*\*/ { in_files = 1; next }
  in_files && /^\*\*Interfaces:\*\*/ { exit }
  in_files && /^- (Create|Modify): `/ {
    line = $0
    sub(/^- (Create|Modify): `/, "", line)
    sub(/`.*$/, "", line)
    print line
  }
' "$REAL_PLAN" | sort >"$TASK3_PLANNED_FILES"
git -C "$REPO_ROOT" show --name-only --format= 0502b755fb | sed '/^$/d' | sort >"$TASK3_COMMIT_FILES"
task3_diff="$(diff -u "$TASK3_COMMIT_FILES" "$TASK3_PLANNED_FILES" 2>&1)"
task3_status=$?
if [ "$task3_status" -eq 0 ]; then
  pass "Task 3 Files 与 0502b755fb 完全一致"
else
  fail "Task 3 Files 与 0502b755fb 不一致" "$task3_diff"
fi

PYTHON_BIN="$(find_python 2>/dev/null || true)"
HOOK_COMMAND=""
if [ -n "$PYTHON_BIN" ]; then
  HOOK_COMMAND="$($PYTHON_BIN - "$HOOKS_JSON" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
print(data["hooks"]["PreToolUse"][0]["hooks"][0]["command"])
PY
)"
fi

if [ ! -f "$HOOK" ]; then
  fail "构建 hook 存在" "缺少 $HOOK"
elif [ -z "$HOOK_COMMAND" ]; then
  fail "读取 hooks.json 真实命令" "需要可用 Python 解释器"
else
  expect_hook_case "子目录 hook 阻止 createDistributable" '{"tool_input":{"command":"./gradlew createDistributable"}}' 2 '禁止直接调用'
  expect_hook_case "hook 阻止模块限定 createDistributable" '{"tool_input":{"command":"./gradlew :app-desktop:createDistributable"}}' 2 '禁止直接调用'
  expect_hook_case "hook 阻止注释伪装" '{"tool_input":{"command":"./gradlew createDistributable # build-desktop.sh"}}' 2 '禁止直接调用'
  expect_hook_case "hook 阻止 echo 伪装" '{"tool_input":{"command":"echo build-desktop.sh; ./gradlew createDistributable"}}' 2 '禁止直接调用'
  expect_hook_case "hook 阻止 build 脚本后追加 Gradle" '{"tool_input":{"command":"./scripts/build-desktop.sh && ./gradlew createDistributable"}}' 2 '禁止直接调用'
  expect_hook_case "hook 阻止 packageMsi" '{"tool_input":{"command":"./gradlew packageMsi"}}' 2 '禁止直接调用'
  expect_hook_case "hook 放行只读搜索" '{"tool_input":{"command":"rg createDistributable scripts/"}}' 0 ''
  expect_hook_case "hook 放行真实 build-desktop.sh" '{"tool_input":{"command":"./scripts/build-desktop.sh test-only"}}' 0 ''
  expect_hook_case "hook 对畸形 JSON fail-open" '{malformed-json' 0 ''
fi

if [ -z "$PYTHON_BIN" ]; then
  fail "可用 Python 解释器" "hook/config 集成测试需要 python3 或 python"
else
  hooks_output="$($PYTHON_BIN - "$HOOKS_JSON" "$REPO_ROOT" <<'PY'
import json
import pathlib
import sys

hooks_file = pathlib.Path(sys.argv[1])
repo_root = pathlib.Path(sys.argv[2])
data = json.loads(hooks_file.read_text(encoding="utf-8"))
command = data["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
expected = "bash -lc 'exec bash \"$(git rev-parse --show-toplevel)/.codex/hooks/enforce-build-script.sh\"'"
if command != expected:
    raise SystemExit(f"hook command mismatch: {command!r}")
target = repo_root / ".codex/hooks/enforce-build-script.sh"
if not target.is_file():
    raise SystemExit(f"hook target missing: {target}")
target.read_text(encoding="utf-8")
print("relative hook target exists and is UTF-8")
PY
  2>&1)"
  hooks_status=$?
  if [ "$hooks_status" -eq 0 ]; then
    pass "hooks.json 从任意子目录定位 git root 且目标为 UTF-8"
  else
    fail "hooks.json 从任意子目录定位 git root 且目标为 UTF-8" "$hooks_output"
  fi
fi

printf '\nRESULT: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
  exit 1
fi
