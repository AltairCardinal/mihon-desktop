param(
    [Parameter(Mandatory = $true)]
    [string]$SourceDirectory,
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot,
    [Parameter(Mandatory = $true)]
    [string]$FullVersion
)

$ErrorActionPreference = "Stop"

if ($FullVersion -notmatch '^[0-9A-Za-z.-]+$') {
    throw "FullVersion contains unsupported path characters: $FullVersion"
}

$source = Get-Item -LiteralPath $SourceDirectory -ErrorAction Stop
if (-not $source.PSIsContainer) {
    throw "Windows unpackaged source is not a directory: $SourceDirectory"
}

$sourceLauncher = Join-Path $source.FullName "Mihon Desktop.exe"
$sourceApp = Join-Path $source.FullName "app"
$sourceRuntime = Join-Path $source.FullName "runtime"
if (-not (Test-Path -LiteralPath $sourceLauncher -PathType Leaf)) {
    throw "Windows unpackaged launcher is missing: $sourceLauncher"
}
if (-not (Test-Path -LiteralPath $sourceApp -PathType Container)) {
    throw "Windows unpackaged app directory is missing: $sourceApp"
}
if (-not (Test-Path -LiteralPath $sourceRuntime -PathType Container)) {
    throw "Windows unpackaged runtime directory is missing: $sourceRuntime"
}

$output = [IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$destination = Join-Path $output "Mihon-Desktop-$FullVersion-unpacked"
$staging = Join-Path $output ".$([IO.Path]::GetFileName($destination)).$([Guid]::NewGuid().ToString('N')).tmp"
$backup = "$destination.$([Guid]::NewGuid().ToString('N')).bak"
$movedExistingDestination = $false

try {
    New-Item -ItemType Directory -Path $staging | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $source.FullName -Force) {
        Copy-Item -LiteralPath $item.FullName -Destination $staging -Recurse -Force
    }

    $stagedLauncher = Join-Path $staging "Mihon Desktop.exe"
    if (-not (Test-Path -LiteralPath $stagedLauncher -PathType Leaf)) {
        throw "Published Windows unpackaged launcher is missing from staging: $stagedLauncher"
    }

    if (Test-Path -LiteralPath $destination) {
        Move-Item -LiteralPath $destination -Destination $backup
        $movedExistingDestination = $true
    }
    Move-Item -LiteralPath $staging -Destination $destination
    if ($movedExistingDestination) {
        Remove-Item -LiteralPath $backup -Recurse -Force
        $movedExistingDestination = $false
    }

    $finalExe = Join-Path $destination "Mihon Desktop.exe"
    Write-Host "Final unpacked EXE: $finalExe"
} catch {
    if ($movedExistingDestination -and -not (Test-Path -LiteralPath $destination)) {
        Move-Item -LiteralPath $backup -Destination $destination
        $movedExistingDestination = $false
    }
    throw
} finally {
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
    if ($movedExistingDestination) {
        Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
    }
}
