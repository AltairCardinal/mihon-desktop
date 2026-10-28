#!/usr/bin/env python3
"""Review and safely push an explicitly authorized commit interval."""

import argparse
import re
import subprocess
import sys
import uuid
from pathlib import Path


class DeliveryError(Exception):
    pass


def git(repo, *args, check=True):
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and result.returncode:
        detail = result.stderr.strip() or result.stdout.strip()
        raise DeliveryError(f"git {' '.join(args)} failed: {detail}")
    return result


def full_oid(value, label):
    if not re.fullmatch(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})", value):
        raise DeliveryError(f"{label} must be a complete 40- or 64-character commit OID")
    return value.lower()


def verify_commit(repo, oid, label):
    oid = full_oid(oid, label)
    resolved = resolve_commit(repo, oid, label)
    if resolved != oid:
        raise DeliveryError(f"{label} does not resolve to the exact supplied commit OID: {oid}")
    return oid


def resolve_commit(repo, revision, label):
    result = git(repo, "rev-parse", "--verify", f"{revision}^{{commit}}")
    resolved = result.stdout.strip().lower()
    return full_oid(resolved, label)


def resolve_remote(repo, remote):
    fetch_urls = git(repo, "remote", "get-url", "--all", remote).stdout.splitlines()
    push_urls = git(repo, "remote", "get-url", "--push", "--all", remote, check=False)
    if push_urls.returncode:
        raise DeliveryError(f"cannot resolve push URL for remote {remote!r}")
    push_urls = push_urls.stdout.splitlines()
    if len(fetch_urls) != 1 or not fetch_urls[0].strip():
        raise DeliveryError(f"remote {remote!r} must have exactly one fetch URL")
    if len(push_urls) != 1 or not push_urls[0].strip():
        raise DeliveryError(f"remote {remote!r} must have exactly one push URL")
    if fetch_urls[0] != push_urls[0]:
        raise DeliveryError(
            f"remote fetch and push URLs differ; refusing unverified push target "
            f"(fetch={fetch_urls[0]!r}, push={push_urls[0]!r})"
        )
    return fetch_urls[0]


def read_remote_head(repo, url, ref):
    result = git(repo, "ls-remote", "--exit-code", url, ref, check=False)
    if result.returncode:
        raise DeliveryError(f"cannot read actual remote ref {ref}: {result.stderr.strip()}")
    rows = [line.split() for line in result.stdout.splitlines() if line.strip()]
    if len(rows) != 1 or len(rows[0]) != 2 or rows[0][1] != ref:
        raise DeliveryError(f"remote ref {ref} is missing or ambiguous")
    return full_oid(rows[0][0], "remote ref OID")


def fetch_remote_branch(repo, url, ref):
    fetched_ref = f"refs/codex/git-delivery/{uuid.uuid4().hex}"
    try:
        git(repo, "fetch", "--no-tags", "--no-write-fetch-head", url, f"{ref}:{fetched_ref}")
        return resolve_commit(repo, fetched_ref, "fetched remote ref")
    finally:
        git(repo, "update-ref", "-d", fetched_ref, check=False)


def require_ancestor(repo, ancestor, descendant, message):
    result = git(repo, "merge-base", "--is-ancestor", ancestor, descendant, check=False)
    if result.returncode:
        raise DeliveryError(message)


