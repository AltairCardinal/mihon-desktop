from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(sys.platform == "darwin", "Requires macOS shell and sed semantics")
class MacosBuildIsolationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="mihon-build-isolation-")
        self.root = Path(self.temporary.name).resolve()
        self.repo = self.root / "repo"
        self.scripts = self.repo / "scripts"
        self.scripts.mkdir(parents=True)
        shutil.copyfile(REPO_ROOT / "scripts/build-desktop.sh", self.scripts / "build-desktop.sh")
        self.version = self.repo / "app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt"
        self.version.parent.mkdir(parents=True)
        self.version.write_text(
            "object AppVersion {\n    const val STAGE = 1\n    const val FEATURE = 2\n"
            "    const val BUILD = 3\n}\n",
            encoding="utf-8",
        )
        self.initial_version = self.version.read_text(encoding="utf-8")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.write_executable(self.bin / "git", "#!/bin/bash\nprintf '123abcd\\n'\n")
        # The destructive boundary is guarded even while testing the old script (RED).
        for command in ("rm", "cp"):
            self.write_executable(
                self.bin / command,
                f"#!{sys.executable}\n"
                "import os, pathlib, sys\n"
                "root = pathlib.Path(os.environ['FIXTURE_ROOT']).resolve()\n"
                "for value in sys.argv[1:]:\n"
                "    if value.startswith('-'):\n"
                "        continue\n"
                "    path = pathlib.Path(value).resolve()\n"
                "    if path != root and root not in path.parents:\n"
                "        sys.exit('Fixture rejected external filesystem operation: ' + value)\n"
                f"os.execv('/bin/{command}', ['{command}', *sys.argv[1:]])\n",
            )
        self.write_executable(
            self.repo / "gradlew",
            f"#!{sys.executable}\n"
            + textwrap.dedent(
                """\
                import os, pathlib, sys
                root = pathlib.Path(os.environ['FIXTURE_ROOT'])
                with (root / 'gradle-calls').open('a', encoding='utf-8') as output:
                    output.write(' '.join(sys.argv[1:]) + '\\n')
                if ':app-desktop:createDistributable' in sys.argv:
                    app = pathlib.Path(os.environ['MIHON_MACOS_DIST_ROOT']) / 'main/app/Mihon Desktop.app'
                    if root not in app.resolve().parents:
                        sys.exit('Fixture rejected external Gradle output')
                    app.mkdir(parents=True, exist_ok=True)
                    (app / 'built-marker').write_text('new release', encoding='utf-8')
                """,
            ),
        )
        self.dist = self.root / "isolated distribution"
        self.deploy = self.root / "release" / "Mihon Desktop.app"
        self.deploy.mkdir(parents=True)
        (self.deploy / "old-marker").write_text("old isolated release", encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def write_executable(path: Path, source: str) -> None:
        path.write_text(source, encoding="utf-8")
        path.chmod(0o755)

    def run_build(self, dist: str | None = None, deploy: str | None = None) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": str(self.bin) + os.pathsep + environment["PATH"],
                "FIXTURE_ROOT": str(self.root),
                "MIHON_HOST_OS": "Darwin",
                "MIHON_MACOS_DIST_ROOT": str(self.dist) if dist is None else dist,
                "MIHON_MACOS_DEPLOY_DIR": str(self.deploy) if deploy is None else deploy,
                "PYTHONUTF8": "1",
                "PYTHONIOENCODING": "utf-8",
                "PYTHONDONTWRITEBYTECODE": "1",
            },
        )
        return subprocess.run(
            ["/bin/bash", str(self.scripts / "build-desktop.sh"), "build-only"],
            cwd=self.repo,
            env=environment,
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=False,
        )

    def test_build_deploys_only_to_explicit_paths_with_spaces(self) -> None:
        result = self.run_build()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("new release", (self.deploy / "built-marker").read_text(encoding="utf-8"))
        self.assertFalse((self.deploy / "old-marker").exists())
        self.assertEqual(":app-desktop:createDistributable\n", (self.root / "gradle-calls").read_text(encoding="utf-8"))
        self.assertIn(f"Final macOS app: {self.deploy}", result.stdout)

    def test_rejects_unsafe_paths_before_version_allocation_or_gradle(self) -> None:
        cases = [
            ("relative/output", str(self.deploy)),
            (str(self.dist), "relative/Mihon.app"),
            ("/", str(self.deploy)),
            ("/Applications", str(self.deploy)),
            (str(self.root.parent), str(self.deploy)),
            (str(self.dist), "/"),
            (str(self.dist), str(self.root / "release")),
            (str(self.dist), str(self.dist / "nested.app")),
            (str(self.deploy / "nested"), str(self.deploy)),
            (str(self.repo), str(self.deploy)),
        ]
        for dist, deploy in cases:
            with self.subTest(dist=dist, deploy=deploy):
                result = self.run_build(dist, deploy)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Unsafe macOS build paths", result.stderr)
                self.assertFalse((self.root / "gradle-calls").exists())
                self.assertEqual(self.initial_version, self.version.read_text(encoding="utf-8"))
                self.assertTrue((self.deploy / "old-marker").exists())

    def test_rejects_symlink_that_resolves_to_overlapping_destination(self) -> None:
        self.dist.symlink_to(self.deploy, target_is_directory=True)
        result = self.run_build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unsafe macOS build paths", result.stderr)
        self.assertFalse((self.root / "gradle-calls").exists())
        self.assertEqual(self.initial_version, self.version.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
