#!/usr/bin/env python3
"""Adaptive Gradle worker scheduling for Mihon builds.

The coordinator never overrides an explicit user flag and never injects heap,
fork, or daemon options. It only chooses ``--max-workers`` and ``--parallel``
when the caller left them unset, and it does so from live machine resources plus
a bounded, secret-free history of previous runs of the same build family.

Subcommands: ``run`` (wrap a build), ``plan`` (print the decision only),
``profile`` (delegate to an externally installed gradle-profiler), ``history``.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

GIB = 1024 ** 3
# Leave room for the Gradle daemon heap, the Kotlin daemon, and the OS page cache
# before worker count is allowed to grow.
MEMORY_HEADROOM_BYTES = 2 * GIB
MEMORY_PER_WORKER_BYTES = 1 * GIB
WORKER_LADDER = (1, 2, 4, 8, 16, 32, 64, 128)
REGRESSION_RATIO = 1.25
HISTORY_LIMIT = 200
HISTORY_DIRECTORY = Path(".gradle-coordinator") / "performance"
RECEIPT_TOLERANCE_MS = 2000
BUSY_SAMPLE_SECONDS = 0.1
EXTERNAL_BUSY_FRACTION = 0.35
MEMORY_COLLAPSE_FRACTION = 0.4
PROFILER_ENV = "MIHON_GRADLE_PROFILER"
WINDOWS_EPOCH_OFFSET_MS = 11644473600000


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def state_directory(root: Path) -> Path:
    return Path(root) / HISTORY_DIRECTORY


def history_path(root: Path) -> Path:
    return state_directory(root) / "history.jsonl"


def profile_directory(root: Path) -> Path:
    return state_directory(root) / "profile"


# ---------------------------------------------------------------- policy ----


def resource_ceiling(snapshot, *, memory_per_worker_bytes: int = MEMORY_PER_WORKER_BYTES):
    """Largest worker count the machine can sustain right now, or None if unknown."""
    if not isinstance(snapshot, dict) or not snapshot.get("valid"):
        return None
    cpu_count = snapshot.get("cpu_count")
    available = snapshot.get("available_bytes")
    if not isinstance(cpu_count, int) or isinstance(cpu_count, bool) or cpu_count < 1:
        return None
    if not isinstance(available, int) or isinstance(available, bool):
        return None
    per_worker = max(1, int(memory_per_worker_bytes))
    memory_bound = max(1, (available - MEMORY_HEADROOM_BYTES) // per_worker)
    return max(1, min(cpu_count, memory_bound))


def ladder_rungs(ceiling):
    """Power-of-two rungs that fit inside the ceiling; never empty."""
    if not ceiling or ceiling < 1:
        return [1]
    rungs = [rung for rung in WORKER_LADDER if rung <= ceiling]
    return rungs or [1]


def next_rung(workers, rungs):
    for rung in rungs:
        if rung > workers:
            return rung
    return rungs[-1]


def step_down(workers, rungs):
    lowered = [rung for rung in rungs if rung < workers]
    return lowered[-1] if lowered else rungs[0]


def _is_measurement(row) -> bool:
    if not isinstance(row, dict):
        return False
    elapsed = row.get("elapsed_seconds")
    return isinstance(elapsed, (int, float)) and not isinstance(elapsed, bool) and elapsed > 0


def _in_key(row, family, toolchain, cache) -> bool:
    return (row.get("family") == family and row.get("toolchain") == toolchain
            and row.get("cache") == cache)


def choose_workers(snapshot, history, family, toolchain, cache, *,
                   memory_per_worker_bytes: int = MEMORY_PER_WORKER_BYTES) -> dict:
    """Pick the worker count for the next build of one family/toolchain/cache key."""
    ceiling = resource_ceiling(snapshot, memory_per_worker_bytes=memory_per_worker_bytes)
    if ceiling is None:
        return {"workers": 1, "ceiling": None, "baseline_seconds": None,
                "reason": "snapshot-unusable", "memory_per_worker_bytes": memory_per_worker_bytes}
    rungs = ladder_rungs(ceiling)
    decision = {"ceiling": ceiling, "baseline_seconds": None, "rungs": rungs,
                "memory_per_worker_bytes": memory_per_worker_bytes}

    rows = [row for row in history if isinstance(row, dict) and _in_key(row, family, toolchain, cache)]
    if not rows:
        return {**decision, "workers": rungs[-1], "reason": "cold-start-resource-derived"}

    latest = rows[-1]
    if latest.get("pressure") is True:
        return {**decision, "workers": step_down(latest.get("workers", rungs[-1]), rungs),
                "reason": "demote-external-pressure"}
    if latest.get("eligible") is not True:
        return {**decision, "workers": step_down(latest.get("workers", rungs[-1]), rungs),
                "reason": "demote-ineligible-run"}

    clean = [row for row in rows if row.get("eligible") is True
             and row.get("pressure") is not True and _is_measurement(row)]
    if not clean:
        return {**decision, "workers": rungs[-1], "reason": "no-usable-history"}
    if clean[-1] is not latest:
        return {**decision, "workers": step_down(latest.get("workers", rungs[-1]), rungs),
                "reason": "demote-latest-not-measurable"}

    prior = clean[:-1]
    baseline = min((row["elapsed_seconds"] for row in prior), default=None)
    decision["baseline_seconds"] = baseline
    if baseline is not None and latest["elapsed_seconds"] > baseline * REGRESSION_RATIO:
        return {**decision, "workers": step_down(latest["workers"], rungs), "reason": "demote-regression"}
    if latest.get("dropped") is True:
        return {**decision, "workers": max(1, min(latest["workers"], ceiling)), "reason": "hold-after-drop"}
    return {**decision, "workers": next_rung(latest["workers"], rungs), "reason": "additive-increase"}


# -------------------------------------------------------------- commands ----


def _gradle_index(argv):
    for index, token in enumerate(argv):
        base = os.path.basename(token).lower()
        if base.startswith("gradle"):
            return index
    return None


def _explicit_settings(argv):
    """Resolve user-supplied worker/parallel settings; the last explicit form wins."""
    workers = None
    parallel = None
    for index, token in enumerate(argv):
        following = argv[index + 1] if index + 1 < len(argv) else None
        if token == "--max-workers" and following is not None:
            workers = following
        elif token.startswith("--max-workers="):
            workers = token.split("=", 1)[1]
        elif token.startswith("-Dorg.gradle.workers.max="):
            workers = token.split("=", 1)[1]
        elif token == "--parallel":
            parallel = True
        elif token == "--no-parallel":
            parallel = False
        elif token.startswith("-Dorg.gradle.parallel="):
            parallel = token.split("=", 1)[1].strip().lower() == "true"
    try:
        workers = int(workers) if workers is not None else None
    except (TypeError, ValueError):
        workers = None
    if workers is not None and workers < 1:
        workers = None
    return workers, parallel


def prepare_command(argv, snapshot, history, family, toolchain, *, cache="cold",
                    memory_per_worker_bytes=MEMORY_PER_WORKER_BYTES):
    """Resolve the effective Gradle flags, leaving explicit user input untouched."""
    argv = list(argv)
    user_workers, user_parallel = _explicit_settings(argv)
    adaptive = choose_workers(snapshot, history, family, toolchain, cache,
                              memory_per_worker_bytes=memory_per_worker_bytes)
    workers = user_workers if user_workers is not None else adaptive["workers"]
    parallel = user_parallel if user_parallel is not None else True

    command = list(argv)
    injected = False
    index = _gradle_index(command)
    if index is not None:
        additions = []
        if user_workers is None:
            additions += ["--max-workers", str(workers)]
        if user_parallel is None:
            additions += ["--parallel"] if parallel else ["--no-parallel"]
        if additions:
            command[index + 1:index + 1] = additions
            injected = True

    decision = {"workers": workers, "parallel": parallel, "injected": injected,
                "source": "user" if user_workers is not None else "adaptive",
                "gradle": index is not None, "reason": adaptive["reason"],
                "ceiling": adaptive["ceiling"], "baseline_seconds": adaptive["baseline_seconds"]}
    return command, decision


# ---------------------------------------------------------------- history ----


def load_history(root: Path):
    path = history_path(root)
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            rows.append(value)
    return rows


def append_history(path, record, limit: int = HISTORY_LIMIT) -> None:
    """Store one secret-free run record and keep the newest ``limit`` rows."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    if path.exists():
        lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    lines.append(json.dumps(record, sort_keys=True))
    if limit and len(lines) > limit:
        lines = lines[-limit:]
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def run_record(*, family, toolchain, cache, decision, elapsed, queue, exit_code, memory, pressure):
    """Only non-sensitive aggregates are persisted; the build command never is."""
    return {"at": utc_now(), "family": family, "toolchain": toolchain, "cache": cache,
            "workers": decision["workers"], "parallel": decision["parallel"],
            "dropped": str(decision["reason"]).startswith("demote"),
            "elapsed_seconds": round(float(elapsed), 3), "queue_seconds": round(float(queue), 3),
            "exit_code": exit_code, "eligible": exit_code == 0, "pressure": bool(pressure),
            "memory": memory}


