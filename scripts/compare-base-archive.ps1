param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.13.04688a4-unpacked",
    [string]$BaseArchive = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\server\classes.jsa",
    [int]$Runs = 4
)

# Interleaved A/B of the packaged application with and without the base class-data
# sharing archive in the runtime image.
#
# The archive holds the system classes of the reference JDK. A trimmed jlink image
# has fewer modules, so the archive can be rejected; the measurement shows whether it is
# accepted and what it is worth.

$ErrorActionPreference = "Continue"

$measure = Join-Path $PSScriptRoot "measure-desktop-startup.ps1"
$target = Join-Path $DistPath "runtime\bin\server\classes.jsa"
$stashed = "$target.base-archive-stash"

function Get-Measure([string]$label) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $measure -DistPath $DistPath -Runs 1 -Label $label | Out-Null
    $raw = Join-Path $PSScriptRoot "..\app-desktop\tmp\startup-measure-$label.json"
    if (-not (Test-Path $raw)) { return $null }
    $data = Get-Content -Raw -Encoding UTF8 $raw | ConvertFrom-Json
    $run = $data | Where-Object { $_.Run -eq 1 } | Select-Object -First 1
    if (-not $run) { return $null }
    return [pscustomobject]@{ FirstFrameMs = $run.FirstFrameMs; UiReadyMs = $run.UiReadyMs }
}

$withBase = @()
$withoutBase = @()

for ($i = 1; $i -le $Runs; $i++) {
    if (Test-Path $target) { Move-Item $target $stashed -Force }
    $result = Get-Measure "base-off-$i"
    if ($result) { $withoutBase += $result; Write-Host ("  base-off-$i : firstFrame={0} uiReady={1}" -f $result.FirstFrameMs, $result.UiReadyMs) }

    if (Test-Path $stashed) { Move-Item $stashed $target -Force }
    $result = Get-Measure "base-on-$i"
    if ($result) { $withBase += $result; Write-Host ("  base-on-$i  : firstFrame={0} uiReady={1}" -f $result.FirstFrameMs, $result.UiReadyMs) }
}

if (Test-Path $stashed) { Move-Item $stashed $target -Force }

function Get-Median($values) {
    $sorted = @($values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    return $sorted[[math]::Floor($sorted.Count / 2)]
}

$offFrame = Get-Median ($withoutBase | ForEach-Object { $_.FirstFrameMs })
$offReady = Get-Median ($withoutBase | ForEach-Object { $_.UiReadyMs })
$onFrame = Get-Median ($withBase | ForEach-Object { $_.FirstFrameMs })
$onReady = Get-Median ($withBase | ForEach-Object { $_.UiReadyMs })

Write-Host ""
Write-Host ("without base archive: firstFrame median={0} ms, uiReady median={1} ms (n={2})" -f $offFrame, $offReady, $withoutBase.Count)
Write-Host ("with base archive   : firstFrame median={0} ms, uiReady median={1} ms (n={2})" -f $onFrame, $onReady, $withBase.Count)
if ($offFrame -and $onFrame) {
    Write-Host ("firstFrame delta: {0} ms ({1} %)" -f ($onFrame - $offFrame), [math]::Round((($onFrame - $offFrame) / $offFrame) * 100, 1))
    Write-Host ("uiReady   delta: {0} ms ({1} %)" -f ($onReady - $offReady), [math]::Round((($onReady - $offReady) / $offReady) * 100, 1))
}
