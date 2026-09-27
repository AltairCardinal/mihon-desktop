"""Aggregate selected JFR samples; never export event values or application data.

Input: `jfr print --json --stack-depth 64 --events jdk.ExecutionSample FILE`.
Native leaf-only overview can separately use --stack-depth 5 and NativeMethodSample.
Sample counts are not wall-clock percentages and do not measure native SQLite I/O.
"""
import argparse
import collections
import json
import re
from pathlib import Path


def summarize(path):
    if path.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("Selected JFR JSON exceeds the bounded 64 MiB input budget")
    events = json.loads(path.read_text(encoding="utf-8"))["recording"]["events"]
    event_types = collections.Counter()
    phases = collections.Counter()
    inclusive = collections.Counter()
    leaves = collections.Counter()
    phase_methods = collections.defaultdict(collections.Counter)
    phase_markers = (
        ("baseline", "SyncBaselineStore"),
        ("projection", "SyncInboxProjector"),
        ("outbox", "SyncOutbox"),
    )
    for event in events:
        event_types[event["type"]] += 1
        values = event["values"]
        frames = (values.get("stackTrace") or {}).get("frames", [])
        methods = []
        for frame in frames:
            method = frame.get("method") or {}
            owner = (method.get("type") or {}).get("name", "unknown")
            owner = re.sub(r"/0x[0-9a-fA-F]+", "/runtime", owner)
            methods.append(owner.replace("/", ".") + "." + method.get("name", "unknown"))
        phase = next((name for name, marker in phase_markers if any(marker in m for m in methods)), "other")
        phases[phase] += 1
        if methods:
            leaves[methods[0]] += 1
        for method in set(methods):
            inclusive[method] += 1
            phase_methods[phase][method] += 1
    def ranked(counts, limit=30):
        return [{"method": name, "samples": count} for name, count in counts.most_common(limit)]
    return {
        "interpretation": "Inclusive sampled stacks overlap; counts are not elapsed time or CPU percentages. Native SQLite I/O is not covered by Java file events.",
        "sampleCount": len(events),
        "eventTypes": dict(event_types),
        "phaseSamples": dict(phases),
        "leafMethods": ranked(leaves),
        "applicationInclusive": ranked(collections.Counter({k: v for k, v in inclusive.items() if k.startswith(("mihon.", "tachiyomi."))})),
        "phaseInclusive": {phase: ranked(counts) for phase, counts in phase_methods.items()},
        "phaseApplicationMethods": {phase: ranked(collections.Counter({k: v for k, v in counts.items() if k.startswith(("mihon.", "org.sqlite.")) and not any(t in k for t in ("$", "invoke", "access", "phase"))})) for phase, counts in phase_methods.items()},
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.write_bytes((json.dumps(summarize(args.input), ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
