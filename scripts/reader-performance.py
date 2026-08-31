#!/usr/bin/env python3
"""Measure Desktop reader first-frame performance through live Test Mode."""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import sys
from typing import Any

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[1]
PYTHON_CLIENT_ROOT = REPOSITORY_ROOT / "test-desktop" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_CLIENT_ROOT))

from reader_test_mode import ReaderContractError, ReaderMeasurement, ReaderTestModeClient  # noqa: E402


SCENARIOS = (
    ("downloaded_directory", 1_000.0, 2_000.0),
    ("downloaded_cbz", 1_500.0, 3_000.0),
)


def nearest_rank_p95(values: list[float]) -> float:
    if not values:
        raise ValueError("at least one measured iteration is required")
    ordered = sorted(values)
    return ordered[math.ceil(0.95 * len(ordered)) - 1]


def scenario_report(
    source: str,
    measurements: list[ReaderMeasurement],
    p95_budget: float,
    max_budget: float,
) -> dict[str, Any]:
    durations = [measurement.duration_millis for measurement in measurements]
    p95 = nearest_rank_p95(durations)
    maximum = max(durations)
    status = "PASS" if p95 <= p95_budget and maximum <= max_budget else "FAIL"
    return {
        "source": source,
        "status": status,
        "p95Millis": p95,
        "maxMillis": maximum,
        "ioGate": measurements[-1].io_gate,
        "samples": [
            {
                "iteration": index,
                "durationMillis": measurement.duration_millis,
                "phaseMillis": measurement.phase_millis,
                "ioGate": measurement.io_gate,
            }
            for index, measurement in enumerate(measurements, start=1)
        ],
    }


def write_report(path: pathlib.Path, warmups: int, iterations: int, scenarios: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {"warmups": warmups, "iterations": iterations, "scenarios": scenarios},
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--iterations", type=int, default=20)
    args = parser.parse_args()
    if args.warmups < 0 or args.iterations <= 0:
        print("warmups must be non-negative and iterations must be positive", file=sys.stderr)
        return 1

    client = ReaderTestModeClient(args.base_url)
    reports: list[dict[str, Any]] = []
    try:
        chapter_id = 100_000
        for source, p95_budget, max_budget in SCENARIOS:
            measurements: list[ReaderMeasurement] = []
            for index in range(args.warmups + args.iterations):
                chapter_id += 1
                try:
                    measurement = client.run_fixture(source, chapter_id)
                finally:
                    client.close_reader()
                if index >= args.warmups:
                    measurements.append(measurement)
            report = scenario_report(source, measurements, p95_budget, max_budget)
            reports.append(report)
            if report["status"] != "PASS":
                print(
                    f"{source}: P95 {report['p95Millis']:.3f} ms (limit {p95_budget:.0f}), "
                    f"max {report['maxMillis']:.3f} ms (limit {max_budget:.0f})",
                    file=sys.stderr,
                )
        write_report(args.output, args.warmups, args.iterations, reports)
        return 0 if all(report["status"] == "PASS" for report in reports) else 1
    except (OSError, ValueError, ReaderContractError) as error:
        print(f"Reader performance rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
