"""End-to-end tests for git-delivery.py using isolated local repositories."""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "git-delivery.py"


def run(args, cwd=None, check=True):
    result = subprocess.run(
        args,
        cwd=cwd,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and result.returncode:
        raise AssertionError(f"Command failed: {args}\n{result.stdout}\n{result.stderr}")
    return result


class DeliveryFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.remote = self.root / "remote.git"
        self.other = self.root / "other.git"
        run(["git", "init", "--bare", str(self.remote)])
        run(["git", "init", "--bare", str(self.other)])
        run(["git", "init", "-b", "main", str(self.source)])
        self.git("config", "user.name", "Delivery Test")
        self.git("config", "user.email", "delivery@example.invalid")
        self.write_commit("base")
        self.base = self.head()
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "-u", "origin", "main")

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args, cwd=None, check=True):
        return run(["git", *args], cwd=cwd or self.source, check=check)

    def write_commit(self, name):
        (self.source / "record.txt").write_text(name + "\n", encoding="utf-8")
        self.git("add", "record.txt")
        self.git("commit", "-m", name)

    def head(self):
        return self.git("rev-parse", "HEAD").stdout.strip()

    def remote_head(self):
        result = self.git("ls-remote", "origin", "refs/heads/main")
        return result.stdout.split()[0] if result.stdout.strip() else None

    def cli(self, action, *, expected_head=None, expected_remote=None, allows=(), check=True):
        args = [
            sys.executable,
            str(SCRIPT),
            action,
            "--repo",
            str(self.source),
            "--remote",
            "origin",
            "--branch",
            "main",
            "--expected-head",
            expected_head or self.head(),
            "--expected-remote",
            expected_remote or self.base,
        ]
        for allowed in allows:
            args.extend(["--allow-commit", allowed])
        return run(args, check=check)

    def test_authorized_single_commit_is_planned_and_pushed(self):
        self.write_commit("authorized")
        commit = self.head()
        plan = self.cli("plan", allows=[commit])
        self.assertIn(commit, plan.stdout)
        pushed = self.cli("push", allows=[commit])
        self.assertEqual(self.remote_head(), commit)
        self.assertIn("refs/heads/main", pushed.stdout)

    def test_unapproved_ancestor_is_rejected_and_remote_unchanged(self):
        self.write_commit("approved")
        approved = self.head()
        self.write_commit("extra")
        extra = self.head()
        result = self.cli("push", expected_head=extra, allows=[approved], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(extra, result.stderr + result.stdout)
        self.assertEqual(self.remote_head(), self.base)

    def test_extra_authorization_outside_range_is_rejected(self):
        self.write_commit("authorized")
        commit = self.head()
        result = self.cli("plan", allows=[self.base, commit], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(self.base, result.stderr + result.stdout)
        self.assertEqual(self.remote_head(), self.base)

    def test_duplicate_authorization_is_rejected(self):
        self.write_commit("authorized")
        commit = self.head()
        result = self.cli("plan", allows=[commit, commit], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("duplicate", result.stderr)

    def test_abbreviated_expected_head_is_rejected(self):
        self.write_commit("authorized")
        commit = self.head()
        result = self.cli("plan", expected_head=commit[:12], allows=[commit], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("complete", result.stderr)

    def test_wrong_expected_head_is_rejected(self):
        self.write_commit("one")
        wrong = self.base
        result = self.cli("plan", expected_head=wrong, allows=[wrong], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.remote_head(), self.base)

    def test_remote_advance_after_plan_is_rejected_without_overwrite(self):
        self.write_commit("local")
        local_head = self.head()
        clone = self.root / "clone"
        run(["git", "clone", "-b", "main", str(self.remote), str(clone)])
        run(["git", "-C", str(clone), "config", "user.name", "Other"])
        run(["git", "-C", str(clone), "config", "user.email", "other@example.invalid"])
        (clone / "remote.txt").write_text("advance\n", encoding="utf-8")
        run(["git", "-C", str(clone), "add", "remote.txt"])
        run(["git", "-C", str(clone), "commit", "-m", "remote advance"])
        run(["git", "-C", str(clone), "push", "origin", "main"])
        moved = run(["git", "ls-remote", str(self.remote), "refs/heads/main"]).stdout.split()[0]
        result = self.cli("push", expected_head=local_head, expected_remote=self.base, allows=[local_head], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.remote_head(), moved)

    def test_stale_origin_tracking_ref_does_not_define_remote_state(self):
        self.write_commit("local")
        local_head = self.head()
        self.git("update-ref", "refs/remotes/origin/main", local_head)
        result = self.cli("plan", expected_head=local_head, expected_remote=self.base, allows=[local_head])
        self.assertIn(f"expected-remote: {self.base}", result.stdout)
        self.assertEqual(self.remote_head(), self.base)

    def test_plan_preserves_existing_fetch_head(self):
        self.write_commit("authorized")
        authorized = self.head()
        unrelated_source = self.root / "unrelated-source"
        run(["git", "init", "-b", "main", str(unrelated_source)])
        run(["git", "-C", str(unrelated_source), "config", "user.name", "Unrelated"])
        run(["git", "-C", str(unrelated_source), "config", "user.email", "unrelated@example.invalid"])
        (unrelated_source / "unrelated.txt").write_text("other remote\n", encoding="utf-8")
        run(["git", "-C", str(unrelated_source), "add", "unrelated.txt"])
        run(["git", "-C", str(unrelated_source), "commit", "-m", "unrelated remote"])
        run(["git", "-C", str(unrelated_source), "remote", "add", "origin", str(self.other)])
        run(["git", "-C", str(unrelated_source), "push", "-u", "origin", "main"])
        self.git("remote", "add", "unrelated", str(self.other))
        self.git("fetch", "--no-tags", "unrelated", "refs/heads/main")
        fetch_head = self.source / ".git" / "FETCH_HEAD"
        original = fetch_head.read_bytes()
        self.assertNotIn(authorized.encode("ascii"), original)

        result = self.cli("plan", allows=[authorized])

        self.assertEqual(original, fetch_head.read_bytes())
        self.assertIn("plan: valid", result.stdout)

    def test_mismatched_push_url_is_rejected(self):
        self.write_commit("local")
        local_head = self.head()
        self.git("remote", "set-url", "--push", "origin", str(self.other))
        result = self.cli("push", expected_head=local_head, allows=[local_head], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.remote_head(), self.base)
        self.assertIsNone(run(["git", "ls-remote", str(self.other), "refs/heads/main"]).stdout.strip() or None)

    def test_pre_push_local_branch_advance_does_not_get_pushed(self):
        self.write_commit("authorized")
        authorized = self.head()
        hook = self.source / ".git" / "hooks" / "pre-push"
        hook.write_text(
            "#!/bin/sh\n"
            f"git -C '{self.source}' config user.name Hook\n"
            f"git -C '{self.source}' config user.email hook@example.invalid\n"
            f"echo hook >> '{self.source / 'hook.txt'}'\n"
            f"git -C '{self.source}' add hook.txt\n"
            f"git -C '{self.source}' commit -m hook-advance >/dev/null\n",
            encoding="utf-8",
        )
        hook.chmod(0o755)
        result = self.cli("push", expected_head=authorized, allows=[authorized], check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.remote_head(), authorized)
        self.assertNotEqual(self.head(), authorized)

    def test_remote_fork_during_pre_push_is_rejected_without_overwrite(self):
        self.write_commit("local")
        local_head = self.head()
        clone = self.root / "racer"
        run(["git", "clone", "-b", "main", str(self.remote), str(clone)])
        run(["git", "-C", str(clone), "config", "user.name", "Racer"])
        run(["git", "-C", str(clone), "config", "user.email", "racer@example.invalid"])
        (clone / "racer.txt").write_text("divergent\n", encoding="utf-8")
        run(["git", "-C", str(clone), "add", "racer.txt"])
        run(["git", "-C", str(clone), "commit", "-m", "remote race"])
        racer_head = run(["git", "-C", str(clone), "rev-parse", "HEAD"]).stdout.strip()
        hook = self.source / ".git" / "hooks" / "pre-push"
        hook.write_text(
            "#!/bin/sh\n"
            f"git -C '{clone}' push origin main\n",
            encoding="utf-8",
        )
        hook.chmod(0o755)
        result = self.cli("push", expected_head=local_head, allows=[local_head], check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.remote_head(), racer_head)

    def test_post_push_remote_advance_is_accepted_when_target_is_in_history(self):
        self.write_commit("authorized")
        authorized = self.head()
        tree = self.git("rev-parse", f"{authorized}^{{tree}}").stdout.strip()
        later = run(
            ["git", "-C", str(self.source), "-c", "user.name=Later", "-c", "user.email=later@example.invalid", "commit-tree", tree, "-p", authorized],
        ).stdout.strip()
        hook = self.remote / "hooks" / "post-receive"
        hook.write_text(
            "#!/bin/sh\n"
            f"git --git-dir='{self.remote}' fetch --no-tags '{self.source}' {later}\n"
            f"git --git-dir='{self.remote}' update-ref refs/heads/main {later}\n",
            encoding="utf-8",
        )
        hook.chmod(0o755)
        result = self.cli("push", expected_head=authorized, allows=[authorized], check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.remote_head(), later)
        self.assertIn(f"current remote {later}", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
