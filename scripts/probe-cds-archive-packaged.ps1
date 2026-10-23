param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.13.04688a4-unpacked",
    [string]$ArchivePath = "D:\Github\mihon\app-desktop\tmp\app-jlink.jsa",
    [int]$Seconds = 16,
    [int]$CloseWaitSeconds = 60
)

# Produces a dynamic class-data-sharing archive with the packaged runtime.
#
# The runtime image needs a base archive, otherwise the JVM refuses
# `-XX:ArchiveClassesAtExit` ("ArchiveClassesAtExit is unsupported when base CDS archive
# is not loaded"). The base archive of the reference JDK is copied into the runtime
# image first.
#
# The launcher configuration is patched for the run and restored afterwards.

$ErrorActionPreference = "Continue"

$exe = Join-Path $DistPath "Mihon Desktop.exe"
$cfg = Join-Path $DistPath "app\Mihon Desktop.cfg"
$runtime = Join-Path $DistPath "runtime"
if (-not (Test-Path $exe)) { Write-Host "Missing executable: $exe"; exit 1 }

$baseArchiveSource = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\server\classes.jsa"
$baseArchiveTarget = Join-Path $runtime "bin\server\classes.jsa"
if (-not (Test-Path $baseArchiveTarget)) {
    if (-not (Test-Path $baseArchiveSource)) { Write-Host "Missing base archive source: $baseArchiveSource"; exit 1 }
    Copy-Item $baseArchiveSource $baseArchiveTarget -Force
    Write-Host "base archive installed: $baseArchiveTarget"
}

$backup = "$cfg.cds.bak"
Copy-Item $cfg $backup -Force
try {
    $text = [System.IO.File]::ReadAllText($cfg)
    $option = "java-options=-XX:ArchiveClassesAtExit=$ArchivePath"
    [System.IO.File]::WriteAllText($cfg, "$text`r`n$option`r`n", (New-Object System.Text.UTF8Encoding($false)))

    if (Test-Path $ArchivePath) { Remove-Item $ArchivePath -Force }

    Write-Host "Starting the packaged application with -XX:ArchiveClassesAtExit ..."
    Start-Process -FilePath $exe | Out-Null
    Start-Sleep -Seconds $Seconds

    $closed = $false
    foreach ($candidate in @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue)) {
        try {
            $candidate.Refresh()
            if ($candidate.MainWindowHandle -ne 0) {
                Write-Host ("  closing the window of pid {0}" -f $candidate.Id)
                [void]$candidate.CloseMainWindow()
                $closed = $true
            }
        } catch { }
    }
    if (-not $closed) { Write-Host "  no window found to close" }

    $deadline = (Get-Date).AddSeconds($CloseWaitSeconds)
    $exited = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        if (@(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue).Count -eq 0) { $exited = $true; break }
    }
    Write-Host "process exited: $exited"
} finally {
    Copy-Item $backup $cfg -Force
    Write-Host "launcher configuration restored"
}

if (Test-Path $ArchivePath) {
    Write-Host "Archive produced: $ArchivePath ($((Get-Item $ArchivePath).Length) bytes)"
    exit 0
}
Write-Host "Archive NOT produced: $ArchivePath"
exit 1
