#!/usr/bin/env python3
"""Bounded native navigation only; UI hierarchy stays in memory and is never logged."""
from dataclasses import dataclass, field
import argparse
import json
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGES = ("app.mihon.desktop.fork.dev", "app.mihon.desktop.fork")
MAX_XML_BYTES = 4 * 1024 * 1024
ALLOWED = {"ENTRY": ("open",), "MAIN": ("settings", "local_setup", "close"),
           "SETTINGS": ("back", "close"), "SIGN_IN": ("back", "close")}
# Only exact product navigation labels are retained. All other UI strings are discarded.
LABELS = {
    "title": ("同步", "Synchronization"), "settings": ("同步设置", "Sync settings"),
    "close": ("关闭同步面板", "Close sync panel"), "back": ("返回", "Back"),
    "connect": ("连接 GitHub", "Connect GitHub"),
    "account": ("GitHub 账号与空间", "GitHub account and space"),
    "startup": ("启动时同步", "Sync on startup"),
    "sign_in": ("请在系统浏览器中授权，本应用不会要求输入 GitHub 密码。",
                "Authorize in your system browser. This app never asks for your GitHub password."),
}
UNSAFE_LABELS = {"立即同步", "Sync now", "正在同步…", "Synchronizing…", "已同步", "Up to date",
                 "设置同步密码", "Set a sync password", "输入同步密码", "Enter sync password",
                 "正在获取 GitHub 验证码…", "Getting a GitHub verification code…",
                 "等待你在浏览器完成授权…", "Waiting for you to authorize in your browser…"}


class Stop(RuntimeError):
    pass


@dataclass(frozen=True)
class Bounds:
    left: int
    top: int
    right: int
    bottom: int

    def point(self):
        return (self.left + self.right) // 2, (self.top + self.bottom) // 2


@dataclass(frozen=True)
class Control:
    name: str
    bounds: Bounds
    enabled: bool


@dataclass
class Snapshot:
    scene: str
    controls: dict = field(default_factory=dict)
    foreign: tuple = ()
    window_token: str = field(default="", repr=False)

    def report(self, package):
        allowed = ALLOWED.get(self.scene, ())
        return {
            "package": package, "scene": self.scene,
            "matches": {name: len(self.controls.get(name, ())) for name in allowed},
            "controls": [{"navigation": name, "enabled": control.enabled,
                          "bounds": [control.bounds.left, control.bounds.top, control.bounds.right, control.bounds.bottom]}
                         for name in allowed for control in self.controls.get(name, ())],
        }


def bounds_of(value, size):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", value)
    if not match:
        raise Stop("INVALID_BOUNDS")
    left, top, right, bottom = map(int, match.groups())
    width, height = size
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        raise Stop("INVALID_BOUNDS")
    return Bounds(left, top, right, bottom)


def parse_hierarchy(xml, package, natural_size):
    if len(xml.encode("utf-8")) > MAX_XML_BYTES or "<!DOCTYPE" in xml or "<!ENTITY" in xml:
        raise Stop("INVALID_HIERARCHY")
    start, end = xml.find("<hierarchy"), xml.rfind("</hierarchy>")
    if start < 0 or end < start:
        raise Stop("INVALID_HIERARCHY")
    try:
        root = ET.fromstring(xml[start:end + len("</hierarchy>")])
    except ET.ParseError:
        raise Stop("INVALID_HIERARCHY") from None
    rotation = root.get("rotation", "")
    if rotation not in ("0", "1", "2", "3"):
        raise Stop("INVALID_HIERARCHY")
    size = natural_size[::-1] if rotation in ("1", "3") else natural_size
    nodes = list(root.iter("node"))
    if len(nodes) > 4096:
        raise Stop("INVALID_HIERARCHY")
    if not any(node.get("package") == package for node in nodes):
        raise Stop("WRONG_HIERARCHY_PACKAGE")
    parents = {child: parent for parent in root.iter() for child in parent}
    controls = {name: {} for name in ("open", "settings", "close", "back", "local_setup")}
    markers = set()
    foreign = []
    editable = False
    for node in nodes:
        if node.get("password") == "true":
            raise Stop("SENSITIVE_OR_LOGIN_SCENE")
        if node.get("class", "").endswith("EditText"):
            editable = True
            continue
        if node.get("package") != package:
            if node.get("package") and (node.get("clickable") == "true" or node.get("focusable") == "true"):
                foreign.append(bounds_of(node.get("bounds", ""), size))
            continue
        text, desc = node.get("text", ""), node.get("content-desc", "")
        if text in UNSAFE_LABELS or desc in UNSAFE_LABELS:
            raise Stop("CONNECTED_OR_ACTIVE_SCENE")
        for name, labels in LABELS.items():
            if text in labels or desc in labels:
                markers.add(name)
        names = []
        if desc in LABELS["title"]:
            names.append("open")
        for name in ("settings", "close", "back"):
            if desc in LABELS[name]:
                names.append(name)
        if text in LABELS["connect"] or desc in LABELS["connect"]:
            names.append("local_setup")
        if not names:
            continue
        owner = node
        chain = []
        while owner is not None and owner.tag == "node":
            chain.append(owner)
            if owner.get("clickable") == "true":
                break
            if len(chain) > 12:
                owner = None
                break
            owner = parents.get(owner)
        if owner is None or owner.tag != "node" or owner.get("package") != package or owner.get("clickable") != "true":
            continue
        # A modal container receives dismiss taps but is not the header/card label's button.
        # Keep its scene marker, and require a navigation owner without nested buttons.
        if any(child is not owner and child.get("clickable") == "true" for child in owner.iter("node")):
            continue
        bounds = bounds_of(owner.get("bounds", ""), size)
        # Never turn a label inside a whole-screen container into a guessed hit target.
        if (bounds.right - bounds.left) * (bounds.bottom - bounds.top) > size[0] * size[1] // 4:
            raise Stop("INVALID_BOUNDS")
        enabled = True
        ancestor = node
        depth = 0
        while ancestor is not None and ancestor.tag == "node":
            enabled = enabled and ancestor.get("enabled") == "true" and ancestor.get("visible-to-user", "true") == "true"
            ancestor = parents.get(ancestor)
            depth += 1
            if depth > 128:
                raise Stop("INVALID_HIERARCHY")
        for name in names:
            controls[name][id(owner)] = Control(name, bounds, enabled)
    controls = {name: tuple(values.values()) for name, values in controls.items()}
    if "sign_in" in markers and controls["back"] and controls["close"] and controls["local_setup"]:
        scene = "SIGN_IN"
    elif {"settings", "account", "startup"} <= markers and controls["back"] and controls["close"]:
        scene = "SETTINGS"
    elif "title" in markers and controls["close"] and controls["settings"] and controls["local_setup"] and not controls["back"]:
        scene = "MAIN"
    elif controls["open"] and not controls["close"] and not controls["back"] and "sign_in" not in markers:
        scene = "ENTRY"
    else:
        scene = "UNKNOWN"
    if editable and scene != "SETTINGS":
        raise Stop("SENSITIVE_OR_LOGIN_SCENE")
    return Snapshot(scene, controls, tuple(foreign))


