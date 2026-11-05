#!/usr/bin/env python3
"""Recompute matched reader-open timing summaries from raw captures and reviewed frame indices.

This is a fixed-purpose, standard-library replay for the 2026-11-05 reader-open
set. It does not capture from a device or modify source artifacts.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from statistics import median
from typing import Any


class ReplayError(RuntimeError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ReplayError(f"missing required file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ReplayError(f"invalid JSON in {path}: {exc}") from exc


def expected_labels() -> tuple[set[str], set[str]]:
    primary: set[str] = set()
    for arm in ("official", "a", "b"):
        modes = ("single",) if arm == "official" else ("single", "off", "on")
        reps = ("r1", "r2", "r3")
        if arm == "official":
            reps = ("r1", "r3", "r2-equivalent")
        for mode in modes:
            for rep in reps:
                for opening in ("first", "warm"):
                    primary.add(f"final-{arm}-{mode}-{rep}-{opening}")
    supplemental = {
        "final-official-single-r2-first",
        "final-official-single-r2-warm",
    }
    return primary, supplemental


def load_endpoint_rows(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    try:
        with path.open("r", encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            if not reader.fieldnames or "label" not in reader.fieldnames:
                raise ReplayError(f"endpoint CSV has no label column: {path}")
            return list(reader.fieldnames), list(reader)
    except FileNotFoundError as exc:
        raise ReplayError(f"missing endpoint CSV: {path}") from exc


def frame_bound(frames: list[dict[str, Any]], index: int, down_ns: int) -> tuple[float, float]:
    if index < 0 or index >= len(frames):
        raise ReplayError(f"frame index {index} outside decoded range 0..{len(frames)-1}")
    if index == 0:
        previous = frames[index]
    else:
        previous = frames[index - 1]
    current = frames[index]
    low = (int(previous["trace_bounds_ns"][0]) - down_ns) / 1_000_000
    high = (int(current["trace_bounds_ns"][1]) - down_ns) / 1_000_000
    return low, high


def signal_runs(frames: list[dict[str, Any]], down_ns: int) -> list[dict[str, int]]:
    selected = [
        int(frame["index"])
        for frame in frames
        if int(frame["trace_bounds_ns"][1]) >= down_ns
        and float(frame.get("lavfi.signalstats.YAVG", 255)) < 17
    ]
    runs: list[dict[str, int]] = []
    for index in selected:
        if not runs or index != runs[-1]["last_frame"] + 1:
            runs.append({"first_frame": index, "last_frame": index, "frame_count": 1})
        else:
            runs[-1]["last_frame"] = index
            runs[-1]["frame_count"] += 1
    return runs


def interval_fields(row: dict[str, str], key: str, pts: list[int], frames: list[dict[str, Any]], down: int) -> tuple[int | None, tuple[float, float] | None]:
    raw = (row.get(f"{key}_frame") or "").strip()
    pts_raw = (row.get(f"{key}_pts_us") or "").strip()
    if not raw:
        if pts_raw:
            raise ReplayError(f"{row['label']}: {key} PTS exists without a frame index")
        return None, None
    try:
        index = int(raw)
    except ValueError as exc:
        raise ReplayError(f"{row['label']}: invalid {key} frame index {raw!r}") from exc
    if index < 0 or index >= len(pts):
        raise ReplayError(f"{row['label']}: {key} frame {index} outside video frame range")
    if not pts_raw or int(pts_raw) != pts[index]:
        raise ReplayError(f"{row['label']}: {key} PTS does not match native frame {index}")
    return index, frame_bound(frames, index, down)


def label_parts(label: str) -> tuple[str, str, str, str, bool]:
    match = re.fullmatch(r"final-(official|a|b)-(single|off|on)-(r[123]|r2-equivalent)-(first|warm)", label)
    if not match:
        match = re.fullmatch(r"final-(official|a|b)-(single|off|on)-(r[123])-(first|warm)", label)
    if not match:
        raise ReplayError(f"unexpected sample label: {label}")
    arm, mode, rep, opening = match.groups()
    supplemental = label in {"final-official-single-r2-first", "final-official-single-r2-warm"}
    if arm == "official" and mode != "single":
        raise ReplayError(f"sample has an invalid arm/mode combination: {label}")
    if arm != "official" and rep == "r2-equivalent":
        raise ReplayError(f"r2-equivalent is valid only for official samples: {label}")
    return arm, mode, rep, opening, supplemental


def verify_template_picks(path: Path | None) -> dict[str, dict[str, int]]:
    if path is None or not path.exists():
        return {}
    picks: dict[str, dict[str, int]] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        for entry in csv.DictReader(stream):
            label = entry.get("sample", "")
            values = {
                key: int(entry[key])
                for key in ("first_texture", "all_textures")
                if (entry.get(key) or "").strip()
            }
            if label and values:
                if label in picks:
                    raise ReplayError(f"duplicate sample in candidate-picks CSV: {label}")
                picks[label] = values
    return picks


def replay(capture_root: Path, endpoints_path: Path, trace_dir: Path, output_dir: Path,
           candidate_picks_path: Path | None) -> tuple[list[dict[str, Any]], dict[str, Any], list[str]]:
    fields, source_rows = load_endpoint_rows(endpoints_path)
    if "analysis_pool" not in fields:
        raise ReplayError("endpoint CSV must contain analysis_pool (primary/supplemental)")
    labels = [row.get("label", "") for row in source_rows]
    duplicates = sorted({label for label in labels if labels.count(label) > 1})
    if duplicates:
        raise ReplayError(f"duplicate endpoint labels: {', '.join(duplicates)}")
    actual = set(labels)
    primary_expected, supplemental_expected = expected_labels()
    expected = primary_expected | supplemental_expected
    missing, unexpected = sorted(expected - actual), sorted(actual - expected)
    if missing or unexpected:
        details = []
        if missing:
            details.append("missing=" + ",".join(missing))
        if unexpected:
            details.append("unexpected=" + ",".join(unexpected))
        raise ReplayError("sample set mismatch: " + " ; ".join(details))
    if len(source_rows) != 44:
        raise ReplayError(f"expected 44 sample rows, got {len(source_rows)}")

    picks = verify_template_picks(candidate_picks_path)
    out_rows: list[dict[str, Any]] = []
    groups: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    warnings: list[str] = []
    for source in source_rows:
        label = source["label"]
        arm, mode, rep, opening, supplemental = label_parts(label)
        expected_pool = "supplemental" if supplemental else "primary"
        if source.get("analysis_pool") != expected_pool:
            raise ReplayError(f"{label}: analysis_pool must be {expected_pool}")
        directory = capture_root / label
        capture = read_json(directory / "capture.json")
        trace = read_json(trace_dir / f"{label}.json")
        processed = read_json(capture_root / "analysis" / "processed" / label / "summary.json")
        try:
            frame_path = capture_root / "analysis" / "processed" / label / "frames.json"
            frames = json.loads(frame_path.read_text(encoding="utf-8"))
        except FileNotFoundError as exc:
            raise ReplayError(f"{label}: missing processed frame map: {frame_path}") from exc
        if not isinstance(frames, list) or not frames:
            raise ReplayError(f"{label}: empty or invalid processed frame map")
        if capture.get("status") != "OK":
            raise ReplayError(f"{label}: capture is not OK")

        try:
            header_records = [json.loads(line) for line in (directory / "video-headers.jsonl").read_text(encoding="utf-8").splitlines() if line]
        except FileNotFoundError as exc:
            raise ReplayError(f"{label}: missing native video headers") from exc
        pts = [int(record["pts_us"]) for record in header_records if not record.get("config", False)]
        if len(pts) != len(frames) or len(pts) != int(processed["decodedFrameCount"]):
            raise ReplayError(f"{label}: native packet / decoded frame count mismatch ({len(pts)} vs {len(frames)})")
        if any(right <= left for left, right in zip(pts, pts[1:])):
            raise ReplayError(f"{label}: native PTS is not strictly increasing")
        if not processed.get("noBFrames") or not processed.get("ptsStrictlyIncreasing") or not processed.get("helperBracketsVideo"):
            raise ReplayError(f"{label}: required frame mapping or clock-bracket validation failed")
        video_path = directory / "video.h264"
        try:
            with video_path.open("rb") as video:
                digest = hashlib.file_digest(video, "sha256").hexdigest()
        except FileNotFoundError as exc:
            raise ReplayError(f"{label}: missing raw video.h264") from exc
        if digest != source.get("video_sha256") or digest != processed.get("videoSha256"):
            raise ReplayError(f"{label}: raw H264 SHA-256 mismatch")

        down = int(trace["inputDownNs"])
        offsets = [int(value) for value in trace["offset"]]
        if down != int(processed["traceInputDownBoottimeNs"]) or down != int(source["input_down_boottime_ns"]):
            raise ReplayError(f"{label}: app DOWN anchor mismatch across reviewed CSV/trace/processed summary")
        if offsets != [int(value) for value in processed["offsetBoundsNs"]]:
            raise ReplayError(f"{label}: monotonic-to-BOOTTIME offset mismatch")
        if trace.get("boot") != source.get("boot_id"):
            raise ReplayError(f"{label}: boot ID mismatch")
        if trace.get("packetFrames") != len(pts):
            raise ReplayError(f"{label}: independent trace check packet count mismatch")
        if int(trace.get("snapshotOutsideEnvelopeNs", 10**18)) > 1000:
            raise ReplayError(f"{label}: trace clock snapshot lies outside the helper offset envelope")
        if not trace.get("inputDownNs") or trace.get("inputToStartActivityMs") is None:
            raise ReplayError(f"{label}: missing trace input/start timing")

        endpoint_data: dict[str, tuple[int | None, tuple[float, float] | None]] = {}
        for key in ("last_detail", "first_near_black", "last_near_black", "first_correct_comic", "first_complete_dual"):
            endpoint_data[key] = interval_fields(source, key, pts, frames, down)
        last_detail, _ = endpoint_data["last_detail"]
        first_near, _ = endpoint_data["first_near_black"]
        last_near, _ = endpoint_data["last_near_black"]
        first_comic, comic_bounds = endpoint_data["first_correct_comic"]
        first_dual, dual_bounds = endpoint_data["first_complete_dual"]
        if last_detail is None or first_comic is None or last_detail >= first_comic:
            raise ReplayError(f"{label}: missing or reversed last-detail/first-comic endpoint")
        if (mode == "single") != (first_dual is None):
            raise ReplayError(f"{label}: dual-completion endpoint does not match reader mode")
        if first_dual is not None and first_dual < first_comic:
            raise ReplayError(f"{label}: complete dual endpoint precedes first correct comic")
        if (first_near is None) != (last_near is None):
            raise ReplayError(f"{label}: incomplete near-black endpoint pair")
        if first_near is not None and not (last_detail < first_near <= last_near < first_comic):
            raise ReplayError(f"{label}: near-black endpoints are outside the visual transition interval")

        comic_low, comic_high = comic_bounds or (None, None)
        if dual_bounds:
            dual_low, dual_high = dual_bounds
        else:
            dual_low, dual_high = None, None
        declared_comic_low = float(source["first_correct_comic_rel_down_low_ms"])
        declared_comic_high = float(source["first_correct_comic_rel_down_high_ms"])
        if abs(comic_low - declared_comic_low) > 0.00001 or abs(comic_high - declared_comic_high) > 0.00001:
            raise ReplayError(f"{label}: first-comic adjacent-frame envelope does not recompute from native PTS")
        if first_dual is not None:
            declared_dual_low = float(source["first_complete_dual_rel_down_low_ms"])
            declared_dual_high = float(source["first_complete_dual_rel_down_high_ms"])
            if abs(dual_low - declared_dual_low) > 0.00001 or abs(dual_high - declared_dual_high) > 0.00001:
                raise ReplayError(f"{label}: dual adjacent-frame envelope does not recompute from native PTS")

        yavg_runs = signal_runs(frames, down)
        after_comic_runs = [run for run in yavg_runs if run["first_frame"] > first_comic]
        near_low = (pts[last_near] - pts[first_near]) / 1000 if first_near is not None else 0.0
        near_high = (pts[first_comic] - pts[last_detail]) / 1000
        budget = "pass" if near_high <= 60 else "over" if near_low > 60 else "unresolved"
        row = {
            "sample": label,
            "set": expected_pool,
            "arm": {"official": "Mihon", "a": "R2", "b": "B9"}[arm],
            "mode": mode,
            "replicate": rep,
            "open": opening,
            "input_down_boottime_ns": down,
            "offset_low_ns": offsets[0],
            "offset_high_ns": offsets[1],
            "input_to_start_activity_ms": f"{float(trace['inputToStartActivityMs']):.6f}",
            "video_sha256": digest,
            "last_detail_frame": last_detail,
            "last_detail_pts_us": pts[last_detail],
            "first_near_black_frame": first_near,
            "last_near_black_frame": last_near,
            "first_correct_comic_frame": first_comic,
            "first_correct_comic_pts_us": pts[first_comic],
            "first_correct_comic_low_ms": f"{comic_low:.6f}",
            "first_correct_comic_high_ms": f"{comic_high:.6f}",
            "first_complete_dual_frame": first_dual,
            "first_complete_dual_pts_us": pts[first_dual] if first_dual is not None else None,
            "first_complete_dual_low_ms": f"{dual_low:.6f}" if dual_low is not None else None,
            "first_complete_dual_high_ms": f"{dual_high:.6f}" if dual_high is not None else None,
            "nearblack_transition_low_ms": f"{near_low:.6f}",
            "nearblack_transition_high_ms": f"{near_high:.6f}",
            "nearblack_60ms_status": budget,
            "yavg17_candidate_runs_json": json.dumps(yavg_runs, separators=(",", ":")),
            "post_comic_yavg17_candidate_runs_json": json.dumps(after_comic_runs, separators=(",", ":")),
            "trace_error_count": sum(int(value) for value in trace.get("traceErrors", {}).values()),
        }
        expected_pick = picks.get(label, {})
        comic_pick = expected_pick.get("first_texture")
        comic_delta = comic_pick - first_comic if comic_pick is not None else None
        dual_pick = expected_pick.get("all_textures")
        dual_delta = dual_pick - first_dual if dual_pick is not None and first_dual is not None else None
        row["template_first_texture_frame"] = comic_pick
        row["template_first_texture_delta_frames"] = comic_delta
        row["template_all_textures_frame"] = dual_pick
        row["template_all_textures_delta_frames"] = dual_delta
        differences = []
        if comic_delta not in (None, 0):
            differences.append(f"first_texture={comic_pick} vs comic={first_comic} ({comic_delta:+d}f)")
        if dual_delta not in (None, 0):
            differences.append(f"all_textures={dual_pick} vs dual={first_dual} ({dual_delta:+d}f)")
        if differences:
            warnings.append(f"{label}: " + "; ".join(differences))
        out_rows.append(row)
        if not supplemental:
            groups[(row["arm"], mode, opening)].append(row)

    summaries: dict[str, Any] = {}
    for (arm, mode, opening), rows in sorted(groups.items()):
        if len(rows) != 3:
            raise ReplayError(f"primary group {arm}/{mode}/{opening} must contain 3 samples, got {len(rows)}")
        comic_low = median(float(row["first_correct_comic_low_ms"]) for row in rows)
        comic_high = median(float(row["first_correct_comic_high_ms"]) for row in rows)
        near_low = median(float(row["nearblack_transition_low_ms"]) for row in rows)
        near_high = median(float(row["nearblack_transition_high_ms"]) for row in rows)
        starts = median(float(row["input_to_start_activity_ms"]) for row in rows)
        dual_rows = [row for row in rows if row["first_complete_dual_frame"] is not None]
        if dual_rows and len(dual_rows) != len(rows):
            raise ReplayError(f"group {arm}/{mode}/{opening} mixes dual and single endpoint availability")
        summaries[f"{arm}/{mode}/{opening}"] = {
            "n": len(rows),
            "median_input_to_start_activity_ms": round(starts, 6),
            "median_first_correct_comic_ms_bounds": [round(comic_low, 6), round(comic_high, 6)],
            "median_nearblack_transition_ms_bounds": [round(near_low, 6), round(near_high, 6)],
            "median_first_complete_dual_ms_bounds": [
                round(median(float(row["first_complete_dual_low_ms"]) for row in dual_rows), 6),
                round(median(float(row["first_complete_dual_high_ms"]) for row in dual_rows), 6),
            ] if dual_rows else None,
            "nearblack_60ms_status_counts": {
                status: sum(row["nearblack_60ms_status"] == status for row in rows)
                for status in ("pass", "unresolved", "over")
            },
        }
    if len(summaries) != 14:
        raise ReplayError(f"expected 14 primary arm/mode/open groups, got {len(summaries)}")
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ReplayError(f"output directory is not empty; refusing to overwrite: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    sample_path = output_dir / "samples.csv"
    with sample_path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(out_rows[0]), extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(sorted(out_rows, key=lambda row: row["sample"]))
    (output_dir / "groups.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return out_rows, summaries, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture-root", type=Path, required=True, help="directory containing the 44 sample folders and analysis/processed")
    parser.add_argument("--endpoints", type=Path, required=True, help="reviewed endpoint CSV with confirmed frame indices")
    parser.add_argument("--output-dir", type=Path, required=True, help="new or empty directory for samples.csv and groups.json")
    parser.add_argument("--trace-check-dir", type=Path, help="defaults to <capture-root>/independent-trace-check")
    parser.add_argument("--candidate-picks", type=Path, help="optional independent template-picks.csv for discrepancy warnings")
    args = parser.parse_args()
    trace_dir = args.trace_check_dir or args.capture_root / "independent-trace-check"
    picks_path = args.candidate_picks or trace_dir / "template-picks.csv"
    try:
        rows, groups, warnings = replay(args.capture_root, args.endpoints, trace_dir, args.output_dir, picks_path)
    except (ReplayError, KeyError, TypeError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    primary_count = sum(row["set"] == "primary" for row in rows)
    supplemental_count = len(rows) - primary_count
    print(f"replayed {len(rows)} rows ({primary_count} primary, {supplemental_count} supplemental), {len(groups)} groups")
    print(f"samples: {args.output_dir / 'samples.csv'}")
    print(f"groups: {args.output_dir / 'groups.json'}")
    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
