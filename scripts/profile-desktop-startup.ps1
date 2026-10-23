param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.11.04688a4-unpacked",
    [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot",
    [string]$OutputDir = "D:\Github\mihon\app-desktop\tmp\profile",
    [int]$Seconds = 20
)

# Profiles one Desktop startup with JFR.
#
# The packaged jlink runtime does not contain the jdk.jfr module, so the recording
# cannot be enabled inside the shipped launcher. The application is therefore started
# from the same classpath with a full JDK, which keeps the production code path and
# only changes the runtime image.
#
# The recording is analysed for:
#   * jdk.NativeMethodSample - where the AWT/Compose thread sits in native code (Skia, D3D)
#   * jdk.ExecutionSample    - the Java frames that dominate the startup
#   * jdk.FileRead           - whether the system font files are scanned during startup
#   * jdk.ClassLoad          - how the class loading is spread over the startup

$ErrorActionPreference = "Continue"

$appDir = Join-Path $DistPath "app"
$cfg = Join-Path $appDir "Mihon Desktop.cfg"
if (-not (Test-Path $cfg)) { Write-Host "Missing launcher config: $cfg"; exit 1 }

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$jfr = Join-Path $OutputDir "startup.jfr"
if (Test-Path $jfr) { Remove-Item $jfr -Force }

$classpath = New-Object System.Collections.Generic.List[string]
$options = New-Object System.Collections.Generic.List[string]
foreach ($line in [System.IO.File]::ReadAllLines($cfg)) {
    if ($line.StartsWith("app.classpath=")) {
        $classpath.Add($line.Substring("app.classpath=".Length).Replace('$APPDIR', $appDir))
    } elseif ($line.StartsWith("java-options=")) {
        $value = $line.Substring("java-options=".Length).Replace('$APPDIR', $appDir)
        if ($value.StartsWith("-splash:")) { continue }
        $options.Add($value)
    }
}
Write-Host "classpath entries: $($classpath.Count), java options: $($options.Count)"

$java = Join-Path $JavaHome "bin\java.exe"
$jfrTool = Join-Path $JavaHome "bin\jfr.exe"

$arguments = @("-XX:StartFlightRecording=filename=$($jfr -replace '\\','/'),settings=profile,dumponexit=true")
$arguments += $options
$arguments += @("-cp", ($classpath -join ';'), "mihon.desktop.MainKt")

Write-Host "Starting application with JFR enabled ..."
$process = Start-Process -FilePath $java -ArgumentList $arguments -PassThru -WorkingDirectory $appDir
Start-Sleep -Seconds $Seconds

$closed = $false
try {
    $process.Refresh()
    if (-not $process.HasExited) {
        [void]$process.CloseMainWindow()
        $closed = $true
    }
} catch { }

Start-Sleep -Seconds 6
$process.Refresh()
if (-not $process.HasExited) {
    Write-Host "Forcing termination."
    Stop-Process -Id $process.Id -Force
}
Write-Host "graceful close requested: $closed"

if (-not (Test-Path $jfr)) { Write-Host "No JFR recording produced."; exit 1 }
Write-Host "Recording: $jfr ($((Get-Item $jfr).Length) bytes)"

Write-Host ""
Write-Host "=== recording summary ==="
& $jfrTool summary $jfr

Write-Host ""
Write-Host "=== top Java frames (ExecutionSample) ==="
& $jfrTool print --events jdk.ExecutionSample --stack-depth 1 $jfr |
    Select-String -Pattern "^\s+[a-z]" |
    ForEach-Object { $_.Line.Trim() } |
    Group-Object | Sort-Object Count -Descending | Select-Object -First 20 |
    ForEach-Object { Write-Host ("{0,5}  {1}" -f $_.Count, $_.Name) }

Write-Host ""
Write-Host "=== native method samples (top frames) ==="
& $jfrTool print --events jdk.NativeMethodSample --stack-depth 1 $jfr |
    Select-String -Pattern "^\s+[a-z]" |
    ForEach-Object { $_.Line.Trim() } |
    Group-Object | Sort-Object Count -Descending | Select-Object -First 15 |
    ForEach-Object { Write-Host ("{0,5}  {1}" -f $_.Count, $_.Name) }

Write-Host ""
Write-Host "=== file reads by directory ==="
& $jfrTool print --events jdk.FileRead $jfr |
    Select-String -Pattern "path\s*=\s*" |
    ForEach-Object { ($_ -split "path\s*=\s*", 2)[1] } |
    ForEach-Object { if ($_ -match "^([A-Za-z]:\\[^\\]+\\[^\\]+)") { $matches[1] } else { "(other)" } } |
    Group-Object | Sort-Object Count -Descending | Select-Object -First 15 |
    ForEach-Object { Write-Host ("{0,6}  {1}" -f $_.Count, $_.Name) }

Write-Host ""
Write-Host "=== class load timeline (per 250 ms bucket) ==="
& $jfrTool print --events jdk.ClassLoad $jfr |
    Select-String -Pattern "startTime\s*=\s*" |
    ForEach-Object {
        if ($_ -match "(\d{2}:\d{2}:\d{2})\.(\d+)") {
            $seconds = [int]$matches[1].Split(':')[2]
            $frac = [double]$matches[2]
            [math]::Floor($seconds + $frac / 4) * 0.25
        }
    } |
    Group-Object | Sort-Object { [double]$_.Name } |
    ForEach-Object { Write-Host ("{0,6}  t={1}s" -f $_.Count, $_.Name) }
