param(
    [string]$DistPath = "",
    [string]$BudgetPath = "",
    [int]$Runs = 0,
    [int]$WarmupRuns = 1,
    [switch]$Enforce,
    [string]$ReportPath = ""
)

# Measures the Windows desktop cold start and compares it against the committed
# performance budget.
#
# The measurement itself lives in measure-desktop-startup.ps1, which reads the phase
# trace the application writes. This script only turns the numbers into a verdict and
# a report that can be archived as a CI artifact.

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir

if (-not $DistPath) {
    $DistPath = Join-Path $repoRoot "app-desktop\tmp\mihon-dist\main\app\Mihon Desktop"
}
if (-not $BudgetPath) {
    $BudgetPath = Join-Path $repoRoot "app-desktop\performance\startup-budget.json"
}
if (-not $ReportPath) {
    $ReportPath = Join-Path $repoRoot "app-desktop\tmp\startup-performance-report.json"
}

$measureScript = Join-Path $scriptDir "measure-desktop-startup.ps1"
if (-not (Test-Path -LiteralPath $measureScript)) { throw "Measurement script not found: $measureScript" }
if (-not (Test-Path -LiteralPath (Join-Path $DistPath "Mihon Desktop.exe"))) {
    throw "Desktop distributable not found: $DistPath"
}
if (-not (Test-Path -LiteralPath $BudgetPath)) { throw "Startup budget not found: $BudgetPath" }

$budget = Get-Content -Raw -Encoding UTF8 $BudgetPath | ConvertFrom-Json
if ($Runs -le 0) { $Runs = [int]$budget.runs }
$tolerance = [double]$budget.tolerance

function Get-Median([object[]]$values) {
    $numbers = @($values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ } | Sort-Object)
    if ($numbers.Count -eq 0) { return $null }
    # [int] rounds half to even, so 1.5 becomes 2 and the median would be the maximum.
    $middle = [int][math]::Floor($numbers.Count / 2)
    if ($numbers.Count % 2 -eq 1) { return $numbers[$middle] }
    return ($numbers[$middle - 1] + $numbers[$middle]) / 2
}

Write-Host ""
Write-Host "=== Desktop startup measurement ($Runs runs) ==="

# The first start creates the profile and the database, which is slower than a start
# against an existing profile. Those runs are discarded so the numbers describe the
# steady state the user actually experiences.
for ($i = 1; $i -le $WarmupRuns; $i++) {
    Write-Host ("  warmup {0}" -f $i)
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $measureScript `
        -DistPath $DistPath -Runs 1 -Label "budget-warmup-$i" -SettleBudgetMs 1000 | Out-Null
}

$frames = @()
$usable = @()
$failed = 0
for ($i = 1; $i -le $Runs; $i++) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $measureScript `
        -DistPath $DistPath -Runs 1 -Label "budget-$i" -SettleBudgetMs 1000 | Out-Null
    $raw = Join-Path $scriptDir "..\app-desktop\tmp\startup-measure-budget-$i.json"
    if (-not (Test-Path $raw)) {
        Write-Host ("  run {0}: no measurement" -f $i)
        $failed++
        continue
    }
    $data = Get-Content -Raw -Encoding UTF8 $raw | ConvertFrom-Json
    $run = $data | Select-Object -First 1
    if (-not $run -or $null -eq $run.FirstFrameMs) {
        Write-Host ("  run {0}: the application never produced a frame" -f $i)
        $failed++
        continue
    }
    if ($null -eq $run.UiReadyMs) {
        Write-Host ("  run {0}: the real user interface never became ready" -f $i)
        $failed++
        continue
    }
    $frames += [double]$run.FirstFrameMs
    $usable += [double]$run.UiReadyMs
    Write-Host ("  run {0}: first frame {1} ms, usable interface {2} ms" -f $i, $run.FirstFrameMs, $run.UiReadyMs)
}

$frameMedian = Get-Median $frames
$usableMedian = Get-Median $usable
$frameLimit = [math]::Round([double]$budget.firstFrameMs * $tolerance)
$usableLimit = [math]::Round([double]$budget.usableInterfaceMs * $tolerance)

$verdict = "unknown"
$breaches = @()

if ($null -ne $frameMedian) {
    if ($frameMedian -gt $frameLimit) {
        $breaches += "first frame ${frameMedian} ms exceeds the budget of $frameLimit ms"
    }
    if ($null -ne $usableMedian -and $usableMedian -gt $usableLimit) {
        $breaches += "usable interface ${usableMedian} ms exceeds the budget of $usableLimit ms"
    }
    if ($breaches.Count -eq 0) {
        $verdict = "pass"
    } else {
        $verdict = "fail"
    }
} elseif ($failed -gt 0) {
    $verdict = "fail"
    $breaches += "$failed of $Runs runs produced no measurement"
}

$report = [pscustomobject]@{
    measuredAt = (Get-Date).ToString("o")
    distributable = $DistPath
    runs = $Runs
    failedRuns = $failed
    firstFrameMs = $frameMedian
    usableInterfaceMs = $usableMedian
    budget = [pscustomobject]@{
        firstFrameMs = [double]$budget.firstFrameMs
        usableInterfaceMs = [double]$budget.usableInterfaceMs
        tolerance = $tolerance
        firstFrameLimitMs = $frameLimit
        usableInterfaceLimitMs = $usableLimit
    }
    verdict = $verdict
    breaches = $breaches
}
$report | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -Path $ReportPath

Write-Host ""
Write-Host ("first frame       : {0} ms (budget {1} ms, limit {2} ms)" -f `
    $(if ($null -ne $frameMedian) { $frameMedian } else { "n/a" }), $budget.firstFrameMs, $frameLimit)
Write-Host ("usable interface  : {0} ms (budget {1} ms, limit {2} ms)" -f `
    $(if ($null -ne $usableMedian) { $usableMedian } else { "n/a" }), $budget.usableInterfaceMs, $usableLimit)
Write-Host ("verdict           : {0}" -f $verdict)
Write-Host ("report            : {0}" -f $ReportPath)

if ($breaches.Count -gt 0) {
    foreach ($breach in $breaches) { Write-Host ("  ! {0}" -f $breach) }
    if ($Enforce) {
        Write-Host ""
        Write-Host "The startup performance budget was exceeded."
        exit 1
    }
    Write-Host ""
    Write-Host "The startup performance budget was exceeded; reported without failing the run."
}
exit 0
