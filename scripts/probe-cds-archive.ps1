param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.13.04688a4-unpacked",
    [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot",
    [string]$ArchivePath = "D:\Github\mihon\app-desktop\tmp\app.jsa",
    [int]$Seconds = 18,
    [int]$CloseWaitSeconds = 60
)

# Produces a dynamic class-data-sharing archive of the desktop application.
#
# The shipped jlink runtime has no base CDS archive, so the JVM refuses to start with
# `-XX:ArchiveClassesAtExit` ("ArchiveClassesAtExit is unsupported when base CDS
# archive is not loaded"). The application is therefore started from the same
# classpath with a complete JDK, which has the base archive.
#
# The archive is written while the JVM shuts down, so the window has to be closed and
# the process has to terminate; both steps are verified, because a silently surviving
# process is the reason an archive never appears.

$ErrorActionPreference = "Continue"

$appDir = Join-Path $DistPath "app"
$cfg = Join-Path $appDir "Mihon Desktop.cfg"
if (-not (Test-Path $cfg)) { Write-Host "Missing launcher config: $cfg"; exit 1 }

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

if (Test-Path $ArchivePath) { Remove-Item $ArchivePath -Force }

$stdout = "D:\Github\mihon\app-desktop\tmp\logs\cds-stdout.txt"
$stderr = "D:\Github\mihon\app-desktop\tmp\logs\cds-stderr.txt"
$arguments = @("-XX:ArchiveClassesAtExit=$ArchivePath") + $options + @("-cp", ($classpath -join ';'), "mihon.desktop.MainKt")

Write-Host "Starting the application with -XX:ArchiveClassesAtExit ..."
$process = Start-Process -FilePath (Join-Path $JavaHome "bin\java.exe") -ArgumentList $arguments -PassThru `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WorkingDirectory $appDir

Start-Sleep -Seconds $Seconds

$closed = $false
foreach ($candidate in @(Get-Process -Name "java" -ErrorAction SilentlyContinue)) {
    try {
        $candidate.Refresh()
        if ($candidate.MainWindowHandle -ne 0 -and $candidate.MainWindowTitle -like "*Mihon*") {
            Write-Host ("  closing the window of pid {0} ('{1}')" -f $candidate.Id, $candidate.MainWindowTitle)
            [void]$candidate.CloseMainWindow()
            $closed = $true
        }
    } catch { }
}
if (-not $closed) { Write-Host "  no application window found" }

$deadline = (Get-Date).AddSeconds($CloseWaitSeconds)
$exited = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    if ($process.HasExited) { $exited = $true; break }
}
Write-Host "process exited: $exited"

Write-Host ""
Write-Host "=== JVM messages ==="
foreach ($file in @($stdout, $stderr)) {
    if (Test-Path $file) {
        Get-Content $file | Select-String -Pattern "archive|Archive|CDS" | ForEach-Object { Write-Host "  $_" }
    }
}

if (Test-Path $ArchivePath) {
    Write-Host ""
    Write-Host "Archive produced: $ArchivePath ($((Get-Item $ArchivePath).Length) bytes)"
    exit 0
}
Write-Host ""
Write-Host "Archive NOT produced: $ArchivePath"
exit 1
