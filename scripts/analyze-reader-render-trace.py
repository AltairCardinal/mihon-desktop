"""Analyze opt-in reader renderer diagnostics. PNGs are application Pictures, not GPU presentation."""
import argparse
import hashlib
import html
import json
from pathlib import Path
from PIL import Image, ImageChops, ImageStat


def analyze_trace(directory: Path) -> dict:
    directory = Path(directory).resolve()
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    issues, captured, written, draws = [], {}, {}, {}
    if manifest.get("missing", 0) or manifest.get("droppedEvents", 0):
        issues.append("capture contains missing frames or dropped events")
    if manifest.get("status") != "RECORDED" or manifest.get("captureActive"):
        issues.append("capture is partial, writing, or still active")
    if manifest.get("requested") != manifest.get("written"):
        issues.append("requested and written frame counts differ")
    if manifest.get("captureStage") != "completed-layer-picture":
        issues.append("unknown capture stage")
    with (directory / "events.jsonl").open(encoding="utf-8") as source:
        for number, line in enumerate(source, 1):
            if number > 100000 or len(line) > 262144:
                issues.append("event analysis limit reached")
                break
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                issues.append(f"invalid event JSON at line {number}")
                continue
            kind, frame = event.get("type"), event.get("renderId")
            if kind == "frame-captured":
                captured[frame] = event
            elif kind == "frame-written":
                written[frame] = event
            elif kind == "capture-gap":
                issues.append(f"frame {frame}: {event.get('reason', 'capture gap')}")
            elif kind == "image-draw" and frame:
                command = event.get("command", {})
                quad = command.get("rootQuad", [])
                if len(quad) == 4:
                    draws.setdefault(frame, []).append({
                        "pageIndex": event.get("pageIndex"), "quad": quad,
                        "centerY": sum(point["y"] for point in quad) / 4,
                        "source": command.get("coordinatePhase", "actual-painter-command"),
                        "asset": command.get("bitmapIdentity"),
                    })
    frames, previous_image, thumbnails = [], None, {}
    for frame_id in sorted(written):
        event = written[frame_id]
        metadata = captured.get(frame_id)
        if metadata is None:
            issues.append(f"frame {frame_id}: missing capture metadata")
            continue
        path = (directory / event.get("file", "")).resolve()
        if not path.is_relative_to(directory) or path.suffix != ".png":
            issues.append(f"frame {frame_id}: unsafe PNG path")
            continue
        if not path.is_file():
            issues.append(f"frame {frame_id}: missing PNG")
            continue
        if path.stat().st_size > 128 * 1024 * 1024:
            issues.append(f"frame {frame_id}: PNG file exceeds analysis limit")
            continue
        try:
            with Image.open(path) as source:
                expected = (metadata.get("canvasWidth"), metadata.get("canvasHeight"))
                if source.size != expected or source.size != (metadata.get("pngWidth"), metadata.get("pngHeight")):
                    issues.append(f"frame {frame_id}: dimension mismatch {source.size} vs {expected}")
                    continue
                image = source.convert("RGB")
            difference = None
            if previous_image is not None:
                if previous_image.size == image.size:
                    diff = ImageChops.difference(previous_image, image)
                    difference = sum(ImageStat.Stat(diff).mean) / 3
                    diff.close()
                previous_image.close()
            previous_image = image
            small = image.convert("L")
            small.thumbnail((320, 320))
            thumbnails[frame_id] = small
            sha = hashlib.sha256()
            with path.open("rb") as png:
                for chunk in iter(lambda: png.read(1024 * 1024), b""):
                    sha.update(chunk)
            frames.append({
                "renderId": frame_id, "file": path.name, "width": image.width, "height": image.height,
                "timeNanos": metadata.get("renderStartedNanos", metadata.get("timeNanos")),
                "captureStartedNanos": metadata.get("captureStartedNanos"),
                "captureFinishedNanos": metadata.get("captureFinishedNanos"),
                "window": metadata.get("window", {}), "images": draws.get(frame_id, []),
                "pixelDifference": difference, "sha256": sha.hexdigest(),
            })
        except (OSError, ValueError) as failure:
            issues.append(f"frame {frame_id}: PNG decode failed: {failure}")
    if previous_image is not None:
        previous_image.close()
    if len(written) != manifest.get("written") or len(captured) != manifest.get("requested"):
        issues.append("manifest/event frame counts do not match")
    if not frames:
        issues.append("no usable captured frames")
    rebounds, pixel_rebounds = [], []
    for before, middle, after in zip(frames, frames[1:], frames[2:]):
        if not (before["window"].get("nativeCanvas") == middle["window"].get("nativeCanvas") == after["window"].get("nativeCanvas")):
            continue
        a_image, b_image, c_image = (thumbnails[item["renderId"]] for item in (before, middle, after))
        if a_image.size == b_image.size == c_image.size:
            def error(a, b):
                difference = ImageChops.difference(a, b)
                value = ImageStat.Stat(difference).mean[0]
                difference.close()
                return value
            base = error(a_image, b_image)
            if base > 0.5 and error(a_image, c_image) <= max(0.2, base * 0.15):
                width, height = a_image.size
                best_shift, best_error = 0, base
                for shift in range(-min(32, height // 3), min(32, height // 3) + 1):
                    if not shift:
                        continue
                    a_crop = a_image.crop((0, max(0, -shift), width, min(height, height - shift)))
                    b_crop = b_image.crop((0, max(0, shift), width, min(height, height + shift)))
                    value = error(a_crop, b_crop)
                    a_crop.close()
                    b_crop.close()
                    if value < best_error:
                        best_shift, best_error = shift, value
                if best_shift and best_error < base * 0.5:
                    pixel_rebounds.append({"renderId": middle["renderId"],
                        "deltaY": round(best_shift * before["height"] / height, 2),
                        "evidence": "PNG vertical alignment and return; candidate, not a proven cause"})
        first = {image["pageIndex"]: image for image in before["images"]}
        last = {image["pageIndex"]: image for image in after["images"]}
        for image in middle["images"]:
            page = image["pageIndex"]
            if page not in first or page not in last:
                continue
            a, b, c = first[page]["centerY"], image["centerY"], last[page]["centerY"]
            if abs(a - c) <= 1 and abs(b - a) >= 2 and (b - a) * (c - b) < 0:
                rebounds.append({"renderId": middle["renderId"], "pageIndex": page,
                                 "deltaY": b - a, "returnedY": c,
                                 "evidence": "application image geometry; candidate, not a proven cause"})
    for thumbnail in thumbnails.values():
        thumbnail.close()
    return {
        "captureStage": manifest.get("captureStage"), "presentationObserved": False,
        "captureComplete": not issues, "geometryComplete": bool(frames) and all(frame["images"] for frame in frames),
        "frames": frames, "rebounds": rebounds, "pixelRebounds": pixel_rebounds,
        "issues": list(dict.fromkeys(issues)),
        "limitations": ["PNGs rasterize completed application Pictures, not GPU backbuffer or compositor output.",
                        "Frame timestamps and image geometry samples are not display/vsync timestamps.",
                        "Readback and encoding add diagnostic overhead; no detected rebound is not proof of stability."],
    }


def write_report(directory: Path, result: dict) -> Path:
    directory = Path(directory)
    (directory / "analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    payload = json.dumps(result, ensure_ascii=False).replace("<", "\\u003c")
    report = '''<!doctype html><meta charset="utf-8"><title>阅读器渲染截帧分析</title>
<style>body{font:15px system-ui;background:#17191e;color:#eee;margin:24px}button,input{margin:8px}
img{display:block;max-width:100%;max-height:70vh}pre{white-space:pre-wrap}#stage{position:relative;display:inline-block}
svg{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}a{color:#a7ceff}</style>
<h1>阅读器渲染截帧分析</h1><p>应用已完成绘制指令的像素重放；不代表 GPU 或系统最终呈现。</p>
<p id="status"></p><button id="prev">上一帧</button><input id="frame" type="range" min="0" value="0"><button id="next">下一帧</button>
<span id="label"></span><div><div id="stage"><img id="image"><svg id="overlay"></svg></div></div>
<h2>本帧窗口、画布与漫画范围</h2><pre id="details"></pre><h2>证据缺口与候选回跳</h2><pre id="issues"></pre>
<script>const report=PAYLOAD, frames=report.frames;const slider=document.getElementById('frame');
slider.max=Math.max(0,frames.length-1);document.getElementById('status').textContent=
(report.captureComplete?'应用截帧记录完整':'记录不完整，不能据此判断稳定')+'；帧数 '+frames.length+'；系统呈现未观测';
document.getElementById('issues').textContent=JSON.stringify({issues:report.issues,rebounds:report.rebounds,pixelRebounds:report.pixelRebounds,limitations:report.limitations},null,2);
function show(){const f=frames[+slider.value];if(!f)return;document.getElementById('label').textContent='render '+f.renderId;
document.getElementById('stage').style.width=Math.max(1,Math.min(innerWidth-48,innerHeight*.7*f.width/f.height))+'px';
document.getElementById('image').style.width='100%';document.getElementById('image').style.maxHeight='none';
document.getElementById('image').src=encodeURIComponent(f.file);document.getElementById('details').textContent=JSON.stringify(f,null,2);
const svg=document.getElementById('overlay');svg.setAttribute('viewBox',`0 0 ${f.width} ${f.height}`);svg.replaceChildren();
for(const img of f.images){const p=document.createElementNS('http://www.w3.org/2000/svg','polygon');
p.setAttribute('points',img.quad.map(v=>`${v.x},${v.y}`).join(' '));p.setAttribute('fill','none');p.setAttribute('stroke','#00ff91');p.setAttribute('stroke-width','3');svg.append(p);}}
slider.oninput=show;document.getElementById('prev').onclick=()=>{slider.value=Math.max(0,+slider.value-1);show()};
document.getElementById('next').onclick=()=>{slider.value=Math.min(frames.length-1,+slider.value+1);show()};window.onresize=show;show();</script>'''
    path = directory / "analysis.html"
    path.write_text(report.replace("PAYLOAD", payload), encoding="utf-8")
    return path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    result = analyze_trace(args.directory)
    report = write_report(args.directory, result)
    print(json.dumps({"report": str(report.resolve()), "frames": len(result["frames"]),
                      "captureComplete": result["captureComplete"], "rebounds": len(result["rebounds"])}, ensure_ascii=False))
