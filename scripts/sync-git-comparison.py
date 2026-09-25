"""Local-only sync/Git benchmark helpers. Private samples stay outside tracked evidence."""

import argparse
import hashlib
import json
import os
import random
import sqlite3
import statistics
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def distribution(values):
    values = sorted(values)
    return {"min": values[0], "median": statistics.median(values),
            "p95": values[min(len(values) - 1, int(len(values) * .95))], "max": values[-1]}


def sample_stats(records):
    return {
        "items": len(records),
        "publicReadingChapters": sum(len(r["chapters"]) for r in records),
        "readingChaptersPerItem": distribution([len(r["chapters"]) for r in records]),
        "inputRecordUtf8Bytes": distribution([len(encoded(r)) for r in records]),
        "mangaInputUtf8Bytes": distribution([len(encoded(r["manga"])) for r in records]),
        "nullAuthor": sum(r["manga"]["author"] is None for r in records),
        "nullArtist": sum(r["manga"]["artist"] is None for r in records),
        "nullThumbnail": sum(r["manga"]["thumbnail_url"] is None for r in records),
        "sourceCounts": {str(k): sum(r["manga"]["source"] == k for r in records)
                         for k in sorted({r["manga"]["source"] for r in records})},
    }


def altered_url(value, rng):
    parts = urlsplit(value)
    chars = list(parts.path)
    positions = [i for i, c in enumerate(chars) if c.isascii() and c.isalnum()]
    for i in rng.sample(positions, min(12, len(positions))):
        chars[i] = rng.choice("0123456789" if chars[i].isdigit() else "abcdefghijklmnopqrstuvwxyz")
    if not positions:
        chars += list("~" + format(rng.getrandbits(32), "08x"))
    return urlunsplit((parts.scheme, parts.netloc, "".join(chars), parts.query, parts.fragment))


def sample(args):
    out = args.output.resolve()
    if out.exists():
        raise RuntimeError("Refusing to overwrite a private input file")
    connection = sqlite3.connect(args.database.resolve().as_uri() + "?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only=ON")
    connection.execute("BEGIN")
    mangas = connection.execute(
        "SELECT _id,source,url,title,author,artist,thumbnail_url FROM mangas "
        "WHERE favorite=1 ORDER BY _id"
    ).fetchall()
    readings = connection.execute(
        "SELECT M.source,M.url AS parent_url,C.url,C.name,C.chapter_number,C.source_order,C.scanlator,"
        "coalesce(PR.public_read,C.read) AS read,coalesce(PR.public_page,C.last_page_read) AS last_page_read,"
        "coalesce(PR.public_read_at,H.last_read,0) AS last_read "
        "FROM chapters C JOIN mangas M ON M._id=C.manga_id LEFT JOIN history H ON H.chapter_id=C._id "
        "LEFT JOIN sync_private_reading PR ON PR.chapter_id=C._id WHERE M.favorite=1 AND "
        "(coalesce(PR.public_read,C.read)=1 OR coalesce(PR.public_page,C.last_page_read)>0 OR "
        "coalesce(PR.public_read_at,H.last_read,0)>0) ORDER BY C._id"
    ).fetchall()
    connection.close()
    grouped = {}
    for row in readings:
        record = dict(row)
        record["read"] = bool(record["read"])
        key = (record.pop("source"), record.pop("parent_url"))
        grouped.setdefault(key, {}).setdefault(record["url"], record)
    templates = {}
    for row in mangas:
        manga = dict(row)
        manga.pop("_id")
        key = (manga["source"], manga["url"])
        templates.setdefault(key, {"manga": manga, "chapters": list(grouped.get(key, {}).values())})
    originals = list(templates.values())
    if not originals or args.count < len(originals):
        raise ValueError("Expected nonempty library no larger than requested sample")
    rng = random.Random(args.seed)
    records = json.loads(json.dumps(originals))
    used = set(templates)
    pool = []
    while len(records) < args.count:
        if not pool:
            pool = list(range(len(originals)))
            rng.shuffle(pool)
        record = json.loads(json.dumps(originals[pool.pop()]))
        manga = record["manga"]
        candidate = altered_url(manga["url"], rng)
        while (manga["source"], candidate) in used:
            candidate = altered_url(manga["url"], rng)
        manga["url"] = candidate
        # Change labels without inflating UTF-8 length; preserve nulls and author/thumbnail distributions.
        title = list(manga["title"])
        for i in rng.sample(range(len(title)), min(3, len(title))):
            c = title[i]
            if c.isascii() and c.isalnum():
                title[i] = rng.choice("0123456789" if c.isdigit() else "abcdefghijklmnopqrstuvwxyz")
            elif "\u4e00" <= c <= "\u9fff":
                title[i] = rng.choice("山川林海云风星月光影书画")
        manga["title"] = "".join(title)
        used.add((manga["source"], candidate))
        records.append(record)
    payload = {"schema": 1, "seed": args.seed, "records": records}
    write_json(out, payload)
    summary = {"schema": 1, "seed": args.seed, "original": sample_stats(originals),
               "generated": sample_stats(records), "inputSha256": hashlib.sha256(out.read_bytes()).hexdigest(),
               "inputBytes": out.stat().st_size, "selection": "favorites and their PUBLIC reading states only",
               "resampling": "original library plus shuffled balanced copies; mutate URLs/titles; preserve chapter profiles"}
    write_json(out.with_name("sample-summary.json"), summary)
    print(json.dumps(summary, ensure_ascii=False))


