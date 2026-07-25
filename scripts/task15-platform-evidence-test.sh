#!/usr/bin/env bash
set -euo pipefail

CASE=""
EVIDENCE_DIR=""
APP_BUNDLE="/Applications/Mihon Desktop.app"
PORT=18151
TIMEOUT_SECONDS=90
COLD_REVIEW_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --case) CASE="$2"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR="$2"; shift 2 ;;
    --app-bundle) APP_BUNDLE="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --timeout-seconds) TIMEOUT_SECONDS="$2"; shift 2 ;;
    --cold-review-file) COLD_REVIEW_FILE="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 64 ;;
  esac
done
case "$CASE" in uri-cold|uri-running|host-share) ;; *) echo "--case is required" >&2; exit 64 ;; esac
[[ -n "$EVIDENCE_DIR" ]] || { echo "--evidence-dir is required" >&2; exit 64; }

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$EVIDENCE_DIR" != /* ]]; then EVIDENCE_DIR="$REPO_ROOT/$EVIDENCE_DIR"; fi
mkdir -p "$EVIDENCE_DIR"
EXECUTABLE="$APP_BUNDLE/Contents/MacOS/Mihon Desktop"
INFO_PLIST="$APP_BUNDLE/Contents/Info.plist"
RESULT_PATH="$EVIDENCE_DIR/$CASE.json"
DETAIL_PATH="$EVIDENCE_DIR/.$CASE-result.json"
META_PATH="$EVIDENCE_DIR/.$CASE-meta.json"
ERROR_PATH="$EVIDENCE_DIR/.$CASE-error.txt"
ACCEPTANCE_HEADER="X-Mihon-Platform-Acceptance-Token"
STARTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
OWNED_PIDS=()
OWNED_APP_ACTIVE=false

[[ -x "$EXECUTABLE" ]] || { echo "Packaged executable not found: $EXECUTABLE" >&2; exit 66; }

python3 - "$REPO_ROOT" "$EXECUTABLE" "$INFO_PLIST" "$META_PATH" <<'PY'
import hashlib, json, os, pathlib, subprocess, sys
root, executable, plist, output = map(pathlib.Path, sys.argv[1:])
bundle = executable.parents[2]

def product_input(path):
    value = path.as_posix()
    parts = value.split("/")
    if value.startswith(("docs/", "openspec/", "test-desktop/", "scripts/tests/", ".codex/")):
        return False
    if value in {"scripts/task15-platform-evidence-test.ps1", "scripts/task15-platform-evidence-test.sh"}:
        return False
    if "build" in parts:
        return False
    for index, part in enumerate(parts[:-1]):
        if part == "src" and index + 1 < len(parts) and "test" in parts[index + 1].lower():
            return False
    return True

def untracked_product_input(path):
    value = path.as_posix()
    if not product_input(path):
        return False
    parts = value.split("/")
    if "src" in parts:
        source_index = parts.index("src")
        if source_index + 1 < len(parts) and "test" not in parts[source_index + 1].lower():
            return True
    return (
        value.startswith(("buildSrc/", "gradle/"))
        or value in {"gradle.properties", "scripts/build-desktop.sh", "scripts/build-windows.ps1"}
        or pathlib.PurePosixPath(value).name in {
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
        }
    )

changed = subprocess.check_output(
    ["git", "-C", str(root), "diff", "--name-only", "HEAD", "--"], text=True
).splitlines()
changed_product = [value for value in changed if product_input(pathlib.PurePosixPath(value))]
if changed_product:
    raise SystemExit(f"Platform evidence requires committed product inputs; modified: {changed_product}")
untracked = subprocess.check_output(
    ["git", "-C", str(root), "ls-files", "--others", "--exclude-standard", "-z"]
).split(b"\0")
untracked_product = [
    os.fsdecode(value) for value in filter(None, untracked)
    if untracked_product_input(pathlib.PurePosixPath(os.fsdecode(value)))
]
if untracked_product:
    raise SystemExit(f"Platform evidence rejects untracked product inputs: {untracked_product}")

tracked = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"]).split(b"\0")
records = []
for raw in sorted(filter(None, tracked)):
    path = pathlib.PurePosixPath(os.fsdecode(raw))
    if not product_input(path):
        continue
    absolute = root.joinpath(*path.parts)
    if not absolute.is_file():
        raise SystemExit(f"Missing product input: {path}")
    mode = subprocess.check_output(
        ["git", "-C", str(root), "ls-files", "-s", "--", path.as_posix()],
        text=True,
    )[:6]
    byte_hash = hashlib.sha256(absolute.read_bytes()).hexdigest()
    records.append(f"{mode}\t{path.as_posix()}\t{byte_hash}\n")
