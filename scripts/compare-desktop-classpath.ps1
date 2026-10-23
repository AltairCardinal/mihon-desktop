param(
    [Parameter(Mandatory = $true)]
    [string]$DistPath,

    [int]$Runs = 6,

    [string]$RemoveJar = "kotlin-reflect",

    [string]$LabelA = "with",

    [string]$LabelB = "without",

    [string]$OptionA = "",

    [string]$OptionB = "",

    [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot",

    [int]$TimeoutSeconds = 90
)

# Compares two classpath variants of the same packaged build.
#
# The packaged launcher reads its classpath from the launcher configuration, so a
# variant that removes a jar cannot be produced without repackaging. This script
# therefore launches the very same packaged classpath from a full JDK and only
# changes the classpath itself, which isolates the effect of the removed jar.
#
# The variants are interleaved, so a drift in background load hits both sides.

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$appDir = Join-Path $DistPath "app"
$cfg = Join-Path $appDir "Mihon Desktop.cfg"
if (-not (Test-Path $cfg)) { throw "Launcher configuration not found: $cfg" }

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

$full = @($classpathEntries)
if ($RemoveJar -and $RemoveJar -ne "none") {
    $reduced = @($classpathEntries | Where-Object { (Split-Path $_ -Leaf) -notlike "*$RemoveJar*" })
    Write-Host "classpath entries: full=$($full.Count) reduced=$($reduced.Count) (removed '$RemoveJar')"
    if ($full.Count -eq $reduced.Count) { throw "The filter '$RemoveJar' did not remove any classpath entry" }
} else {
    $reduced = $full
    Write-Host "classpath entries: $($full.Count) (identical on both sides)"
}

$java = Join-Path $JavaHome "bin\java.exe"
if (-not (Test-Path $java)) { throw "Java not found: $java" }

$tracePath = Join-Path $env:LOCALAPPDATA "Mihon\logs\startup-trace.json"

function Stop-MihonProcesses {
    # The launcher spawns a child JVM; kill repeatedly so no owner survives and
    # forwards the next launch to a stale instance.
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        $procs = Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue
        if (-not $procs) { return }
        foreach ($p in $procs) {
            try { $p.Kill() } catch { }
        }
        Start-Sleep -Milliseconds 700
    }
}

function Invoke-Variant([string[]]$entries, [string]$label, [int]$run, [string]$extraOption) {
    # A surviving instance keeps the single instance broker, so the next launch only
    # forwards to it and exits without ever writing a trace. Every variant therefore
    # starts from a clean slate.
    Stop-MihonProcesses

    $arguments = @()
    foreach ($option in $options) { $arguments += $option }
    if ($extraOption) {
        foreach ($single in ($extraOption -split '\s+' | Where-Object { $_ })) { $arguments += $single }
    }
    $arguments += @("-cp", ($entries -join ';'), "mihon.desktop.MainKt")

    # A stale trace file would be read as the result of this run, because the
    # freshness check tolerates a one second clock difference.
    if (Test-Path $tracePath) {
        try { [System.IO.File]::Delete($tracePath) } catch { }
    }

    $launchedAt = Get-Date
    $process = Start-Process -FilePath $java -ArgumentList $arguments -PassThru -WorkingDirectory $appDir

    # Wait until the trace contains both markers, then read the settled file once.
    # Polling the file while it is rewritten races with the application and reports
    # a mixture of two different points in time.
    $settled = $null
    $poll = [System.Diagnostics.Stopwatch]::StartNew()
    while ($poll.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Milliseconds 25
        if (Test-Path $tracePath) {
            $info = Get-Item $tracePath
            if ($info.LastWriteTime -ge $launchedAt.AddSeconds(-1)) {
                try {
                    $trace = Get-Content -Raw -Encoding UTF8 $tracePath | ConvertFrom-Json
                    $frame = $trace.marks | Where-Object { $_.phase -eq "first-frame" } | Select-Object -Last 1
                    $ui = $trace.marks | Where-Object { $_.phase -eq "ui-ready" } | Select-Object -Last 1
                    if ($frame -and $ui) { $settled = $trace; break }
                } catch { }
            }
        }
        if ($process.HasExited) { break }
    }

    $firstFrameMs = $null
    $uiReadyMs = $null
    if ($settled) {
        $frame = $settled.marks | Where-Object { $_.phase -eq "first-frame" } | Select-Object -Last 1
        $ui = $settled.marks | Where-Object { $_.phase -eq "ui-ready" } | Select-Object -Last 1
        if ($frame) { $firstFrameMs = [double]$frame.sinceProcessStartMs }
        if ($ui) { $uiReadyMs = [double]$ui.sinceProcessStartMs }
    }
    $seen = $null -ne $settled

    try { if (-not $process.HasExited) { $process.Kill() } } catch { }
    try { $process.WaitForExit(5000) | Out-Null } catch { }
    Stop-MihonProcesses
    Start-Sleep -Milliseconds 500

    return [pscustomobject]@{
        Run = $run
        Label = $label
        FirstFrameMs = if ($firstFrameMs) { [math]::Round($firstFrameMs) } else { $null }
        UiReadyMs = if ($uiReadyMs) { [math]::Round($uiReadyMs) } else { $null }
        Ok = $seen
    }
}

