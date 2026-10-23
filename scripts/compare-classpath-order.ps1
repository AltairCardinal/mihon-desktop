param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop",
    [int]$Runs = 4
)

# A/B comparison of the original launcher classpath order against the reordered one.
# The two variants alternate so that background load affects both equally.

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$measureScript = Join-Path $scriptDir "measure-desktop-startup.ps1"
$reorderScript = Join-Path $scriptDir "reorder-desktop-classpath.ps1"
$cfg = Join-Path $DistPath "app\Mihon Desktop.cfg"
$originalCopy = "$cfg.classpath-original"

# The original configuration is captured once so both variants start from the same base.
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $reorderScript -DistPath $DistPath -ResetOriginal | Out-Null
$originalContent = Get-Content -Raw -Encoding UTF8 $originalCopy

function Write-Utf8NoBom([string]$path, [string]$content) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $encoding)
}

function Read-LastFrame([string]$label) {
    $raw = Join-Path $scriptDir "..\app-desktop\tmp\startup-measure-$label.json"
    if (-not (Test-Path $raw)) { return $null }
    $data = Get-Content -Raw -Encoding UTF8 $raw | ConvertFrom-Json
    $run = $data | Select-Object -First 1
    if (-not $run -or $null -eq $run.FirstFrameMs) { return $null }
    return [pscustomobject]@{
        FirstFrameMs = $run.FirstFrameMs
        UiReadyMs = $run.UiReadyMs
    }
}

$results = @()

try {
    for ($i = 1; $i -le $Runs; $i++) {
        Write-Utf8NoBom $cfg $originalContent
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $measureScript -DistPath $DistPath -Runs 1 -Label "cp-original" | Out-Null
        $original = Read-LastFrame "cp-original"

        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $reorderScript -DistPath $DistPath | Out-Null
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $measureScript -DistPath $DistPath -Runs 1 -Label "cp-reordered" | Out-Null
        $reordered = Read-LastFrame "cp-reordered"

        $results += [pscustomobject]@{
            Run = $i
            OriginalFrame = if ($original) { $original.FirstFrameMs } else { $null }
            OriginalUi = if ($original) { $original.UiReadyMs } else { $null }
            ReorderedFrame = if ($reordered) { $reordered.FirstFrameMs } else { $null }
            ReorderedUi = if ($reordered) { $reordered.UiReadyMs } else { $null }
        }
        Write-Host ("run {0}: original frame={1} ui={2} | reordered frame={3} ui={4}" -f `
                $i, $results[-1].OriginalFrame, $results[-1].OriginalUi, $results[-1].ReorderedFrame, $results[-1].ReorderedUi)
    }
}
finally {
    Write-Utf8NoBom $cfg $originalContent
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
        Mean = [math]::Round(($numbers | Measure-Object -Average).Average)
    }
}

Write-Host ""
Write-Host "=== Classpath order comparison ==="
$results | Format-Table -AutoSize | Out-String | Write-Host
foreach ($pair in @(
    @{ Name = "original "; Frame = @($results | ForEach-Object { $_.OriginalFrame }); Ui = @($results | ForEach-Object { $_.OriginalUi }) },
    @{ Name = "reordered"; Frame = @($results | ForEach-Object { $_.ReorderedFrame }); Ui = @($results | ForEach-Object { $_.ReorderedUi }) }
)) {
    $frameStats = Get-Stats $pair.Frame
    $uiStats = Get-Stats $pair.Ui
    if ($frameStats) {
        Write-Host ("{0} first frame: min={1}ms median={2}ms mean={3}ms (n={4})" -f `
                $pair.Name, $frameStats.Min, $frameStats.Median, $frameStats.Mean, $frameStats.Count)
    }
    if ($uiStats) {
        Write-Host ("{0} usable UI  : min={1}ms median={2}ms mean={3}ms (n={4})" -f `
                $pair.Name, $uiStats.Min, $uiStats.Median, $uiStats.Mean, $uiStats.Count)
    }
}
$originalFrame = Get-Stats @($results | ForEach-Object { $_.OriginalFrame })
$reorderedFrame = Get-Stats @($results | ForEach-Object { $_.ReorderedFrame })
if ($originalFrame -and $reorderedFrame) {
    $delta = $originalFrame.Median - $reorderedFrame.Median
    Write-Host ""
    Write-Host ("Median first frame improvement from the classpath order: {0} ms ({1} %)" -f `
            $delta, [math]::Round(($delta / $originalFrame.Median) * 100, 1))
}