def inspect(args):
    repo = Path(args.repo).resolve()
    if not repo.is_dir():
        raise DeliveryError(f"repository directory does not exist: {repo}")
    if not git(repo, "rev-parse", "--is-inside-work-tree").stdout.strip() == "true":
        raise DeliveryError(f"not a Git worktree: {repo}")

    branch_ref = f"refs/heads/{args.branch}"
    if git(repo, "check-ref-format", branch_ref, check=False).returncode:
        raise DeliveryError(f"invalid branch name: {args.branch!r}")
    symbolic = git(repo, "symbolic-ref", "--quiet", "HEAD", check=False)
    if symbolic.returncode:
        raise DeliveryError("HEAD is detached; refusing delivery")
    if symbolic.stdout.strip() != branch_ref:
        raise DeliveryError(f"checked-out branch is {symbolic.stdout.strip()}, expected {branch_ref}")

    expected_head = verify_commit(repo, args.expected_head, "--expected-head")
    expected_remote = full_oid(args.expected_remote, "--expected-remote")
    actual_head = resolve_commit(repo, "HEAD", "HEAD")
    if actual_head != expected_head:
        raise DeliveryError(f"HEAD mismatch: expected {expected_head}, actual {actual_head}")

    url = resolve_remote(repo, args.remote)
    actual_remote = read_remote_head(repo, url, branch_ref)
    if actual_remote != expected_remote:
        raise DeliveryError(
            f"remote mismatch for {branch_ref}: expected {expected_remote}, actual {actual_remote}"
        )
    fetched_remote = fetch_remote_branch(repo, url, branch_ref)
    if fetched_remote != actual_remote:
        raise DeliveryError(
            f"remote changed while fetching {branch_ref}: ls-remote={actual_remote}, fetch={fetched_remote}"
        )
    expected_remote = verify_commit(repo, expected_remote, "--expected-remote")
    require_ancestor(repo, expected_remote, expected_head, "expected remote commit is not an ancestor of expected HEAD")

    commits = git(repo, "rev-list", "--reverse", f"{expected_remote}..{expected_head}").stdout.splitlines()
    commits = [commit.lower() for commit in commits]
    allowed = [verify_commit(repo, oid, "--allow-commit") for oid in args.allow_commit]
    if len(set(allowed)) != len(allowed):
        raise DeliveryError("--allow-commit contains a duplicate OID")
    missing = [commit for commit in commits if commit not in set(allowed)]
    extra = [commit for commit in allowed if commit not in set(commits)]
    if missing or extra:
        lines = ["authorized commit set must exactly match expectedRemote..expectedHead"]
        if missing:
            lines.append("unapproved commits in push range: " + ", ".join(missing))
        if extra:
            lines.append("authorized commits outside push range: " + ", ".join(extra))
        raise DeliveryError("\n".join(lines))

    subjects = {}
    for commit in commits:
        subjects[commit] = git(repo, "show", "-s", "--format=%s", commit).stdout.strip()
    return repo, url, branch_ref, expected_remote, expected_head, commits, subjects


def render_plan(url, ref, base, head, commits, subjects):
    print(f"remote-url: {url}")
    print(f"target-ref: {ref}")
    print(f"expected-remote: {base}")
    print(f"expected-head: {head}")
    print(f"authorized commits ({len(commits)}):")
    for commit in commits:
        print(f"  {commit} {subjects[commit]}")


def deliver(args):
    repo, url, ref, base, head, commits, subjects = inspect(args)
    render_plan(url, ref, base, head, commits, subjects)
    if args.action == "plan":
        print("plan: valid; no push performed")
        return

    before_push = read_remote_head(repo, url, ref)
    if before_push != base:
        raise DeliveryError(f"remote changed before push: expected {base}, actual {before_push}")
    current_head = resolve_commit(repo, "HEAD", "HEAD")
    if current_head != head:
        raise DeliveryError(f"HEAD changed before push: expected {head}, actual {current_head}")

    # Push the reviewed immutable object ID to the verified URL and full destination ref.
    git(repo, "push", "--", url, f"{head}:{ref}")

    after_push = read_remote_head(repo, url, ref)
    fetched_after = fetch_remote_branch(repo, url, ref)
    if fetched_after != after_push:
        raise DeliveryError(f"remote changed while verifying push: ls-remote={after_push}, fetch={fetched_after}")
    require_ancestor(repo, head, after_push, f"push verification failed: {head} is not in remote history at {after_push}")
    print(f"push verified: {head} is contained in {url} {ref} (current remote {after_push})")


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    subparsers = root.add_subparsers(dest="action", required=True)
    for action in ("plan", "push"):
        command = subparsers.add_parser(action)
        command.add_argument("--repo", required=True, help="Git worktree path")
        command.add_argument("--remote", default="origin", help="remote name (default: origin)")
        command.add_argument("--branch", default="main", help="checked-out target branch (default: main)")
        command.add_argument("--expected-head", required=True, help="complete expected local commit OID")
        command.add_argument("--expected-remote", required=True, help="complete expected existing remote commit OID")
        command.add_argument("--allow-commit", action="append", default=[], help="complete authorized commit OID; repeat for each commit")
    return root


def main():
    args = parser().parse_args()
    try:
        deliver(args)
    except DeliveryError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
