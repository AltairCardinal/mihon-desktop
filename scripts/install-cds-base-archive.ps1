param(
    [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot",
    [string]$Target = "D:\Github\mihon\app-desktop\tmp\bench\H\runtime"
)

# Installs the module image and the base class-data-sharing archive of a complete JDK
# into a packaged runtime.
#
# The base archive is bound to the jimage it was built from, so a jlink image rejects
# it. Using the module image of the JDK itself makes the archive applicable again, which
# is the only way to produce an application archive for a jpackage application with the
# toolchain at hand.

$ErrorActionPreference = "Stop"

if (-not (Test-Path (Join-Path $JavaHome "lib\modules"))) { Write-Host "Missing JDK image"; exit 1 }
if (-not (Test-Path $Target)) { Write-Host "Missing target runtime: $Target"; exit 1 }

Copy-Item (Join-Path $JavaHome "lib\modules") (Join-Path $Target "lib\modules") -Force
Write-Host "module image installed: $((Get-Item (Join-Path $Target 'lib\modules')).Length) bytes"

$serverDir = Join-Path $Target "bin\server"
if (-not (Test-Path $serverDir)) { New-Item -ItemType Directory -Force -Path $serverDir | Out-Null }
Copy-Item (Join-Path $JavaHome "bin\server\classes.jsa") (Join-Path $serverDir "classes.jsa") -Force
Write-Host "base archive installed"
