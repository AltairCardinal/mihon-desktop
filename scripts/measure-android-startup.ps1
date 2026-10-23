param(
    [string]$Avd = "mihon-api36-task20",
    [string]$Adb = "D:\Android\Sdk\platform-tools\adb.exe",
    [string]$Apk = "D:\Github\mihon\app\build\outputs\apk\benchmark\app-x86_64-benchmark.apk",
    [string]$Package = "app.mihon.desktop.fork.benchmark",
    [string]$Activity = "eu.kanade.tachiyomi.ui.main.MainActivity",
    [int]$Runs = 10,
    [string]$OutputPath = "D:\Github\mihon\app-desktop\performance\android-startup.json"
)

# Measures the Android cold start of the shipped application on a running emulator.
#
# The platform's own `am start -W` reports TotalTime (start request -> first frame
# drawn) and WaitTime, which is the same signal the macrobenchmark reports as
# timeToInitialDisplay. The macrobenchmark itself cannot be used here because it
# needs the Microbenchmark helper from the Play Store and the available system
# images are `google_apis` without it.
#
# The app is force-stopped before every run, so each measurement starts from a cold
# process.

$ErrorActionPreference = "Continue"

if (-not (Test-Path $Apk)) { Write-Host "Missing APK: $Apk"; exit 1 }

Write-Host "Waiting for the device ..."
& $Adb wait-for-device | Out-Null
$boot = (& $Adb shell getprop sys.boot_completed 2>&1) -join ""
if ($boot.Trim() -ne "1") { Write-Host "Device is not booted (sys.boot_completed=$($boot.Trim()))"; exit 1 }

Write-Host "Installing $Apk ..."
& $Adb install -r -g $Apk 2>&1 | Select-Object -Last 1 | ForEach-Object { Write-Host "  $_" }

$results = @()
for ($i = 1; $i -le $Runs; $i++) {
    & $Adb shell "am force-stop $Package" | Out-Null
    Start-Sleep -Milliseconds 800
    $output = & $Adb shell "am start -W -n $Package/$Activity" 2>&1
    $total = 0
    $wait = 0
    $state = ""
    foreach ($line in $output) {
        $text = "$line"
        if ($text -match "LaunchState:\s*(\S+)") { $state = $matches[1] }
        if ($text -match "TotalTime:\s*(\d+)") { $total = [int]$matches[1] }
        if ($text -match "WaitTime:\s*(\d+)") { $wait = [int]$matches[1] }
    }
    if ($total -gt 0) {
        $results += [pscustomobject]@{ Run = $i; LaunchState = $state; TotalTimeMs = $total; WaitTimeMs = $wait }
        Write-Host ("  run {0}: {1} total={2} ms wait={3} ms" -f $i, $state, $total, $wait)
    } else {
        Write-Host ("  run {0}: FAILED" -f $i)
    }
}

if ($results.Count -eq 0) { Write-Host "No successful run."; exit 1 }

$totals = @($results | ForEach-Object { $_.TotalTimeMs } | Sort-Object)
$median = $totals[[math]::Floor($totals.Count / 2)]
$report = [pscustomobject]@{
    description = "Android cold start of the shipped application, measured with the platform's `am start -W` on an emulator. TotalTime is the start request until the first frame is drawn, the same signal the macrobenchmark calls timeToInitialDisplay."
    device = (& $Adb shell "getprop ro.product.model" 2>&1) -join ""
    api = (& $Adb shell "getprop ro.build.version.sdk" 2>&1) -join ""
    abi = (& $Adb shell "getprop ro.product.cpu.abi" 2>&1) -join ""
    renderer = (& $Adb shell "getprop ro.hardware.egl" 2>&1) -join ""
    buildVariant = "benchmark (release-like: minified, profileable, not debuggable)"
    runs = $results.Count
    coldStartTotalTimeMs = [pscustomobject]@{
        min = $totals[0]
        median = $median
        max = $totals[-1]
    }
    results = $results
}
New-Item -ItemType Directory -Force -Path (Split-Path $OutputPath -Parent) | Out-Null
$report | ConvertTo-Json -Depth 5 | Set-Content -Path $OutputPath -Encoding UTF8
Write-Host ""
Write-Host ("Android cold start: min={0} ms median={1} ms max={2} ms (n={3})" -f $totals[0], $median, $totals[-1], $results.Count)
Write-Host "Report: $OutputPath"
