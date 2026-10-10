"""Loopback-only external HP02 fixture. It never opens the app DB or modifies production DI."""

import argparse
import json
import struct
import threading
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


PORT = 18464
SCENARIOS = ("success", "failure", "cache")


def png(page):
    width, height = 320, 480
    colors = ((210, 60, 60), (60, 170, 80), (60, 100, 220), (220, 170, 40))
    pixel = bytes(colors[page - 1])
    # Four visibly distinct real images. A black bar count also distinguishes pages without OCR.
    raw = b"".join(
        b"\0" + (b"\0\0\0" if 40 <= y < 40 + page * 24 else pixel) * width for y in range(height)
    )

    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


class Fixture:
    def __init__(self, output):
        self.output = output
        self.lock = threading.Lock()
        self.release = threading.Event()
        self.scenario = None
        self.catalog_calls = 0
        self.image_calls = 0
        self.observations = []

    def state(self):
        with self.lock:
            return {
                "scenario": self.scenario,
                "directoryHeld": not self.release.is_set(),
                "catalogCalls": self.catalog_calls,
                "imageCalls": self.image_calls,
            }

    def observe(self, value):
        with self.lock:
            self.observations.append(value)
            self.output.write_text(json.dumps(self.observations, ensure_ascii=False, indent=2), encoding="utf-8")


def handler(fixture):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, code, value, kind="application/json"):
            body = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/control/state":
                self.reply(200, fixture.state())
            elif self.path in {f"/hp02-history/{mode}/catalog" for mode in ("success", "failure")}:
                with fixture.lock:
                    fixture.catalog_calls += 1
                    scenario = fixture.scenario
                if not fixture.release.wait(28):
                    self.reply(504, {"error": "external fixture directory gate timed out"})
                    return
                if scenario == "failure":
                    self.reply(500, {"error": "fixed catalogue failure"})
                    return
                work = self.path.removesuffix("/catalog")
                self.reply(200, [
                    {"url": f"{work}/chapter/{n}", "name": f"Ch.{n}", "number": n} for n in (3, 2, 1)
                ])
            elif self.path in {f"/image/{n}.png" for n in range(1, 5)}:
                with fixture.lock:
                    fixture.image_calls += 1
                self.reply(200, png(int(self.path.split("/")[-1][0])), "image/png")
            else:
                self.reply(404, {"error": "fixed route only"})

        def do_POST(self):
            if self.path in {f"/control/scenario/{s}" for s in SCENARIOS}:
                with fixture.lock:
                    fixture.scenario = self.path.split("/")[-1]
                    fixture.catalog_calls = 0
                    fixture.image_calls = 0
                    fixture.release.clear()
                self.reply(200, fixture.state())
            elif self.path == "/control/release":
                fixture.release.set()
                self.reply(200, fixture.state())
            elif self.path == "/control/observe":
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 65536:
                    self.reply(400, {"error": "bounded observation required"})
                    return
                fixture.observe(json.loads(self.rfile.read(length).decode("utf-8")))
                self.reply(200, {"recorded": True})
            else:
                self.reply(404, {"error": "fixed route only"})

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    process_root = Path(__file__).resolve().parent.parent / ".gradle-coordinator"
    if not output.is_relative_to(process_root.resolve()) or output.suffix != ".json":
        raise ValueError("Observations must stay in this worktree's ignored coordinator directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", PORT), handler(Fixture(output)))
    print(json.dumps({"fixtureReady": True, "host": "127.0.0.1", "port": PORT}), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
