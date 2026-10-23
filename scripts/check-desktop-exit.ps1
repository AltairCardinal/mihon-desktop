param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.13.04688a4-unpacked",
    [int]$Seconds = 12,
    [int]$CloseWaitSeconds = 40
)

# Checks whether the desktop application terminates its JVM after the window is closed.
#
# The dynamic class-data-sharing archive is written while the JVM shuts down, so the
# archive can only be produced when the application really exits. A lingering process
# would silently defeat every archive based start-up optimisation.

$ErrorActionPreference = "Continue"

$exe = Join-Path $DistPath "Mihon Desktop.exe"
if (-not (Test-Path $exe)) { Write-Host "Missing executable: $exe"; exit 1 }

Write-Host "Starting $exe ..."
$launchedAt = Get-Date
$process = Start-Process -FilePath $exe -PassThru
Start-Sleep -Seconds $Seconds

$all = Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue
Write-Host "processes before close: $($all.Count)"
$closedAt = $null
foreach ($candidate in $all) {
    try {
        $candidate.Refresh()
        if ($candidate.MainWindowHandle -ne 0) {
            Write-Host ("  closing pid {0}" -f $candidate.Id)
            [void]$candidate.CloseMainWindow()
            if ($null -eq $closedAt) { $closedAt = Get-Date }
        }
    } catch { }
}

$deadline = (Get-Date).AddSeconds($CloseWaitSeconds)
$exited = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    $remaining = @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue)
    if ($remaining.Count -eq 0) { $exited = $true; break }
}

if ($exited) {
    $elapsed = [math]::Round(((Get-Date) - $closedAt).TotalSeconds, 1)
    Write-Host "All processes exited ${elapsed}s after the close request."
    exit 0
}

$remaining = @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue)
Write-Host "Processes are still running ${CloseWaitSeconds}s after the close request:"
foreach ($candidate in $remaining) {
    $candidate.Refresh()
    Write-Host ("  pid {0} cpu={1} title='{2}'" -f $candidate.Id, $candidate.TotalProcessorTime.TotalMilliseconds, $candidate.MainWindowTitle)
}
exit 1
