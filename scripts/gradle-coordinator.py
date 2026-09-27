#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
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
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else None
    except (OSError, json.JSONDecodeError):
        return None


def replace_state_file(temporary: Path, target: Path, timeout: float = 2) -> None:
    deadline = time.monotonic() + timeout
    while True:
        try:
            temporary.replace(target)
            return
        except PermissionError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.01)


def write_state(state_dir: Path, key: str, state: dict[str, object]) -> None:
    state_dir.mkdir(parents=True, exist_ok=True)
    target = state_path(state_dir, key)
    temporary = target.with_name(f"{target.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(json.dumps(state, indent=2, sort_keys=True), encoding="utf-8")
        replace_state_file(temporary, target)
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


def foreground_options() -> dict[str, object]:
    """Put the managed child in a private process group without detaching it."""
    if os.name == "nt":
        return {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
    return {"preexec_fn": os.setpgrp}


def cleanup_foreground_process(
    process: subprocess.Popen[object],
    process_identity_value: str | None,
) -> None:
    """Terminate and reap a foreground child using its original identity."""
    if process.poll() is None:
        if process_identity_value is None:
            # Popen still owns this handle, but no reusable identity was observed.
            process.terminate()
        else:
            terminate_process_tree(
                process.pid,
                process_identity_value,
                detached=False,
            )
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        # The Popen handle still refers to the child created above.  This is the
        # final escalation after the identity-checked tree termination.
        process.kill()
        process.wait(timeout=5)


def foreground_state_belongs_to(
    state: dict[str, object],
    owner_pid: int,
    owner_identity: str,
    process_pid: int,
    process_identity_value: str | None,
) -> bool:
    return (
        state.get("executionMode") == "foreground"
        and state.get("workerPid") == owner_pid
        and state.get("workerIdentity") == owner_identity
        and state.get("processPid") == process_pid
        and state.get("processIdentity") == process_identity_value
    )


def command_start(args: argparse.Namespace) -> int:
    command = list(args.command)
    if command and command[0] == "--":
        command.pop(0)
    if not command:
        print("ERROR: start requires a command after --", file=sys.stderr)
        return 2

    with state_lock(args.state_dir, "_launch"), state_lock(args.state_dir, args.key):
        conflict = active_other_key(args.state_dir, args.key)
        if conflict:
            print(f"ERROR: coordinator is busy: {conflict}", file=sys.stderr)
            return 2
        existing = load_state(args.state_dir, args.key)
        if (
            existing
            and existing.get("status") in ACTIVE
            and active_process_is_alive(existing)
        ):
            if existing.get("command") != command or existing.get("cwd") != str(Path.cwd().resolve()):
                print(f"ERROR: coordinator key is busy with a different command: {args.key}", file=sys.stderr)
                return 2
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
                "cwd": str(Path.cwd().resolve()),
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


def active_other_key(state_dir: Path, key: str) -> str | None:
    """Called under the launch lock so competing starts cannot pass together."""
    for path in state_dir.glob("*.json"):
        if path.stem == key:
            continue
        state = load_state(state_dir, path.stem)
        if state and state.get("status") in ACTIVE and active_process_is_alive(state):
            return path.stem
    return None


def command_foreground(args: argparse.Namespace) -> int:
    command = list(args.command)
    if command and command[0] == "--":
        command.pop(0)
    if not command:
        print("ERROR: foreground requires a command after --", file=sys.stderr)
        return 2

    owner_pid = os.getpid()
    owner_identity = await_process_identity(owner_pid)
    if owner_identity is None:
        print("ERROR: failed to identify foreground coordinator", file=sys.stderr)
        return 1

    attached = False
    process: subprocess.Popen[str] | None = None
    output = None
    process_identity_value: str | None = None
    quick_exit_code: int | None = None
    with state_lock(args.state_dir, "_launch"), state_lock(args.state_dir, args.key):
        conflict = active_other_key(args.state_dir, args.key)
        if conflict:
            print(f"ERROR: coordinator is busy: {conflict}", file=sys.stderr)
            return 2
        existing = load_state(args.state_dir, args.key)
        if (
            existing
            and existing.get("status") in ACTIVE
            and active_process_is_alive(existing)
        ):
            if existing.get("command") != command or existing.get("cwd") != str(Path.cwd().resolve()):
                print(
                    f"ERROR: coordinator key is busy with a different command: {args.key}",
                    file=sys.stderr,
                )
                return 2
            print(
                f"ATTACHED key={args.key} status={existing['status']} "
                f"workerPid={existing['workerPid']} processPid={existing.get('processPid')}"
            )
            attached = True
        else:
            path = log_path(args.state_dir, args.key)
            path.parent.mkdir(parents=True, exist_ok=True)
            state = {
                "status": "STARTING",
                "cwd": str(Path.cwd().resolve()),
                "command": command,
                "workerPid": owner_pid,
                "workerIdentity": owner_identity,
                "processPid": None,
                "processIdentity": None,
                "executionMode": "foreground",
                "logPath": str(path),
                "startedAt": now(),
                "exitCode": None,
            }
            write_state(args.state_dir, args.key, state)
            try:
                output = path.open("w", encoding="utf-8")
                process = subprocess.Popen(
                    command,
                    stdin=subprocess.DEVNULL,
                    stdout=output,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding="utf-8",
                    **foreground_options(),
                )
                process_identity_value = await_process_identity(process.pid)
                if process_identity_value is None and process.poll() is None:
                    cleanup_foreground_process(process, None)
                    raise RuntimeError("failed to identify managed foreground process")
                state = load_state(args.state_dir, args.key) or state
                state.update(
                    {
                        "processPid": process.pid,
                        "processIdentity": process_identity_value,
                    }
                )
                if process_identity_value is None:
                    quick_exit_code = process.wait()
                    state.update(
                        {
                            "status": "PASSED" if quick_exit_code == 0 else "FAILED",
                            "exitCode": quick_exit_code,
                            "finishedAt": now(),
                        }
                    )
                else:
                    state["status"] = "RUNNING"
                write_state(args.state_dir, args.key, state)
            except BaseException as exc:
                if process is not None and process.poll() is None:
                    try:
                        cleanup_foreground_process(process, process_identity_value)
                    except BaseException as cleanup_error:
                        exc = RuntimeError(f"{exc}; cleanup failed: {cleanup_error}")
                state = load_state(args.state_dir, args.key) or state
                state.update(
                    {
                        "status": "FAILED",
                        "exitCode": 1,
                        "finishedAt": now(),
                        "error": f"{type(exc).__name__}: {exc}",
                    }
                )
                try:
                    write_state(args.state_dir, args.key, state)
                except BaseException:
                    # Preserve the original startup failure while ensuring the
                    # managed child was not left detached.
                    pass
                if output is not None:
                    output.close()
                print(describe(state, args.key))
                return 1

    if attached:
        return command_wait(args)

    assert process is not None
    print(f"STARTED key={args.key} workerPid={owner_pid} processPid={process.pid}")
    if quick_exit_code is not None:
        if output is not None:
            output.close()
        state = load_state(args.state_dir, args.key)
        print(describe(state, args.key))
        return quick_exit_code

    timed_out = False
    interrupted = False
    wait_error: BaseException | None = None
    process_exit_code = 1
    try:
        try:
            process_exit_code = process.wait(timeout=args.timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            cleanup_foreground_process(process, process_identity_value)
            process_exit_code = process.returncode
            if process_exit_code is None:
                process_exit_code = 124
        except KeyboardInterrupt:
            interrupted = True
            cleanup_foreground_process(process, process_identity_value)
            process_exit_code = process.returncode
            if process_exit_code is None:
                process_exit_code = 130
        except BaseException as exc:
            process_exit_code = 1
            wait_error = exc
            try:
                cleanup_foreground_process(process, process_identity_value)
            except BaseException as cleanup_error:
                wait_error = RuntimeError(f"{exc}; cleanup failed: {cleanup_error}")
    finally:
        if output is not None:
            output.close()

    with state_lock(args.state_dir, args.key):
        state = load_state(args.state_dir, args.key) or {}
        owns_state = foreground_state_belongs_to(
            state,
            owner_pid,
            owner_identity,
            process.pid,
            process_identity_value,
        )
        if not owns_state:
            print(
                f"IGNORED stale foreground owner key={args.key} "
                f"workerPid={owner_pid} processPid={process.pid}; {describe(state, args.key)}"
            )
            return process_exit_code
        if state.get("status") != "CANCELLED":
            if timed_out:
                state.update(
                    {
                        "status": "CANCELLED",
                        "exitCode": 124,
                        "finishedAt": now(),
                        "error": "foreground command timed out and was terminated",
                    }
                )
            elif interrupted:
                state.update(
                    {
                        "status": "CANCELLED",
                        "exitCode": 130,
                        "finishedAt": now(),
                        "error": "foreground command interrupted and was terminated",
                    }
                )
            elif wait_error is not None:
                state.update(
                    {
                        "status": "FAILED",
                        "exitCode": 1,
                        "finishedAt": now(),
                        "error": f"{type(wait_error).__name__}: {wait_error}",
                    }
                )
            else:
                state.update(
                    {
                        "status": "PASSED" if process_exit_code == 0 else "FAILED",
                        "exitCode": process_exit_code,
                        "finishedAt": now(),
                    }
                )
            write_state(args.state_dir, args.key, state)
        final_exit_code = int(state.get("exitCode") or 0)
        print(describe(state, args.key))
    return final_exit_code


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


def terminate_process_tree(
    pid: object,
    expected_identity: object,
    detached: bool = True,
) -> None:
    if not isinstance(pid, int) or not process_matches(pid, expected_identity):
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(pid), "/T", "/F"],
            capture_output=True,
            check=False,
        )
    elif not detached:
        # foreground_options() makes the child a process-group leader while it
        # remains a direct child of this coordinator.  Verify both identity and
        # ownership before signalling the group; this cannot reach the caller's
        # group, and also works on macOS where /proc is unavailable.
        try:
            process_group = os.getpgid(pid)
        except (OSError, ProcessLookupError):
            process_group = None
        if process_group == pid and process_matches(pid, expected_identity):
            try:
                os.killpg(process_group, signal.SIGTERM)
            except (OSError, ProcessLookupError):
                pass
        else:
            # The identity-checked root remains safe to terminate, but never
            # walk unverified descendants or the caller's process group.
            try:
                os.kill(pid, signal.SIGTERM)
            except (OSError, ProcessLookupError):
                pass
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
            foreground = state.get("executionMode") == "foreground"
            process_alive = process_matches(
                state.get("processPid"),
                state.get("processIdentity"),
            )
            if foreground and not process_alive:
                # The foreground owner may be between wait() and terminal-state write.
                # Do not overwrite a fast process's eventual result with CANCELLED.
                print(describe(state, args.key))
                return 0
            terminate_process_tree(
                state.get("processPid"),
                state.get("processIdentity"),
                detached=not foreground,
            )
            if not foreground:
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

    for action in ("start", "run", "foreground", "status", "wait", "stop"):
        child = subparsers.add_parser(action)
        child.add_argument("--state-dir", type=Path, default=Path(".gradle-coordinator"))
        child.add_argument("--key", default="gradle")
        if action in ("start", "run", "foreground"):
            child.add_argument("command", nargs=argparse.REMAINDER)
        if action in ("run", "foreground", "wait"):
            child.add_argument("--timeout-seconds", type=float, default=900)

    worker = subparsers.add_parser("_worker")
    worker.add_argument("--state-dir", required=True, type=Path)
    worker.add_argument("--key", required=True)
    worker.add_argument("--command-json", required=True)
    return root


def main() -> int:
    args = parser().parse_args()
    if args.key.startswith("_") or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.key):
        print("ERROR: invalid or reserved coordinator key", file=sys.stderr)
        return 2
    actions = {
        "start": command_start,
        "run": command_run,
        "foreground": command_foreground,
        "status": command_status,
        "wait": command_wait,
        "stop": command_stop,
        "_worker": command_worker,
    }
    return actions[args.action](args)


if __name__ == "__main__":
    raise SystemExit(main())