canonical = "".join(records).encode()
artifact = executable.read_bytes()
source_commit = subprocess.check_output(
    ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
).strip()
source_tree = subprocess.check_output(
    ["git", "-C", str(root), "rev-parse", "HEAD^{tree}"], text=True
).strip()
product_source = {
    "algorithm": "mihon-production-input-v1:sha256(mode<TAB>relative-path<TAB>raw-byte-sha256<LF>)",
    "exclusions": [
        "docs/**", "openspec/**", "test-desktop/**", "scripts/tests/**",
        "scripts/task15-platform-evidence-test.{ps1,sh}", ".codex/**",
        "**/src/*Test/**", "**/src/*test/**", "**/build/**",
    ],
    "fileCount": len(records),
    "digest": hashlib.sha256(canonical).hexdigest(),
}
artifact_hash = hashlib.sha256(artifact).hexdigest()
provenance_path = pathlib.Path(f"{bundle}.task151-provenance.json")
if not provenance_path.is_file():
    raise SystemExit(f"Build provenance sidecar is missing: {provenance_path}")
provenance = json.loads(provenance_path.read_text())
verified = json.loads(subprocess.check_output(
    [
        sys.executable,
        str(root / "scripts/task15-build-provenance.py"),
        "verify",
        "--repo",
        str(root),
        "--require-version-allocation",
        "--artifact",
        str(bundle),
        "--provenance",
        str(provenance_path),
    ],
    text=True,
))
if verified != provenance:
    raise SystemExit("Shared provenance verifier returned a different sidecar")
expected = {
    "schemaVersion": 1,
    "sourceCommit": source_commit,
    "sourceTree": source_tree,
}
if any(provenance.get(key) != value for key, value in expected.items()):
    raise SystemExit("Artifact provenance source commit/tree mismatch")
if (
    provenance.get("productSource", {}).get("algorithm") != product_source["algorithm"]
    or provenance.get("productSource", {}).get("digest") != product_source["digest"]
    or provenance.get("productSource", {}).get("fileCount") != product_source["fileCount"]
):
    raise SystemExit("Artifact provenance does not match committed product inputs and executable")
data = {
    "taskBaseCommit": source_commit,
    "taskBaseTree": source_tree,
    "productSource": product_source,
    "artifact": {
        "path": str(bundle),
        "algorithm": provenance["artifact"]["algorithm"],
        "fileCount": provenance["artifact"]["fileCount"],
        "sha256": provenance["artifact"]["sha256"],
        "size": provenance["artifact"]["size"],
        "executablePath": str(executable),
        "executableSha256": artifact_hash,
        "modifiedUtc": executable.stat().st_mtime,
        "bundleVersion": subprocess.check_output(
            ["/usr/libexec/PlistBuddy", "-c", "Print :CFBundleVersion", str(plist)], text=True
        ).strip(),
        "shortVersion": subprocess.check_output(
            ["/usr/libexec/PlistBuddy", "-c", "Print :CFBundleShortVersionString", str(plist)], text=True
        ).strip(),
        "provenancePath": str(provenance_path),
        "provenanceSha256": hashlib.sha256(provenance_path.read_bytes()).hexdigest(),
    },
}
pathlib.Path(output).write_text(json.dumps(data, ensure_ascii=False, indent=2))
PY

app_pids() {
  ps -ww -axo pid=,command= | python3 -c '
import sys
exe=sys.argv[1]
for line in sys.stdin:
    fields=line.strip().split(None,1)
    if len(fields)==2 and (fields[1] == exe or fields[1].startswith(exe+" ")):
        print(fields[0])
' "$EXECUTABLE"
}

run_policy() {
  local kind="$1" payload="$2" input="$EVIDENCE_DIR/.task151-policy-$$-$RANDOM.json"
  local result status
  printf '%s' "$payload" >"$input"
  set +e
  result="$(python3 "$REPO_ROOT/scripts/task15-build-provenance.py" policy --kind "$kind" --input "$input")"
  status=$?
  set -e
  rm -f "$input"
  [[ $status -eq 0 ]] || return "$status"
  printf '%s\n' "$result"
}

