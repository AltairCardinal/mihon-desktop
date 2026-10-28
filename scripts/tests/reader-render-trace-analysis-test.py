import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location("reader_trace", Path(__file__).parents[1] / "analyze-reader-render-trace.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReaderRenderTraceAnalysisTest(unittest.TestCase):
    def fixture(self, directory, tops=(20, 10, 20)):
        events = []
        for index, top in enumerate(tops, 1):
            image = Image.new("RGBA", (64, 64), (0, 0, 0, 255))
            ImageDraw.Draw(image).rectangle((20, top, 42, top + 20), fill=(200, 120, 40, 255))
            image.save(directory / f"frame-{index:06}.png")
            events.extend([
                {"type": "frame-captured", "renderId": index, "timeNanos": index * 1000000,
                 "canvasWidth": 64, "canvasHeight": 64, "pngWidth": 64, "pngHeight": 64,
                 "window": {"nativeCanvas": [0, 0, 64, 64]}},
                {"type": "image-draw", "renderId": index, "pageIndex": 1,
                 "command": {"rootQuad": [{"x": 20, "y": top}, {"x": 42, "y": top},
                                           {"x": 42, "y": top + 20}, {"x": 20, "y": top + 20}]}},
                {"type": "frame-written", "renderId": index, "file": f"frame-{index:06}.png"},
            ])
        (directory / "events.jsonl").write_text("".join(json.dumps(e) + "\n" for e in events), encoding="utf-8")
        (directory / "manifest.json").write_text(json.dumps({
            "status": "RECORDED", "captureStage": "completed-layer-picture", "presentationObserved": False,
            "closed": True, "captureActive": False, "requested": len(tops), "written": len(tops),
            "missing": 0, "droppedEvents": 0,
        }), encoding="utf-8")

    def test_real_png_and_geometry_identify_up_then_return_without_claiming_presentation(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory)
            result = module.analyze_trace(directory)
            self.assertEqual(3, len(result["frames"]))
            self.assertTrue(result["captureComplete"])
            self.assertFalse(result["presentationObserved"])
            self.assertEqual(2, result["rebounds"][0]["renderId"])
            self.assertEqual(-10, result["rebounds"][0]["deltaY"])

    def test_missing_png_cannot_be_classified_as_complete(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory)
            (directory / "frame-000002.png").unlink()
            result = module.analyze_trace(directory)
            self.assertFalse(result["captureComplete"])
            self.assertTrue(any("missing PNG" in issue for issue in result["issues"]))

    def test_pixel_shift_return_is_detected_without_geometry_records(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory)
            events = [json.loads(line) for line in (directory / "events.jsonl").read_text(encoding="utf-8").splitlines()]
            (directory / "events.jsonl").write_text("".join(
                json.dumps(event) + "\n" for event in events if event["type"] != "image-draw"
            ), encoding="utf-8")
            result = module.analyze_trace(directory)
            self.assertTrue(result.get("pixelRebounds"), "PNG pixel evidence must work independently of draw geometry")
            self.assertEqual(-10, result["pixelRebounds"][0]["deltaY"])

    def test_wrong_frame_dimensions_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory)
            Image.new("RGB", (32, 32)).save(directory / "frame-000002.png")
            result = module.analyze_trace(directory)
            self.assertFalse(result["captureComplete"])
            self.assertTrue(any("dimension mismatch" in issue for issue in result["issues"]))

    def test_capture_gap_and_dropped_events_remain_partial(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory)
            manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
            manifest.update(status="PARTIAL", missing=1, droppedEvents=2)
            (directory / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            result = module.analyze_trace(directory)
            self.assertFalse(result["captureComplete"])


if __name__ == "__main__":
    unittest.main()
