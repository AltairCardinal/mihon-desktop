#!/usr/bin/env python3
"""Build a local main candidate from a private committed worktree, never shared WIP."""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True, encoding="utf-8").strip()


def commit(repo: Path, value: str) -> str:
    if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", value):
        raise ValueError("a full immutable commit SHA is required")
    resolved = git(repo, "rev-parse", "--verify", f"{value}^{{commit}}")
    if resolved != value.lower():
        raise ValueError("commit SHA does not identify a commit directly")
    return resolved


def artifact_root(manifest: dict) -> Path:
    path = Path(manifest["artifactPath"]).resolve()
    return path.parent if manifest["platform"] == "windows" else path


def build(args: argparse.Namespace) -> Path:
    repo = Path(git(args.repo.resolve(), "rev-parse", "--show-toplevel")).resolve()
    expected = commit(repo, args.expected_head)
    required = list(dict.fromkeys(commit(repo, value) for value in args.require_commit))
    if git(repo, "rev-parse", "--verify", "refs/heads/main") != expected:
        raise ValueError("main moved; inspect the new main and select its full commit SHA again")
    for value in required:
        if subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", value, expected]).returncode:
            raise ValueError(f"required commit is missing from selected main: {value}")

    specification = importlib.util.spec_from_file_location("build_provenance", Path(__file__).with_name("task15-build-provenance.py"))
    provenance = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(provenance)
    bash = Path(r"C:\Program Files\Git\bin\bash.exe") if sys.platform == "win32" else Path("/bin/bash")
    with tempfile.TemporaryDirectory(prefix="mihon-main-preview-") as temporary:
        owned_root = Path(temporary).resolve()
        checkout = owned_root / "checkout"
        registered = False
        try:
            git(repo, "worktree", "add", "--detach", str(checkout), expected)
            registered = True
            # SDK paths remain local configuration, never committed or printed.
            configuration = repo / "local.properties"
            configuration_identity = None
            if configuration.is_file():
                shutil.copyfile(configuration, checkout / "local.properties")
                configuration_identity = provenance.artifact_identity(checkout / "local.properties")
            environment = dict(os.environ, MIHON_PYTHON=sys.executable,
                               PYTHONUTF8="1", PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
            with subprocess.Popen([str(bash), "scripts/build-desktop.sh", "preview"], cwd=checkout,
                                  env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding="utf-8") as process:
                for line in process.stdout:
                    # Only the durable, copied artifact below is a user-facing output.
                    if not line.startswith(("Final unpacked EXE:", "Final macOS app:", "Preview manifest:")):
                        print(line, end="", flush=True)
                if process.wait():
                    raise ValueError("isolated main preview failed; no main candidate was published")
            preview_root = checkout / "app-desktop/artifacts/preview"
            manifests = list(preview_root.glob("*/*/preview-manifest.json"))
            if len(manifests) != 1:
                raise ValueError("isolated preview must produce exactly one manifest")
            manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
            if (manifest["source"]["sourceCommit"] != expected or manifest["source"]["dirty"]
                    or manifest["sourceIntegrity"]["status"] != "PASS" or manifest["build"]["status"] != "PASS"):
                raise ValueError("isolated preview does not match the selected committed main")
            platform = manifest["platform"]
            if platform not in {"windows", "macos"}:
                raise ValueError("unsupported preview platform")
            source_app = artifact_root(manifest)
            if not source_app.is_relative_to(manifests[0].parent.resolve()):
                raise ValueError("preview artifact escaped its isolated output directory")
            identity = provenance.artifact_identity(source_app)
            if manifest.get("artifact") != identity:
                raise ValueError("preview artifact no longer matches its manifest")
            destination = repo / "app-desktop/artifacts/preview" / platform / f"main-{uuid.uuid4().hex}"
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination_app = destination / source_app.name
            staging = destination.with_name(f".{destination.name}.partial")
            staging.mkdir()
            try:
                staged_app = staging / source_app.name
                shutil.copytree(source_app, staged_app)
                if provenance.artifact_identity(staged_app) != identity:
                    raise ValueError("copied preview artifact integrity failed")
                old_path = Path(manifest["artifactPath"]).resolve()
                manifest["kind"] = "main-preview"
                manifest["artifactPath"] = str(destination_app / old_path.relative_to(source_app))
                manifest["mainDelivery"] = {"expectedHead": expected, "requiredCommits": required,
                                            "isolation": "detached-worktree", "remoteMain": "NOT_VERIFIED",
                                            "localConfiguration": configuration_identity}
                provenance.write(staging / "preview-manifest.json", manifest)
                staging.rename(destination)
                manifest_path = destination / "preview-manifest.json"
            finally:
                if staging.exists():
                    if staging.is_symlink() or staging.resolve().parent != destination.parent.resolve():
                        raise ValueError("unsafe candidate staging cleanup target")
                    shutil.rmtree(staging)
        finally:
            if registered:
                # Remove only this invocation's exact worktree inside its own temporary root.
                if checkout.resolve().parent != owned_root or checkout.resolve() == repo:
                    raise ValueError("unsafe private worktree cleanup target")
                git(repo, "worktree", "remove", "--force", str(checkout))
    print(f"Preview manifest: {manifest_path}")
    label = "Final unpacked EXE" if platform == "windows" else "Final macOS app"
    print(f"{label}: {manifest['artifactPath']}")
    return manifest_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--require-commit", action="append", required=True)
    args = parser.parse_args()
    try:
        build(args)
        return 0
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Main preview rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
