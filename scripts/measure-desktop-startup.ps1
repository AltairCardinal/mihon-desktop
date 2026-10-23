param(
    [Parameter(Mandatory = $true)]
    [string]$DistPath,

    [int]$Runs = 5,

    [int]$TimeoutSeconds = 90,

    [string]$Label = "baseline",

    [switch]$FreshProfile,

    [string]$ExtraJavaOption = "",

    [switch]$GracefulClose,

    [int]$SettleBudgetMs = 4000
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class Win32 {
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
}
"@

function Get-WindowTitle([IntPtr]$handle) {
    $builder = New-Object System.Text.StringBuilder 512
    [void][Win32]::GetWindowText($handle, $builder, $builder.Capacity)
    return $builder.ToString()
}

function Write-Utf8NoBom([string]$path, [string]$content) {
    # Windows PowerShell 5.1 writes a BOM with -Encoding UTF8, which corrupts the
    # launcher configuration file and breaks JVM startup.
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $encoding)
}

function Get-MihonProcesses {
    Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue
}

function Stop-MihonProcesses {
    # The launcher spawns a child JVM; kill repeatedly so no owner survives and
    # forwards the next launch to a stale instance.
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        $procs = Get-MihonProcesses
        if (-not $procs) { return }
        foreach ($p in $procs) {
            try { $p.Kill() } catch { }
        }
        Start-Sleep -Milliseconds 700
    }
}

$exe = Join-Path $DistPath "Mihon Desktop.exe"
if (-not (Test-Path $exe)) {
    throw "Desktop executable not found: $exe"
}

$cfg = Join-Path $DistPath "app\Mihon Desktop.cfg"
$cfgBackup = "$cfg.startup-measure-backup"
if (Test-Path $cfg) {
    Copy-Item $cfg $cfgBackup -Force
}

if (-not $ExtraJavaOption -and $env:MIHON_STARTUP_EXTRA_JAVA_OPTION) {
    $ExtraJavaOption = $env:MIHON_STARTUP_EXTRA_JAVA_OPTION
}

if ($ExtraJavaOption) {
    if (-not (Test-Path $cfg)) { throw "Launcher config not found: $cfg" }
    $content = Get-Content -Raw -Encoding UTF8 $cfg
    if ($content -notmatch '(?m)^java-options=') { throw "java-options line missing in $cfg" }
    # The launcher parses each java-options line as exactly one option, so every
    # option needs its own line.
    $lines = $ExtraJavaOption -split '\s+' | Where-Object { $_ }
    $appended = ($lines | ForEach-Object { "java-options=" + $_ }) -join "`r`n"
    $content = $content.TrimEnd() + "`r`n" + $appended + "`r`n"
    Write-Utf8NoBom $cfg $content
}

$tracePath = Join-Path $env:LOCALAPPDATA "Mihon\logs\startup-trace.json"
$appData = Join-Path $env:APPDATA "Mihon"
$localData = Join-Path $env:LOCALAPPDATA "Mihon"
$backupAppData = $null
$backupLocalData = $null
if ($FreshProfile) {
    if (Test-Path $appData) {
        $backupAppData = "$appData.startup-measure-backup"
        Move-Item $appData $backupAppData -Force
    }
    if (Test-Path $localData) {
        $backupLocalData = "$localData.startup-measure-backup"
        Move-Item $localData $backupLocalData -Force
    }
}

$results = @()

