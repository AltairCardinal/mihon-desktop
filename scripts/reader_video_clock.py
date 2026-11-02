"""Lossless stock scrcpy packet parsing; clock mapping requires proven MONOTONIC input."""
from __future__ import annotations


def exact(stream, count):
    chunks = bytearray()
    while len(chunks) < count:
        chunk = stream.read(count - len(chunks))
        if not chunk:
            raise EOFError(f"Expected {count} bytes, received {len(chunks)}")
        chunks.extend(chunk)
    return bytes(chunks)


def read_packet(stream):
    header = exact(stream, 12)
    flags = int.from_bytes(header[:8], "big")
    if flags & (1 << 63):
        raise ValueError("Unexpected stream/session metadata; disable send_stream_meta first")
    size = int.from_bytes(header[8:], "big")
    if size > 64 * 1024 * 1024:
        raise ValueError(f"Implausible packet size: {size}")
    config = bool(flags & (1 << 62))
    return {"header": header, "payload": exact(stream, size), "flags": flags,
            "config": config, "keyframe": bool(flags & (1 << 61)),
            "pts_us": None if config else flags & ((1 << 61) - 1)}


def map_monotonic_pts(pts_us, snapshots):
    """Snapshots are (trace BOOTTIME ns, MONOTONIC ns), never host arrivals."""
    if not snapshots:
        raise ValueError("MONOTONIC clock snapshots required")
    offsets = [trace - clock for trace, clock in snapshots]
    low, high = min(offsets), max(offsets)
    offset = (low + high) // 2
    return {"trace_ns": pts_us * 1000 + offset,
            "snapshot_offset_range_ns": [low, high],
            "snapshot_residual_bound_ns": max(offset - low, high - offset),
            "timestamp_unit_ns": 1000,
            "capture_pipeline_bias": "UNKNOWN; not physical display presentation"}


def map_offset_bounds(pts_us, low, high):
    if low > high:
        raise ValueError("Invalid clock offset interval")
    return {"trace_ns": pts_us * 1000 + (low + high) // 2,
            "trace_bounds_ns": [pts_us * 1000 + low - 1000, pts_us * 1000 + high + 1000],
            "source_unit_bound_ns": 1000, "offset_bounds_ns": [low, high]}


def main():
    import argparse
    import json
    from pathlib import Path
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("packets", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--offset-low-ns", type=int)
    parser.add_argument("--offset-high-ns", type=int)
    args = parser.parse_args()
    if (args.offset_low_ns is None) != (args.offset_high_ns is None):
        parser.error("Both offset bounds are required")
    count = 0
    with args.packets.open("rb") as source, args.output.open("x",encoding="utf-8") as output:
        while source.peek(1):
            if count >= 2_000_000:
                raise ValueError("Packet count limit exceeded")
            packet = read_packet(source)
            record = {key:packet[key] for key in ["flags","config","keyframe","pts_us"]}
            record.update(order=count,size=len(packet["payload"]))
            if packet["pts_us"] is not None and args.offset_low_ns is not None:
                record.update(map_offset_bounds(packet["pts_us"],args.offset_low_ns,args.offset_high_ns))
            output.write(json.dumps(record)+"\n")
            count += 1
    print(json.dumps({"packets":count,"output":str(args.output),"physicalPresentationProven":False}))


if __name__ == "__main__":
    main()
