#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path


ACTIVE = {"STARTING", "RUNNING"}
TERMINAL = {"PASSED", "FAILED", "CANCELLED"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def state_path(state_dir: Path, key: str) -> Path:
    return state_dir / f"{key}.json"


def log_path(state_dir: Path, key: str) -> Path:
    return state_dir / f"{key}.log"


def load_state(state_dir: Path, key: str) -> dict[str, object] | None:
    path = state_path(state_dir, key)
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def write_state(state_dir: Path, key: str, state: dict[str, object]) -> None:
    state_dir.mkdir(parents=True, exist_ok=True)
    target = state_path(state_dir, key)
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2, sort_keys=True), encoding="utf-8")
    temporary.replace(target)


def pid_is_alive(pid: object) -> bool:
    if not isinstance(pid, int) or pid <= 0:
        return False
    if os.name == "nt":
        import ctypes

        process_query_limited_information = 0x1000
        still_active = 259
        handle = ctypes.windll.kernel32.OpenProcess(
            process_query_limited_information,
            False,
            pid,
        )
        if not handle:
            return False
        try:
            exit_code = ctypes.c_ulong()
            return bool(
                ctypes.windll.kernel32.GetExitCodeProcess(
                    handle,
                    ctypes.byref(exit_code),
                )
                and exit_code.value == still_active
            )
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


@contextmanager
def state_lock(state_dir: Path, key: str):
    state_dir.mkdir(parents=True, exist_ok=True)
    lock = state_dir / f"{key}.lock"
    deadline = time.monotonic() + 5
    descriptor: int | None = None
    while descriptor is None:
        try:
            descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"coordinator lock is busy: {lock}")
            time.sleep(0.02)
    try:
        os.write(descriptor, str(os.getpid()).encode())
        yield
    finally:
        os.close(descriptor)
        lock.unlink(missing_ok=True)


def detached_options() -> dict[str, object]:
    if os.name == "nt":
        return {
            "creationflags": subprocess.CREATE_NEW_PROCESS_GROUP
            | subprocess.CREATE_NO_WINDOW,
        }
    return {"start_new_session": True}


