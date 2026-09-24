#!/usr/bin/env python3
"""Preflight-first, append-only installer for the Mihon debug UI catalog.

Default: dry run. Never commits, pushes, downloads or modifies unrelated source.
Existing nonidentical authored files cause failure rather than replacement.
"""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

KIT = Path(__file__).resolve().parents[1]
EXPECTED_BASE = "6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
ACTIVITY = "eu.kanade.tachiyomi.uicatalog.UiCatalogActivity"
GRADLE_BLOCK = '''// BEGIN MIHON_UI_CATALOG_TEST_DEPENDENCIES
// Uses the repository's existing Compose BOM and AndroidX Test versions.
dependencies {
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
// END MIHON_UI_CATALOG_TEST_DEPENDENCIES'''


def run_git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(root), *args], text=True, capture_output=True, check=False)
    if result.returncode:
        raise ValueError("Git validation failed: " + result.stderr.strip())
    return result.stdout.strip()


def safe_target(root: Path, relative: Path) -> Path:
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"Unsafe path: {relative}")
    p = root / relative
    if not p.resolve().is_relative_to(root):
        raise ValueError(f"Path leaves repository: {relative}")
    for ancestor in (p, *p.parents):
        if ancestor == root:
            break
        if ancestor.is_symlink():
            raise ValueError(f"Refusing symlink target/parent: {ancestor}")
    return p


def append_block(original: bytes | None, block: str, marker: str) -> bytes:
    text = (original or b"").decode("utf-8-sig")
    newline = "\r\n" if "\r\n" in text else "\n"
    normal = text.replace("\r\n", "\n")
    block = block.replace("\r\n", "\n").strip()
    if marker in normal:
        if block not in normal:
            raise ValueError(f"Existing {marker} block differs; reconcile manually.")
        return original or b""
    addition = (("" if not text else newline * (1 if text.endswith(newline) else 2)) +
                block.replace("\n", newline) + newline).encode("utf-8")
    return (original or b"") + addition


def merge_manifest(original: bytes | None, template: bytes) -> bytes:
    if original is None:
        return template
    text = original.decode("utf-8-sig")
    root = ET.fromstring(text)
    if root.tag != "manifest":
        raise ValueError("Debug AndroidManifest.xml root is not <manifest>.")
    expected = ET.fromstring(template).find("application/activity")
    assert expected is not None
    for a in root.findall("application/activity"):
        if a.get(f"{{{ANDROID_NS}}}name") == ACTIVITY:
            normalize = lambda n: (n.tag, dict(n.attrib), tuple(normalize(c) for c in n))
            if normalize(a) != normalize(expected):
                raise ValueError("A nonidentical UI catalog activity already exists.")
            return original
    if f'xmlns:android="{ANDROID_NS}"' not in text and f"xmlns:android='{ANDROID_NS}'" not in text:
        raise ValueError("Manifest needs its existing android namespace prefix; merge manually.")
    fragment = re.search(r"    <activity\b[\s\S]*?</activity>", template.decode("utf-8"))
    if fragment is None:
        raise ValueError("Invalid catalog manifest template.")
    activity_text = fragment.group(0)
    if root.find("application") is None:
        if "</manifest>" not in text:
            raise ValueError("Self-closing manifest requires manual merge.")
        text = text.replace("</manifest>", "    <application>\n" + activity_text + "\n    </application>\n</manifest>", 1)
    elif "</application>" in text:
        text = text.replace("</application>", "\n" + activity_text + "\n    </application>", 1)
    else:
        text, count = re.subn(r"(<application\b[^>]*?)/>", lambda m: m.group(1) + ">\n" + activity_text + "\n    </application>", text, count=1)
        if count != 1:
            raise ValueError("Unsupported application element; merge manually.")
    ET.fromstring(text)
    return text.encode("utf-8")


