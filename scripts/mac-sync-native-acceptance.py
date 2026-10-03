#!/usr/bin/env python3
"""Exercise an isolated packaged Mac app using native events, never HTTP UI actions.

The caller launches the app through LaunchServices. This tool only reads Test Mode
state and control metadata; it neither signs in nor reads screen pixels.
"""

import argparse
import ctypes
import json
import math
import pathlib
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class NativeEvents:
    class Point(ctypes.Structure):
        _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double)]

    def __init__(self, pid):
        self.pid = pid
        self.cg = ctypes.CDLL("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
        self.cf = ctypes.CDLL("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
        self.cg.CGPreflightPostEventAccess.restype = ctypes.c_bool
        require(self.cg.CGPreflightPostEventAccess(), "Native event permission is unavailable")
        self.cg.CGEventCreateKeyboardEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint16, ctypes.c_bool]
        self.cg.CGEventCreateKeyboardEvent.restype = ctypes.c_void_p
        self.cg.CGEventCreateMouseEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint32, self.Point, ctypes.c_uint32]
        self.cg.CGEventCreateMouseEvent.restype = ctypes.c_void_p
        self.cg.CGEventSetFlags.argtypes = [ctypes.c_void_p, ctypes.c_uint64]
        self.cg.CGEventSetIntegerValueField.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int64]
        self.cg.CGEventPostToPid.argtypes = [ctypes.c_int32, ctypes.c_void_p]
        self.cg.CGEventPost.argtypes = [ctypes.c_uint32, ctypes.c_void_p]
        self.cf.CFRelease.argtypes = [ctypes.c_void_p]

    def activate(self):
        require(not self.screen_locked(), "Mac screen is locked; unlock it locally before native acceptance")
        ctypes.CDLL("/System/Library/Frameworks/AppKit.framework/AppKit")
        objc = ctypes.CDLL("/usr/lib/libobjc.A.dylib")
        objc.objc_getClass.argtypes = [ctypes.c_char_p]
        objc.objc_getClass.restype = ctypes.c_void_p
        objc.sel_registerName.argtypes = [ctypes.c_char_p]
        objc.sel_registerName.restype = ctypes.c_void_p
        send_id = ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int32)(
            ("objc_msgSend", objc))
        send_bool = ctypes.CFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ulong)(
            ("objc_msgSend", objc))
        app = send_id(objc.objc_getClass(b"NSRunningApplication"),
                      objc.sel_registerName(b"runningApplicationWithProcessIdentifier:"), self.pid)
        require(bool(app), "Observed PID is not a registered running application")
        require(send_bool(app, objc.sel_registerName(b"activateWithOptions:"), 3),
                "Could not activate the exact application PID")

    def screen_locked(self):
        self.cg.CGSessionCopyCurrentDictionary.restype = ctypes.c_void_p
        self.cf.CFStringCreateWithCString.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_uint32]
        self.cf.CFStringCreateWithCString.restype = ctypes.c_void_p
        self.cf.CFDictionaryGetValue.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        self.cf.CFDictionaryGetValue.restype = ctypes.c_void_p
        self.cf.CFGetTypeID.argtypes = [ctypes.c_void_p]
        self.cf.CFGetTypeID.restype = ctypes.c_ulong
        self.cf.CFBooleanGetTypeID.restype = ctypes.c_ulong
        self.cf.CFBooleanGetValue.argtypes = [ctypes.c_void_p]
        self.cf.CFBooleanGetValue.restype = ctypes.c_bool
        session = self.cg.CGSessionCopyCurrentDictionary()
        require(bool(session), "Mac graphical session metadata is unavailable")
        key = self.cf.CFStringCreateWithCString(None, b"CGSSessionScreenIsLocked", 0x08000100)
        try:
            require(bool(key), "Could not allocate the session metadata key")
            value = self.cf.CFDictionaryGetValue(session, key)
            if not value:
                return False  # No explicit lock flag; window and focus checks still apply.
            require(self.cf.CFGetTypeID(value) == self.cf.CFBooleanGetTypeID(), "Unsupported screen lock metadata")
            return bool(self.cf.CFBooleanGetValue(value))
        finally:
            if key:
                self.cf.CFRelease(key)
            self.cf.CFRelease(session)

    def post(self, event, system_mouse=False):
        require(bool(event), "CoreGraphics could not allocate an event")
        try:
            if system_mouse:
                self.cg.CGEventPost(0, event)
            else:
                self.cg.CGEventPostToPid(self.pid, event)
        finally:
            self.cf.CFRelease(event)

    def key(self, code, shift=False):
        require(not self.screen_locked(), "Mac screen locked during acceptance; stop native input")
        for down in (True, False):
            event = self.cg.CGEventCreateKeyboardEvent(None, code, down)
            require(bool(event), "CoreGraphics could not allocate a keyboard event")
            self.cg.CGEventSetFlags(event, 1 << 17 if shift else 0)
            self.post(event)
        time.sleep(0.15)

    def click(self, bounds):
        require(not self.screen_locked(), "Mac screen locked during acceptance; stop native input")
        x, y, width, height = (bounds[k] for k in ("x", "y", "width", "height"))
        require(all(math.isfinite(v) for v in (x, y, width, height)) and width > 0 and height > 0,
                "Control has invalid screen bounds")
        require(self.visible_at(bounds), "The native hit target at the click point is not the verified application")
        point = self.Point(x + width / 2, y + height / 2)
        for kind in (5, 1, 2):  # moved, left down, left up
            event = self.cg.CGEventCreateMouseEvent(None, kind, point, 0)
            require(bool(event), "CoreGraphics could not allocate a mouse event")
            self.cg.CGEventSetIntegerValueField(event, 1, 1)  # click state
            self.post(event, system_mouse=True)
        time.sleep(0.2)

    def visible_at(self, bounds):
        """Check window geometry and native hit-test PID, never names or pixels."""
        class Rect(ctypes.Structure):
            _fields_ = [(name, ctypes.c_double) for name in ("x", "y", "width", "height")]

        self.cg.CGWindowListCopyWindowInfo.argtypes = [ctypes.c_uint32, ctypes.c_uint32]
        self.cg.CGWindowListCopyWindowInfo.restype = ctypes.c_void_p
        self.cg.CGRectMakeWithDictionaryRepresentation.argtypes = [ctypes.c_void_p, ctypes.POINTER(Rect)]
        self.cg.CGRectMakeWithDictionaryRepresentation.restype = ctypes.c_bool
        self.cf.CFArrayGetCount.argtypes = [ctypes.c_void_p]
        self.cf.CFArrayGetCount.restype = ctypes.c_long
        self.cf.CFArrayGetValueAtIndex.argtypes = [ctypes.c_void_p, ctypes.c_long]
        self.cf.CFArrayGetValueAtIndex.restype = ctypes.c_void_p
        self.cf.CFDictionaryGetValue.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        self.cf.CFDictionaryGetValue.restype = ctypes.c_void_p
        self.cf.CFStringCreateWithCString.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_uint32]
        self.cf.CFStringCreateWithCString.restype = ctypes.c_void_p
        self.cf.CFNumberGetValue.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
        self.cf.CFNumberGetValue.restype = ctypes.c_bool
        windows = self.cg.CGWindowListCopyWindowInfo(1, 0)  # on-screen only
        require(bool(windows), "Could not read on-screen window metadata")
        keys = [self.cf.CFStringCreateWithCString(None, name, 0x08000100) for name in
                (b"kCGWindowOwnerPID", b"kCGWindowLayer", b"kCGWindowBounds")]
        try:
            require(all(keys), "Could not allocate metadata keys")
            for index in range(min(self.cf.CFArrayGetCount(windows), 4096)):
                window = self.cf.CFArrayGetValueAtIndex(windows, index)
                values = [self.cf.CFDictionaryGetValue(window, key) for key in keys]
                if not all(values):
                    continue
                owner, layer = ctypes.c_int64(), ctypes.c_int64()
                if not (self.cf.CFNumberGetValue(values[0], 4, ctypes.byref(owner))
                        and self.cf.CFNumberGetValue(values[1], 4, ctypes.byref(layer))):
                    continue
                rect = Rect()
                if self.cg.CGRectMakeWithDictionaryRepresentation(values[2], ctypes.byref(rect)):
                    x, y = bounds["x"] + bounds["width"] / 2, bounds["y"] + bounds["height"] / 2
                    if rect.x <= x <= rect.x + rect.width and rect.y <= y <= rect.y + rect.height:
                        if owner.value == self.pid and layer.value == 0:
                            return self.hit_target_is_application(x, y)
            return False
        finally:
            for key in keys:
                if key:
                    self.cf.CFRelease(key)
            self.cf.CFRelease(windows)


    def hit_target_is_application(self, x, y):
        # Dock can expose a full-screen, non-intercepting window rectangle. Its
        # bounding box alone does not establish which application receives input.
        ax = ctypes.CDLL("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices")
        ax.AXIsProcessTrusted.restype = ctypes.c_bool
        require(ax.AXIsProcessTrusted(), "Native hit-test accessibility permission is unavailable")
        ax.AXUIElementCreateSystemWide.restype = ctypes.c_void_p
        ax.AXUIElementCopyElementAtPosition.argtypes = [
            ctypes.c_void_p, ctypes.c_float, ctypes.c_float, ctypes.POINTER(ctypes.c_void_p)]
        ax.AXUIElementGetPid.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int32)]
        root = ax.AXUIElementCreateSystemWide()
        require(bool(root), "Could not create native hit-test root")
        element = ctypes.c_void_p()
        try:
            require(ax.AXUIElementCopyElementAtPosition(root, x, y, ctypes.byref(element)) == 0
                    and bool(element.value), "Native hit-test did not return an element")
            owner = ctypes.c_int32()
            require(ax.AXUIElementGetPid(element, ctypes.byref(owner)) == 0,
                    "Could not identify native hit-test owner")
            return owner.value == self.pid
        finally:
            if element.value:
                self.cf.CFRelease(element)
            self.cf.CFRelease(root)


