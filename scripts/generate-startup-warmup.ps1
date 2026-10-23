param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.13.04688a4-unpacked",
    [string]$OutputPath = "D:\Github\mihon\app-desktop\src\main\resources\desktop-startup-warmup.txt",
    [int]$Seconds = 16
)

# Regenerates the class list that the desktop start-up loads ahead of the first user
# interface.
#
# The application is started once with `-verbose:class`; every class that is loaded from
# the moment the window content is composed until the real user interface is ready is
# collected. Those classes are exactly the ones the first frame would otherwise have to
# read from the packaged jars while it is being composed.
#
# Synthetic lambda classes are dropped: their names contain a memory address and change
# between runs.

$ErrorActionPreference = "Continue"

$appDir = Join-Path $DistPath "app"
$cfg = Join-Path $appDir "Mihon Desktop.cfg"
$exe = Join-Path $DistPath "Mihon Desktop.exe"
if (-not (Test-Path $cfg)) { Write-Host "Missing launcher config: $cfg"; exit 1 }

$backup = "$cfg.warmup.bak"
Copy-Item $cfg $backup -Force
try {
    $text = [System.IO.File]::ReadAllText($cfg)
    [System.IO.File]::WriteAllText($cfg, "$text`r`njava-options=-verbose:class`r`n", (New-Object System.Text.UTF8Encoding($false)))

    $stdout = "D:\Github\mihon\app-desktop\tmp\logs\warmup-classes.txt"
    $stderr = "D:\Github\mihon\app-desktop\tmp\logs\warmup-classes-err.txt"
    Write-Host "Starting the application with -verbose:class ..."
    Start-Process -FilePath $exe -RedirectStandardOutput $stdout -RedirectStandardError $stderr | Out-Null
    Start-Sleep -Seconds $Seconds

    foreach ($candidate in @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue)) {
        try {
            $candidate.Refresh()
            if ($candidate.MainWindowHandle -ne 0) { [void]$candidate.CloseMainWindow() }
        } catch { }
    }
    Start-Sleep -Seconds 5
} finally {
    Copy-Item $backup $cfg -Force
    Write-Host "launcher configuration restored"
}

$trace = Join-Path $env:LOCALAPPDATA "Mihon\logs\startup-trace.json"
if (-not (Test-Path $trace)) { Write-Host "Missing start-up trace: $trace"; exit 1 }
$content = Get-Content -Raw -Encoding UTF8 $trace | ConvertFrom-Json
$offset = ($content.jvmStartEpochMs - $content.processStartEpochMs) / 1000.0
$marks = @{}
foreach ($mark in $content.marks) { $marks[$mark.phase] = $mark.sinceProcessStartMs }
if (-not $marks.ContainsKey("content-composition") -or -not $marks.ContainsKey("ui-ready")) {
    Write-Host "The start-up trace does not contain the expected phases."
    exit 1
}
$from = ($marks["content-composition"] - $offset * 1000) / 1000.0
$to = ($marks["ui-ready"] - $offset * 1000) / 1000.0

$pattern = '^\[(\d+\.\d+)s\].*class,load\]\s+(\S+)\s+source:'
$names = New-Object System.Collections.Generic.List[string]
$seen = @{}
# The classes the list already covers are loaded before the window opens, so they are
# not part of this run's window any more. The new list therefore merges the window
# into the existing list instead of replacing it.
if (Test-Path $OutputPath) {
    foreach ($existing in [System.IO.File]::ReadAllLines($OutputPath)) {
        $trimmed = $existing.Trim()
        if ($trimmed -and -not $trimmed.StartsWith("#") -and -not $seen.ContainsKey($trimmed)) {
            $seen[$trimmed] = $true
            $names.Add($trimmed)
        }
    }
}
$before = $names.Count
foreach ($line in [System.IO.File]::ReadAllLines($stdout)) {
    $match = [regex]::Match($line, $pattern)
    if (-not $match.Success) { continue }
    $moment = [double]$match.Groups[1].Value
    if ($moment -lt $from -or $moment -ge $to) { continue }
    $name = $match.Groups[2].Value
    if ($name.Contains('$$Lambda') -or $name.Contains('/0x')) { continue }
    if ($name.Contains('__JVM_LookupDefineClass__')) { continue }
    if ($seen.ContainsKey($name)) { continue }
    $seen[$name] = $true
    $names.Add($name)
}

New-Item -ItemType Directory -Force -Path (Split-Path $OutputPath -Parent) | Out-Null
[System.IO.File]::WriteAllText($OutputPath, (($names -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ""
Write-Host "Wrote $($names.Count) class names to $OutputPath ($($names.Count - $before) added by this window)"