pid_policy_payload() {
  local owned="$1" current="$2"
  python3 - "$owned" "$current" <<'PY'
import json, sys
values = lambda raw: [int(value) for value in raw.split("|") if value]
print(json.dumps({"owned": values(sys.argv[1]), "current": values(sys.argv[2])}))
PY
}

assert_no_existing_app() {
  local existing
  existing="$(app_pids | paste -sd'|' - || true)"
  run_policy "pid-empty" "$(python3 - "$existing" <<'PY'
import json, sys
print(json.dumps({"pids": [int(value) for value in sys.argv[1].split("|") if value]}))
PY
)" >/dev/null
}

register_owned_app() {
  local pids joined
  pids="$(app_pids)"
  joined="$(paste -sd'|' - <<<"$pids")"
  run_policy "pid-owned" "$(pid_policy_payload "$joined" "$joined")" >/dev/null || return 1
  OWNED_PIDS=($pids)
  OWNED_APP_ACTIVE=true
}

owned_app_pids() {
  local pid current
  current="$(app_pids || true)"
  for pid in "${OWNED_PIDS[@]}"; do
    grep -qx "$pid" <<<"$current" && printf '%s\n' "$pid"
  done
}

stop_owned_app() {
  local pids current cleanup
  [[ "$OWNED_APP_ACTIVE" == "true" ]] || return 0
  current="$(app_pids | paste -sd'|' - || true)"
  cleanup="$(run_policy "pid-cleanup" "$(pid_policy_payload "$(IFS='|'; echo "${OWNED_PIDS[*]}")" "$current")")" ||
    return 1
  pids="$(python3 -c 'import json,sys; print(" ".join(map(str,json.load(sys.stdin)["kill"])))' <<<"$cleanup")"
  [[ -z "$pids" ]] || kill $pids 2>/dev/null || true
  local deadline=$((SECONDS + 10))
  while [[ -n "$(owned_app_pids || true)" && $SECONDS -lt $deadline ]]; do sleep 0.2; done
  pids="$(owned_app_pids || true)"
  [[ -z "$pids" ]] || { kill -9 $pids 2>/dev/null || true; sleep 0.2; }
  [[ -z "$(owned_app_pids || true)" ]] || { echo "Owned Mihon process did not stop" >&2; return 1; }
  OWNED_PIDS=()
  OWNED_APP_ACTIVE=false
}

wait_health() {
  local port="$1" deadline=$((SECONDS + TIMEOUT_SECONDS))
  until curl -fsS --max-time 2 "http://127.0.0.1:$port/test/health" >/dev/null; do
    [[ $SECONDS -lt $deadline ]] || return 1
    sleep 0.25
  done
}

start_test_app() {
  local port="$1" token="${2:-}"
  assert_no_existing_app || return 1
  local args=(--test-mode "--test-http-port=$port" "--screenshot-dir=$EVIDENCE_DIR")
  [[ -z "$token" ]] || args+=("--platform-acceptance-token=$token")
  open -na "$APP_BUNDLE" --args "${args[@]}" || return 1
  wait_health "$port" || { register_owned_app || true; return 1; }
  register_owned_app
}

action_cursor() {
  curl -fsS --max-time 3 "http://127.0.0.1:$1/test/history" |
    python3 -c 'import json,sys; print(len(json.load(sys.stdin)))'
}

wait_parser_rejected() {
  local port="$1" cursor="$2" deadline=$((SECONDS + TIMEOUT_SECONDS)) history payload result status
  while [[ $SECONDS -lt $deadline ]]; do
    history="$(curl -fsS --max-time 3 "http://127.0.0.1:$port/test/history")"
    payload="$(python3 - "$cursor" "$history" <<'PY'
import json, sys
print(json.dumps({"cursor": int(sys.argv[1]), "history": json.loads(sys.argv[2])}))
PY
)" || return 1
    result="$(run_policy "terminal" "$payload")" || return 1
    status="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' <<<"$result")"
    if [[ "$status" == "VALID" ]]; then
      sleep 1
      history="$(curl -fsS --max-time 3 "http://127.0.0.1:$port/test/history")" || return 1
      payload="$(python3 - "$cursor" "$history" <<'PY'
import json, sys
print(json.dumps({"cursor": int(sys.argv[1]), "history": json.loads(sys.argv[2])}))
PY
)" || return 1
      result="$(run_policy "terminal" "$payload")" || return 1
      python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["record"],separators=(",",":")))' <<<"$result"
      return
    fi
    sleep 0.25
  done
  return 1
}

