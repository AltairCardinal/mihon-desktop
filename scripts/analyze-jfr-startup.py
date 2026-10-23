#!/usr/bin/env python3
"""Slice a JFR dump to the Desktop startup window and aggregate the hot frames.

Usage:
    python analyze-jfr-startup.py <dump.json> [startup_seconds]
"""
import json
import sys
from collections import Counter
from datetime import datetime


def parse_start(text):
    return datetime.fromisoformat(text).timestamp()


def main():
    path = sys.argv[1]
    window = float(sys.argv[2]) if len(sys.argv) > 2 else 4.0
    with open(path, "rb") as raw:
        head = raw.read(2)
    encoding = "utf-16" if head in (b"\xff\xfe", b"\xfe\xff") else "utf-8"
    with open(path, encoding=encoding) as handle:
        payload = json.load(handle)

    events = payload["recording"]["events"] if isinstance(payload, dict) else payload
    records = []
    for event in events:
        values = event.get("values", {})
        start = values.get("startTime")
        if not start:
            continue
        records.append((parse_start(start), values))

    if not records:
        print("no timestamped events")
        return 1

    origin = min(r[0] for r in records)
    print(f"recording origin: {origin}  events: {len(records)}")

    window_events = [v for t, v in records if t - origin <= window]
    print(f"events inside the first {window}s: {len(window_events)}")

    kinds = Counter(event.get("type", "?") for event, (_, values) in zip(events, records)
                    if values.get("startTime"))
    top = Counter()
    for event, (_, values) in zip(events, records):
        if values.get("startTime") and 0 <= parse_start(values["startTime"]) - origin <= window:
            top[event.get("type", "?")] += 1
    print("  by type:")
    for kind, count in top.most_common(8):
        print(f"    {count:6}  {kind}")

    for kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
        subset = []
        for event, (_, values) in zip(events, records):
            if event.get("type") != kind:
                continue
            if not values.get("startTime"):
                continue
            if 0 <= parse_start(values["startTime"]) - origin <= window:
                subset.append(values)
        if not subset:
            continue
        print(f"\n=== {kind} ({len(subset)} samples) ===")
        threads = Counter(v.get("sampledThread", {}).get("javaName", "?") for v in subset)
        print("  by thread:")
        for name, count in threads.most_common(8):
            print(f"    {count:5}  {name}")
        frames = Counter()
        for value in subset:
            frames_list = (value.get("stackTrace") or {}).get("frames") or []
            if not frames_list:
                continue
            method = frames_list[0].get("method") or {}
            name = method.get("type", {}).get("name", "?")
            method_name = method.get("name", "?")
            frames[f"{name}.{method_name}"] += 1
        print("  by top frame:")
        for name, count in frames.most_common(15):
            print(f"    {count:5}  {name}")

    reads = []
    for event, (_, values) in zip(events, records):
        if event.get("type") != "jdk.FileRead" or not values.get("startTime"):
            continue
        if 0 <= parse_start(values["startTime"]) - origin <= window:
            reads.append(values)
    if reads:
        print(f"\n=== jdk.FileRead ({len(reads)}) ===")
        dirs = Counter()
        for value in reads:
            path_value = value.get("path", "")
            parts = path_value.replace("/", "\\").split("\\")
            dirs["\\".join(parts[:3]) if len(parts) > 3 else path_value] += 1
        for name, count in dirs.most_common(15):
            print(f"  {count:5}  {name}")
        slowest = sorted(reads, key=lambda v: -float(v.get("duration", 0) or 0))[:10]
        print("  slowest reads:")
        for value in slowest:
            print(f"    {float(value.get('duration', 0)):8.1f} ms  {value.get('path')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
