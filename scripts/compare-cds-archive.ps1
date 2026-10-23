param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.13.04688a4-unpacked",
    [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot",
    [string]$ArchivePath = "D:\Github\mihon\app-desktop\tmp\app.jsa",
    [int]$Runs = 5
)

# Compares the desktop start-up with and without a dynamic class-data-sharing archive.
#
# The archive removes the class parsing and verification work from the start-up, which
# the start-up trace shows as the dominant cost of the first real user interface. The
# application is started from the packaged classpath with a complete JDK, because the
# shipped jlink runtime has no base CDS archive and refuses
# `-XX:SharedArchiveFile`.

$ErrorActionPreference = "Continue"

$appDir = Join-Path $DistPath "app"
$cfg = Join-Path $appDir "Mihon Desktop.cfg"
$trace = Join-Path $env:LOCALAPPDATA "Mihon\logs\startup-trace.json"

$classpath = New-Object System.Collections.Generic.List[string]
$options = New-Object System.Collections.Generic.List[string]
foreach ($line in [System.IO.File]::ReadAllLines($cfg)) {
    if ($line.StartsWith("app.classpath=")) {
        $classpath.Add($line.Substring("app.classpath=".Length).Replace('$APPDIR', $appDir))
    } elseif ($line.StartsWith("java-options=")) {
        $value = $line.Substring("java-options=".Length).Replace('$APPDIR', $appDir)
        if (-not $value.StartsWith("-splash:")) { $options.Add($value) }
    }
}

function Invoke-Startup([string[]]$extraOptions, [string]$label) {
    $arguments = $extraOptions + $options + @("-cp", ($classpath -join ';'), "mihon.desktop.MainKt")
    $process = Start-Process -FilePath (Join-Path $JavaHome "bin\java.exe") -ArgumentList $arguments -PassThru -WorkingDirectory $appDir
    $deadline = (Get-Date).AddSeconds(30)
    $marks = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 250
        if (Test-Path $trace) {
            $info = Get-Item $trace
            if ($info.LastWriteTime -ge $process.StartTime.AddSeconds(-1)) {
                try {
                    $content = Get-Content -Raw -Encoding UTF8 $trace | ConvertFrom-Json
                    if ($content.marks | Where-Object { $_.phase -eq "ui-ready" }) { $marks = $content; break }
                } catch { }
            }
        }
    }
    foreach ($candidate in @(Get-Process -Name "java" -ErrorAction SilentlyContinue)) {
        try {
            $candidate.Refresh()
            if ($candidate.MainWindowHandle -ne 0 -and $candidate.MainWindowTitle -like "*Mihon*") {
                [void]$candidate.CloseMainWindow()
            }
        } catch { }
    }
    Start-Sleep -Seconds 3
    if ($marks) {
        $frame = ($marks.marks | Where-Object { $_.phase -eq "first-frame" } | Select-Object -Last 1).sinceProcessStartMs
        $ready = ($marks.marks | Where-Object { $_.phase -eq "ui-ready" } | Select-Object -Last 1).sinceProcessStartMs
        Write-Host ("  {0}: firstFrame={1} ms uiReady={2} ms classes={3}" -f $label, $frame, $ready, $marks.loadedClassCount)
        return [pscustomobject]@{ FirstFrameMs = $frame; UiReadyMs = $ready }
    }
    Write-Host ("  {0}: no trace" -f $label)
    return $null
}

Write-Host "Measuring without the archive ..."
$without = @()
for ($i = 1; $i -le $Runs; $i++) { $result = Invoke-Startup @() "plain-$i"; if ($result) { $without += $result } }

Write-Host "Measuring with the archive ..."
$with = @()
for ($i = 1; $i -le $Runs; $i++) { $result = Invoke-Startup @("-XX:SharedArchiveFile=$ArchivePath") "cds-$i"; if ($result) { $with += $result } }

function Get-Median($values) {
    $sorted = @($values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    return $sorted[[math]::Floor($sorted.Count / 2)]
}

$plainFrame = Get-Median ($without | ForEach-Object { $_.FirstFrameMs })
$plainReady = Get-Median ($without | ForEach-Object { $_.UiReadyMs })
$cdsFrame = Get-Median ($with | ForEach-Object { $_.FirstFrameMs })
$cdsReady = Get-Median ($with | ForEach-Object { $_.UiReadyMs })

Write-Host ""
Write-Host ("without archive: firstFrame median={0} ms, uiReady median={1} ms (n={2})" -f $plainFrame, $plainReady, $without.Count)
Write-Host ("with archive   : firstFrame median={0} ms, uiReady median={1} ms (n={2})" -f $cdsFrame, $cdsReady, $with.Count)
if ($plainFrame -and $cdsFrame) {
    Write-Host ("firstFrame delta: {0} ms ({1} %)" -f ($cdsFrame - $plainFrame), [math]::Round((($cdsFrame - $plainFrame) / $plainFrame) * 100, 1))
    Write-Host ("uiReady   delta: {0} ms ({1} %)" -f ($cdsReady - $plainReady), [math]::Round((($cdsReady - $plainReady) / $plainReady) * 100, 1))
}