capture_test_screenshot() {
  local port="$1" name="$2"
  local response
  response="$(curl -fsS --max-time 15 -X POST -H "Content-Type: application/json" \
    -d "{\"name\":\"$name\"}" "http://127.0.0.1:$port/test/screenshot"
  )" || return 1
  run_policy "screenshot" "$response" |
    python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["screenshot"],separators=(",",":")))'
}

new_token() {
  /usr/bin/openssl rand -hex 32
}

write_detail() {
  python3 - "$DETAIL_PATH" "$@" <<'PY'
import json, pathlib, sys
out = pathlib.Path(sys.argv[1])
pairs = sys.argv[2:]
data = {}
for pair in pairs:
    key, kind, value = pair.split("=", 2)
    if kind == "json":
        data[key] = json.loads(value)
    elif kind == "list":
        data[key] = value.split("|") if value else []
    elif kind == "bool":
        data[key] = value.lower() == "true"
    else:
        data[key] = value
out.write_text(json.dumps(data, ensure_ascii=False, indent=2))
PY
}

choose_copy_service() {
  local share_pid="$1"
  local script="$EVIDENCE_DIR/.choose-copy.applescript"
  cat >"$script" <<APPLESCRIPT
tell application "System Events"
  set candidates to {"Copy", "Copy to Clipboard", "拷贝", "复制", "复制到剪贴板"}
  set processItem to first application process whose unix id is $share_pid
  set allItems to entire contents of processItem
  repeat with elementItem in allItems
    try
      set elementName to name of elementItem as text
      if candidates contains elementName then
        perform action "AXPress" of elementItem
        return "PRESSED:" & elementName
      end if
    end try
  end repeat
end tell
error "Copy sharing service is not accessible"
APPLESCRIPT
  /usr/bin/osascript "$script"
}

mihon_window_geometry() {
  local app_pid="$1"
  local script="$EVIDENCE_DIR/.mihon-window.applescript"
  cat >"$script" <<APPLESCRIPT
tell application "System Events"
  set mihonProcess to first application process whose unix id is $app_pid
  if (count of windows of mihonProcess) is 0 then error "Mihon window is missing"
  set frontmost of mihonProcess to true
  set windowPosition to position of window 1 of mihonProcess
  set windowSize to size of window 1 of mihonProcess
  return (item 1 of windowPosition as text) & "," & (item 2 of windowPosition as text) & "," & ¬
    (item 1 of windowSize as text) & "," & (item 2 of windowSize as text)
end tell
APPLESCRIPT
  /usr/bin/osascript "$script"
}

find_share_pid() {
  local parent_pids="$1"
  ps -ww -axo pid=,ppid=,command= | python3 -c '
import sys
parents=set(sys.argv[1].split("|"))
for line in sys.stdin:
    fields=line.strip().split(None,2)
    if len(fields)==3 and fields[1] in parents and fields[2].startswith("/usr/bin/osascript -l JavaScript"):
        print(fields[0])
' "$parent_pids" | head -n 1
}

run_uri_cold() {
  assert_no_existing_app || return 1
  local url_types uri pids screenshots="" geometry
  url_types="$(/usr/bin/plutil -extract CFBundleURLTypes json -o - "$INFO_PLIST")" || return 1
  grep -q '"tachiyomi"' <<<"$url_types" || { echo "Bundle URL type missing" >&2; return 1; }
  uri="tachiyomi://task151-invalid/cold?nonce=$(uuidgen | tr -d -)"
  open "$uri" || return 1
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  until [[ -n "$(app_pids || true)" ]]; do
    [[ $SECONDS -lt $deadline ]] || { echo "OS handler did not cold-launch app" >&2; return 1; }
    sleep 0.2
  done
  register_owned_app || return 1
  sleep 1
  pids="$(owned_app_pids | paste -sd'|' -)"
  geometry="$(mihon_window_geometry "${OWNED_PIDS[0]}")" || return 1
  for index in 1 2 3 4; do
    local shot="$EVIDENCE_DIR/task151-macos-uri-cold-$index.png"
    /usr/sbin/screencapture -x -R"$geometry" "$shot" || return 1
    screenshots+="${screenshots:+|}$shot"
    sleep 0.35
  done
  write_detail \
    "status=text=PENDING_REVIEW" "uri=text=$uri" "launchMechanism=text=macOS LaunchServices via open URI" \
    "bundleUrlTypes=json=$url_types" "processIds=list=$pids" "windowGeometry=text=$geometry" \
    "screenshots=list=$screenshots" "visualReview=text=Human visual review required; no test flags were passed to the OS handler."
  stop_owned_app
  return 1
}