# ------------------------------------------------------- platform sampling ---


_WINDOWS_LIBRARIES = {}


def _win_library(name):
    """A cached WinDLL with declared signatures; ctypes defaults truncate 64-bit handles."""
    import ctypes
    from ctypes import wintypes

    if name in _WINDOWS_LIBRARIES:
        return _WINDOWS_LIBRARIES[name]
    signatures = {
        "kernel32": {
            "GetSystemTimes": (wintypes.BOOL, None),
            "GlobalMemoryStatusEx": (wintypes.BOOL, None),
            "OpenProcess": (wintypes.HANDLE, (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)),
            "CloseHandle": (wintypes.BOOL, (wintypes.HANDLE,)),
            "GetProcessTimes": (wintypes.BOOL, None),
            "CreateToolhelp32Snapshot": (wintypes.HANDLE, (wintypes.DWORD, wintypes.DWORD)),
            "Process32FirstW": (wintypes.BOOL, None),
            "Process32NextW": (wintypes.BOOL, None),
        },
        "psapi": {"GetProcessMemoryInfo": (wintypes.BOOL, None)},
    }
    library = ctypes.WinDLL(name, use_last_error=True)
    for function, (restype, argtypes) in signatures.get(name, {}).items():
        try:
            symbol = getattr(library, function)
        except AttributeError:
            continue
        symbol.restype = restype
        if argtypes is not None:
            symbol.argtypes = argtypes
    _WINDOWS_LIBRARIES[name] = library
    return library


