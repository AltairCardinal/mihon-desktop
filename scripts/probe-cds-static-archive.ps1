param(
    [Parameter(Mandatory = $true)][string]$DistPath,
    [Parameter(Mandatory = $true)][string]$ClassList,
    [Parameter(Mandatory = $true)][string]$ArchivePath,
    [string]$JdkHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot",
    [switch]$WithoutBaseArchive
)

# Produces a static class-data-sharing archive for the classes of the application.
#
# The archive holds the classes the start up loads, so the JVM does not have to parse
# and verify them. It is produced from a class list instead of a recorded session, so
# the build can create it without starting the application.
#
# The archive is bound to the module image and the virtual machine of the runtime, so
# it has to be produced by a launcher that lives inside the runtime image.

$ErrorActionPreference = "Continue"

$runtime = Join-Path $DistPath "runtime"
$appDir = Join-Path $DistPath "app"
$cfg = Join-Path $appDir "Mihon Desktop.cfg"
if (-not (Test-Path $cfg)) { Write-Host "Missing launcher configuration: $cfg"; exit 1 }

$classpath = New-Object System.Collections.Generic.List[string]
foreach ($line in [System.IO.File]::ReadAllLines($cfg)) {
    if ($line.StartsWith("app.classpath=")) {
        $classpath.Add($line.Substring("app.classpath=".Length).Replace('$APPDIR', $appDir))
    }
}

$runtimeLauncher = Join-Path $runtime "bin\java.exe"
$sourceLauncher = Join-Path $JdkHome "bin\java.exe"
if (-not (Test-Path $runtimeLauncher)) { Copy-Item $sourceLauncher $runtimeLauncher -Force }
if (Test-Path $ArchivePath) { [System.IO.File]::Delete($ArchivePath) }

# The dump needs a base archive to build on, but the base archive is only needed for
# the dump itself when the archive is created from a class list.
$baseArchive = Join-Path $runtime "bin\server\classes.jsa"
$baseStash = "$baseArchive.stashed"
if ($WithoutBaseArchive -and (Test-Path $baseArchive)) {
    Move-Item $baseArchive $baseStash -Force
    Write-Host "The base archive was moved away for the dump."
}

Write-Host "Dumping the static class-data-sharing archive..."
& $runtimeLauncher `
    "-XX:SharedArchiveFile=$ArchivePath" `
    -Xshare:dump `
    "-XX:SharedClassListFile=$ClassList" `
    -cp ($classpath -join ';') 2>&1 |
    Where-Object { $_ -notmatch 'openjdk version|OpenJDK 64-Bit' } |
    Select-Object -First 12 | ForEach-Object { Write-Host "  $_" }

if (-not (Test-Path $ArchivePath)) {
    Write-Host "The static archive was not produced."
    if (Test-Path $baseStash) { Move-Item $baseStash $baseArchive -Force }
    exit 1
}
if (Test-Path $baseStash) { Move-Item $baseStash $baseArchive -Force }
Write-Host ("Static class-data-sharing archive: {0} ({1} MB)" -f $ArchivePath, [math]::Round((Get-Item $ArchivePath).Length / 1MB, 2))
