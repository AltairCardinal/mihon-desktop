param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop",
    [string]$ArchivePath = "$env:LOCALAPPDATA\Mihon\cds\mihon-train.jsa",
    [string]$Mode = "archive-at-exit",
    [int]$WaitSeconds = 12,
    [int]$WindowTimeoutSeconds = 60
)

# Verifies that the desktop application can produce a class data sharing archive.
# The archive is written by the application JVM while it exits, so the window has to be
# closed with a window message instead of killing the process.

$ErrorActionPreference = "Continue"

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class WinClose {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
    public const uint WM_CLOSE = 0x0010;
}
"@

function Find-ApplicationWindow([int[]]$processIds) {
    $found = [IntPtr]::Zero
    $callback = [WinClose+EnumWindowsProc]{
        param([IntPtr]$hWnd, [IntPtr]$lParam)
        $owner = 0
        [void][WinClose]::GetWindowThreadProcessId($hWnd, [ref]$owner)
        if ($processIds -contains [int]$owner -and [WinClose]::IsWindowVisible($hWnd)) {
            $title = New-Object System.Text.StringBuilder 512
            [void][WinClose]::GetWindowText($hWnd, $title, $title.Capacity)
            if ($title.ToString() -match '^Mihon Desktop \d') {
                $script:foundWindow = $hWnd
                return $false
            }
        }
        return $true
    }
    $script:foundWindow = [IntPtr]::Zero
    [void][WinClose]::EnumWindows($callback, [IntPtr]::Zero)
    return $script:foundWindow
}

$cfg = Join-Path $DistPath "app\Mihon Desktop.cfg"
$cfgBackup = "$cfg.cds-backup"
Copy-Item $cfg $cfgBackup -Force

$archiveDir = Split-Path -Parent $ArchivePath
New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
if (Test-Path $ArchivePath) { [System.IO.File]::Delete($ArchivePath) }

$stderrPath = Join-Path $env:TEMP "mihon-cds-diag-stderr.txt"

try {
    $options = if ($Mode -eq "auto-create") {
        @("-XX:+AutoCreateSharedArchive", "-XX:SharedArchiveFile=$ArchivePath")
    } elseif ($Mode -eq "none") {
        @()
    } else {
        @("-XX:ArchiveClassesAtExit=$ArchivePath")
    }
    $content = Get-Content -Raw -Encoding UTF8 $cfg
    $appended = ($options | ForEach-Object { "java-options=" + $_ }) -join "`r`n"
    if ($appended) {
        $content = $content.TrimEnd() + "`r`n" + $appended + "`r`n"
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($cfg, $content, $encoding)
    Write-Host ("Launcher options: " + ($options -join " "))

    Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
    Start-Sleep -Seconds 2

    $proc = Start-Process -FilePath (Join-Path $DistPath "Mihon Desktop.exe") -PassThru -RedirectStandardError $stderrPath

    $handle = [IntPtr]::Zero
    $deadline = (Get-Date).AddSeconds($WindowTimeoutSeconds)
    while ($handle -eq [IntPtr]::Zero -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $ids = @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
        if ($ids.Count -gt 0) { $handle = Find-ApplicationWindow $ids }
    }

    if ($handle -eq [IntPtr]::Zero) {
        Write-Host "No application window found; cannot close gracefully."
        $ids = @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
        Write-Host "  live process ids: $($ids -join ', ')"
        Write-Host "--- stderr ---"
        if (Test-Path $stderrPath) { Get-Content $stderrPath -Tail 20 | ForEach-Object { Write-Host "  $_" } }
    } else {
        Write-Host "Sending WM_CLOSE to window $($handle.ToInt64())..."
        [void][WinClose]::PostMessage($handle, [WinClose]::WM_CLOSE, [IntPtr]::Zero, [IntPtr]::Zero)
        $deadline = (Get-Date).AddSeconds(40)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 300
            if ($proc.HasExited) { break }
        }
        Write-Host "Launcher exited: $($proc.HasExited)"
    }

    Start-Sleep -Seconds 3
    if (Test-Path $ArchivePath) {
        Write-Host ("Archive produced: {0} ({1} MB)" -f $ArchivePath, [math]::Round((Get-Item $ArchivePath).Length / 1MB, 2))
    } else {
        Write-Host "Archive NOT produced."
    }
    Write-Host "--- stderr (cds lines) ---"
    if (Test-Path $stderrPath) {
        Get-Content $stderrPath | Select-String -Pattern "cds|CDS|shared archive|Archive|Error" | Select-Object -First 12 |
            ForEach-Object { Write-Host $_.Line }
    }
}
finally {
    Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
    if (Test-Path $cfgBackup) { Move-Item $cfgBackup $cfg -Force }
}
