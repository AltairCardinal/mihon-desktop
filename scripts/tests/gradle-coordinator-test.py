from __future__ import annotations

import json
import importlib.util
import os
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
COORDINATOR = REPO_ROOT / "scripts" / "gradle-coordinator.py"
COORDINATOR_SPEC = importlib.util.spec_from_file_location("gradle_coordinator", COORDINATOR)
assert COORDINATOR_SPEC is not None and COORDINATOR_SPEC.loader is not None
COORDINATOR_MODULE = importlib.util.module_from_spec(COORDINATOR_SPEC)
COORDINATOR_SPEC.loader.exec_module(COORDINATOR_MODULE)


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
            encoding="utf-8",
            env=self.utf8_environment(),
            check=False,
        )

    @staticmethod
    def utf8_environment() -> dict[str, str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PYTHONDONTWRITEBYTECODE": "1",
                "PYTHONUTF8": "1",
                "PYTHONIOENCODING": "utf-8",
            }
        )
        return environment

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
                state = json.loads(state_path.read_text(encoding="utf-8"))
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
            "p.write_text(str(int(p.read_text(encoding='utf-8'))+1) if p.exists() else '1', encoding='utf-8'); "
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
        self.assertEqual("1", self.counter.read_text(encoding="utf-8"))

        state = json.loads((self.state_dir / f"{self.key}.json").read_text(encoding="utf-8"))
        self.assertEqual("PASSED", state["status"])
        self.assertEqual(0, state["exitCode"])
        self.assertIn("slow-finished", (self.state_dir / f"{self.key}.log").read_text(encoding="utf-8"))

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

    def test_other_key_cannot_start_while_managed_process_is_alive(self) -> None:
        command = [sys.executable, "-c", "import time; time.sleep(3)"]
        first = self.run_command("start", "--", *command)
        self.assertEqual(0, first.returncode, first.stderr)
        original_key = self.key
        try:
            self.key = "competing-build"
            second = self.run_command("start", "--", *command)
            self.assertNotEqual(0, second.returncode, second.stdout)
            self.assertIn("busy", second.stderr)
        finally:
            self.run_command("stop")
            self.key = original_key

    def test_start_does_not_attach_to_different_command(self) -> None:
        first = self.run_command("start", "--", sys.executable, "-c", "import time; time.sleep(3)")
        self.assertEqual(0, first.returncode, first.stderr)
        second = self.run_command("start", "--", sys.executable, "-c", "print('different')")
        self.assertNotEqual(0, second.returncode)
        self.assertIn("different command", second.stderr)

    def test_simultaneous_keys_start_only_one_child(self) -> None:
        original = self.key
        processes = []
        keys = ["race-a", "race-b"]
        try:
            for key in keys:
                self.key = key
                marker = self.state_dir / f"{key}.started"
                code = "from pathlib import Path; import sys,time; Path(sys.argv[1]).write_text('started',encoding='utf-8'); time.sleep(3)"
                processes.append(subprocess.Popen(self.coordinator_command("start", "--", sys.executable, "-c", code, str(marker)), cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", env=self.utf8_environment()))
            results = [(process, process.communicate(timeout=10)) for process in processes]
            self.assertEqual(sorted(process.returncode for process, _ in results), [0, 2])
            deadline = time.monotonic() + 2
            while not list(self.state_dir.glob("*.started")) and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertEqual(len(list(self.state_dir.glob("*.started"))), 1)
        finally:
            for key in keys:
                self.key = key
                self.run_command("stop")
            self.key = original

    def test_non_state_json_is_ignored_and_reserved_keys_are_rejected(self) -> None:
        (self.state_dir / "report.json").write_text("[]", encoding="utf-8")
        result = self.run_command("run", "--timeout-seconds", "5", "--", sys.executable, "-c", "print('done')")
        self.assertEqual(result.returncode, 0, result.stderr)
        original = self.key
        try:
            self.key = "_launch"
            result = self.run_command("start", "--", sys.executable, "-c", "print('must not run')")
            self.assertEqual(result.returncode, 2)
            self.assertIn("reserved", result.stderr)
        finally:
            self.key = original

    def test_same_key_and_command_cannot_attach_from_another_directory(self) -> None:
        command = [sys.executable, "-c", "import time; time.sleep(3)"]
        self.assertEqual(self.run_command("start", "--", *command).returncode, 0)
        result = subprocess.run(self.coordinator_command("start", "--", *command), cwd=self.state_dir, capture_output=True, text=True, encoding="utf-8", env=self.utf8_environment())
        self.assertEqual(result.returncode, 2)
        self.assertIn("different command", result.stderr)

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
        self.assertIn("run-finished", (self.state_dir / f"{self.key}.log").read_text(encoding="utf-8"))

    def test_foreground_owns_direct_child_without_background_worker(self) -> None:
        child_pid_file = self.state_dir / "foreground-child.txt"
        child_code = (
            "from pathlib import Path; import os,sys,time; "
            "Path(sys.argv[1]).write_text(f'{os.getpid()}:{os.getppid()}', encoding='utf-8'); "
            "print('foreground-started', flush=True); time.sleep(0.25); print('foreground-finished', flush=True)"
        )
        command = [sys.executable, "-c", child_code, str(child_pid_file)]
        foreground = subprocess.Popen(
            self.coordinator_command("foreground", "--timeout-seconds", "5", "--", *command),
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
        )
        try:
            running = self.wait_for_state("RUNNING")
            deadline = time.monotonic() + 5
            while not child_pid_file.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(child_pid_file.exists())
            child_pid, parent_pid = (
                int(value) for value in child_pid_file.read_text(encoding="utf-8").split(":")
            )
            self.assertEqual(foreground.pid, running["workerPid"])
            self.assertEqual(child_pid, running["processPid"])
            self.assertEqual(foreground.pid, parent_pid)
            self.assertEqual("foreground", running["executionMode"])
            self.assertNotEqual(running["workerPid"], running["processPid"])
            stdout, stderr = foreground.communicate(timeout=5)
        finally:
            if foreground.poll() is None:
                self.run_command("stop")
                foreground.communicate(timeout=5)
        self.assertEqual(0, foreground.returncode, stderr or stdout)
        self.assertIn("PASSED", stdout)
        self.assertIn("foreground-finished", (self.state_dir / f"{self.key}.log").read_text(encoding="utf-8"))

    def test_foreground_preserves_fast_success_and_nonzero_exit(self) -> None:
        fast = self.run_command(
            "foreground",
            "--timeout-seconds",
            "5",
            "--",
            sys.executable,
            "-c",
            "print('fast-foreground')",
        )
        self.assertEqual(0, fast.returncode, fast.stderr)
        self.assertIn("PASSED", fast.stdout)
        state = json.loads((self.state_dir / f"{self.key}.json").read_text(encoding="utf-8"))
        self.assertEqual("PASSED", state["status"])
        self.assertEqual(0, state["exitCode"])
        self.assertIn("fast-foreground", (self.state_dir / f"{self.key}.log").read_text(encoding="utf-8"))

        self.key = "foreground-failure"
        failed = self.run_command(
            "foreground",
            "--timeout-seconds",
            "5",
            "--",
            sys.executable,
            "-c",
            "print('expected-foreground-failure'); raise SystemExit(7)",
        )
        self.assertEqual(7, failed.returncode)
        self.assertIn("FAILED", failed.stdout)
        state = json.loads((self.state_dir / f"{self.key}.json").read_text(encoding="utf-8"))
        self.assertEqual("FAILED", state["status"])
        self.assertEqual(7, state["exitCode"])

    def test_foreground_same_key_attaches_without_second_child(self) -> None:
        counter = self.state_dir / "foreground-counter.txt"
        child_code = (
            "from pathlib import Path; import sys,time; "
            "p=Path(sys.argv[1]); p.write_text(str(int(p.read_text(encoding='utf-8'))+1) if p.exists() else '1', encoding='utf-8'); "
            "time.sleep(0.35)"
        )
        command = [sys.executable, "-c", child_code, str(counter)]
        first = subprocess.Popen(
            self.coordinator_command("foreground", "--timeout-seconds", "5", "--", *command),
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
        )
        try:
            self.wait_for_state("RUNNING")
            second = self.run_command("foreground", "--timeout-seconds", "5", "--", *command)
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertIn("ATTACHED", second.stdout)
            stdout, stderr = first.communicate(timeout=5)
        finally:
            if first.poll() is None:
                self.run_command("stop")
                first.communicate(timeout=5)
        self.assertEqual(0, first.returncode, stderr or stdout)
        self.assertEqual("1", counter.read_text(encoding="utf-8"))

    def test_foreground_same_key_rejects_different_command(self) -> None:
        first = subprocess.Popen(
            self.coordinator_command(
                "foreground",
                "--timeout-seconds",
                "5",
                "--",
                sys.executable,
                "-c",
                "import time; time.sleep(0.35)",
            ),
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
        )
        try:
            self.wait_for_state("RUNNING")
            different = self.run_command(
                "foreground",
                "--timeout-seconds",
                "5",
                "--",
                sys.executable,
                "-c",
                "print('must-not-run')",
            )
            self.assertEqual(2, different.returncode)
            self.assertIn("different command", different.stderr)
            self.assertIsNone(first.poll())
            stdout, stderr = first.communicate(timeout=5)
        finally:
            if first.poll() is None:
                self.run_command("stop")
                first.communicate(timeout=5)
        self.assertEqual(0, first.returncode, stderr or stdout)

    def test_foreground_old_owner_cannot_finalize_new_state(self) -> None:
        first = subprocess.Popen(
            self.coordinator_command(
                "foreground",
                "--timeout-seconds",
                "5",
                "--",
                sys.executable,
                "-c",
                "import time; time.sleep(0.2)",
            ),
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
        )
        replacement = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(5)"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
        )
        try:
            self.wait_for_state("RUNNING")
            with COORDINATOR_MODULE.state_lock(self.state_dir, self.key):
                replacement_state = {
                    "status": "RUNNING",
                    "command": [sys.executable, "-c", "print('replacement')"],
                    "workerPid": os.getpid(),
                    "workerIdentity": COORDINATOR_MODULE.process_identity(os.getpid()),
                    "processPid": replacement.pid,
                    "processIdentity": COORDINATOR_MODULE.process_identity(replacement.pid),
                    "executionMode": "foreground",
                    "startedAt": "2026-09-13T00:00:00+00:00",
                    "exitCode": None,
                }
                (self.state_dir / f"{self.key}.json").write_text(
                    json.dumps(replacement_state),
                    encoding="utf-8",
                )
                time.sleep(0.4)
            stdout, stderr = first.communicate(timeout=5)
            current = json.loads((self.state_dir / f"{self.key}.json").read_text(encoding="utf-8"))
            self.assertEqual("RUNNING", current["status"])
            self.assertEqual(replacement.pid, current["processPid"])
            self.assertEqual(0, first.returncode, stderr or stdout)
        finally:
            if first.poll() is None:
                self.run_command("stop")
                first.communicate(timeout=5)
            replacement.terminate()
            replacement.wait(timeout=5)

    def test_foreground_startup_failure_reaps_started_child(self) -> None:
        child_pid_file = self.state_dir / "startup-child.pid"
        injector = textwrap.dedent(
            """
            import importlib.util
            import pathlib
            import sys

            spec = importlib.util.spec_from_file_location("coordinator", sys.argv[1])
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            original = module.write_state
            calls = [0]

            def flaky(state_dir, key, state):
                calls[0] += 1
                if calls[0] == 2:
                    raise OSError("injected state write failure")
                return original(state_dir, key, state)

            module.write_state = flaky
            child_code = (
                "import os,pathlib,sys,time; "
                "pathlib.Path(sys.argv[1]).write_text(str(os.getpid()), encoding='utf-8'); "
                "time.sleep(5)"
            )
            args = module.parser().parse_args(
                [
                    "foreground",
                    "--state-dir",
                    sys.argv[2],
                    "--key",
                    "startup-failure",
                    "--",
                    sys.executable,
                    "-c",
                    child_code,
                    sys.argv[3],
                ]
            )
            sys.exit(module.command_foreground(args))
            """
        )
        completed = subprocess.run(
            [sys.executable, "-c", injector, str(COORDINATOR), str(self.state_dir), str(child_pid_file)],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
            check=False,
        )
        self.assertEqual(1, completed.returncode, completed.stderr or completed.stdout)
        child_pid = int(child_pid_file.read_text(encoding="utf-8"))
        self.assertIsNone(COORDINATOR_MODULE.process_identity(child_pid))

    def test_foreground_stop_does_not_kill_unrelated_process(self) -> None:
        child_started = self.state_dir / "cancellable-child.txt"
        child_code = (
            "from pathlib import Path; import os,sys,time; "
            "Path(sys.argv[1]).write_text(str(os.getpid()), encoding='utf-8'); "
            "time.sleep(5)"
        )
        foreground = subprocess.Popen(
            self.coordinator_command(
                "foreground",
                "--timeout-seconds",
                "10",
                "--",
                sys.executable,
                "-c",
                child_code,
                str(child_started),
            ),
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
        )
        unrelated = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(5)"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            # Deliberately inherit the test runner's process group.  The
            # foreground child has its own verified group; cancellation must
            # not terminate this unrelated sibling or the caller's shell.
        )
        try:
            self.wait_for_state("RUNNING")
            stopped = self.run_command("stop")
            self.assertEqual(0, stopped.returncode, stopped.stderr)
            stdout, stderr = foreground.communicate(timeout=5)
            self.assertIsNone(unrelated.poll())
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=5)
            if foreground.poll() is None:
                foreground.communicate(timeout=5)
        self.assertEqual(130, foreground.returncode, stderr or stdout)
        state = json.loads((self.state_dir / f"{self.key}.json").read_text(encoding="utf-8"))
        self.assertEqual("CANCELLED", state["status"])
        self.assertEqual(130, state["exitCode"])

    def test_foreground_timeout_cleans_up_managed_process(self) -> None:
        started = time.monotonic()
        completed = self.run_command(
            "foreground",
            "--timeout-seconds",
            "0.1",
            "--",
            sys.executable,
            "-c",
            "import time; time.sleep(5)",
        )
        elapsed = time.monotonic() - started
        self.assertEqual(124, completed.returncode)
        self.assertLess(elapsed, 3)
        state = json.loads((self.state_dir / f"{self.key}.json").read_text(encoding="utf-8"))
        self.assertEqual("CANCELLED", state["status"])
        self.assertEqual(124, state["exitCode"])
        self.assertFalse(
            state.get("processPid")
            and state.get("processIdentity")
            and self._process_is_alive(state["processPid"], state["processIdentity"])
        )

    @staticmethod
    def _process_is_alive(pid: int, identity: str) -> bool:
        return COORDINATOR_MODULE.process_matches(pid, identity)

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
            encoding="utf-8",
        )

        reconciled = self.run_command("wait", "--timeout-seconds", "0.1")

        self.assertEqual(125, reconciled.returncode)
        self.assertIn("ORPHANED", reconciled.stdout)
        terminal = json.loads(state_path.read_text(encoding="utf-8"))
        self.assertEqual("ORPHANED", terminal["status"])
        self.assertEqual(125, terminal["exitCode"])

    def test_real_child_survives_worker_loss_then_becomes_orphaned(self) -> None:
        worker_code = (
            "from pathlib import Path; import sys,time; "
            "p=Path(sys.argv[1]); "
            "p.write_text(str(int(p.read_text(encoding='utf-8'))+1) if p.exists() else '1', encoding='utf-8'); "
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
        self.assertEqual("1", self.counter.read_text(encoding="utf-8"))

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
        state_path.write_text(json.dumps(state), encoding="utf-8")
        try:
            stopped = self.run_command("stop")
            self.assertEqual(0, stopped.returncode, stopped.stderr)
            self.assertIn("CANCELLED", stopped.stdout)
            self.assertIsNone(sleeper.poll())

            state_path.write_text(json.dumps(state), encoding="utf-8")
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
            encoding="utf-8",
        )
        return state_path

    def test_stale_lock_contents_do_not_block_reconciliation(self) -> None:
        self.write_dead_active_state()
        lock = self.state_dir / f"{self.key}.lock"
        lock.write_text("999999997", encoding="utf-8")
        reconciled = self.run_command("wait", "--timeout-seconds", "0.1")
        self.assertEqual(125, reconciled.returncode)
        self.assertIn("ORPHANED", reconciled.stdout)
        self.assertTrue(lock.exists())

    def test_state_write_waits_for_a_reader_that_temporarily_blocks_replace(self) -> None:
        state_path = self.state_dir / f"{self.key}.json"
        state_path.write_text(json.dumps({"status": "RUNNING"}), encoding="utf-8")
        reader_ready = self.state_dir / "reader-ready"
        release_reader = self.state_dir / "release-reader"
        first_failure = self.state_dir / "first-replace-failure"
        retry_observed = self.state_dir / "replace-retry-observed"
        reader_code = (
            "import pathlib,sys,time; "
            "target=pathlib.Path(sys.argv[1]); "
            "handle=target.open('r',encoding='utf-8'); "
            "pathlib.Path(sys.argv[2]).write_text('ready', encoding='utf-8'); "
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
            "  first_failure.write_text('failed', encoding='utf-8')\\n"
            "  raise PermissionError('injected sharing violation')\\n"
            " if replace_attempts == 2:\\n"
            "  retry_observed.write_text('retry', encoding='utf-8')\\n"
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
            release_reader.write_text("release", encoding="utf-8")
            reader_stdout, reader_stderr = reader.communicate(timeout=5)
            writer_stdout, writer_stderr = (
                writer.communicate(timeout=5) if writer is not None else ("", "")
            )

        self.assertEqual(0, reader.returncode, reader_stderr or reader_stdout)
        assert writer is not None
        self.assertEqual(0, writer.returncode, writer_stderr or writer_stdout)
        self.assertEqual("PASSED", json.loads(state_path.read_text(encoding="utf-8"))["status"])
        self.assertEqual([], list(self.state_dir.glob(f"{self.key}.json.*.tmp")))

    def test_concurrent_reconciliation_uses_one_os_lock_without_deleting_it(self) -> None:
        state_path = self.write_dead_active_state()
        lock = self.state_dir / f"{self.key}.lock"
        lock.write_text("legacy-pid-only-lock", encoding="utf-8")
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
            "pathlib.Path(sys.argv[3]).write_text('acquired', encoding='utf-8'); "
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
            "pathlib.Path(sys.argv[3]).write_text('ready', encoding='utf-8'); "
            "first=module.try_lock_descriptor(descriptor); "
            "pathlib.Path(sys.argv[4]).write_text('acquired' if first else 'blocked', encoding='utf-8'); "
            "sys.exit(90) if first else None; "
            "release=pathlib.Path(sys.argv[5]); "
            "exec('while not release.exists():\\n time.sleep(0.01)'); "
            "exec('while not module.try_lock_descriptor(descriptor):\\n time.sleep(0.01)'); "
            "pathlib.Path(sys.argv[6]).write_text('acquired', encoding='utf-8'); "
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
            self.assertEqual("blocked", first_attempt.read_text(encoding="utf-8"))
            self.assertIsNone(waiter.poll())
        finally:
            release.write_text("release", encoding="utf-8")
            holder_stdout, holder_stderr = holder.communicate(timeout=5)
            waiter_stdout, waiter_stderr = (
                waiter.communicate(timeout=5) if waiter is not None else ("", "")
            )

        self.assertEqual(0, holder.returncode, holder_stderr or holder_stdout)
        assert waiter is not None
        self.assertEqual(125, waiter.returncode, waiter_stderr)
        self.assertEqual("", waiter_stdout)
        self.assertTrue(waiter_acquired.exists())
        self.assertEqual("ORPHANED", json.loads(state_path.read_text(encoding="utf-8"))["status"])
        self.assertTrue(lock.exists())


if __name__ == "__main__":
    unittest.main()