run_uri_running() {
  assert_no_existing_app || return 1
  start_test_app "$PORT" || return 1
  local owner_pids after_pids action_after uri terminal state screenshot
  owner_pids="$(owned_app_pids | paste -sd'|' -)"
  [[ "$owner_pids" != *"|"* && -n "$owner_pids" ]] ||
    { echo "Expected exactly one owner process before running URI" >&2; return 1; }
  uri="tachiyomi://task151-invalid/running?nonce=$(uuidgen | tr -d -)"
  action_after="$(action_cursor "$PORT")" || return 1
  open "$uri" || return 1
  terminal="$(wait_parser_rejected "$PORT" "$action_after")" || return 1
  sleep 1
  after_pids="$(owned_app_pids | paste -sd'|' -)"
  run_policy "pid-owned" "$(pid_policy_payload "$owner_pids" "$(app_pids | paste -sd'|' -)")" >/dev/null ||
    return 1
  state="$(curl -fsS --max-time 3 "http://127.0.0.1:$PORT/test/state")" || return 1
  screenshot="$(capture_test_screenshot "$PORT" task151-macos-uri-running)" || return 1
  write_detail \
    "status=text=PASS" "uri=text=$uri" "launchMechanism=text=macOS LaunchServices via open URI" \
    "ownerProcessIds=list=$owner_pids" "remainingOwnerProcessIds=list=$after_pids" \
    "uniqueOwnerPreserved=bool=true" "actionCursor=text=$action_after" \
    "terminal=json=$terminal" "state=json=$state" "visibleFeedback=json=$screenshot"
  stop_owned_app
}

one_native_share() {
  local kind="$1"
  local port="$2"
  local response="$EVIDENCE_DIR/.host-share-$kind-response.json"
  local token
  local automation_file="$EVIDENCE_DIR/.host-share-$kind-automation.txt"
  token="$(new_token)" || return 1
  start_test_app "$port" "$token" || return 1
  curl -sS --max-time $((TIMEOUT_SECONDS + 40)) -X POST \
    -H "$ACCEPTANCE_HEADER: $token" -H "Content-Type: application/json" -d '{}' \
    "http://127.0.0.1:$port/test/platform-acceptance/share/$kind" >"$response" &
  local curl_pid=$!
  local parents share_pid="" deadline=$((SECONDS + 20))
  parents="$(owned_app_pids | paste -sd'|' -)"
  while [[ $SECONDS -lt $deadline && -z "$share_pid" ]]; do
    share_pid="$(find_share_pid "$parents")"
    [[ -n "$share_pid" ]] || sleep 0.2
  done
  if [[ -z "$share_pid" ]]; then
    echo "Share picker osascript child was not found for Mihon PID(s): $parents" >"$automation_file"
  elif ! choose_copy_service "$share_pid" >"$automation_file" 2>&1; then
    kill "$share_pid" 2>/dev/null || true
  fi
  if ! wait "$curl_pid"; then
    [[ -z "$share_pid" ]] || kill "$share_pid" 2>/dev/null || true
    stop_owned_app
    [[ ! -s "$response" ]] || cat "$response"
    return 1
  fi
  [[ -z "$share_pid" ]] || kill "$share_pid" 2>/dev/null || true
  stop_owned_app
  cat "$response"
}

run_host_share() {
  assert_no_existing_app || return 1
  local text file feedback
  text="$(one_native_share text "$PORT")" || return 1
  file="$(one_native_share file "$((PORT + 1))")" || return 1
  local status
  status="$(python3 - "$text" "$file" <<'PY'
import json,sys
items=[json.loads(sys.argv[1]),json.loads(sys.argv[2])]
print("PASS" if all(x.get("launchResult")=="OpenedNatively" and x.get("terminalResult")=="SharedNatively" for x in items) else "FAIL")
PY
)" || return 1
  feedback="$(python3 -c 'import json,sys; print("|".join(json.loads(x).get("terminalResult","") for x in sys.argv[1:]))' "$text" "$file")" ||
    return 1
  write_detail \
    "status=text=$status" "nativeShareExpected=bool=true" "text=json=$text" "file=json=$file" \
    "userFeedback=list=$feedback"
  [[ "$status" == "PASS" ]]
}

