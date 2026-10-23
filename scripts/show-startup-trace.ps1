param(
    [string[]]$TracePaths
)

$ErrorActionPreference = "Stop"

foreach ($path in $TracePaths) {
    Write-Host ""
    Write-Host "=== $path ==="
    $trace = Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
    $previous = $trace.processStartEpochMs
    foreach ($mark in $trace.marks) {
        Write-Host ("  {0,-22} +{1,6} ms   (at {2} ms since process start)" -f $mark.phase, ($mark.epochMs - $previous), $mark.sinceProcessStartMs)
        $previous = $mark.epochMs
    }
    Write-Host ("  process -> jvm start: {0} ms" -f ($trace.jvmStartEpochMs - $trace.processStartEpochMs))
}
