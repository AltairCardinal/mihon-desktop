#!/usr/bin/env python3
"""Classify GitHub changes before deciding whether product CI is required."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys


CI_CONTROL_FILES = {
    ".github/workflows/build.yml",
    ".github/workflows/desktop-ci.yml",
    "scripts/ci-scope.py",
    "scripts/tests/ci-scope-test.py",
}
DOC_FILES = {
    ".github/pull_request_template.md",
    ".github/workflows/pages.yml",
}
COMMIT_ID = re.compile(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})\Z")


def file_scope(path):
    if path in CI_CONTROL_FILES:
        return "ci"
    if (
        path in DOC_FILES
        or path.startswith(("docs/", "website/"))
        or ("/" not in path and path.lower().endswith(".md"))
    ):
        return "docs"
    return "product"


def git(*args):
    return subprocess.run(
        ["git", *args],
        check=True,
        capture_output=True,
        timeout=60,
    ).stdout


def commit_id(value):
    if not isinstance(value, str) or not COMMIT_ID.fullmatch(value):
        raise ValueError("The event must supply full commit IDs.")
    return value


def detect_scope(event_name, event_path):
    if event_name == "workflow_dispatch":
        return "product", 0, "Manual dispatch always requests the product pipeline."
    if event_name not in {"push", "pull_request"}:
        return "product", 0, "Unknown event: conservatively request the product pipeline."
    payload = json.loads(Path(event_path).read_text(encoding="utf-8"))
    if event_name == "push":
        before = commit_id(payload.get("before"))
        after = commit_id(payload.get("after"))
        separator = ".."
    else:
        request = payload["pull_request"]
        before = commit_id(request["base"]["sha"])
        after = commit_id(request["head"]["sha"])
        separator = "..."
    if set(before) == {"0"} or set(after) == {"0"}:
        return "product", 0, "A zero commit ID cannot establish a documentation-only diff."
    for revision in (before, after):
        git("rev-parse", "--verify", revision + "^{commit}")
    # Disable rename detection so moving a product file into docs still records
    # the product deletion. NUL separators preserve spaces and Unicode filenames.
    changed = git("diff", "--no-ext-diff", "--no-renames", "--name-only", "-z",
                  before + separator + after, "--")
    paths = [path.decode("utf-8") for path in changed.split(b"\0") if path]
    scopes = {file_scope(path) for path in paths}
    scope = "product" if "product" in scopes else "ci" if "ci" in scopes else "docs"
    return scope, len(paths), "Classified the event's actual Git diff."


def write_result(scope, count, reason):
    needs_product = str(scope == "product").lower()
    result = f"needs_product={needs_product}\nscope={scope}\n"
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as output:
            output.write(result)
    print(result, end="")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    message = (
        "Product pipeline required; the following steps perform its checks."
        if scope == "product"
        else "Product tests are not run for this scope. The CI routing regression tests run instead; "
             "the documentation workflow builds website changes separately."
    )
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(
                f"### CI scope: {scope}\n\n{reason}\n\n"
                f"Changed paths: {count}.\n\n{message}\n"
            )


def main():
    try:
        scope, count, reason = detect_scope(
            os.environ.get("GITHUB_EVENT_NAME", ""),
            os.environ.get("GITHUB_EVENT_PATH", ""),
        )
        write_result(scope, count, reason)
    except (OSError, ValueError, KeyError, TypeError, AttributeError,
            subprocess.SubprocessError) as error:
        print(f"CI scope detection failed; product checks must not be skipped: {error}",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
