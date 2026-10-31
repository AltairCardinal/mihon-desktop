"""Exercise the complete runtime validator with owned, console-free Windows processes."""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts/validate-windows-extension-runtime.ps1"
CSC = Path(r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe")
FIXTURE = r'''
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
class Fixture {
    [DllImport("kernel32.dll")] static extern IntPtr GetConsoleWindow();
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool AttachConsole(uint id);
    [DllImport("kernel32.dll")] static extern bool FreeConsole();
    static JavaScriptSerializer Json = new JavaScriptSerializer();
    static string Value(string[] args, string key) {
        foreach (string arg in args) if (arg.StartsWith(key + "=")) return arg.Substring(key.Length + 1);
        throw new Exception("Missing " + key);
    }
    static int Main(string[] args) {
        Console.OutputEncoding = new UTF8Encoding(false);
        if (args.Length > 0 && args[0] == "shared") {
            var target = Process.GetProcessById(int.Parse(args[3]));
            if (target.MainModule.FileName != args[4] || target.StartTime.ToUniversalTime().Ticks.ToString() != args[5])
                throw new Exception("Interactive shell identity changed");
            FreeConsole(); // Detach only this owned helper before attaching to the verified shell.
            if (!AttachConsole((uint)target.Id)) throw new Exception("AttachConsole target=" + target.Id + " error=" + Marshal.GetLastWin32Error());
            try {
                File.WriteAllText(Path.Combine(Environment.GetEnvironmentVariable("FIXTURE_AUDIT_ROOT"), "shared-console.txt"),
                    GetConsoleWindow().ToInt64().ToString());
                var start = new ProcessStartInfo(args[1], "-NoProfile -NonInteractive -File \"" + args[2] + "\"");
                start.UseShellExecute = false;
                start.CreateNoWindow = false; // Inherit only the verified existing console, never allocate one.
                start.RedirectStandardOutput = true; start.RedirectStandardError = true;
                using (var child = Process.Start(start)) {
                    string root = Environment.GetEnvironmentVariable("FIXTURE_AUDIT_ROOT");
                    using (var output = File.Create(Path.Combine(root, "shell.out")))
                    using (var error = File.Create(Path.Combine(root, "shell.err"))) {
                        var outCopy = child.StandardOutput.BaseStream.CopyToAsync(output);
                        var errCopy = child.StandardError.BaseStream.CopyToAsync(error);
                        if (!child.WaitForExit(35000)) { child.Kill(); throw new Exception("Owned shared wrapper timeout"); }
                        outCopy.Wait(); errCopy.Wait(); return child.ExitCode;
                    }
                }
            } finally { FreeConsole(); }
        }
        bool server = args.Length > 0 && args[0] == "-m";
        string audit = Environment.GetEnvironmentVariable("FIXTURE_AUDIT_ROOT");
        File.WriteAllText(Path.Combine(audit, server ? "server.json" : "app.json"), Json.Serialize(new {
            pid = Process.GetCurrentProcess().Id, console = GetConsoleWindow().ToInt64(), args = args
        }));
        if (server) {
            Console.WriteLine("owned fixture server stdout"); Console.Out.Flush();
            Console.Error.WriteLine("owned fixture server stderr"); Console.Error.Flush();
            var listener = new TcpListener(IPAddress.Loopback, int.Parse(args[2]));
            listener.Start();
            while (true) using (var client = listener.AcceptTcpClient()) {
                var stream = client.GetStream();
                var reader = new StreamReader(stream, Encoding.ASCII, false, 1024, true);
                string request = reader.ReadLine(); string header;
                while (!String.IsNullOrEmpty(header = reader.ReadLine())) { }
                string name = Uri.UnescapeDataString(request.Split(' ')[1]).TrimStart('/');
                byte[] body = File.ReadAllBytes(Path.Combine(Environment.CurrentDirectory, name));
                byte[] head = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: " + body.Length + "\r\nConnection: close\r\n\r\n");
                stream.Write(head, 0, head.Length);
                if (!request.StartsWith("HEAD ")) stream.Write(body, 0, body.Length);
            }
        }
        string mode = Environment.GetEnvironmentVariable("FIXTURE_FAIL_MODE");
        if (mode == "exit") { Console.Error.WriteLine("owned fixture exit 23"); return 23; }
        if (mode == "timeout") { Thread.Sleep(60000); return 0; }
        byte[] downloaded = new WebClient().DownloadData(Value(args, "--test-extension-runtime-url"));
        File.WriteAllBytes(Path.Combine(audit, "downloaded.bin"), downloaded);
        File.WriteAllText(Value(args, "--test-extension-runtime-result"), Json.Serialize(new {
            appVersion = "fixture-version", packageName = Value(args, "--test-extension-runtime-package"),
            success = mode != "result", error = "owned fixture failure", sourceIds = new long[] { 17 }
        }));
        Thread.Sleep(60000); // The validator must clean up its still-running owned app.
        return 0;
    }
}
'''


def quote_ps(value):
    return "'" + str(value).replace("'", "''") + "'"


def terminal_states():
    states = {}
    callback_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    user = ctypes.windll.user32
    user.IsWindowVisible.argtypes = [ctypes.c_void_p]
    user.IsIconic.argtypes = [ctypes.c_void_p]

    @callback_type
    def inspect(handle, _):
        name = ctypes.create_unicode_buffer(256)
        user.GetClassNameW(ctypes.c_void_p(handle), name, len(name))
        if name.value in ("ConsoleWindowClass", "CASCADIA_HOSTING_WINDOW_CLASS"):
            states[handle] = (bool(user.IsWindowVisible(handle)), bool(user.IsIconic(handle)))
        return True

    user.EnumWindows(inspect, 0)
    return states


def alive(pid):
    kernel = ctypes.windll.kernel32
    kernel.OpenProcess.restype = ctypes.c_void_p
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        return False
    try:
        code = ctypes.c_ulong()
        kernel.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(code))
        return code.value == 259
    finally:
        kernel.CloseHandle(ctypes.c_void_p(handle))


