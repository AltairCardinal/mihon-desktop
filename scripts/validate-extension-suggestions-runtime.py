#!/usr/bin/env python3
"""Exercise a released Desktop runtime in fresh, explicitly isolated Test Mode profiles.

Python 3.9+, standard library only. Seed only an application-created, marked profile
after its process exits. No ordinary profile, external extension download, or second API.
The local v2 catalog serves the same signed JAR/APK fixtures used by Android acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import runpy
import secrets
import signal
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request


SIGNER = "9be8a18439915033e8362f25426323e8b7b94f223eadca4962ce5f91a23d6021"
FIXTURES = (
    ("aex00.external.v16", "A EIS v16", "1.6.0", 160000, "1.6",
     "aex00-external-v16-controlled-sample", (0xAE001601, 0xAE001602),
     "e623be999c1c6b7c9a5383253645f6dc3d496de44d44a4fa1cd1a4fe020c33c4"),
    ("aex00.external.v15", "B EIS v15", "1.5.0", 150000, "1.5",
     "aex00-external-v15-suspend-only", (0xAE0015,),
     "ffcaad5974329a319e20b3cabd8565117f23668315bddac5ab72eae819e74fcf"),
)


def wait_for(read, predicate, description, timeout=90):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = read()
            if predicate(last):
                return last
        except (ConnectionError, urllib.error.URLError, TimeoutError):
            pass
        time.sleep(0.1)
    raise AssertionError(f"Timed out: {description}; last={last}")


class FixtureServer:
    def __init__(self, resources: Path):
        self.files = {}
        self.gets = []
        self.fail_v15 = False
        self.hold_v16 = threading.Event()
        self.hold_v16.set()
        self.nonce = secrets.token_hex(12)
        for index, fixture in enumerate(FIXTURES):
            artifact = resources / (fixture[5] + ".jar")
            data = artifact.read_bytes()
            assert hashlib.sha256(data).hexdigest() == fixture[7], artifact
            self.files[f"/{index}.jar"] = data
            apk_name = ("aex00-external-v15-controlled-sample" if index == 1 else fixture[5]) + ".apk"
            apk = (resources / apk_name).read_bytes()
            expected = ("34c21ef4c3a5b60b789cd5dce95a78f638ba9875007f19d094cb3e344df4e182",
                        "caf80d849e2eb5ad8f0be5121f914d9cee1ee06c15653d15f33321602183a316")[index]
            assert hashlib.sha256(apk).hexdigest() == expected, apk_name
            self.files[f"/{index}.apk"] = apk
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                owner.gets.append(self.path)
                if self.path == "/0.jar":
                    if not owner.hold_v16.wait(30):
                        self.send_error(503)
                        return
                if self.path == "/1.jar" and owner.fail_v15:
                    self.send_error(500, "Controlled partial failure")
                    return
                if self.path == "/repo.json":
                    body = json.dumps(owner.catalog()).encode("utf-8")
                elif self.path.startswith("/website/" + owner.nonce + "/"):
                    body = b"<html><title>Mihon EIS acceptance</title>Controlled website</html>"
                elif self.path in owner.files:
                    body = owner.files[self.path]
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # A stopped download may close its own connection.

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def catalog(self):
        extensions = []
        for index, item in enumerate(FIXTURES):
            extensions.append({
                "packageName": item[0], "name": item[1], "versionName": item[2],
                "versionCode": item[3], "extensionLib": item[4], "contentWarning": "CONTENT_WARNING_SAFE",
                "resources": {"jarUrl": f"{self.base}/{index}.jar", "apkUrl": f"{self.base}/{index}.apk",
                              "iconUrl": f"{self.base}/icon.png"},
                "sources": [{"id": source,
                             "name": (f"AEX-00 v1.6 {'en' if source_index == 0 else 'zh'} fixture" if index == 0 else
                                      "AEX-00 v1.5 suspend-only fixture"),
                             "language": "zh" if index == 0 and source_index == 1 else "en",
                             "homeUrl": f"{self.base}/website/{self.nonce}/{source}"}
                            for source_index, source in enumerate(item[6])],
            })
        return {"name": "EIS controlled local repository", "badgeLabel": "EIS", "signingKey": SIGNER,
                "contact": {"website": self.base}, "extensionList": {"extensions": extensions}}

    def close(self):
        self.hold_v16.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class MacApplicationProcess:
    """LaunchServices owns the child, so its Unix exit status is unavailable.

    Keep the real application's PID and microsecond process-start identity,
    reusing the coordinator's libproc reader before polling or signalling it.
    """
    LAUNCH = r'''
ObjC.import("AppKit");
function run(argv) {
    const configuration = $.NSMutableDictionary.dictionary;
    configuration.setObjectForKey($(JSON.parse(argv[1])), $.NSWorkspaceLaunchConfigurationArguments);
    const error = Ref();
    const app = $.NSWorkspace.sharedWorkspace.launchApplicationAtURLOptionsConfigurationError(
        $.NSURL.fileURLWithPath(argv[0]), $.NSWorkspaceLaunchNewInstance, configuration, error
    );
    const pid = Number(app.processIdentifier);
    if (!(pid > 0)) throw new Error("LaunchServices did not return an application PID");
    return JSON.stringify({pid: pid});
}
'''

    def __init__(self, argv):
        self.executable = Path(argv[0]).resolve(strict=True)
        self.bundle = self.executable.parents[2]
        assert self.bundle.suffix == ".app" and self.executable.parent.name == "MacOS"
        assert self.executable.parent.parent.name == "Contents"
        self.arguments = argv[1:]
        self.pid = None
        self.identity = None
        self.returncode = None
        self.launch_attempted = False
        self.read_identity = runpy.run_path(str(Path(__file__).with_name("gradle-coordinator.py")))["darwin_process_identity"]

    def recover_identity(self):
        """Read only exact executable candidates; never expose their secret argv."""
        listing = subprocess.run(
            ["/bin/ps", "-axww", "-o", "pid=,comm="],
            capture_output=True, text=True, encoding="utf-8", check=True,
        )
        matches = []
        for line in listing.stdout.splitlines():
            parts = line.strip().split(None, 1)
            if len(parts) != 2 or not parts[0].isdigit():
                continue
            candidate = Path(parts[1])
            if not candidate.is_absolute():
                continue
            try:
                if not candidate.samefile(self.executable):
                    continue
            except OSError:
                continue
            pid = int(parts[0])
            before = self.read_identity(pid)
            command = subprocess.run(
                ["/bin/ps", "-ww", "-p", str(pid), "-o", "command="],
                capture_output=True, text=True, encoding="utf-8",
            )
            # Unique profile, loopback port and random one-use token must all match.
            padded = " " + command.stdout.strip() + " "
            if (before is not None and command.returncode == 0 and
                    all(" " + argument + " " in padded for argument in self.arguments) and
                    self.read_identity(pid) == before):
                matches.append((pid, before))
        if len(matches) > 1:
            raise RuntimeError("Multiple applications match this exact owned launch; refusing cleanup")
        if matches:
            self.pid, self.identity = matches[0]
            return True
        return False

    def launch(self):
        if self.recover_identity():
            self.pid, self.identity = None, None
            raise RuntimeError("An application already owns this exact launch; refusing a duplicate")
        self.launch_attempted = True
        try:
            launched = subprocess.run(
                ["/usr/bin/osascript", "-l", "JavaScript", "-e", self.LAUNCH, str(self.bundle), json.dumps(self.arguments)],
                capture_output=True, text=True, encoding="utf-8", timeout=30,
            )
            if launched.returncode != 0:
                raise RuntimeError("LaunchServices launcher failed")
            reported_pid = int(json.loads(launched.stdout)["pid"])
            if not self.recover_identity() or self.pid != reported_pid:
                raise RuntimeError("Launched application identity could not be verified")
        except Exception:
            # Runtime retains this object before launch. Even malformed output or a
            # launcher timeout can recover the exact child for finally cleanup.
            try:
                self.recover_identity()
            except Exception:
                pass  # Retain the unresolved handle; stop retries without signalling unknown processes.
            raise RuntimeError("macOS GUI launch failed; exact owned-process recovery was attempted") from None

    def poll(self):
        if self.identity is None:
            return None  # Unconfirmed launch is never treated as a stopped process.
        if self.returncode is None and self.read_identity(self.pid) != self.identity:
            self.returncode = "unavailable (LaunchServices child)"
        return self.returncode

    def wait(self, timeout):
        deadline = time.monotonic() + timeout
        while self.poll() is None:
            if time.monotonic() >= deadline:
                raise subprocess.TimeoutExpired("owned macOS application", timeout)
            time.sleep(0.1)
        return self.returncode

    def terminate(self):
        if self.identity is not None and self.poll() is None:
            os.kill(self.pid, signal.SIGTERM)

    def kill(self):
        if self.identity is not None and self.poll() is None:
            os.kill(self.pid, signal.SIGKILL)


class Runtime:
    def __init__(self, executable: Path, profile: Path, evidence: Path):
        self.executable, self.profile, self.evidence = executable, profile, evidence
        self.process = None
        self.launches = []
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, path, payload=None, token=False):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["X-Mihon-Platform-Acceptance-Token"] = self.token
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=body, headers=headers)
        try:
            response = self.client.open(req, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return json.loads(response.read().decode("utf-8"))

    def state(self):
        if self.process is not None and self.process.poll() is not None:
            raise RuntimeError(f"Owned runtime exited early: PID {self.process.pid}, exit {self.process.returncode}")
        return self.request("/test/state")

    def extension(self):
        return self.state()["extension"]

    def action(self, name, params=None, success=True, token=False):
        result = self.request("/test/action/" + name, params or {}, token)
        assert result["success"] == success, result
        return result.get("extension")

    def start(self):
        assert self.process is None
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        self.base = f"http://127.0.0.1:{port}"
        self.token = secrets.token_hex(32)
        self.log = (self.evidence / f"{self.profile.name}-{len(self.launches)}.log").open("wb")
        argv = [str(self.executable), "--test-mode", f"--test-profile={self.profile}",
                f"--test-http-port={port}", f"--platform-acceptance-token={self.token}"]
        if sys.platform == "darwin":
            self.process = MacApplicationProcess(argv)
            try:
                self.process.launch()
            finally:
                self.launches.append({"pid": self.process.pid, "profile": str(self.profile), "port": port,
                                      "processIdentity": self.process.identity, "exitStatusAvailable": False})
                self.log.write(f"LaunchServices application PID={self.process.pid}; application output uses its profile logs.\n".encode("utf-8"))
                self.log.flush()
        else:
            self.process = subprocess.Popen(argv, stdout=self.log, stderr=subprocess.STDOUT)
            self.launches.append({"pid": self.process.pid, "profile": str(self.profile), "port": port,
                                  "exitStatusAvailable": True})
        wait_for(self.state, lambda value: value.get("extension") is not None, "runtime HTTP ready")
        requested = self.action("extension_suggestion_show")["navigationRequestId"]
        wait_for(self.extension, lambda value: value["displayedRequestId"] == requested,
                 "real Browse extension content mounted")

    def stop(self):
        if self.process is None:
            return
        if isinstance(self.process, MacApplicationProcess) and not self.process.launch_attempted:
            self.log.close()
            self.process = None
            return
        failure = None
        try:
            if isinstance(self.process, MacApplicationProcess) and self.process.identity is None:
                if not self.process.recover_identity():
                    raise RuntimeError("GUI launch ownership is unconfirmed; no HTTP shutdown or signal was sent")
                self.launches[-1].update(pid=self.process.pid, processIdentity=self.process.identity)
            try:
                self.request("/test/shutdown", {})
                self.process.wait(timeout=30)
                if not isinstance(self.process, MacApplicationProcess) and self.process.returncode != 0:
                    failure = RuntimeError(f"Owned runtime exited {self.process.returncode}")
            except Exception as error:
                failure = error
                if self.process.poll() is None:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        self.process.kill()
                        self.process.wait(timeout=10)
        finally:
            self.log.close()
            if self.process.poll() is not None:
                self.process = None
        if failure is not None:
            raise failure

    def seed(self, server):
        assert self.process is None
        assert (self.profile / ".mihon-test-profile").read_text(encoding="utf-8") == "mihon-desktop-test-profile-v1\n"
        databases = list((self.profile / "home").rglob("mihon.db"))
        assert len(databases) == 1, databases
        db = databases[0].resolve()
        assert self.profile in db.parents
        with sqlite3.connect(str(db)) as connection:
            assert connection.execute("SELECT count(*) FROM mangas").fetchone()[0] == 0
            assert connection.execute("SELECT count(*) FROM extension_repos").fetchone()[0] == 0
            connection.execute("INSERT INTO extension_repos(base_url,name,website,signing_key_fingerprint,index_url) VALUES(?,?,?,?,?)",
                               (server.base, "EIS controlled", server.base, SIGNER, server.base + "/repo.json"))
            for item in FIXTURES:
                for source in item[6]:
                    connection.execute("""INSERT INTO mangas(source,url,title,status,favorite,initialized,viewer,chapter_flags,
                        cover_last_modified,date_added) VALUES(?,?,?,0,1,1,0,0,0,0)""",
                                       (source, f"/eis/owned/{source}", f"EIS favorite {source}"))

    def initialize(self, server):
        self.start()
        self.stop()
        self.seed(server)
        self.start()
        self.action("extension_refresh")
        return wait_for(self.extension, lambda value: not value["suggestions"]["loading"] and
                        value["suggestions"]["total"] == 2, "two real SQL/catalog suggestions")


def validate(executable, resources, output):
    output.mkdir(parents=True, exist_ok=False)
    server = FixtureServer(resources)
    runtimes = []
    evidence = {"executable": str(executable), "executableSha256": hashlib.sha256(executable.read_bytes()).hexdigest(),
                "checks": [], "profiles": []}
    try:
        local = Runtime(executable, output / "local-state", output)
        runtimes.append(local)
        initial = local.initialize(server)
        rows = initial["suggestions"]["rows"]
        assert {source["id"] for row in rows for source in row["sources"]} == {s for item in FIXTURES for s in item[6]}
        first = next(row for row in rows if row["packageName"] == FIXTURES[0][0])
        ignored = next(row for row in rows if row["packageName"] == FIXTURES[1][0])
        assert {source["id"] for source in first["websites"]} == set(FIXTURES[0][6]), first
        for index, source in enumerate(first["websites"]):
            if index:
                local.stop(); local.start()
            current = wait_for(local.extension, lambda value: not value["suggestions"]["loading"] and
                               value["suggestions"]["total"] == 2, "website current catalog after restart")
            first = next(row for row in current["suggestions"]["rows"] if row["packageName"] == FIXTURES[0][0])
            local.action("extension_suggestion_website", {"identity": first["identity"], "sourceId": str(source["id"])}, token=True)
            website_path = "/website/" + server.nonce + "/" + str(source["id"])
            wait_for(lambda: server.gets, lambda gets: website_path in gets, "actual system browser GET")
        local.stop(); local.start()
        current = wait_for(local.extension, lambda value: not value["suggestions"]["loading"] and
                           value["suggestions"]["total"] == 2, "single website current catalog")
        ignored = next(row for row in current["suggestions"]["rows"] if row["packageName"] == FIXTURES[1][0])
        assert len(ignored["websites"]) == 1
        source = ignored["websites"][0]
        local.action("extension_suggestion_website", {"identity": ignored["identity"], "sourceId": str(source["id"])}, token=True)
        website_path = "/website/" + server.nonce + "/" + str(source["id"])
        wait_for(lambda: server.gets, lambda gets: website_path in gets, "single-source actual browser GET")
        local.action("extension_suggestion_ignore", {"identity": ignored["identity"]})
        local.action("extension_suggestion_toggle")
        wait_for(local.extension, lambda value: value["suggestions"]["total"] == 1 and not value["suggestions"]["expanded"], "local ignore/fold")
        local.stop(); local.start()
        wait_for(local.extension, lambda value: value["suggestions"]["total"] == 1 and not value["suggestions"]["expanded"], "restart persisted local state")
        local.stop()
        evidence["checks"].append("real SQL matching, multi-source browser GET, ignore/fold restart")

        batch = Runtime(executable, output / "batch-state", output)
        runtimes.append(batch)
        initial = batch.initialize(server)
        assert initial["suggestions"]["expanded"]  # Independent local profile has no other profile's state.
        server.fail_v15 = True
        confirmation = batch.action("extension_suggestion_batch_request")["confirmation"]
        batch.action("extension_suggestion_batch_confirm", {"confirmationId": confirmation["id"]})
        batch.action("extension_suggestion_batch_confirm", {"confirmationId": confirmation["id"]}, success=False)
        result = wait_for(batch.extension, lambda value: not value["batch"]["running"] and value["batch"]["completed"] == 2, "partial batch completion")
        assert {item["result"] for item in result["batch"]["items"]} == {"Installed", "Failed"}, result
        server.fail_v15 = False
        confirmation = batch.action("extension_suggestion_batch_retry_request")["confirmation"]
        assert [item["packageName"] for item in confirmation["artifacts"]] == [FIXTURES[1][0]]
        batch.action("extension_suggestion_batch_confirm", {"confirmationId": confirmation["id"]})
        wait_for(batch.extension, lambda value: all(item["result"] == "Installed" for item in value["batch"]["items"]), "explicit failure retry")
        batch.stop(); batch.start()
        loaded = wait_for(batch.extension, lambda value: len(value["installed"]) == 2 and
                          not value["suggestions"]["loading"] and value["suggestions"]["total"] == 0,
                          "real loader and installed exclusion after restart")
        assert loaded["batch"]["items"] == [], loaded
        assert {source["id"] for item in loaded["installed"] for source in item["sources"]} == {s for item in FIXTURES for s in item[6]}
        batch.action("extension_uninstall", {"packageName": FIXTURES[0][0]})
        available = wait_for(batch.extension, lambda value: value["suggestions"]["total"] == 1 and
                             not value["suggestions"]["loading"], "uninstall recomputes missing sources")
        row = available["suggestions"]["rows"][0]
        batch.action("extension_suggestion_install", {"identity": row["identity"]})
        wait_for(batch.extension, lambda value: len(value["installed"]) == 2 and value["suggestions"]["total"] == 0,
                 "single suggestion real installation")

        batch.action("extension_uninstall", {"packageName": FIXTURES[0][0]})
        wait_for(batch.extension, lambda value: value["suggestions"]["total"] == 1 and
                 not value["suggestions"]["loading"], "prepare cancellation candidate")
        server.hold_v16.clear()
        request_count = server.gets.count("/0.jar")
        confirmation = batch.action("extension_suggestion_batch_request")["confirmation"]
        batch.action("extension_suggestion_batch_confirm", {"confirmationId": confirmation["id"]})
        wait_for(lambda: server.gets.count("/0.jar"), lambda count: count > request_count, "production download started")
        # The ordinary entry must not create a second download or replace the batch owner.
        batch.action("extension_install", {"packageName": FIXTURES[0][0]}, success=False)
        batch.action("extension_suggestion_batch_stop")
        server.hold_v16.set()
        stopped = wait_for(batch.extension, lambda value: not value["batch"]["running"], "stop waits for real download cleanup")
        assert stopped["batch"]["items"][0]["result"] == "Stopped", stopped
        assert server.gets.count("/0.jar") == request_count + 1
        assert FIXTURES[0][0] not in {item["packageName"] for item in stopped["installed"]}
        batch.stop()
        evidence["checks"].append("profile isolation, partial failure/retry, no duplicate confirmation, real loader/restart/no replay, single install, ordinary-vs-batch deduplication, stop")
        evidence["profiles"] = [item.launches for item in runtimes]
        evidence["httpRequests"] = server.gets
        evidence["success"] = True
    except BaseException as failure:
        evidence["failure"] = str(failure)
        raise
    finally:
        evidence["profiles"] = [item.launches for item in runtimes]
        cleanup_errors = []
        for runtime in runtimes:
            if runtime.process is not None:
                try:
                    runtime.stop()
                except Exception as error:
                    cleanup_errors.append(str(error))
        server.close()
        if cleanup_errors:
            evidence["cleanupErrors"] = cleanup_errors
            evidence["success"] = False
        (output / "result.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        if cleanup_errors and "failure" not in evidence:
            raise RuntimeError("Runtime cleanup failed: " + "; ".join(cleanup_errors))
    return evidence


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--executable", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resources", type=Path, default=Path(__file__).resolve().parent.parent / "app-desktop/src/test/resources/extensions/real")
    args = parser.parse_args()
    result = validate(args.executable.resolve(strict=True), args.resources.resolve(strict=True), args.output.resolve())
    print(json.dumps({"success": result["success"], "checks": result["checks"], "evidence": str(args.output / "result.json")}, ensure_ascii=False))
