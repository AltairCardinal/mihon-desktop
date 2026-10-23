param(
    [Parameter(Mandatory = $true)][string]$PathA,
    [Parameter(Mandatory = $true)][string]$PathB,
    [string]$LabelA = "A",
    [string]$LabelB = "B",
    [int]$Runs = 3
)

# Compares the very first start of two builds with an empty profile.
#
# Only the first start creates the database, so the ordinary measurement hides the
# cost of that creation completely. This script moves the profile aside before every
# single run and restores it afterwards, so every run is a first start.

$ErrorActionPreference = "Continue"

$appData = Join-Path $env:APPDATA "Mihon"
$localData = Join-Path $env:LOCALAPPDATA "Mihon"
$trace = Join-Path $localData "logs\startup-trace.json"

function Get-Marks([string]$distPath) {
    $exe = Join-Path $distPath "Mihon Desktop.exe"
    $launched = Get-Date
    $process = Start-Process -FilePath $exe -PassThru
    $deadline = (Get-Date).AddSeconds(90)
    $marks = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 25
        if ($process.HasExited) { break }
        if (Test-Path $trace) {
            $info = Get-Item $trace
            if ($info.LastWriteTime -ge $launched.AddSeconds(-1)) {
                try {
                    $content = Get-Content -Raw -Encoding UTF8 $trace | ConvertFrom-Json
                    if ($content.marks | Where-Object { $_.phase -eq "ui-ready" }) { $marks = $content; break }
                } catch { }
            }
        }
    }
    try { if (-not $process.HasExited) { $process.Kill() } } catch { }
    Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
    Start-Sleep -Milliseconds 700
    return $marks
}

function Invoke-FirstStart([string]$distPath, [string]$label, [int]$run) {
    $stamp = "firststart-$label-$run-$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
    $appBackup = "$appData.$stamp"
    $localBackup = "$localData.$stamp"
    if (Test-Path $appData) { Move-Item $appData $appBackup -Force }
    if (Test-Path $localData) { Move-Item $localData $localBackup -Force }

    try {
        $marks = Get-Marks $distPath
    } finally {
        # The fresh profile is moved aside instead of deleted: the application can
        # still hold a file of it, and deleting it would fail.
        if (Test-Path $appData) { Move-Item $appData "$appData.fresh-$stamp" -Force }
        if (Test-Path $localData) { Move-Item $localData "$localData.fresh-$stamp" -Force }
        if (Test-Path $appBackup) { Move-Item $appBackup $appData -Force }
        if (Test-Path $localBackup) { Move-Item $localBackup $localData -Force }
    }

    if (-not $marks) {
        Write-Host ("run {0} {1}: the application did not start" -f $run, $label)
        return $null
    }
    $frame = ($marks.marks | Where-Object { $_.phase -eq "first-frame" } | Select-Object -Last 1).sinceProcessStartMs
    $ready = ($marks.marks | Where-Object { $_.phase -eq "ui-ready" } | Select-Object -Last 1).sinceProcessStartMs
    $schema = $null
    $previous = ($marks.marks | Where-Object { $_.phase -eq "di-network" } | Select-Object -Last 1).epochMs
    $schemaMark = $marks.marks | Where-Object { $_.phase -eq "di-data:schema" } | Select-Object -Last 1
    if ($schemaMark) { $schema = $schemaMark.epochMs - $previous }
    Write-Host ("run {0} {1}: firstFrame={2} ms  usable={3} ms  schema={4} ms" -f $run, $label, $frame, $ready, $schema)
    return [pscustomobject]@{ Label = $label; FirstFrameMs = $frame; UiReadyMs = $ready; SchemaMs = $schema }
}

$results = @()
for ($i = 1; $i -le $Runs; $i++) {
    foreach ($side in @(@{ Name = $LabelA; Path = $PathA }, @{ Name = $LabelB; Path = $PathB })) {
        $result = Invoke-FirstStart $side.Path $side.Name $i
        if ($result) { $results += $result }
    }
}

function Get-Median([object[]]$rows, [string]$label, [string]$property) {
    $values = @($rows | Where-Object { $_.Label -eq $label } | ForEach-Object { $_.$property } | Where-Object { $null -ne $_ } | Sort-Object)
    if ($values.Count -eq 0) { return $null }
    return $values[[int][math]::Floor($values.Count / 2)]
}

Write-Host ""
Write-Host "=== first start with an empty profile ==="
foreach ($label in @($LabelA, $LabelB)) {
    Write-Host ("{0,-8} usable median={1} ms   firstFrame median={2} ms   schema median={3} ms" -f `
            $label,
            (Get-Median $results $label "UiReadyMs"),
            (Get-Median $results $label "FirstFrameMs"),
            (Get-Median $results $label "SchemaMs"))
}
$a = Get-Median $results $LabelA "UiReadyMs"
$b = Get-Median $results $LabelB "UiReadyMs"
if ($a -and $b) {
    Write-Host ""
    Write-Host ("Median usable interface improvement of the first start: {0} ms ({1} %)" -f ($a - $b), [math]::Round((($a - $b) / $a) * 100, 1))
}
