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

awk '
function error(task, message) {
  printf "ERROR: Task %s: %s\n", task, message > "/dev/stderr"
  errors++
}

function task_id(line, value) {
  value = line
  sub(/^.*Task[[:space:]]+/, "", value)
  sub(/[^0-9A-Za-z].*$/, "", value)
  return value
}

function metadata(line, task, field, value) {
  value = line
  sub("^\\*\\*" field ":\\*\\*[[:space:]]*", "", value)
  count[task, field]++
  data[task, field] = value
}

BEGIN {
  in_bodies = 0
  current = ""
  pending_count = 0
}

/^### Task[[:space:]]+[0-9]+[A-Za-z]?([^0-9A-Za-z]|$)/ {
  in_bodies = 1
  current = task_id($0)
  body_count[current]++
  next
}

!in_bodies && /^- \[[ xX]\] Task[[:space:]]+[0-9]+[A-Za-z]?([^0-9A-Za-z]|$)/ {
  id = task_id($0)
  overview_count[id]++
  if ($0 ~ /^- \[[xX]\]/) {
    completed[id] = 1
  } else {
    pending[id] = 1
    pending_count++
  }
  next
}

in_bodies && current != "" {
  if ($0 ~ /^\*\*Risk axis:\*\*/) {
    metadata($0, current, "Risk axis")
  } else if ($0 ~ /^\*\*Platform boundary:\*\*/) {
    metadata($0, current, "Platform boundary")
  } else if ($0 ~ /^\*\*Estimated scope:\*\*/) {
    metadata($0, current, "Estimated scope")
  } else if ($0 ~ /^\*\*Verification:\*\*/) {
    metadata($0, current, "Verification")
  } else if ($0 ~ /^\*\*Split waiver:\*\*/) {
    metadata($0, current, "Split waiver")
  }
}

END {
  if (pending_count == 0) {
    found_overview = 0
    for (id in overview_count) found_overview = 1
    if (!found_overview) {
      print "ERROR: no top-level Task overview found before Task bodies" > "/dev/stderr"
      errors++
    }
  }

  for (id in overview_count) {
    if (overview_count[id] != 1) error(id, "appears more than once in the top-level overview")
  }

  for (id in pending) {
    if (body_count[id] != 1) {
      error(id, "must map to exactly one Task body")
      continue
    }

    required[1] = "Risk axis"
    required[2] = "Platform boundary"
    required[3] = "Estimated scope"
    required[4] = "Verification"
    for (i = 1; i <= 4; i++) {
      field = required[i]
      if (count[id, field] != 1 || data[id, field] == "") {
        error(id, "requires exactly one non-empty **" field ":** field")
      }
    }

    risk = data[id, "Risk axis"]
    if (risk != "" && risk !~ /^[A-Za-z0-9][A-Za-z0-9_-]*$/) {
      error(id, "Risk axis 必须是单个非空 slug（只含字母、数字、_ 或 -，不得使用多值分隔符）")
    }

    boundary = data[id, "Platform boundary"]
    if (boundary != "" && boundary !~ /^(shared|android|desktop|shared\+android|shared\+desktop|verification|docs|tooling)$/) {
      error(id, "Platform boundary must be one allowed value; android+desktop is forbidden")
    }

    scope = data[id, "Estimated scope"]
    if (scope != "" && scope !~ /^[0-9]+ files, [0-9]+ lines$/) {
      error(id, "Estimated scope must use: N files, M lines")
    } else if (scope != "") {
      files = scope
      sub(/ files,.*$/, "", files)
      lines = scope
      sub(/^[0-9]+ files, /, "", lines)
      sub(/ lines$/, "", lines)
      if ((files + 0 > 8 || lines + 0 > 400) && (count[id, "Split waiver"] != 1 || data[id, "Split waiver"] == "")) {
        error(id, "scope exceeds 8 files or 400 lines and requires one non-empty **Split waiver:**")
      }
    }

    if (count[id, "Split waiver"] > 1) {
      error(id, "Split waiver may appear at most once")
    }
  }

  if (errors > 0) exit 1
  printf "PASS: 已检查 %d 个待办 Task 正文：%s\n", pending_count, FILENAME
}
' "$PLAN_FILE"
