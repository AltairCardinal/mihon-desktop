param(
    [Parameter(Mandatory = $true)]
    [string]$PathA,

    [Parameter(Mandatory = $true)]
    [string]$PathB,

    [string]$LabelA = "A",

    [string]$LabelB = "B",

    [int]$Runs = 6,

    [int]$SettleBudgetMs = 1500,

    [string]$ExtraJavaOptionA = "",

    [string]$ExtraJavaOptionB = ""
)

# Interleaved A/B measurement: alternating runs cancel out background load drift
# (disk contention from other applications, CPU frequency changes, cache state).

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$measureScript = Join-Path $PSScriptRoot "measure-desktop-startup.ps1"

function Get-FirstFrame([string]$distPath, [string]$label, [int]$index) {
    $raw = Join-Path $PSScriptRoot "..\app-desktop\tmp\startup-measure-$label.json"
    if (-not (Test-Path $raw)) { return $null }
    $data = Get-Content -Raw -Encoding UTF8 $raw | ConvertFrom-Json
    $run = $data | Where-Object { $_.Run -eq $index } | Select-Object -First 1
    if (-not $run) { return $null }
    return [pscustomobject]@{
        FirstFrameMs = $run.FirstFrameMs
        UiReadyMs = $run.UiReadyMs
        WindowVisibleMs = $run.WindowVisibleMs
        Trace = $run.Trace
    }
}

$results = @()

for ($i = 1; $i -le $Runs; $i++) {
    foreach ($side in @(@{ Label = $LabelA; Path = $PathA; Option = $ExtraJavaOptionA }, @{ Label = $LabelB; Path = $PathB; Option = $ExtraJavaOptionB })) {
        $measureArgs = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $measureScript,
            "-DistPath", $side.Path, "-Runs", 1, "-Label", "ab-$($side.Label)", "-SettleBudgetMs", $SettleBudgetMs
        )
        if ($side.Option) {
            # A JVM option starts with a dash. The launcher script reads such a value as
            # one of its own parameter names, so the option travels through the
            # environment variable the launcher script already supports.
            $env:MIHON_STARTUP_EXTRA_JAVA_OPTION = $side.Option
        } else {
            Remove-Item Env:\MIHON_STARTUP_EXTRA_JAVA_OPTION -ErrorAction SilentlyContinue
        }
        & powershell.exe @measureArgs | Out-Null
        $entry = Get-FirstFrame $side.Path "ab-$($side.Label)" 1
        $results += [pscustomobject]@{
            Run = $i
            Side = $side.Label
            FirstFrameMs = if ($entry) { $entry.FirstFrameMs } else { $null }
            UiReadyMs = if ($entry) { $entry.UiReadyMs } else { $null }
            WindowVisibleMs = if ($entry) { $entry.WindowVisibleMs } else { $null }
            Trace = if ($entry) { $entry.Trace } else { $null }
        }
        Write-Host ("run {0} {1}: firstFrame={2} ms  usable={3} ms  windowVisible={4} ms" -f `
                $i, $side.Label, $results[-1].FirstFrameMs, $results[-1].UiReadyMs, $results[-1].WindowVisibleMs)
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
Write-Host "=== Interleaved A/B startup comparison ==="
$results | Format-Table -AutoSize | Out-String | Write-Host

$statsA = Get-Stats @($results | Where-Object { $_.Side -eq $LabelA } | ForEach-Object { $_.FirstFrameMs })
$statsB = Get-Stats @($results | Where-Object { $_.Side -eq $LabelB } | ForEach-Object { $_.FirstFrameMs })
$usableA = Get-Stats @($results | Where-Object { $_.Side -eq $LabelA } | ForEach-Object { $_.UiReadyMs })
$usableB = Get-Stats @($results | Where-Object { $_.Side -eq $LabelB } | ForEach-Object { $_.UiReadyMs })
$windowA = Get-Stats @($results | Where-Object { $_.Side -eq $LabelA } | ForEach-Object { $_.WindowVisibleMs })
$windowB = Get-Stats @($results | Where-Object { $_.Side -eq $LabelB } | ForEach-Object { $_.WindowVisibleMs })

foreach ($pair in @(@{ Name = $LabelA; Stats = $statsA }, @{ Name = $LabelB; Stats = $statsB })) {
    if ($pair.Stats) {
        Write-Host ("{0} first frame : min={1}ms median={2}ms mean={3}ms (n={4})" -f `
                $pair.Name, $pair.Stats.Min, $pair.Stats.Median, $pair.Stats.Mean, $pair.Stats.Count)
    } else {
        Write-Host ("{0}: no successful runs" -f $pair.Name)
    }
}
foreach ($pair in @(@{ Name = $LabelA; Stats = $usableA }, @{ Name = $LabelB; Stats = $usableB })) {
    if ($pair.Stats) {
        Write-Host ("{0} usable UI   : min={1}ms median={2}ms mean={3}ms (n={4})" -f `
                $pair.Name, $pair.Stats.Min, $pair.Stats.Median, $pair.Stats.Mean, $pair.Stats.Count)
    }
}
foreach ($pair in @(@{ Name = $LabelA; Stats = $windowA }, @{ Name = $LabelB; Stats = $windowB })) {
    if ($pair.Stats) {
        Write-Host ("{0} window seen : min={1}ms median={2}ms mean={3}ms (n={4})" -f `
                $pair.Name, $pair.Stats.Min, $pair.Stats.Median, $pair.Stats.Mean, $pair.Stats.Count)
    }
}

if ($statsA -and $statsB) {
    $delta = $statsA.Median - $statsB.Median
    $percent = [math]::Round(($delta / $statsA.Median) * 100, 1)
    Write-Host ""
    Write-Host ("Median first frame improvement: {0} ms ({1} %)" -f $delta, $percent)
}
if ($usableA -and $usableB) {
    $delta = $usableA.Median - $usableB.Median
    $percent = [math]::Round(($delta / $usableA.Median) * 100, 1)
    Write-Host ("Median usable interface improvement: {0} ms ({1} %)" -f $delta, $percent)
}
if ($windowA -and $windowB) {
    $delta = $windowA.Median - $windowB.Median
    $percent = [math]::Round(($delta / $windowA.Median) * 100, 1)
    Write-Host ("Median window visible improvement: {0} ms ({1} %)" -f $delta, $percent)
}

$results | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path (Join-Path $PSScriptRoot "..\app-desktop\tmp\startup-ab-$LabelA-$LabelB.json")
