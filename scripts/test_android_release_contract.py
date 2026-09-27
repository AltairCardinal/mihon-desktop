"""Focused real-AGP regression tests; no APK compilation or device operations."""
import os
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class AndroidReleaseContractTest(unittest.TestCase):
    def run_contract(self, key, args=(), error=None):
        result = subprocess.run(
            [
                sys.executable, "scripts/gradle-coordinator.py", "run",
                "--key", f"android-contract-{key}", "--",
                "gradlew.bat" if os.name == "nt" else "./gradlew",
                "--offline", "--no-parallel", "--max-workers=2",
                *args, "-I", "scripts/android-release-contract.init.gradle",
                ":app:verifyAndroidReleaseContract",
            ],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
        )
        log = (ROOT / ".gradle-coordinator" / f"android-contract-{key}.log").read_text(encoding="utf-8")
        if error:
            self.assertNotEqual(result.returncode, 0, log)
            self.assertIn(error, log)
        else:
            self.assertEqual(result.returncode, 0, log)
            self.assertIn("ANDROID_RELEASE_CONTRACT_OK", log)

    def test_default_and_compatibility(self):
        self.run_contract("default")
        self.run_contract("compat", ["-I", "scripts/android-fork-release.init.gradle"])

    def test_isolated_identities(self):
        for key, script, identity in [
            ("aex05", "aex05-upgrade-identity", "aex05"),
            ("eis", "eis-android-acceptance", "eis"),
            ("sync", "sync-android-acceptance", "syncacceptance"),
        ]:
            with self.subTest(key=key):
                self.run_contract(key, ["-I", f"scripts/{script}.init.gradle", f"-Pcontract.identity=app.mihon.{identity}"])

    def test_forbidden_release_flags(self):
        for flag in ["include-telemetry", "enable-updater", "disable-code-shrink"]:
            with self.subTest(flag=flag):
                self.run_contract(flag, [f"-P{flag}"], "Fork baseline requires R8/resource shrinking and disables telemetry/updater")

    def test_multiple_identity_scripts_are_rejected(self):
        self.run_contract("mixed", ["-I", "scripts/aex05-upgrade-identity.init.gradle", "-I", "scripts/sync-android-acceptance.init.gradle"], "Do not combine Android identity scripts")

    def test_merged_manifests(self):
        result = subprocess.run(
            [sys.executable, "scripts/gradle-coordinator.py", "run", "--key", "android-contract-manifests", "--",
             "gradlew.bat" if os.name == "nt" else "./gradlew", "--offline", "--no-parallel", "--max-workers=2",
             ":app:processDebugMainManifest", ":app:processReleaseMainManifest", ":app:processDebugAndroidTestManifest"],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        metadata = dict(line.split("=", 1) for line in (ROOT / "gradle/android-release.properties").read_text(encoding="utf-8").splitlines() if line and not line.startswith("#"))
        prefix = "{http://schemas.android.com/apk/res/android}"
        for variant in ["debug", "release"]:
            manifest = ET.parse(ROOT / f"app/build/intermediates/merged_manifest/{variant}/process{variant.title()}MainManifest/AndroidManifest.xml").getroot()
            identity = metadata["applicationId"] + (".dev" if variant == "debug" else "")
            self.assertEqual(manifest.get("package"), identity)
            self.assertEqual(manifest.get(prefix + "versionCode"), metadata["versionCode"])
            authorities = {node.get(prefix + "authorities") for node in manifest.findall("application/provider")}
            self.assertIn(identity + ".provider", authorities)
            self.assertIn(identity + ".shizuku", authorities)
        test_manifest = ET.parse(ROOT / "app/build/intermediates/packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml").getroot()
        self.assertEqual(test_manifest.get("package"), metadata["applicationId"] + ".dev.test")
        self.assertEqual(test_manifest.find("instrumentation").get(prefix + "targetPackage"), metadata["applicationId"] + ".dev")
        release_test = subprocess.run(
            [sys.executable, "scripts/gradle-coordinator.py", "run", "--key", "android-contract-release-test-manifest", "--",
             "gradlew.bat" if os.name == "nt" else "./gradlew", "--offline", "--no-parallel", "--max-workers=2",
             "-Pmihon.testBuildType=release", ":app:processReleaseAndroidTestManifest"],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
        )
        self.assertEqual(release_test.returncode, 0, release_test.stdout + release_test.stderr)
        release_manifest = ET.parse(ROOT / "app/build/intermediates/packaged_manifests/releaseAndroidTest/processReleaseAndroidTestManifest/AndroidManifest.xml").getroot()
        self.assertEqual(release_manifest.get("package"), metadata["applicationId"] + ".test")
        self.assertEqual(release_manifest.find("instrumentation").get(prefix + "targetPackage"), metadata["applicationId"])


if __name__ == "__main__":
    unittest.main()
