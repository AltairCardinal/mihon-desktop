param(
    [string]$RepoRoot = "D:\Github\mihon",
    [string]$Label = "run",
    [string[]]$TestFilters = @()
)

# Runs the Desktop JVM module test suite and preserves the raw evidence of that run:
#   - the Gradle console log
#   - the JUnit XML result files
#
# The evidence directory is timestamped so a baseline run and a candidate run can be
# compared later with scripts/compare-test-baselines.ps1 without losing either log.

$ErrorActionPreference = "Continue"

$gradle = Join-Path $RepoRoot "gradlew.bat"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceRoot = Join-Path $RepoRoot "app-desktop\tmp\test-baselines"
$evidenceDir = Join-Path $evidenceRoot "$Label-$timestamp"
$xmlDir = Join-Path $evidenceDir "xml"
$consoleLog = Join-Path $evidenceDir "console.log"

New-Item -ItemType Directory -Force -Path $xmlDir | Out-Null

$gradleArgs = @("-p", $RepoRoot, ":app-desktop:jvmTest", "--console=plain")
foreach ($filter in $TestFilters) { $gradleArgs += @("--tests", $filter) }

& $gradle @gradleArgs *> $consoleLog
$exitCode = $LASTEXITCODE

$sourceXml = Join-Path $RepoRoot "app-desktop\build\test-results\jvmTest"
if (Test-Path $sourceXml) {
    Copy-Item -Path (Join-Path $sourceXml "TEST-*.xml") -Destination $xmlDir -Force
}

Write-Host "Gradle exit code: $exitCode"
Write-Host "Evidence directory: $evidenceDir"

$summary = Get-ChildItem $xmlDir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue |
    ForEach-Object {
        [xml]$doc = Get-Content $_.FullName -Raw
        [pscustomobject]@{
            Suite   = $doc.testsuite.name
            Tests   = [int]$doc.testsuite.tests
            Failures = [int]$doc.testsuite.failures
            Errors  = [int]$doc.testsuite.errors
        }
    }

Write-Host ""
Write-Host "=== suite summary ==="
$summary | Sort-Object Suite | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
Write-Host ("total tests: {0}  failures: {1}  errors: {2}" -f `
        ($summary | Measure-Object -Property Tests -Sum).Sum, `
        ($summary | Measure-Object -Property Failures -Sum).Sum, `
        ($summary | Measure-Object -Property Errors -Sum).Sum)

Write-Host ""
Write-Host "=== failing test cases ==="
Get-ChildItem $xmlDir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue | ForEach-Object {
    [xml]$doc = Get-Content $_.FullName -Raw
    foreach ($case in $doc.testsuite.testcase) {
        if ($case.failure -or $case.error) {
            Write-Host ("{0} :: {1}" -f $case.classname, $case.name)
        }
    }
}

Set-Content -Path (Join-Path $evidenceDir "exit-code.txt") -Value $exitCode -Encoding ASCII
Write-Host ""
Write-Host "Evidence written to $evidenceDir"
exit $exitCode
