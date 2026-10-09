from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BASH = Path(r"C:\Program Files\Git\bin\bash.exe") if sys.platform == "win32" else Path("/bin/bash")


@unittest.skipUnless(sys.platform == "win32" and BASH.is_file(), "Windows build entrypoint")
class WindowsPreviewTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="mihon-preview-")
        self.root = Path(self.temporary.name)
        self.scripts = self.root / "scripts"
        self.scripts.mkdir()
        for name in ("build-desktop.sh", "build-windows.ps1", "publish-windows-unpacked.ps1", "task15-build-provenance.py"):
            shutil.copyfile(REPO / "scripts" / name, self.scripts / name)
        self.version = self.root / "app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt"
        self.version.parent.mkdir(parents=True)
        self.version.write_text("object AppVersion {\n const val STAGE = 1\n const val FEATURE = 2\n const val BUILD = 3\n}\n", encoding="utf-8")
        self.version_before = self.version.read_bytes()
        self.fixture = self.root / "app-desktop/src/test/resources/extensions/real/keiyoushi-manhuagui-1.4.28.apk"
        self.fixture.parent.mkdir(parents=True)
        self.fixture.write_bytes(b"fixture-apk")
        self.formal = self.root / "app-desktop/artifacts/windows/keep.txt"
        self.formal.parent.mkdir(parents=True)
        self.formal.write_text("formal release", encoding="utf-8")
        self.scripts.joinpath("package-windows-distributable.ps1").write_text("throw 'Preview must not archive a formal release'\n", encoding="utf-8")
        self.scripts.joinpath("validate-windows-extension-runtime.ps1").write_text(
            "param($Executable,$ArtifactPath,$PackageName,$DisplayName,$VersionName,$VersionCode,$RepositoryFingerprint,$ArtifactSha256,$ExpectedVersion)\n"
            "$ErrorActionPreference='Stop'\n"
            "@{executable=$Executable;version=$ExpectedVersion} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot '../runtime-call.json')\n"
            "if ($env:FIXTURE_RUNTIME_FAIL) {throw 'fixture runtime failed'}\n", encoding="utf-8")
        self.root.joinpath("gradlew.bat").write_text(
            f'@echo off\n"{sys.executable}" "%~dp0gradle-fixture.py" %*\nexit /b %ERRORLEVEL%\n', encoding="utf-8")
        self.root.joinpath("gradle-fixture.py").write_text(
            "import json,os,sys\nfrom pathlib import Path\n"
            "root=Path(__file__).parent\n"
            "(root/'gradle-call.json').write_text(json.dumps({'args':sys.argv[1:],'dist':os.environ.get('MIHON_WINDOWS_DIST_ROOT')}),encoding='utf-8')\n"
            "if os.environ.get('FIXTURE_GRADLE_FAIL'): sys.exit(7)\n"
            "if os.environ.get('FIXTURE_CHANGE_INPUT'): (root/'app-desktop/src/main/kotlin/mihon/desktop/New.kt').write_text('object New',encoding='utf-8')\n"
            "dist=Path(os.environ.get('MIHON_WINDOWS_DIST_ROOT',root/'app-desktop/tmp/mihon-dist'))\n"
            "app=dist/'main/app/Mihon Desktop'\napp.mkdir(parents=True,exist_ok=True)\n"
            "(app/'Mihon Desktop.exe').write_bytes(b'fixture launcher')\n"
            "if os.environ.get('FIXTURE_OLD_LAUNCHER'): os.utime(app/'Mihon Desktop.exe',(1,1))\n"
            "(app/'app').mkdir(exist_ok=True)\n(app/'runtime').mkdir(exist_ok=True)\n", encoding="utf-8")
        self.root.joinpath(".gitignore").write_text("app-desktop/tmp/\napp-desktop/artifacts/\n*-call.json\n", encoding="utf-8")
        for args in (("init", "-q"), ("add", "."), ("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture")):
            subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_preview(self, **extra: str) -> subprocess.CompletedProcess[str]:
        environment = dict(os.environ, MIHON_HOST_OS="MINGW64_NT", MIHON_PYTHON=sys.executable,
                           MIHON_POWERSHELL_BIN="powershell.exe", PYTHONUTF8="1", PYTHONIOENCODING="utf-8",
                           PYTHONDONTWRITEBYTECODE="1")
        environment.update(extra)
        return subprocess.run([str(BASH), "scripts/build-desktop.sh", "preview"], cwd=self.root, env=environment,
                              text=True, encoding="utf-8", capture_output=True, check=False)

    def manifest(self) -> dict:
        paths = list(self.root.glob("app-desktop/artifacts/preview/windows/*/preview-manifest.json"))
        self.assertEqual(1, len(paths))
        return json.loads(paths[0].read_text(encoding="utf-8"))

    def test_preview_runs_only_isolated_build_without_version_bump(self) -> None:
        result = self.run_preview()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.version_before, self.version.read_bytes())
        call = json.loads(self.root.joinpath("gradle-call.json").read_text(encoding="utf-8"))
        self.assertEqual([":app-desktop:createDistributable"], call["args"])
        self.assertTrue(Path(call["dist"]).is_relative_to(self.root / "app-desktop/tmp/preview"))
        self.assertEqual("formal release", self.formal.read_text(encoding="utf-8"))
        data = self.manifest()
        exe = Path(data["artifactPath"])
        self.assertTrue(exe.is_file())
        self.assertTrue(exe.is_relative_to(self.root / "app-desktop/artifacts/preview/windows"))
        self.assertIn(f"Final unpacked EXE: {exe}", result.stdout)
        self.assertEqual("preview", data["kind"])
        self.assertEqual("PASS", data["build"]["status"])
        self.assertEqual("PASS", data["productionRuntime"]["status"])
        self.assertEqual("NOT_RUN", data["nativeInteraction"]["status"])
        runtime = json.loads(self.root.joinpath("runtime-call.json").read_text(encoding="utf-8-sig"))
        self.assertEqual(data["version"], runtime["version"])

    def test_build_failure_cannot_publish_or_claim_pass(self) -> None:
        result = self.run_preview(FIXTURE_GRADLE_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Final unpacked EXE:", result.stdout)
        self.assertEqual("TOOL_FAIL", self.manifest()["build"]["status"])
        self.assertEqual("NOT_RUN", self.manifest()["productionRuntime"]["status"])
        self.assertFalse(self.root.joinpath("runtime-call.json").exists())
        self.assertEqual(self.version_before, self.version.read_bytes())

    def test_runtime_failure_preserves_build_but_does_not_publish(self) -> None:
        result = self.run_preview(FIXTURE_RUNTIME_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Final unpacked EXE:", result.stdout)
        data = self.manifest()
        self.assertEqual("PASS", data["build"]["status"])
        self.assertEqual("TOOL_FAIL", data["productionRuntime"]["status"])
        self.assertEqual("NOT_RUN", data["nativeInteraction"]["status"])

    def test_manifest_fingerprints_dirty_and_untracked_product_inputs(self) -> None:
        self.version.write_text(self.version.read_text(encoding="utf-8") + "// local input\n", encoding="utf-8")
        extra = self.version.parent / "NewFeature.kt"
        extra.write_text("object NewFeature", encoding="utf-8")
        result = self.run_preview()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        source = self.manifest()["source"]
        self.assertEqual(40, len(source["sourceCommit"]))
        self.assertEqual(64, len(source["diffSha256"]))
        self.assertEqual([extra.relative_to(self.root).as_posix()], [item["path"] for item in source["untrackedInputs"]])
        self.assertTrue(source["dirty"])

    def test_reused_launcher_mtime_is_not_a_preview_failure(self) -> None:
        result = self.run_preview(FIXTURE_OLD_LAUNCHER="1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("PASS", self.manifest()["sourceIntegrity"]["status"])

    def test_changed_inputs_during_build_are_recorded_and_not_published(self) -> None:
        result = self.run_preview(FIXTURE_CHANGE_INPUT="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Final unpacked EXE:", result.stdout)
        self.assertEqual("TOOL_FAIL", self.manifest()["sourceIntegrity"]["status"])

    def test_same_process_restores_distribution_environment_on_success(self) -> None:
        self.assert_same_process_environment_restored(False)

    def test_same_process_restores_distribution_environment_on_failure(self) -> None:
        self.assert_same_process_environment_restored(True)

    def assert_same_process_environment_restored(self, failed: bool) -> None:
        for existing in (False, True):
            with self.subTest(existing=existing, failed=failed):
                wrapper = self.root / "invoke-preview.ps1"
                wrapper.write_text(
                    "$ErrorActionPreference='Stop'\n"
                    + ("$env:MIHON_WINDOWS_DIST_ROOT='original distribution'\n" if existing else "Remove-Item Env:MIHON_WINDOWS_DIST_ROOT -ErrorAction SilentlyContinue\n")
                    + "$failed=$false\ntry { & (Join-Path $PSScriptRoot 'scripts/build-windows.ps1') -Preview } catch { $failed=$true }\n"
                    + "@{present=(Test-Path Env:MIHON_WINDOWS_DIST_ROOT);value=$env:MIHON_WINDOWS_DIST_ROOT;failed=$failed} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'environment-result.json')\n",
                    encoding="utf-8",
                )
                environment = dict(os.environ, MIHON_PYTHON=sys.executable, PYTHONUTF8="1", PYTHONIOENCODING="utf-8")
                if failed:
                    environment["FIXTURE_GRADLE_FAIL"] = "1"
                result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)],
                                        cwd=self.root, env=environment, capture_output=True, text=True, encoding="utf-8")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                record = json.loads(self.root.joinpath("environment-result.json").read_text(encoding="utf-8-sig"))
                self.assertEqual(failed, record["failed"])
                self.assertEqual(existing, record["present"])
                self.assertEqual("original distribution" if existing else None, record["value"])


