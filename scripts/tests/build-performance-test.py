"""Adaptive Gradle worker policy, resource sampling, and runner contracts."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
ENTRY = ROOT / "scripts/build-performance.py"
GIB = 1024 ** 3


def module():
    spec = importlib.util.spec_from_file_location("build_performance", ENTRY)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


_IDENTITY_SUPPORTED = None


def platform_identity_supported():
    """Class-level capability probe; a skipUnless decorator cannot reach self."""
    global _IDENTITY_SUPPORTED
    if _IDENTITY_SUPPORTED is None:
        try:
            _IDENTITY_SUPPORTED = module().process_started_ms(os.getpid()) is not None
        except Exception:
            _IDENTITY_SUPPORTED = False
    return _IDENTITY_SUPPORTED


def windows_available_memory():
    """Independent read of ullAvailPhys so the sampler cannot silently swap semantics."""
    import ctypes
    from ctypes import wintypes

    class Status(ctypes.Structure):
        _fields_ = [
            ("dwLength", wintypes.DWORD), ("dwMemoryLoad", wintypes.DWORD),
            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
            ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
        ]

    status = Status()
    status.dwLength = ctypes.sizeof(Status)
    ctypes.WinDLL("kernel32", use_last_error=True).GlobalMemoryStatusEx(ctypes.byref(status))
    return status.ullAvailPhys, status.ullTotalPhys


class ResourceCeilingTest(unittest.TestCase):
    def setUp(self):
        self.performance = module()
        self.snapshot = {"valid": True, "cpu_count": 32, "available_bytes": 20 * GIB,
                         "total_bytes": 32 * GIB, "cpu_busy_pct": 10}

    def test_cold_start_uses_the_resource_ceiling_instead_of_a_flat_floor(self):
        ceiling = self.performance.resource_ceiling(self.snapshot)
        rungs = self.performance.ladder_rungs(ceiling)
        decision = self.performance.choose_workers(self.snapshot, [], "android-release", "tool", "cold")
        self.assertEqual(rungs[-1], decision["workers"])
        self.assertGreaterEqual(decision["workers"], 8, "cold start must not crawl from a pathological floor")
        self.assertEqual("cold-start-resource-derived", decision["reason"])

    def test_cold_start_scales_with_the_machine(self):
        for cpus, expected_at_least in ((4, 2), (8, 4), (32, 8)):
            snapshot = dict(self.snapshot, cpu_count=cpus)
            workers = self.performance.choose_workers(snapshot, [], "android-release", "tool", "cold")["workers"]
            self.assertGreaterEqual(workers, expected_at_least, cpus)
            self.assertLessEqual(workers, cpus)

    def test_memory_pressure_is_graded_instead_of_one_blunt_threshold(self):
        ceiling_by_available = {}
        for gib in (1, 2, 4, 8, 12, 20, 30):
            snapshot = dict(self.snapshot, available_bytes=gib * GIB)
            ceiling_by_available[gib] = self.performance.resource_ceiling(snapshot)
        self.assertEqual(1, ceiling_by_available[1])
        self.assertLess(ceiling_by_available[2], ceiling_by_available[4])
        self.assertLess(ceiling_by_available[4], ceiling_by_available[8])
        self.assertLess(ceiling_by_available[8], ceiling_by_available[12])
        self.assertEqual(sorted(ceiling_by_available.values()), list(ceiling_by_available.values()))

    def test_low_memory_overrides_history_and_cpu_count_caps_the_result(self):
        starved = dict(self.snapshot, available_bytes=GIB)
        history = [self.record(8), self.record(8)]
        self.assertEqual(1, self.performance.choose_workers(starved, history, "android-release", "tool", "cold")["workers"])
        small = dict(self.snapshot, cpu_count=3)
        workers = self.performance.choose_workers(small, history, "android-release", "tool", "cold")["workers"]
        self.assertLessEqual(workers, 3)

    def test_invalid_snapshot_falls_back_to_one_worker_without_aborting(self):
        for snapshot in ({"valid": False}, {}, None, {"valid": True, "cpu_count": 0, "available_bytes": GIB},
                         {"valid": True, "cpu_count": "many", "available_bytes": 20 * GIB}):
            self.assertEqual(1, self.performance.choose_workers(snapshot, [], "android-release", "tool", "cold")["workers"])

    def test_memory_per_worker_override_is_honoured(self):
        generous = self.performance.resource_ceiling(self.snapshot, memory_per_worker_bytes=GIB // 2)
        frugal = self.performance.resource_ceiling(self.snapshot, memory_per_worker_bytes=4 * GIB)
        self.assertGreater(generous, frugal)

    def record(self, workers=2, duration=100, **extra):
        row = dict(family="android-release", toolchain="tool", cache="cold", workers=workers,
                   elapsed_seconds=duration, eligible=True, pressure=False)
        row.update(extra)
        return row


class WorkerPolicyTest(unittest.TestCase):
    def setUp(self):
        self.performance = module()
        self.snapshot = {"valid": True, "cpu_count": 32, "available_bytes": 20 * GIB,
                         "total_bytes": 32 * GIB, "cpu_busy_pct": 10}

    def choose(self, history=(), snapshot=None, family="android-release", toolchain="tool", cache="cold"):
        return self.performance.choose_workers(snapshot or self.snapshot, list(history), family, toolchain, cache)

    def record(self, workers=2, duration=100, **extra):
        row = dict(family="android-release", toolchain="tool", cache="cold", workers=workers,
                   elapsed_seconds=duration, eligible=True, pressure=False)
        row.update(extra)
        return row

    def test_sustained_success_increases_exactly_one_rung_per_build(self):
        self.assertEqual(4, self.choose([self.record(2, 100), self.record(2, 102)])["workers"])
        self.assertEqual(8, self.choose([self.record(4, 102), self.record(4, 103)])["workers"])
        self.assertEqual(16, self.choose([self.record(8, 103), self.record(8, 104)])["workers"])

    def test_regression_against_the_best_prior_run_steps_down(self):
        self.assertEqual(1, self.choose([self.record(2, 100), self.record(2, 160)])["workers"])
        climbing_then_slowing = [self.record(2, 100), self.record(8, 90), self.record(8, 180)]
        self.assertEqual(4, self.choose(climbing_then_slowing)["workers"])

    def test_baseline_is_derived_from_history_not_a_hardcoded_constant(self):
        slow_machine = [self.record(8, 500), self.record(8, 560)]
        decision = self.choose(slow_machine)
        self.assertEqual(500, decision["baseline_seconds"])
        self.assertEqual(16, decision["workers"], "560s is only 12% over a 500s baseline")

        fast_machine = [self.record(8, 100), self.record(8, 130)]
        self.assertEqual(100, self.choose(fast_machine)["baseline_seconds"])
        self.assertEqual(4, self.choose(fast_machine)["workers"], "130s is 30% over a 100s baseline")

    def test_pressure_or_ineligible_latest_run_demotes(self):
        pressured = [self.record(8, 100), self.record(8, 100, pressure=True)]
        self.assertEqual(4, self.choose(pressured)["workers"])
        failed = [self.record(8, 100), self.record(8, 100, eligible=False)]
        self.assertEqual(4, self.choose(failed)["workers"])

    def test_a_dropped_rung_is_held_for_one_build_before_climbing_again(self):
        dropped = [self.record(8, 100), self.record(4, 100, dropped=True)]
        self.assertEqual(4, self.choose(dropped)["workers"])
        recovered = dropped + [self.record(4, 101)]
        self.assertEqual(8, self.choose(recovered)["workers"])

    def test_foreign_toolchain_and_noop_rows_are_never_used_as_evidence(self):
        suspicious_fast = [self.record(4, 100), self.record(4, 5, toolchain="other")]
        self.assertEqual(8, self.choose(suspicious_fast)["workers"], "a foreign toolchain must not rescale the baseline")
        noop = [self.record(4, 100), self.record(4, 5, cache="noop")]
        self.assertEqual(8, self.choose(noop)["workers"], "a noop run must not rescale the baseline")
        foreign_family = [self.record(4, 100), self.record(4, 5, family="desktop")]
        self.assertEqual(8, self.choose(foreign_family)["workers"])

    def test_malformed_history_rows_are_skipped_without_aborting(self):
        history = [None, {"eligible": True}, "malformed", 17, self.record(2, 100)]
        self.assertEqual(4, self.choose(history)["workers"])

    def test_warm_cache_is_scored_against_warm_history_only(self):
        warm = [self.record(8, 30, cache="warm"), self.record(8, 45, cache="warm")]
        decision = self.choose(warm, cache="warm")
        self.assertEqual(30, decision["baseline_seconds"])
        self.assertEqual(4, decision["workers"], "45s is 50% over the warm 30s baseline")
        self.assertEqual(16, self.choose(warm, cache="cold")["workers"], "warm runs must not inform a cold decision")

    def test_every_decision_is_a_ladder_rung_inside_the_ceiling(self):
        ceiling = self.performance.resource_ceiling(self.snapshot)
        rungs = set(self.performance.ladder_rungs(ceiling))
        histories = ([], [self.record(1, 100)], [self.record(2, 400)], [self.record(16, 100, pressure=True)],
                     [self.record(8, 100, dropped=True)], [None, "x"])
        for history in histories:
            workers = self.choose(history)["workers"]
            self.assertIn(workers, rungs, history)
            self.assertGreaterEqual(workers, 1)
            self.assertLessEqual(workers, ceiling)


class CommandPreparationTest(unittest.TestCase):
    def setUp(self):
        self.performance = module()
        self.snapshot = {"valid": True, "cpu_count": 32, "available_bytes": 20 * GIB,
                         "total_bytes": 32 * GIB, "cpu_busy_pct": 10}

    def test_user_gradle_flags_take_precedence_and_are_left_untouched(self):
        command, decision = self.performance.prepare_command(
            ["gradlew", "--max-workers", "7", "--no-parallel", ":app:assembleRelease"],
            self.snapshot, [], "android-release", "tool")
        self.assertEqual(["gradlew", "--max-workers", "7", "--no-parallel", ":app:assembleRelease"], command)
        self.assertEqual(7, decision["workers"])
        self.assertFalse(decision["parallel"])
        self.assertEqual("user", decision["source"])
        self.assertFalse(decision["injected"])

    def test_system_property_and_last_explicit_option_win(self):
        for flags, workers, parallel in (
            (["-Dorg.gradle.workers.max=5", "-Dorg.gradle.parallel=false"], 5, False),
            (["--max-workers=3", "--parallel", "--max-workers", "6", "--no-parallel"], 6, False),
        ):
            command, decision = self.performance.prepare_command(["gradlew", *flags, "help"],
                                                                self.snapshot, [], "smoke", "tool")
            self.assertEqual(workers, decision["workers"])
            self.assertEqual(parallel, decision["parallel"])
            self.assertEqual(["gradlew", *flags, "help"], command)
            self.assertFalse(decision["injected"])

    def test_parallel_flag_resolves_independently_of_the_worker_flag(self):
        command, decision = self.performance.prepare_command(
            ["gradlew", "-Dorg.gradle.parallel=false", "--parallel", "help"], self.snapshot, [], "smoke", "tool")
        self.assertTrue(decision["parallel"], "the last explicit parallel flag wins")
        self.assertEqual(16, decision["workers"], "workers stay adaptive when the user set only parallel")
        self.assertEqual(1, command.count("--parallel"), "an explicit parallel flag must not be duplicated")
        self.assertEqual(1, command.count("--max-workers"))

    def test_adaptive_decision_injects_workers_and_parallel_for_gradle_only(self):
        command, decision = self.performance.prepare_command(["gradlew", "assembleRelease"],
                                                             self.snapshot, [], "android-release", "tool")
        self.assertEqual(16, decision["workers"])
        self.assertTrue(decision["parallel"])
        self.assertTrue(decision["injected"])
        self.assertEqual(["gradlew", "--max-workers", "16", "--parallel", "assembleRelease"], command)

        untouched, same = self.performance.prepare_command([sys.executable, "fake.py", "7"],
                                                           self.snapshot, [], "android-release", "tool")
        self.assertEqual([sys.executable, "fake.py", "7"], untouched)
        self.assertEqual(16, same["workers"])
        self.assertFalse(same["injected"])

    def test_no_heap_fork_or_daemon_options_are_ever_added(self):
        forbidden = ("-xmx", "jvmargs", "no-daemon", "forkevery", "compiler.execution.strategy",
                     "kotlin.incremental", "workers.max", "configuration-cache")
        command, _ = self.performance.prepare_command(["gradlew", "assembleRelease"],
                                                      self.snapshot, [], "android-release", "tool")
        joined = " ".join(command).lower()
        for needle in forbidden:
            self.assertNotIn(needle, joined)


class DaemonReceiptTest(unittest.TestCase):
    def setUp(self):
        self.performance = module()

    def test_borrowed_daemon_receipt_requires_live_matching_start_identity(self):
        receipt = {"pid": 123, "start_time_ms": 100000, "workers": 4, "parallel": True}
        with patch.object(self.performance, "process_started_ms", return_value=99000):
            self.assertEqual(123, self.performance.validate_runtime_receipt(receipt)["pid"])
        with patch.object(self.performance, "process_started_ms", return_value=200000):
            self.assertIsNone(self.performance.validate_runtime_receipt(receipt))
        with patch.object(self.performance, "process_started_ms", return_value=None):
            self.assertIsNone(self.performance.validate_runtime_receipt(receipt))
        for malformed in (None, {}, {"pid": 0, "start_time_ms": 1}, {"pid": 1}, "receipt"):
            self.assertIsNone(self.performance.validate_runtime_receipt(malformed))

    def test_process_sampling_includes_verified_borrowed_daemon_without_owning_it(self):
        performance = self.performance
        with patch.object(performance, "process_descendants", side_effect=lambda pid: [pid]), \
             patch.object(performance, "process_usage",
                          side_effect=lambda pid: (5 * GIB if pid == 123 else 10 * 1024 ** 2, 1.0)):
            sample = performance.sample_build_processes(42, {"pid": 123})
            self.assertGreater(sample["working_set_bytes"], 5 * GIB)
            self.assertEqual("launcher-and-verified-daemon", sample["coverage"])
        with patch.object(performance, "process_descendants", return_value=[42]), \
             patch.object(performance, "process_usage", return_value=(10, 1.0)):
            self.assertEqual("UNKNOWN", performance.sample_build_processes(42, None)["coverage"])

    def test_unverified_daemon_is_reported_unknown_and_never_sampled(self):
        performance = self.performance
        with patch.object(performance, "process_descendants", return_value=[]), \
             patch.object(performance, "process_usage", return_value=(1024, 0.0)):
            self.assertEqual("UNKNOWN", performance.sample_build_processes(42, None)["coverage"])


class PlatformSamplerTest(unittest.TestCase):
    """The resource layer is the fragile part, so it gets real assertions, not only mocks."""

    def setUp(self):
        self.performance = module()

    def test_system_snapshot_is_valid_and_self_consistent(self):
        snapshot = self.performance.system_snapshot()
        self.assertTrue(snapshot["valid"], snapshot)
        self.assertEqual(os.cpu_count(), snapshot["cpu_count"])
        self.assertGreater(snapshot["available_bytes"], 0)
        self.assertGreater(snapshot["total_bytes"], snapshot["available_bytes"])
        self.assertGreaterEqual(snapshot["cpu_busy_pct"], 0.0)
        self.assertLessEqual(snapshot["cpu_busy_pct"], 100.0)

    @unittest.skipUnless(sys.platform == "win32", "Windows memory semantics")
    def test_windows_memory_uses_available_not_free(self):
        snapshot = self.performance.system_snapshot()
        available, total = windows_available_memory()
        self.assertEqual("GlobalMemoryStatusEx.ullAvailPhys", snapshot["memory_source"])
        self.assertLessEqual(abs(snapshot["available_bytes"] - available), 512 * 1024 * 1024)
        self.assertLessEqual(abs(snapshot["total_bytes"] - total), 512 * 1024 * 1024)

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux MemAvailable")
    def test_linux_memory_uses_memavailable(self):
        expected = None
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            if line.startswith("MemAvailable:"):
                expected = int(line.split()[1]) * 1024
                break
        if expected is None:
            self.skipTest("MemAvailable is unavailable")
        snapshot = self.performance.system_snapshot()
        self.assertEqual("/proc/meminfo:MemAvailable", snapshot["memory_source"])
        self.assertLessEqual(abs(snapshot["available_bytes"] - expected), 512 * 1024 * 1024)

    @unittest.skipUnless(platform_identity_supported(),
                         "process identity is not observable on this platform")
    def test_live_process_reports_stable_identity_and_working_set(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            first = self.performance.process_started_ms(child.pid)
            second = self.performance.process_started_ms(child.pid)
            self.assertIsNotNone(first)
            self.assertEqual(first, second)
            self.assertGreater(first, 0)
            size, _ = self.performance.process_usage(child.pid)
            self.assertGreater(size, 0)
            self.assertLess(size, 64 * GIB)
            self.assertIsNone(self.performance.process_started_ms(0x7FFFFFFF))
        finally:
            child.kill()
            child.wait(timeout=30)

    @unittest.skipUnless(platform_identity_supported(),
                         "process identity is not observable on this platform")
    def test_descendant_processes_are_discovered(self):
        marker = "grandchild-ready"
        child = subprocess.Popen(
            [sys.executable, "-c",
             "import subprocess,sys,time;subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)']);"
             f"print('{marker}', flush=True);time.sleep(30)"],
            stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(marker, child.stdout.readline().strip())
            found = []
            for _ in range(50):
                found = self.performance.process_descendants(child.pid)
                if found:
                    break
                threading_sleep(0.1)
            self.assertTrue(found, "expected at least one descendant")
        finally:
            child.kill()
            child.wait(timeout=30)


def threading_sleep(seconds):
    import time
    time.sleep(seconds)


class RunnerProcessTest(unittest.TestCase):
    def setUp(self):
        self.performance = module()
        self.temporary = tempfile.TemporaryDirectory(prefix="build-performance-")
        self.root = Path(self.temporary.name)
        self.fixture = self.root / "fake-gradle.py"
        self.fixture.write_text(
            "import sys\nprint('> Task :app:compileReleaseKotlin')\nprint('BUILD SUCCESSFUL')\n"
            "sys.exit(int(sys.argv[1]))\n", encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self, code=0, *flags):
        return subprocess.run([sys.executable, str(ENTRY), "run", "--root", str(self.root),
            "--family", "android-release", "--", sys.executable, str(self.fixture), str(code), *flags],
            capture_output=True, text=True, encoding="utf-8",
            env=dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8"))

    def history_rows(self):
        path = self.root / ".gradle-coordinator/performance/history.jsonl"
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

    def test_real_subprocess_preserves_output_exit_and_secret_free_history(self):
        result = self.invoke(7, "-Psecret=do-not-store-this")
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertIn("> Task :app:compileReleaseKotlin", result.stdout)
        self.assertIn("BUILD SUCCESSFUL", result.stdout)
        rows = (self.root / ".gradle-coordinator/performance/history.jsonl").read_text(encoding="utf-8")
        self.assertNotIn("do-not-store-this", rows)
        self.assertNotIn("fake-gradle.py", rows)
        record = self.history_rows()[-1]
        self.assertEqual(7, record["exit_code"])
        self.assertFalse(record["eligible"])
        self.assertGreaterEqual(record["elapsed_seconds"], 0)
        self.assertGreaterEqual(record["queue_seconds"], 0)
        self.assertIn(record["workers"], self.performance.WORKER_LADDER)
        self.assertGreaterEqual(record["workers"], 1)

    def test_successful_run_is_eligible_and_records_memory_pressure_evidence(self):
        result = self.invoke(0)
        self.assertEqual(0, result.returncode, result.stderr)
        record = self.history_rows()[-1]
        self.assertTrue(record["eligible"])
        self.assertIn("coverage", record["memory"])
        self.assertGreaterEqual(record["memory"]["working_set_bytes"], 0)

    def test_memory_is_attributed_while_the_build_is_still_alive(self):
        sleeper = self.root / "slow-gradle.py"
        sleeper.write_text("import time\nprint('> Task :app:compileReleaseKotlin')\ntime.sleep(3)\n",
                           encoding="utf-8")
        result = subprocess.run([sys.executable, str(ENTRY), "run", "--root", str(self.root),
            "--family", "android-release", "--", sys.executable, str(sleeper)],
            capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(0, result.returncode, result.stderr)
        record = self.history_rows()[-1]
        self.assertGreaterEqual(record["memory"]["samples"], 1, "the monitor must sample during the build")
        self.assertGreater(record["memory"]["working_set_bytes"], 1024 * 1024,
                           "a dead pid reports no working set, so sampling must happen while alive")

    def test_history_is_pruned_to_a_bounded_number_of_rows(self):
        path = self.root / ".gradle-coordinator/performance/history.jsonl"
        for index in range(self.performance.HISTORY_LIMIT + 40):
            self.performance.append_history(path, dict(family="android-release", toolchain="tool",
                cache="cold", workers=4, parallel=True, dropped=False, elapsed_seconds=float(index),
                queue_seconds=0.1, exit_code=0, eligible=True, pressure=False, index=index))
        rows = self.history_rows()
        self.assertEqual(self.performance.HISTORY_LIMIT, len(rows))
        self.assertEqual(self.performance.HISTORY_LIMIT + 39, rows[-1]["index"], "newest rows must be kept")

    def test_plan_reports_the_decision_without_running_the_build(self):
        marker = self.root / "ran.txt"
        fixture = self.root / "marker.py"
        fixture.write_text(f"open({str(marker)!r},'w').write('x')\n", encoding="utf-8")
        result = subprocess.run([sys.executable, str(ENTRY), "plan", "--root", str(self.root),
            "--family", "android-release", "--", sys.executable, str(fixture)],
            capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(marker.exists(), "plan must never execute the build")
        payload = json.loads(result.stdout)
        self.assertIn("workers", payload)
        self.assertIn("reason", payload)
        self.assertFalse(payload["injected"])

    def test_history_command_redacts_and_reports_stored_rows(self):
        self.invoke(0)
        result = subprocess.run([sys.executable, str(ENTRY), "history", "--root", str(self.root)],
                                capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("android-release", result.stdout)
        self.assertNotIn("fake-gradle.py", result.stdout)

    def test_missing_profiler_has_actionable_instructions_without_download(self):
        result = subprocess.run([sys.executable, str(ENTRY), "profile", "--root", str(self.root),
            "--cli", str(self.root / "missing-profiler"), "--scenario", str(self.root / "scenario.scenarios"),
            "--output", str(self.root / "profile")], capture_output=True, text=True, encoding="utf-8")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("gradle-profiler", result.stderr)
        self.assertIn("MIHON_GRADLE_PROFILER", result.stderr)
        self.assertFalse((self.root / "profile").exists(), "a missing profiler must not be downloaded")

    def write_launcher(self, name, body):
        if sys.platform == "win32":
            path = self.root / f"{name}.cmd"
            path.write_text(f'@echo off\r\n"{sys.executable}" "{body}" %*\r\n', encoding="utf-8")
        else:
            path = self.root / name
            path.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{body}" "$@"\n', encoding="utf-8")
            path.chmod(0o755)
        return path

    def test_profile_runs_and_summarises_when_the_profiler_is_available(self):
        script = self.root / "fake-profiler.py"
        script.write_text(
            "import pathlib,sys\n"
            "args=sys.argv[1:]\n"
            "out=pathlib.Path(args[args.index('--output')+1])\n"
            "out.mkdir(parents=True,exist_ok=True)\n"
            "(out/'profile.html').write_text('profile',encoding='utf-8')\n",
            encoding="utf-8")
        launcher = self.write_launcher("fake-profiler", script)
        scenario = self.root / "scenario.scenarios"
        scenario.write_bytes("﻿mihon-android-release\norg.gradle.parallel=true\n".encode("utf-8"))
        output = self.root / "profile"

        result = subprocess.run([sys.executable, str(ENTRY), "profile", "--root", str(self.root),
            "--cli", str(launcher), "--scenario", str(scenario), "--output", str(output),
            "--name", "android-release"], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((output / "profile.html").exists())
        summary = json.loads((self.root / ".gradle-coordinator/performance/profile/android-release.json")
                             .read_text(encoding="utf-8"))
        self.assertEqual(0, summary["exit_code"])
        self.assertGreaterEqual(summary["elapsed_seconds"], 0)
        self.assertEqual("mihon-android-release", summary["scenario"], "a BOM must not leak into the scenario name")

    def test_profiler_failure_is_reported_and_summarised(self):
        script = self.root / "failing-profiler.py"
        script.write_text("import sys\nprint('boom',file=sys.stderr)\nsys.exit(3)\n", encoding="utf-8")
        launcher = self.write_launcher("failing-profiler", script)
        scenario = self.root / "scenario.scenarios"
        scenario.write_text("mihon-android-release\n", encoding="utf-8")
        output = self.root / "profile"

        result = subprocess.run([sys.executable, str(ENTRY), "profile", "--root", str(self.root),
            "--cli", str(launcher), "--scenario", str(scenario), "--output", str(output),
            "--name", "android-release"], capture_output=True, text=True, encoding="utf-8")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("boom", result.stderr)
        summary = json.loads((self.root / ".gradle-coordinator/performance/profile/android-release.json")
                             .read_text(encoding="utf-8"))
        self.assertEqual(3, summary["exit_code"])


if __name__ == "__main__":
    unittest.main()
