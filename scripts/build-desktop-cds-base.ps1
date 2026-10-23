param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop",
    [string]$JdkHome = "",
    [switch]$Verify
)

# Creates the base class-data-sharing archive for the packaged runtime image.
#
# A jlink image ships without a base archive, so the JVM parses and verifies the
# classes of the JDK itself on every start. The archive is bound to the module image
# and to the virtual machine of the runtime it belongs to, so it cannot be copied from
# another runtime and it cannot be produced with a launcher that lives outside the
# runtime: such a launcher would use the module image of its own JDK, and the runtime
# would reject the archive afterwards.
#
# The archive therefore has to be produced by a launcher inside the runtime image. The
# launcher of the JDK that built the image is copied into the image for that purpose,
# it then uses the module image and the virtual machine of the image, and the archive
# it writes matches the runtime.

$ErrorActionPreference = "Continue"

$runtime = Join-Path $DistPath "runtime"
if (-not (Test-Path $runtime)) { Write-Host "Missing runtime image: $runtime"; exit 1 }

if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome) { $JdkHome = Split-Path (Split-Path (Get-Command java -ErrorAction SilentlyContinue).Source) -Parent }
$sourceLauncher = if ($JdkHome) { Join-Path $JdkHome "bin\java.exe" } else { $null }
if (-not $sourceLauncher -or -not (Test-Path $sourceLauncher)) {
    Write-Host "No JDK launcher found; the base archive is skipped."
    exit 0
}

$serverDir = Join-Path $runtime "bin\server"
$archive = Join-Path $serverDir "classes.jsa"
if (-not (Test-Path $serverDir)) { New-Item -ItemType Directory -Force -Path $serverDir | Out-Null }

$runtimeLauncher = Join-Path $runtime "bin\java.exe"
$launcherWasCopied = -not (Test-Path $runtimeLauncher)
if ($launcherWasCopied) { Copy-Item $sourceLauncher $runtimeLauncher -Force }

try {
    if (Test-Path $archive) {
        # The published artifact is marked read-only, so the attribute has to go
        # before the old archive can be replaced.
        $existing = Get-Item $archive
        if ($existing.IsReadOnly) { $existing.IsReadOnly = $false }
        [System.IO.File]::Delete($archive)
    }

    Write-Host "Dumping the base class-data-sharing archive..."
    & $runtimeLauncher "-XX:SharedArchiveFile=$archive" -Xshare:dump -version 2>&1 |
        Where-Object { $_ -notmatch 'openjdk version|OpenJDK 64-Bit' } | ForEach-Object { Write-Host "  $_" }

    if (-not (Test-Path $archive)) {
        Write-Host "The base archive was not produced."
        exit 1
    }

    if ($Verify) {
        # An archive that the runtime rejects would only slow the start up down, so the
        # result is checked with the runtime itself before it is kept.
        $check = & $runtimeLauncher -Xshare:on -Xlog:cds -version 2>&1 | Out-String
        if ($check -notmatch "Opened archive") {
            Write-Host "The runtime does not accept the archive; it is removed again."
            [System.IO.File]::Delete($archive)
            exit 1
        }
    }

    Write-Host ("Base class-data-sharing archive: {0} ({1} MB)" -f $archive, [math]::Round((Get-Item $archive).Length / 1MB, 2))
} finally {
    if ($launcherWasCopied -and (Test-Path $runtimeLauncher)) { [System.IO.File]::Delete($runtimeLauncher) }
}
