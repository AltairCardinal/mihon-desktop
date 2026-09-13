from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATOR = REPO_ROOT / "scripts" / "aex00-generate-external-v15-fixture.ps1"
DEFAULT_OUTPUT = REPO_ROOT / "app-desktop" / "tmp" / "aex00-external-v15"
TMP_ROOT = REPO_ROOT / "app-desktop" / "tmp"


class Aex00FixtureGeneratorTest(unittest.TestCase):
    @staticmethod
    def utf8_environment() -> dict[str, str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PYTHONDONTWRITEBYTECODE": "1",
                "PYTHONUTF8": "1",
                "PYTHONIOENCODING": "utf-8",
                "ANDROID_HOME": "D:\\Android\\Sdk",
                "ANDROID_SDK_ROOT": "D:\\Android\\Sdk",
            }
        )
        return environment

    def setUp(self) -> None:
        self.spaced_output = Path(
            tempfile.mkdtemp(prefix="aex00-generator-test-", suffix=" spaced", dir=TMP_ROOT)
        )
        self.spaced_relative_output = self.spaced_output.relative_to(REPO_ROOT)
        # Reuse the already fixed source snapshot so this behavior test does
        # not turn network availability into a second fixture prerequisite.
        shutil.copytree(DEFAULT_OUTPUT / "upstream", self.spaced_output / "upstream")

    def tearDown(self) -> None:
        if not hasattr(self, "spaced_output") or not self.spaced_output.exists():
            return
        resolved = self.spaced_output.resolve()
        tmp_root = TMP_ROOT.resolve()
        if tmp_root not in resolved.parents:
            raise RuntimeError(f"拒绝清理临时目录边界外路径：{resolved}")
        shutil.rmtree(resolved)

    def run_generator(self, output: str | None = None) -> subprocess.CompletedProcess[str]:
        command = ["pwsh", "-NoProfile", "-File", str(GENERATOR), "-ApiVersion", "v15"]
        if output is not None:
            command.extend(["-OutputDirectory", output])
        return subprocess.run(
            command,
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
            check=False,
        )

    @staticmethod
    def read_manifest(output: Path) -> dict[str, object]:
        return json.loads(
            (output / "fixture-manifest.json").read_text(encoding="utf-8-sig")
        )

    def run_replay(self, command: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["pwsh", "-NoProfile", "-Command", f"& {{ {command} }}"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
            check=False,
        )

    def test_default_and_spaced_outputs_have_replayable_same_directory_commands(self) -> None:
        default_generated = self.run_generator()
        self.assertEqual(0, default_generated.returncode, default_generated.stderr)
        spaced_generated = self.run_generator(str(self.spaced_relative_output))
        self.assertEqual(0, spaced_generated.returncode, spaced_generated.stderr)

        default_manifest = self.read_manifest(DEFAULT_OUTPUT)
        spaced_manifest = self.read_manifest(self.spaced_output)
        for manifest in (default_manifest, spaced_manifest):
            generate_command = manifest["build"]["sequence"][0].split(": ", 1)[1]
            generated = self.run_replay(generate_command)
            self.assertEqual(0, generated.returncode, generated.stderr or generated.stdout)

        # Read both manifests after replaying their own generated command.
        default_manifest = self.read_manifest(DEFAULT_OUTPUT)
        spaced_manifest = self.read_manifest(self.spaced_output)
        for output, manifest in (
            (DEFAULT_OUTPUT, default_manifest),
            (self.spaced_output, spaced_manifest),
        ):
            fixture = manifest["fixture"]
            build = manifest["build"]
            self.assertIn("foreground", fixture["testCommand"])
            self.assertIn("foreground", fixture["exportCommand"])
            self.assertIn(str(output), fixture["testCommand"])
            self.assertIn(str(output), fixture["exportCommand"])
            self.assertTrue(
                any("junit-platform-launcher" in dependency for dependency in build["dependencies"])
            )
            relative_output = (
                str(self.spaced_relative_output)
                if output == self.spaced_output
                else "app-desktop/tmp/aex00-external-v15"
            )
            self.assertIn(
                f'-OutputDirectory "{relative_output}"',
                manifest["build"]["sequence"][0],
            )
            self.assertIn("-FixtureProject", manifest["build"]["sequence"][4])

        # Execute both generated command forms through the new foreground
        # coordinator.  This proves the paths are replayable, not just strings
        # that happen to pass JSON assertions.
        default_test = self.run_replay(default_manifest["fixture"]["testCommand"])
        self.assertEqual(0, default_test.returncode, default_test.stderr or default_test.stdout)
        spaced_export = self.run_replay(spaced_manifest["fixture"]["exportCommand"])
        self.assertEqual(0, spaced_export.returncode, spaced_export.stderr or spaced_export.stdout)

        package_output = self.spaced_output / "build" / "aex00-generator-test-v15.apk"
        package_command = (
            "pwsh -NoProfile -File scripts/aex00-package-controlled-v16-apk.ps1 "
            f'-ApiVersion v15 -FixtureProject "{self.spaced_relative_output}" '
            f'-OutputPath "{package_output.relative_to(REPO_ROOT)}"'
        )
        packaged = self.run_replay(package_command)
        self.assertEqual(0, packaged.returncode, packaged.stderr or packaged.stdout)
        self.assertTrue(package_output.is_file())
        metadata = subprocess.run(
            [
                str(Path("D:/Android/Sdk/build-tools/36.0.0/aapt2.exe")),
                "dump",
                "xmltree",
                str(package_output),
                "--file",
                "AndroidManifest.xml",
            ],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=self.utf8_environment(),
            check=False,
        )
        self.assertEqual(0, metadata.returncode, metadata.stderr or metadata.stdout)
        self.assertIn("aex00.external.v15.controlled", metadata.stdout)
        self.assertIn("1.5", metadata.stdout)


if __name__ == "__main__":
    unittest.main()
