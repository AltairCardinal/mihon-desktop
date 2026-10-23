param(
    [Parameter(Mandatory = $true)][string]$DistPath,
    [Parameter(Mandatory = $true)][string]$Label,
    [string]$OutputDir = "D:\Github\mihon\app-desktop\tmp\prefetch-shots",
    [int[]]$OffsetsMs = @(100, 300, 600, 1000, 1600, 2500, 4000),
    [int]$SettleMs = 6000,
    [int]$WaitSeconds = 90,
    [switch]$ClearImageCache
)

# Checks how the covers of the library reach the grid.
#
# For every offset after the first frame the script records
#   - the fill rate of the grid area (how much of it carries covers)
#   - the number of files in the image cache (how many covers were fetched)
#
# The image cache tells the two builds apart: a build that only loads the visible
# covers stops at the number of covers of the first screen, a build that prefetches
# the next screens goes clearly beyond it.

$ErrorActionPreference = "Continue"

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class WinShot4 {
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

$imageCacheDir = Join-Path $env:LOCALAPPDATA "Mihon\cache\image_cache"

function Get-ImageCacheCount {
    if (-not (Test-Path $imageCacheDir)) { return 0 }
    return (Get-ChildItem $imageCacheDir -File -ErrorAction SilentlyContinue | Measure-Object).Count
}

function Find-AppWindow([int[]]$processIds) {
    $script:found = [IntPtr]::Zero
    $callback = [WinShot4+EnumWindowsProc]{
        param([IntPtr]$hWnd, [IntPtr]$lParam)
        $owner = 0
        [void][WinShot4]::GetWindowThreadProcessId($hWnd, [ref]$owner)
        if ($processIds -contains [int]$owner -and [WinShot4]::IsWindowVisible($hWnd)) {
            $title = New-Object System.Text.StringBuilder 512
            [void][WinShot4]::GetWindowText($hWnd, $title, $title.Capacity)
            if ($title.ToString() -match '^Mihon Desktop \d') {
                $script:found = $hWnd
                return $false
            }
        }
        return $true
    }
    [void][WinShot4]::EnumWindows($callback, [IntPtr]::Zero)
    return $script:found
}

function Measure-GridFill([System.Drawing.Bitmap]$bitmap) {
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
    return [math]::Round(($total - $dominant) / $total, 4)
}

function Save-Window([IntPtr]$handle, [string]$path) {
    $rect = New-Object WinShot4+RECT
    [void][WinShot4]::GetWindowRect($handle, [ref]$rect)
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -le 0 -or $height -le 0) { return $null }
    $bitmap = New-Object System.Drawing.Bitmap $width, $height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $hdc = $graphics.GetHdc()
    $printed = [WinShot4]::PrintWindow($handle, $hdc, [WinShot4]::PW_RENDERFULLCONTENT)
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

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
Start-Sleep -Seconds 2

if ($ClearImageCache -and (Test-Path $imageCacheDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $stash = "$imageCacheDir.stashed-$stamp"
    Move-Item -Path $imageCacheDir -Destination $stash -Force
    Write-Host "image cache moved aside: $stash"
}

$before = Get-ImageCacheCount
Write-Host "image cache before: $before files"

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
$rows = @()
foreach ($offset in $OffsetsMs) {
    while ($clock.Elapsed.TotalMilliseconds -lt $offset) { Start-Sleep -Milliseconds 10 }
    $path = Join-Path $OutputDir ("{0}-ff+{1}ms.png" -f $Label, $offset)
    $fill = Save-Window $handle $path
    $cached = Get-ImageCacheCount
    $value = if ($null -ne $fill) { $fill } else { -1 }
    $rows += [pscustomobject]@{ Label = $Label; OffsetMs = $offset; GridFill = $value; CachedCovers = $cached }
    Write-Host ("offset +{0,5} ms   grid fill={1}   cached covers={2}" -f $offset, $value, $cached)
}

while ($clock.Elapsed.TotalMilliseconds -lt ($OffsetsMs[-1] + $SettleMs)) { Start-Sleep -Milliseconds 100 }
$finalCached = Get-ImageCacheCount
Write-Host ("settled: cached covers={0} (was {1} before the start)" -f $finalCached, $before)

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
