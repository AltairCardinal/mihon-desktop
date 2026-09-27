"""Bounded local SQLite durability probe; synthetic rows only, not an application benchmark."""

import json
import sqlite3
import statistics
import tempfile
import time
from pathlib import Path


def run(mode: str, ordinal: int) -> dict:
    with tempfile.TemporaryDirectory(prefix="sync-sqlite-probe-") as directory:
        path = Path(directory) / "probe.db"
        connection = sqlite3.connect(path)
        actual_mode = connection.execute(f"PRAGMA journal_mode={mode}").fetchone()[0]
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("CREATE TABLE events(id INTEGER PRIMARY KEY, body BLOB NOT NULL)")
        connection.commit()
        body = b"x" * 256
        sql_times = []
        commit_times = []
        started = time.perf_counter_ns()
        for _ in range(80):
            connection.execute("BEGIN IMMEDIATE")
            before_sql = time.perf_counter_ns()
            connection.executemany("INSERT INTO events(body) VALUES (?)", [(body,)] * 50)
            before_commit = time.perf_counter_ns()
            connection.commit()
            done = time.perf_counter_ns()
            sql_times.append((before_commit - before_sql) / 1_000_000)
            commit_times.append((done - before_commit) / 1_000_000)
        wall_ms = (time.perf_counter_ns() - started) / 1_000_000
        rows = connection.execute("SELECT count(*) FROM events").fetchone()[0]
        connection.close()
        return {
            "mode": actual_mode,
            "ordinal": ordinal,
            "synchronous": "FULL",
            "transactions": 80,
            "rowsPerTransaction": 50,
            "rows": rows,
            "wallMillis": round(wall_ms, 3),
            "commitTotalMillis": round(sum(commit_times), 3),
            "commitMedianMillis": round(statistics.median(commit_times), 3),
            "commitP95Millis": round(sorted(commit_times)[75], 3),
            "sqlTotalMillis": round(sum(sql_times), 3),
        }


if __name__ == "__main__":
    samples = [run(mode, ordinal) for ordinal, mode in enumerate(("DELETE", "WAL", "WAL", "DELETE"), 1)]
    target = Path(__file__).with_name("sqlite-commit-probe.json")
    target.write_text(json.dumps({"sqliteVersion": sqlite3.sqlite_version, "samples": samples}, indent=2), encoding="utf-8")
    for item in samples:
        print(
            item["ordinal"], item["mode"],
            "wallMs", item["wallMillis"],
            "commitTotalMs", item["commitTotalMillis"],
            "commitMedianMs", item["commitMedianMillis"],
        )
