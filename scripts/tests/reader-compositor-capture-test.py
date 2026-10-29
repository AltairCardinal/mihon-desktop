import ctypes
from ctypes import wintypes as w
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import unittest

from PIL import Image

spec = importlib.util.spec_from_file_location("reader_compositor", Path(__file__).parents[1] / "capture-reader-compositor.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OffscreenWindow:
    """Real, nonactivating STATIC white window, outside every display; no desktop input."""
    def __enter__(self):
        self.ready = threading.Event()
        self.closed = threading.Event()
        self.hwnd = None
        self.resize_to = None
        self.error = None
        self.thread = threading.Thread(target=self.run)
        self.thread.start()
        if not self.ready.wait(5):
            raise RuntimeError("Fixture window creation timed out")
        if self.error:
            raise self.error
        return self

    def run(self):
        u = ctypes.WinDLL("user32", use_last_error=True)
        u.CreateWindowExW.argtypes = [w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int,
                                     ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, ctypes.c_void_p]
        u.CreateWindowExW.restype = w.HWND
        u.UpdateWindow.argtypes = [w.HWND]
        u.DestroyWindow.argtypes = [w.HWND]
        u.PeekMessageW.argtypes = [ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT, w.UINT]
        u.DispatchMessageW.argtypes = [ctypes.POINTER(w.MSG)]
        u.DispatchMessageW.restype = ctypes.c_ssize_t
        u.SetThreadDpiAwarenessContext.argtypes = [ctypes.c_void_p]
        u.SetThreadDpiAwarenessContext.restype = ctypes.c_void_p
        dpi_context = u.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))
        g = ctypes.WinDLL("gdi32")
        g.CreateCompatibleDC.argtypes, g.CreateCompatibleDC.restype = [w.HDC], w.HDC
        g.CreateDIBSection.argtypes = [w.HDC, ctypes.c_void_p, w.UINT, ctypes.POINTER(ctypes.c_void_p), w.HANDLE, w.DWORD]
        g.CreateDIBSection.restype = w.HANDLE
        g.SelectObject.argtypes, g.SelectObject.restype = [w.HDC, w.HANDLE], w.HANDLE
        g.DeleteObject.argtypes = [w.HANDLE]
        g.DeleteDC.argtypes = [w.HDC]
        u.UpdateLayeredWindow.argtypes = [w.HWND, w.HDC, ctypes.POINTER(w.POINT), ctypes.POINTER(w.SIZE),
                                         w.HDC, ctypes.POINTER(w.POINT), w.DWORD, ctypes.c_void_p, w.DWORD]
        dc, bitmap, previous = None, None, None

        def paint(width, height):
            nonlocal bitmap, previous
            header = ctypes.create_string_buffer(struct.pack("<IiiHHIIiiII", 40, width, -height, 1, 32, 0, 0, 0, 0, 0, 0))
            pixels = ctypes.c_void_p()
            replacement = g.CreateDIBSection(dc, header, 0, ctypes.byref(pixels), None, 0)
            if not replacement:
                raise ctypes.WinError(ctypes.get_last_error())
            selected = g.SelectObject(dc, replacement)
            if bitmap:
                g.DeleteObject(bitmap)
            else:
                previous = selected
            bitmap = replacement
            ctypes.memset(pixels, 255, width * height * 4)
            location, size, origin = w.POINT(-20000, -20000), w.SIZE(width, height), w.POINT()
            blend = ctypes.create_string_buffer(bytes((0, 0, 255, 1)))
            if not u.UpdateLayeredWindow(self.hwnd, None, ctypes.byref(location), ctypes.byref(size), dc,
                                         ctypes.byref(origin), 0, blend, 2):
                raise ctypes.WinError(ctypes.get_last_error())

        try:
            self.hwnd = u.CreateWindowExW(0x08080080, "STATIC", "Mihon compositor fixture", 0x90000006,
                                          -20000, -20000, 320, 240, None, None, None, None)
            if not self.hwnd:
                raise ctypes.WinError(ctypes.get_last_error())
            u.UpdateWindow(self.hwnd)
            dc = g.CreateCompatibleDC(None)
            paint(319, 239)
            self.ready.set()
            message = w.MSG()
            while not self.closed.wait(0.005):
                if self.resize_to:
                    width, height = self.resize_to
                    self.resize_to = None
                    paint(width, height)
                while u.PeekMessageW(ctypes.byref(message), None, 0, 0, 1):
                    u.TranslateMessage(ctypes.byref(message))
                    u.DispatchMessageW(ctypes.byref(message))
        except BaseException as failure:
            self.error = failure
            self.ready.set()
        finally:
            if self.hwnd:
                u.DestroyWindow(self.hwnd)
            if previous:
                g.SelectObject(dc, previous)
            if bitmap:
                g.DeleteObject(bitmap)
            if dc:
                g.DeleteDC(dc)
            if dpi_context:
                u.SetThreadDpiAwarenessContext(dpi_context)

    def __exit__(self, *args):
        self.closed.set()
        self.thread.join(5)
        if self.thread.is_alive():
            raise RuntimeError("Fixture window did not close")


