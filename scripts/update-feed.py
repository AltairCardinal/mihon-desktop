#!/usr/bin/env python3
"""Prepare and promote verified GitHub release assets into the public update feed.

This tool never builds, signs, installs, uploads, publishes a release, or pushes Git.
Promotion reads the published release with the maintainer's existing gh login.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "AltairCardinal/mihon-desktop"
REPOSITORY_URL = f"https://github.com/{REPOSITORY}"
DEFAULT_FEED = ROOT / "website/public/updates/v1.json"
MAX_FEED_BYTES = 512 * 1024
MAX_ASSET_BYTES = 2 * 1024**3 - 1
MAX_ANDROID_BYTES = 512 * 1024**2
SAFE_SEGMENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,179}\Z")
CHECKSUM = re.compile(r"[0-9a-f]{64}\Z")
DESKTOP_VERSION = re.compile(r"([0-9]+)\.([0-9]+)\.([0-9]+)\.([0-9]+)(?:\.([0-9a-fA-F]{7,40}))?\Z")
TARGETS = {
    "android": ({"universal", "arm64-v8a", "armeabi-v7a", "x86_64", "x86"}, {"apk"}),
    "windows": ({"x86_64"}, {"zip", "msi"}),
    "macos": ({"x86_64", "arm64"}, {"zip", "dmg"}),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def positive_integer(value, maximum=MAX_ASSET_BYTES):
    return type(value) is int and 0 < value <= maximum


def safe_segment(value):
    return isinstance(value, str) and SAFE_SEGMENT.fullmatch(value) and ".." not in value


def release_path(url, kind):
    require(isinstance(url, str) and len(url) <= 1024, "Missing or oversized release URL")
    require(not any(ord(character) <= 32 for character in url), "Release URL contains whitespace or control characters")
    require("?" not in url and "#" not in url, "Release URL cannot contain a query or fragment")
    parsed = urlsplit(url)
    require(parsed.scheme == "https" and parsed.netloc == "github.com", "Release URL must use https://github.com")
    require(not parsed.query and not parsed.fragment, "Release URL cannot contain a query or fragment")
    prefix = f"/{REPOSITORY}/releases/{kind}/"
    require(parsed.path.startswith(prefix), "Release URL belongs to another project")
    segments = parsed.path[len(prefix):].split("/")
    require(len(segments) == (1 if kind == "tag" else 2), "Invalid release URL path")
    require(all(safe_segment(value) for value in segments), "Unsafe tag or asset filename")
    return segments


def entry_identity(value):
    version = value["versionCode"] if value["platform"] == "android" else tuple(
        int(part) for part in DESKTOP_VERSION.fullmatch(value["version"]).groups()[:4]
    )
    return value["platform"], value["arch"], value["channel"], version


def validate_entry(value):
    require(isinstance(value, dict), "Release entry must be an object")
    platform = value.get("platform")
    require(isinstance(platform, str) and platform in TARGETS, "Unsupported platform")
    architectures, packages = TARGETS[platform]
    require(value.get("channel") in ("stable", "testing"), "Unsupported update channel")
    require(isinstance(value.get("arch"), str) and isinstance(value.get("packageType"), str), "Invalid package target")
    require(value["arch"] in architectures and value["packageType"] in packages, "Unsupported package target")
    version = value.get("version")
    require(isinstance(version, str) and 0 < len(version) <= 120, "Invalid release version")
    if platform == "android":
        require(bool(re.fullmatch(r"[A-Za-z0-9_.+-]+", version)), "Invalid Android version name")
        require(positive_integer(value.get("versionCode"), 2100000000), "Invalid Android versionCode")
        require(value.get("applicationId") == "app.mihon.desktop.fork", "Android entry must use the formal fork identity")
        require(isinstance(value.get("certificateSha256"), str) and CHECKSUM.fullmatch(value["certificateSha256"]),
                "Android signer fingerprint is required")
    else:
        match = DESKTOP_VERSION.fullmatch(version)
        require(match is not None, "Desktop version must contain four numbers and an optional commit hash")
        require(all(int(part) <= 2147483647 for part in match.groups()[:4]), "Desktop version component is too large")
        require(not any(key in value for key in ("versionCode", "applicationId", "certificateSha256")),
                "Android identity cannot be attached to a Desktop entry")
    tag = release_path(value.get("releaseUrl"), "tag")[0]
    asset_tag, asset_name = release_path(value.get("downloadUrl"), "download")
    require(tag == asset_tag, "Release and download tags differ")
    require(asset_name.endswith("." + value["packageType"]), "Asset extension does not match packageType")
    require(isinstance(value.get("sha256"), str) and CHECKSUM.fullmatch(value["sha256"]), "Asset SHA-256 is required")
    require(positive_integer(value.get("sizeBytes"), MAX_ANDROID_BYTES if platform == "android" else MAX_ASSET_BYTES),
            "Invalid or oversized artifact length")
    notes = value.get("notes")
    require(isinstance(notes, str) and len(notes) <= 32768, "Release notes exceed 32768 characters")
    require(not any(ord(character) < 32 and character not in "\n\r\t" for character in notes), "Release notes contain control characters")
    return value


def validate_feed(feed):
    require(isinstance(feed, dict), "Update feed must be an object")
    require(type(feed.get("schemaVersion")) is int and feed["schemaVersion"] == 1, "Unsupported feed schema")
    releases = feed.get("releases")
    require(isinstance(releases, list) and len(releases) <= 256, "Feed must contain at most 256 release entries")
    identities = set()
    for value in releases:
        validate_entry(value)
        identity = entry_identity(value)
        require(identity not in identities, "Ambiguous duplicate platform/channel/version identity")
        identities.add(identity)
    require(len((json.dumps(feed, ensure_ascii=False, indent=2) + "\n").encode("utf-8")) <= MAX_FEED_BYTES, "Feed exceeds 512 KiB")
    return feed


def read_json(path):
    path = Path(path)
    require(path.stat().st_size <= MAX_FEED_BYTES, "JSON input exceeds 512 KiB")
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def write_feed(path, value):
    write_json(path, validate_feed(value))


def android_candidate_identity(artifact):
    specification = importlib.util.spec_from_file_location("mihon_android_build", ROOT / "scripts/build-android.py")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    record = module.verify_artifact(artifact, module.AndroidTools())
    require(record.get("variant") == "release" and record.get("signatureVerified") is True, "Only a signed formal Android candidate can be promoted")
    return {key: record[key] for key in ("versionCode", "applicationId", "certificateSha256")}, record["versionName"]


def prepare_entry(artifact, *, platform, arch, package_type, version, channel, tag, notes):
    artifact = Path(artifact).resolve(strict=True)
    require(artifact.is_file() and safe_segment(artifact.name), "Artifact must have a safe, single-segment filename")
    require(safe_segment(tag), "Unsafe release tag")
    require(artifact.suffix == "." + package_type, "Artifact extension does not match package type")
    require(positive_integer(artifact.stat().st_size, MAX_ANDROID_BYTES if platform == "android" else MAX_ASSET_BYTES),
            "Invalid or oversized artifact length")
    with artifact.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    value = {
        "channel": channel, "platform": platform, "arch": arch, "packageType": package_type,
        "version": version, "releaseUrl": f"{REPOSITORY_URL}/releases/tag/{tag}",
        "downloadUrl": f"{REPOSITORY_URL}/releases/download/{tag}/{artifact.name}",
        "sha256": digest, "sizeBytes": artifact.stat().st_size, "notes": notes,
    }
    if platform == "android":
        identity, candidate_version = android_candidate_identity(artifact)
        require(version == candidate_version, "Version does not match the verified Android candidate")
        value.update(identity)
    return validate_entry(value)


def append_entry(feed, value, release):
    validate_feed(feed)
    validate_entry(value)
    require(isinstance(release, dict), "GitHub release response must be an object")
    require(release.get("draft") is False, "The GitHub release must be published before promotion")
    require(release.get("prerelease") is (value["channel"] == "testing"), "GitHub prerelease status disagrees with channel")
    require(release.get("html_url") == value["releaseUrl"], "GitHub release URL differs from prepared entry")
    require(release.get("tag_name") == release_path(value["releaseUrl"], "tag")[0], "GitHub release tag differs")
    assets = release.get("assets")
    require(isinstance(assets, list), "GitHub release assets are missing")
    require(all(isinstance(asset, dict) for asset in assets), "Invalid GitHub asset metadata")
    matching = [asset for asset in assets if asset.get("browser_download_url") == value["downloadUrl"]]
    require(len(matching) == 1, "Prepared artifact is missing or ambiguous in the published release")
    asset = matching[0]
    require(asset.get("state") == "uploaded", "GitHub asset upload has not completed")
    require(asset.get("digest") == "sha256:" + value["sha256"], "GitHub asset SHA-256 differs from local artifact")
    require(type(asset.get("size")) is int and asset["size"] == value["sizeBytes"], "GitHub asset length differs")
    existing = next((item for item in feed["releases"] if entry_identity(item) == entry_identity(value)), None)
    require(existing is None or existing == value, "A published version cannot be replaced; allocate a new version")
    return validate_feed({"schemaVersion": 1, "releases": [*feed["releases"], *([] if existing else [value])]})


def github_release(tag):
    require(safe_segment(tag), "Unsafe release tag")
    result = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/releases/tags/{tag}"],
                            capture_output=True, text=True, encoding="utf-8", timeout=60)
    require(result.returncode == 0, "Cannot read the published release; verify gh authentication and network access")
    require(len(result.stdout.encode("utf-8")) <= MAX_FEED_BYTES, "GitHub release metadata exceeds limit")
    return json.loads(result.stdout)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate", help="Validate an existing static feed without networking")
    validate.add_argument("feed", type=Path, nargs="?", default=DEFAULT_FEED)
    prepare = commands.add_parser("prepare", help="Hash an existing artifact and prepare an unpublished entry")
    prepare.add_argument("--artifact", type=Path, required=True)
    prepare.add_argument("--platform", choices=TARGETS, required=True)
    prepare.add_argument("--arch", required=True)
    prepare.add_argument("--package-type", choices=("apk", "zip", "msi", "dmg"), required=True)
    prepare.add_argument("--version", required=True)
    prepare.add_argument("--channel", choices=("stable", "testing"), default="testing")
    prepare.add_argument("--tag", required=True)
    prepare.add_argument("--notes-file", type=Path, required=True)
    prepare.add_argument("--output", type=Path, required=True)
    promote = commands.add_parser("add", help="Verify the published GitHub asset and append to the local feed")
    promote.add_argument("--entry", type=Path, required=True)
    promote.add_argument("--feed", type=Path, default=DEFAULT_FEED)
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            feed = validate_feed(read_json(args.feed))
            print(f"Valid update feed: {len(feed['releases'])} release entries")
        elif args.command == "prepare":
            value = prepare_entry(args.artifact, platform=args.platform, arch=args.arch, package_type=args.package_type,
                                  version=args.version, channel=args.channel, tag=args.tag,
                                  notes=args.notes_file.read_text(encoding="utf-8"))
            require(not args.output.exists(), "Prepared entry already exists; use a new output path")
            write_json(args.output, value)
            print(f"Prepared entry: {args.output.resolve()} (not published)")
        else:
            value = validate_entry(read_json(args.entry))
            feed = validate_feed(read_json(args.feed))
            updated = append_entry(feed, value, github_release(release_path(value["releaseUrl"], "tag")[0]))
            write_feed(args.feed, updated)
            print(f"Updated local feed: {args.feed.resolve()} (review, commit and deploy through Pages)")
        return 0
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"Update feed error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