def command_start(args: argparse.Namespace) -> int:
    command = list(args.command)
    if command and command[0] == "--":
        command.pop(0)
    if not command:
        print("ERROR: start requires a command after --", file=sys.stderr)
        return 2

    with state_lock(args.state_dir, args.key):
        existing = load_state(args.state_dir, args.key)
        if (
            existing
            and existing.get("status") in ACTIVE
            and pid_is_alive(existing.get("workerPid"))
        ):
            print(
                f"ATTACHED key={args.key} status={existing['status']} "
                f"workerPid={existing['workerPid']}"
            )
            return 0

        encoded = json.dumps(command)
        worker = subprocess.Popen(
            [
                sys.executable,
                str(Path(__file__).resolve()),
                "_worker",
                "--state-dir",
                str(args.state_dir),
                "--key",
                args.key,
                "--command-json",
                encoded,
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            **detached_options(),
        )
        write_state(
            args.state_dir,
            args.key,
            {
                "status": "STARTING",
                "command": command,
                "workerPid": worker.pid,
                "processPid": None,
                "startedAt": now(),
                "exitCode": None,
            },
        )
    print(f"STARTED key={args.key} workerPid={worker.pid}")
    return 0


def command_run(args: argparse.Namespace) -> int:
    start_exit_code = command_start(args)
    if start_exit_code != 0:
        return start_exit_code
    return command_wait(args)


def command_worker(args: argparse.Namespace) -> int:
    command = json.loads(args.command_json)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        state = load_state(args.state_dir, args.key)
        if state and state.get("workerPid") == os.getpid():
            break
        time.sleep(0.01)
    else:
        return 2

    path = log_path(args.state_dir, args.key)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("w", encoding="utf-8") as output:
            process = subprocess.Popen(
                command,
                stdin=subprocess.DEVNULL,
                stdout=output,
                stderr=subprocess.STDOUT,
                text=True,
            )
            state = load_state(args.state_dir, args.key) or {}
            state.update(
                {
                    "status": "RUNNING",
                    "processPid": process.pid,
                    "workerPid": os.getpid(),
                }
            )
            write_state(args.state_dir, args.key, state)
            exit_code = process.wait()
    except BaseException as exc:
        state = load_state(args.state_dir, args.key) or {}
        state.update(
            {
                "status": "FAILED",
                "exitCode": 1,
                "finishedAt": now(),
                "error": f"{type(exc).__name__}: {exc}",
            }
        )
        write_state(args.state_dir, args.key, state)
        return 1

    state = load_state(args.state_dir, args.key) or {}
    if state.get("status") != "CANCELLED":
        state.update(
            {
                "status": "PASSED" if exit_code == 0 else "FAILED",
                "exitCode": exit_code,
                "finishedAt": now(),
            }
        )
        write_state(args.state_dir, args.key, state)
    return exit_code


def describe(state: dict[str, object] | None, key: str) -> str:
    if state is None:
        return f"NOT_STARTED key={key}"
    return (
        f"{state.get('status')} key={key} workerPid={state.get('workerPid')} "
        f"processPid={state.get('processPid')} exitCode={state.get('exitCode')}"
    )


def command_status(args: argparse.Namespace) -> int:
    state = load_state(args.state_dir, args.key)
    print(describe(state, args.key))
    return 0 if state else 3


def command_wait(args: argparse.Namespace) -> int:
    deadline = time.monotonic() + args.timeout_seconds
    while True:
        state = load_state(args.state_dir, args.key)
        if state is None:
            print(describe(state, args.key))
            return 3
        status = state.get("status")
        if status in TERMINAL:
            print(describe(state, args.key))
            return int(state.get("exitCode") or 0)
        if time.monotonic() >= deadline:
            print(describe(state, args.key))
            return 124
        time.sleep(0.05)


def terminate_process_tree(pid: object) -> None:
    if not isinstance(pid, int) or not pid_is_alive(pid):
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(pid), "/T", "/F"],
            capture_output=True,
            check=False,
        )
    else:
        try:
            os.killpg(os.getpgid(pid), signal.SIGTERM)
        except (OSError, ProcessLookupError):
            os.kill(pid, signal.SIGTERM)


def command_stop(args: argparse.Namespace) -> int:
    with state_lock(args.state_dir, args.key):
        state = load_state(args.state_dir, args.key)
        if state is None:
            print(describe(state, args.key))
            return 0
        if state.get("status") in ACTIVE:
            terminate_process_tree(state.get("processPid"))
            terminate_process_tree(state.get("workerPid"))
            state.update(
                {
                    "status": "CANCELLED",
                    "exitCode": 130,
                    "finishedAt": now(),
                }
            )
            write_state(args.state_dir, args.key, state)
        print(describe(state, args.key))
    return 0


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    subparsers = root.add_subparsers(dest="action", required=True)

    for action in ("start", "run", "status", "wait", "stop"):
        child = subparsers.add_parser(action)
        child.add_argument("--state-dir", type=Path, default=Path(".gradle-coordinator"))
        child.add_argument("--key", default="gradle")
        if action in ("start", "run"):
            child.add_argument("command", nargs=argparse.REMAINDER)
        if action in ("run", "wait"):
            child.add_argument("--timeout-seconds", type=float, default=900)

    worker = subparsers.add_parser("_worker")
    worker.add_argument("--state-dir", required=True, type=Path)
    worker.add_argument("--key", required=True)
    worker.add_argument("--command-json", required=True)
    return root


def main() -> int:
    args = parser().parse_args()
    actions = {
        "start": command_start,
        "run": command_run,
        "status": command_status,
        "wait": command_wait,
        "stop": command_stop,
        "_worker": command_worker,
    }
    return actions[args.action](args)


if __name__ == "__main__":
    raise SystemExit(main())