@unittest.skipUnless(os.name == "nt", "Windows Graphics Capture requires Windows")
class NativeCompositorTest(unittest.TestCase):
    def test_output_region_wiring_stops_before_reading_an_offscreen_display(self):
        with tempfile.TemporaryDirectory() as temporary, OffscreenWindow() as window:
            result = module.record_window(window.hwnd, os.getpid(), Path(temporary), seconds=1,
                                          executable=Path(sys.executable), output_region=True)
            self.assertEqual("dxgi-output-duplication-reader-region", result["captureStage"])
            self.assertEqual("PARTIAL", result["status"])
            self.assertEqual(0, result["received"])
            self.assertEqual([-20000, -20000, 319, 239], result["captureRegion"])
            events = [json.loads(line) for line in (Path(temporary) / "events.jsonl").read_text(encoding="utf-8").splitlines()]
            self.assertTrue(events[0]["monitorCapture"])
            self.assertEqual(result["captureRegion"], events[0]["captureRegion"])
            self.assertIn("one active display", (Path(temporary) / "native-stderr.log").read_text(encoding="utf-8"))

    def test_output_region_uses_the_real_gpu_crop_and_packed_row_copy(self):
        helper = Path(__file__).resolve().parents[2] / "app-desktop/artifacts/diagnostics/compositor-native/reader-compositor.exe"
        result = subprocess.run([str(helper), "--test-region-copy"], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8"))
        magic, width, height, content_width, content_height, timestamp = struct.unpack("<IIIIIq", result.stdout[:28])
        self.assertEqual((0x4d445247, 3, 2, 3, 2, 42),
                         (magic, width, height, content_width, content_height, timestamp))
        self.assertEqual(bytes(v for y in (1, 2) for x in (1, 2, 3) for v in (x, y, 140, 255)),
                         result.stdout[28:])

    def test_output_region_cannot_capture_a_region_outside_the_owned_reader(self):
        helper = Path(__file__).resolve().parents[2] / "app-desktop/artifacts/diagnostics/compositor-native/reader-compositor.exe"
        with OffscreenWindow() as window:
            result = subprocess.run([str(helper), hex(window.hwnd), str(os.getpid()), "1",
                                     "--output-region", "0", "0", "200", "100"],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(b"", result.stdout)
            self.assertIn(b"reader client region", result.stderr.lower())

    def test_offscreen_owned_window_really_captures_pixels(self):
        with tempfile.TemporaryDirectory() as temporary, OffscreenWindow() as window:
            before = module.windows().qpc() - 1_000_000_000
            ready = []
            module.record_window(window.hwnd, os.getpid(), Path(temporary), seconds=1.5,
                                 executable=Path(sys.executable), on_ready=lambda: ready.append(True))
            after = module.windows().qpc()
            frames = list(Path(temporary).glob("window-*.png"))
            self.assertTrue(frames, "The actual compositor API must produce a PNG of the owned window")
            with Image.open(frames[0]) as image:
                self.assertEqual((255, 255, 255), image.convert("RGB").getpixel((160, 120)))
                self.assertEqual((319, 239), image.size)
                self.assertEqual(((255, 255),) * 4, image.getextrema(), "Native padded rows must not leak into pixels")
            manifest = json.loads((Path(temporary) / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("windows-graphics-capture-window", manifest["captureStage"])
            self.assertEqual("RECORDED", manifest["status"])
            self.assertFalse(manifest["displayScanoutObserved"])
            self.assertEqual([True], ready)
            events = [json.loads(line) for line in (Path(temporary) / "events.jsonl").read_text(encoding="utf-8").splitlines()]
            actual = [e for e in events if e["type"] == "compositor-frame"]
            self.assertTrue(all(before <= event["qpcNanos"] <= after for event in actual))
            u = ctypes.WinDLL("user32")
            u.GetForegroundWindow.restype = w.HWND
            self.assertNotEqual(window.hwnd, u.GetForegroundWindow())

    def test_wrong_owner_pid_is_rejected_before_capture(self):
        with OffscreenWindow() as window:
            with self.assertRaisesRegex(ValueError, "owner"):
                module.validate_source(window.hwnd, os.getpid() + 1)

    def test_real_compositor_resizes_its_pixel_buffers(self):
        with tempfile.TemporaryDirectory() as temporary, OffscreenWindow() as window:
            result = module.record_window(window.hwnd, os.getpid(), Path(temporary), seconds=2,
                                          on_ready=lambda: setattr(window, "resize_to", (401, 301)))
            sizes = set()
            for frame in Path(temporary).glob("window-*.png"):
                with Image.open(frame) as image:
                    sizes.add(image.size)
            self.assertIn((319, 239), sizes)
            self.assertIn((401, 301), sizes)
            self.assertEqual("RECORDED", result["status"])

    def test_wrong_executable_is_rejected(self):
        with OffscreenWindow() as window:
            with self.assertRaisesRegex(ValueError, "executable"):
                module.validate_source(window.hwnd, os.getpid(), Path(__file__).with_name("unrelated.exe"))

    def test_native_backend_independently_rejects_the_wrong_owner(self):
        helper = Path(__file__).resolve().parents[2] / "app-desktop/artifacts/diagnostics/compositor-native/reader-compositor.exe"
        with OffscreenWindow() as window:
            result = subprocess.run([str(helper), hex(window.hwnd), str(os.getpid() + 1), "1"],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(b"", result.stdout)
            self.assertIn(b"owner mismatch", result.stderr.lower())

    def test_reader_selector_binds_to_an_actual_awt_frame(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "ReaderCaptureAwtFixture.java"
            source.write_text('''import java.awt.*;
public class ReaderCaptureAwtFixture {
  public static void main(String[] args) throws Exception {
    Frame frame = new Frame("Reader capture fixture");
    EventQueue.invokeAndWait(() -> {
      frame.setBounds(-20000, -20000, 319, 239);
      frame.setFocusableWindowState(false);
      frame.setAutoRequestFocus(false);
      frame.setVisible(true);
    });
    System.out.println(ProcessHandle.current().pid());
    System.out.flush();
    System.in.read();
    EventQueue.invokeAndWait(frame::dispose);
    System.exit(0);
  }
}''', encoding="utf-8")
            subprocess.run([shutil.which("javac"), "-d", str(root), str(source)], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
            java = subprocess.Popen([shutil.which("java"), "-cp", str(root), "ReaderCaptureAwtFixture"],
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                pid = int(java.stdout.readline())
                hwnd = module.reader_hwnd(pid)
                binding = module.validate_source(hwnd, pid, Path(shutil.which("java")))
                try:
                    self.assertEqual(pid, binding.api.owner(hwnd))
                    self.assertLess(binding.geometry()["nativeWindow"][0], -10000)
                finally:
                    binding.close()
            finally:
                java.communicate(b"x", timeout=5)
                self.assertEqual(0, java.returncode)


class CompositorWriterTest(unittest.TestCase):
    def test_duplicate_frames_do_not_need_another_encoding_slot(self):
        started, release = threading.Event(), threading.Event()
        encode = module.encode_png

        def blocked_encoder(*args):
            started.set()
            if not release.wait(5):
                raise RuntimeError("Test encoder was not released")
            return encode(*args)

        with tempfile.TemporaryDirectory() as temporary:
            writer = module.CompositorWriter(Path(temporary), max_queued_bytes=16)
            module.encode_png = blocked_encoder
            try:
                writer.offer(bytes(16), 2, 2, 1, {})
                self.assertTrue(started.wait(2))
                writer.offer(bytes(16), 2, 2, 2, {})
            finally:
                release.set()
                result = writer.close()
                module.encode_png = encode
            self.assertEqual(2, result["written"], "Reusing an in-flight PNG must not drop a duplicate frame")
            self.assertEqual("RECORDED", result["status"])

    def test_output_region_records_its_stage_and_fixed_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            writer = module.CompositorWriter(Path(temporary), output_region=[8, 31, 2, 2])
            writer.offer(bytes(16), 2, 2, 1, {})
            result = writer.close()
            self.assertEqual("dxgi-output-duplication-reader-region", result["captureStage"])
            self.assertEqual([8, 31, 2, 2], result["captureRegion"])
            self.assertFalse(result["displayScanoutObserved"])
            events = [json.loads(line) for line in (Path(temporary) / "events.jsonl").read_text(encoding="utf-8").splitlines()]
            frame = next(event for event in events if event["type"] == "compositor-frame")
            self.assertEqual(1, frame["lastPresentQpc100ns"])
            self.assertEqual(100, frame["qpcNanos"])
            self.assertNotIn("systemRelative100ns", frame)

    def test_identical_frames_keep_both_records_and_share_one_lossless_png(self):
        with tempfile.TemporaryDirectory() as temporary:
            writer = module.CompositorWriter(Path(temporary))
            pixels = bytearray([5, 15, 220, 255] * 12)
            writer.offer(pixels, 4, 3, 1234, {})
            writer.offer(pixels, 4, 3, 1235, {})
            result = writer.close()
            self.assertEqual(2, result["written"])
            self.assertEqual(1, len(list(Path(temporary).glob("window-*.png"))))
            events = [json.loads(line) for line in (Path(temporary) / "events.jsonl").read_text(encoding="utf-8").splitlines()]
            frames = [event for event in events if event["type"] == "compositor-frame"]
            self.assertEqual([123400, 123500], [event["qpcNanos"] for event in frames])
            self.assertEqual(frames[0]["file"], frames[1]["file"])

    def test_callback_pixels_are_owned_before_native_buffer_is_reused(self):
        with tempfile.TemporaryDirectory() as temporary:
            writer = module.CompositorWriter(Path(temporary))
            pixels = bytearray([5, 15, 220, 255] * 12)
            writer.offer(pixels, 4, 3, 1234, {})
            pixels[:] = bytes([255, 0, 0, 255] * 12)
            writer.close()
            frames = list(Path(temporary).glob("window-*.png"))
            self.assertTrue(frames)
            with Image.open(frames[0]) as image:
                self.assertEqual((220, 15, 5, 255), image.getpixel((0, 0)))

    def test_frame_limit_is_reported_as_partial(self):
        with tempfile.TemporaryDirectory() as temporary:
            writer = module.CompositorWriter(Path(temporary), max_frames=1)
            pixels = bytes(16)
            writer.offer(pixels, 2, 2, 1, {})
            writer.offer(pixels, 2, 2, 2, {})
            result = writer.close()
            self.assertEqual(1, result["written"])
            self.assertEqual(1, result["missing"])
            self.assertEqual("PARTIAL", result["status"])

    def test_pixel_budget_failure_is_explicit(self):
        with tempfile.TemporaryDirectory() as temporary:
            writer = module.CompositorWriter(Path(temporary), max_queued_bytes=8)
            writer.offer(bytes(16), 2, 2, 1, {})
            result = writer.close()
            self.assertEqual(1, result["missing"])
            self.assertEqual("PARTIAL", result["status"])

    def test_disk_budget_and_invalid_frame_shape_are_partial(self):
        with tempfile.TemporaryDirectory() as temporary:
            writer = module.CompositorWriter(Path(temporary), max_disk_bytes=1)
            writer.offer(bytes(16), 2, 2, 1, {})
            result = writer.close()
            self.assertEqual("PARTIAL", result["status"])
            self.assertEqual(1, result["missing"])
        with tempfile.TemporaryDirectory() as temporary:
            writer = module.CompositorWriter(Path(temporary))
            writer.offer(bytes(4), 2, 2, 1, {})
            self.assertEqual(1, writer.close()["missing"])


if __name__ == "__main__":
    unittest.main()