def git_run(cwd, *args, env=None):
    result = subprocess.run(["git", "-c", "credential.helper=", "-c", "core.autocrlf=false", *args],
                            cwd=cwd, env=env, capture_output=True, timeout=300)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8"))
    return result.stdout


def files_at(directory):
    result = {}
    for path in sorted(directory.rglob("*")):
        if path.is_symlink():
            raise ValueError("Artifact symlinks are not accepted")
        if path.is_file():
            relative = path.relative_to(directory)
            if relative.parts[0] != ".mihon-sync":
                raise ValueError("Only .mihon-sync artifact paths are accepted")
            result[relative.as_posix()] = path.read_bytes()
    if not result:
        raise ValueError("No artifacts")
    return result


def install_files(directory, files):
    for relative, body in files.items():
        path = directory / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)


def native_git(args):
    initial, final = files_at(args.initial), files_at(args.final)
    if not initial.keys() <= final.keys():
        raise ValueError("This first-upload benchmark expects no initial paths removed")
    work = args.work.resolve()
    if work.exists():
        raise RuntimeError("A fresh isolated work directory is required")
    work.mkdir(parents=True)
    client, remote = work / "client", work / "remote.git"
    client.mkdir()
    env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
               GIT_TERMINAL_PROMPT="0", GIT_AUTHOR_NAME="Sync benchmark", GIT_AUTHOR_EMAIL="benchmark@invalid",
               GIT_COMMITTER_NAME="Sync benchmark", GIT_COMMITTER_EMAIL="benchmark@invalid",
               NO_PROXY="localhost,127.0.0.1", no_proxy="localhost,127.0.0.1")
    git_run(work, "init", "--bare", str(remote), env=env)
    git_run(remote, "config", "http.receivepack", "true", env=env)
    git_run(client, "init", "-b", "mihon-sync-v1", env=env)
    install_files(client, initial)
    git_run(client, "add", "--all", env=env)
    git_run(client, "commit", "-m", "Identical initial sync tree", env=env)
    git_run(client, "push", str(remote), "HEAD:refs/heads/mihon-sync-v1", env=env)
    initial_tree = git_run(client, "rev-parse", "HEAD^{tree}", env=env).decode().strip()
    install_files(client, final)  # Frozen files ready: disk materialization is outside the timed Git command.
    backend = Path(git_run(work, "--exec-path", env=env).decode().strip()) / "git-http-backend.exe"
    if not backend.is_file():
        raise FileNotFoundError(backend)
    requests = []
    bytes_per_second = args.mbps * 1_000_000 / 8

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_GET(self):
            self.serve()

        def do_POST(self):
            self.serve()

        def serve(self):
            started = time.perf_counter()
            if self.headers.get("Transfer-Encoding"):
                self.send_error(400, "Benchmark uses bounded Content-Length requests")
                return
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            # Match the REST fixture: full request ingestion followed by upload-byte delay.
            if length:
                time.sleep(length / bytes_per_second)
            url = urlsplit(self.path)
            cgi = dict(env, GIT_PROJECT_ROOT=str(work), GIT_HTTP_EXPORT_ALL="1",
                       REQUEST_METHOD=self.command, PATH_INFO=url.path, QUERY_STRING=url.query,
                       CONTENT_TYPE=self.headers.get("Content-Type", ""), CONTENT_LENGTH=str(length),
                       REMOTE_ADDR="127.0.0.1", SERVER_PROTOCOL="HTTP/1.1",
                       GATEWAY_INTERFACE="CGI/1.1", HTTP_GIT_PROTOCOL=self.headers.get("Git-Protocol", ""))
            completed = subprocess.run([str(backend)], input=body, env=cgi, capture_output=True, timeout=120)
            if completed.returncode:
                self.send_error(500, "Isolated git-http-backend failed")
                return
            header, separator, content = completed.stdout.partition(b"\r\n\r\n")
            if not separator:
                header, separator, content = completed.stdout.partition(b"\n\n")
            if not separator:
                self.send_error(500, "Invalid CGI response")
                return
            headers = [line.decode("ascii").split(":", 1) for line in header.splitlines()]
            status = next((int(v.strip().split()[0]) for k, v in headers if k.lower() == "status"), 200)
            time.sleep(args.latency_ms / 1000)
            self.send_response(status)
            for key, value in headers:
                if key.lower() not in ("status", "content-length", "connection", "transfer-encoding"):
                    self.send_header(key, value.strip())
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            chunk_size = max(1, int(bytes_per_second * .01))
            for offset in range(0, len(content), chunk_size):
                if offset:
                    time.sleep(.01)
                self.wfile.write(content[offset:offset + chunk_size])
                self.wfile.flush()
            requests.append({"method": self.command, "path": url.path, "status": status,
                             "requestBytes": length, "responseBytes": len(content),
                             "elapsedMillis": round((time.perf_counter() - started) * 1000, 3)})

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    remote_url = f"http://127.0.0.1:{server.server_port}/remote.git"
    phases = {}
    started = time.perf_counter()
    try:
        for label, command in [
            ("add", ["add", "--all"]),
            ("commit", ["commit", "-m", "Publish the frozen 1600-item sync artifacts"]),
            ("push", ["-c", "http.postBuffer=67108864", "push", remote_url, "HEAD:refs/heads/mihon-sync-v1"]),
            ("confirm", ["ls-remote", remote_url, "refs/heads/mihon-sync-v1"]),
        ]:
            phase = time.perf_counter()
            output = git_run(client, *command, env=env)
            phases[label + "Millis"] = round((time.perf_counter() - phase) * 1000, 3)
        elapsed = round((time.perf_counter() - started) * 1000, 3)
        head = git_run(client, "rev-parse", "HEAD", env=env).decode().strip()
        if output.decode().split()[0] != head:
            raise AssertionError("Remote ref differs from the pushed commit")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
    remote_tree = git_run(remote, "rev-parse", "refs/heads/mihon-sync-v1^{tree}", env=env).decode().strip()
    client_tree = git_run(client, "rev-parse", "HEAD^{tree}", env=env).decode().strip()
    if remote_tree != client_tree:
        raise AssertionError("Remote tree differs")
    actual_paths = git_run(remote, "ls-tree", "-r", "--name-only", "refs/heads/mihon-sync-v1", env=env).decode().splitlines()
    if set(actual_paths) != set(final):
        raise AssertionError("Remote file set differs")
    for relative, expected in final.items():
        if git_run(remote, "show", f"refs/heads/mihon-sync-v1:{relative}", env=env) != expected:
            raise AssertionError("Remote artifact bytes differ")
    result = {"schema": 1, "transport": "native-git-smart-http-loopback", "gitVersion": git_run(work, "--version", env=env).decode().strip(),
              "latencyMillisPerResponse": args.latency_ms, "megabitsPerSecond": args.mbps,
              "elapsedMillis": elapsed, "phases": phases, "requests": requests,
              "requestBodyBytes": sum(r["requestBytes"] for r in requests),
              "responseBodyBytes": sum(r["responseBytes"] for r in requests),
              "initialTreeOid": initial_tree, "finalTreeOid": client_tree, "finalFileCount": len(final),
              "finalFileBytes": sum(map(len, final.values())), "allRemoteBytesVerified": True,
              "timing": "frozen final files installed; git add + commit + push + ls-remote; exact byte audit afterward",
              "history": "one final commit after initial tree; REST intermediate commits are not reproduced"}
    write_json(args.output, result)
    print(json.dumps(result, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    extract = actions.add_parser("sample")
    extract.add_argument("--database", type=Path, required=True)
    extract.add_argument("--output", type=Path, required=True)
    extract.add_argument("--count", type=int, default=1600)
    extract.add_argument("--seed", type=int, default=202609251600)
    native = actions.add_parser("git")
    native.add_argument("--initial", type=Path, required=True)
    native.add_argument("--final", type=Path, required=True)
    native.add_argument("--work", type=Path, required=True)
    native.add_argument("--output", type=Path, required=True)
    native.add_argument("--latency-ms", type=float, default=50)
    native.add_argument("--mbps", type=float, default=10)
    args = parser.parse_args()
    (sample if args.action == "sample" else native_git)(args)


if __name__ == "__main__":
    main()
