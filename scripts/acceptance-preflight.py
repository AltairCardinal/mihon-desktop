#!/usr/bin/env python3
"""Read-only candidate/device/session checks; never launch, install, or send input."""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import pathlib
import plistlib
import re
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from mac_acceptance_session import SessionFailure, observe_session

QDC_ONLY_ACTIVE_PATHS = 0x2


class Preflight:
    def __init__(self, args):
        self.args = args
        self.checks = []

    def record(self, layer, status, reason, next_step="", **facts):
        self.checks.append(dict(layer=layer, status=status, reason=reason, next=next_step, **facts))

    def command(self, argv):
        return subprocess.run(argv, check=True, capture_output=True, text=True, encoding="utf-8", timeout=15).stdout

    def artifact(self):
        path = self.args.artifact.resolve()
        if not path.exists():
            self.record("candidate", "ENV_BLOCKED", "Candidate is missing", "Provide an existing isolated candidate")
            return False
        try:
            if self.args.platform == "windows":
                with path.open("rb") as stream:
                    executable = stream.read(2) == b"MZ"
                valid = path.suffix.lower() == ".exe" and executable and all((path.parent / name).is_dir() for name in ("app", "runtime"))
                identity_path = path
            elif self.args.platform == "macos":
                info = plistlib.loads((path / "Contents/Info.plist").read_bytes())
                name = info.get("CFBundleExecutable", "")
                valid = path.suffix == ".app" and name and pathlib.Path(name).name == name and (path / "Contents/MacOS" / name).is_file()
                identity_path = path / "Contents/MacOS" / name
            else:
                with zipfile.ZipFile(path) as archive:
                    valid = path.suffix.lower() == ".apk" and all(name in archive.namelist() for name in ("AndroidManifest.xml", "classes.dex"))
                identity_path = path
            if not valid:
                raise ValueError("Candidate does not have the expected packaged shape")
        except (OSError, ValueError, zipfile.BadZipFile, plistlib.InvalidFileException):
            self.record("candidate", "ENV_BLOCKED", "Candidate platform or packaged shape is invalid", "Use the expected EXE/app/APK candidate")
            return False
        digest = hashlib.sha256()
        with identity_path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        identity = dict(path=str(path), launcherOrApkSha256=digest.hexdigest())
        for manifest_path in (path.parent / "preview-manifest.json", path.parent.parent / "preview-manifest.json"):
            if manifest_path.is_file():
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                if pathlib.Path(manifest.get("artifactPath", "")).resolve() != path:
                    self.record("candidate", "ENV_BLOCKED", "Preview manifest identifies a different candidate", "Select the exact artifact recorded by the preview manifest")
                    return False
                if manifest.get("kind") != "preview" or manifest.get("platform") != self.args.platform:
                    self.record("candidate", "ENV_BLOCKED", "Preview manifest platform/kind does not match", "Use the expected platform's preview artifact")
                    return False
                if manifest.get("build", {}).get("status") != "PASS" or manifest.get("sourceIntegrity", {}).get("status") != "PASS":
                    self.record("candidate", "ENV_BLOCKED", "Preview build/source integrity did not pass", "Build a valid preview of the current inputs")
                    return False
                if self.args.platform == "windows" and manifest.get("productionRuntime", {}).get("status") != "PASS":
                    self.record("candidate", "ENV_BLOCKED", "Required Windows production runtime validation did not pass", "Resolve the preview runtime validation failure")
                    return False
                identity.update(version=manifest.get("version"), source=manifest.get("source"), manifest=str(manifest_path))
                break
        self.record("candidate", "PASS", "Candidate packaged shape and identity observed; signature/provenance are not validated", **identity)
        if self.args.min_free_gib is not None:
            free = shutil.disk_usage(path.parent).free
            status = "PASS" if free >= self.args.min_free_gib * 1024 ** 3 else "ENV_BLOCKED"
            self.record("disk", status, "Free space checked against the caller's budget", "Free space or reduce the requested budget" if status != "PASS" else "", freeGiB=round(free / 1024 ** 3, 2), requiredGiB=self.args.min_free_gib)
        return True

    def android(self):
        adb = self.args.adb or shutil.which("adb")
        if not adb or not pathlib.Path(adb).is_file():
            self.record("device-tool", "ENV_BLOCKED", "adb is unavailable", "Provide --adb with the installed platform-tools executable")
            return
        command = [sys.executable, str(adb)] if str(adb).endswith(".py") else [str(adb)]
        devices = self.command(command + ["devices", "-l"])
        entries = [line.split() for line in devices.splitlines()[1:] if line.strip()]
        selected = [entry for entry in entries if entry[0] == self.args.serial]
        if selected and len(selected[0]) < 2:
            self.record("device-tool", "TOOL_FAIL", "adb returned malformed device metadata", "Inspect the platform-tools output")
            return
        if not self.args.serial or len(selected) != 1 or selected[0][1] != "device":
            self.record("device", "ENV_BLOCKED", "The explicitly selected device is missing, offline, or unauthorized", "Check adb devices -l and supply the intended --serial")
            return
        self.record("device", "PASS", "Explicit device is online; stable identifier omitted")
        shell = command + ["-s", self.args.serial, "shell"]
        api = self.command(shell + ["getprop", "ro.build.version.sdk"]).strip()
        abi = self.command(shell + ["getprop", "ro.product.cpu.abi"]).strip()
        if not api.isdigit() or not abi:
            self.record("device-platform", "TOOL_FAIL", "Device platform metadata is incomplete", "Inspect the device's getprop output")
            return
        self.record("device-platform", "PASS", "Device platform metadata observed", api=int(api), abi=abi)
        window = self.command(shell + ["dumpsys", "window"])
        power = self.command(shell + ["dumpsys", "power"])
        lock = re.findall(r"(?:mShowingLockscreen|isStatusBarKeyguard|mKeyguardShowing|showing)=(true|false)", window)
        interactive = re.findall(r"\bmInteractive=(\S+)", power)
        wakefulness = re.findall(r"\bmWakefulness=(\S+)", power)
        changing = re.findall(r"\bmWakefulnessChanging=(\S+)", power)
        if "true" in lock or "false" in interactive or any(value in {"Asleep", "Dozing"} for value in wakefulness):
            self.record("graphical-session", "ENV_BLOCKED", "Selected device is locked or noninteractive", "Unlock/wake the intended device with authorization")
            return
        unknown_power = (
            any(value not in {"true", "false"} for value in interactive)
            or any(value not in {"Awake", "Asleep", "Dozing"} for value in wakefulness)
            or any(value != "false" for value in changing)
        )
        if not lock or not (interactive or wakefulness) or unknown_power:
            self.record("graphical-session", "NOT_RUN", "Lock/power metadata is absent, unknown, or changing", "Use the platform's supported read-only lock/session probe")
            return
        activity = self.command(shell + ["dumpsys", "activity", "activities"])
        foreground = re.search(r"(?:mResumedActivity|topResumedActivity|ResumedActivity).*? ([A-Za-z0-9_.]+)/", activity)
        if not foreground:
            self.record("foreground", "NOT_RUN", "Foreground activity was not observable", "Observe the target activity before native input")
        elif self.args.package and foreground.group(1) != self.args.package:
            self.record("foreground", "ENV_BLOCKED", "Foreground does not match --package", "Observe or launch the authorized exact candidate separately")
        else:
            self.record("foreground", "PASS", "Foreground activity observed; no input sent")
        self.record("graphical-session", "PASS", "Explicit unlock and interactive/awake metadata observed")

    def windows(self):
        if sys.platform != "win32":
            self.record("host", "ENV_BLOCKED", "Windows preflight must run on the Windows host", "Run this command on the candidate's host")
            return
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        # UINT32 outputs and LONG return stay 32-bit on 64-bit Windows.
        user32.GetDisplayConfigBufferSizes.argtypes = [
            ctypes.c_uint32, ctypes.POINTER(ctypes.c_uint32), ctypes.POINTER(ctypes.c_uint32),
        ]
        user32.GetDisplayConfigBufferSizes.restype = ctypes.c_int32
        active_capacity, mode_capacity = ctypes.c_uint32(), ctypes.c_uint32()
        error = user32.GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, ctypes.byref(active_capacity), ctypes.byref(mode_capacity))
        if error != 0:
            self.record("display-topology", "TOOL_FAIL", "Display topology API query failed; output capacities are not valid",
                        "Inspect the returned Win32 error and recheck the current display/session environment",
                        reasonCode="DISPLAY_TOPOLOGY_API_FAILED", errorCode=error)
        elif active_capacity.value == 0:
            self.record("display-topology", "NOT_RUN", "Successful query returned zero capacity for active display paths",
                        "Check display connections; after authorized bounded recovery, use actual Computer Use capture/input to assess readiness",
                        reasonCode="NO_ACTIVE_DISPLAY_PATH", errorCode=0, activePathCapacity=0, modeCapacity=mode_capacity.value)
        else:
            self.record("display-topology", "PASS", "Display-path capacity metadata observed; not capture/input proof",
                        "Select the exact candidate window and verify actual Computer Use capture/input separately",
                        errorCode=0, activePathCapacity=active_capacity.value, modeCapacity=mode_capacity.value)
        self.record("native-tool", "NOT_RUN", "Computer Use is session-owned and requires its own health/target probe", "Run the documented Node Computer Use import and read-only window probe")
        user32.OpenInputDesktop.argtypes = [ctypes.c_uint, ctypes.c_bool, ctypes.c_uint]
        user32.OpenInputDesktop.restype = ctypes.c_void_p
        user32.CloseDesktop.argtypes = [ctypes.c_void_p]
        desktop = user32.OpenInputDesktop(0, False, 1)
        if not desktop:
            self.record("graphical-session", "ENV_BLOCKED", "Input desktop is unavailable or inaccessible", "Unlock/connect the intended graphical session")
            return
        try:
            name = ctypes.create_unicode_buffer(256)
            size = ctypes.c_uint()
            user32.GetUserObjectInformationW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint, ctypes.POINTER(ctypes.c_uint)]
            if not user32.GetUserObjectInformationW(desktop, 2, name, ctypes.sizeof(name), ctypes.byref(size)):
                self.record("graphical-session", "TOOL_FAIL", "Unable to read input desktop metadata", "Inspect the desktop probe API failure")
            elif name.value.casefold() != "default":
                self.record("graphical-session", "ENV_BLOCKED", "Input desktop is not the normal interactive desktop", "Return to the unlocked candidate session")
            else:
                self.record("graphical-session", "PASS", "Normal input desktop observed; target window has not been selected")
        finally:
            user32.CloseDesktop(desktop)

    def macos(self):
        if sys.platform != "darwin":
            self.record("host", "ENV_BLOCKED", "macOS preflight must run on the Mac host", "Run this command locally on the Mac, optionally through existing SSH")
            return
        state = observe_session(include_foreground=False)
        display_ready = state['online'] and state['asleep'] is False
        self.record("main-display", "PASS" if display_ready else "ENV_BLOCKED",
                    "Main display is awake" if display_ready else "Main display is sleeping or unavailable",
                    "" if display_ready else "Use the authorized Mac acceptance runner for bounded display recovery",
                    online=state['online'], asleep=state['asleep'])
        if not state['sessionPresent']:
            self.record("graphical-session", "ENV_BLOCKED", "No observable graphical session", "Connect to the intended desktop")
            return
        locked = state['reportedLockFlag']
        if not display_ready:
            status, reason = "NOT_RUN", "Display is not awake; an unlock requirement is unconfirmed"
        elif locked is True:
            status, reason = "ENV_BLOCKED", "Awake display reports a locked graphical session"
        else:
            status, reason = ("PASS", "Lock flag observed false") if locked is False else ("NOT_RUN", "Explicit lock flag absent; exact target readiness requires the native runner")
        self.record("graphical-session", status, reason, "Run the authorized exact-target native acceptance separately", reportedLockFlag=locked)
        self.record("native-event-permission", "PASS" if state['nativePermission'] else "ENV_BLOCKED",
                    "Native event permission checked without requesting access", "Use existing permission setup" if not state['nativePermission'] else "")

    def run(self):
        try:
            if self.artifact():
                getattr(self, self.args.platform)()
        except SessionFailure as error:
            self.record("probe", error.status, str(error), "Inspect the current Mac session/tool contract; no wake or device actions were issued")
        except (OSError, ValueError, subprocess.SubprocessError, AttributeError):
            self.record("probe", "TOOL_FAIL", "Read-only probe failed or returned unsupported metadata", "Inspect tool availability and current platform contract; no device actions were issued")
        statuses = {item["status"] for item in self.checks}
        status = next((value for value in ("TOOL_FAIL", "ENV_BLOCKED", "NOT_RUN", "PASS") if value in statuses), "NOT_RUN")
        return dict(scope="read-only-preflight", platform=self.args.platform, status=status,
                    atUtc=datetime.now(timezone.utc).isoformat(), checks=self.checks,
                    nativeInteraction=dict(status="NOT_RUN", reason="No application launch, native input, or product assertions were executed"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("windows", "macos", "android"), required=True)
    parser.add_argument("--artifact", type=pathlib.Path, required=True)
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    parser.add_argument("--package", help="Optional expected Android foreground package")
    parser.add_argument("--min-free-gib", type=float, help="Optional caller-selected free-space budget; no default threshold")
    args = parser.parse_args()
    if args.min_free_gib is not None and args.min_free_gib < 0:
        parser.error("--min-free-gib must be nonnegative")
    if args.package and not re.fullmatch(r"[A-Za-z0-9_.]+", args.package):
        parser.error("--package must be an Android package identifier")
    payload = Preflight(args).run()
    print(json.dumps(payload, ensure_ascii=False))
    return 0 if payload["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