class Acceptance:
    def __init__(self, base, app, profile):
        url = urllib.parse.urlsplit(base)
        require(url.scheme == "http" and url.hostname in ("127.0.0.1", "localhost")
                and url.port is not None and url.path in ("", "/")
                and url.username is None and url.password is None and not url.query and not url.fragment,
                "A local Test Mode HTTP origin is required")
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.app = str(pathlib.Path(app).resolve(strict=True))
        self.profile = str(pathlib.Path(profile).resolve(strict=True))
        require(self.app.endswith(".app"), "Expected a packaged .app")
        snap = self.read("/test/sync/ui")
        self.pid = snap["pid"]
        command = subprocess.check_output(["ps", "-p", str(self.pid), "-o", "command="],
                                          text=True, encoding="utf-8").strip()
        require(command.startswith(self.app + "/Contents/MacOS/Mihon Desktop ")
                and re.search(r"(?:^|\s)--test-profile=" + re.escape(self.profile) + r"(?:\s|$)", command)
                and "--test-mode" in command and "--headless" not in command,
                "Observed PID is not the requested isolated visible packaged app")
        self.events = NativeEvents(self.pid)

    def read(self, path):
        with self.opener.open(self.base + path, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))

    def snapshot(self):
        snap = self.read("/test/sync/ui")
        require(snap["pid"] == self.pid, "Application identity changed")
        require(snap["coordinateSystem"] == "awt-screen-points", "Unsupported coordinate system")
        state = self.read("/test/sync")
        require(not state["connected"] and state["page"] == "MAIN", "Unexpected production scenario; stop input")
        return snap, state

    def wait(self, predicate, reason):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            snap, state = self.snapshot()
            if snap["ready"] and predicate(snap, state):
                return snap
            time.sleep(0.1)
        raise RuntimeError(reason)

    @staticmethod
    def focused(snap):
        # The underlying Compose owner retains its local toolbar focus while the
        # modal owner is mounted in the same AWT window. Observe the active scope.
        group = "panel" if any(c["group"] == "panel" for c in snap["controls"]) else "toolbar"
        controls = [c for c in snap["controls"] if c["group"] == group
                    and c["focused"] and c["ownerFocused"] and c["enabled"]]
        require(len(controls) <= 1, "Multiple controls report active focus")
        if not snap.get("window", {}).get("focusedWindow"):
            return None
        return controls[0] if controls else None

    def focus(self, group=None, tag=None):
        def matches(snap, _):
            current = self.focused(snap)
            return current and (group is None or current["group"] == group) and (tag is None or current["tag"] == tag)
        return self.focused(self.wait(matches, "Expected active focus was not observed"))["tag"]

    def advance(self, previous, shift=False):
        self.events.key(48, shift=shift)
        snap = self.wait(lambda s, _: self.focused(s) is not None
                         and self.focused(s)["group"] == "panel"
                         and self.focused(s)["tag"] != previous,
                         "Tab did not move focus to another active panel control")
        return self.focused(snap)["tag"]

    def run(self):
        subprocess.run(["open", "-a", self.app], check=True)
        self.events.activate()
        snap = self.wait(lambda _, st: st["loaded"] and not st["visible"],
                         "The panel must initially be closed and loaded")
        entry = [c for c in snap["controls"] if c["tag"] == "sync-open" and c["enabled"]]
        require(len(entry) == 1, "Expected one attached sync toolbar entry")
        time.sleep(0.2)
        fresh = self.snapshot()[0]
        entry2 = [c for c in fresh["controls"] if c["tag"] == "sync-open"]
        require(len(entry2) == 1 and entry2[0]["bounds"] == entry[0]["bounds"], "Entry coordinates did not stabilize")
        bounds = entry2[0]["bounds"]
        content = fresh["window"]["contentBounds"]
        require(bounds["x"] >= content["x"] and bounds["y"] >= content["y"]
                and bounds["x"] + bounds["width"] <= content["x"] + content["width"]
                and bounds["y"] + bounds["height"] <= content["y"] + content["height"],
                "Toolbar coordinates are outside the bound application content")
        require(self.events.visible_at(bounds), "No on-screen application window contains the observed toolbar entry")
        print("PASS actual application has an on-screen window at the toolbar entry", flush=True)
        self.events.click(entry2[0]["bounds"])
        self.wait(lambda s, st: st["visible"] and len([
            c for c in s["controls"] if c["group"] == "panel" and c["enabled"]
        ]) >= 2, "Native mouse did not open and mount the sync panel")
        print("PASS native mouse opens the production sync panel", flush=True)
        self.events.key(48)
        start = self.focus("panel")
        snap = self.snapshot()[0]
        controls = {c["tag"] for c in snap["controls"] if c["group"] == "panel" and c["enabled"]}
        require(start in controls and len(controls) >= 2, "Panel focus inventory is incomplete")
        forward = [start]
        for _ in range(len(controls)):
            forward.append(self.advance(forward[-1]))
        require(forward[-1] == start and set(forward[:-1]) == controls
                and len(set(forward[:-1])) == len(controls), "Tab did not complete one confined focus cycle")
        reverse = [start]
        for _ in range(len(controls)):
            reverse.append(self.advance(reverse[-1], shift=True))
        require(reverse == [start] + list(reversed(forward[:-1])), "Shift+Tab did not reverse the focus cycle")
        print("PASS Tab and Shift+Tab remain in the panel: " + ", ".join(forward), flush=True)
        for code, label in ((36, "Enter"), (49, "Space")):
            self.events.key(53)
            self.wait(lambda _, st: not st["visible"], "Escape did not close the panel")
            self.focus(tag="sync-open")
            self.events.key(code)
            self.wait(lambda _, st: st["visible"], label + " did not reopen through restored toolbar focus")
            print("PASS Escape restores toolbar focus; native " + label + " reopens", flush=True)
        self.events.key(53)
        self.wait(lambda _, st: not st["visible"], "Final Escape did not close the panel")
        self.focus(tag="sync-open")
        print("PASS packaged Mac native interaction; PID=" + str(self.pid), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--app", required=True)
    parser.add_argument("--profile", required=True)
    args = parser.parse_args()
    require(sys.platform == "darwin", "Run on the Mac owning the visible application")
    Acceptance(args.base, args.app, args.profile).run()


if __name__ == "__main__":
    main()