try {
    for ($i = 1; $i -le $Runs; $i++) {
        Stop-MihonProcesses

        $launchedAt = Get-Date
        $stderrPath = Join-Path $env:TEMP "mihon-startup-stderr-$Label-$i.txt"
        $proc = Start-Process -FilePath $exe -PassThru -RedirectStandardError $stderrPath
        $windowMs = $null
        $splashMs = $null
        $windowProc = $null
        $firstFrameMs = $null
        $uiReadyMs = $null
        $traceMarks = $null

        $poll = [System.Diagnostics.Stopwatch]::StartNew()
        $frameSeenAt = $null
        $traceComplete = $false
        while ($poll.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
            Start-Sleep -Milliseconds 25

            # Primary signal: the application reports its own first frame.
            if (Test-Path $tracePath) {
                $traceInfo = Get-Item $tracePath
                if ($traceInfo.LastWriteTime -ge $launchedAt.AddSeconds(-1)) {
                    try {
                        $trace = Get-Content -Raw -Encoding UTF8 $tracePath | ConvertFrom-Json
                        $frameMark = $trace.marks | Where-Object { $_.phase -eq "first-frame" } | Select-Object -Last 1
                        $uiMark = $trace.marks | Where-Object { $_.phase -eq "ui-ready" } | Select-Object -Last 1
                        if ($frameMark) {
                            if ($null -eq $frameSeenAt) { $frameSeenAt = $poll.Elapsed.TotalMilliseconds }
                            # The application rewrites the trace file on every marker, so
                            # a snapshot that only carries the first frame can be read
                            # before the real interface is ready. The measurement waits
                            # for both markers and then reads the settled file, otherwise
                            # the two reported moments come from different points in
                            # time.
                            if ($uiMark) {
                                $firstFrameMs = [double]$frameMark.sinceProcessStartMs
                                $uiReadyMs = [double]$uiMark.sinceProcessStartMs
                                $traceMarks = $trace.marks
                                $traceComplete = $true
                            }
                        }
                    } catch { }
                }
            }

            $candidates = Get-MihonProcesses
            if (-not $candidates) { break }
            foreach ($candidate in $candidates) {
                try {
                    $candidate.Refresh()
                    $handle = $candidate.MainWindowHandle
                } catch { continue }
                if ($null -eq $handle) { continue }
                $handle = [IntPtr]$handle
                if ($handle -eq [IntPtr]::Zero) { continue }
                if (-not [Win32]::IsWindowVisible($handle)) { continue }
                $title = Get-WindowTitle $handle
                # The application window is titled "Mihon Desktop <version>"; the
                # launcher error dialog is titled "Mihon Desktop.exe" and must not
                # be mistaken for a ready application.
                if ($title -match '^Mihon Desktop \d') {
                    $windowMs = [math]::Round($poll.Elapsed.TotalMilliseconds)
                    $windowProc = $candidate
                } elseif (-not $splashMs -and [string]::IsNullOrEmpty($title)) {
                    $splashMs = [math]::Round($poll.Elapsed.TotalMilliseconds)
                }
            }

            if ($traceComplete) {
                # The window can only be observed once the first frame is drawn, and a
                # build whose first frame already carries the real interface reports
                # both markers at the same moment. The poll therefore keeps looking
                # for the window for a short grace period instead of stopping at the
                # marker.
                if ($null -ne $windowMs) { break }
                if ($null -ne $frameSeenAt -and $poll.Elapsed.TotalMilliseconds - $frameSeenAt -gt 1500) { break }
            }
        }

        # Give the app a moment to settle so post-startup load can be observed.
        if ($windowProc) {
            $settle = [System.Diagnostics.Stopwatch]::StartNew()
            $prevCpu = $windowProc.TotalProcessorTime.TotalMilliseconds
            $idleStreak = 0
            while ($settle.Elapsed.TotalMilliseconds -lt $SettleBudgetMs) {
                Start-Sleep -Milliseconds 250
                try { $windowProc.Refresh() } catch { break }
                if ($windowProc.HasExited) { break }
                $cpu = $windowProc.TotalProcessorTime.TotalMilliseconds
                $cpuDelta = $cpu - $prevCpu
                $prevCpu = $cpu
                if ($cpuDelta -lt 25) {
                    $idleStreak++
                    if ($idleStreak -ge 4) { break }
                } else {
                    $idleStreak = 0
                }
            }
        }

        $traceCopy = $null
        if ($traceMarks) {
            $traceCopy = Join-Path $PSScriptRoot "..\app-desktop\tmp\startup-trace-$Label-$i.json"
            Copy-Item $tracePath $traceCopy -Force
        }

        if ($GracefulClose -and $windowProc) {
            # A graceful close lets the JVM exit normally, which is required for
            # -XX:ArchiveClassesAtExit to write the class data sharing archive.
            try { [void]$windowProc.CloseMainWindow() } catch { }
            $closeDeadline = (Get-Date).AddSeconds(25)
            while ((Get-Date) -lt $closeDeadline) {
                Start-Sleep -Milliseconds 200
                if ($proc.HasExited) { break }
            }
        }

        $results += [pscustomobject]@{
            Run = $i
            FirstFrameMs = if ($firstFrameMs) { [math]::Round($firstFrameMs) } else { $null }
            UiReadyMs = if ($uiReadyMs) { [math]::Round($uiReadyMs) } else { $null }
            WindowVisibleMs = $windowMs
            SplashMs = $splashMs
            CpuMs = if ($windowProc -and -not $windowProc.HasExited) { [math]::Round($windowProc.TotalProcessorTime.TotalMilliseconds) } else { $null }
            Trace = $traceCopy
            LauncherAlive = -not $proc.HasExited
        }

        Stop-MihonProcesses
        Start-Sleep -Milliseconds 400
    }
}
finally {
    Stop-MihonProcesses
    if (Test-Path $cfgBackup) {
        Move-Item $cfgBackup $cfg -Force
    }
    if ($backupAppData -and (Test-Path $backupAppData)) {
        if (Test-Path $appData) { Remove-Item $appData -Recurse -Force }
        Move-Item $backupAppData $appData -Force
    }
    if ($backupLocalData -and (Test-Path $backupLocalData)) {
        if (Test-Path $localData) { Remove-Item $localData -Recurse -Force }
        Move-Item $backupLocalData $localData -Force
    }
}