apply_cold_review() {
  [[ "$CASE" == "uri-cold" ]] || { echo "--cold-review-file is valid only for uri-cold" >&2; return 64; }
  [[ -f "$RESULT_PATH" ]] || { echo "Run uri-cold once before applying its review: $RESULT_PATH" >&2; return 66; }
  [[ -f "$COLD_REVIEW_FILE" ]] || { echo "Cold review file not found: $COLD_REVIEW_FILE" >&2; return 66; }
  python3 - "$RESULT_PATH" "$COLD_REVIEW_FILE" "$META_PATH" <<'PY'
import hashlib, json, pathlib, sys
result_path, review_path, meta_path = map(pathlib.Path, sys.argv[1:])
result = json.loads(result_path.read_text())
review = json.loads(review_path.read_text())
current = json.loads(meta_path.read_text())
if (
    result.get("taskBaseCommit") != current.get("taskBaseCommit")
    or result.get("taskBaseTree") != current.get("taskBaseTree")
    or result.get("productSource", {}).get("digest") != current.get("productSource", {}).get("digest")
    or result.get("artifact", {}).get("sha256") != current.get("artifact", {}).get("sha256")
):
    raise SystemExit("Cold evidence provenance no longer matches the committed build and artifact")
if result["result"].get("status") != "PENDING_REVIEW":
    raise SystemExit("Cold evidence is not pending review")
if review.get("case") != "uri-cold" or review.get("decision") not in {"PASS", "FAIL"}:
    raise SystemExit("Cold review must identify uri-cold and decision PASS or FAIL")
for field in ("visibleFeedback", "reviewer", "reviewedAtUtc"):
    if not review.get(field):
        raise SystemExit(f"Cold review requires {field}")
screenshots = [pathlib.Path(value).resolve() for value in result["result"].get("screenshots", [])]
reviewed = {pathlib.Path(item["path"]).resolve(): item["sha256"].lower() for item in review.get("screenshots", [])}
if len(screenshots) != len(reviewed):
    raise SystemExit("Cold review screenshot count mismatch")
for screenshot in screenshots:
    if screenshot not in reviewed:
        raise SystemExit(f"Cold review does not identify screenshot: {screenshot}")
    actual = hashlib.sha256(screenshot.read_bytes()).hexdigest()
    if actual != reviewed[screenshot]:
        raise SystemExit(f"Cold review screenshot hash mismatch: {screenshot}")
result["result"]["status"] = review["decision"]
result["result"]["visibleFeedbackReview"] = review
result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
raise SystemExit(0 if review["decision"] == "PASS" else 1)
PY
}

if [[ -n "$COLD_REVIEW_FILE" ]]; then
  if [[ "$COLD_REVIEW_FILE" != /* ]]; then COLD_REVIEW_FILE="$PWD/$COLD_REVIEW_FILE"; fi
  apply_cold_review
  exit $?
fi

rm -f "$DETAIL_PATH" "$ERROR_PATH"
exit_code=0
case "$CASE" in
  uri-cold) run_uri_cold >"$EVIDENCE_DIR/$CASE.raw.log" 2>&1 || exit_code=$? ;;
  uri-running) run_uri_running >"$EVIDENCE_DIR/$CASE.raw.log" 2>&1 || exit_code=$? ;;
  host-share) run_host_share >"$EVIDENCE_DIR/$CASE.raw.log" 2>&1 || exit_code=$? ;;
esac
stop_owned_app || exit_code=1
if [[ ! -s "$DETAIL_PATH" ]]; then
  printf 'Task151 runner failed; see %s\n' "$EVIDENCE_DIR/$CASE.raw.log" >"$ERROR_PATH"
  write_detail "status=text=BLOCKED"
  exit_code=1
fi

FINISHED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
python3 - "$META_PATH" "$DETAIL_PATH" "$RESULT_PATH" "$CASE" "$STARTED_AT" "$FINISHED_AT" "$ERROR_PATH" <<'PY'
import json, pathlib, sys
meta_path, detail_path, result_path = map(pathlib.Path, sys.argv[1:4])
case, started, finished, error_path = sys.argv[4:]
data = json.loads(meta_path.read_text())
data.update({"schemaVersion": 1, "os": "macos", "case": case, "startedAtUtc": started, "finishedAtUtc": finished})
data["result"] = json.loads(detail_path.read_text())
error = pathlib.Path(error_path)
data["error"] = error.read_text() if error.exists() else None
result_path.write_text(json.dumps(data, ensure_ascii=False, indent=2))
PY
rm -f "$META_PATH" "$DETAIL_PATH" "$ERROR_PATH"
cat "$RESULT_PATH"
exit "$exit_code"
