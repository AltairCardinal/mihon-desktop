"""Capture a verified reader's window surface or its explicit fixed output region.

Windows Graphics Capture supplements Skia Pictures. QPC frame time is compositor
time, not a physical display scanout observation. The opt-in output-region mode
copies only the initial reader client rectangle from DXGI; no input is sent.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import ctypes
from ctypes import wintypes as w
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import threading
import time
import zlib

from PIL import Image


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


class Windows:
    def __init__(self):
        if os.name != "nt":
            raise OSError("Windows Graphics Capture requires Windows")
        self.u = ctypes.WinDLL("user32", use_last_error=True)
        self.k = ctypes.WinDLL("kernel32", use_last_error=True)
        self.d = ctypes.WinDLL("dwmapi")
        declarations = [
            (self.u.GetWindowThreadProcessId, [w.HWND, ctypes.POINTER(w.DWORD)], w.DWORD),
            (self.u.IsWindow, [w.HWND], w.BOOL),
            (self.u.IsWindowVisible, [w.HWND], w.BOOL),
            (self.u.GetWindowRect, [w.HWND, ctypes.POINTER(w.RECT)], w.BOOL),
            (self.u.GetClientRect, [w.HWND, ctypes.POINTER(w.RECT)], w.BOOL),
            (self.u.ClientToScreen, [w.HWND, ctypes.POINTER(w.POINT)], w.BOOL),
            (self.u.GetWindowDisplayAffinity, [w.HWND, ctypes.POINTER(w.DWORD)], w.BOOL),
            (self.u.GetClassNameW, [w.HWND, w.LPWSTR, ctypes.c_int], ctypes.c_int),
            (self.k.OpenProcess, [w.DWORD, w.BOOL, w.DWORD], w.HANDLE),
            (self.k.CloseHandle, [w.HANDLE], w.BOOL),
            (self.k.WaitForSingleObject, [w.HANDLE, w.DWORD], w.DWORD),
            (self.k.QueryFullProcessImageNameW, [w.HANDLE, w.DWORD, w.LPWSTR, ctypes.POINTER(w.DWORD)], w.BOOL),
            (self.k.QueryPerformanceCounter, [ctypes.POINTER(ctypes.c_longlong)], w.BOOL),
            (self.k.QueryPerformanceFrequency, [ctypes.POINTER(ctypes.c_longlong)], w.BOOL),
            (self.d.DwmGetWindowAttribute, [w.HWND, w.DWORD, ctypes.c_void_p, w.DWORD], ctypes.c_long),
            (self.u.SetThreadDpiAwarenessContext, [ctypes.c_void_p], ctypes.c_void_p),
        ]
        for function, arguments, result in declarations:
            function.argtypes, function.restype = arguments, result
        frequency = ctypes.c_longlong()
        if not self.k.QueryPerformanceFrequency(ctypes.byref(frequency)):
            raise ctypes.WinError(ctypes.get_last_error())
        self.frequency = frequency.value

    def qpc(self):
        counter = ctypes.c_longlong()
        if not self.k.QueryPerformanceCounter(ctypes.byref(counter)):
            raise ctypes.WinError(ctypes.get_last_error())
        return counter.value * 1_000_000_000 // self.frequency

    def owner(self, hwnd):
        pid = w.DWORD()
        self.u.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        return pid.value


_windows = None


def windows():
    global _windows
    if _windows is None:
        _windows = Windows()
    return _windows


class WindowSource:
    def __init__(self, hwnd, pid, executable=None):
        self.api, self.hwnd, self.pid, self.handle = windows(), int(hwnd), int(pid), None
        if not self.api.u.IsWindow(hwnd) or self.api.owner(hwnd) != pid:
            raise ValueError("Window owner does not match the requested process")
        self.handle = self.api.k.OpenProcess(0x1000 | 0x100000, False, pid)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            name, size = ctypes.create_unicode_buffer(32768), w.DWORD(32768)
            if not self.api.k.QueryFullProcessImageNameW(self.handle, 0, name, ctypes.byref(size)):
                raise ctypes.WinError(ctypes.get_last_error())
            self.executable = name.value
            if executable and os.path.normcase(str(Path(name.value).resolve())) != os.path.normcase(str(Path(executable).resolve())):
                raise ValueError("Window owner executable does not match the pinned candidate")
            affinity = w.DWORD()
            if self.api.u.GetWindowDisplayAffinity(hwnd, ctypes.byref(affinity)) and affinity.value:
                raise PermissionError("Window capture is blocked by the application's display-affinity policy")
        except BaseException:
            self.close()
            raise

    def alive(self):
        return self.api.k.WaitForSingleObject(self.handle, 0) == 258 and self.api.owner(self.hwnd) == self.pid

    def geometry(self):
        before = self.api.qpc()
        previous = self.api.u.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))
        try:
            rect, client, extended, origin = w.RECT(), w.RECT(), w.RECT(), w.POINT()
            if not self.api.u.GetWindowRect(self.hwnd, ctypes.byref(rect)):
                raise ctypes.WinError(ctypes.get_last_error())
            if not self.api.u.GetClientRect(self.hwnd, ctypes.byref(client)) or not self.api.u.ClientToScreen(self.hwnd, ctypes.byref(origin)):
                raise ctypes.WinError(ctypes.get_last_error())
            result = {"nativeWindow": [rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top],
                      "nativeClient": [origin.x, origin.y, client.right, client.bottom]}
            if self.api.d.DwmGetWindowAttribute(self.hwnd, 9, ctypes.byref(extended), ctypes.sizeof(extended)) >= 0:
                result["extendedFrameBounds"] = [extended.left, extended.top, extended.right - extended.left, extended.bottom - extended.top]
        finally:
            if previous:
                self.api.u.SetThreadDpiAwarenessContext(previous)
        result.update(observedBeforeQpcNanos=before, observedAfterQpcNanos=self.api.qpc())
        return result

    def close(self):
        if self.handle:
            self.api.k.CloseHandle(self.handle)
            self.handle = None


def validate_source(hwnd, pid, executable=None):
    return WindowSource(hwnd, pid, executable)


def encode_png(pixels, width, height):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    with Image.frombytes("RGBA", (width, height), pixels, "raw", "BGRA") as image:
        rgba = image.tobytes()
    compressor, parts, stride = zlib.compressobj(1), [], width * 4
    for row in range(height):
        parts.append(compressor.compress(b"\0"))
        parts.append(compressor.compress(rgba[row * stride:(row + 1) * stride]))
    parts.append(compressor.flush())
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", b"".join(parts)) + chunk(b"IEND", b""))


class CompositorWriter:
    def __init__(self, directory, max_frames=1800, max_queued_bytes=128 * 1024 * 1024,
                 max_disk_bytes=512 * 1024 * 1024, output_region=None):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.log = (self.directory / "events.jsonl").open("x", encoding="utf-8")
        self.lock, self.slots = threading.RLock(), threading.BoundedSemaphore(4)
        self.pool = ThreadPoolExecutor(2, thread_name_prefix="compositor-png")
        self.limit, self.max_bytes, self.max_disk = max_frames, max_queued_bytes, max_disk_bytes
        self.received, self.missing, self.queued, self.disk = 0, 0, 0, 0
        self.last, self.references, self.saved, self.failures = None, [], set(), []
        self.result = None
        self.output_region = list(output_region) if output_region is not None else None

    def event(self, kind, **fields):
        with self.lock:
            self.log.write(json.dumps({"type": kind, **fields}, ensure_ascii=False) + "\n")

    def gap(self, reason):
        with self.lock:
            self.failures.append(reason)
            self.event("capture-gap", reason=reason)

    def offer(self, pixels, width, height, timestamp, geometry):
        expected = width * height * 4
        with self.lock:
            self.received += 1
            frame_id = self.received
            if frame_id > self.limit:
                self.missing += 1
                self.gap("frame-budget-exceeded")
                return False
            if not (0 < width <= 16384 and 0 < height <= 16384) or expected > 64 * 1024 * 1024:
                self.missing += 1
                self.gap("frame-dimensions-or-size-rejected")
                return True
        handed_off, reserved = False, False
        try:
            if not isinstance(pixels, (bytes, bytearray, memoryview)):
                raise ValueError("Expected owned packed BGRA8 bytes from the native capture pipe")
            owned = bytes(pixels)
            if len(owned) != expected:
                raise ValueError("Frame byte count differs from its dimensions")
            with self.lock:
                checksum = zlib.crc32(owned)
                key = (width, height, checksum)
                if self.last and self.last[0] == key and self.last[1] == owned:
                    file = self.last[2]
                else:
                    # A duplicate references an existing PNG, even while that PNG is
                    # encoding. It must not consume another slot or pixel budget.
                    if self.queued + expected > self.max_bytes or not self.slots.acquire(blocking=False):
                        self.missing += 1
                        self.gap("pixel-queue-budget-exceeded")
                        return True
                    self.queued += expected
                    reserved = True
                    file = f"window-{frame_id:06}.png"
                    self.last = (key, owned, file)
                    self.pool.submit(self._save, file, owned, width, height)
                    handed_off = True
                self.references.append(file)
                clock = {"lastPresentQpc100ns": timestamp} if self.output_region is not None else {"systemRelative100ns": timestamp}
                self.event("compositor-frame", frameId=frame_id, file=file, width=width, height=height,
                           **clock, qpcNanos=timestamp * 100,
                           observedWindowAtReceive=geometry)
            return True
        except BaseException as failure:
            with self.lock:
                self.missing += 1
            self.gap(f"frame-copy-or-queue-failed:{failure}")
            return True
        finally:
            if reserved and not handed_off:
                with self.lock:
                    self.queued -= expected
                self.slots.release()

    def _save(self, file, pixels, width, height):
        try:
            png = encode_png(pixels, width, height)
            with self.lock:
                if self.disk + len(png) > self.max_disk:
                    raise ValueError("PNG disk budget exceeded")
                self.disk += len(png)
            (self.directory / file).write_bytes(png)
            with self.lock:
                self.saved.add(file)
                self.event("png-written", file=file, bytes=len(png))
        except BaseException as failure:
            self.gap(f"png-write-failed:{failure}")
        finally:
            with self.lock:
                self.queued -= len(pixels)
            self.slots.release()

    def close(self):
        if self.result is not None:
            return self.result
        self.pool.shutdown(wait=True)
        with self.lock:
            written = sum(file in self.saved for file in self.references)
            stage = "dxgi-output-duplication-reader-region" if self.output_region is not None else "windows-graphics-capture-window"
            self.result = {"captureStage": stage, "displayScanoutObserved": False,
                           "received": self.received, "written": written, "missing": self.received - written,
                           "pngBytes": self.disk, "queuedPixelBytes": self.queued, "failures": self.failures,
                           "status": "RECORDED" if written and written == self.received and not self.failures else "PARTIAL"}
            if self.output_region is not None:
                self.result["captureRegion"] = self.output_region
            self.event("session-drained", **self.result)
            self.log.close()
            write_json(self.directory / "manifest.json", self.result)
            self.last = None
            return self.result


def record_window(hwnd, pid, directory, seconds=60, executable=None, stop_event=None, max_frames=1800,
                  on_ready=None, output_region=False):
    source = validate_source(hwnd, pid, executable)
    try:
        initial_geometry = source.geometry()
        region = initial_geometry["nativeClient"] if output_region else None
        writer = CompositorWriter(directory, max_frames=max_frames, output_region=region)
    except BaseException:
        source.close()
        raise
    process, timeout = None, None
    ready = False
    try:
        backend_root = Path(__file__).resolve().parents[1] / "app-desktop/artifacts/diagnostics/compositor-native"
        backend = json.loads((backend_root / "backend.json").read_text(encoding="utf-8-sig"))
        helper = backend_root / "reader-compositor.exe"
        cpp = Path(__file__).with_name("native") / "reader-compositor.cpp"
        if hashlib.sha256(helper.read_bytes()).hexdigest().upper() != backend["executableSha256"] or hashlib.sha256(cpp.read_bytes()).hexdigest().upper() != backend["sourceSha256"]:
            raise ValueError("The native capture helper does not match its source/build manifest")
        writer.event("source", hwnd=hex(hwnd), pid=pid, executable=source.executable,
                     qpcFrequency=source.api.frequency, window=initial_geometry, cursorCapture=False,
                     monitorCapture=output_region, captureRegion=region,
                     captureStage="dxgi-output-duplication-reader-region" if output_region else "windows-graphics-capture-window",
                     displayScanoutObserved=False, nativeBackend=backend)
        with (Path(directory) / "native-stderr.log").open("wb") as errors:
            command = [str(helper), hex(hwnd), str(pid), str(seconds)]
            if output_region:
                command += ["--output-region", *map(str, region)]
            process = subprocess.Popen(command, stdout=subprocess.PIPE,
                                       stderr=errors, creationflags=subprocess.CREATE_NO_WINDOW)
            def stop_timeout():
                if process.poll() is None:
                    writer.gap("native-capture-timeout")
                    process.kill()
            timeout = threading.Timer(seconds + 5, stop_timeout)
            timeout.start()
            while True:
                header = process.stdout.read(28)
                if not header:
                    break
                if len(header) != 28:
                    raise ValueError("Native capture header was truncated")
                magic, width, height, content_width, content_height, timestamp = struct.unpack("<IIIIIq", header)
                size = width * height * 4
                expected_magic = 0x4d445247 if output_region else 0x4d574743
                if magic != expected_magic or not 0 < size <= 64 * 1024 * 1024 or (width, height) != (content_width, content_height):
                    raise ValueError("Native capture dimensions or protocol were rejected")
                if region is not None and (width, height) != tuple(region[2:]):
                    raise ValueError("Output pixels differ from the fixed reader client region")
                pixels = process.stdout.read(size)
                if len(pixels) != size:
                    raise ValueError("Native capture pixels were truncated")
                alive = source.alive()
                try:
                    geometry = source.geometry()
                except OSError:
                    geometry = {"sourceUnavailableAtReceive": True, "observedQpcNanos": source.api.qpc()}
                if not writer.offer(pixels, width, height, timestamp, geometry):
                    process.terminate()
                    break
                if not ready and writer.references:
                    ready = True
                    if on_ready:
                        on_ready()
                if not alive:
                    break
                if stop_event and stop_event.is_set():
                    process.terminate()
                    break
            code = process.wait(timeout=5)
            if code and not writer.failures:
                writer.gap(f"native-capture-exit:{code}; see native-stderr.log")
        with (Path(directory) / "native-stderr.log").open(encoding="utf-8") as native_log:
            for line in native_log:
                if line.startswith("CAPTURE_GAP "):
                    writer.gap(line.strip())
                elif line.startswith("CAPTURE_STOP "):
                    writer.event("capture-stopped", reason=line.strip())
    except BaseException as failure:
        writer.gap(f"capture-unavailable:{failure}")
        raise
    finally:
        try:
            if timeout:
                timeout.cancel()
                timeout.join(timeout=2)
            if process:
                if process.stdout:
                    process.stdout.close()
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)
        except BaseException as failure:
            writer.gap(f"capture-stop-failed:{failure}")
        result = writer.close()
        source.close()
    return result


def reader_hwnd(pid):
    api, matches = windows(), []
    callback_type = ctypes.WINFUNCTYPE(w.BOOL, w.HWND, w.LPARAM)

    @callback_type
    def inspect(hwnd, _):
        if api.owner(hwnd) == pid and api.u.IsWindowVisible(hwnd):
            name = ctypes.create_unicode_buffer(256)
            api.u.GetClassNameW(hwnd, name, len(name))
            if name.value == "SunAwtFrame":
                rect = w.RECT()
                api.u.GetWindowRect(hwnd, ctypes.byref(rect))
                matches.append(((rect.right - rect.left) * (rect.bottom - rect.top), int(hwnd)))
        return True

    api.u.EnumWindows.argtypes = [callback_type, w.LPARAM]
    api.u.EnumWindows.restype = w.BOOL
    if not api.u.EnumWindows(inspect, 0):
        raise ctypes.WinError(ctypes.get_last_error())
    if not matches:
        raise ValueError("The reader owner's visible AWT frame was not found")
    return max(matches)[1]


def watch_candidate(candidate, expected_commit, wait_seconds=1200, capture_seconds=60, output_region=False):
    candidate = candidate.resolve()
    manifest = json.loads((candidate / "preview-manifest.json").read_text(encoding="utf-8"))
    executable = Path(manifest["artifactPath"]).resolve()
    if manifest["source"]["sourceCommit"] != expected_commit or not executable.is_relative_to(candidate) or executable.name != "Mihon Desktop.exe":
        raise ValueError("Candidate source and executable identity do not match")
    spec = importlib.util.spec_from_file_location("provenance", Path(__file__).with_name("task15-build-provenance.py"))
    provenance = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(provenance)
    if provenance.artifact_identity(executable.parent) != manifest["artifact"]:
        raise ValueError("Candidate artifact integrity does not match its manifest")
    root = Path(manifest["diagnosticOverlay"]["value"]).resolve()
    if root != candidate / "reader-render-traces":
        raise ValueError("Reader trace directory differs from the pinned candidate")
    known = {item.name for item in root.iterdir() if item.is_dir()}
    state = candidate / "compositor-watch.json"
    write_json(state, {"status": "ARMED", "watcherPid": os.getpid(), "executable": str(executable),
                       "sourceCommit": expected_commit, "waitSeconds": wait_seconds, "outputRegion": output_region})
    deadline = time.monotonic() + wait_seconds
    while time.monotonic() < deadline:
        for session in root.iterdir():
            if not session.is_dir() or session.name in known:
                continue
            try:
                with (session / "events.jsonl").open(encoding="utf-8") as events:
                    first = json.loads(events.readline(65537))
            except (FileNotFoundError, json.JSONDecodeError):
                continue
            if first.get("type") != "session-start":
                raise ValueError("Unexpected reader trace header")
            pid = int(first["pid"])
            hwnd = reader_hwnd(pid)
            source = validate_source(hwnd, pid, executable)
            source.close()
            context = {"watcherPid": os.getpid(), "sourcePid": pid, "hwnd": hex(hwnd), "session": str(session),
                       "outputRegion": output_region}
            write_json(state, {"status": "CAPTURE_STARTING", **context})
            result = record_window(hwnd, pid, session / ("output-region" if output_region else "compositor"),
                                   capture_seconds, executable=executable, output_region=output_region,
                                   on_ready=lambda: write_json(state, {"status": "CAPTURING", **context}))
            write_json(state, {"status": "FINISHED" if result["status"] == "RECORDED" else "PARTIAL",
                               "session": str(session), "capture": result})
            return result
        time.sleep(0.1)
    write_json(state, {"status": "EXPIRED", "watcherPid": os.getpid()})
    return None


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--wait-seconds", type=int, default=1200)
    parser.add_argument("--capture-seconds", type=int, default=60)
    parser.add_argument("--output-region", action="store_true",
                        help="Save only the fixed initial reader client region from the display output")
    args = parser.parse_args()
    try:
        result = watch_candidate(args.candidate, args.expected_commit, args.wait_seconds, args.capture_seconds, args.output_region)
        print(json.dumps(result, ensure_ascii=False))
    except BaseException as failure:
        if args.candidate.is_dir():
            write_json(args.candidate / "compositor-watch.json", {"status": "FAILED", "error": str(failure), "watcherPid": os.getpid()})
        raise
