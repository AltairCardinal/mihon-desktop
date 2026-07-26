#!/usr/bin/env bash
set -euo pipefail

CASE=""
EVIDENCE_DIR=""
APP_BUNDLE=""
INSTALLER_ARTIFACT=""
INSTALLER_PROVENANCE=""
TRUSTED_TEAM_ID=""
CONFIRM_INSTALLER_HANDOFF=false
PORT=18151
TIMEOUT_SECONDS=90
COLD_REVIEW_FILE=""
REVIEW_FILE=""
LIST_CASES=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --list-cases) LIST_CASES=true; shift ;;
    --case) CASE="$2"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR="$2"; shift 2 ;;
    --app-bundle) APP_BUNDLE="$2"; shift 2 ;;
    --artifact) INSTALLER_ARTIFACT="$2"; shift 2 ;;
    --installer-provenance) INSTALLER_PROVENANCE="$2"; shift 2 ;;
    --trusted-team-id) TRUSTED_TEAM_ID="$2"; shift 2 ;;
    --confirm-installer-handoff) CONFIRM_INSTALLER_HANDOFF=true; shift ;;
    --port) PORT="$2"; shift 2 ;;
    --timeout-seconds) TIMEOUT_SECONDS="$2"; shift 2 ;;
    --cold-review-file) COLD_REVIEW_FILE="$2"; shift 2 ;;
    --review-file) REVIEW_FILE="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 64 ;;
  esac
done
if [[ "$LIST_CASES" == "true" ]]; then
  printf '%s\n' uri-cold uri-running host-share credential-roundtrip capture installer-handoff
  exit 0
fi
case "$CASE" in
  uri-cold|uri-running|host-share|credential-roundtrip|capture|installer-handoff) ;;
  *) echo "--case is required" >&2; exit 64 ;;
esac
[[ -n "$EVIDENCE_DIR" ]] || { echo "--evidence-dir is required" >&2; exit 64; }

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST_OS="$(uname -s)"
case "$HOST_OS" in
  Darwin)
    OS_ID="macos"
    APP_BUNDLE="${APP_BUNDLE:-/Applications/Mihon Desktop.app}"
    EXECUTABLE="$APP_BUNDLE/Contents/MacOS/Mihon Desktop"
    INFO_PLIST="$APP_BUNDLE/Contents/Info.plist"
    ;;
  Linux*)
    OS_ID="linux"
    APP_BUNDLE="${APP_BUNDLE:-$REPO_ROOT/app-desktop/tmp/mihon-dist/main/app/Mihon Desktop}"
    EXECUTABLE="$APP_BUNDLE/bin/Mihon Desktop"
    INFO_PLIST=""
    ;;
  *)
    echo "Unsupported Unix host: $HOST_OS" >&2
    exit 64
    ;;
