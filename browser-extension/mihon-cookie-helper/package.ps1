[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "artifacts")
)

$ErrorActionPreference = "Stop"
$manifestPath = Join-Path $PSScriptRoot "manifest.json"
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
$packageName = "mihon-cookie-helper-$($manifest.version).zip"

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$destination = Join-Path $OutputDirectory $packageName
$productionFiles = @(
    "manifest.json",
    "cookie-helper.mjs",
    "popup.html",
    "popup.css",
    "popup.mjs"
) | ForEach-Object { Join-Path $PSScriptRoot $_ }

Compress-Archive -LiteralPath $productionFiles -DestinationPath $destination -CompressionLevel Optimal -Force

$resolvedPackage = (Resolve-Path -LiteralPath $destination).Path
Write-Output "Browser extension package: $resolvedPackage"
