#!/usr/bin/env python3
"""Write the canonical final-parity client summary from the coverage inventory."""

from __future__ import annotations

import argparse
import dataclasses
import json
import pathlib
import sys
from typing import Any


@dataclasses.dataclass(frozen=True)
class Result:
    id: str
    status: str
    detail: str


@dataclasses.dataclass(frozen=True)
class FinalParitySummary:
    families: list[Result]
    permanent_protections: list[Result]
    mapped_capability_ids: list[int]

    def write(self, path: pathlib.Path) -> None:
        payload = {
            "families": [dataclasses.asdict(result) for result in self.families],
            "permanentProtections": [
                dataclasses.asdict(result) for result in self.permanent_protections
            ],
            "mappedCapabilityIds": self.mapped_capability_ids,
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


def text(entry: dict[str, Any], field: str) -> str:
    value = entry.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError(f"inventory entry has invalid {field}")
    return value


def build_summary(inventory: dict[str, Any]) -> FinalParitySummary:
    scenarios = inventory.get("scenarios")
    protections = inventory.get("permanentProtections")
    boundaries = inventory.get("boundaries")
    if not all(isinstance(value, list) for value in (scenarios, protections, boundaries)):
        raise ValueError("inventory scenarios, boundaries, and protections must be arrays")

    def result(entry: dict[str, Any], id_field: str) -> Result:
        status = text(entry, "status")
        return Result(
            id=text(entry, id_field),
            status="PASS" if status in {"covered", "non-ui"} else "FAIL",
            detail=text(entry, "reason"),
        )

    mapped_ids = [
        capability
        for entry in scenarios + boundaries
        for capability in entry.get("capabilityIds", [])
    ]
    if not all(type(capability) is int for capability in mapped_ids):
        raise ValueError("inventory capabilityIds must contain integers")
    return FinalParitySummary(
        families=[result(entry, "family") for entry in scenarios],
        permanent_protections=[result(entry, "id") for entry in protections],
        mapped_capability_ids=mapped_ids,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
        if not isinstance(inventory, dict):
            raise ValueError("inventory root must be an object")
        build_summary(inventory).write(args.output)
        return 0
    except (OSError, json.JSONDecodeError, TypeError, ValueError) as error:
        print(f"Final parity client rejected inventory: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
