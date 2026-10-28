"""Native acceptance of the packaged Windows launcher, using only isolated profiles.

MIHON_SPLASH_EXE must point to the preview/final unpacked EXE to test.
"""
from __future__ import annotations

import ctypes as c
from ctypes import wintypes as w
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.request


@unittest.skipUnless(os.name == "nt" and os.environ.get("MIHON_SPLASH_EXE"), "Explicit Windows EXE required")
class WindowsStartupSplashTest(unittest.TestCase):
    def test_splash_first_frame_stays_on_launch_monitor_for_normal_and_maximized_startup(self):
        exe = Path(os.environ["MIHON_SPLASH_EXE"]).resolve(strict=True)
        user = c.WinDLL("user32", use_last_error=True)
        kernel = c.WinDLL("kernel32", use_last_error=True)
        user.SetProcessDPIAware()
        user.MonitorFromPoint.argtypes = [w.POINT, w.DWORD]
        user.MonitorFromPoint.restype = w.HANDLE
        user.MonitorFromWindow.argtypes = [w.HWND, w.DWORD]
        user.MonitorFromWindow.restype = w.HANDLE
        user.GetCursorPos.argtypes = [c.POINTER(w.POINT)]
        user.SetCursorPos.argtypes = [c.c_int, c.c_int]

        class MonitorInfo(c.Structure):
            _fields_ = [("size", w.DWORD), ("monitor", w.RECT), ("work", w.RECT), ("flags", w.DWORD)]

        user.GetMonitorInfoW.argtypes = [w.HANDLE, c.POINTER(MonitorInfo)]

        def monitor_info(monitor):
            info = MonitorInfo()
            info.size = c.sizeof(info)
            self.assertTrue(user.GetMonitorInfoW(monitor, c.byref(info)))
            return info

        # Explicit secondary-screen runs are bounded test input, with cursor restoration.
        secondary_point = None
        original_cursor = w.POINT()
        self.assertTrue(user.GetCursorPos(c.byref(original_cursor)))
        if os.environ.get("MIHON_SPLASH_SCREEN") == "secondary":
            monitors = []
            monitor_callback = c.WINFUNCTYPE(w.BOOL, w.HANDLE, w.HDC, c.POINTER(w.RECT), w.LPARAM)

            @monitor_callback
            def visit_monitor(monitor, _dc, _rect, _data):
                info = monitor_info(monitor)
                if not info.flags & 1:
                    monitors.append(info.work)
                return True

            self.assertTrue(user.EnumDisplayMonitors(None, None, visit_monitor, 0))
            self.assertTrue(monitors, "Explicit secondary-screen test requires a secondary monitor")
            area = monitors[0]
            secondary_point = ((area.left + area.right) // 2, (area.top + area.bottom) // 2)

            def restore_cursor():
                current = w.POINT()
                if user.GetCursorPos(c.byref(current)) and (current.x, current.y) == secondary_point:
                    user.SetCursorPos(original_cursor.x, original_cursor.y)

            self.addCleanup(restore_cursor)
        callback = c.WINFUNCTYPE(w.BOOL, w.HWND, w.LPARAM)
        user.GetWindowThreadProcessId.argtypes = [w.HWND, c.POINTER(w.DWORD)]
        user.IsWindowVisible.argtypes = [w.HWND]
        user.IsZoomed.argtypes = [w.HWND]
        user.ShowWindow.argtypes = [w.HWND, c.c_int]
        user.PostMessageW.argtypes = [w.HWND, w.UINT, w.WPARAM, w.LPARAM]
        kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        kernel.OpenProcess.restype = w.HANDLE
        kernel.CloseHandle.argtypes = [w.HANDLE]
        kernel.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
        kernel.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, c.POINTER(w.DWORD)]
        kernel.CreateToolhelp32Snapshot.argtypes = [w.DWORD, w.DWORD]
        kernel.CreateToolhelp32Snapshot.restype = w.HANDLE

        class ProcessEntry(c.Structure):
            _fields_ = [
                ("size", w.DWORD), ("usage", w.DWORD), ("pid", w.DWORD),
                ("heap", c.c_size_t), ("module", w.DWORD), ("threads", w.DWORD),
                ("parent", w.DWORD), ("priority", w.LONG), ("flags", w.DWORD),
                ("exe", w.WCHAR * 260),
            ]

        kernel.Process32FirstW.argtypes = [w.HANDLE, c.POINTER(ProcessEntry)]
        kernel.Process32NextW.argtypes = [w.HANDLE, c.POINTER(ProcessEntry)]

        def process_tree(root_pid):
            snapshot = kernel.CreateToolhelp32Snapshot(2, 0)
            self.assertNotEqual(c.c_void_p(-1).value, snapshot, "Process snapshot failed")
            parents = {}
            try:
                entry = ProcessEntry()
                entry.size = c.sizeof(entry)
                more = kernel.Process32FirstW(snapshot, c.byref(entry))
                while more:
                    parents[entry.pid] = entry.parent
                    more = kernel.Process32NextW(snapshot, c.byref(entry))
            finally:
                kernel.CloseHandle(snapshot)
            owned = {root_pid}
            while True:
                descendants = {pid for pid, parent in parents.items() if parent in owned}
                if descendants <= owned:
                    return owned
                owned |= descendants
        dwm = c.WinDLL("dwmapi")
        dwm.DwmGetWindowAttribute.argtypes = [w.HWND, w.DWORD, c.c_void_p, w.DWORD]
        child_handles = {}

        def windows(allowed):
            found = []

            @callback
            def visit(hwnd, _):
                pid = w.DWORD()
                user.GetWindowThreadProcessId(hwnd, c.byref(pid))
                if pid.value not in allowed:
                    return True
                handle = kernel.OpenProcess(0x1000, False, pid.value)
                if handle:
                    try:
                        path = c.create_unicode_buffer(32768)
                        length = w.DWORD(len(path))
                        if kernel.QueryFullProcessImageNameW(handle, 0, path, c.byref(length)) and Path(path.value) == exe:
                            if pid.value not in child_handles:
                                child_handles[pid.value] = kernel.OpenProcess(0x100000, False, pid.value)
                            name = c.create_unicode_buffer(256)
                            title = c.create_unicode_buffer(256)
                            user.GetClassNameW(w.HWND(hwnd), name, len(name))
                            user.GetWindowTextW(w.HWND(hwnd), title, len(title))
                            cloaked = w.DWORD()
                            dwm.DwmGetWindowAttribute(hwnd, 14, c.byref(cloaked), 4)
                            found.append((hwnd, pid.value, name.value, title.value,
                                          bool(user.IsWindowVisible(hwnd)), bool(cloaked.value)))
                    finally:
                        kernel.CloseHandle(handle)
                return True

            user.EnumWindows(visit, 0)
            return found

        # Match only the launched process tree AND this unpacked executable path.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def port():
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                return listener.getsockname()[1]

        def is_splash(name, title):
            return name == "JavaSplash" or (name == "SunAwtWindow" and not title)

        with tempfile.TemporaryDirectory(prefix="mihon-native-splash-") as directory:
            root = Path(directory)
            profile = root / "profile"
            for maximized in (False, True):
                http_port, jmx_port = port(), port()
                while jmx_port == http_port:
                    jmx_port = port()
                trace = []
                splash_ms = main_ms = None
                positioned_ms = None
                main_handle = None
                captured = False
                with (root / f"process-{maximized}.log").open("w", encoding="utf-8") as log:
                    started = time.monotonic()
                    if secondary_point is not None:
                        self.assertTrue(user.SetCursorPos(*secondary_point))
                    cursor = w.POINT()
                    self.assertTrue(user.GetCursorPos(c.byref(cursor)))
                    startup_monitor = user.MonitorFromPoint(cursor, 2)
                    process = subprocess.Popen(
                        [str(exe), "--test-mode", f"--test-profile={profile}",
                         f"--test-http-port={http_port}", f"--test-jmx-port={jmx_port}"],
                        stdout=log, stderr=log,
                    )
                    try:
                        while time.monotonic() - started < 30:
                            own = windows(process_tree(process.pid))
                            elapsed = round((time.monotonic() - started) * 1000)
                            for hwnd, pid, name, title, visible, cloaked in own:
                                if visible and is_splash(name, title):
                                    self.assertNotEqual("JavaSplash", name, "JVM splash can flash on primary before Main")
                                    actual_monitor = user.MonitorFromWindow(hwnd, 2)
                                    current_cursor = w.POINT()
                                    user.GetCursorPos(c.byref(current_cursor))
                                    self.assertEqual(startup_monitor, actual_monitor,
                                                     f"Wrong monitor at {elapsed}ms; cursor now="
                                                     f"{current_cursor.x},{current_cursor.y}; "
                                                     f"launch cursor={cursor.x},{cursor.y}")
                                    rectangle = w.RECT()
                                    self.assertTrue(user.GetWindowRect(w.HWND(hwnd), c.byref(rectangle)))
                                    work = monitor_info(startup_monitor).work
                                    self.assertLessEqual(abs(rectangle.left + rectangle.right - work.left - work.right), 2)
                                    self.assertLessEqual(abs(rectangle.top + rectangle.bottom - work.top - work.bottom), 2)
                                    if splash_ms is None:
                                        splash_ms = positioned_ms = elapsed
                                capture = os.environ.get("MIHON_SPLASH_CAPTURE")
                                if (capture and not maximized and not captured and visible and is_splash(name, title)
                                        and splash_ms is not None and elapsed >= splash_ms + 150):
                                    from PIL import ImageGrab
                                    rectangle = w.RECT()
                                    user.GetWindowRect(w.HWND(hwnd), c.byref(rectangle))
                                    ImageGrab.grab(bbox=(rectangle.left, rectangle.top,
                                                        rectangle.right, rectangle.bottom)).save(capture)
                                    captured = True
                                if visible and title.startswith("Mihon Desktop"):
                                    main_handle = hwnd
                                    if not cloaked and main_ms is None:
                                        main_ms = elapsed
                            trace.append({"ms": elapsed, "windows": own})
                            if main_ms is not None:
                                break
                            if process.poll() is not None:
                                break
                            time.sleep(0.01)
                        self.assertIsNotNone(splash_ms, "No loading splash appeared before the main window")
                        self.assertIsNotNone(main_ms, "Main window was never revealed")
                        self.assertLess(splash_ms, main_ms)
                        self.assertIsNotNone(positioned_ms, "Splash never reached the startup cursor monitor")
                        self.assertLess(positioned_ms, main_ms, "Splash moved only after main window appeared")
                        self.assertLess(splash_ms, 1000, "Loading feedback should appear within one second locally")
                        self.assertEqual(maximized, bool(user.IsZoomed(main_handle)))
                        deadline = time.monotonic() + 15
                        while True:
                            try:
                                with opener.open(f"http://127.0.0.1:{http_port}/test/health", timeout=1) as response:
                                    self.assertEqual("ok", json.load(response)["status"])
                                break
                            except OSError:
                                if time.monotonic() > deadline:
                                    raise
                                time.sleep(0.05)
                        self.assertEqual("mihon-desktop-test-profile-v1\n",
                                         (profile / ".mihon-test-profile").read_text(encoding="utf-8"))
                        # Main's first visible native frame can precede its EDT opening callbacks.
                        close_deadline = time.monotonic() + 1
                        while any(is_splash(item[2], item[3]) and item[4]
                                  for item in windows(process_tree(process.pid))):
                            self.assertLess(time.monotonic(), close_deadline,
                                            "Splash lingered after main window opened")
                            time.sleep(0.01)
                        if not maximized:
                            user.ShowWindow(main_handle, 3)
                            time.sleep(0.3)
                        user.PostMessageW(main_handle, 0x10, 0, 0)
                        process.wait(timeout=25)
                        self.assertEqual(0, process.returncode)
                        primary = bool(monitor_info(startup_monitor).flags & 1)
                        print(f"PASS maximized={maximized}, primary={primary}: "
                              f"first splash frame={splash_ms} ms, main={main_ms} ms", flush=True)
                    finally:
                        if process.poll() is None:
                            if main_handle:
                                user.PostMessageW(main_handle, 0x10, 0, 0)
                            try:
                                process.wait(timeout=15)
                            except subprocess.TimeoutExpired:
                                # Terminate only the launcher tree started by this test, including
                                # jpackage's GUI child; never leave that child as an orphan.
                                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                               check=True, capture_output=True)
                                process.wait(timeout=5)
                        destination = os.environ.get("MIHON_SPLASH_TRACE")
                        if destination:
                            Path(f"{destination}-{maximized}.json").write_text(
                                json.dumps(trace, indent=2), encoding="utf-8",
                            )
                        # The GUI child can outlive its jpackage parent briefly during cache shutdown.
                        for pid, handle in list(child_handles.items()):
                            if handle:
                                try:
                                    self.assertEqual(0, kernel.WaitForSingleObject(handle, 15000),
                                                     f"Owned GUI process {pid} did not exit")
                                finally:
                                    kernel.CloseHandle(handle)
                        child_handles.clear()


if __name__ == "__main__":
    unittest.main()
