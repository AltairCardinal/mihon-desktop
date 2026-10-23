param(
    [Parameter(Mandatory = $true)][string]$DistPath,
    [string]$JdkHome = "",
    [string]$ClassList = "",
    [switch]$Verify
)

# Creates the class-data-sharing archive of the packaged desktop application.
#
# The archive holds the classes the start up loads, so the JVM does not have to parse
# and verify them. It is created from the class list of the start up warm up, so the
# build can produce it without starting the application.
#
# The archive is bound to the module image and to the virtual machine of the runtime,
# so it has to be produced by a launcher that lives inside the runtime image, and it
# has to be produced from the very class path the application starts with. The archive
# is placed where the virtual machine looks for its default archive, so the launcher
# needs no extra option.

$ErrorActionPreference = "Continue"

$runtime = Join-Path $DistPath "runtime"
$appDir = Join-Path $DistPath "app"
$cfg = Join-Path $appDir "Mihon Desktop.cfg"
if (-not (Test-Path $cfg)) { Write-Host "Missing launcher configuration: $cfg"; exit 1 }
if (-not (Test-Path $runtime)) { Write-Host "Missing runtime image: $runtime"; exit 1 }

if (-not $ClassList) {
    $ClassList = Join-Path $PSScriptRoot "..\app-desktop\src\main\resources\desktop-startup-warmup.txt"
}
if (-not (Test-Path $ClassList)) { Write-Host "Missing class list: $ClassList"; exit 1 }

if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome) { $JdkHome = Split-Path (Split-Path (Get-Command java -ErrorAction SilentlyContinue).Source) -Parent }
$sourceLauncher = if ($JdkHome) { Join-Path $JdkHome "bin\java.exe" } else { $null }
if (-not $sourceLauncher -or -not (Test-Path $sourceLauncher)) {
    Write-Host "No JDK launcher found; the class-data-sharing archive is skipped."
    exit 0
}

$classpath = New-Object System.Collections.Generic.List[string]
foreach ($line in [System.IO.File]::ReadAllLines($cfg)) {
    if ($line.StartsWith("app.classpath=")) {
        $classpath.Add($line.Substring("app.classpath=".Length).Replace('$APPDIR', $appDir))
    }
}
if ($classpath.Count -eq 0) { Write-Host "The launcher configuration carries no class path."; exit 1 }

$serverDir = Join-Path $runtime "bin\server"
$archive = Join-Path $serverDir "classes.jsa"
if (-not (Test-Path $serverDir)) { New-Item -ItemType Directory -Force -Path $serverDir | Out-Null }
if (Test-Path $archive) {
    $existing = Get-Item $archive
    if ($existing.IsReadOnly) { $existing.IsReadOnly = $false }
    [System.IO.File]::Delete($archive)
}

$runtimeLauncher = Join-Path $runtime "bin\java.exe"
$launcherWasCopied = -not (Test-Path $runtimeLauncher)
if ($launcherWasCopied) { Copy-Item $sourceLauncher $runtimeLauncher -Force }

try {
    Write-Host "Creating the class-data-sharing archive of the application..."
    try {
        & $runtimeLauncher `
            "-XX:SharedArchiveFile=$archive" `
            -Xshare:dump `
            "-XX:SharedClassListFile=$ClassList" `
            -cp ($classpath -join ';') 2>&1 |
            Where-Object { $_ -notmatch 'openjdk version|OpenJDK 64-Bit|Preload Warning|ClassNotFoundException' } |
            Select-Object -First 10 | ForEach-Object { Write-Host "  $_" }
    } catch {
        Write-Host "The archive could not be created: $($_.Exception.Message)"
    }

    if (-not (Test-Path $archive)) {
        Write-Host "The archive was not produced; the application starts without it."
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

    Write-Host ("Class-data-sharing archive: {0} ({1} MB)" -f $archive, [math]::Round((Get-Item $archive).Length / 1MB, 2))
} finally {
    if ($launcherWasCopied -and (Test-Path $runtimeLauncher)) { [System.IO.File]::Delete($runtimeLauncher) }
}