def _windows_memory():
    import ctypes
    from ctypes import wintypes

    class MemoryStatusEx(ctypes.Structure):
        _fields_ = [
            ("dwLength", wintypes.DWORD), ("dwMemoryLoad", wintypes.DWORD),
            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
            ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
        ]

    status = MemoryStatusEx()
    status.dwLength = ctypes.sizeof(MemoryStatusEx)
    if not _win_library("kernel32").GlobalMemoryStatusEx(ctypes.byref(status)):
        raise OSError(ctypes.get_last_error(), "GlobalMemoryStatusEx failed")
    # ullAvailPhys is the *available* figure: free pages plus reclaimable cache.
    # Reading a free-page count here would misreport a memory-pressured machine.
    return int(status.ullAvailPhys), int(status.ullTotalPhys), "GlobalMemoryStatusEx.ullAvailPhys"


def _posix_memory():
    try:
        with open("/proc/meminfo", encoding="utf-8") as handle:
            fields = {}
            for line in handle:
                key, _, rest = line.partition(":")
                parts = rest.split()
                if parts and parts[0].isdigit():
                    fields[key] = int(parts[0]) * 1024
        if "MemAvailable" in fields and "MemTotal" in fields:
            return fields["MemAvailable"], fields["MemTotal"], "/proc/meminfo:MemAvailable"
    except OSError:
        pass
    try:
        pages = os.sysconf("SC_AVPHYS_PAGES")
        page_size = os.sysconf("SC_PAGE_SIZE")
        total = os.sysconf("SC_PHYS_PAGES")
        if pages > 0 and page_size > 0 and total > 0:
            return pages * page_size, total * page_size, "sysconf:SC_AVPHYS_PAGES"
    except (ValueError, OSError, AttributeError):
        pass
    raise OSError("no supported physical memory probe on this platform")


def available_memory():
    if sys.platform == "win32":
        available, _, source = _windows_memory()
        return available, source
    available, _, source = _posix_memory()
    return available, source


def _windows_system_times():
    import ctypes
    from ctypes import wintypes

    filetime = wintypes.FILETIME()
    idle, kernel, user = filetime, filetime, filetime
    _win_library("kernel32").GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user))

    def ticks(value):
        return (value.dwHighDateTime << 32) | value.dwLowDateTime

    return ticks(idle), ticks(kernel), ticks(user)