def locate(snapshot, name, scene):
    if snapshot.scene != scene or scene not in ALLOWED:
        raise Stop("UNEXPECTED_SCENE")
    if name not in ALLOWED[scene]:
        raise Stop("FORBIDDEN_CONTROL")
    controls = snapshot.controls.get(name, ())
    if len(controls) != 1:
        raise Stop("AMBIGUOUS_CONTROL")
    control = controls[0]
    if not control.enabled:
        raise Stop("DISABLED_CONTROL")
    x, y = control.bounds.point()
    if any(bounds.left <= x < bounds.right and bounds.top <= y < bounds.bottom for bounds in snapshot.foreign):
        raise Stop("TARGET_OCCLUDED")
    return control


def guard_policy(output):
    fields = re.findall(r"\b(?:mShowingLockscreen|mKeyguardShowing|isStatusBarKeyguard|mDreamingLockscreen)\s*=\s*(true|false)\b", output)
    delegate = re.search(r"(?:KeyguardServiceDelegate|mKeyguardDelegate)[\s\S]*", output)
    if delegate:
        fields += re.findall(r"\b(?:showing|inputRestricted)\s*=\s*(true|false)\b", delegate.group())
    if "true" in fields or re.search(r"\b(?:screenState=SCREEN_STATE_OFF|interactiveState=INTERACTIVE_STATE_ASLEEP)\b", output):
        raise Stop("SCREEN_LOCKED")
    if not fields:
        raise Stop("LOCK_STATE_UNKNOWN")


def guard_foreground(output, package):
    focus = re.findall(r"mCurrentFocus=Window\{([a-fA-F0-9]+)\s+u\d+\s+([A-Za-z0-9_.]+)/[^}\r\n]+\}", output)
    if len(focus) != 1 or focus[0][1] != package:
        raise Stop("WRONG_FOREGROUND_PACKAGE")
    return focus[0][0]


def parse_size(output):
    matches = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", output)
    if not matches:
        raise Stop("DISPLAY_SIZE_UNKNOWN")
    size = tuple(map(int, matches[-1]))
    if not all(0 < value <= 32768 for value in size):
        raise Stop("DISPLAY_SIZE_UNKNOWN")
    return size