function Get-Stats([object[]]$values) {
    $numbers = @($values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    if ($numbers.Count -eq 0) { return $null }
    $sorted = @($numbers | Sort-Object)
    # [int] rounds half to even, so 1.5 becomes 2 and the median would be the maximum.
    $middle = [int][math]::Floor($sorted.Count / 2)
    $median = if ($sorted.Count % 2 -eq 1) { $sorted[$middle] } else { ($sorted[$middle - 1] + $sorted[$middle]) / 2 }
    return [pscustomobject]@{
        Count = $sorted.Count
        Min = [math]::Round($sorted[0])
        Median = [math]::Round($median)
        Max = [math]::Round($sorted[-1])
        Mean = [math]::Round(($numbers | Measure-Object -Average).Average)
    }
}

Write-Host ""
Write-Host "=== Desktop startup measurement: $Label ==="
$results | Format-Table -AutoSize | Out-String | Write-Host

$frameStats = Get-Stats @($results | ForEach-Object { $_.FirstFrameMs })
$uiStats = Get-Stats @($results | ForEach-Object { $_.UiReadyMs })

Write-Host "Time to first frame (process start -> first frame drawn, app self-reported):"
if ($frameStats) {
    Write-Host ("  min={0}ms  median={1}ms  mean={2}ms  max={3}ms  (n={4})" -f $frameStats.Min, $frameStats.Median, $frameStats.Mean, $frameStats.Max, $frameStats.Count)
} else {
    Write-Host "  no successful runs"
}
Write-Host "Time to usable interface (process start -> real user interface on screen):"
if ($uiStats) {
    Write-Host ("  min={0}ms  median={1}ms  mean={2}ms  max={3}ms  (n={4})" -f $uiStats.Min, $uiStats.Median, $uiStats.Mean, $uiStats.Max, $uiStats.Count)
} else {
    Write-Host "  no successful runs"
}
$windowStats = Get-Stats @($results | ForEach-Object { $_.WindowVisibleMs })

Write-Host "App window visible (process start -> titled HWND visible):"
if ($windowStats) {
    Write-Host ("  min={0}ms  median={1}ms  mean={2}ms  max={3}ms  (n={4})" -f $windowStats.Min, $windowStats.Median, $windowStats.Mean, $windowStats.Max, $windowStats.Count)
} else {
    Write-Host "  no successful runs"
}

$lastTrace = @($results | Where-Object { $_.Trace -and (Test-Path $_.Trace) } | Select-Object -Last 1)
if ($lastTrace) {
    Write-Host ""
    Write-Host "Startup phase breakdown (last successful run):"
    $trace = Get-Content -Raw -Encoding UTF8 $lastTrace.Trace | ConvertFrom-Json
    $previous = $trace.processStartEpochMs
    foreach ($mark in $trace.marks) {
        Write-Host ("  {0,-22} +{1,6} ms   (at {2} ms since process start)" -f $mark.phase, ($mark.epochMs - $previous), $mark.sinceProcessStartMs)
        $previous = $mark.epochMs
    }
    Write-Host ("  process -> jvm start: {0} ms" -f ($trace.jvmStartEpochMs - $trace.processStartEpochMs))
}

$results | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path (Join-Path $PSScriptRoot "..\app-desktop\tmp\startup-measure-$Label.json")
Write-Host ""
Write-Host "Raw results: app-desktop/tmp/startup-measure-$Label.json"