def _posix_cpu_line():
    try:
        with open("/proc/stat", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("cpu "):
                    return [int(value) for value in line.split()[1:]]
    except OSError:
        pass
    return None


def cpu_busy_fraction(interval: float = BUSY_SAMPLE_SECONDS):
    """Fraction of total machine capacity currently doing work, 0.0 to 1.0."""
    if sys.platform == "win32":
        first = _windows_system_times()
        time.sleep(interval)
        second = _windows_system_times()
        idle = (second[0] - first[0]) or 1
        busy = ((second[1] - first[1]) - idle) + (second[2] - first[2])
        total = (second[1] - first[1]) + (second[2] - first[2])
        return max(0.0, min(1.0, busy / total)) if total > 0 else 0.0
    first = _posix_cpu_line()
    if first is None:
        return 0.0
    time.sleep(interval)
    second = _posix_cpu_line()
    if second is None or len(second) < 4 or len(first) < 4:
        return 0.0
    idle = (second[3] - first[3]) + ((second[4] - first[4]) if len(second) > 4 and len(first) > 4 else 0)
    total = sum(second) - sum(first)
    return max(0.0, min(1.0, (total - idle) / total)) if total > 0 else 0.0


def system_snapshot():
    try:
        if sys.platform == "win32":
            available, total, source = _windows_memory()
        else:
            available, total, source = _posix_memory()
        cpu_count = os.cpu_count() or 0
        if cpu_count < 1 or total < 1:
            raise OSError("cpu or memory probe returned nothing usable")
        return {"valid": True, "cpu_count": cpu_count, "available_bytes": available,
                "total_bytes": total, "cpu_busy_pct": round(cpu_busy_fraction() * 100.0, 1),
                "memory_source": source, "platform": sys.platform, "at": utc_now()}
    except (OSError, ValueError, ImportError):
        return {"valid": False, "cpu_count": 0, "available_bytes": 0, "total_bytes": 0,
                "cpu_busy_pct": 0.0, "memory_source": "unavailable", "platform": sys.platform,
                "at": utc_now()}


def process_started_ms(pid):
    """Process identity as a start timestamp, so a recycled PID cannot be mistaken for it."""
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return None
    if pid <= 0:
        return None
    if sys.platform == "win32":
        return _windows_process_started_ms(pid)
    return _proc_start_ms(pid)


def _windows_process_started_ms(pid):
    import ctypes
    from ctypes import wintypes

    handle = _win_library("kernel32").OpenProcess(0x1000, False, pid)
    if not handle:
        return None
    try:
        creation, exit_time, kernel, user = (wintypes.FILETIME() for _ in range(4))
        if not _win_library("kernel32").GetProcessTimes(
                handle, ctypes.byref(creation), ctypes.byref(exit_time),
                ctypes.byref(kernel), ctypes.byref(user)):
            return None
        ticks = (creation.dwHighDateTime << 32) | creation.dwLowDateTime
        return int(ticks / 10000 - WINDOWS_EPOCH_OFFSET_MS)
    finally:
        _win_library("kernel32").CloseHandle(handle)


def _proc_start_ms(pid):
    try:
        with open(f"/proc/{pid}/stat", encoding="utf-8") as handle:
            fields = handle.read().rsplit(") ", 1)[-1].split()
        ticks = int(fields[19])
        with open("/proc/stat", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("btime "):
                    boot = int(line.split()[1])
                    return boot * 1000 + ticks * 1000 // os.sysconf("SC_CLK_TCK")
    except (OSError, IndexError, ValueError):
        return None
    return None


def process_usage(pid):
    """Return ``(working_set_bytes, cpu_seconds)`` for a live process."""
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return (0, 0.0)
    if pid <= 0:
        return (0, 0.0)
    if sys.platform == "win32":
        return _windows_process_usage(pid)
    return _proc_usage(pid)


def _windows_process_usage(pid):
    import ctypes
    from ctypes import wintypes

    class ProcessMemoryCounters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                    ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                    ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

    kernel32 = _win_library("kernel32")
    handle = kernel32.OpenProcess(0x1000, False, pid)
    if not handle:
        return (0, 0.0)
    try:
        counters = ProcessMemoryCounters()
        counters.cb = ctypes.sizeof(ProcessMemoryCounters)
        working_set = 0
        if _win_library("psapi").GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
            working_set = int(counters.WorkingSetSize)
        creation, exit_time, kernel, user = (wintypes.FILETIME() for _ in range(4))
        cpu_seconds = 0.0
        if kernel32.GetProcessTimes(handle, ctypes.byref(creation), ctypes.byref(exit_time),
                                    ctypes.byref(kernel), ctypes.byref(user)):
            kernel_ticks = (kernel.dwHighDateTime << 32) | kernel.dwLowDateTime
            user_ticks = (user.dwHighDateTime << 32) | user.dwLowDateTime
            cpu_seconds = (kernel_ticks + user_ticks) / 10000000.0
        return (working_set, cpu_seconds)
    finally:
        kernel32.CloseHandle(handle)


def _proc_usage(pid):
    working_set = 0
    try:
        with open(f"/proc/{pid}/statm", encoding="utf-8") as handle:
            working_set = int(handle.read().split()[1]) * os.sysconf("SC_PAGE_SIZE")
    except (OSError, IndexError, ValueError):
        pass
    cpu_seconds = 0.0
    try:
        with open(f"/proc/{pid}/stat", encoding="utf-8") as handle:
            fields = handle.read().rsplit(") ", 1)[-1].split()
        cpu_seconds = (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")
    except (OSError, IndexError, ValueError):
        pass
    return (working_set, cpu_seconds)


def process_descendants(pid):
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return []
    children = _windows_children_map() if sys.platform == "win32" else _proc_children_map()
    if not children:
        return []
    found, frontier = set(), [pid]
    while frontier:
        current = frontier.pop()
        for child in children.get(current, ()):
            if child not in found and child != pid:
                found.add(child)
                frontier.append(child)
    return sorted(found)


def _windows_children_map():
    """Invert the Toolhelp parent table so lookups go parent -> children.

    ``th32ModuleID`` is a DWORD and ``pcPriClassBase`` a pointer in the Windows
    header. Declaring either with the wrong width shifts ``th32ParentProcessID``
    and silently produces a process tree with a handful of bogus parents. The
    API also insists on a fixed 568-byte record while the ctypes buffer packs to
    576, so dwSize is set explicitly and the buffer is left oversized.
    """
    import ctypes
    from ctypes import wintypes

    class ProcessEntry32W(ctypes.Structure):
        _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
                    ("th32ProcessID", wintypes.DWORD), ("th32DefaultHeapID", ctypes.c_size_t),
                    ("th32ModuleID", wintypes.DWORD), ("cntThreads", wintypes.DWORD),
                    ("th32ParentProcessID", wintypes.DWORD), ("pcPriClassBase", ctypes.c_size_t),
                    ("dwFlags", wintypes.DWORD), ("szExeFile", wintypes.WCHAR * 260)]

    record_size = 568 if ctypes.sizeof(ctypes.c_void_p) == 8 else 556
    TH32CS_SNAPPROCESS = 0x00000002
    kernel32 = _win_library("kernel32")
    snapshot = kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
    if not snapshot:
        return {}
    children = {}
    try:
        entry = ProcessEntry32W()
        entry.dwSize = record_size
        if not kernel32.Process32FirstW(snapshot, ctypes.byref(entry)):
            return {}
        while True:
            children.setdefault(entry.th32ParentProcessID, []).append(entry.th32ProcessID)
            entry = ProcessEntry32W()
            entry.dwSize = record_size
            if not kernel32.Process32NextW(snapshot, ctypes.byref(entry)):
                break
    finally:
        kernel32.CloseHandle(snapshot)
    return children


def _proc_children_map():
    children = {}
    try:
        entries = os.listdir("/proc")
    except OSError:
        return children
    for entry in entries:
        if not entry.isdigit():
            continue
        try:
            with open(f"/proc/{entry}/stat", encoding="utf-8") as handle:
                fields = handle.read().rsplit(") ", 1)[-1].split()
            children.setdefault(int(fields[1]), []).append(int(entry))
        except (OSError, IndexError, ValueError):
            continue
    return children


def find_borrowed_daemon():
    """Locate a Gradle daemon that already existed, so its memory can be attributed."""
    if sys.platform == "win32":
        powershell = shutil.which("powershell") or shutil.which("pwsh")
        if not powershell:
            return None
        try:
            completed = subprocess.run(
                [powershell, "-NoProfile", "-Command",
                 "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                 "Where-Object { $_.CommandLine -like '*GradleDaemon*' } | "
                 "Select-Object -First 1 -ExpandProperty ProcessId)"],
                capture_output=True, text=True, timeout=60)
            pid = int(completed.stdout.strip().splitlines()[0])
        except (subprocess.SubprocessError, OSError, ValueError, IndexError):
            return None
    else:
        pid = None
        for entry in os.listdir("/proc") if os.path.isdir("/proc") else []:
            if not entry.isdigit():
                continue
            try:
                with open(f"/proc/{entry}/cmdline", "rb") as handle:
                    cmdline = handle.read().replace(b"\0", b" ").decode("utf-8", "replace")
            except OSError:
                continue
            if "GradleDaemon" in cmdline:
                pid = int(entry)
                break
        if pid is None:
            return None
    started = process_started_ms(pid)
    if started is None:
        return None
    return {"pid": pid, "start_time_ms": started}


def validate_runtime_receipt(receipt):
    """Accept a receipt only when the live process identity still matches it."""
    if not isinstance(receipt, dict):
        return None
    pid = receipt.get("pid")
    started = receipt.get("start_time_ms")
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        return None
    if not isinstance(started, (int, float)) or isinstance(started, bool):
        return None
    live = process_started_ms(pid)
    if live is None or abs(live - started) > RECEIPT_TOLERANCE_MS:
        return None
    return dict(receipt)


def sample_build_processes(pid, borrowed):
    """Working set of the build tree plus a verified borrowed daemon, without owning it."""
    pids = {pid}
    pids.update(process_descendants(pid))
    if borrowed:
        pids.add(borrowed["pid"])
    working_set = 0
    for target in sorted(pids):
        working_set += process_usage(target)[0]
    return {"working_set_bytes": working_set,
            "coverage": "launcher-and-verified-daemon" if borrowed else "UNKNOWN",
            "pids": sorted(pids)}


class PressureMonitor(threading.Thread):
    """Sample memory and CPU while the build is alive; a dead pid reports nothing useful."""

    def __init__(self, pid, borrowed, start_available, interval=1.0):
        super().__init__(daemon=True)
        self.pid = pid
        self.borrowed = borrowed
        self.interval = interval
        self.start_available = max(1, start_available)
        self.min_available = start_available
        self.max_external_fraction = 0.0
        self.peak_working_set = 0
        self.coverage = "UNKNOWN"
        self.samples = 0
        self._stop = threading.Event()

    def stop(self):
        self._stop.set()

    def run(self):
        cpu_count = max(1, os.cpu_count() or 1)
        sample = sample_build_processes(self.pid, self.borrowed)
        baseline_cpu = sum(process_usage(target)[1] for target in sample["pids"])
        started = time.monotonic()
        while not self._stop.wait(self.interval):
            try:
                self.min_available = min(self.min_available, available_memory()[0])
                sample = sample_build_processes(self.pid, self.borrowed)
                self.peak_working_set = max(self.peak_working_set, sample["working_set_bytes"])
                self.coverage = sample["coverage"]
                self.samples += 1
                busy = cpu_busy_fraction(0.05)
                tree = sum(process_usage(target)[1] for target in sample["pids"])
                elapsed = max(0.001, time.monotonic() - started)
                self_cpu = max(0.0, tree - baseline_cpu) / elapsed / cpu_count
                self.max_external_fraction = max(self.max_external_fraction, busy - self_cpu)
            except (OSError, ValueError):
                continue

    def memory_evidence(self, fallback):
        """Prefer what was observed while the build ran over a post-mortem sample."""
        if self.samples and self.peak_working_set > 0:
            return {"working_set_bytes": self.peak_working_set, "coverage": self.coverage,
                    "samples": self.samples}
        return {"working_set_bytes": fallback["working_set_bytes"],
                "coverage": fallback["coverage"], "samples": self.samples}

    @property
    def pressured(self):
        if self.min_available < self.start_available * MEMORY_COLLAPSE_FRACTION:
            return True
        return self.max_external_fraction > EXTERNAL_BUSY_FRACTION


# -------------------------------------------------------------------- cli ----


def _split_command(argv):
    if "--" in argv:
        index = argv.index("--")
        return argv[:index], argv[index + 1:]
    return list(argv), []


def _report(decision, snapshot, stream):
    print(f"workers={decision['workers']} parallel={decision['parallel']} "
          f"ceiling={decision['ceiling']} reason={decision['reason']} "
          f"available={snapshot['available_bytes'] / GIB:.1f}GiB/{snapshot['total_bytes'] / GIB:.1f}GiB "
          f"cpu={snapshot['cpu_count']}", file=stream)


def _memory_bytes_per_worker(argument):
    """Round to whole mebibytes so the decision record stays readable and stable."""
    if argument is None:
        return MEMORY_PER_WORKER_BYTES
    mebibyte = 1024 ** 2
    return max(mebibyte, int(float(argument) * 1024) * mebibyte)


def command_plan(args, command, snapshot, history):
    resolved, decision = prepare_command(command, snapshot, history, args.family, args.toolchain,
                                         cache=args.cache,
                                         memory_per_worker_bytes=_memory_bytes_per_worker(args.memory_per_worker_gb))
    decision["command"] = resolved
    decision["memory_source"] = snapshot.get("memory_source")
    print(json.dumps(decision, indent=2, sort_keys=True))
    _report(decision, snapshot, sys.stderr)
    return 0


def command_run(args, command, snapshot, history):
    resolved, decision = prepare_command(command, snapshot, history, args.family, args.toolchain,
                                         cache=args.cache,
                                         memory_per_worker_bytes=_memory_bytes_per_worker(args.memory_per_worker_gb))
    if decision["gradle"] and not args.no_daemon_sampling:
        borrowed = validate_runtime_receipt(find_borrowed_daemon())
    else:
        borrowed = None
    _report(decision, snapshot, sys.stderr)

    started_wall = time.monotonic()
    first_output = [None]

    process = subprocess.Popen(resolved, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                               encoding="utf-8", errors="replace", bufsize=1)

    def pump(stream, target):
        for line in stream:
            if first_output[0] is None:
                first_output[0] = time.monotonic() - started_wall
            target.write(line)
            target.flush()
        stream.close()

    pumps = [threading.Thread(target=pump, args=(process.stdout, sys.stdout), daemon=True),
             threading.Thread(target=pump, args=(process.stderr, sys.stderr), daemon=True)]
    for worker in pumps:
        worker.start()

    monitor = PressureMonitor(process.pid, borrowed, snapshot.get("available_bytes", 0))
    monitor.start()
    try:
        exit_code = process.wait()
    except KeyboardInterrupt:
        process.terminate()
        exit_code = 130
    finally:
        monitor.stop()
        for worker in pumps:
            worker.join(timeout=5)

    elapsed = time.monotonic() - started_wall
    memory = monitor.memory_evidence(sample_build_processes(process.pid, borrowed))
    queue_seconds = first_output[0] or 0.0
    pressure = monitor.pressured
    append_history(history_path(args.root), run_record(
        family=args.family, toolchain=args.toolchain, cache=args.cache, decision=decision,
        elapsed=elapsed, queue=queue_seconds, exit_code=exit_code, memory=memory, pressure=pressure))
    print(f"workers={decision['workers']} elapsed={elapsed:.1f}s exit={exit_code} "
          f"queue={queue_seconds:.1f}s peak={memory['working_set_bytes'] / GIB:.2f}GiB "
          f"coverage={memory['coverage']} samples={memory['samples']} pressure={pressure}",
          file=sys.stderr)
    return exit_code


def command_history(args, root):
    rows = [row for row in load_history(root)
            if row.get("family") == args.family and row.get("cache") == args.cache]
    if not rows:
        print(f"no recorded runs for family={args.family} cache={args.cache}")
        return 0
    toolchains = sorted({str(row.get("toolchain", "?")) for row in rows})
    print(f"family={args.family} toolchain={','.join(toolchains)} cache={args.cache} runs={len(rows)}")
    print(f"{'at':<26} {'workers':>7} {'elapsed':>9} {'queue':>7} {'exit':>5} {'pressure':>9}")
    for row in rows[-args.limit:]:
        print(f"{str(row.get('at', ''))[:25]:<26} {str(row.get('workers', '?')):>7} "
              f"{float(row.get('elapsed_seconds', 0.0)):>9.1f} {float(row.get('queue_seconds', 0.0)):>7.1f} "
              f"{str(row.get('exit_code', '?')):>5} {str(bool(row.get('pressure'))):>9}")
    return 0


def resolve_profiler(cli):
    candidate = cli or os.environ.get(PROFILER_ENV)
    if candidate:
        resolved = shutil.which(candidate) or (candidate if Path(candidate).exists() else None)
        if resolved:
            return resolved
    raise FileNotFoundError(
        f"gradle-profiler was not found. Install it yourself and point {PROFILER_ENV} at the launcher, "
        f"for example: set MIHON_GRADLE_PROFILER=C:\\tools\\gradle-profiler\\bin\\gradle-profiler.bat. "
        f"This tool never downloads a profiler on your behalf.")


def scenario_name(path):
    """The scenario label gradle-profiler reports, falling back to the file stem.

    utf-8-sig so a BOM written by a Windows editor does not leak into the summary.
    """
    try:
        for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
            candidate = line.strip()
            if not candidate or candidate.startswith(("#", ";")) or "=" in candidate:
                continue
            return candidate.strip("[]")
    except OSError:
        pass
    return Path(path).stem


def command_profile(args, root):
    try:
        cli = resolve_profiler(args.cli)
    except FileNotFoundError as error:
        print(str(error), file=sys.stderr)
        return 2
    scenario = Path(args.scenario)
    if not scenario.exists():
        print(f"scenario file not found: {scenario}", file=sys.stderr)
        return 2
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    resolved = [cli, "--profile", str(scenario), "--output", str(output)]
    if args.gradle_args:
        resolved += list(args.gradle_args)
    started = time.monotonic()
    completed = subprocess.run(resolved)
    elapsed = time.monotonic() - started
    summary = {"at": utc_now(), "scenario": scenario_name(scenario), "scenario_path": str(scenario),
               "output": str(output), "exit_code": completed.returncode,
               "elapsed_seconds": round(elapsed, 3), "profiler": Path(cli).name}
    destination = profile_directory(root) / f"{args.name}.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")
    print(f"profile {args.name}: exit={completed.returncode} elapsed={elapsed:.1f}s "
          f"report={output} summary={destination}", file=sys.stderr)
    return completed.returncode


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    for name, help_text in (("run", "run a build under the adaptive policy"),
                            ("plan", "print the decision without running anything")):
        sub = subparsers.add_parser(name, help=help_text)
        sub.add_argument("--root", default=".", help="repository root that owns .gradle-coordinator")
        sub.add_argument("--family", default="android-release")
        sub.add_argument("--toolchain", default="tool")
        sub.add_argument("--cache", default="cold", choices=("cold", "warm", "noop"))
        sub.add_argument("--memory-per-worker-gb", type=float, default=None)
        if name == "run":
            sub.add_argument("--no-daemon-sampling", action="store_true")

    history = subparsers.add_parser("history", help="show recorded runs without their build commands")
    history.add_argument("--root", default=".", help="repository root that owns .gradle-coordinator")
    history.add_argument("--family", default="android-release")
    history.add_argument("--toolchain", default="tool")
    history.add_argument("--cache", default="cold", choices=("cold", "warm", "noop"))
    history.add_argument("--limit", type=int, default=20)

    profile = subparsers.add_parser("profile", help="delegate to an externally installed gradle-profiler")
    profile.add_argument("--root", default=".", help="repository root that owns .gradle-coordinator")
    profile.add_argument("--cli", default=None)
    profile.add_argument("--scenario", required=True)
    profile.add_argument("--output", required=True)
    profile.add_argument("--name", default="profile")
    profile.add_argument("--gradle-args", default="")
    return parser


def main(argv=None):
    arguments = list(sys.argv[1:] if argv is None else argv)
    own, command = _split_command(arguments)
    parser = build_parser()
    args = parser.parse_args(own)
    root = Path(args.root).resolve()

    if args.subcommand == "history":
        return command_history(args, root)
    if args.subcommand == "profile":
        return command_profile(args, root)
    if not command:
        parser.error(f"{args.subcommand} needs a build command after --")
    snapshot = system_snapshot()
    history = load_history(root)
    if args.subcommand == "plan":
        return command_plan(args, command, snapshot, history)
    return command_run(args, command, snapshot, history)


if __name__ == "__main__":
    sys.exit(main())
