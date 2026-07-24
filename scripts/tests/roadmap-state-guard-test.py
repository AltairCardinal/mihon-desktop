from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
GUARD = REPO_ROOT / "scripts" / "roadmap-state-guard.py"


class RoadmapStateGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.parent = self.root / "parent.md"
        self.execution = self.root / "execution.md"
        self.child = self.root / "child.md"
        self.parent.write_text(
            "---\nactive-child-plan: execution.md\n---\n"
            "# Parent\n- [ ] Task 6: final audit\n",
            encoding="utf-8",
        )
        self.execution.write_text(
            "---\nactive-task: Task 17\n---\n"
            "# Execution\n- [x] Task 16: audit\n- [ ] Task 17: product closure\n",
            encoding="utf-8",
        )
        self.child.write_text(
            "---\nparent-plan: execution.md\nstatus: planned\n---\n"
            "# Child\n- [x] Task 141: done\n- [ ] Task 142: pending\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def run_guard(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(GUARD),
                "--parent",
                str(self.parent),
                "--execution",
                str(self.execution),
                "--child",
                str(self.child),
            ],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_valid_single_active_task_authority_passes(self) -> None:
        result = self.run_guard()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("PASS", result.stdout)

    def test_child_active_task_is_rejected(self) -> None:
        self.child.write_text(
            "---\nparent-plan: execution.md\nstatus: planned\nactive-task: Task 142\n---\n"
            "# Child\n- [x] Task 141: done\n- [ ] Task 142: pending\n",
            encoding="utf-8",
        )
        result = self.run_guard()
        self.assertEqual(1, result.returncode)
        self.assertIn("child plan must not define active-task", result.stderr)

    def test_execution_active_task_must_be_first_unchecked_task(self) -> None:
        self.execution.write_text(
            "---\nactive-task: Task 18\n---\n"
            "# Execution\n- [x] Task 16: audit\n- [ ] Task 17: product closure\n"
            "- [ ] Task 18: final gate\n",
            encoding="utf-8",
        )
        result = self.run_guard()
        self.assertEqual(1, result.returncode)
        self.assertIn("first unchecked Task 17", result.stderr)


if __name__ == "__main__":
    unittest.main()
