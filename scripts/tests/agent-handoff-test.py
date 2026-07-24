from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = REPO_ROOT / "scripts" / "agent-handoff.py"


class AgentHandoffTest(unittest.TestCase):
    def validate(self, receipt: dict[str, object]) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VALIDATOR)],
            cwd=REPO_ROOT,
            input=json.dumps(receipt),
            capture_output=True,
            text=True,
            check=False,
        )

    def valid_receipt(self) -> dict[str, object]:
        return {
            "status": "IMPLEMENTED",
            "diff": ["app-desktop/src/main/kotlin/Example.kt"],
            "tests": [{"command": "./gradlew focusedTest", "result": "GREEN 3/3"}],
            "commit": "UNCOMMITTED",
            "process": {"state": "NONE"},
            "next": "independent review",
        }

    def test_valid_completion_receipt_passes(self) -> None:
        result = self.validate(self.valid_receipt())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("PASS", result.stdout)

    def test_every_required_field_is_enforced(self) -> None:
        for field in ("status", "diff", "tests", "commit", "process", "next"):
            with self.subTest(field=field):
                receipt = self.valid_receipt()
                receipt.pop(field)
                result = self.validate(receipt)
                self.assertEqual(1, result.returncode)
                self.assertIn(field, result.stderr)

    def test_invalid_status_and_running_process_without_pid_are_rejected(self) -> None:
        receipt = self.valid_receipt()
        receipt["status"] = "DONE"
        self.assertEqual(1, self.validate(receipt).returncode)

        receipt = self.valid_receipt()
        receipt["process"] = {"state": "RUNNING"}
        result = self.validate(receipt)
        self.assertEqual(1, result.returncode)
        self.assertIn("pid", result.stderr)


if __name__ == "__main__":
    unittest.main()
