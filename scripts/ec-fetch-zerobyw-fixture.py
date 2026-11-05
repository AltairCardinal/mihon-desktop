"""Fetch immutable EC inputs; neither third-party artifact is redistributed in Git."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import urllib.request

RELEASE = "https://github.com/keiyoushi/extensions/releases/download/726723b-2"
ARTIFACTS = {
    "apk": (79_679, "b04e4a298e6fef4fa58ef452ece77dc988025515f4b9b835d54a085c072dc874"),
    "jar": (111_991, "f9f016079892a344b03028fb2a84347e7e15823503851721ebbda7a0c2513396"),
}


def validate(path: Path, size: int, expected_hash: str) -> None:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    if path.stat().st_size != size or digest.hexdigest() != expected_hash:
        raise ValueError(f"Pinned Zerobyw 1.6.2 size/SHA-256 mismatch: {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=ARTIFACTS, default="apk")
    parser.add_argument("--existing", type=Path, help="Validate an existing artifact without downloading")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    size, expected_hash = ARTIFACTS[args.kind]
    name = f"tachiyomi-zh.zerobyw-v1.6.2.{args.kind}"
    if args.existing is not None:
        validate(args.existing, size, expected_hash)
        print(args.existing.resolve())
        return
    output = (args.output or Path(".test-tmp/ec-coroutines") / name).resolve()
    if output.is_file():
        validate(output, size, expected_hash)
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(f".{args.kind}.{os.getpid()}.part")
        try:
            with urllib.request.urlopen(f"{RELEASE}/{name}", timeout=45) as response, temporary.open("xb") as target:
                length = 0
                while chunk := response.read(65536):
                    length += len(chunk)
                    if length > size:
                        raise ValueError("Pinned artifact exceeded its declared size")
                    target.write(chunk)
            validate(temporary, size, expected_hash)
            temporary.replace(output)
        finally:
            temporary.unlink(missing_ok=True)
    print(output)


if __name__ == "__main__":
    main()