$results = @()
for ($i = 1; $i -le $Runs; $i++) {
    $results += Invoke-Variant $full $LabelA $i $OptionA
    Write-Host ("run {0} {1}: firstFrame={2} usable={3}" -f $i, $LabelA, $results[-1].FirstFrameMs, $results[-1].UiReadyMs)
    $results += Invoke-Variant $reduced $LabelB $i $OptionB
    Write-Host ("run {0} {1}: firstFrame={2} usable={3}" -f $i, $LabelB, $results[-1].FirstFrameMs, $results[-1].UiReadyMs)
}

function Get-Stats([object[]]$values) {
    $numbers = @($values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    if ($numbers.Count -eq 0) { return $null }
    $sorted = @($numbers | Sort-Object)
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
Write-Host "=== Interleaved classpath comparison ($RemoveJar) ==="
$results | Format-Table -AutoSize | Out-String | Write-Host

$statsA = Get-Stats @($results | Where-Object { $_.Label -eq $LabelA } | ForEach-Object { $_.FirstFrameMs })
$statsB = Get-Stats @($results | Where-Object { $_.Label -eq $LabelB } | ForEach-Object { $_.FirstFrameMs })
$usableA = Get-Stats @($results | Where-Object { $_.Label -eq $LabelA } | ForEach-Object { $_.UiReadyMs })
$usableB = Get-Stats @($results | Where-Object { $_.Label -eq $LabelB } | ForEach-Object { $_.UiReadyMs })

foreach ($pair in @(@{ Name = $LabelA; Stats = $statsA }, @{ Name = $LabelB; Stats = $statsB })) {
    if ($pair.Stats) {
        Write-Host ("{0} first frame : min={1}ms median={2}ms mean={3}ms (n={4})" -f `
                $pair.Name, $pair.Stats.Min, $pair.Stats.Median, $pair.Stats.Mean, $pair.Stats.Count)
    }
}
foreach ($pair in @(@{ Name = $LabelA; Stats = $usableA }, @{ Name = $LabelB; Stats = $usableB })) {
    if ($pair.Stats) {
        Write-Host ("{0} usable UI   : min={1}ms median={2}ms mean={3}ms (n={4})" -f `
                $pair.Name, $pair.Stats.Min, $pair.Stats.Median, $pair.Stats.Mean, $pair.Stats.Count)
    }
}
if ($usableA -and $usableB) {
    $delta = $usableA.Median - $usableB.Median
    Write-Host ""
    Write-Host ("Median usable interface improvement: {0} ms ({1} %)" -f $delta, [math]::Round(($delta / $usableA.Median) * 100, 1))
}

$results | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path (Join-Path $PSScriptRoot "..\app-desktop\tmp\startup-classpath-$RemoveJar.json")
