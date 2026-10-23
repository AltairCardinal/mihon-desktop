param(
    [string]$Source = "D:\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop",
    [string]$Target = "D:\Github\mihon\app-desktop\tmp\bench\B"
)

$ErrorActionPreference = "Stop"

if (Test-Path $Target) {
    Write-Host "Target already exists: $Target"
    exit 1
}

New-Item -ItemType Directory -Force -Path $Target | Out-Null
Copy-Item -Path (Join-Path $Source "*") -Destination $Target -Recurse -Force
Write-Host "Copied to $Target"
Get-ChildItem $Target | ForEach-Object { Write-Host "  $($_.Name)" }
