#!/usr/bin/env python3
"""Exercise CI routing with real commits and the production command line."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ci-scope.py"


class ScopeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "CI Test")
        self.git("config", "user.email", "ci-test@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.write("app/src/Reader.kt", "original product\n")
        self.write("README.md", "original documentation\n")
        self.base = self.commit()

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.repo, check=True, capture_output=True,
            text=True, encoding="utf-8",
        ).stdout.strip()

    def write(self, name, content="change\n"):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def run_scope(self, event="push", payload=None):
        event_path = self.root / "event.json"
        event_path.write_text(json.dumps(payload or {
            "before": self.base, "after": self.git("rev-parse", "HEAD"),
        }), encoding="utf-8")
        output = self.root / "output"
        summary = self.root / "summary"
        output.write_text("existing=value\n", encoding="utf-8")
        summary.write_text("", encoding="utf-8")
        env = {
            **os.environ,
            "GITHUB_EVENT_NAME": event,
            "GITHUB_EVENT_PATH": str(event_path),
            "GITHUB_OUTPUT": str(output),
            "GITHUB_STEP_SUMMARY": str(summary),
            "PYTHONUTF8": "1",
            "PYTHONIOENCODING": "utf-8",
            "PYTHONDONTWRITEBYTECODE": "1",
        }
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], cwd=self.repo, env=env,
            capture_output=True, text=True, encoding="utf-8",
        )
        values = dict(line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines())
        self.assertEqual(values["existing"], "value")
        return result, values, summary.read_text(encoding="utf-8")

    def test_documentation_and_website_changes_skip_product(self):
        for name in [
            "website/package-lock.json", "website/.vitepress/config.mts",
            "docs/中文 名称.md", "README.zh-CN.md", "AGENTS.md",
            ".github/pull_request_template.md", ".github/workflows/pages.yml",
        ]:
            self.write(name)
        self.commit()
        result, values, summary = self.run_scope()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["needs_product"], "false")
        self.assertEqual(values["scope"], "docs")
        self.assertIn("Product tests are not run", summary)

    def test_exact_ci_control_files_run_routing_checks_only(self):
        for name in [
            ".github/workflows/build.yml", ".github/workflows/desktop-ci.yml",
            "scripts/ci-scope.py", "scripts/tests/ci-scope-test.py",
        ]:
            self.write(name)
        self.commit()
        result, values, _ = self.run_scope()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["needs_product"], "false")
        self.assertEqual(values["scope"], "ci")

    def test_product_mixed_or_unknown_paths_require_product(self):
        for name in [
            "app/src/Reader.kt", "gradle/libs.versions.toml", "build.gradle.kts",
            "scripts/build-android.py", ".github/workflows/unknown.yml",
            "app/README.md", "unknown.txt",
        ]:
            with self.subTest(path=name):
                self.base = self.git("rev-parse", "HEAD")
                self.write(name, "new product\n")
                self.write("docs/readme.md", name)
                self.commit()
                result, values, _ = self.run_scope()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(values["needs_product"], "true")

    def test_deleted_product_is_not_hidden_by_rename_to_docs(self):
        self.git("mv", "app/src/Reader.kt", "README-reader.md")
        self.commit()
        result, values, _ = self.run_scope()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["needs_product"], "true")

    def test_deleted_documentation_skips_product(self):
        (self.repo / "README.md").unlink()
        self.commit()
        result, values, _ = self.run_scope()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["needs_product"], "false")

    def test_pull_request_uses_merge_base_not_base_tip_difference(self):
        self.git("checkout", "-qb", "feature")
        self.write("docs/feature.md")
        head = self.commit()
        self.git("checkout", "--detach", self.base)
        self.write("app/src/Reader.kt", "unrelated change on base\n")
        base_tip = self.commit()
        result, values, _ = self.run_scope("pull_request", {
            "pull_request": {"base": {"sha": base_tip}, "head": {"sha": head}},
        })
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["needs_product"], "false")

    def test_push_compares_both_tips(self):
        self.write("app/src/Reader.kt", "changed product\n")
        before = self.commit()
        self.write("app/src/Reader.kt", "original product\n")
        after = self.commit()
        result, values, _ = self.run_scope("push", {"before": before, "after": after})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["needs_product"], "true")

    def test_manual_and_unknown_events_always_require_product(self):
        for event in ["workflow_dispatch", "schedule", "pull_request_target", ""]:
            with self.subTest(event=event):
                result, values, _ = self.run_scope(event)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(values["needs_product"], "true")

    def test_zero_before_requires_product(self):
        result, values, _ = self.run_scope("push", {"before": "0" * 40, "after": self.base})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["needs_product"], "true")

    def test_invalid_or_missing_refs_fail_closed(self):
        for before in [None, "", "--name-only", "f" * 40]:
            with self.subTest(before=before):
                result, values, _ = self.run_scope("push", {"before": before, "after": self.base})
                self.assertNotEqual(result.returncode, 0)
                self.assertNotEqual(values.get("needs_product"), "false")

    def test_empty_diff_is_documentation_only(self):
        result, values, _ = self.run_scope()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values["needs_product"], "false")

    def test_pull_request_without_common_history_fails_closed(self):
        self.git("checkout", "--orphan", "unrelated")
        self.write("docs/unrelated.md")
        unrelated = self.commit()
        result, values, _ = self.run_scope("pull_request", {
            "pull_request": {"base": {"sha": self.base}, "head": {"sha": unrelated}},
        })
        self.assertNotEqual(result.returncode, 0)
        self.assertNotEqual(values.get("needs_product"), "false")


if __name__ == "__main__":
    unittest.main(verbosity=2)