esac
if [[ "$EVIDENCE_DIR" != /* ]]; then EVIDENCE_DIR="$REPO_ROOT/$EVIDENCE_DIR"; fi
mkdir -p "$EVIDENCE_DIR"
RESULT_PATH="$EVIDENCE_DIR/$CASE.json"
DETAIL_PATH="$EVIDENCE_DIR/.$CASE-result.json"
META_PATH="$EVIDENCE_DIR/.$CASE-meta.json"
ERROR_PATH="$EVIDENCE_DIR/.$CASE-error.txt"
ACCEPTANCE_HEADER="X-Mihon-Platform-Acceptance-Token"
STARTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
OWNED_PIDS=()
OWNED_APP_ACTIVE=false

if [[ "$OS_ID" == "linux" ]]; then
  missing=()
  command -v java >/dev/null 2>&1 || missing+=(java)
  if [[ "$CASE" == "credential-roundtrip" ]]; then
    [[ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ]] || missing+=(dbus-session)
    command -v secret-tool >/dev/null 2>&1 || missing+=(secret-tool)
    command -v dbus-send >/dev/null 2>&1 || missing+=(dbus-send)
  fi
  if [[ "$CASE" == "capture" ]]; then
    [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]] || missing+=(gui-session)
    command -v xdotool >/dev/null 2>&1 || missing+=(xdotool)
    command -v import >/dev/null 2>&1 || missing+=(imagemagick-import)
  fi
  if ((${#missing[@]})); then
    python3 - "$RESULT_PATH" "$CASE" "${missing[*]}" <<'PY'
import json, pathlib, sys
path, case, missing = sys.argv[1:]
pathlib.Path(path).write_text(json.dumps({
    "schemaVersion": 1, "os": "linux", "case": case,
    "result": {"status": "BLOCKED", "missingPrerequisites": missing.split()},
    "error": "Linux GUI, DBus, Secret Service, Java, and window tooling are required",
}, indent=2) + "\n")
PY
    cat "$RESULT_PATH"
    exit 1
  fi
fi
if [[ ! -x "$EXECUTABLE" && "$CASE" == "installer-handoff" ]]; then
  python3 - "$RESULT_PATH" "$OS_ID" "$EXECUTABLE" <<'PY'
import json, pathlib, sys
path, os_id, executable = sys.argv[1:]
pathlib.Path(path).write_text(json.dumps({
    "schemaVersion": 1, "os": os_id, "case": "installer-handoff",
    "result": {
        "status": "BLOCKED",
        "blockers": ["PackagedApplicationMissing", "CanonicalSignedArtifactMissing"],
    },
    "error": f"Packaged executable not found: {executable}",
}, indent=2) + "\n")
PY
  cat "$RESULT_PATH"
  exit 1
fi
[[ -x "$EXECUTABLE" ]] || { echo "Packaged executable not found: $EXECUTABLE" >&2; exit 66; }

python3 - "$REPO_ROOT" "$APP_BUNDLE" "$EXECUTABLE" "$INFO_PLIST" "$META_PATH" <<'PY'
import hashlib, json, os, pathlib, subprocess, sys
root, bundle, executable, plist, output = map(pathlib.Path, sys.argv[1:])

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
bundle_version = short_version = None
if plist.is_file():
    bundle_version = subprocess.check_output(
        ["/usr/libexec/PlistBuddy", "-c", "Print :CFBundleVersion", str(plist)], text=True
    ).strip()
    short_version = subprocess.check_output(
        ["/usr/libexec/PlistBuddy", "-c", "Print :CFBundleShortVersionString", str(plist)], text=True
    ).strip()
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
        "bundleVersion": bundle_version,
        "shortVersion": short_version,
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
  if [[ "$OS_ID" == "macos" ]]; then
    open -na "$APP_BUNDLE" --args "${args[@]}" || return 1
  else
    "$EXECUTABLE" "${args[@]}" >"$EVIDENCE_DIR/.task152-linux-app.log" 2>&1 &
  fi
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

new_platform_probe() {
  PROBE_SOURCE="$EVIDENCE_DIR/.Task152PlatformProbe.java"
  python3 "$REPO_ROOT/scripts/task15-build-provenance.py" write-probe --output "$PROBE_SOURCE" >/dev/null
  if [[ "$OS_ID" == "macos" ]]; then
    PROBE_CLASSPATH="$APP_BUNDLE/Contents/app/*"
  else
    PROBE_CLASSPATH="$APP_BUNDLE/lib/*"
  fi
}

run_platform_probe() {
  local output status
  set +e
  output="$(java --class-path "$PROBE_CLASSPATH" "$PROBE_SOURCE" "$@" 2>&1)"
  status=$?
  set -e
  printf '%s\n' "$output" | awk '/^\{/{line=$0} END{print line}'
  return "$status"
}

set_secure_screen_preference() {
  local value="$1" output
  output="$(run_platform_probe preference set "$value")" || return 1
  python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "PASS"' <<<"$output"
}

restore_secure_screen_preference() {
  local original="$1" output
  if [[ "$original" == "__MISSING__" ]]; then
    output="$(run_platform_probe preference delete)" || return 1
  else
    output="$(run_platform_probe preference set "$original")" || return 1
  fi
  python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "PASS"' <<<"$output"
}

run_credential_roundtrip() {
  if [[ "$OS_ID" == "macos" ]]; then
    command -v security >/dev/null 2>&1 || { echo "macOS security command is unavailable" >&2; return 1; }
  else
    dbus-send --session --dest=org.freedesktop.secrets --type=method_call \
      /org/freedesktop/secrets org.freedesktop.DBus.Peer.Ping >/dev/null || return 1
  fi
  new_platform_probe || return 1
  local result
  result="$(run_platform_probe credential "mihon.task152.$(uuidgen | tr -d -)")" || return 1
  run_policy credential "$result" >/dev/null || return 1
  printf '%s\n' "$result" >"$DETAIL_PATH"
}

run_capture() {
  new_platform_probe || return 1
  local original adapter capability first_pid second_pid handle first_shot second_shot feedback result
  original="$(run_platform_probe preference get)" || return 1
  original="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["value"])' <<<"$original")" || return 1
  trap 'stop_owned_app || true; restore_secure_screen_preference "$original" || true; rm -f "$PROBE_SOURCE"' RETURN

  adapter="$(run_platform_probe privacy)" || return 1
  capability="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["queryResult"])' <<<"$adapter")" || return 1
  set_secure_screen_preference ALWAYS || return 1
  start_test_app "$PORT" || return 1
  first_pid="${OWNED_PIDS[0]}"
  handle="$(mihon_window_handle "$first_pid")" || return 1
  [[ "$handle" =~ ^[0-9]+$ && "$handle" -gt 0 ]] || return 1
  first_shot="$(capture_native_window "$first_pid" "task152-$OS_ID-capture-protected")" || return 1
  stop_owned_app || return 1

  set_secure_screen_preference NEVER || return 1
  start_test_app "$((PORT + 1))" || return 1
  second_pid="${OWNED_PIDS[0]}"
  curl -fsS --max-time 10 -X POST \
    "http://127.0.0.1:$((PORT + 1))/test/navigate/SecuritySettingsScreen" >/dev/null || return 1
  sleep 0.5
  second_shot="$(capture_native_window "$second_pid" "task152-$OS_ID-capture-cleared")" || return 1
  feedback="$(capture_test_screenshot "$((PORT + 1))" "task152-$OS_ID-window-privacy-feedback")" || return 1
  result="$(python3 - "$OS_ID" "$handle" "$adapter" "$capability" "$first_shot" "$second_shot" "$feedback" <<'PY'
import hashlib, json, pathlib, sys
os_id, handle, adapter, capability, first, second, feedback = sys.argv[1:]
digest = lambda value: hashlib.sha256(pathlib.Path(value).read_bytes()).hexdigest()
print(json.dumps({
    "status": "PENDING_REVIEW", "os": os_id, "capability": capability,
    "windowHandle": int(handle), "adapter": json.loads(adapter),
    "screenshots": [
        {"role": "protected", "path": first, "sha256": digest(first)},
        {"role": "clear", "path": second, "sha256": digest(second)},
        {
            "role": "feedback",
            "path": json.loads(feedback)["path"],
            "sha256": digest(json.loads(feedback)["path"]),
        },
    ],
    "reviewRequired": "Review exact screenshot paths and hashes before declaring observations.",
}))
PY
)" || return 1
  run_policy capture "$result" >/dev/null || return 1
  printf '%s\n' "$result" >"$DETAIL_PATH"
  stop_owned_app
  restore_secure_screen_preference "$original"
  rm -f "$PROBE_SOURCE"
  trap - RETURN
  return 1
}

installer_result_passed() {
  [[ "$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' <<<"$1")" == "PASS" ]]
}

run_installer_handoff() {
  new_platform_probe || return 1
  local path production_path name release_tag arch sha256 size production signature blockers result sidecar provenance
  blockers=""
  if [[ -n "$INSTALLER_ARTIFACT" ]]; then
    if [[ "$INSTALLER_ARTIFACT" != /* ]]; then INSTALLER_ARTIFACT="$PWD/$INSTALLER_ARTIFACT"; fi
    path="$INSTALLER_ARTIFACT"
  elif [[ "$OS_ID" == "macos" ]]; then
    path="$EVIDENCE_DIR/mihon-desktop-macos-arm64-missing.dmg"
  else
    path="$EVIDENCE_DIR/mihon-desktop-linux-x86_64-missing.AppImage"
  fi
  name="$(basename "$path")"
  if [[ "$OS_ID" == "macos" && "$name" =~ ^mihon-desktop-macos-(x86_64|arm64)-([^[:space:]]+)\.dmg$ ]]; then
    arch="${BASH_REMATCH[1]}"
    release_tag="${BASH_REMATCH[2]}"
  elif [[ "$OS_ID" == "linux" ]]; then
    arch="x86_64"
    release_tag="missing"
  else
    arch="arm64"
    release_tag="invalid"
    blockers="CanonicalArtifactNameMismatch"
  fi
  if [[ "$release_tag" == "invalid" ]]; then
    production_path="$EVIDENCE_DIR/mihon-desktop-macos-$arch-invalid.dmg"
  else
    production_path="$path"
  fi
  if [[ -f "$path" ]]; then
    read -r sha256 size < <(python3 - "$path" <<'PY'
import hashlib, pathlib, sys
path = pathlib.Path(sys.argv[1])
print(hashlib.sha256(path.read_bytes()).hexdigest(), path.stat().st_size)
PY
)
  else
    sha256="$(printf '0%.0s' {1..64})"
    size=0
    blockers+="${blockers:+|}CanonicalSignedArtifactMissing"
  fi
  sidecar="${INSTALLER_PROVENANCE:-$path.task153-provenance.json}"
  provenance=""
  if [[ -f "$path" && -f "$sidecar" ]] &&
    python3 "$REPO_ROOT/scripts/task15-build-provenance.py" verify-installer \
      --repo "$REPO_ROOT" --artifact "$path" --canonical-name "$name" \
      --provenance "$sidecar" >/dev/null 2>&1; then
    provenance="$(python3 - "$REPO_ROOT" "$sidecar" <<'PY'
import json, sys
print(json.dumps({"repo": sys.argv[1], "sidecarPath": sys.argv[2]}))
PY
)"
  else
    blockers+="${blockers:+|}InstallerProvenanceMissingOrInvalid"
  fi

  if [[ "$OS_ID" == "macos" && -f "$path" ]]; then
    local codesign_status=0 spctl_status=0 details team_id
    /usr/bin/codesign --verify --deep --strict --verbose=2 "$path" >/dev/null 2>&1 ||
      codesign_status=$?
    /usr/sbin/spctl -a -vv -t install "$path" >/dev/null 2>&1 || spctl_status=$?
    details="$(/usr/bin/codesign -dv --verbose=4 "$path" 2>&1 || true)"
    team_id="$(sed -n 's/^TeamIdentifier=//p' <<<"$details" | head -n 1)"
    signature="$(python3 - "$codesign_status" "$spctl_status" "$team_id" <<'PY'
import json, sys
codesign, spctl, team = sys.argv[1:]
valid = codesign == "0" and spctl == "0" and len(team) == 10
print(json.dumps({
    "tool": "codesign+spctl", "status": "Valid" if valid else "Invalid",
    "teamId": team or None, "codesignExitCode": int(codesign), "spctlExitCode": int(spctl),
}))
PY
)"
    if [[ -z "$TRUSTED_TEAM_ID" ]]; then
      blockers+="${blockers:+|}IndependentTrustedTeamMissing"
    elif [[ "$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' <<<"$signature")" != "Valid" ||
      "$team_id" != "$TRUSTED_TEAM_ID" ]]; then
      blockers+="${blockers:+|}TrustedSignatureOrNotarizationUnavailable"
    fi
  else
    signature='{"tool":"NotApplicable","status":"NotApplicable"}'
    [[ "$OS_ID" == "linux" ]] || blockers+="${blockers:+|}TrustedSignatureOrNotarizationUnavailable"
  fi

  local allow_confirm=false
  [[ "$CONFIRM_INSTALLER_HANDOFF" == "true" && -z "$blockers" ]] && allow_confirm=true
  production="$(run_platform_probe installer "$production_path" "$release_tag" \
    "$(tr '[:lower:]' '[:upper:]' <<<"$OS_ID")" "$arch" "$sha256" "$size" \
    "$TRUSTED_TEAM_ID" "$allow_confirm")" || return 1
  local preparation
  preparation="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["preparationResult"])' <<<"$production")"
  if [[ "$preparation" != "ReadyToInstall" ]]; then
    blockers+="${blockers:+|}ProductionDefaultTrustReturned$preparation"
  elif [[ "$CONFIRM_INSTALLER_HANDOFF" != "true" ]]; then
    blockers+="${blockers:+|}ExplicitInstallerConfirmationRequired"
  fi
  result="$(python3 - "$OS_ID" "$blockers" "$release_tag" "$path" "$name" "$sha256" "$size" \
    "$signature" "$production" "$TRUSTED_TEAM_ID" "$provenance" <<'PY'
import json, pathlib, sys
os_id, blockers, tag, path, name, sha256, size, signature, production, trusted, provenance = sys.argv[1:]
artifact = None
if pathlib.Path(path).is_file():
    artifact = {"path": path, "name": name, "sha256": sha256, "size": int(size)}
production = json.loads(production)
passed = not blockers and production.get("cancellationResult") == "InstallCancelled" and production.get("launchResult") == "InstallHandedOff"
print(json.dumps({
    "status": "PASS" if passed else "BLOCKED", "os": os_id,
    "blockers": blockers.split("|") if blockers else [],
    "releaseTag": tag, "artifact": artifact, "signature": json.loads(signature),
    "trustedIdentity": trusted or None,
    "provenance": json.loads(provenance) if provenance else None,
    "production": production,
}))
PY
)" || return 1
  run_policy installer-handoff "$result" >/dev/null || return 1
  printf '%s\n' "$result" >"$DETAIL_PATH"
  rm -f "$PROBE_SOURCE"
  installer_result_passed "$result"
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
  if [[ "$OS_ID" == "linux" ]]; then
    local handle
    handle="$(xdotool search --onlyvisible --pid "$app_pid" | head -n 1)" || return 1
    xdotool getwindowgeometry --shell "$handle" | python3 -c '
import sys
values=dict(line.strip().split("=",1) for line in sys.stdin if "=" in line)
print(",".join(values[key] for key in ("X","Y","WIDTH","HEIGHT")))
'
    return
  fi
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

mihon_window_handle() {
  local app_pid="$1"
  if [[ "$OS_ID" == "linux" ]]; then
    xdotool search --onlyvisible --pid "$app_pid" | head -n 1
    return
  fi
  local script="$EVIDENCE_DIR/.task152-window.swift"
  cat >"$script" <<'SWIFT'
import CoreGraphics
import Foundation
let pid = Int32(CommandLine.arguments[1])!
let options: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
let rows = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] ?? []
for row in rows {
    if (row[kCGWindowOwnerPID as String] as? Int32) == pid,
       let number = row[kCGWindowNumber as String] as? Int {
        print(number)
        break
    }
}
SWIFT
  swift "$script" "$app_pid"
}

capture_native_window() {
  local app_pid="$1" name="$2" geometry handle path
  path="$EVIDENCE_DIR/$name.png"
  geometry="$(mihon_window_geometry "$app_pid")" || return 1
  if [[ "$OS_ID" == "macos" ]]; then
    /usr/sbin/screencapture -x -R"$geometry" "$path" || return 1
  else
    handle="$(mihon_window_handle "$app_pid")" || return 1
    import -window "$handle" "$path" || return 1
  fi
  [[ -s "$path" ]] || return 1
  printf '%s\n' "$path"
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

apply_capture_review() {
  [[ "$CASE" == "capture" ]] || { echo "--review-file is valid only for capture" >&2; return 64; }
  [[ -f "$RESULT_PATH" ]] || { echo "Run capture once before applying its review: $RESULT_PATH" >&2; return 66; }
  [[ -f "$REVIEW_FILE" ]] || { echo "Capture review file not found: $REVIEW_FILE" >&2; return 66; }
  python3 - "$RESULT_PATH" "$REVIEW_FILE" "$META_PATH" "$REPO_ROOT/scripts/task15-build-provenance.py" <<'PY'
import json, pathlib, subprocess, sys, tempfile
result_path, review_path, meta_path, helper = map(pathlib.Path, sys.argv[1:])
result = json.loads(result_path.read_text())
review = json.loads(review_path.read_text())
current = json.loads(meta_path.read_text())
if (
    result.get("taskBaseCommit") != current.get("taskBaseCommit")
    or result.get("taskBaseTree") != current.get("taskBaseTree")
    or result.get("productSource", {}).get("digest") != current.get("productSource", {}).get("digest")
    or result.get("artifact", {}).get("sha256") != current.get("artifact", {}).get("sha256")
):
    raise SystemExit("Capture evidence provenance no longer matches the committed build and artifact")
payload = {"runtime": result.get("result"), "review": review}
temporary = tempfile.NamedTemporaryFile(
    mode="w", encoding="utf-8", suffix=".json", dir=result_path.parent, delete=False,
)
try:
    with temporary:
        json.dump(payload, temporary)
    validated = json.loads(subprocess.check_output(
        [sys.executable, str(helper), "policy", "--kind", "capture-review", "--input", temporary.name],
        text=True,
    ))
finally:
    pathlib.Path(temporary.name).unlink(missing_ok=True)
result["result"]["status"] = validated["decision"]
result["result"]["review"] = validated["review"]
result["error"] = None
result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
raise SystemExit(0 if validated["decision"] == "PASS" else 1)
PY
}

if [[ -n "$COLD_REVIEW_FILE" ]]; then
  if [[ "$COLD_REVIEW_FILE" != /* ]]; then COLD_REVIEW_FILE="$PWD/$COLD_REVIEW_FILE"; fi
  apply_cold_review
  exit $?
fi
if [[ -n "$REVIEW_FILE" ]]; then
  if [[ "$REVIEW_FILE" != /* ]]; then REVIEW_FILE="$PWD/$REVIEW_FILE"; fi
  apply_capture_review
  exit $?
fi

rm -f "$DETAIL_PATH" "$ERROR_PATH"
exit_code=0
case "$CASE" in
  uri-cold) run_uri_cold >"$EVIDENCE_DIR/$CASE.raw.log" 2>&1 || exit_code=$? ;;
  uri-running) run_uri_running >"$EVIDENCE_DIR/$CASE.raw.log" 2>&1 || exit_code=$? ;;
  host-share) run_host_share >"$EVIDENCE_DIR/$CASE.raw.log" 2>&1 || exit_code=$? ;;
  credential-roundtrip) run_credential_roundtrip >"$EVIDENCE_DIR/$CASE.raw.log" 2>&1 || exit_code=$? ;;
  capture) run_capture >"$EVIDENCE_DIR/$CASE.raw.log" 2>&1 || exit_code=$? ;;
  installer-handoff) run_installer_handoff >"$EVIDENCE_DIR/$CASE.raw.log" 2>&1 || exit_code=$? ;;
esac
stop_owned_app || exit_code=1
if [[ ! -s "$DETAIL_PATH" ]]; then
  printf 'Task151 runner failed; see %s\n' "$EVIDENCE_DIR/$CASE.raw.log" >"$ERROR_PATH"
  write_detail "status=text=BLOCKED"
  exit_code=1
fi

FINISHED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
python3 - "$META_PATH" "$DETAIL_PATH" "$RESULT_PATH" "$CASE" "$STARTED_AT" "$FINISHED_AT" "$ERROR_PATH" "$OS_ID" <<'PY'
import json, pathlib, sys
meta_path, detail_path, result_path = map(pathlib.Path, sys.argv[1:4])
case, started, finished, error_path, os_id = sys.argv[4:]
data = json.loads(meta_path.read_text())
data.update({"schemaVersion": 1, "os": os_id, "case": case, "startedAtUtc": started, "finishedAtUtc": finished})
data["result"] = json.loads(detail_path.read_text())
error = pathlib.Path(error_path)
data["error"] = error.read_text() if error.exists() else None
result_path.write_text(json.dumps(data, ensure_ascii=False, indent=2))
PY
rm -f "$META_PATH" "$DETAIL_PATH" "$ERROR_PATH"
cat "$RESULT_PATH"
exit "$exit_code"
