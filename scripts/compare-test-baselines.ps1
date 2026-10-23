param(
    [Parameter(Mandatory = $true)][string]$BaselineDir,
    [Parameter(Mandatory = $true)][string]$CandidateDir
)

# Compares two preserved Desktop module test runs (see scripts/run-desktop-module-tests.ps1)
# case by case and reports the regression delta:
#   - NEW      : failing in the candidate, passing or absent in the baseline
#   - FIXED    : failing in the baseline, passing in the candidate
#   - COMMON   : failing in both runs
#
# The exit code is 1 when the candidate introduces any new failing case, so the
# comparison can be used as a regression gate.

$ErrorActionPreference = "Continue"

function Get-CaseMap {
    param([string]$Directory)

    $map = @{}
    $xmlDir = Join-Path $Directory "xml"
    if (-not (Test-Path $xmlDir)) {
        Write-Host "Missing XML results in $xmlDir"
        return $map
    }
    foreach ($file in Get-ChildItem $xmlDir -Filter "TEST-*.xml") {
        [xml]$doc = Get-Content $file.FullName -Raw
        foreach ($case in $doc.testsuite.testcase) {
            $key = "$($case.classname) :: $($case.name)"
            $map[$key] = [bool]($case.failure -or $case.error)
        }
    }
    return $map
}

$baseline = Get-CaseMap -Directory $BaselineDir
$candidate = Get-CaseMap -Directory $CandidateDir

Write-Host "baseline cases : $($baseline.Count)"
Write-Host "candidate cases: $($candidate.Count)"

$baselineFailing = @($baseline.Keys | Where-Object { $baseline[$_] })
$candidateFailing = @($candidate.Keys | Where-Object { $candidate[$_] })

$new = @($candidateFailing | Where-Object { -not $baseline.ContainsKey($_) -or -not $baseline[$_] })
$fixed = @($baselineFailing | Where-Object { -not $candidate.ContainsKey($_) -or -not $candidate[$_] })
$common = @($candidateFailing | Where-Object { $baseline.ContainsKey($_) -and $baseline[$_] })
$missing = @($baseline.Keys | Where-Object { -not $candidate.ContainsKey($_) })
$added = @($candidate.Keys | Where-Object { -not $baseline.ContainsKey($_) })

Write-Host ""
Write-Host "=== regression delta ==="
Write-Host ("baseline failing : {0}" -f $baselineFailing.Count)
Write-Host ("candidate failing: {0}" -f $candidateFailing.Count)
Write-Host ("NEW failures     : {0}" -f $new.Count)
Write-Host ("FIXED failures   : {0}" -f $fixed.Count)
Write-Host ("COMMON failures  : {0}" -f $common.Count)
Write-Host ("cases only in baseline : {0}" -f $missing.Count)
Write-Host ("cases only in candidate: {0}" -f $added.Count)

if ($new.Count -gt 0) {
    Write-Host ""
    Write-Host "=== NEW failures (regressions) ==="
    $new | Sort-Object | ForEach-Object { Write-Host "  $_" }
}
if ($fixed.Count -gt 0) {
    Write-Host ""
    Write-Host "=== FIXED failures ==="
    $fixed | Sort-Object | ForEach-Object { Write-Host "  $_" }
}
if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "=== cases only in baseline (not executed in candidate) ==="
    $missing | Sort-Object | ForEach-Object { Write-Host "  $_" }
}

if ($new.Count -gt 0) { exit 1 }
exit 0