def build_plan(root: Path, allow_different_base: bool) -> tuple[list[tuple[Path, bytes | None, bytes]], str]:
    if Path(run_git(root, "rev-parse", "--show-toplevel")).resolve() != root:
        raise ValueError("Pass the repository root, not a subdirectory.")
    base = run_git(root, "rev-parse", "HEAD")
    if base != EXPECTED_BASE and not allow_different_base:
        raise ValueError(f"HEAD is {base}, expected {EXPECTED_BASE}. Review API differences, then use --allow-different-base; this flag does not validate compatibility.")
    required = (
        "app/build.gradle.kts", "gradle/libs.versions.toml",
        "app/src/main/java/eu/kanade/presentation/components/AppBar.kt",
        "app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt",
        "presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Scaffold.kt",
    )
    for name in required:
        if not safe_target(root, Path(name)).is_file():
            raise ValueError(f"Required Android baseline file missing: {name}")
    gradle = (root / required[0]).read_text(encoding="utf-8-sig")
    if 'namespace = "eu.kanade.tachiyomi"' not in gradle:
        raise ValueError("Namespace differs; update catalog imports before installation.")
    if 'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"' not in gradle:
        raise ValueError("AndroidJUnitRunner not declared; configure the test runner manually first.")
    catalog = (root / required[1]).read_text(encoding="utf-8-sig")
    for alias in ("androidx-compose-bom", "androidx-test-junit", "androidx-test-espresso-core", "androidx-compose-materialIcons"):
        if not re.search(r"^" + re.escape(alias) + r"\s*=", catalog, re.M):
            raise ValueError(f"Version-catalog alias missing: {alias}")
    if "androidx.compose.material.icons" not in (root / required[2]).read_text():
        raise ValueError("This branch uses a different icon API; adapt sample icons first.")
    # Validate the project-local instructions before extending them.
    plans: dict[Path, tuple[bytes | None, bytes]] = {}
    def plan(rel: Path, new: bytes, mode: str = "new") -> None:
        target = safe_target(root, rel)
        old = target.read_bytes() if target.exists() else None
        if old == new:
            return
        if mode == "new" and old is not None:
            raise ValueError(f"Refusing to overwrite existing different file: {rel}")
        plans[target] = (old, new)
    for source in sorted((KIT / "overlay").rglob("*")):
        if not source.is_file():
            continue
        rel = source.relative_to(KIT / "overlay")
        target = safe_target(root, rel)
        if rel.as_posix() == "app/src/debug/AndroidManifest.xml":
            old = target.read_bytes() if target.exists() else None
            plan(rel, merge_manifest(old, source.read_bytes()), "merge")
        else:
            plan(rel, source.read_bytes())
    for source in sorted((KIT / "docs/ui").rglob("*")):
        if source.is_file():
            plan(source.relative_to(KIT), source.read_bytes())
    agents = safe_target(root, Path("AGENTS.md"))
    agent_block = (KIT / "AGENTS.ui.md").read_text(encoding="utf-8")
    plan(Path("AGENTS.md"), append_block(agents.read_bytes() if agents.exists() else None,
         agent_block, "BEGIN MIHON_UI_CONVENTIONS"), "append")
    plan(Path(required[0]), append_block((root / required[0]).read_bytes(), GRADLE_BLOCK,
         "BEGIN MIHON_UI_CATALOG_TEST_DEPENDENCIES"), "append")
    return [(p, old, new) for p, (old, new) in plans.items()], base


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    old_mode = path.stat().st_mode & 0o777 if path.exists() else 0o644
    fd, temp = tempfile.mkstemp(prefix=".ui-catalog-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.chmod(temp, old_mode)
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def apply_plan(root: Path, plan: list[tuple[Path, bytes | None, bytes]], base: str) -> None:
    if run_git(root, "rev-parse", "HEAD") != base:
        raise ValueError("HEAD changed after preflight; rerun the installer.")
    for path, old, _ in plan:
        safe_target(root, path.relative_to(root))
        if (path.read_bytes() if path.exists() else None) != old:
            raise ValueError(f"File changed after preflight: {path}")
    written = []
    try:
        for path, old, new in plan:
            if (path.read_bytes() if path.exists() else None) != old:
                raise ValueError(f"Concurrent edit detected: {path}")
            atomic_write(path, new)
            written.append((path, old, new))
    except Exception:
        for path, old, new in reversed(written):
            if path.read_bytes() != new:
                print(f"WARNING: concurrent edit prevents rollback of {path}", file=sys.stderr)
                continue
            if old is None:
                path.unlink()
            else:
                atomic_write(path, old)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--apply", action="store_true", help="Apply the preflighted local changes; default is dry run.")
    parser.add_argument("--allow-different-base", action="store_true", help="Acknowledge a reviewed HEAD difference; not an API compatibility guarantee.")
    args = parser.parse_args()
    root = args.repository.expanduser().resolve()
    try:
        plan, base = build_plan(root, args.allow_different_base)
        print(f"Repository: {root}\nHEAD: {base}")
        print("DRY RUN" if not args.apply else "APPLY TO LOCAL WORKTREE")
        for path, old, new in plan:
            print(f"{'CREATE' if old is None else 'APPEND/MERGE'} {path.relative_to(root)} ({len(new)} bytes)")
        if args.apply:
            apply_plan(root, plan, base)
            print(f"Applied {len(plan)} file changes. No commit or remote operation was performed.")
        else:
            print(f"Planned {len(plan)} changes; no files were written. Review and rerun with --apply.")
        print("Android build, UI tests, screenshots and production integration remain NOT_RUN.")
        return 0
    except (OSError, ValueError, ET.ParseError, UnicodeError) as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
