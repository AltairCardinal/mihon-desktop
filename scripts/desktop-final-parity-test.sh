#!/usr/bin/env bash

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXED_EXE_RELATIVE="app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe"
FIXED_EXE="${MIHON_FINAL_PARITY_EXE:-$REPO_ROOT/$FIXED_EXE_RELATIVE}"
UNPACKED_APP="$(dirname "$FIXED_EXE")"
PROVENANCE="${MIHON_FINAL_PARITY_PROVENANCE:-$UNPACKED_APP.task151-provenance.json}"
PROVENANCE_TOOL="$REPO_ROOT/scripts/task15-build-provenance.py"
CLIENT="$REPO_ROOT/test-desktop/src/main/python/mihon_desktop_final_parity_client.py"
INVENTORY="${MIHON_FINAL_PARITY_INVENTORY:-$REPO_ROOT/app-desktop/src/test/resources/parity/test-mode-coverage-inventory.json}"
PORT="${MIHON_FINAL_PARITY_PORT:-8080}"
STARTUP_TIMEOUT_SECONDS="${MIHON_FINAL_PARITY_STARTUP_TIMEOUT_SECONDS:-30}"
POLL_INTERVAL_SECONDS="${MIHON_FINAL_PARITY_POLL_INTERVAL_SECONDS:-0.25}"
PYTHON="${MIHON_PYTHON:-}"
APP_PID=""