class Device:
    def __init__(self, adb, serial, package, runner=None):
        if package not in PACKAGES or not re.fullmatch(r"[A-Za-z0-9._:-]{1,160}", serial):
            raise Stop("INVALID_TARGET")
        self.adb, self.serial, self.package, self.runner = adb, serial, package, runner
        self.runner = runner or subprocess.run

    def run(self, *args):
        try:
            result = self.runner([str(self.adb), "-s", self.serial, *args], capture_output=True,
                                 text=True, encoding="utf-8", timeout=25, check=False)
        except (OSError, subprocess.TimeoutExpired, UnicodeError):
            raise Stop("ADB_COMMAND_FAILED") from None
        if result.returncode != 0:
            raise Stop("ADB_COMMAND_FAILED")
        return result.stdout

    def guard(self):
        if self.run("get-state").strip() != "device":
            raise Stop("DEVICE_NOT_READY")
        guard_policy(self.run("shell", "dumpsys", "window", "policy"))
        return guard_foreground(self.run("shell", "dumpsys", "window"), self.package)

    def dump(self):
        path = f"/data/local/tmp/mihon-sync-native-{uuid.uuid4().hex}.xml"
        self.run("shell", "test", "!", "-e", path)
        try:
            output = self.run("shell", "uiautomator", "dump", "--compressed", path)
            if "ERROR" in output.upper() or f"UI hierchary dumped to: {path}" not in output:
                raise Stop("DUMP_FAILED")
            self.run("shell", "chmod", "600", path)
            size = self.run("shell", "stat", "-c", "%s", path).strip()
            if not size.isdigit() or not (0 < int(size) <= MAX_XML_BYTES):
                raise Stop("INVALID_HIERARCHY")
            return self.run("exec-out", "cat", path)
        finally:
            try:
                self.run("shell", "rm", "-f", path)
            except Stop:
                raise Stop("DUMP_CLEANUP_FAILED") from None

    def capture(self):
        token = self.guard()
        size = parse_size(self.run("shell", "wm", "size"))
        snapshot = parse_hierarchy(self.dump(), self.package, size)
        if self.guard() != token:
            raise Stop("WINDOW_CHANGED")
        snapshot.window_token = token
        return snapshot

    def wait(self, scene):
        deadline = time.monotonic() + 8
        while True:
            snapshot = self.capture()
            if snapshot.scene == scene:
                return snapshot
            if snapshot.scene != "UNKNOWN" or time.monotonic() >= deadline:
                raise Stop("UNEXPECTED_SCENE")
            time.sleep(0.2)

    def tap(self, name, scene):
        snapshot = self.capture()
        control = locate(snapshot, name, scene)
        if self.guard() != snapshot.window_token:
            raise Stop("WINDOW_CHANGED")
        x, y = control.bounds.point()
        self.run("shell", "input", "tap", str(x), str(y))

    def back(self, scene):
        if scene not in ("SETTINGS", "SIGN_IN"):
            raise Stop("FORBIDDEN_CONTROL")
        snapshot = self.capture()
        locate(snapshot, "back", scene)
        if self.guard() != snapshot.window_token:
            raise Stop("WINDOW_CHANGED")
        self.run("shell", "input", "keyevent", "KEYCODE_BACK")


def navigate(device, emit, *, fresh_debug=False):
    # MAIN alone cannot prove credential absence: BeginSetup may otherwise start remote discovery.
    # Explicit caller confirmation is limited to a freshly installed, unauthenticated Debug profile.
    if device.package != PACKAGES[0] or not fresh_debug:
        raise Stop("FRESH_DEBUG_REQUIRED")
    installed = device.run("shell", "pm", "path", device.package)
    if not re.search(r"(?m)^package:/[^\r\n]+\.apk\s*$", installed):
        raise Stop("PACKAGE_NOT_INSTALLED")
    emit(device.wait("ENTRY").report(device.package))
    for name, before, after in (("open", "ENTRY", "MAIN"), ("settings", "MAIN", "SETTINGS")):
        device.tap(name, before)
        emit(device.wait(after).report(device.package))
    device.back("SETTINGS")
    emit(device.wait("MAIN").report(device.package))
    device.tap("local_setup", "MAIN")
    emit(device.wait("SIGN_IN").report(device.package))
    device.back("SIGN_IN")
    emit(device.wait("MAIN").report(device.package))
    device.tap("close", "MAIN")
    emit(device.wait("ENTRY").report(device.package))
    emit({"status": "PASS", "package": device.package,
          "scope": "ENTRY_MAIN_SETTINGS_BACK_SIGN_IN_BACK_CLOSE_NAVIGATION_ONLY",
          "passwordAndRemoteVerified": False})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", choices=PACKAGES, default=PACKAGES[0])
    parser.add_argument("--adb", default="D:/Android/Sdk/platform-tools/adb.exe")
    parser.add_argument("--inspect", action="store_true", help="Read only: never send native input")
    parser.add_argument("--fresh-debug", action="store_true",
                        help="Confirm fresh Debug installation with no stored authorization; Release is inspect-only")
    args = parser.parse_args()
    def emit(value):
        print(json.dumps(value, ensure_ascii=False))
    try:
        device = Device(args.adb, args.serial, args.package)
        if args.inspect:
            emit({"status": "READ_ONLY", **device.capture().report(device.package)})
        else:
            navigate(device, emit, fresh_debug=args.fresh_debug)
        return 0
    except Stop as stopped:
        emit({"status": "STOP", "package": args.package, "reason": str(stopped)})
        return 2
    except Exception:
        # Never echo subprocess output, XML, device identifiers or exception payloads.
        emit({"status": "STOP", "package": args.package, "reason": "TOOL_FAILURE"})
        return 2


if __name__ == "__main__":
    sys.exit(main())
