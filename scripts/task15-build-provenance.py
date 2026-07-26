#!/usr/bin/env python3
"""Fail-closed source and artifact provenance for Task 15 platform acceptance."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import textwrap
from typing import Any, Dict, List, Tuple, Union

ALGORITHM = "mihon-production-input-v1:sha256(mode<TAB>relative-path<TAB>raw-byte-sha256<LF>)"
APP_VERSION = pathlib.PurePosixPath(
    "app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt",
)
EXCLUSIONS = [
    "docs/**",
    "openspec/**",
    "test-desktop/**",
    "scripts/tests/**",
    "scripts/task15-platform-evidence-test.{ps1,sh}",
    ".codex/**",
    "**/src/*Test/**",
    "**/src/*test/**",
    "**/build/**",
]


def git(root: pathlib.Path, *args: str, text: bool = True) -> Union[str, bytes]:
    return subprocess.check_output(["git", "-C", str(root), *args], text=text)


def is_product_input(path: pathlib.PurePosixPath) -> bool:
    value = path.as_posix()
    parts = value.split("/")
    if value.startswith(("docs/", "openspec/", "test-desktop/", "scripts/tests/", ".codex/")):
        return False
    if value in {
        "scripts/task15-platform-evidence-test.ps1",
        "scripts/task15-platform-evidence-test.sh",
    }:
        return False
    if "build" in parts:
        return False
    return not any(
        part == "src" and index + 1 < len(parts) and "test" in parts[index + 1].lower()
        for index, part in enumerate(parts[:-1])
    )


def is_untracked_product_input(path: pathlib.PurePosixPath) -> bool:
    if not is_product_input(path):
        return False
    value = path.as_posix()
    parts = value.split("/")
    if "src" in parts:
        source_index = parts.index("src")
        if (
            source_index + 1 < len(parts)
            and "test" not in parts[source_index + 1].lower()
        ):
            return True
    return (
        value.startswith(("buildSrc/", "gradle/"))
        or value
        in {
            "gradle.properties",
            "scripts/build-desktop.sh",
            "scripts/build-windows.ps1",
            "scripts/task15-build-provenance.py",
        }
        or pathlib.PurePosixPath(value).name
        in {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}
    )


def assert_committed_inputs(root: pathlib.Path) -> None:
    changed = git(root, "diff", "--name-only", "HEAD", "--").splitlines()
    changed_product = [
        value for value in changed if is_product_input(pathlib.PurePosixPath(value))
    ]
    if changed_product:
        raise ValueError(f"modified product input(s): {', '.join(changed_product)}")
    untracked = git(
        root,
        "ls-files",
        "--others",
        "--exclude-standard",
        "-z",
        text=False,
    ).split(b"\0")
    untracked_product = [
        os.fsdecode(value)
        for value in filter(None, untracked)
        if is_untracked_product_input(pathlib.PurePosixPath(os.fsdecode(value)))
    ]
    if untracked_product:
        raise ValueError(f"untracked product input(s): {', '.join(untracked_product)}")


def version_fields(text: str) -> Tuple[int, int, int]:
    values = []
    for name in ("STAGE", "FEATURE", "BUILD"):
        match = re.search(rf"const val {name} = (\d+)", text)
        if not match:
            raise ValueError(f"cannot read AppVersion.{name}")
        values.append(int(match.group(1)))
    return tuple(values)  # type: ignore[return-value]


def assert_expected_version_allocation(root: pathlib.Path) -> Dict[str, str]:
    if not git(root, "rev-parse", "--verify", "HEAD^").strip():
        raise ValueError("version allocation requires a parent commit")
    previous = git(root, "show", f"HEAD^:{APP_VERSION.as_posix()}")
    current = (root / APP_VERSION).read_text(encoding="utf-8")
    previous_fields = version_fields(previous)
    current_fields = version_fields(current)
    expected = (previous_fields[0], previous_fields[1], previous_fields[2] + 1)
    if current_fields != expected:
        raise ValueError(
            "evidence build requires the committed AppVersion transition "
            f"{previous_fields} -> {expected}, got {current_fields}",
        )
    return {
        "kind": "BUILD_INCREMENT",
        "from": ".".join(map(str, previous_fields)),
        "to": ".".join(map(str, current_fields)),
    }


def source_identity(root: pathlib.Path, require_version_allocation: bool) -> Dict[str, Any]:
    assert_committed_inputs(root)
    transition = (
        assert_expected_version_allocation(root) if require_version_allocation else None
    )
    tracked = git(root, "ls-files", "-z", text=False).split(b"\0")
    records: List[str] = []
    for raw in sorted(filter(None, tracked)):
        path = pathlib.PurePosixPath(os.fsdecode(raw))
        if not is_product_input(path):
            continue
        absolute = root.joinpath(*path.parts)
        if not absolute.is_file():
            raise ValueError(f"missing product input: {path}")
        index = git(root, "ls-files", "-s", "--", path.as_posix())
        mode = index[:6]
        digest = hashlib.sha256(absolute.read_bytes()).hexdigest()
        records.append(f"{mode}\t{path.as_posix()}\t{digest}\n")
    canonical = "".join(records).encode()
    result: Dict[str, Any] = {
        "sourceCommit": git(root, "rev-parse", "HEAD").strip(),
        "sourceTree": git(root, "rev-parse", "HEAD^{tree}").strip(),
        "productSource": {
            "algorithm": ALGORITHM,
            "exclusions": EXCLUSIONS,
            "fileCount": len(records),
            "digest": hashlib.sha256(canonical).hexdigest(),
        },
    }
    if transition:
        result["versionAllocation"] = transition
    return result


def artifact_identity(path: pathlib.Path) -> Dict[str, Any]:
    if path.is_file():
        content = path.read_bytes()
        return {
            "algorithm": "sha256(raw-file)",
            "fileCount": 1,
            "sha256": hashlib.sha256(content).hexdigest(),
            "size": len(content),
        }
    if not path.is_dir():
        raise ValueError(f"artifact is missing: {path}")
    records: List[str] = []
    total_size = 0
    file_count = 0
    for item in sorted(path.rglob("*"), key=lambda value: value.relative_to(path).as_posix()):
        relative = item.relative_to(path).as_posix()
        if item.is_symlink():
            target = os.readlink(str(item))
            digest = hashlib.sha256(target.encode()).hexdigest()
            records.append(f"symlink\t{relative}\t{digest}\n")
        elif item.is_file():
            content = item.read_bytes()
            digest = hashlib.sha256(content).hexdigest()
            records.append(f"file\t{relative}\t{digest}\n")
            total_size += len(content)
            file_count += 1
    return {
        "algorithm": "mihon-artifact-tree-v1:sha256(kind<TAB>relative-path<TAB>content-sha256<LF>)",
        "fileCount": file_count,
        "sha256": hashlib.sha256("".join(records).encode()).hexdigest(),
        "size": total_size,
    }


def load(path: pathlib.Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write(path: pathlib.Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def capture_screenshots(payload: Dict[str, Any]) -> Dict[str, Dict[str, str]]:
    screenshots = payload.get("screenshots")
    if not isinstance(screenshots, list) or len(screenshots) != 3:
        raise ValueError("capture requires protected, clear, and feedback screenshots")
    records: Dict[str, Dict[str, str]] = {}
    for item in screenshots:
        if not isinstance(item, dict):
            raise ValueError("capture screenshot record must be an object")
        role = item.get("role")
        path = item.get("path")
        digest = item.get("sha256")
        if (
            role not in {"protected", "clear", "feedback"}
            or not isinstance(path, str)
            or not path
            or not isinstance(digest, str)
            or not re.fullmatch(r"[0-9a-f]{64}", digest)
            or role in records
        ):
            raise ValueError("capture screenshot record is incomplete")
        records[role] = item
    if set(records) != {"protected", "clear", "feedback"}:
        raise ValueError("capture screenshot roles are incomplete")
    return records


def validate_capture_runtime(payload: Dict[str, Any]) -> Dict[str, Any]:
    os_name = payload.get("os")
    if payload.get("status") != "PENDING_REVIEW" or os_name not in {
        "windows",
        "macos",
        "linux",
    }:
        raise ValueError("capture runtime must remain pending human review")
    if int(payload.get("windowHandle", 0)) <= 0:
        raise ValueError("capture result is missing a real window handle")
    adapter = payload.get("adapter")
    if (
        not isinstance(adapter, dict)
        or adapter.get("identity") != "DesktopWindowPrivacy"
        or adapter.get("os") != os_name
    ):
        raise ValueError("capture result is not from the production privacy adapter")
    if os_name == "windows":
        capability = payload.get("capability")
        expected_affinity = {"Supported": 0x11, "Limited": 0x1}.get(capability)
        if (
            expected_affinity is None
            or adapter.get("attachResult") != "Supported"
            or adapter.get("applyResult") != capability
            or adapter.get("queryResult") != capability
            or adapter.get("clearResult") != "Supported"
            or int(payload.get("appliedAffinity", -1)) != expected_affinity
            or int(payload.get("clearedAffinity", -1)) != 0
        ):
            raise ValueError("Windows adapter and affinity evidence are inconsistent")
    else:
        expected_reason = {
            "macos": "macos_capture_affinity_unavailable",
            "linux": "linux_capture_affinity_unavailable",
        }[os_name]
        if (
            payload.get("capability") != "Unsupported"
            or adapter.get("queryResult") != "Unsupported"
            or adapter.get("reason") != expected_reason
        ):
            raise ValueError(f"{os_name} production adapter evidence is inconsistent")
    capture_screenshots(payload)
    return {"status": "VALID", "capability": payload["capability"]}


def validate_capture_review(payload: Dict[str, Any]) -> Dict[str, Any]:
    runtime = payload.get("runtime")
    review = payload.get("review")
    if not isinstance(runtime, dict) or not isinstance(review, dict):
        raise ValueError("capture review requires runtime and review objects")
    validate_capture_runtime(runtime)
    if (
        review.get("case") != "capture"
        or review.get("decision") not in {"PASS", "FAIL"}
        or not review.get("reviewer")
        or not review.get("reviewedAtUtc")
    ):
        raise ValueError("capture review identity, decision, reviewer, or time is missing")
    runtime_shots = capture_screenshots(runtime)
    reviewed_shots = capture_screenshots({"screenshots": review.get("screenshots")})
    for role, expected in runtime_shots.items():
        reviewed = reviewed_shots[role]
        path = pathlib.Path(expected["path"]).resolve()
        if (
            pathlib.Path(reviewed["path"]).resolve() != path
            or reviewed["sha256"] != expected["sha256"]
            or not path.is_file()
            or hashlib.sha256(path.read_bytes()).hexdigest() != expected["sha256"]
        ):
            raise ValueError(f"capture review screenshot mismatch: {role}")
    observations = review.get("observations")
    if not isinstance(observations, dict):
        raise ValueError("capture review observations are missing")
    if review["decision"] == "PASS":
        capability = runtime["capability"]
        expected_protected = {
            "Supported": "MihonExcluded",
            "Limited": "MihonObscured",
            "Unsupported": "MihonVisible",
        }[capability]
        if (
            observations.get("protected") != expected_protected
            or observations.get("clear") != "MihonVisible"
            or observations.get("feedback") != capability
        ):
            raise ValueError("capture observations do not match adapter capability")
    return {"status": "VALID", "decision": review["decision"], "review": review}


def validate_installer_handoff(payload: Dict[str, Any]) -> Dict[str, Any]:
    artifact = payload.get("artifact")
    if artifact is not None and (
        payload.get("status") == "PASS" or payload.get("provenance") is not None
    ):
        provenance = payload.get("provenance")
        if not isinstance(artifact, dict) or not isinstance(provenance, dict):
            raise ValueError("installer artifact requires independent provenance")
        installer_provenance(
            pathlib.Path(str(provenance.get("repo", ""))).resolve(),
            pathlib.Path(str(artifact.get("path", ""))).resolve(),
            pathlib.Path(str(provenance.get("sidecarPath", ""))).resolve(),
            str(artifact.get("name", "")),
        )
    if payload.get("status") == "BLOCKED":
        blockers = payload.get("blockers")
        if not isinstance(blockers, list) or not blockers or not all(
            isinstance(value, str) and value for value in blockers
        ):
            raise ValueError("blocked installer handoff requires explicit blockers")
        production = payload.get("production")
        if production is not None:
            manual = (
                isinstance(production, dict)
                and production.get("identity") == "DesktopUpdateInstaller"
                and production.get("preparationResult") == "InstallManualOnly"
                and production.get("userConfirmation") == "NotRequested"
                and production.get("cancellationResult") == "NotApplicable"
                and production.get("launchResult") == "NotAttempted"
                and production.get("feedback") == "ManualOnly"
            )
            confirmation_required = (
                isinstance(production, dict)
                and production.get("identity") == "DesktopUpdateInstaller"
                and production.get("preparationResult") == "ReadyToInstall"
                and production.get("userConfirmation") == "AwaitingConfirmation"
                and production.get("cancellationResult") == "InstallCancelled"
                and production.get("launchResult") == "NotAttempted"
                and production.get("feedback") == "ConfirmationRequired"
                and production.get("productionRevalidation") == "prepare+handoff"
            )
            if not (manual or confirmation_required):
                raise ValueError("blocked production installer evidence is inconsistent")
        return {"status": "VALID", "outcome": "BLOCKED"}
    if payload.get("status") != "PASS":
        raise ValueError("installer handoff must be PASS or BLOCKED")
    os_name = payload.get("os")
    if os_name not in {"windows", "macos"}:
        raise ValueError("only signed Windows/macOS handoff can pass")
    release_tag = payload.get("releaseTag")
    signature = payload.get("signature")
    production = payload.get("production")
    if (
        not isinstance(release_tag, str)
        or not release_tag
        or any(value.isspace() for value in release_tag)
        or not isinstance(artifact, dict)
        or not isinstance(signature, dict)
        or not isinstance(production, dict)
    ):
        raise ValueError("installer handoff metadata is incomplete")
    path = pathlib.Path(str(artifact.get("path", ""))).resolve()
    digest = artifact.get("sha256")
    size = artifact.get("size")
    name = artifact.get("name")
    if os_name == "windows":
        canonical = f"mihon-desktop-windows-x86_64-{release_tag}.msi"
        signature_valid = (
            signature.get("tool") == "Get-AuthenticodeSignature"
            and signature.get("status") == "Valid"
            and isinstance(signature.get("publisher"), str)
            and bool(signature["publisher"])
            and signature.get("publisher") == payload.get("trustedIdentity")
        )
    else:
        canonical = re.fullmatch(
            rf"mihon-desktop-macos-(x86_64|arm64)-{re.escape(release_tag)}\.dmg",
            str(name),
        )
        signature_valid = (
            signature.get("tool") == "codesign+spctl"
            and signature.get("status") == "Valid"
            and isinstance(signature.get("teamId"), str)
            and re.fullmatch(r"[A-Z0-9]{10}", signature["teamId"]) is not None
            and signature.get("teamId") == payload.get("trustedIdentity")
        )
    if (
        not path.is_file()
        or name != path.name
        or (name != canonical if isinstance(canonical, str) else canonical is None)
        or not isinstance(digest, str)
        or re.fullmatch(r"[0-9a-f]{64}", digest) is None
        or hashlib.sha256(path.read_bytes()).hexdigest() != digest
        or not isinstance(size, int)
        or path.stat().st_size != size
        or not signature_valid
    ):
        raise ValueError("canonical artifact, checksum, size, or signature evidence is invalid")
    if os_name == "windows":
        command = (
            "$s=Get-AuthenticodeSignature -LiteralPath $args[0];"
            "if($s.Status -ne 'Valid' -or -not $s.SignerCertificate){exit 31};"
            "[Console]::Out.Write($s.SignerCertificate.Subject)"
        )
        actual_identity = subprocess.check_output(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command, str(path)],
            text=True,
        ).strip()
    else:
        subprocess.check_call(
            ["/usr/bin/codesign", "--verify", "--deep", "--strict", str(path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        subprocess.check_call(
            ["/usr/sbin/spctl", "-a", "-t", "install", str(path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        details = subprocess.check_output(
            ["/usr/bin/codesign", "-dv", "--verbose=4", str(path)],
            stderr=subprocess.STDOUT,
            text=True,
        )
        match = re.search(r"(?m)^TeamIdentifier=([A-Z0-9]{10})$", details)
        actual_identity = match.group(1) if match else ""
    if actual_identity != payload.get("trustedIdentity"):
        raise ValueError("system signer identity does not match independent trust")
    if (
        production.get("identity") != "DesktopUpdateInstaller"
        or production.get("preparationResult") != "ReadyToInstall"
        or production.get("cancellationResult") != "InstallCancelled"
        or production.get("userConfirmation") != "Confirmed"
        or production.get("launchResult") != "InstallHandedOff"
        or production.get("feedback") != "InstallerHandedOff"
        or production.get("productionRevalidation") != "prepare+handoff"
    ):
        raise ValueError("user confirmation, cancellation, launch, or feedback evidence is incomplete")
    return {"status": "VALID", "outcome": "PASS"}


def seal(args: argparse.Namespace) -> Dict[str, Any]:
    root = args.repo.resolve()
    captured = load(args.source)
    current = source_identity(root, args.require_version_allocation)
    if captured != current:
        raise ValueError("committed source identity changed between capture and seal")
    result = {
        "schemaVersion": 1,
        "generatedBy": "scripts/task15-build-provenance.py",
        **current,
        "artifact": artifact_identity(args.artifact),
    }
    write(args.output, result)
    return result


def verify(args: argparse.Namespace) -> Dict[str, Any]:
    root = args.repo.resolve()
    provenance = load(args.provenance)
    current = source_identity(root, args.require_version_allocation)
    for key in ("sourceCommit", "sourceTree", "productSource"):
        if provenance.get(key) != current.get(key):
            raise ValueError(f"provenance {key} does not match committed source")
    if args.require_version_allocation and provenance.get("versionAllocation") != current.get(
        "versionAllocation",
    ):
        raise ValueError("provenance version allocation does not match committed source")
    if provenance.get("artifact") != artifact_identity(args.artifact):
        raise ValueError("provenance artifact hash/size mismatch")
    return provenance


def installer_provenance(
    repo: pathlib.Path,
    artifact: pathlib.Path,
    provenance_path: pathlib.Path,
    canonical_name: str,
) -> Dict[str, Any]:
    if not artifact.is_file() or artifact.name != canonical_name:
        raise ValueError("installer artifact is missing or canonical name mismatches")
    provenance = load(provenance_path)
    current = source_identity(repo.resolve(), require_version_allocation=False)
    for key in ("sourceCommit", "sourceTree", "productSource"):
        if provenance.get(key) != current.get(key):
            raise ValueError(f"installer provenance {key} does not match committed source")
    expected_artifact = {
        **artifact_identity(artifact),
        "canonicalName": canonical_name,
    }
    if provenance.get("artifact") != expected_artifact:
        raise ValueError("installer provenance hash, size, or canonical name mismatch")
    return provenance


def seal_installer(args: argparse.Namespace) -> Dict[str, Any]:
    artifact = args.artifact.resolve()
    if artifact.name != args.canonical_name:
        raise ValueError("installer canonical name does not match artifact")
    result = {
        "schemaVersion": 1,
        "generatedBy": "controlled-release:task15-installer",
        **source_identity(args.repo.resolve(), require_version_allocation=False),
        "artifact": {
            **artifact_identity(artifact),
            "canonicalName": args.canonical_name,
        },
    }
    write(args.output, result)
    return result


def verify_installer(args: argparse.Namespace) -> Dict[str, Any]:
    return installer_provenance(
        args.repo.resolve(),
        args.artifact.resolve(),
        args.provenance.resolve(),
        args.canonical_name,
    )


def policy(args: argparse.Namespace) -> Dict[str, Any]:
    payload = json.load(sys.stdin) if str(args.input) == "-" else load(args.input)

    def pid_values(name: str) -> List[int]:
        raw = payload.get(name)
        if raw is None:
            return []
        values = raw if isinstance(raw, list) else [raw]
        return [int(value) for value in values if value is not None]

    if args.kind == "terminal":
        cursor = int(payload["cursor"])
        history = payload["history"]
        if len(history) < cursor:
            raise ValueError("action history cursor moved backwards")
        terminal = [
            record
            for record in history[cursor:]
            if record.get("action")
            in {
                "ExternalActionSucceeded",
                "ExternalActionRejected",
                "ExternalActionFailed",
            }
        ]
        if not terminal:
            return {"status": "PENDING"}
        if len(terminal) != 1:
            raise ValueError("expected exactly one terminal action after cursor")
        record = terminal[0]
        if record.get("action") != "ExternalActionRejected":
            raise ValueError("terminal action is not ExternalActionRejected")
        if record.get("params", {}).get("target") != "ParserRejected":
            raise ValueError("rejection target is not ParserRejected")
        return {"status": "VALID", "record": record}
    if args.kind == "screenshot":
        if payload.get("success") is not True:
            raise ValueError("screenshot result is not successful")
        return {"status": "VALID", "screenshot": payload}
    if args.kind == "pid-empty":
        pids = pid_values("pids")
        if pids:
            raise ValueError(f"existing owner PID(s): {pids}")
        return {"status": "VALID"}
    if args.kind == "pid-owned":
        owned = pid_values("owned")
        current = pid_values("current")
        if len(owned) != 1 or current != owned:
            raise ValueError(f"owner PID changed: owned={owned}, current={current}")
        return {"status": "VALID", "owner": owned[0]}
    if args.kind == "pid-cleanup":
        owned = set(pid_values("owned"))
        current = pid_values("current")
        return {
            "status": "VALID",
            "kill": [value for value in current if value in owned],
        }
    if args.kind == "credential":
        expected_backends = {
            "windows": ("DPAPI", "OsCredentialBackend(platform=WINDOWS)"),
            "macos": ("Keychain", "OsCredentialBackend(platform=MACOS)"),
            "linux": ("SecretService", "OsCredentialBackend(platform=LINUX)"),
        }
        os_name = payload.get("os")
        expected = expected_backends.get(os_name)
        if (
            payload.get("status") != "PASS"
            or expected is None
            or payload.get("backend") != expected[0]
            or payload.get("storeIdentity") != "DesktopCredentialStore(backend=OsCredentialBackend)"
            or payload.get("backendIdentity") != expected[1]
            or payload.get("service") != "mihon-desktop-tracker"
        ):
            raise ValueError("credential result does not identify a successful OS backend")
        required = (
            "saved",
            "firstReadMatched",
            "overwritten",
            "secondReadMatched",
            "deleted",
            "missingAfterDelete",
        )
        if any(payload.get(field) is not True for field in required):
            raise ValueError("credential roundtrip is incomplete")
        return {"status": "VALID", "backend": payload["backend"]}
    if args.kind == "capture":
        return validate_capture_runtime(payload)
    if args.kind == "capture-review":
        return validate_capture_review(payload)
    if args.kind == "installer-handoff":
        return validate_installer_handoff(payload)
    raise ValueError(f"unsupported policy kind: {args.kind}")


def write_probe(args: argparse.Namespace) -> Dict[str, Any]:
    source = textwrap.dedent(
        r"""
        import java.util.Locale;
        import java.util.UUID;
        import java.util.prefs.Preferences;
        import java.awt.Frame;
        import java.nio.file.Path;
        import java.util.List;
        import kotlin.coroutines.Continuation;
        import kotlin.coroutines.CoroutineContext;
        import kotlin.coroutines.EmptyCoroutineContext;
        import kotlin.coroutines.intrinsics.IntrinsicsKt;
        import kotlin.jvm.functions.Function1;
        import kotlin.jvm.functions.Function2;
        import kotlinx.coroutines.BuildersKt;
        import kotlinx.coroutines.CoroutineScope;
        import mihon.desktop.platform.DesktopCredentialStore;
        import mihon.desktop.platform.OsCredentialBackend;
        import mihon.desktop.platform.CredentialNamespace;
        import mihon.desktop.privacy.DesktopWindowPrivacy;
        import mihon.desktop.privacy.DesktopWindowPrivacyResult;
        import mihon.desktop.update.DesktopUpdateInstaller;
        import mihon.desktop.update.DesktopUpdateProcessRunner;
        import mihon.desktop.update.InstallPreparation;
        import mihon.desktop.update.InstallHandoffResult;
        import mihon.desktop.update.InstallerTrust;
        import mihon.desktop.update.ReadyToInstall;
        import mihon.desktop.update.VerifiedDownload;
        import tachiyomi.domain.release.model.ReleaseAsset;
        import tachiyomi.domain.release.model.ReleaseChecksum;
        import tachiyomi.domain.release.model.ReleaseOs;
        import tachiyomi.domain.release.model.ReleasePackageType;
        import tachiyomi.domain.release.model.ReleaseTarget;
        import tachiyomi.domain.release.model.ReleaseVariant;

        public final class Task152PlatformProbe {
            private static String jsonString(String value) {
                return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }

            private static String osId() {
                String value = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                if (value.contains("win")) return "windows";
                if (value.contains("mac") || value.contains("darwin")) return "macos";
                if (value.contains("linux")) return "linux";
                return "unsupported";
            }

            private static String backend(String os) {
                return switch (os) {
                    case "windows" -> "DPAPI";
                    case "macos" -> "Keychain";
                    case "linux" -> "SecretService";
                    default -> "Unsupported";
                };
            }

            private static void credential(String account) {
                DesktopCredentialStore store = new DesktopCredentialStore();
                OsCredentialBackend backendInstance = new OsCredentialBackend();
                String first = "task152-first-" + UUID.randomUUID();
                String second = "task152-second-" + UUID.randomUUID();
                boolean saved = false, firstRead = false, overwritten = false;
                boolean secondRead = false, deleted = false, missing = false;
                try {
                    store.delete(account);
                    store.save(account, first);
                    saved = true;
                    firstRead = first.equals(store.load(account));
                    store.save(account, second);
                    overwritten = true;
                    secondRead = second.equals(store.load(account));
                    store.delete(account);
                    deleted = true;
                    missing = store.load(account) == null;
                    String os = osId();
                    System.out.println(
                        "{\"status\":\"PASS\",\"os\":" + jsonString(os) +
                        ",\"backend\":" + jsonString(backend(os)) +
                        ",\"storeIdentity\":" + jsonString(store.toString()) +
                        ",\"backendIdentity\":" + jsonString(backendInstance.toString()) +
                        ",\"service\":" + jsonString(CredentialNamespace.TRACKER_V1.getService()) +
                        ",\"saved\":" + saved +
                        ",\"firstReadMatched\":" + firstRead +
                        ",\"overwritten\":" + overwritten +
                        ",\"secondReadMatched\":" + secondRead +
                        ",\"deleted\":" + deleted +
                        ",\"missingAfterDelete\":" + missing + "}"
                    );
                } finally {
                    try { store.delete(account); } catch (RuntimeException ignored) {}
                }
            }

            private static String resultName(DesktopWindowPrivacyResult result) {
                return result.getClass().getSimpleName();
            }

            private static String reason(DesktopWindowPrivacyResult result) {
                if (result instanceof DesktopWindowPrivacyResult.Unsupported value) {
                    return value.getReasonSlug();
                }
                if (result instanceof DesktopWindowPrivacyResult.Limited value) {
                    return value.getReasonSlug();
                }
                if (result instanceof DesktopWindowPrivacyResult.Failed value) {
                    return value.getError().getReasonSlug();
                }
                return "";
            }

            private static void privacy() {
                Frame frame = new Frame("Mihon Task152 privacy probe");
                frame.setSize(320, 200);
                DesktopWindowPrivacy privacy = new DesktopWindowPrivacy();
                try {
                    frame.setVisible(true);
                    DesktopWindowPrivacyResult attached = privacy.attach(frame);
                    DesktopWindowPrivacyResult applied = privacy.apply(true);
                    DesktopWindowPrivacyResult queried = privacy.query();
                    DesktopWindowPrivacyResult cleared = privacy.clear();
                    System.out.println(
                        "{\"status\":\"PASS\",\"os\":" + jsonString(osId()) +
                        ",\"identity\":" + jsonString(privacy.getClass().getSimpleName()) +
                        ",\"attachResult\":" + jsonString(resultName(attached)) +
                        ",\"applyResult\":" + jsonString(resultName(applied)) +
                        ",\"queryResult\":" + jsonString(resultName(queried)) +
                        ",\"clearResult\":" + jsonString(resultName(cleared)) +
                        ",\"reason\":" + jsonString(reason(queried)) + "}"
                    );
                } finally {
                    privacy.detach();
                    frame.dispose();
                }
            }

            private static void installer(String[] args) throws Exception {
                Path path = Path.of(args[1]);
                String releaseTag = args[2];
                ReleaseOs os = ReleaseOs.valueOf(args[3]);
                String arch = args[4];
                String sha256 = args[5];
                long size = Long.parseLong(args[6]);
                String trustedIdentity = args[7];
                boolean confirmed = Boolean.parseBoolean(args[8]);
                ReleasePackageType packageType =
                    os == ReleaseOs.WINDOWS ? ReleasePackageType.MSI :
                    os == ReleaseOs.MACOS ? ReleasePackageType.DMG :
                    ReleasePackageType.APPIMAGE;
                ReleaseTarget target =
                    new ReleaseTarget(os, arch, packageType, ReleaseVariant.STANDARD);
                ReleaseAsset asset = new ReleaseAsset(
                    path.getFileName().toString(),
                    target,
                    new ReleaseChecksum("sha256", sha256)
                );
                VerifiedDownload download = new VerifiedDownload(path, asset, sha256, size);
                InstallerTrust trust = new InstallerTrust(
                    os == ReleaseOs.WINDOWS ? trustedIdentity : null,
                    os == ReleaseOs.MACOS ? trustedIdentity : null
                );
                java.lang.reflect.Constructor<?> defaulted = null;
                for (java.lang.reflect.Constructor<?> candidate :
                    DesktopUpdateInstaller.class.getDeclaredConstructors()) {
                    if (candidate.getParameterCount() == 6) defaulted = candidate;
                }
                if (defaulted == null) throw new IllegalStateException("default installer constructor missing");
                defaulted.setAccessible(true);
                DesktopUpdateInstaller installer = (DesktopUpdateInstaller) defaulted.newInstance(
                    target, trust, null, null, 12, null
                );
                InstallPreparation prepared = BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    new Function2<CoroutineScope, Continuation<? super InstallPreparation>, Object>() {
                        public Object invoke(
                            CoroutineScope scope,
                            Continuation<? super InstallPreparation> continuation
                        ) {
                            return installer.prepare(download, releaseTag, continuation);
                        }
                    }
                );
                boolean ready = prepared instanceof ReadyToInstall;
                String cancellation = "NotApplicable";
                String confirmation = "NotRequested";
                String launch = "NotAttempted";
                String feedback = "ManualOnly";
                if (ready) {
                    InstallHandoffResult cancelled = BuildersKt.runBlocking(
                        EmptyCoroutineContext.INSTANCE,
                        new Function2<CoroutineScope, Continuation<? super InstallHandoffResult>, Object>() {
                            public Object invoke(
                                CoroutineScope scope,
                                Continuation<? super InstallHandoffResult> continuation
                            ) {
                                return installer.handoff((ReadyToInstall) prepared, false, continuation);
                            }
                        }
                    );
                    cancellation = cancelled.getClass().getSimpleName();
                    confirmation = confirmed ? "Confirmed" : "AwaitingConfirmation";
                    feedback = confirmed ? "InstallerHandedOff" : "ConfirmationRequired";
                    if (confirmed) {
                        InstallHandoffResult handedOff = BuildersKt.runBlocking(
                            EmptyCoroutineContext.INSTANCE,
                            new Function2<CoroutineScope, Continuation<? super InstallHandoffResult>, Object>() {
                                public Object invoke(
                                    CoroutineScope scope,
                                    Continuation<? super InstallHandoffResult> continuation
                                ) {
                                    return installer.handoff((ReadyToInstall) prepared, true, continuation);
                                }
                            }
                        );
                        launch = handedOff.getClass().getSimpleName();
                    }
                }
                System.out.println(
                    "{\"status\":\"PASS\",\"identity\":" +
                    jsonString(installer.getClass().getSimpleName()) +
                    ",\"preparationResult\":" +
                    jsonString(prepared.getClass().getSimpleName()) +
                    ",\"userConfirmation\":" +
                    jsonString(confirmation) +
                    ",\"cancellationResult\":" +
                    jsonString(cancellation) +
                    ",\"launchResult\":" + jsonString(launch) +
                    ",\"productionRevalidation\":\"prepare+handoff\"" +
                    ",\"feedback\":" +
                    jsonString(feedback) + "}"
                );
            }

            private static void preference(String operation, String value) throws Exception {
                Preferences preferences = Preferences.userRoot().node("/mihon");
                String key = "secure_screen_v2";
                String result;
                switch (operation) {
                    case "get" -> result = preferences.get(key, "__MISSING__");
                    case "set" -> {
                        preferences.put(key, value);
                        preferences.flush();
                        result = value;
                    }
                    case "delete" -> {
                        preferences.remove(key);
                        preferences.flush();
                        result = "__MISSING__";
                    }
                    default -> throw new IllegalArgumentException("unsupported preference operation");
                }
                System.out.println("{\"status\":\"PASS\",\"value\":" + jsonString(result) + "}");
            }

            public static void main(String[] args) {
                try {
                    if (args.length >= 2 && args[0].equals("credential")) {
                        credential(args[1]);
                    } else if (args.length == 1 && args[0].equals("privacy")) {
                        privacy();
                    } else if (args.length == 9 && args[0].equals("installer")) {
                        installer(args);
                    } else if (args.length >= 2 && args[0].equals("preference")) {
                        preference(args[1], args.length >= 3 ? args[2] : "");
                    } else {
                        throw new IllegalArgumentException("credential or preference operation required");
                    }
                } catch (Throwable error) {
                    System.out.println(
                        "{\"status\":\"FAILED\",\"errorClass\":" +
                        jsonString(error.getClass().getName()) + "}"
                    );
                    System.exit(1);
                }
            }
        }
        """,
    ).strip() + "\n"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(source, encoding="utf-8")
    return {"status": "WRITTEN", "output": str(args.output)}


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    subcommands = result.add_subparsers(dest="command", required=True)
    for name in ("source", "seal", "verify"):
        command = subcommands.add_parser(name)
        command.add_argument("--repo", type=pathlib.Path, required=True)
        command.add_argument("--require-version-allocation", action="store_true")
        if name in {"seal", "verify"}:
            command.add_argument("--artifact", type=pathlib.Path, required=True)
        if name == "source":
            command.add_argument("--output", type=pathlib.Path, required=True)
        elif name == "seal":
            command.add_argument("--source", type=pathlib.Path, required=True)
            command.add_argument("--output", type=pathlib.Path, required=True)
        else:
            command.add_argument("--provenance", type=pathlib.Path, required=True)
    for name in ("seal-installer", "verify-installer"):
        command = subcommands.add_parser(name)
        command.add_argument("--repo", type=pathlib.Path, required=True)
        command.add_argument("--artifact", type=pathlib.Path, required=True)
        command.add_argument("--canonical-name", required=True)
        if name == "seal-installer":
            command.add_argument("--output", type=pathlib.Path, required=True)
        else:
            command.add_argument("--provenance", type=pathlib.Path, required=True)
    policy_command = subcommands.add_parser("policy")
    policy_command.add_argument(
        "--kind",
        choices=(
            "terminal",
            "screenshot",
            "pid-empty",
            "pid-owned",
            "pid-cleanup",
            "credential",
            "capture",
            "capture-review",
            "installer-handoff",
        ),
        required=True,
    )
    policy_command.add_argument("--input", type=pathlib.Path, required=True)
    probe_command = subcommands.add_parser("write-probe")
    probe_command.add_argument("--output", type=pathlib.Path, required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "source":
            value = source_identity(args.repo.resolve(), args.require_version_allocation)
            write(args.output, value)
        elif args.command == "seal":
            value = seal(args)
        elif args.command == "verify":
            value = verify(args)
        elif args.command == "seal-installer":
            value = seal_installer(args)
        elif args.command == "verify-installer":
            value = verify_installer(args)
        elif args.command == "policy":
            value = policy(args)
        else:
            value = write_probe(args)
        print(json.dumps(value, ensure_ascii=False))
        return 0
    except (OSError, subprocess.CalledProcessError, ValueError, json.JSONDecodeError) as error:
        print(f"Task151 provenance rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
