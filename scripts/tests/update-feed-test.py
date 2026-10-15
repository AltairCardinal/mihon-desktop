"""Behavior contracts for the public update catalog and release promotion tool."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/update-feed.py"
REPO = "https://github.com/AltairCardinal/mihon-desktop"


def entry(**changes):
    value = {
        "channel": "testing", "platform": "windows", "arch": "x86_64", "packageType": "zip",
        "version": "0.11.19.92.abcdef12", "releaseUrl": f"{REPO}/releases/tag/windows-0.11.19.92",
        "downloadUrl": f"{REPO}/releases/download/windows-0.11.19.92/mihon-windows.zip",
        "sha256": "a" * 64, "sizeBytes": 42, "notes": "Fix update checks.\n修复更新检查。",
    }
    value.update(changes)
    return value


def published_release(value, **changes):
    result = {
        "tag_name": "windows-0.11.19.92", "draft": False, "prerelease": True,
        "html_url": value["releaseUrl"],
        "assets": [{"name": "mihon-windows.zip", "browser_download_url": value["downloadUrl"],
                    "digest": "sha256:" + value["sha256"], "size": value["sizeBytes"], "state": "uploaded"}],
    }
    result.update(changes)
    return result


class UpdateFeedCommandTest(unittest.TestCase):
    def test_empty_catalog_is_valid_without_claiming_a_published_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            catalog = Path(temporary) / "v1.json"
            catalog.write_text('{"schemaVersion": 1, "releases": []}', encoding="utf-8")
            result = subprocess.run([sys.executable, str(SCRIPT), "validate", str(catalog)],
                                    capture_output=True, text=True, encoding="utf-8")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("0 release", result.stdout)


class UpdateFeedContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        specification = importlib.util.spec_from_file_location("update_feed", SCRIPT)
        cls.module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(cls.module)

    def test_valid_multi_platform_feed_roundtrips_without_losing_notes(self):
        android = entry(platform="android", arch="universal", packageType="apk", version="0.19.4-aex.73",
                        versionCode=91, applicationId="app.mihon.desktop.fork", certificateSha256="b" * 64,
                        downloadUrl=f"{REPO}/releases/download/windows-0.11.19.92/mihon.apk")
        catalog = {"schemaVersion": 1, "releases": [entry(), android]}
        self.assertEqual(catalog, self.module.validate_feed(catalog))

    def test_rejects_malformed_schema_and_ambiguous_version_identities(self):
        invalid = [None, {}, {"schemaVersion": True, "releases": []}, {"schemaVersion": 2, "releases": []},
                   {"schemaVersion": 1, "releases": {}},
                   {"schemaVersion": 1, "releases": [entry(), entry(version="0.11.19.92.1234abcd")]}]
        for catalog in invalid:
            with self.subTest(catalog=catalog), self.assertRaises(ValueError):
                self.module.validate_feed(catalog)

    def test_rejects_unsafe_links_and_wrong_repository_or_tag(self):
        urls = [
            "http://github.com/AltairCardinal/mihon-desktop/releases/download/windows-0.11.19.92/a.zip",
            f"{REPO}.evil/releases/download/windows-0.11.19.92/a.zip",
            "https://github.com@evil.example/AltairCardinal/mihon-desktop/releases/download/windows-0.11.19.92/a.zip",
            f"{REPO}/releases/download/other-tag/a.zip", f"{REPO}/releases/download/windows-0.11.19.92/%2e%2e",
            f"{REPO}/releases/download/windows-0.11.19.92/a.zip?token=secret",
            f"{REPO}/releases/download/windows-0.11.19.92/a.zip#fragment",
            "https://github.com:444/AltairCardinal/mihon-desktop/releases/download/windows-0.11.19.92/a.zip",
            f"{REPO}/releases/download/windows-0.11.19.92/a%2fother.zip",
            f"{REPO}/releases/download/windows-0.11.19.92/a.zip?",
            f"{REPO}/releases/download/windows-0.11.19.92/a.zip#",
            f"\n{REPO}/releases/download/windows-0.11.19.92/a.zip",
        ]
        for url in urls:
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.module.validate_feed({"schemaVersion": 1, "releases": [entry(downloadUrl=url)]})

    def test_rejects_invalid_asset_identity_and_missing_integrity(self):
        changes = [dict(channel="nightly"), dict(platform="unknown"), dict(arch="x86"),
                   dict(packageType="apk"), dict(version="0.11.19"), dict(version="0.11.19.92.bad/path"),
                   dict(sha256=""), dict(sha256="g" * 64), dict(sizeBytes=True), dict(sizeBytes=0),
                   dict(sizeBytes=2 * 1024**3), dict(notes="x" * 32769)]
        changes.extend([dict(arch=[]), dict(packageType={}), dict(version="０.１１.１９.９２")])
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.module.validate_feed({"schemaVersion": 1, "releases": [entry(**change)]})

    def test_android_requires_formal_package_signer_and_integer_version_code(self):
        value = entry(platform="android", arch="universal", packageType="apk", version="0.19.4-aex.73",
                      downloadUrl=f"{REPO}/releases/download/windows-0.11.19.92/mihon.apk",
                      versionCode=91, applicationId="app.mihon.desktop.fork", certificateSha256="b" * 64)
        for change in [dict(versionCode=0), dict(versionCode=True), dict(applicationId="app.mihon"),
                       dict(applicationId="app.mihon.desktop.fork.dev"), dict(certificateSha256="")]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.module.validate_feed({"schemaVersion": 1, "releases": [value | change]})

    def test_append_requires_published_asset_with_matching_github_digest(self):
        value = entry()
        existing = {"schemaVersion": 1, "releases": []}
        release = published_release(value)
        result = self.module.append_entry(existing, value, release)
        self.assertEqual([value], result["releases"])
        self.assertEqual([], existing["releases"], "Validation must not mutate the source catalog")
        for change in [dict(draft=True), dict(prerelease=False), dict(tag_name="other"), dict(assets=[])]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.module.append_entry(existing, value, release | change)
        with self.assertRaises(ValueError):
            self.module.append_entry(existing, value, release | {"assets": [None]})
        for change in [dict(digest=None), dict(digest="sha256:" + "c" * 64), dict(size=43), dict(state="new")]:
            changed = copy.deepcopy(release)
            changed["assets"][0].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.module.append_entry(existing, value, changed)

    def test_repeated_promotion_is_idempotent_but_replacement_is_rejected(self):
        value = entry()
        catalog = {"schemaVersion": 1, "releases": [value]}
        self.assertEqual(catalog, self.module.append_entry(catalog, value, published_release(value)))
        replacement = entry(sha256="c" * 64)
        with self.assertRaises(ValueError):
            self.module.append_entry(catalog, replacement, published_release(replacement))

    def test_prepare_hashes_the_actual_artifact_and_refuses_mismatched_extensions(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / "mihon-windows.zip"
            artifact.write_bytes(b"actual candidate bytes")
            prepared = self.module.prepare_entry(artifact, platform="windows", arch="x86_64", package_type="zip",
                                                 version="0.11.19.92.abcdef12", channel="testing",
                                                 tag="windows-0.11.19.92", notes="Release notes")
            import hashlib
            self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), prepared["sha256"])
            self.assertEqual(artifact.stat().st_size, prepared["sizeBytes"])
            self.assertEqual(f"{REPO}/releases/download/windows-0.11.19.92/mihon-windows.zip", prepared["downloadUrl"])
            with self.assertRaises(ValueError):
                self.module.prepare_entry(artifact, platform="windows", arch="x86_64", package_type="msi",
                                          version="0.11.19.92.abcdef12", channel="testing",
                                          tag="windows-0.11.19.92", notes="Release notes")

    def test_atomic_writer_preserves_previous_catalog_when_validation_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "v1.json"
            original = '{"schemaVersion": 1, "releases": []}\n'
            path.write_text(original, encoding="utf-8")
            with self.assertRaises(ValueError):
                self.module.write_feed(path, {"schemaVersion": 2, "releases": []})
            self.assertEqual(original, path.read_text(encoding="utf-8"))
            self.module.write_feed(path, {"schemaVersion": 1, "releases": [entry()]})
            self.assertEqual(entry()["notes"], json.loads(path.read_text(encoding="utf-8"))["releases"][0]["notes"])

    def test_size_limit_covers_the_actual_pretty_printed_file(self):
        catalog = {"schemaVersion": 1, "releases": [entry()]}
        limit = len(json.dumps(catalog, ensure_ascii=False).encode("utf-8"))
        with patch.object(self.module, "MAX_FEED_BYTES", limit), self.assertRaises(ValueError):
            self.module.validate_feed(catalog)

    def test_android_promotion_uses_verified_candidate_manifest_identity(self):
        artifact = Path("verified.apk")
        verified = dict(variant="release", signatureVerified=True, versionCode=91,
                        applicationId="app.mihon.desktop.fork", certificateSha256="b" * 64,
                        versionName="0.19.4-aex.73")
        build_module = Mock()
        build_module.verify_artifact.return_value = verified
        spec = Mock()
        with patch.object(self.module.importlib.util, "spec_from_file_location", return_value=spec), \
                patch.object(self.module.importlib.util, "module_from_spec", return_value=build_module):
            identity, version = self.module.android_candidate_identity(artifact)
            build_module.verify_artifact.assert_called_once_with(artifact, build_module.AndroidTools.return_value)
            self.assertEqual(91, identity["versionCode"])
            self.assertEqual(verified["versionName"], version)
            build_module.verify_artifact.return_value = verified | {"signatureVerified": False}
            with self.assertRaises(ValueError):
                self.module.android_candidate_identity(artifact)

    def test_oversized_artifact_is_rejected_before_hashing(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / "mihon-windows.zip"
            artifact.write_bytes(b"large")
            with patch.object(self.module, "MAX_ASSET_BYTES", 4), \
                    patch.object(self.module.hashlib, "file_digest") as digest, \
                    self.assertRaises(ValueError):
                self.module.prepare_entry(artifact, platform="windows", arch="x86_64", package_type="zip",
                                          version="0.11.19.92", channel="testing", tag="v1", notes="")
            digest.assert_not_called()


if __name__ == "__main__":
    unittest.main()
