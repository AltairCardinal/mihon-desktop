from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

SCRIPT = Path(__file__).resolve().parents[1] / "acceptance-preflight.py"
sys.path.insert(0, str(SCRIPT.parent))


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mihon-preflight-")
        self.root = Path(self.temporary.name)
        self.artifact = self.root / "candidate.apk"
        with zipfile.ZipFile(self.artifact, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture")
            archive.writestr("classes.dex", b"fixture")
        self.calls = self.root / "calls.jsonl"
        self.adb = self.root / "fake-adb.py"
        self.adb.write_text(
            "import json,os,sys\nfrom pathlib import Path\n"
            "args=sys.argv[1:]\n"
            "with Path(os.environ['PREFLIGHT_CALLS']).open('a',encoding='utf-8') as f:f.write(json.dumps(args)+'\\n')\n"
            "if os.environ.get('PREFLIGHT_TOOL_FAIL'):sys.exit(2)\n"
            "if args==['devices','-l']: print('List of devices attached\\nfixture-device '+('offline' if os.environ.get('PREFLIGHT_OFFLINE') else 'device')+' product:fixture')\n"
            "elif args[-2:]==['getprop','ro.build.version.sdk']:print('36')\n"
            "elif args[-2:]==['getprop','ro.product.cpu.abi']:print('x86_64')\n"
            "elif args[-2:]==['dumpsys','window']:print('unknown' if os.environ.get('PREFLIGHT_UNKNOWN_LOCK') else 'mShowingLockscreen='+('true' if os.environ.get('PREFLIGHT_LOCKED') else 'false'))\n"
            "elif args[-2:]==['dumpsys','power']:print(os.environ.get('PREFLIGHT_POWER','mInteractive=true'))\n"
            "elif args[-3:]==['dumpsys','activity','activities']:print('mResumedActivity: ActivityRecord{ fixture.launcher/.Main }')\n"
            "else:sys.exit('Forbidden or unknown read-only command')\n", encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def run_preflight(self, serial="fixture-device", artifact=None, **extra):
        environment = dict(os.environ, PREFLIGHT_CALLS=str(self.calls), PYTHONUTF8="1", PYTHONIOENCODING="utf-8", **extra)
        return subprocess.run([sys.executable, str(SCRIPT), "--platform", "android", "--artifact", str(artifact or self.artifact),
                               "--adb", str(self.adb), "--serial", serial], env=environment, text=True, encoding="utf-8",
                              capture_output=True, check=False)

    def payload(self, result):
        self.assertTrue(result.stdout, result.stderr)
        return json.loads(result.stdout)

    def test_android_ready_is_read_only_and_does_not_claim_native_acceptance(self):
        result = self.run_preflight()
        data = self.payload(result)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PASS", data["status"])
        self.assertEqual("NOT_RUN", data["nativeInteraction"]["status"])
        self.assertEqual("read-only-preflight", data["scope"])
        self.assertNotIn("fixture-device", result.stdout)
        calls = [json.loads(line) for line in self.calls.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["devices", "-l"], calls[0])
        self.assertTrue(all(call[:3] == ["-s", "fixture-device", "shell"] for call in calls[1:]))

    def test_missing_artifact_stops_before_device_probe(self):
        data = self.payload(self.run_preflight(artifact=self.root / "missing.apk"))
        self.assertEqual("ENV_BLOCKED", data["status"])
        self.assertFalse(self.calls.exists())

    def test_wrong_device_stops_before_shell(self):
        data = self.payload(self.run_preflight(serial="wrong-device"))
        self.assertEqual("ENV_BLOCKED", data["status"])
        self.assertEqual(1, len(self.calls.read_text(encoding="utf-8").splitlines()))

    def test_offline_device_and_locked_screen_are_environment_blocks(self):
        for extra in ({"PREFLIGHT_OFFLINE": "1"}, {"PREFLIGHT_LOCKED": "1"}):
            with self.subTest(extra=extra):
                data = self.payload(self.run_preflight(**extra))
                self.assertEqual("ENV_BLOCKED", data["status"])
                self.assertEqual("NOT_RUN", data["nativeInteraction"]["status"])

    def test_tool_error_is_distinct_from_environment_block(self):
        data = self.payload(self.run_preflight(PREFLIGHT_TOOL_FAIL="1"))
        self.assertEqual("TOOL_FAIL", data["status"])

    def test_malformed_candidate_is_not_a_pass(self):
        self.artifact.write_bytes(b"not an apk")
        data = self.payload(self.run_preflight())
        self.assertEqual("ENV_BLOCKED", data["status"])
        self.assertFalse(self.calls.exists())

    def test_unknown_session_evidence_stays_not_run(self):
        data = self.payload(self.run_preflight(PREFLIGHT_UNKNOWN_LOCK="1"))
        self.assertEqual("NOT_RUN", data["status"])

    def test_explicit_awake_without_minteractive_continues_read_only_probe(self):
        result = self.run_preflight(PREFLIGHT_POWER="mWakefulness=Awake\nmWakefulnessChanging=false")
        data = self.payload(result)
        self.assertEqual("PASS", data["status"])
        self.assertEqual("NOT_RUN", data["nativeInteraction"]["status"])
        calls = [json.loads(line) for line in self.calls.read_text(encoding="utf-8").splitlines()]
        self.assertIn(["-s", "fixture-device", "shell", "dumpsys", "activity", "activities"], calls)

    def test_sleep_and_conflicting_power_metadata_never_pass(self):
        for power in ("mWakefulness=Asleep", "mWakefulness=Dozing",
                      "mInteractive=true\nmWakefulness=Asleep", "mInteractive=false\nmWakefulness=Awake"):
            with self.subTest(power=power):
                data = self.payload(self.run_preflight(PREFLIGHT_POWER=power))
                self.assertEqual("ENV_BLOCKED", data["status"])

    def test_unknown_or_changing_power_metadata_stays_not_run(self):
        for power in ("unknown", "mWakefulness=Unknown", "mInteractive=true\nmWakefulness=Unknown",
                      "mWakefulness=Awake\nmWakefulnessChanging=true"):
            with self.subTest(power=power):
                data = self.payload(self.run_preflight(PREFLIGHT_POWER=power))
                self.assertEqual("NOT_RUN", data["status"])

    def test_manifest_target_mismatch_stops_before_device_probe(self):
        self.root.joinpath("preview-manifest.json").write_text(json.dumps({"kind": "preview", "platform": "android", "artifactPath": str(self.root / "different.apk")}), encoding="utf-8")
        data = self.payload(self.run_preflight())
        self.assertEqual("ENV_BLOCKED", data["status"])
        self.assertFalse(self.calls.exists())

    def test_failed_preview_manifest_blocks_candidate(self):
        self.root.joinpath("preview-manifest.json").write_text(json.dumps({
            "kind": "preview", "platform": "android", "artifactPath": str(self.artifact),
            "build": {"status": "PASS"}, "sourceIntegrity": {"status": "TOOL_FAIL"},
        }), encoding="utf-8")
        data = self.payload(self.run_preflight())
        self.assertEqual("ENV_BLOCKED", data["status"])
        self.assertFalse(self.calls.exists())

    def test_foreground_mismatch_is_environment_block(self):
        environment = dict(os.environ, PREFLIGHT_CALLS=str(self.calls), PYTHONUTF8="1", PYTHONIOENCODING="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPT), "--platform", "android", "--artifact", str(self.artifact),
                                 "--adb", str(self.adb), "--serial", "fixture-device", "--package", "different.app"],
                                env=environment, text=True, encoding="utf-8", capture_output=True)
        self.assertEqual("ENV_BLOCKED", self.payload(result)["status"])

    def test_wrong_host_is_blocked_for_packaged_mac_candidate(self):
        if sys.platform == "darwin":
            self.skipTest("This is a wrong-host test")
        app = self.root / "Candidate.app"
        executable = app / "Contents/MacOS/Candidate"
        executable.parent.mkdir(parents=True)
        executable.write_bytes(b"fixture")
        import plistlib
        (app / "Contents/Info.plist").write_bytes(plistlib.dumps({"CFBundleExecutable": "Candidate"}))
        result = subprocess.run([sys.executable, str(SCRIPT), "--platform", "macos", "--artifact", str(app)],
                                text=True, encoding="utf-8", capture_output=True)
        self.assertEqual("ENV_BLOCKED", self.payload(result)["status"])


class MacSessionPreflightTest(unittest.TestCase):
    def test_shared_session_failure_preserves_preflight_json_result(self):
        spec = importlib.util.spec_from_file_location("acceptance_preflight_failure", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        probe = module.Preflight(SimpleNamespace(platform='macos'))
        probe.artifact = lambda: True
        with patch.object(module.sys, 'platform', 'darwin'), patch.object(module, 'observe_session', side_effect=module.SessionFailure('TOOL_FAIL', 'metadata unavailable')):
            result = probe.run()
        self.assertEqual('TOOL_FAIL', result['status'])
        self.assertEqual('NOT_RUN', result['nativeInteraction']['status'])
        self.assertEqual('read-only-preflight', result['scope'])

    def probe(self, *, asleep, locked, online=True):
        spec = importlib.util.spec_from_file_location("acceptance_preflight_under_test", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cg = SimpleNamespace(
            CGMainDisplayID=Mock(return_value=42),
            CGDisplayIsOnline=Mock(return_value=online),
            CGDisplayIsAsleep=Mock(return_value=asleep),
            CGSessionCopyCurrentDictionary=Mock(return_value=1),
            CGPreflightPostEventAccess=Mock(return_value=True),
        )
        cf = SimpleNamespace(
            CFStringCreateWithCString=Mock(return_value=2),
            CFDictionaryGetValue=Mock(return_value=3 if locked is not None else None),
            CFGetTypeID=Mock(return_value=7), CFBooleanGetTypeID=Mock(return_value=7),
            CFBooleanGetValue=Mock(return_value=locked), CFRelease=Mock(),
        )
        result = module.Preflight(SimpleNamespace())
        with patch.object(module.sys, "platform", "darwin"), patch.object(module.ctypes, "CDLL", side_effect=[cg, cf]):
            result.macos()
        return {item["layer"]: item for item in result.checks}

    def test_sleeping_display_does_not_prove_an_unlock_is_required(self):
        for locked in (True, False, None):
            with self.subTest(locked=locked):
                checks = self.probe(asleep=True, locked=locked)
                self.assertEqual("NOT_RUN", checks["graphical-session"]["status"])
                self.assertEqual(locked, checks["graphical-session"]["reportedLockFlag"])
                self.assertEqual("ENV_BLOCKED", checks["main-display"]["status"])
                self.assertTrue(checks["main-display"]["asleep"])
                self.assertEqual("PASS", checks["native-event-permission"]["status"])

    def test_awake_display_with_lock_signal_still_blocks_native_acceptance(self):
        checks = self.probe(asleep=False, locked=True)
        self.assertEqual("ENV_BLOCKED", checks["graphical-session"]["status"])
        self.assertEqual("PASS", checks["main-display"]["status"])

    def test_awake_lock_absence_is_not_silently_treated_as_unlocked(self):
        for locked, status in ((False, "PASS"), (None, "NOT_RUN")):
            with self.subTest(locked=locked):
                checks = self.probe(asleep=False, locked=locked)
                self.assertEqual(status, checks["graphical-session"]["status"])
                self.assertEqual("PASS", checks["main-display"]["status"])

    def test_offline_display_does_not_prove_a_lock_state(self):
        checks = self.probe(asleep=False, locked=True, online=False)
        self.assertEqual("NOT_RUN", checks["graphical-session"]["status"])
        self.assertEqual("ENV_BLOCKED", checks["main-display"]["status"])


class WindowsDisplayTopologyPreflightTest(unittest.TestCase):
    def probe(self, *, active_capacity, mode_capacity, error_code=0):
        spec = importlib.util.spec_from_file_location("windows_preflight_under_test", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        def topology(flags, active, modes):
            self.assertEqual(2, flags)
            module.ctypes.cast(active, module.ctypes.POINTER(module.ctypes.c_uint32)).contents.value = active_capacity
            module.ctypes.cast(modes, module.ctypes.POINTER(module.ctypes.c_uint32)).contents.value = mode_capacity
            return error_code
        def desktop_name(desktop, index, name, size, required):
            self.assertEqual(0x100000001, desktop)
            name.value = 'Default'
            return True
        api = SimpleNamespace(GetDisplayConfigBufferSizes=Mock(side_effect=topology),
                              OpenInputDesktop=Mock(return_value=0x100000001),
                              GetUserObjectInformationW=Mock(side_effect=desktop_name), CloseDesktop=Mock())
        preflight = module.Preflight(SimpleNamespace(platform='windows'))
        preflight.artifact = lambda: True
        with patch.object(module.sys, 'platform', 'win32'), patch.object(module.ctypes, 'WinDLL', return_value=api, create=True):
            result = preflight.run()
        api.CloseDesktop.assert_called_once_with(0x100000001)
        return result, {item['layer']: item for item in result['checks']}, api, module

    def test_successful_zero_active_capacity_stays_not_run_with_independent_next_step(self):
        result, checks, api, module = self.probe(active_capacity=0, mode_capacity=0)
        display = checks['display-topology']
        self.assertEqual('NOT_RUN', display['status'])
        self.assertEqual('NO_ACTIVE_DISPLAY_PATH', display['reasonCode'])
        self.assertEqual(0, display['activePathCapacity'])
        self.assertTrue(display['next'])
        self.assertEqual('NOT_RUN', checks['native-tool']['status'])
        self.assertEqual('NOT_RUN', result['nativeInteraction']['status'])
        api.GetDisplayConfigBufferSizes.assert_called_once()
        self.assertEqual(module.ctypes.c_int32, api.GetDisplayConfigBufferSizes.restype)

    def test_positive_capacity_is_metadata_only_not_native_pass(self):
        result, checks, _, _ = self.probe(active_capacity=2, mode_capacity=4)
        display = checks['display-topology']
        self.assertEqual('PASS', display['status'])
        self.assertEqual(2, display['activePathCapacity'])
        self.assertEqual(4, display['modeCapacity'])
        self.assertEqual('NOT_RUN', result['status'])
        self.assertEqual('NOT_RUN', checks['native-tool']['status'])
        self.assertEqual('NOT_RUN', result['nativeInteraction']['status'])

    def test_api_failure_keeps_native_error_and_never_reports_zero_as_missing_display(self):
        result, checks, _, _ = self.probe(active_capacity=0, mode_capacity=0, error_code=87)
        display = checks['display-topology']
        self.assertEqual('TOOL_FAIL', display['status'])
        self.assertEqual(87, display['errorCode'])
        self.assertNotIn('activePathCapacity', display)
        self.assertNotEqual('NO_ACTIVE_DISPLAY_PATH', display.get('reasonCode'))
        self.assertEqual('TOOL_FAIL', result['status'])
        self.assertEqual('NOT_RUN', checks['native-tool']['status'])


if __name__ == "__main__":
    unittest.main()
