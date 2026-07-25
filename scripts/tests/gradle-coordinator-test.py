from __future__ import annotations

import json
import os
import signal
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
            self.coordinator_command(action, *extra),
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

    def coordinator_command(self, action: str, *extra: str) -> list[str]:
        return [
            sys.executable,
            str(COORDINATOR),
            action,
            "--state-dir",
            str(self.state_dir),
            "--key",
            self.key,
            *extra,
        ]

    def wait_for_state(self, expected: str, timeout: float = 5) -> dict[str, object]:
        state_path = self.state_dir / f"{self.key}.json"
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if state_path.exists():
                state = json.loads(state_path.read_text())
                if state.get("status") == expected:
                    return state
            time.sleep(0.02)
        self.fail(f"state did not reach {expected}")

    @staticmethod
    def isolated_process_options() -> dict[str, object]:
        if os.name == "nt":
            return {
                "creationflags": subprocess.CREATE_NEW_PROCESS_GROUP
                | subprocess.CREATE_NO_WINDOW,
            }
        return {"start_new_session": True}

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

    def test_dead_active_state_becomes_orphaned(self) -> None:
        state_path = self.state_dir / f"{self.key}.json"
        state_path.write_text(
            json.dumps(
                {
                    "status": "RUNNING",
                    "command": ["gradlew", "spotlessCheck"],
                    "workerPid": 999_999_999,
                    "workerIdentity": "dead-worker",
                    "processPid": 999_999_998,
                    "processIdentity": "dead-process",
                    "startedAt": "2026-07-25T00:00:00+00:00",
                    "exitCode": None,
                },
            ),
        )

        reconciled = self.run_command("wait", "--timeout-seconds", "0.1")

        self.assertEqual(125, reconciled.returncode)
        self.assertIn("ORPHANED", reconciled.stdout)
        terminal = json.loads(state_path.read_text())
        self.assertEqual("ORPHANED", terminal["status"])
        self.assertEqual(125, terminal["exitCode"])

    def test_real_child_survives_worker_loss_then_becomes_orphaned(self) -> None:
        worker_code = (
            "from pathlib import Path; import sys,time; "
            "p=Path(sys.argv[1]); "
            "p.write_text(str(int(p.read_text())+1) if p.exists() else '1'); "
            "print('child-started', flush=True); time.sleep(2); print('child-finished')"
        )
        command = [sys.executable, "-c", worker_code, str(self.counter)]
        started = self.run_command("start", "--", *command)
        self.assertEqual(0, started.returncode, started.stderr)
        running = self.wait_for_state("RUNNING")
        if not running.get("workerIdentity") or not running.get("processIdentity"):
            self.run_command("wait", "--timeout-seconds", "5")
            self.fail("managed state must record workerIdentity and processIdentity")

        worker_pid = int(running["workerPid"])
        if os.name == "nt":
            os.kill(worker_pid, signal.SIGTERM)
        else:
            os.kill(worker_pid, signal.SIGKILL)
        time.sleep(0.1)

        attached = self.run_command("start", "--", *command)
        self.assertEqual(0, attached.returncode, attached.stderr)
        self.assertIn("ATTACHED", attached.stdout)
        final = self.run_command("wait", "--timeout-seconds", "5")
        self.assertEqual(125, final.returncode)
        self.assertIn("ORPHANED", final.stdout)
        self.assertEqual("1", self.counter.read_text())

    def test_wrong_process_identity_is_never_attached_or_killed(self) -> None:
        sleeper = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(5)"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            **self.isolated_process_options(),
        )
        state_path = self.state_dir / f"{self.key}.json"
        state = {
            "status": "RUNNING",
            "command": ["gradlew", "spotlessCheck"],
            "workerPid": 999_999_999,
            "workerIdentity": "dead-worker",
            "processPid": sleeper.pid,
            "processIdentity": "not-the-sleeper",
            "startedAt": "2026-07-25T00:00:00+00:00",
            "exitCode": None,
        }
        state_path.write_text(json.dumps(state))
        try:
            stopped = self.run_command("stop")
            self.assertEqual(0, stopped.returncode, stopped.stderr)
            self.assertIn("CANCELLED", stopped.stdout)
            self.assertIsNone(sleeper.poll())

            state_path.write_text(json.dumps(state))
            reconciled = self.run_command("status")
            self.assertIn("ORPHANED", reconciled.stdout)
            self.assertIsNone(sleeper.poll())
        finally:
            sleeper.terminate()
            sleeper.wait(timeout=5)

    def write_dead_active_state(self) -> Path:
        state_path = self.state_dir / f"{self.key}.json"
        state_path.write_text(
            json.dumps(
                {
                    "status": "RUNNING",
                    "command": ["gradlew", "spotlessCheck"],
                    "workerPid": 999_999_999,
                    "workerIdentity": "dead-worker",
                    "processPid": 999_999_998,
                    "processIdentity": "dead-process",
                    "startedAt": "2026-07-25T00:00:00+00:00",
                    "exitCode": None,
                },
            ),
        )
        return state_path

    def test_stale_lock_contents_do_not_block_reconciliation(self) -> None:
        self.write_dead_active_state()
        lock = self.state_dir / f"{self.key}.lock"
        lock.write_text("999999997")
        reconciled = self.run_command("wait", "--timeout-seconds", "0.1")
        self.assertEqual(125, reconciled.returncode)
        self.assertIn("ORPHANED", reconciled.stdout)
        self.assertTrue(lock.exists())

    def test_state_write_waits_for_a_reader_that_temporarily_blocks_replace(self) -> None:
        state_path = self.state_dir / f"{self.key}.json"
        state_path.write_text(json.dumps({"status": "RUNNING"}))
        reader_ready = self.state_dir / "reader-ready"
        release_reader = self.state_dir / "release-reader"
        first_failure = self.state_dir / "first-replace-failure"
        retry_observed = self.state_dir / "replace-retry-observed"
        reader_code = (
            "import pathlib,sys,time; "
            "target=pathlib.Path(sys.argv[1]); "
            "handle=target.open('r',encoding='utf-8'); "
            "pathlib.Path(sys.argv[2]).write_text('ready'); "
            "release=pathlib.Path(sys.argv[3]); "
            "exec('while not release.exists():\\n time.sleep(0.01)'); "
            "handle.close()"
        )
        writer_code = (
            "import importlib.util,pathlib,sys,time; "
            "spec=importlib.util.spec_from_file_location('coordinator',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); "
            "spec.loader.exec_module(module); "
            "first_failure=pathlib.Path(sys.argv[4]); "
            "retry_observed=pathlib.Path(sys.argv[5]); "
            "release=pathlib.Path(sys.argv[6]); "
            "original_replace=pathlib.Path.replace; "
            "replace_attempts=0; "
            "exec(\"def observed_replace(self,target):\\n"
            " global replace_attempts\\n"
            " replace_attempts += 1\\n"
            " if replace_attempts == 1:\\n"
            "  first_failure.write_text('failed')\\n"
            "  raise PermissionError('injected sharing violation')\\n"
            " if replace_attempts == 2:\\n"
            "  retry_observed.write_text('retry')\\n"
            "  while not release.exists():\\n"
            "   time.sleep(0.01)\\n"
            " return original_replace(self,target)\"); "
            "pathlib.Path.replace=observed_replace; "
            "module.write_state(pathlib.Path(sys.argv[2]),sys.argv[3],{'status':'PASSED','exitCode':0})"
        )
        reader = subprocess.Popen(
            [
                sys.executable,
                "-c",
                reader_code,
                str(state_path),
                str(reader_ready),
                str(release_reader),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        writer: subprocess.Popen[str] | None = None
        try:
            deadline = time.monotonic() + 5
            while not reader_ready.exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(reader_ready.exists())
            writer = subprocess.Popen(
                [
                    sys.executable,
                    "-c",
                    writer_code,
                    str(COORDINATOR),
                    str(self.state_dir),
                    self.key,
                    str(first_failure),
                    str(retry_observed),
                    str(release_reader),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            deadline = time.monotonic() + 5
            while not retry_observed.exists() and writer.poll() is None and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(first_failure.exists())
            self.assertTrue(retry_observed.exists())
            self.assertIsNone(writer.poll())
        finally:
            release_reader.write_text("release")
            reader_stdout, reader_stderr = reader.communicate(timeout=5)
            writer_stdout, writer_stderr = (
                writer.communicate(timeout=5) if writer is not None else ("", "")
            )

        self.assertEqual(0, reader.returncode, reader_stderr or reader_stdout)
        assert writer is not None
        self.assertEqual(0, writer.returncode, writer_stderr or writer_stdout)
        self.assertEqual("PASSED", json.loads(state_path.read_text())["status"])
        self.assertEqual([], list(self.state_dir.glob(f"{self.key}.json.*.tmp")))

    def test_concurrent_reconciliation_uses_one_os_lock_without_deleting_it(self) -> None:
        state_path = self.write_dead_active_state()
        lock = self.state_dir / f"{self.key}.lock"
        lock.write_text("legacy-pid-only-lock")
        acquired = self.state_dir / "lock-acquired"
        release = self.state_dir / "release-lock"
        waiter_ready = self.state_dir / "waiter-ready"
        first_attempt = self.state_dir / "first-attempt"
        waiter_acquired = self.state_dir / "waiter-acquired"
        holder_code = (
            "import importlib.util,os,pathlib,sys,time; "
            "spec=importlib.util.spec_from_file_location('coordinator',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); "
            "spec.loader.exec_module(module); "
            "lock=pathlib.Path(sys.argv[2]); "
            "descriptor=os.open(lock,os.O_CREAT|os.O_RDWR,0o600); "
            "assert module.try_lock_descriptor(descriptor); "
            "os.ftruncate(descriptor,0); "
            "pathlib.Path(sys.argv[3]).write_text('acquired'); "
            "release=pathlib.Path(sys.argv[4]); "
            "exec('while not release.exists():\\n time.sleep(0.01)'); "
            "module.unlock_descriptor(descriptor); os.close(descriptor)"
        )
        waiter_code = (
            "import importlib.util,os,pathlib,sys,time; "
            "spec=importlib.util.spec_from_file_location('coordinator',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); "
            "spec.loader.exec_module(module); "
            "lock=pathlib.Path(sys.argv[2]); "
            "descriptor=os.open(lock,os.O_CREAT|os.O_RDWR,0o600); "
            "pathlib.Path(sys.argv[3]).write_text('ready'); "
            "first=module.try_lock_descriptor(descriptor); "
            "pathlib.Path(sys.argv[4]).write_text('acquired' if first else 'blocked'); "
            "sys.exit(90) if first else None; "
            "release=pathlib.Path(sys.argv[5]); "
            "exec('while not release.exists():\\n time.sleep(0.01)'); "
            "exec('while not module.try_lock_descriptor(descriptor):\\n time.sleep(0.01)'); "
            "pathlib.Path(sys.argv[6]).write_text('acquired'); "
            "module.unlock_descriptor(descriptor); os.close(descriptor); "
            "state_dir=pathlib.Path(sys.argv[7]); key=sys.argv[8]; "
            "state=module.reconcile_stale_active("
            "state_dir,key,module.load_state(state_dir,key)); "
            "assert state['status']=='ORPHANED' and state['exitCode']==125; "
            "sys.exit(125)"
        )
        holder = subprocess.Popen(
            [
                sys.executable,
                "-c",
                holder_code,
                str(COORDINATOR),
                str(lock),
                str(acquired),
                str(release),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        waiter: subprocess.Popen[str] | None = None
        try:
            deadline = time.monotonic() + 5
            while not acquired.exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(acquired.exists())

            waiter = subprocess.Popen(
                [
                    sys.executable,
                    "-c",
                    waiter_code,
                    str(COORDINATOR),
                    str(lock),
                    str(waiter_ready),
                    str(first_attempt),
                    str(release),
                    str(waiter_acquired),
                    str(self.state_dir),
                    self.key,
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            deadline = time.monotonic() + 5
            while (
                (not waiter_ready.exists() or not first_attempt.exists())
                and time.monotonic() < deadline
            ):
                time.sleep(0.01)
            self.assertTrue(waiter_ready.exists())
            self.assertTrue(first_attempt.exists())
            self.assertEqual("blocked", first_attempt.read_text())
            self.assertIsNone(waiter.poll())
        finally:
            release.write_text("release")
            holder_stdout, holder_stderr = holder.communicate(timeout=5)
            waiter_stdout, waiter_stderr = (
                waiter.communicate(timeout=5) if waiter is not None else ("", "")
            )

        self.assertEqual(0, holder.returncode, holder_stderr or holder_stdout)
        assert waiter is not None
        self.assertEqual(125, waiter.returncode, waiter_stderr)
        self.assertEqual("", waiter_stdout)
        self.assertTrue(waiter_acquired.exists())
        self.assertEqual("ORPHANED", json.loads(state_path.read_text())["status"])
        self.assertTrue(lock.exists())


if __name__ == "__main__":
    unittest.main()
