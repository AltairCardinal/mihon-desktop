#!/usr/bin/env python3
from __future__ import annotations

import json
import sys


REQUIRED_FIELDS = ("status", "diff", "tests", "commit", "process", "next")
VALID_STATUSES = {"IMPLEMENTED", "REVIEWED", "BLOCKED"}
VALID_PROCESS_STATES = {"NONE", "RUNNING"}


def validate(receipt: object) -> list[str]:
    if not isinstance(receipt, dict):
        return ["receipt must be a JSON object"]

    errors: list[str] = []
    for field in REQUIRED_FIELDS:
        if field not in receipt:
            errors.append(f"missing required field: {field}")

    if errors:
        return errors

    if receipt["status"] not in VALID_STATUSES:
        errors.append(f"status must be one of {sorted(VALID_STATUSES)}")

    diff = receipt["diff"]
    if not isinstance(diff, list) or not all(isinstance(path, str) and path for path in diff):
        errors.append("diff must be a list of non-empty paths")

    tests = receipt["tests"]
    if not isinstance(tests, list) or not tests:
        errors.append("tests must be a non-empty list")
    elif not all(
        isinstance(test, dict)
        and isinstance(test.get("command"), str)
        and bool(test["command"])
        and isinstance(test.get("result"), str)
        and bool(test["result"])
        for test in tests
    ):
        errors.append("every test requires non-empty command and result")

    if not isinstance(receipt["commit"], str) or not receipt["commit"]:
        errors.append("commit must be a non-empty hash or UNCOMMITTED")

    process = receipt["process"]
    if not isinstance(process, dict) or process.get("state") not in VALID_PROCESS_STATES:
        errors.append(f"process.state must be one of {sorted(VALID_PROCESS_STATES)}")
    elif process["state"] == "RUNNING" and not isinstance(process.get("pid"), int):
        errors.append("running process requires integer pid")

    if not isinstance(receipt["next"], str) or not receipt["next"]:
        errors.append("next must be non-empty")

    return errors


def main() -> int:
    try:
        receipt = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        print(f"ERROR: invalid JSON: {exc}", file=sys.stderr)
        return 1

    errors = validate(receipt)
    if errors:
        print("\n".join(f"ERROR: {error}" for error in errors), file=sys.stderr)
        return 1
    print(f"PASS: valid {receipt['status']} agent handoff")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
