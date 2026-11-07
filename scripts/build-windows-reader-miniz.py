#!/usr/bin/env python3
"""Build the pinned Windows x64 reader JNI library without downloading dependencies."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


PIN = "174573d60290f447c13a2b1b3405de2b96e27d6c"
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app-desktop/native/windows-miniz"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(arguments: list[str | Path], *, cwd: Path, env: dict[str, str]) -> bytes:
    result = subprocess.run(
        [str(value) for value in arguments], cwd=cwd, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NO_WINDOW, check=False,
    )
    if result.stdout:
        sys.stdout.buffer.write(result.stdout)
        sys.stdout.buffer.flush()
    result.check_returncode()
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--build-dir", type=Path, default=ROOT / "app-desktop/build/windows-reader-miniz")
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("The reader JNI build requires Windows x64 with MSVC and Windows SDK")
    output = args.output.resolve()
    build = args.build_dir.resolve()
    build.mkdir(parents=True, exist_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    program_files = Path(os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)"))
    vswhere = program_files / "Microsoft Visual Studio/Installer/vswhere.exe"
    installation = run(
        [vswhere, "-latest", "-products", "*", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
         "-property", "installationPath", "-utf8"], cwd=build, env=dict(os.environ),
    ).decode("utf-8").strip()
    if not installation:
        raise RuntimeError("MSVC x64 tools are required to build the packaged reader DLL")
    vc_root = Path(installation) / "VC/Tools/MSVC"
    vc = sorted((path for path in vc_root.iterdir() if path.is_dir()), key=lambda p: p.name)[-1]
    sdk = program_files / "Windows Kits/10"
    sdk_version = sorted(path.name for path in (sdk / "Include").iterdir()
                         if (path / "um/windows.h").is_file() and (sdk / "Lib" / path.name / "um/x64").is_dir())[-1]
    javac = shutil.which("javac")
    jdk = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else Path(javac or "").resolve().parents[1]
    if not (jdk / "include/win32/jni_md.h").is_file():
        raise RuntimeError("JAVA_HOME or javac must identify a JDK with Windows JNI headers")
    compiler = vc / "bin/Hostx64/x64/cl.exe"
    linker = vc / "bin/Hostx64/x64/link.exe"
    env = dict(os.environ)
    env["PATH"] = str(compiler.parent) + os.pathsep + env.get("PATH", "")
    env["INCLUDE"] = os.pathsep.join(str(p) for p in [vc / "include"] +
                                   [sdk / "Include" / sdk_version / part for part in ("ucrt", "shared", "um", "winrt")])
    env["LIB"] = os.pathsep.join(str(p) for p in [vc / "lib/x64"] +
                               [sdk / "Lib" / sdk_version / part for part in ("ucrt/x64", "um/x64")])
    common = ["/nologo", "/c", "/O2", "/MT", "/Brepro", "/D_CRT_SECURE_NO_WARNINGS"]
    c_sources = [SOURCE / "miniz" / name for name in ("miniz.c", "miniz_zip.c", "miniz_tdef.c", "miniz_tinfl.c")]
    run([compiler, *common, *c_sources], cwd=build, env=env)
    run([compiler, *common, "/std:c++17", "/EHsc", "/I" + str(jdk / "include"),
         "/I" + str(jdk / "include/win32"), SOURCE / "reader_miniz.cpp"], cwd=build, env=env)
    run([linker, "/nologo", "/DLL", "/MACHINE:X64", "/Brepro", "/INCREMENTAL:NO", "/OUT:" + str(output),
         "reader_miniz.obj", *[p.stem + ".obj" for p in c_sources], "bcrypt.lib"], cwd=build, env=env)
    sources = sorted(p for p in SOURCE.rglob("*") if p.is_file())
    manifest = {
        "minizCommit": PIN, "abi": 1, "architecture": "x64", "runtime": "MSVC static CRT (/MT)",
        "compiler": str(compiler), "windowsSdk": sdk_version, "jniHeaders": str(jdk),
        "sources": {p.relative_to(ROOT).as_posix(): digest(p) for p in sources},
        "buildScriptSha256": digest(Path(__file__)), "dllSha256": digest(output),
    }
    output.with_suffix(".build.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    shutil.copyfile(SOURCE / "miniz/LICENSE", output.parent / "miniz-LICENSE.txt")
    run([compiler.parent / "dumpbin.exe", "/headers", "/dependents", output], cwd=build, env=env)
    print(f"Reader miniz DLL: {output}", flush=True)
    print(f"Reader miniz SHA-256: {manifest['dllSha256']}", flush=True)


if __name__ == "__main__":
    main()
