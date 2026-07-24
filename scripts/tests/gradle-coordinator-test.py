from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
COORDINATOR = REPO_ROOT / "scripts" / "gradle-coordinator.py"


class GradleCoordinatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.state_dir = Path(self.temp_dir.name)
        self.counter = self.state_dir / "counter.txt"
        self.key = "slow-test"

    def tearDown(self) -> None:
        if COORDINATOR.exists():
            self.run_command("stop")
        self.temp_dir.cleanup()

    def run_command(
        self,
        action: str,
        *extra: str,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(COORDINATOR),
                action,
                "--state-dir",
                str(self.state_dir),
                "--key",
                self.key,
                *extra,
            ],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_timeout_keeps_original_process_and_second_start_attaches(self) -> None:
        worker_code = (
            "from pathlib import Path; import sys,time; "
            "p=Path(sys.argv[1]); "
            "p.write_text(str(int(p.read_text())+1) if p.exists() else '1'); "
            "print('slow-started', flush=True); time.sleep(0.8); print('slow-finished')"
        )
        command = [sys.executable, "-c", worker_code, str(self.counter)]

        first = self.run_command("start", "--", *command)
        self.assertEqual(0, first.returncode, first.stderr)
        short_wait = self.run_command("wait", "--timeout-seconds", "0.05")
        self.assertEqual(124, short_wait.returncode)
        self.assertIn("RUNNING", short_wait.stdout)

        second = self.run_command("start", "--", *command)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertIn("ATTACHED", second.stdout)

        final_wait = self.run_command("wait", "--timeout-seconds", "5")
        self.assertEqual(0, final_wait.returncode, final_wait.stderr)
        self.assertIn("PASSED", final_wait.stdout)
        self.assertEqual("1", self.counter.read_text())

        state = json.loads((self.state_dir / f"{self.key}.json").read_text())
        self.assertEqual("PASSED", state["status"])
        self.assertEqual(0, state["exitCode"])
        self.assertIn("slow-finished", (self.state_dir / f"{self.key}.log").read_text())

    def test_failed_process_persists_exit_code(self) -> None:
        started = self.run_command(
            "start",
            "--",
            sys.executable,
            "-c",
            "import sys; print('expected-failure'); sys.exit(7)",
        )
        self.assertEqual(0, started.returncode, started.stderr)
        waited = self.run_command("wait", "--timeout-seconds", "5")
        self.assertEqual(7, waited.returncode)
        self.assertIn("FAILED", waited.stdout)

    def test_run_starts_and_waits_for_one_managed_process(self) -> None:
        completed = self.run_command(
            "run",
            "--timeout-seconds",
            "5",
            "--",
            sys.executable,
            "-c",
            "print('run-finished')",
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("STARTED", completed.stdout)
        self.assertIn("PASSED", completed.stdout)
        self.assertIn("run-finished", (self.state_dir / f"{self.key}.log").read_text())


if __name__ == "__main__":
    unittest.main()
