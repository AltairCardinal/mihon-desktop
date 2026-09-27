"""Android entry contract: real SDK APK parsing/signing, isolated filesystem fixtures."""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from types import SimpleNamespace
from unittest.mock import patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENTRY = ROOT / "scripts/build-android.py"
SPEC = importlib.util.spec_from_file_location("build_android", ENTRY)
ANDROID = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ANDROID)


class AndroidEntryTest(unittest.TestCase):
    def test_uninstalled_package_with_pm_path_exit_one_is_a_safe_new_install(self):
        tools = SimpleNamespace(adb=Path("unused-adb"))
        calls = []

        def transport(command, **kwargs):
            calls.append(command[3:])
            if command[3:6] == ["shell", "pm", "path"]:
                raise ValueError("Command failed (1): pm path found no package")
            self.assertEqual(command[3:], ["shell", "pm", "list", "packages", "app.mihon.desktop.fork"])
            return subprocess.CompletedProcess(command, 0, "package:app.mihon.desktop.fork.dev\n", "")

        with patch.object(ANDROID, "run", side_effect=transport):
            self.assertIsNone(ANDROID.installed_apk(tools, "test-serial", "app.mihon.desktop.fork", Path("unused")))
        self.assertEqual(len(calls), 1)

    def test_package_query_failure_is_never_treated_as_uninstalled(self):
        tools = SimpleNamespace(adb=Path("unused-adb"))
        with patch.object(ANDROID, "run", side_effect=ValueError("Command failed (1): device offline")):
            with self.assertRaisesRegex(ValueError, "device offline"):
                ANDROID.installed_apk(tools, "test-serial", "app.mihon.desktop.fork", Path("unused"))
        malformed = subprocess.CompletedProcess([], 0, "Error: package manager unavailable\n", "")
        with patch.object(ANDROID, "run", return_value=malformed):
            with self.assertRaisesRegex(ValueError, "invalid package listing"):
                ANDROID.installed_apk(tools, "test-serial", "app.mihon.desktop.fork", Path("unused"))
        listed = subprocess.CompletedProcess([], 0, "package:app.mihon.desktop.fork\n", "")
        empty = subprocess.CompletedProcess([], 0, "", "")
        with patch.object(ANDROID, "run", side_effect=[listed, empty]):
            with self.assertRaisesRegex(ValueError, "no readable APK path"):
                ANDROID.installed_apk(tools, "test-serial", "app.mihon.desktop.fork", Path("unused"))

    def test_help_exposes_explicit_actions(self):
        result = subprocess.run([sys.executable, str(ENTRY), "--help"], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stderr)
        for name in ["check", "debug", "candidate", "verify", "install"]:
            self.assertIn(name, result.stdout)

    def test_install_requires_explicit_serial(self):
        result = subprocess.run([sys.executable, str(ENTRY), "install", "--artifact", "absent.apk"], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 2)
        self.assertIn("--serial", result.stderr)

    def test_source_snapshot_tracks_dirty_and_untracked_inputs_but_not_docs(self):
        with tempfile.TemporaryDirectory(prefix="android-source-contract-") as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            shutil.copyfile(ROOT / "scripts/task15-build-provenance.py", root / "scripts/task15-build-provenance.py")
            (root / "app/src/main").mkdir(parents=True)
            source = root / "app/src/main/fixture.txt"
            source.write_text("original", encoding="utf-8")
            ANDROID.run(["git", "init", "--quiet"], cwd=root)
            ANDROID.run(["git", "add", "."], cwd=root)
            ANDROID.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture"], cwd=root)
            original = ANDROID.source_snapshot(root)
            (root / "docs").mkdir()
            (root / "docs/note.md").write_text("documentation only", encoding="utf-8")
            self.assertEqual(original, ANDROID.source_snapshot(root))
            source.write_text("modified", encoding="utf-8")
            modified = ANDROID.source_snapshot(root)
            self.assertNotEqual(original["productionInputsSha256"], modified["productionInputsSha256"])
            self.assertNotEqual(original["sourceDiffSha256"], modified["sourceDiffSha256"])
            (root / "gradle").mkdir()
            (root / "gradle/new.properties").write_text("new=input", encoding="utf-8")
            self.assertNotEqual(modified["untrackedInputsSha256"], ANDROID.source_snapshot(root)["untrackedInputsSha256"])


class AndroidSdkArtifactTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="mihon-apk-contract-")
        cls.root = Path(cls.temp.name)
        cls.tools = ANDROID.AndroidTools()
        cls.release = ANDROID.metadata()
        cls.key = cls.root / "fixture.p12"
        cls.keytool = Path(os.environ["JAVA_HOME"]) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")
        cls.previous_password = os.environ.get("MIHON_TEST_KEY_PASSWORD")
        os.environ["MIHON_TEST_KEY_PASSWORD"] = "isolated-test-password"
        ANDROID.run([cls.keytool, "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", "-genkeypair", "-keystore", cls.key, "-alias", "fixture", "-storetype", "PKCS12", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=Isolated Android Test", "-storepass:env", "MIHON_TEST_KEY_PASSWORD", "-noprompt"])
        cls.unsigned = cls.make_apk("fixture", cls.release["applicationId"])
        cls.signed = cls.root / "signed.apk"
        ANDROID.run([cls.tools.signer, "sign", "--ks", cls.key, "--ks-key-alias", "fixture", "--ks-pass", "env:MIHON_TEST_KEY_PASSWORD", "--out", cls.signed, cls.unsigned])
        cls.actual = ANDROID.inspect_apk(cls.signed, cls.tools)
        cls.release = {**cls.release, "releaseCertificateSha256": cls.actual["certificateSha256"]}
        (cls.root / "gradle").mkdir()
        (cls.root / "gradle/android-release.properties").write_text("".join(f"{key}={value}\n" for key, value in cls.release.items()), encoding="utf-8")

    @classmethod
    def make_apk(cls, name, identity, version=None, debug=False):
        manifest = cls.root / f"{name}.xml"
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{identity}" android:versionCode="{version if version is not None else cls.release['versionCode']}" android:versionName="{cls.release['versionName']}"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/><application android:hasCode="false" android:debuggable="{str(debug).lower()}" android:label="{name}"/></manifest>''', encoding="utf-8")
        raw = cls.root / f"{name}-raw.apk"
        ANDROID.run([cls.tools.aapt, "link", "-I", cls.tools.jar, "--manifest", manifest, "--min-sdk-version", "26", "--target-sdk-version", "36", "-o", raw])
        with zipfile.ZipFile(raw, "a") as archive:
            for abi in ANDROID.ABIS:
                archive.writestr(f"lib/{abi}/libfixture.so", b"fixture-not-for-installation")
        output = cls.root / f"{name}-unsigned.apk"
        ANDROID.run([cls.tools.align, "-f", "-p", "4", raw, output])
        return output

    @classmethod
    def tearDownClass(cls):
        if cls.previous_password is None:
            os.environ.pop("MIHON_TEST_KEY_PASSWORD", None)
        else:
            os.environ["MIHON_TEST_KEY_PASSWORD"] = cls.previous_password
        cls.temp.cleanup()

    def write_record(self, variant="release", apk=None):
        apk = apk or self.signed
        actual = ANDROID.inspect_apk(apk, self.tools, signed=variant != "unsigned")
        record = {**actual, "variant": variant, "apk": apk.name,
                  "sourceRevision": "a" * 40, "sourceDiffSha256": "b" * 64,
                  "productionInputsSha256": "c" * 64, "untrackedInputsSha256": "d" * 64,
                  "r8": variant != "debug", "resourceShrinking": variant != "debug", "telemetry": False, "updater": False}
        (self.root / "mapping").mkdir(exist_ok=True)
        mapping = self.root / "mapping/mapping.txt"
        mapping.write_text("fixture mapping", encoding="utf-8")
        record["mapping"] = {"path": "mapping/mapping.txt", "sha256": ANDROID.sha256(mapping)}
        (self.root / "artifact.json").write_text(json.dumps(record), encoding="utf-8")
        return record

    def test_sdk_reads_real_apk_and_verifies_signature(self):
        self.write_record()
        result = ANDROID.verify_artifact(self.signed, self.tools, self.root)
        self.assertEqual(result["versionCode"], self.release["versionCode"])
        self.assertEqual(set(result["abis"]), ANDROID.ABIS)
        self.assertTrue(result["signatureVerified"])

    def test_formal_trust_root_is_not_replaceable_by_artifact_metadata(self):
        self.write_record()
        # Repository public release metadata remains authoritative, even if a record blesses a test key.
        with self.assertRaisesRegex(ValueError, "established release certificate"):
            ANDROID.verify_artifact(self.signed, self.tools, ROOT)

    def test_wrong_identity_version_unsigned_and_tampered_apk_are_rejected(self):
        wrong = ANDROID.inspect_apk(self.make_apk("wrong-id", "app.mihon"), self.tools, signed=False)
        with self.assertRaisesRegex(ValueError, "identity"):
            ANDROID.enforce_identity(wrong, self.release, self.tools.config, "release")
        with self.assertRaisesRegex(ValueError, "version"):
            old = ANDROID.inspect_apk(self.make_apk("old-version", self.release["applicationId"], 1), self.tools, signed=False)
            ANDROID.enforce_identity(old, self.release, self.tools.config, "unsigned", current_version=True)
        with self.assertRaisesRegex(ValueError, "signature"):
            ANDROID.inspect_apk(self.unsigned, self.tools)
        record = self.write_record()
        record["versionCode"] += 1
        (self.root / "artifact.json").write_text(json.dumps(record), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "disagrees"):
            ANDROID.verify_artifact(self.signed, self.tools, self.root)
        tampered = self.root / "tampered.apk"
        tampered.write_bytes(self.signed.read_bytes() + b"tampered")
        self.write_record()
        record = {**self.write_record(), "apk": tampered.name}
        (self.root / "artifact.json").write_text(json.dumps(record), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "hash"):
            ANDROID.verify_artifact(tampered, self.tools, self.root)

    def test_unsigned_install_never_calls_adb(self):
        self.write_record("unsigned", self.unsigned)
        with patch.object(ANDROID, "adb_command") as adb:
            with self.assertRaisesRegex(ValueError, "Unsigned"):
                ANDROID.install_artifact(self.unsigned, "isolated-serial", self.tools, self.root)
            adb.assert_not_called()

    def test_install_transport_uses_explicit_serial_and_verifies_pulled_apk(self):
        calls = self.install_with_transport(None, twice=True)
        self.assertEqual(sum(args[0] == "install" for args in calls), 1)

    def install_with_transport(self, previous, *, twice=False, after=None, expected_error=None, candidate=None, variant="release"):
        candidate = candidate or self.signed
        self.write_record(variant, candidate)
        calls = []
        installed = previous
        real_run = ANDROID.run

        def transport(command, **kwargs):
            nonlocal installed
            if command[0] != self.tools.adb:
                return real_run(command, **kwargs)
            self.assertEqual(command[1:3], ["-s", "isolated-serial"])
            args = command[3:]
            calls.append(args)
            if args == ["get-state"]:
                text = "device\n"
            elif args[:2] == ["shell", "getprop"]:
                text = "36\n" if args[2].endswith("sdk") else "arm64-v8a\n"
            elif args[:4] == ["shell", "pm", "list", "packages"]:
                text = f"package:{args[4]}\n" if installed else ""
            elif args[:3] == ["shell", "pm", "path"]:
                if not installed:
                    raise ValueError("Command failed (1): pm path found no package")
                text = "package:/data/app/fixture/base.apk\n"
            elif args[0] == "pull":
                Path(args[2]).write_bytes(Path(installed).read_bytes())
                text = "1 file pulled"
            elif args[:2] == ["install", "-r"]:
                installed = after or candidate
                text = "Success\n"
            else:
                raise AssertionError(f"Unexpected device command: {args}")
            return subprocess.CompletedProcess(command, 0, text, "")

        with patch.object(ANDROID, "run", side_effect=transport):
            if expected_error:
                with self.assertRaisesRegex(ValueError, expected_error):
                    ANDROID.install_artifact(candidate, "isolated-serial", self.tools, self.root)
            else:
                ANDROID.install_artifact(candidate, "isolated-serial", self.tools, self.root)
                if twice:
                    ANDROID.install_artifact(candidate, "isolated-serial", self.tools, self.root)
        return calls

    def sign_fixture(self, name, version, alias="fixture"):
        unsigned = self.make_apk(name, self.release["applicationId"], version)
        signed = self.root / f"{name}-signed.apk"
        ANDROID.run([self.tools.signer, "sign", "--ks", self.key, "--ks-key-alias", alias, "--ks-pass", "env:MIHON_TEST_KEY_PASSWORD", "--out", signed, unsigned])
        return signed

    def test_upgrade_rejects_same_version_different_binary_and_wrong_installed_certificate(self):
        old = self.sign_fixture("older", self.release["versionCode"] - 1)
        calls = self.install_with_transport(old)
        self.assertEqual(sum(args[0] == "install" for args in calls), 1)

        same = self.sign_fixture("different-binary", self.release["versionCode"])
        calls = self.install_with_transport(same, expected_error="strictly greater")
        self.assertFalse(any(args[0] == "install" for args in calls))
        ANDROID.run([self.keytool, "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", "-genkeypair", "-keystore", self.key, "-alias", "other", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=Other Isolated Test", "-storepass:env", "MIHON_TEST_KEY_PASSWORD", "-noprompt"])
        wrong = self.sign_fixture("wrong-certificate", self.release["versionCode"] - 1, "other")
        calls = self.install_with_transport(wrong, expected_error="certificate differs")
        self.assertFalse(any(args[0] == "install" for args in calls))
        calls = self.install_with_transport(None, after=old, expected_error="does not match")
        self.assertEqual(sum(args[0] == "install" for args in calls), 1)

    def test_debug_can_replace_same_code_without_consuming_formal_version(self):
        signed = []
        for name in ["debug-before", "debug-after"]:
            unsigned = self.make_apk(name, self.release["applicationId"] + ".dev", debug=True)
            output = self.root / f"{name}-signed.apk"
            ANDROID.run([self.tools.signer, "sign", "--ks", self.key, "--ks-key-alias", "fixture", "--ks-pass", "env:MIHON_TEST_KEY_PASSWORD", "--out", output, unsigned])
            signed.append(output)
        calls = self.install_with_transport(signed[0], candidate=signed[1], variant="debug")
        self.assertEqual(sum(args[0] == "install" for args in calls), 1)

    def test_mapping_and_build_declarations_cannot_be_tampered(self):
        record = self.write_record()
        record["telemetry"] = True
        (self.root / "artifact.json").write_text(json.dumps(record), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "baseline"):
            ANDROID.verify_artifact(self.signed, self.tools, self.root)
        self.write_record()
        (self.root / "mapping/mapping.txt").write_text("modified", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "mapping"):
            ANDROID.verify_artifact(self.signed, self.tools, self.root)

    @unittest.skipUnless(os.name == "nt", "DPAPI signing adapter is Windows-only")
    def test_missing_signing_credentials_are_not_replaced(self):
        absent = self.root / "missing-signing-directory"
        result = ANDROID.run(["powershell.exe", "-NoProfile", "-File", ROOT / "scripts/sign-android-fork-release.ps1", "-CheckOnly", "-SigningDirectory", absent], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Release key is missing", result.stderr)
        self.assertFalse(absent.exists())

    @unittest.skipUnless(os.name == "nt", "Windows DPAPI signing adapter")
    def test_powershell_signing_finishes_without_get_file_hash(self):
        signing = self.root / "ps-signing"
        signing.mkdir()
        ANDROID.run([self.keytool, "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", "-importkeystore", "-srckeystore", self.key,
                     "-srcalias", "fixture", "-srcstorepass:env", "MIHON_TEST_KEY_PASSWORD",
                     "-destkeystore", signing / "release.p12", "-deststoretype", "PKCS12",
                     "-destalias", "mihon-desktop-fork", "-deststorepass:env", "MIHON_TEST_KEY_PASSWORD",
                     "-noprompt"])
        scripts = self.root / "scripts"
        scripts.mkdir(exist_ok=True)
        (scripts / "sign-android-fork-release.ps1").write_bytes((ROOT / "scripts/sign-android-fork-release.ps1").read_bytes())
        launcher = self.root / "signing-probe.ps1"
        launcher.write_text('''param([string]$BuildTools)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
function Get-FileHash { throw 'Get-FileHash unavailable in this host' }
$secret = [Security.SecureString]::new()
foreach ($character in $env:MIHON_TEST_KEY_PASSWORD.ToCharArray()) { $secret.AppendChar($character) }
$secret | Export-Clixml -LiteralPath (Join-Path $PSScriptRoot 'ps-signing/password.dpapi.xml')
$secret.Dispose()
& (Join-Path $PSScriptRoot 'scripts/sign-android-fork-release.ps1') `
    -InputApk (Join-Path $PSScriptRoot 'fixture-unsigned.apk') `
    -OutputApk (Join-Path $PSScriptRoot 'ps-signed.apk') `
    -SigningDirectory (Join-Path $PSScriptRoot 'ps-signing') -BuildTools $BuildTools
''', encoding="utf-8")
        result = ANDROID.run(["powershell.exe", "-NoProfile", "-File", launcher,
                              "-BuildTools", self.tools.build_tools])
        output = self.root / "ps-signed.apk"
        actual = ANDROID.inspect_apk(output, self.tools)
        self.assertIn(f"SHA256: {actual['sha256']}", result.stdout)
        self.assertEqual(actual["certificateSha256"], self.release["releaseCertificateSha256"])

    def test_successful_empty_build_does_not_adopt_stale_receipt(self):
        reports = self.root / "app/build/reports"
        reports.mkdir(parents=True, exist_ok=True)
        (reports / "android-candidate.json").write_text(json.dumps({"requestId": "stale", "sourceInputs": "f" * 64, "variant": "release", "apkSha256": self.actual["sha256"]}), encoding="utf-8")
        with patch.object(ANDROID.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)):
            with self.assertRaisesRegex(ValueError, "no matching evidence"):
                ANDROID.gradle_build("release", "f" * 64, root=self.root)

    def test_debug_repeats_without_allocating_release_versions_and_changed_inputs_fail(self):
        args = SimpleNamespace(action="debug", offline=True)
        snapshot = {"sourceRevision": "e" * 40, "productionInputsSha256": "f" * 64}
        with patch.object(ANDROID, "source_snapshot", return_value=snapshot), patch.object(ANDROID, "java_version", return_value="21"), patch.object(ANDROID, "gradle_build", side_effect=ValueError("fixture stop")) as build:
            for _ in range(2):
                with self.assertRaisesRegex(ValueError, "fixture stop"):
                    ANDROID.build_candidate(args, self.tools, self.root)
            self.assertEqual(build.call_count, 2)
        self.assertEqual(len(list((self.root / "app/artifacts/android").glob("*-debug-*"))), 2)
        with patch.object(ANDROID, "source_snapshot", side_effect=[snapshot, {**snapshot, "productionInputsSha256": "0" * 64}]), patch.object(ANDROID, "java_version", return_value="21"), patch.object(ANDROID, "gradle_build", return_value={}):
            with self.assertRaisesRegex(ValueError, "Source inputs changed"):
                ANDROID.build_candidate(args, self.tools, self.root)
        self.assertEqual(ANDROID.metadata(self.root)["versionCode"], self.release["versionCode"])

    def test_formal_code_reservation_is_independent_of_display_name(self):
        artifact_root = self.root / "app/artifacts/android"
        artifact_root.mkdir(parents=True, exist_ok=True)
        (artifact_root / f"other-name-vc{self.release['versionCode']}-oldhash-release").mkdir()
        args = SimpleNamespace(action="candidate", unsigned=False, offline=True)
        snapshot = {"sourceRevision": "e" * 40, "productionInputsSha256": "f" * 64}
        with patch.object(ANDROID, "source_snapshot", return_value=snapshot), patch.object(ANDROID, "java_version", return_value="21"), patch.object(ANDROID, "signing_preflight"), patch.object(ANDROID, "gradle_build") as build:
            with self.assertRaisesRegex(ValueError, "already reserved"):
                ANDROID.build_candidate(args, self.tools, self.root)
            build.assert_not_called()

    def test_failed_build_cannot_adopt_existing_apk(self):
        args = SimpleNamespace(action="candidate", unsigned=True, offline=True)
        snapshot = {"sourceRevision": "e" * 40, "productionInputsSha256": "f" * 64}
        with patch.object(ANDROID, "source_snapshot", return_value=snapshot), patch.object(ANDROID, "java_version", return_value="21"), patch.object(ANDROID, "gradle_build", side_effect=ValueError("Gradle failed")):
            with self.assertRaisesRegex(ValueError, "Gradle failed"):
                ANDROID.build_candidate(args, self.tools, self.root)
        self.assertFalse(list((self.root / "app/artifacts/android").rglob("artifact.json")))
        with patch.object(ANDROID, "source_snapshot", return_value=snapshot), patch.object(ANDROID, "java_version", return_value="21"), patch.object(ANDROID, "gradle_build") as build:
            with self.assertRaises(FileExistsError):
                ANDROID.build_candidate(args, self.tools, self.root)
            build.assert_not_called()


if __name__ == "__main__":
    unittest.main()
