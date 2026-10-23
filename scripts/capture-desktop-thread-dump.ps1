param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.15.04688a4-unpacked",
    [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot",
    [int[]]$AtMs = @(2200, 2500, 2800, 3100),
    [string]$OutputDir = "D:\Github\mihon\app-desktop\tmp\threaddump"
)

# Captures thread dumps while the desktop application composes its first frame.
#
# The start-up trace shows a long gap between the composition of the real user
# interface and the first drawn frame, while the sampling profiler reports that
# the process is almost idle in that window. A thread dump shows what the
# threads are actually waiting for.

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$appDir = Join-Path $DistPath "app"
$cfg = Join-Path $appDir "Mihon Desktop.cfg"
$classpathEntries = New-Object System.Collections.Generic.List[string]
$options = New-Object System.Collections.Generic.List[string]
foreach ($line in [System.IO.File]::ReadAllLines($cfg)) {
    if ($line.StartsWith("app.classpath=")) {
        $classpathEntries.Add($line.Substring("app.classpath=".Length).Replace('$APPDIR', $appDir))
    } elseif ($line.StartsWith("java-options=")) {
        $value = $line.Substring("java-options=".Length).Replace('$APPDIR', $appDir)
        if ($value.StartsWith("-splash:")) { continue }
        $options.Add($value)
    }
}

$java = Join-Path $JavaHome "bin\java.exe"
$jcmd = Join-Path $JavaHome "bin\jcmd.exe"
$arguments = @()
foreach ($option in $options) { $arguments += $option }
$arguments += @("-cp", ($classpathEntries -join ';'), "mihon.desktop.MainKt")

$process = Start-Process -FilePath $java -ArgumentList $arguments -PassThru -WorkingDirectory $appDir
Write-Host "started pid $($process.Id)"

$watch = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($at in ($AtMs | Sort-Object)) {
    while ($watch.Elapsed.TotalMilliseconds -lt $at) { Start-Sleep -Milliseconds 20 }
    if ($process.HasExited) { break }
    $target = Join-Path $OutputDir ("dump-{0}ms.txt" -f $at)
    & $jcmd $process.Id Thread.print -l > $target 2>&1
    Write-Host ("dumped at {0} ms -> {1} ({2} lines)" -f $at, $target, (Get-Content $target | Measure-Object -Line).Lines)
}

try { if (-not $process.HasExited) { $process.Kill() } } catch { }
Start-Sleep -Milliseconds 500
Write-Host "done"
