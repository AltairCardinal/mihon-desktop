param(
    [string]$LogPath = "D:\Github\mihon\mihon-classload.log",
    [int]$BucketMs = 250
)

$ErrorActionPreference = "Stop"

$lines = Get-Content $LogPath
Write-Host "Total class load records: $($lines.Count)"

$bySource = @{}
$byBucket = @{}
$first = $null
$last = $null

foreach ($line in $lines) {
    if ($line -notmatch '^\[(\d+\.\d+)s\]\s+(\S+)\s+source:\s+(.*)$') { continue }
    $uptime = [double]$Matches[1]
    $class = $Matches[2]
    $source = $Matches[3].Trim()

    if ($null -eq $first) { $first = $uptime }
    $last = $uptime

    $jar = if ($source -match '([^/\\]+\.jar)$') { $Matches[1] } else { $source }
    if (-not $bySource.ContainsKey($jar)) { $bySource[$jar] = 0 }
    $bySource[$jar]++

    $bucket = [int]($uptime * 1000 / $BucketMs)
    if (-not $byBucket.ContainsKey($bucket)) { $byBucket[$bucket] = 0 }
    $byBucket[$bucket]++
}

Write-Host ("Load window: {0}s .. {1}s" -f $first, $last)
Write-Host ""
Write-Host "Classes per time bucket (${BucketMs}ms):"
foreach ($key in ($byBucket.Keys | Sort-Object)) {
    $startMs = $key * $BucketMs
    $bar = "#" * [math]::Min(60, [int]($byBucket[$key] / 20))
    Write-Host ("  {0,6} ms  {1,5}  {2}" -f $startMs, $byBucket[$key], $bar)
}

Write-Host ""
Write-Host "Top 25 jars by loaded classes:"
$bySource.GetEnumerator() | Sort-Object -Property Value -Descending | Select-Object -First 25 |
    ForEach-Object { Write-Host ("  {0,6}  {1}" -f $_.Value, $_.Key) }