if [[ -z "$PYTHON" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    PYTHON="python3"
  elif command -v python >/dev/null 2>&1; then
    PYTHON="python"
  else
    echo "Python 3 is required to validate final parity results. Set MIHON_PYTHON." >&2
    exit 2
  fi
fi

RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mihon-final-parity.XXXXXX")"
APP_LOG="$RUN_DIR/app.log"
SUMMARY_FILE="$RUN_DIR/final-parity-summary.json"

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  if [[ -n "$APP_PID" ]]; then
    if kill -0 "$APP_PID" 2>/dev/null; then
      kill "$APP_PID" 2>/dev/null || true
    fi
    wait "$APP_PID" 2>/dev/null || true
  fi
  rm -rf -- "$RUN_DIR"
  exit "$exit_code"
}
trap cleanup EXIT INT TERM

fail_artifact() {
  echo "$1" >&2
  echo "Build and seal the canonical unpacked application first: ./scripts/build-desktop.sh evidence" >&2
  exit 2
}

if [[ ! -f "$FIXED_EXE" ]]; then
  fail_artifact "Fixed unpacked EXE is missing: $FIXED_EXE"
fi

if [[ ! -f "$PROVENANCE" ]]; then
  echo "Trusted build provenance is missing: $PROVENANCE" >&2
  echo "Rebuild and seal the canonical artifact: ./scripts/build-desktop.sh evidence" >&2
  exit 2
fi
if [[ -n "${MIHON_FINAL_PARITY_PROVENANCE_COMMAND:-}" ]]; then
  if ! eval "$MIHON_FINAL_PARITY_PROVENANCE_COMMAND" >/dev/null 2>&1; then
    fail_artifact "Trusted build provenance rejected the fixed unpacked application: $PROVENANCE"
  fi
elif ! "$PYTHON" "$PROVENANCE_TOOL" verify \
  --repo "$REPO_ROOT" \
  --artifact "$UNPACKED_APP" \
  --provenance "$PROVENANCE"; then
  fail_artifact "Trusted build provenance rejected the fixed unpacked application: $PROVENANCE"
fi

if [[ ! -f "$INVENTORY" ]]; then
  echo "Test Mode coverage inventory is missing: $INVENTORY" >&2
  exit 3
fi

health_check() {
  if [[ -n "${MIHON_FINAL_PARITY_HEALTH_COMMAND:-}" ]]; then
    eval "$MIHON_FINAL_PARITY_HEALTH_COMMAND" >/dev/null 2>&1
  else
    curl --fail --silent --show-error --max-time 1 \
      "http://127.0.0.1:$PORT/test/health" >/dev/null 2>&1
  fi
}

if health_check; then
  echo "Test Mode health is already responding before launch on port $PORT." >&2
  echo "Stop the old Mihon Desktop Test Mode instance or choose an unused MIHON_FINAL_PARITY_PORT." >&2
  exit 4
fi

echo "Starting fixed unpacked Mihon Desktop in headless Test Mode"
echo "EXE: $FIXED_EXE"
"$FIXED_EXE" \
  --test-mode \
  "--test-http-port=$PORT" \
  --headless \
  >"$APP_LOG" 2>&1 &
APP_PID=$!

deadline=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
while true; do
  if health_check; then
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      echo "Test Mode health responded but launched process is not alive (pid=$APP_PID, port=$PORT)." >&2
      echo "Another process may own the endpoint; stop it and retry." >&2
      [[ -s "$APP_LOG" ]] && tail -n 40 "$APP_LOG" >&2
      exit 4
    fi
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "Mihon Desktop exited before Test Mode health became ready (pid=$APP_PID)." >&2
    [[ -s "$APP_LOG" ]] && tail -n 40 "$APP_LOG" >&2
    exit 4
  fi
  if (( SECONDS >= deadline )); then
    echo "Timed out waiting for Test Mode health on port $PORT after ${STARTUP_TIMEOUT_SECONDS}s." >&2
    echo "Increase MIHON_FINAL_PARITY_STARTUP_TIMEOUT_SECONDS or inspect startup log: $APP_LOG" >&2
    [[ -s "$APP_LOG" ]] && tail -n 40 "$APP_LOG" >&2
    exit 4
  fi
  sleep "$POLL_INTERVAL_SECONDS"
done
echo "Test Mode health ready (pid=$APP_PID, port=$PORT)"

export MIHON_FINAL_PARITY_SUMMARY_FILE="$SUMMARY_FILE"
echo "Running final parity client scenarios"
if [[ -n "${MIHON_FINAL_PARITY_TEST_COMMAND:-}" ]]; then
  if ! (
    cd "$REPO_ROOT"
    eval "$MIHON_FINAL_PARITY_TEST_COMMAND"
  ); then
    echo "Final parity client command failed: $MIHON_FINAL_PARITY_TEST_COMMAND" >&2
    exit 5
  fi
elif ! "$PYTHON" "$CLIENT" --inventory "$INVENTORY" --output "$SUMMARY_FILE"; then
  echo "Final parity client command failed: $CLIENT" >&2
  exit 5
fi

if [[ ! -s "$SUMMARY_FILE" ]]; then
  echo "Final parity summary is missing: $SUMMARY_FILE" >&2
  echo "The test client must write MIHON_FINAL_PARITY_SUMMARY_FILE." >&2
  exit 3
fi

"$PYTHON" - "$INVENTORY" "$SUMMARY_FILE" <<'PY'
import json
import sys

inventory_path, summary_path = sys.argv[1:]

def reject(message):
    print(f"Final parity summary schema is invalid: {message}", file=sys.stderr)
    raise SystemExit(3)

def load_object(path, label):
    try:
        with open(path, encoding="utf-8") as handle:
            value = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        reject(f"{label} is unreadable: {error}")
    if type(value) is not dict:
        reject(f"{label} root must be an object")
    return value

def require_result_array(summary, field):
    value = summary.get(field)
    if type(value) is not list:
        reject(f"{field} must be an array")
    results = []
    for index, entry in enumerate(value):
        if type(entry) is not dict or set(entry) != {"id", "status", "detail"}:
            reject(f"{field}[{index}] must contain exactly id, status, and detail")
        result_id = entry["id"]
        status = entry["status"]
        detail = entry["detail"]
        if type(result_id) is not str or not result_id:
            reject(f"{field}[{index}].id must be a non-empty string")
        if type(status) is not str or status not in {"PASS", "FAIL"}:
            reject(f"{field}[{index}].status must be PASS or FAIL")
        if type(detail) is not str or not detail:
            reject(f"{field}[{index}].detail must be a non-empty string")
        results.append(entry)
    return results

inventory = load_object(inventory_path, "coverage inventory")
summary = load_object(summary_path, "summary")
if set(summary) != {"families", "permanentProtections", "mappedCapabilityIds"}:
    reject("root must contain exactly families, permanentProtections, and mappedCapabilityIds")
families = require_result_array(summary, "families")
protections = require_result_array(summary, "permanentProtections")
mapped_capabilities = summary["mappedCapabilityIds"]
if type(mapped_capabilities) is not list or not all(type(value) is int for value in mapped_capabilities):
    reject("mappedCapabilityIds must be an integer array")

try:
    expected_families = [entry["family"] for entry in inventory["scenarios"]]
    expected_protections = [entry["id"] for entry in inventory["permanentProtections"]]
    mapped_entries = inventory["scenarios"] + inventory["boundaries"]
    expected_capabilities = sorted({capability for entry in mapped_entries for capability in entry["capabilityIds"]})
except (KeyError, TypeError, ValueError) as error:
    reject(f"coverage inventory shape is invalid: {error}")

family_ids = [entry["id"] for entry in families]
protection_ids = [entry["id"] for entry in protections]
actual_capabilities = sorted(set(mapped_capabilities))
unmapped = sorted(set(expected_capabilities) - set(actual_capabilities))
unknown = sorted(set(actual_capabilities) - set(expected_capabilities))

for entry in families:
    print(f"Family {entry['id']}: {entry['status']} - {entry['detail']}")
for entry in protections:
    print(f"Protection {entry['id']}: {entry['status']} - {entry['detail']}")
print(f"Families: {len(set(family_ids))}/{len(expected_families)}")
print(f"Permanent protections: {len(set(protection_ids))}/{len(expected_protections)}")
print(f"Capabilities: {len(set(actual_capabilities))}/{len(expected_capabilities)} unmapped={len(unmapped)}")

problems = []
if len(expected_families) != 13 or set(family_ids) != set(expected_families) or len(family_ids) != len(set(family_ids)):
    problems.append("family results do not match the 13-family inventory")
if len(expected_protections) != 5 or set(protection_ids) != set(expected_protections) or len(protection_ids) != len(set(protection_ids)):
    problems.append("protection results do not match the 5 permanent protections")
if len(expected_capabilities) != 64 or unmapped or unknown or len(mapped_capabilities) != len(set(mapped_capabilities)):
    problems.append(f"capability mapping differs: unmapped={unmapped}, unknown={unknown}")
if problems:
    print("Final parity summary is incomplete: " + "; ".join(problems), file=sys.stderr)
    raise SystemExit(3)

failed = [
    f"{kind} {entry['id']}"
    for kind, entries in (("family", families), ("protection", protections))
    for entry in entries
    if entry["status"] != "PASS"
]
if failed:
    print("Final parity runtime checks failed: " + ", ".join(failed), file=sys.stderr)
    raise SystemExit(6)
PY
summary_exit=$?
if (( summary_exit != 0 )); then
  exit "$summary_exit"
fi

echo "Final desktop parity Test Mode verification passed."
