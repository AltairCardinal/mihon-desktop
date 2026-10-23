param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop",
    [int]$Runs = 4,
    [string]$Experiments = "cds,tier1,d3d,gc"
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$measureScript = Join-Path $scriptDir "measure-desktop-startup.ps1"

$cdsDir = Join-Path $env:LOCALAPPDATA "Mihon\cds"
New-Item -ItemType Directory -Force -Path $cdsDir | Out-Null

$catalog = @{
    "baseline" = ""
    "cds"  = "-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=$cdsDir\mihon-exp.jsa"
    "cdsstatic" = "-XX:SharedArchiveFile=$cdsDir\mihon-exp.jsa -Xshare:auto"
    "tier1" = "-XX:TieredStopAtLevel=1"
    "d3d"  = "-Dskiko.renderApi=DIRECT3D"
    "gc"   = "-XX:+UseSerialGC -Xms256m"
    "combo" = "-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=$cdsDir\mihon-combo.jsa -Dskiko.renderApi=DIRECT3D -XX:+UseSerialGC -Xms256m"
}

foreach ($name in $Experiments.Split(",") | Where-Object { $_ }) {
    $option = $catalog[$name]
    if (-not $catalog.ContainsKey($name)) { throw "Unknown experiment: $name" }
    Write-Host ""
    Write-Host "######## EXPERIMENT: $name -> $option ########"
    $env:MIHON_STARTUP_EXTRA_JAVA_OPTION = $option
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $measureScript `
        -DistPath $DistPath -Runs $Runs -Label "exp-$name" 2>&1 |
        Select-String -Pattern "min=|median=|App window visible|Startup phase trace|^  [a-z]" |
        ForEach-Object { Write-Host $_.Line }
}
$env:MIHON_STARTUP_EXTRA_JAVA_OPTION = $null