def stop_owned_fixture(pid, executable):
    kernel = ctypes.windll.kernel32
    kernel.OpenProcess.restype = ctypes.c_void_p
    handle = kernel.OpenProcess(0x101001, False, pid)
    if not handle:
        return
    try:
        name = ctypes.create_unicode_buffer(32768)
        size = ctypes.c_ulong(len(name))
        if kernel.QueryFullProcessImageNameW(ctypes.c_void_p(handle), 0, name, ctypes.byref(size)):
            if Path(name.value).resolve() == executable:
                kernel.TerminateProcess(ctypes.c_void_p(handle), 1)
                kernel.WaitForSingleObject(ctypes.c_void_p(handle), 5000)
    finally:
        kernel.CloseHandle(ctypes.c_void_p(handle))


@unittest.skipUnless(sys.platform == "win32" and CSC.is_file(), "Windows console process contract")
class RuntimeProcessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="runtime process fixture ")
        cls.root = Path(cls.temporary.name).resolve()
        source = cls.root / "fixture.cs"
        source.write_text(FIXTURE, encoding="utf-8")
        cls.executable = cls.root / "owned console fixture.exe"
        subprocess.run([str(CSC), "/nologo", "/target:exe", "/r:System.Web.Extensions.dll",
                        f"/out:{cls.executable}", str(source)], check=True, capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW)
        cls.shells = list(dict.fromkeys(filter(None, (shutil.which("powershell"), shutil.which("pwsh")))))
        query = ("Get-CimInstance Win32_Process -Filter \"Name='powershell.exe' OR Name='pwsh.exe'\" | "
                 "ForEach-Object { $p=$_; $parent=Get-Process -Id $p.ParentProcessId -ErrorAction SilentlyContinue; "
                 "if ($parent.ProcessName -eq 'WindowsTerminal') { $live=Get-Process -Id $p.ProcessId; "
                 "[pscustomobject]@{pid=$p.ProcessId;path=$p.ExecutablePath;started=$live.StartTime.ToUniversalTime().Ticks} } } | "
                 "Select-Object -First 1 | ConvertTo-Json -Compress")
        raw = subprocess.check_output([cls.shells[0], "-NoProfile", "-NonInteractive", "-Command", query],
                                      creationflags=subprocess.CREATE_NO_WINDOW)
        cls.interactive = json.loads(raw.decode("utf-8-sig"))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def invoke(self, shell, mode, shared=False):
        root = self.root / (Path(shell).stem + " " + (mode or "success") + (" shared" if shared else ""))
        root.mkdir()
        artifact = root / "fixture payload.apk"
        artifact.write_bytes(b"owned extension fixture")
        wrapper = root / "invoke.ps1"
        injection = ""
        if mode.startswith("cleanup-"):
            line = next(i for i, text in enumerate(VALIDATOR.read_text(encoding="utf-8").splitlines(), 1)
                        if "$owned.WaitForExit(5000)" in text)
            injection = (f"Set-PSBreakpoint -Script {quote_ps(VALIDATOR)} -Line {line} -Action {{ "
                         "if (-not $global:cleanupInjected) { $global:cleanupInjected=$true; "
                         "$owned | Add-Member -Force -MemberType ScriptMethod -Name WaitForExit "
                         "-Value { param($timeout) throw 'owned cleanup wait injected' } } } | Out-Null\n")
        parent_audit = ""
        if shared:
            parent_audit = (
                "Add-Type 'using System; using System.Runtime.InteropServices; public static class ParentConsole { "
                "[DllImport(\"kernel32.dll\")] public static extern IntPtr GetConsoleWindow(); }'\n"
                "[IO.File]::WriteAllText((Join-Path $env:FIXTURE_AUDIT_ROOT 'parent-console.txt'), "
                "[ParentConsole]::GetConsoleWindow().ToInt64().ToString())\n")
        wrapper.write_text(
            "$ErrorActionPreference='Stop'\n"
            "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n"
            # RED safely intercepts the forbidden API before it can affect a shared console.
            "function Start-Process { throw 'FORBIDDEN_START_PROCESS_INTERCEPTED' }\n"
            + parent_audit + injection +
            f"& {quote_ps(VALIDATOR)} -Executable {quote_ps(self.executable)} "
            f"-ArtifactPath {quote_ps(artifact)} -PackageName 'fixture.package' "
            "-DisplayName 'Name with spaces and \"quotes\" \\tail\\' "
            "-VersionName 'Version \"two\"' -VersionCode 7 -RepositoryFingerprint 'repo \\path\\' "
            f"-ArtifactSha256 '{hashlib.sha256(artifact.read_bytes()).hexdigest()}' "
            "-ExpectedVersion 'fixture-version' -ExpectedSourceId 17 -TimeoutSeconds 2\n",
            encoding="utf-8")
        env = dict(os.environ, MIHON_PYTHON=str(self.executable), FIXTURE_AUDIT_ROOT=str(root),
                   FIXTURE_FAIL_MODE="result" if mode == "cleanup-result" else mode)
        before = terminal_states()
        command = [shell, "-NoProfile", "-NonInteractive", "-File", str(wrapper)]
        if shared:
            target = self.interactive
            command = [str(self.executable), "shared", shell, str(wrapper), str(target["pid"]),
                       target["path"], str(target["started"])]
        shell_output = (root / "launcher.out").open("wb")
        process = subprocess.Popen(command,
                                   env=env, stdout=shell_output, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        deadline = time.monotonic() + 40
        samples = 0
        try:
            while process.poll() is None:
                self.assertLess(time.monotonic(), deadline, "Owned validator exceeded its test deadline")
                current = terminal_states()
                for handle, state in before.items():
                    if handle in current:
                        self.assertEqual(state, current[handle], "Existing terminal visibility/minimize state changed")
                samples += 1
                time.sleep(0.025)
            shell_output.close()
            output = (root / "launcher.out").read_text(encoding="utf-8-sig")
            if shared:
                console = (root / "shared-console.txt").read_text(encoding="utf-8")
                self.assertNotEqual("0", console)
                self.assertEqual(console, (root / "parent-console.txt").read_text(encoding="utf-8"))
                output += (root / "shell.out").read_text(encoding="utf-8-sig")
                output += (root / "shell.err").read_text(encoding="utf-8-sig")
        finally:
            shell_output.close()
            if process.poll() is None:
                process.kill()
                process.wait()
                for name in ("server.json", "app.json"):
                    audit = root / name
                    if audit.is_file():
                        stop_owned_fixture(json.loads(audit.read_text(encoding="utf-8-sig"))["pid"], self.executable)
        print(f"shell={Path(shell).name} mode={mode or 'success'} exit={process.returncode} "
              f"shared={shared} terminalWindows={len(before)} terminalSamples={samples}")
        self.assertNotIn("FORBIDDEN_START_PROCESS_INTERCEPTED", output)
        records = []
        for name in ("server.json", "app.json"):
            record = json.loads((root / name).read_text(encoding="utf-8-sig"))
            records.append(record)
            self.assertEqual(0, record["console"], f"{name} allocated a console")
            try:
                self.assertFalse(alive(record["pid"]), f"Owned {name} process survived validator cleanup")
            finally:
                stop_owned_fixture(record["pid"], self.executable)
        args = records[1]["args"]
        self.assertIn('--test-extension-runtime-name=Name with spaces and "quotes" \\tail\\', args)
        self.assertIn('--test-extension-runtime-version=Version "two"', args)
        self.assertIn('--test-extension-runtime-fingerprint=repo \\path\\', args)
        result_path = Path(next(arg.split("=", 1)[1] for arg in args if arg.startswith("--test-extension-runtime-result=")))
        diagnostics = result_path.parent.parent
        if mode:
            self.assertNotEqual(0, process.returncode, output)
            self.assertTrue(diagnostics.is_dir())
            self.assertIn("owned fixture server stdout", (diagnostics / "fixture-server.out.log").read_text(encoding="utf-8"))
            self.assertIn("owned fixture server stderr", (diagnostics / "fixture-server.err.log").read_text(encoding="utf-8"))
            shutil.rmtree(diagnostics)
        else:
            self.assertEqual(0, process.returncode, output)
            self.assertEqual(artifact.read_bytes(), (root / "downloaded.bin").read_bytes())
            self.assertFalse(diagnostics.exists(), "Successful validator must remove its own temporary directory")
        return output

    def test_success_keeps_console_free_argv_http_and_owned_cleanup(self):
        for shell in self.shells:
            with self.subTest(shell=shell):
                self.invoke(shell, "")
        self.invoke(self.shells[0], "", shared=True)

    def test_failure_preserves_logs_exit_code_timeout_and_owned_cleanup(self):
        for shell in self.shells:
            for mode in ("result", "exit", "timeout", "cleanup-result", "cleanup-success"):
                with self.subTest(shell=shell, mode=mode):
                    output = self.invoke(shell, mode)
                    self.assertIn({"result": "owned fixture failure", "exit": "exit code 23",
                                   "timeout": "within 2 seconds", "cleanup-result": "owned fixture failure",
                                   "cleanup-success": "Runtime cleanup failed"}[mode], output)
                    if mode.startswith("cleanup-"):
                        self.assertIn("owned cleanup wait injected", output)
        self.invoke(self.shells[0], "result", shared=True)


if __name__ == "__main__":
    unittest.main()
