param(
    [string]$RepoRoot = "D:\Github\mihon"
)

# Runs the Desktop JVM module suite plus the shared module suites and the format check.
# The console log is kept under a timestamped name so no run overwrites an earlier one.

$ErrorActionPreference = "Continue"

$gradle = Join-Path $RepoRoot "gradlew.bat"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = Join-Path $RepoRoot "app-desktop\tmp\logs"
$log = Join-Path $logDir "full-test-run-$timestamp.log"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

& $gradle -p $RepoRoot :app-desktop:jvmTest :domain:jvmTest :data:jvmTest spotlessCheck --console=plain *> $log
$code = $LASTEXITCODE

Write-Host "Gradle exit code: $code"
Write-Host "Log: $log"

Write-Host ""
Write-Host "=== failures ==="
Select-String -Path $log -Pattern "FAILED|tests completed" | ForEach-Object { Write-Host $_.Line }

Write-Host ""
Write-Host "=== tail ==="
Get-Content $log -Tail 12 | ForEach-Object { Write-Host $_ }

exit $code
