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
TERMINAL = {"PASSED", "FAILED", "CANCELLED", "ORPHANED"}


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
    temporary = target.with_name(f"{target.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(json.dumps(state, indent=2, sort_keys=True), encoding="utf-8")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def darwin_process_identity(pid: int) -> str | None:
    import ctypes

    class ProcBsdInfo(ctypes.Structure):
        _fields_ = [
            ("pbi_flags", ctypes.c_uint32),
            ("pbi_status", ctypes.c_uint32),
            ("pbi_xstatus", ctypes.c_uint32),
            ("pbi_pid", ctypes.c_uint32),
            ("pbi_ppid", ctypes.c_uint32),
            ("pbi_uid", ctypes.c_uint32),
            ("pbi_gid", ctypes.c_uint32),
            ("pbi_ruid", ctypes.c_uint32),
            ("pbi_rgid", ctypes.c_uint32),
            ("pbi_svuid", ctypes.c_uint32),
            ("pbi_svgid", ctypes.c_uint32),
            ("rfu_1", ctypes.c_uint32),
            ("pbi_comm", ctypes.c_char * 16),
            ("pbi_name", ctypes.c_char * 32),
            ("pbi_nfiles", ctypes.c_uint32),
            ("pbi_pgid", ctypes.c_uint32),
            ("pbi_pjobc", ctypes.c_uint32),
            ("e_tdev", ctypes.c_uint32),
            ("e_tpgid", ctypes.c_uint32),
            ("pbi_nice", ctypes.c_int32),
            ("pbi_start_tvsec", ctypes.c_uint64),
            ("pbi_start_tvusec", ctypes.c_uint64),
        ]

    try:
        libproc = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
        proc_pidinfo = libproc.proc_pidinfo
        proc_pidinfo.argtypes = [
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_uint64,
            ctypes.c_void_p,
            ctypes.c_int,
        ]
        proc_pidinfo.restype = ctypes.c_int
        info = ProcBsdInfo()
        proc_pid_tbsd_info = 3
        result = proc_pidinfo(
            pid,
            proc_pid_tbsd_info,
            0,
            ctypes.byref(info),
            ctypes.sizeof(info),
        )
    except (AttributeError, OSError):
        return None
    if result != ctypes.sizeof(info) or info.pbi_pid != pid:
        return None
    return f"darwin:{info.pbi_start_tvsec}:{info.pbi_start_tvusec}"


def process_identity(pid: object) -> str | None:
    if not isinstance(pid, int) or pid <= 0:
        return None
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
            return None
        try:
            exit_code = ctypes.c_ulong()
            if not ctypes.windll.kernel32.GetExitCodeProcess(
                handle,
                ctypes.byref(exit_code),
            ) or exit_code.value != still_active:
                return None

            class FileTime(ctypes.Structure):
                _fields_ = [
                    ("low", ctypes.c_ulong),
                    ("high", ctypes.c_ulong),
                ]

            created = FileTime()
            exited = FileTime()
            kernel = FileTime()
            user = FileTime()
            if not ctypes.windll.kernel32.GetProcessTimes(
                handle,
                ctypes.byref(created),
                ctypes.byref(exited),
                ctypes.byref(kernel),
                ctypes.byref(user),
            ):
                return None
            created_ticks = (created.high << 32) | created.low
            return f"windows:{created_ticks}"
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
    except OSError:
        return None

    if sys.platform == "darwin":
        return darwin_process_identity(pid)

    proc_stat = Path(f"/proc/{pid}/stat")
    if proc_stat.exists():
        try:
            fields_after_command = proc_stat.read_text(encoding="utf-8").rsplit(")", 1)[1].split()
            start_ticks = fields_after_command[19]
            boot_id_path = Path("/proc/sys/kernel/random/boot_id")
            boot_id = boot_id_path.read_text(encoding="utf-8").strip() if boot_id_path.exists() else "unknown"
            return f"proc:{boot_id}:{start_ticks}"
        except (OSError, IndexError):
            return None

    return None


def process_matches(pid: object, expected_identity: object) -> bool:
    return isinstance(expected_identity, str) and bool(expected_identity) and process_identity(pid) == expected_identity


def await_process_identity(pid: int, timeout: float = 0.5) -> str | None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        identity = process_identity(pid)
        if identity is not None:
            return identity
        time.sleep(0.01)
    return None


def active_process_is_alive(state: dict[str, object]) -> bool:
    return process_matches(
        state.get("workerPid"),
        state.get("workerIdentity"),
    ) or process_matches(
        state.get("processPid"),
        state.get("processIdentity"),
    )


def try_lock_descriptor(descriptor: int) -> bool:
    if os.name == "nt":
        import msvcrt

        os.lseek(descriptor, 0, os.SEEK_SET)
        try:
            msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
            return True
        except OSError:
            return False

    import fcntl

    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except BlockingIOError:
        return False


def unlock_descriptor(descriptor: int) -> None:
    if os.name == "nt":
        import msvcrt

        os.lseek(descriptor, 0, os.SEEK_SET)
        msvcrt.locking(descriptor, msvcrt.LK_UNLCK, 1)
        return

    import fcntl

    fcntl.flock(descriptor, fcntl.LOCK_UN)


@contextmanager
def state_lock(state_dir: Path, key: str):
    state_dir.mkdir(parents=True, exist_ok=True)
    lock = state_dir / f"{key}.lock"
    deadline = time.monotonic() + 5
    descriptor = os.open(lock, os.O_CREAT | os.O_RDWR, 0o600)
    acquired = False
    try:
        while not acquired:
            acquired = try_lock_descriptor(descriptor)
            if not acquired:
                if time.monotonic() >= deadline:
                    raise TimeoutError(f"coordinator lock is busy: {lock}")
                time.sleep(0.02)
        os.lseek(descriptor, 0, os.SEEK_SET)
        os.ftruncate(descriptor, 0)
        os.write(
            descriptor,
            json.dumps(
                {
                    "pid": os.getpid(),
                    "identity": process_identity(os.getpid()),
                },
            ).encode(),
        )
        yield
    finally:
        if acquired:
            unlock_descriptor(descriptor)
        os.close(descriptor)


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
            and active_process_is_alive(existing)
        ):
            print(
                f"ATTACHED key={args.key} status={existing['status']} "
                f"workerPid={existing['workerPid']} processPid={existing.get('processPid')}"
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
        worker_identity = await_process_identity(worker.pid)
        if worker_identity is None:
            worker.terminate()
            print("ERROR: failed to identify coordinator worker", file=sys.stderr)
            return 1
        write_state(
            args.state_dir,
            args.key,
            {
                "status": "STARTING",
                "command": command,
                "workerPid": worker.pid,
                "workerIdentity": worker_identity,
                "processPid": None,
                "processIdentity": None,
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
    worker_identity = process_identity(os.getpid())
    while time.monotonic() < deadline:
        state = load_state(args.state_dir, args.key)
        if (
            state
            and state.get("workerPid") == os.getpid()
            and state.get("workerIdentity") == worker_identity
        ):
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
            process_identity_value = await_process_identity(process.pid)
            if process_identity_value is None:
                process.terminate()
                raise RuntimeError("failed to identify managed process")
            state = load_state(args.state_dir, args.key) or {}
            state.update(
                {
                    "status": "RUNNING",
                    "processPid": process.pid,
                    "processIdentity": process_identity_value,
                    "workerPid": os.getpid(),
                    "workerIdentity": worker_identity,
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


def reconcile_stale_active(
    state_dir: Path,
    key: str,
    state: dict[str, object] | None,
) -> dict[str, object] | None:
    if state is None or state.get("status") not in ACTIVE or active_process_is_alive(state):
        return state
    with state_lock(state_dir, key):
        current = load_state(state_dir, key)
        if current is None or current.get("status") not in ACTIVE or active_process_is_alive(current):
            return current
        current.update(
            {
                "status": "ORPHANED",
                "exitCode": 125,
                "finishedAt": now(),
                "error": "worker and managed process exited without recording a terminal state",
            },
        )
        write_state(state_dir, key, current)
        return current


def command_status(args: argparse.Namespace) -> int:
    state = reconcile_stale_active(
        args.state_dir,
        args.key,
        load_state(args.state_dir, args.key),
    )
    print(describe(state, args.key))
    return 0 if state else 3


def command_wait(args: argparse.Namespace) -> int:
    deadline = time.monotonic() + args.timeout_seconds
    while True:
        state = reconcile_stale_active(
            args.state_dir,
            args.key,
            load_state(args.state_dir, args.key),
        )
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


def terminate_process_tree(pid: object, expected_identity: object) -> None:
    if not isinstance(pid, int) or not process_matches(pid, expected_identity):
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
            terminate_process_tree(state.get("processPid"), state.get("processIdentity"))
            terminate_process_tree(state.get("workerPid"), state.get("workerIdentity"))
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
