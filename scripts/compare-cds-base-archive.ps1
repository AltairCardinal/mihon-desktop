param(
    [Parameter(Mandatory = $true)][string]$DistPath,
    [int]$Runs = 6,
    [int]$SettleBudgetMs = 500
)

# Compares the start-up with and without the base class-data-sharing archive of the
# packaged runtime.
#
# A jlink image ships without a base archive, so the JVM parses and verifies the
# classes of the JDK itself on every start. The archive has to be produced by the very
# runtime image it belongs to, because it is bound to the module image of that image.
#
# The archive is moved in and out between the runs of the two sides, so both sides
# measure the same build on the same machine.

$ErrorActionPreference = "Continue"

$measureScript = Join-Path $PSScriptRoot "measure-desktop-startup.ps1"
$archive = Join-Path $DistPath "runtime\bin\server\classes.jsa"
$stash = Join-Path $PSScriptRoot "..\app-desktop\tmp\classes-base-stash.jsa"

if (-not (Test-Path $archive)) { throw "The base archive is not installed: $archive" }

function Get-WindowMedian([object[]]$rows, [string]$side) {
    $values = @($rows | Where-Object { $_.Side -eq $side } | ForEach-Object { [double]$_.Window } | Sort-Object)
    if ($values.Count -eq 0) { return $null }
    return $values[[int][math]::Floor($values.Count / 2)]
}

$rows = @()
for ($i = 1; $i -le $Runs; $i++) {
    foreach ($side in @("nobase", "base")) {
        if ($side -eq "base") {
            Copy-Item $stash $archive -Force
        } else {
            Move-Item $archive $stash -Force
        }

        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $measureScript `
            -DistPath $DistPath -Runs 1 -Label "cdsbase-$side" -SettleBudgetMs $SettleBudgetMs | Out-Null

        $raw = Join-Path $PSScriptRoot "..\app-desktop\tmp\startup-measure-cdsbase-$side.json"
        if (Test-Path $raw) {
            $data = Get-Content $raw -Raw | ConvertFrom-Json
            $run = $data | Where-Object { $_.Run -eq 1 } | Select-Object -First 1
            $rows += [pscustomobject]@{
                Side = $side
                FirstFrameMs = [double]$run.FirstFrameMs
                UiReadyMs = [double]$run.UiReadyMs
                WindowVisibleMs = [double]$run.WindowVisibleMs
            }
            Write-Host ("run {0} {1}: firstFrame={2} ms  usable={3} ms  windowVisible={4} ms" -f `
                    $i, $side, $run.FirstFrameMs, $run.UiReadyMs, $run.WindowVisibleMs)
        } else {
            Write-Host ("run {0} {1}: no result" -f $i, $side)
        }
    }
}

Copy-Item $stash $archive -Force
if ((Get-Item $archive).IsReadOnly) { (Get-Item $archive).IsReadOnly = $false }
Write-Host ""
Write-Host "=== Base CDS archive comparison ==="
$rows | Format-Table -AutoSize | Out-String | Write-Host

foreach ($metric in @("FirstFrameMs", "UiReadyMs", "WindowVisibleMs")) {
    $without = Get-WindowMedian $rows "nobase"
    $with = Get-WindowMedian $rows "base"
    if ($without -and $with) {
        Write-Host ("{0,-16} without={1} ms  with={2} ms  delta={3} ms ({4} %)" -f `
                $metric, $without, $with, ($with - $without), [math]::Round((($with - $without) / $without) * 100, 1))
    }
}