@unittest.skipUnless(sys.platform == "darwin", "macOS preview dispatch and APFS output")
class MacPreviewTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mihon-preview-contract-")
        self.root = Path(self.temporary.name).resolve()
        (self.root / "scripts").mkdir()
        for name in ("build-desktop.sh", "task15-build-provenance.py"):
            shutil.copyfile(REPO / "scripts" / name, self.root / "scripts" / name)
        self.version = self.root / "app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt"
        self.version.parent.mkdir(parents=True)
        self.version.write_text("object AppVersion {\n const val STAGE = 1\n const val FEATURE = 2\n const val BUILD = 3\n}\n", encoding="utf-8")
        self.before = self.version.read_bytes()
        self.source_tmp = self.root / "source-temporary"
        self.source_tmp.mkdir()
        self.root.joinpath(".gitignore").write_text("app-desktop/artifacts/\ngradle-call.json\n", encoding="utf-8")
        self.root.joinpath("gradlew").write_text(
            f"#!{sys.executable}\n"
            "import json,os,sys\nfrom pathlib import Path\n"
            "root=Path(__file__).parent\ndist=Path(os.environ['MIHON_MACOS_DIST_ROOT'])\n"
            "assert str(dist).startswith('/private/tmp/mihon-preview-dist.')\n"
            "(root/'gradle-call.json').write_text(json.dumps({'args':sys.argv[1:],'dist':str(dist)}),encoding='utf-8')\n"
            "if os.environ.get('FIXTURE_GRADLE_FAIL'):sys.exit(7)\n"
            "app=dist/'main/app/Mihon Desktop.app'\napp.mkdir(parents=True)\n(app/'fixture-marker').write_text('new',encoding='utf-8')\n", encoding="utf-8")
        self.root.joinpath("gradlew").chmod(0o755)
        for args in (("init", "-q"), ("add", "."), ("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture")):
            subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)

    def tearDown(self):
        call = self.root / "gradle-call.json"
        if call.exists():
            dist = Path(json.loads(call.read_text(encoding="utf-8"))["dist"])
            if dist.exists() and str(dist).startswith("/private/tmp/mihon-preview-dist.") and dist.parent == Path("/private/tmp"):
                shutil.rmtree(dist)
        self.temporary.cleanup()

    def run_preview(self, **extra):
        return subprocess.run(["/bin/bash", "scripts/build-desktop.sh", "preview"], cwd=self.root,
                              env=dict(os.environ, TMPDIR=str(self.source_tmp), PYTHONUTF8="1", PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1", **extra),
                              capture_output=True, text=True, encoding="utf-8", check=False)

    def manifest(self):
        paths = list(self.root.glob("app-desktop/artifacts/preview/macos/*/preview-manifest.json"))
        self.assertEqual(1, len(paths))
        return json.loads(paths[0].read_text(encoding="utf-8"))

    def test_preview_isolated_no_tests_no_allocation_no_native_pass(self):
        result = self.run_preview()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        call = json.loads(self.root.joinpath("gradle-call.json").read_text(encoding="utf-8"))
        self.assertEqual([":app-desktop:createDistributable"], call["args"])
        self.assertEqual(self.before, self.version.read_bytes())
        data = self.manifest()
        app = Path(data["artifactPath"])
        self.assertTrue(str(app).startswith(str(self.root / "app-desktop/artifacts/preview/macos")))
        self.assertTrue((app / "fixture-marker").is_file())
        self.assertEqual("PASS", data["build"]["status"])
        self.assertEqual("NOT_RUN", data["productionRuntime"]["status"])
        self.assertEqual("NOT_RUN", data["nativeInteraction"]["status"])
        self.assertIn(f"Final macOS app: {app}", result.stdout)

    def test_success_removes_owned_dist_and_provenance_temporary_files(self):
        result = self.run_preview()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        call = json.loads(self.root.joinpath("gradle-call.json").read_text(encoding="utf-8"))
        self.assertFalse(Path(call["dist"]).exists())
        self.assertEqual([], list(self.source_tmp.glob("mihon-preview-source.*")))
        self.assertIn("Preview manifest:", result.stdout)
        self.assertTrue(Path(self.manifest()["artifactPath"]).exists())

    def test_failed_build_retains_only_owned_dist_and_reports_exact_location(self):
        result = self.run_preview(FIXTURE_GRADLE_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        call = json.loads(self.root.joinpath("gradle-call.json").read_text(encoding="utf-8"))
        self.assertTrue(Path(call["dist"]).is_dir())
        self.assertIn(f"Preview distribution retained: {call['dist']}", result.stdout)
        self.assertEqual([], list(self.source_tmp.glob("mihon-preview-source.*")))

    def test_failed_build_does_not_deploy_or_claim_pass(self):
        result = self.run_preview(FIXTURE_GRADLE_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Final macOS app:", result.stdout)
        self.assertEqual("TOOL_FAIL", self.manifest()["build"]["status"])
        self.assertEqual(self.before, self.version.read_bytes())


if __name__ == "__main__":
    unittest.main()
