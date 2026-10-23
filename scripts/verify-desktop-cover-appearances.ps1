param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.19.04688a4-unpacked",
    [string]$OutputDir = "D:\Github\mihon\app-desktop\tmp\first-frame-shots",
    [string]$Label = "v19",
    [int[]]$OffsetsMs = @(200, 600, 1200, 2500),
    [int]$WaitSeconds = 90
)

# Captures the library window repeatedly after the application reports its first frame.
#
# The library grid appears after the library items are loaded, and the covers arrive
# afterwards. A series of screenshots at fixed offsets after the first frame shows
# whether the grid opens with all its covers or fills them in one by one.

$ErrorActionPreference = "Continue"

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class WinShot3 {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint flags);
    public struct RECT { public int Left, Top, Right, Bottom; }
    public const uint PW_RENDERFULLCONTENT = 0x00000002;
}
"@

function Find-AppWindow([int[]]$processIds) {
    $script:found = [IntPtr]::Zero
    $callback = [WinShot3+EnumWindowsProc]{
        param([IntPtr]$hWnd, [IntPtr]$lParam)
        $owner = 0
        [void][WinShot3]::GetWindowThreadProcessId($hWnd, [ref]$owner)
        if ($processIds -contains [int]$owner -and [WinShot3]::IsWindowVisible($hWnd)) {
            $title = New-Object System.Text.StringBuilder 512
            [void][WinShot3]::GetWindowText($hWnd, $title, $title.Capacity)
            if ($title.ToString() -match '^Mihon Desktop \d') {
                $script:found = $hWnd
                return $false
            }
        }
        return $true
    }
    [void][WinShot3]::EnumWindows($callback, [IntPtr]::Zero)
    return $script:found
}

function Save-Window([IntPtr]$handle, [string]$path) {
    $rect = New-Object WinShot3+RECT
    [void][WinShot3]::GetWindowRect($handle, [ref]$rect)
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -le 0 -or $height -le 0) { return $null }
    $bitmap = New-Object System.Drawing.Bitmap $width, $height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $hdc = $graphics.GetHdc()
    $printed = [WinShot3]::PrintWindow($handle, $hdc, [WinShot3]::PW_RENDERFULLCONTENT)
    $graphics.ReleaseHdc($hdc)
    if (-not $printed) {
        $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
    }
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $fill = Measure-GridFill $bitmap
    $graphics.Dispose()
    $bitmap.Dispose()
    return $fill
}

# Measures how much of the grid area carries content.
#
# An empty library grid is a single flat colour, a grid with covers is full of
# different ones. The share of pixels that differ from the dominant colour of the
# area therefore tells whether the grid is empty, partly filled or completely filled.
function Measure-GridFill([System.Drawing.Bitmap]$bitmap) {
    # The grid sits under the toolbar and above the navigation bar.
    $left = [int]($bitmap.Width * 0.06)
    $top = [int]($bitmap.Height * 0.17)
    $right = [int]($bitmap.Width * 0.96)
    $bottom = [int]($bitmap.Height * 0.80)
    $step = 4
    $colors = New-Object 'System.Collections.Generic.Dictionary[int,int]'
    $total = 0
    for ($y = $top; $y -lt $bottom; $y += $step) {
        for ($x = $left; $x -lt $right; $x += $step) {
            $pixel = $bitmap.GetPixel($x, $y)
            $key = ($pixel.R -shl 16) -bor ($pixel.G -shl 8) -bor $pixel.B
            if ($colors.ContainsKey($key)) { $colors[$key] = $colors[$key] + 1 } else { $colors[$key] = 1 }
            $total++
        }
    }
    if ($total -eq 0) { return 0.0 }
    $dominant = 0
    foreach ($entry in $colors.GetEnumerator()) { if ($entry.Value -gt $dominant) { $dominant = $entry.Value } }
    $distinct = $colors.Count
    $fill = [math]::Round(($total - $dominant) / $total, 4)
    return [pscustomobject]@{ Fill = $fill; DistinctColors = $distinct; Sampled = $total }
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
Start-Sleep -Seconds 2

$tracePath = Join-Path $env:LOCALAPPDATA "Mihon\logs\startup-trace.json"
if (Test-Path $tracePath) { [System.IO.File]::Delete($tracePath) }

$proc = Start-Process -FilePath (Join-Path $DistPath "Mihon Desktop.exe") -PassThru
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$firstFrameMs = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 20
    if (Test-Path $tracePath) {
        try {
            $trace = Get-Content -Raw -Encoding UTF8 $tracePath | ConvertFrom-Json
            $frame = $trace.marks | Where-Object { $_.phase -eq "first-frame" } | Select-Object -Last 1
            if ($frame) { $firstFrameMs = [double]$frame.sinceProcessStartMs; break }
        } catch { }
    }
    if ($proc.HasExited) { break }
}
if ($null -eq $firstFrameMs) {
    Write-Host "FAILED: no first frame within $WaitSeconds seconds."
    Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
    exit 1
}
Write-Host "first frame at ${firstFrameMs} ms"

$ids = @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
$handle = if ($ids.Count -gt 0) { Find-AppWindow $ids } else { [IntPtr]::Zero }
if ($handle -eq [IntPtr]::Zero) {
    Write-Host "FAILED: window not found."
    Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
    exit 1
}

$clock = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($offset in $OffsetsMs) {
    while ($clock.Elapsed.TotalMilliseconds -lt $offset) { Start-Sleep -Milliseconds 10 }
    $path = Join-Path $OutputDir ("{0}-first-frame+{1}ms.png" -f $Label, $offset)
    $fill = Save-Window $handle $path
    if ($null -ne $fill) {
        Write-Host ("saved {0}  grid fill={1}  distinct colours={2}" -f $path, $fill.Fill, $fill.DistinctColors)
    } else {
        Write-Host "could not capture at +$offset ms"
    }
}

if (Test-Path $tracePath) {
    $trace = Get-Content -Raw -Encoding UTF8 $tracePath | ConvertFrom-Json
    Write-Host ""
    Write-Host "start-up phases:"
    foreach ($mark in $trace.marks) {
        Write-Host ("  {0,-22} +{1,6} ms" -f $mark.phase, $mark.sinceProcessStartMs)
    }
}

Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
Write-Host "done"
