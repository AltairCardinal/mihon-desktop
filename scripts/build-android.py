#!/usr/bin/env python3
"""Thin Android build/delivery entry; Gradle and Android SDK remain authoritative."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "http://schemas.android.com/apk/res/android"
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}


def build_configuration(variant, repository=None):
    if repository is not None:
        if variant != "debug" or not re.fullmatch(r"mihon-sync-acceptance-[a-z0-9][a-z0-9-]{0,76}", repository):
            raise ValueError("A dedicated sync acceptance repository is only allowed for Debug")
    config = {"variant": variant, "syncAcceptanceRepository": repository}
    encoded = json.dumps(config, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {**config, "buildConfigurationInputsSha256": hashlib.sha256(encoded).hexdigest()}


def run(command, *, cwd=ROOT, check=True):
    result = subprocess.run([str(item) for item in command], cwd=cwd, capture_output=True, text=True, encoding="utf-8")
    if check and result.returncode:
        raise ValueError(f"Command failed ({result.returncode}): {command[0]}\n{result.stdout}\n{result.stderr}")
    return result


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def metadata(root=ROOT):
    values = {}
    for line in (root / "gradle/android-release.properties").read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        if key.strip() in values:
            raise ValueError("Duplicate Android metadata key")
        values[key.strip()] = value.strip()
    if values.get("applicationId") != "app.mihon.desktop.fork":
        raise ValueError("Unexpected formal application identity")
    if not re.fullmatch(r"[1-9][0-9]*", values.get("versionCode", "")):
        raise ValueError("Invalid Android versionCode")
    if not re.fullmatch(r"[A-Za-z0-9_.+-]+", values.get("versionName", "")):
        raise ValueError("Invalid Android versionName")
    if not re.fullmatch(r"[0-9a-f]{64}", values.get("releaseCertificateSha256", "")):
        raise ValueError("Invalid Android certificate fingerprint")
    values["versionCode"] = int(values["versionCode"])
    return values


def sdk_config(root=ROOT):
    source = (root / "buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt").read_text(encoding="utf-8")
    values = {}
    for key in ("COMPILE_SDK", "TARGET_SDK", "MIN_SDK"):
        match = re.search(rf"const val {key} = (\d+)", source)
        if not match:
            raise ValueError(f"Cannot read authoritative AndroidConfig.{key}")
        values[key] = int(match.group(1))
    return values


class AndroidTools:
    def __init__(self, root=ROOT):
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        local = root / "local.properties"
        if local.exists():
            for line in local.read_text(encoding="utf-8").splitlines():
                if line.startswith("sdk.dir="):
                    sdk = line.partition("=")[2].replace("\\:", ":").replace("\\\\", "\\")
        if not sdk:
            raise ValueError("Set ANDROID_HOME or sdk.dir in local.properties")
        self.sdk = Path(sdk).resolve()
        self.config = sdk_config(root)
        # AGP's default installed baseline for this compile SDK; kept explicit in evidence.
        self.build_tools_version = f"{self.config['COMPILE_SDK']}.0.0"
        self.build_tools = self.sdk / "build-tools" / self.build_tools_version
        self.jar = self.sdk / "platforms" / f"android-{self.config['COMPILE_SDK']}" / "android.jar"
        self.aapt = self.build_tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
        self.signer = self.build_tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
        self.align = self.build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
        self.adb = self.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        for file in (self.jar, self.aapt, self.signer, self.align, self.adb):
            if not file.is_file():
                raise ValueError(f"Required Android SDK file is missing: {file}")


def inspect_apk(path, tools, *, signed=True):
    """Read actual binary manifest, native libraries, alignment and signature."""
    path = Path(path).resolve(strict=True)
    badging = run([tools.aapt, "dump", "badging", path]).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging, re.M)
    minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    if not package or not minimum or not target:
        raise ValueError("APK is missing package/version/SDK metadata")
    with zipfile.ZipFile(path) as archive:
        abis = sorted({name.split("/")[1] for name in archive.namelist() if re.match(r"lib/[^/]+/[^/]+\.so$", name)})
    run([tools.align, "-c", "-p", "4", path])
    verification = run([tools.signer, "verify", "--verbose", "--print-certs", path], check=False)
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", verification.stdout, re.M)
    schemes = re.findall(r"Verified using (v[123](?:\.1)?) scheme[^:]*: true", verification.stdout)
    if signed and (verification.returncode or len(certificates) != 1 or not ({"v2", "v3", "v3.1"} & set(schemes))):
        raise ValueError("APK signature verification failed or signer/scheme is unsupported")
    if not signed and verification.returncode == 0:
        raise ValueError("Unsigned candidate unexpectedly contains a valid signature")
    return {
        "applicationId": package[1], "versionCode": int(package[2]), "versionName": package[3],
        "minSdk": int(minimum[1]), "targetSdk": int(target[1]), "abis": abis,
        "debuggable": bool(re.search(r"^application-debuggable", badging, re.M)),
        "sha256": sha256(path), "certificateSha256": certificates[0].lower() if signed else None,
        "signatureVerified": signed, "signatureSchemes": schemes if signed else [],
    }


def enforce_identity(actual, release, config, variant, *, current_version=False):
    expected_id = release["applicationId"] + (".dev" if variant == "debug" else "")
    if actual["applicationId"] != expected_id or actual["debuggable"] != (variant == "debug"):
        raise ValueError("APK identity/debuggable state does not match its delivery variant")
    if current_version and (actual["versionCode"] != release["versionCode"] or (variant != "debug" and actual["versionName"] != release["versionName"])):
        raise ValueError("APK version differs from the allocated candidate")
    if actual["minSdk"] != config["MIN_SDK"] or actual["targetSdk"] != config["TARGET_SDK"]:
        raise ValueError("APK SDK values differ from AndroidConfig")
    if not actual["abis"] or not set(actual["abis"]).issubset(ABIS):
        raise ValueError("APK contains no supported native ABI or an unknown ABI")
    if variant == "release" and (not actual["signatureVerified"] or actual["certificateSha256"] != release["releaseCertificateSha256"]):
        raise ValueError("Formal release requires the established release certificate")


def load_provenance(root):
    spec = importlib.util.spec_from_file_location("mihon_provenance", root / "scripts/task15-build-provenance.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def source_snapshot(root=ROOT):
    """Reuse production exclusions; support frozen dirty/untracked candidate inputs."""
    provenance = load_provenance(root)
    revision = run(["git", "rev-parse", "HEAD"], cwd=root).stdout.strip()
    tracked = run(["git", "ls-files", "-s", "-z"], cwd=root).stdout.split("\0")
    records = []
    indexed = {}
    for item in filter(None, tracked):
        index, relative = item.split("\t", 1)
        if provenance.is_product_input(PurePosixPath(relative)):
            file = root / relative
            record = f"{index.split()[0]}\t{relative}\t{sha256(file) if file.is_file() else 'DELETED'}\n"
            records.append(record)
            indexed[relative] = record
    untracked = run(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=root).stdout.split("\0")
    untracked_records = []
    for relative in filter(None, untracked):
        if provenance.is_untracked_product_input(PurePosixPath(relative)) or (relative.startswith("scripts/") and not relative.startswith("scripts/tests/")):
            untracked_records.append(f"untracked\t{relative}\t{sha256(root / relative)}\n")
    # Hash changed input records relative to the recorded HEAD, without a giant diff file.
    changed = run(["git", "diff", "--name-only", "-z", "HEAD"], cwd=root).stdout.split("\0")
    diff_records = [indexed.get(path, f"deleted\t{path}\n") for path in changed if path and provenance.is_product_input(PurePosixPath(path))]
    return {
        "sourceRevision": revision,
        "sourceDiffSha256": hashlib.sha256("".join(sorted(diff_records)).encode()).hexdigest(),
        "untrackedInputsSha256": hashlib.sha256("".join(sorted(untracked_records)).encode()).hexdigest(),
        "productionInputsSha256": hashlib.sha256("".join(sorted(records + untracked_records)).encode()).hexdigest(),
    }


def java_version(root=ROOT):
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") if os.environ.get("JAVA_HOME") else "java"
    result = run([java, "-version"])
    version = re.search(r'version "(\d+)[^"]*"', result.stderr + result.stdout)
    expected = (root / ".github/.java-version").read_text(encoding="utf-8").strip()
    if not version or version[1] != expected:
        raise ValueError(f"Android builds require JDK {expected}")
    return version[0]


def signing_preflight(tools, root=ROOT):
    if os.name != "nt":
        raise ValueError("Formal signing currently requires the Windows DPAPI adapter; use --unsigned in CI")
    run(["powershell.exe", "-NoProfile", "-File", root / "scripts/sign-android-fork-release.ps1", "-CheckOnly", "-BuildTools", tools.build_tools], cwd=root)


def coordinator_status(root=ROOT):
    spec = importlib.util.spec_from_file_location("android_coordinator", root / "scripts/gradle-coordinator.py")
    coordinator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(coordinator)
    active = []
    for file in (root / ".gradle-coordinator").glob("*.json"):
        state = coordinator.load_state(file.parent, file.stem)
        if state and state.get("status") in coordinator.ACTIVE:
            active.append({"key": file.stem, "status": state["status"], "alive": coordinator.active_process_is_alive(state)})
    return active


def verify_artifact(path, tools, root=ROOT):
    path = Path(path).resolve(strict=True)
    record = json.loads((path.parent / "artifact.json").read_text(encoding="utf-8"))
    if record.get("apk") != path.name or record.get("variant") not in {"release", "debug", "unsigned"}:
        raise ValueError("Artifact manifest does not identify this APK")
    if sha256(path) != record.get("sha256"):
        raise ValueError("APK hash differs from artifact manifest")
    actual = inspect_apk(path, tools, signed=record["variant"] != "unsigned")
    enforce_identity(actual, metadata(root), tools.config, record["variant"])
    for field, value in actual.items():
        if record.get(field) != value:
            raise ValueError(f"Artifact manifest disagrees with APK: {field}")
    for field in ("sourceRevision", "sourceDiffSha256", "productionInputsSha256", "untrackedInputsSha256"):
        if not re.fullmatch(r"[0-9a-f]{40,64}", record.get(field, "")):
            raise ValueError(f"Artifact is missing source provenance: {field}")
    formal = record["variant"] != "debug"
    if "syncAcceptanceRepository" in record or "buildConfigurationInputsSha256" in record:
        config = build_configuration(record["variant"], record.get("syncAcceptanceRepository"))
        if record.get("buildConfigurationInputsSha256") != config["buildConfigurationInputsSha256"]:
            raise ValueError("Artifact build configuration inputs have changed")
    if any(record.get(key) is not expected for key, expected in {"r8": formal, "resourceShrinking": formal, "telemetry": False, "updater": False}.items()):
        raise ValueError("Artifact build configuration violates the fork baseline")
    if formal:
        mapping = record.get("mapping")
        if not isinstance(mapping, dict) or mapping.get("path") != "mapping/mapping.txt":
            raise ValueError("Artifact R8 mapping reference is missing or invalid")
        mapping_path = path.parent / mapping["path"]
        if not mapping_path.is_file() or sha256(mapping_path) != mapping.get("sha256"):
            raise ValueError("Artifact R8 mapping is missing or has changed")
    return record


def gradle_build(variant, source_inputs, *, offline=False, root=ROOT, repository=None):
    config = build_configuration(variant, repository)
    request = uuid.uuid4().hex
    apk_variant = "debug" if variant == "debug" else "release"
    command = [sys.executable, root / "scripts/gradle-coordinator.py", "run", "--key", "android-candidate", "--timeout-seconds", "3600", "--",
               root / ("gradlew.bat" if os.name == "nt" else "gradlew"), "--no-parallel", "--max-workers=2"]
    if offline:
        command.append("--offline")
    if repository is not None:
        command.append(f"-Pmihon.syncAcceptanceRepository={repository}")
    command.extend(["-I", root / "scripts/android-candidate-evidence.init.gradle",
                    f"-Pandroid.candidateRequest={request}", f"-Pandroid.candidateInputs={source_inputs}",
                    f"-Pandroid.candidateConfigurationInputs={config['buildConfigurationInputsSha256']}",
                    f"-Pandroid.candidateVariant={apk_variant}", ":app:recordAndroidCandidate"])
    print("Gradle log: " + str(root / ".gradle-coordinator/android-candidate.log"), flush=True)
    result = subprocess.run([str(part) for part in command], cwd=root)
    if result.returncode:
        raise ValueError("Gradle did not complete successfully; no existing APK will be adopted")
    evidence = json.loads((root / "app/build/reports/android-candidate.json").read_text(encoding="utf-8"))
    if evidence.get("requestId") != request or evidence.get("sourceInputs") != source_inputs or evidence.get("variant") != apk_variant:
        raise ValueError("Gradle produced no matching evidence for this invocation")
    if evidence.get("configurationInputs") != config["buildConfigurationInputsSha256"] or evidence.get("syncAcceptanceRepository") != repository:
        raise ValueError("Gradle build configuration does not match this invocation")
    return evidence


def build_candidate(args, tools, root=ROOT):
    release = metadata(root)
    jdk = java_version(root)
    variant = "debug" if args.action == "debug" else ("unsigned" if args.unsigned else "release")
    repository = getattr(args, "sync_acceptance_repository", None)
    config = build_configuration(variant, repository)
    if variant == "release":
        signing_preflight(tools, root)
    before = source_snapshot(root)
    artifact_root = root / "app/artifacts/android"
    artifact_root.mkdir(parents=True, exist_ok=True)
    stem = f"{release['versionName']}-vc{release['versionCode']}"
    # Each different formal candidate consumes a code, even across source revisions.
    if variant == "release" and any(artifact_root.glob(f"*-vc{release['versionCode']}-*-release")):
        raise ValueError("A formal candidate already reserved this version; allocate a new versionCode")
    destination = artifact_root / f"{stem}-{before['sourceRevision'][:10]}-{variant}"
    if variant == "debug":
        destination = artifact_root / f"{stem}-{before['sourceRevision'][:10]}-debug-{uuid.uuid4().hex[:12]}"
    destination.mkdir()  # Exclusive reservation; failed candidates remain visibly incomplete.
    evidence = gradle_build(variant, before["productionInputsSha256"], offline=args.offline, root=root, repository=repository)
    if before != source_snapshot(root):
        raise ValueError("Source inputs changed during the build; candidate rejected")
    apk_variant = "debug" if variant == "debug" else "release"
    name = "app-universal-debug.apk" if variant == "debug" else "app-universal-release-unsigned.apk"
    built = root / "app/build/outputs/apk" / apk_variant / name
    if sha256(built) != evidence.get("apkSha256"):
        raise ValueError("APK differs from the completed Gradle task's output")
    actual = inspect_apk(built, tools, signed=variant == "debug")
    enforce_identity(actual, release, tools.config, "debug" if variant == "debug" else "unsigned", current_version=True)
    if set(actual["abis"]) != ABIS:
        raise ValueError("Universal APK is missing a supported ABI")
    output = destination / f"Mihon-Fork-{stem}-{variant}-universal.apk"
    if variant == "release":
        frozen_input = destination / ".unsigned-input.apk"
        with built.open("rb") as source, frozen_input.open("xb") as target:
            shutil.copyfileobj(source, target)
        try:
            if sha256(frozen_input) != evidence.get("apkSha256"):
                raise ValueError("Unsigned APK changed before signing")
            run(["powershell.exe", "-NoProfile", "-File", root / "scripts/sign-android-fork-release.ps1", "-InputApk", frozen_input, "-OutputApk", output, "-BuildTools", tools.build_tools], cwd=root)
        finally:
            frozen_input.unlink()
    else:
        with built.open("rb") as source, output.open("xb") as target:
            shutil.copyfileobj(source, target)
    actual = inspect_apk(output, tools, signed=variant != "unsigned")
    if variant != "release" and actual["sha256"] != evidence.get("apkSha256"):
        raise ValueError("APK changed while copying the completed build output")
    enforce_identity(actual, release, tools.config, variant, current_version=True)
    if before != source_snapshot(root):
        raise ValueError("Source inputs changed before candidate publication")
    mapping = root / "app/build/outputs/mapping/release/mapping.txt"
    mapping_record = None
    if variant != "debug":
        if not mapping.is_file():
            raise ValueError("Release R8 mapping is missing")
        (destination / "mapping").mkdir()
        shutil.copyfile(mapping, destination / "mapping/mapping.txt")
        copied_hash = sha256(destination / "mapping/mapping.txt")
        if copied_hash != evidence.get("mappingSha256"):
            raise ValueError("R8 mapping differs from this Gradle invocation")
        mapping_record = {"path": "mapping/mapping.txt", "sha256": copied_hash}
    wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    gradle_version = re.search(r"gradle-([0-9.]+)-(?:bin|all)\.zip", wrapper)
    record = {**before, **actual, **config, "apk": output.name, "variant": variant,
              "createdAt": datetime.now(timezone.utc).isoformat(), "jdk": jdk,
              "gradle": gradle_version[1] if gradle_version else "unknown",
              "compileSdk": tools.config["COMPILE_SDK"], "buildTools": tools.build_tools_version,
              "r8": variant != "debug", "resourceShrinking": variant != "debug",
              "telemetry": False, "updater": False, "mapping": mapping_record,
              "acceptance": "candidate-only; runtime and upgrade acceptance are not implied"}
    with (destination / "artifact.json").open("x", encoding="utf-8") as stream:
        json.dump(record, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    verify_artifact(output, tools, root)
    print(f"Final {variant} APK: {output}\nSHA256: {actual['sha256']}")
    return output


def adb_command(tools, serial, *args, check=True):
    if not serial or not serial.strip():
        raise ValueError("An explicit device serial is required")
    return run([tools.adb, "-s", serial, *args], check=check)


def installed_apk(tools, serial, application_id, temporary):
    listing = adb_command(tools, serial, "shell", "pm", "list", "packages", application_id)
    packages = [line.strip() for line in listing.stdout.splitlines() if line.strip()]
    if any(not re.fullmatch(r"package:[A-Za-z0-9_.]+", line) for line in packages):
        raise ValueError("Package manager returned an invalid package listing")
    if f"package:{application_id}" not in packages:
        return None
    response = adb_command(tools, serial, "shell", "pm", "path", application_id)
    paths = [line[len("package:"):].strip() for line in response.stdout.splitlines() if line.startswith("package:")]
    if not paths:
        raise ValueError("Listed application has no readable APK path")
    if len(paths) != 1:
        raise ValueError("Installed split APKs require a separate verified upgrade path")
    destination = Path(temporary) / "installed.apk"
    adb_command(tools, serial, "pull", paths[0], destination)
    return inspect_apk(destination, tools)


def install_artifact(path, serial, tools, root=ROOT):
    record = verify_artifact(path, tools, root)
    if record["variant"] == "unsigned":
        raise ValueError("Unsigned CI artifacts cannot be installed")
    state = adb_command(tools, serial, "get-state").stdout.strip()
    if state != "device":
        raise ValueError("Selected device is not ready")
    api = int(adb_command(tools, serial, "shell", "getprop", "ro.build.version.sdk").stdout.strip())
    abis = adb_command(tools, serial, "shell", "getprop", "ro.product.cpu.abilist").stdout.strip().split(",")
    if api < record["minSdk"] or not set(abis).intersection(record["abis"]):
        raise ValueError("APK is incompatible with the selected device API/ABI")
    with tempfile.TemporaryDirectory(prefix="mihon-installed-") as directory:
        previous = installed_apk(tools, serial, record["applicationId"], directory)
        if previous:
            if previous["applicationId"] != record["applicationId"] or previous["certificateSha256"] != record["certificateSha256"]:
                raise ValueError("Installed application identity/certificate differs")
            if previous["sha256"] == record["sha256"]:
                print("Already installed: identical APK; no device changes")
                return
            if previous["versionCode"] > record["versionCode"] or (previous["versionCode"] == record["versionCode"] and record["variant"] != "debug"):
                raise ValueError("A different APK must have a strictly greater versionCode")
        # Recheck immediately before the only device write operation.
        verify_artifact(path, tools, root)
        result = adb_command(tools, serial, "install", "-r", Path(path).resolve())
        if not re.search(r"^Success\s*$", result.stdout, re.M):
            raise ValueError("adb did not report a successful installation")
        installed = installed_apk(tools, serial, record["applicationId"], directory)
        if not installed or any(installed[key] != record[key] for key in ("applicationId", "versionCode", "versionName", "certificateSha256", "sha256")):
            raise ValueError("Installed APK does not match the verified candidate")
    print("Installed and verified; application launch/business acceptance remains pending")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    check = commands.add_parser("check")
    check.add_argument("--signing", action="store_true")
    check.add_argument("--serial")
    debug = commands.add_parser("debug")
    debug.add_argument("--offline", action="store_true")
    debug.add_argument("--sync-acceptance-repository")
    candidate = commands.add_parser("candidate")
    candidate.add_argument("--unsigned", action="store_true")
    candidate.add_argument("--offline", action="store_true")
    verify = commands.add_parser("verify")
    verify.add_argument("--artifact", type=Path, required=True)
    install = commands.add_parser("install")
    install.add_argument("--artifact", type=Path, required=True)
    install.add_argument("--serial", required=True)
    args = parser.parse_args(argv)
    try:
        tools = AndroidTools()
        if args.action == "check":
            result = {"release": metadata(), "jdk": java_version(), "sdk": tools.config, "buildTools": tools.build_tools_version, "source": source_snapshot(), "coordinator": coordinator_status()}
            if args.signing:
                signing_preflight(tools)
                result["signing"] = "verified"
            if args.serial:
                with tempfile.TemporaryDirectory(prefix="mihon-check-") as directory:
                    installed = installed_apk(tools, args.serial, result["release"]["applicationId"], directory)
                    result["installed"] = installed
            print(json.dumps(result, ensure_ascii=False, indent=2))
        elif args.action in {"debug", "candidate"}:
            build_candidate(args, tools)
        elif args.action == "verify":
            print(json.dumps(verify_artifact(args.artifact, tools), ensure_ascii=False, indent=2))
        else:
            install_artifact(args.artifact, args.serial, tools)
        return 0
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
