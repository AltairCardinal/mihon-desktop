#!/usr/bin/env python3
"""Build a 100 ms timeline of the JFR samples inside the Desktop startup window."""
import json
import sys
from collections import Counter, defaultdict
from datetime import datetime


def parse_start(text):
    return datetime.fromisoformat(text).timestamp()


def frame_label(frame):
    method = frame.get("method") or {}
    owner = method.get("type", {}).get("name", "?").replace("/", ".")
    return f"{owner}.{method.get('name', '?')}"


def main():
    path, window, bucket = sys.argv[1], float(sys.argv[2]), float(sys.argv[3] or 0.1)
    with open(path, "rb") as raw:
        head = raw.read(2)
    encoding = "utf-16" if head in (b"\xff\xfe", b"\xfe\xff") else "utf-8"
    with open(path, encoding=encoding) as handle:
        payload = json.load(handle)
    events = payload["recording"]["events"] if isinstance(payload, dict) else payload

    origin = None
    buckets = defaultdict(Counter)
    frames_by_bucket = defaultdict(Counter)
    for event in events:
        values = event.get("values", {})
        start = values.get("startTime")
        if not start:
            continue
        moment = parse_start(start)
        if origin is None or moment < origin:
            origin = moment
    for event in events:
        values = event.get("values", {})
        start = values.get("startTime")
        if not start:
            continue
        offset = parse_start(start) - origin
        if offset > window:
            continue
        kind = event.get("type", "?")
        thread = values.get("sampledThread", {}).get("javaName", "?")
        stack = (values.get("stackTrace") or {}).get("frames") or []
        label = frame_label(stack[0]) if stack else "-"
        slot = int(offset / bucket)
        buckets[slot][f"{kind}|{thread}"] += 1
        frames_by_bucket[slot][f"{thread}: {label}"] += 1

    print(f"timeline (bucket={bucket}s), window={window}s")
    for slot in sorted(buckets):
        start = slot * bucket
        end = (slot + 1) * bucket
        total = sum(buckets[slot].values())
        summary = ", ".join(f"{k.split('|')[1]}={v}" for k, v in buckets[slot].most_common(4))
        print(f"[{start:5.2f}-{end:5.2f}s] n={total:3}  {summary}")
        for name, count in frames_by_bucket[slot].most_common(3):
            print(f"          {count:3}  {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
