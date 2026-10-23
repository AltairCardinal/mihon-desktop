param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.19.04688a4-unpacked",
    [string]$ScreenshotPath = "D:\Github\mihon\app-desktop\tmp\first-frame-covers.png",
    [int]$WaitSeconds = 90
)

# Captures the application window as soon as the application reports its first drawn
# frame.
#
# The library covers are loaded ahead of the first frame, so the first frame is the
# moment that decides whether the grid opens with its covers or fills them in one by
# one. A screenshot taken seconds later cannot tell the two apart.

$ErrorActionPreference = "Continue"

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class WinShot2 {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint flags);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    public struct RECT { public int Left, Top, Right, Bottom; }
    public const uint PW_RENDERFULLCONTENT = 0x00000002;
}
"@

function Find-AppWindow([int[]]$processIds) {
    $script:found = [IntPtr]::Zero
    $callback = [WinShot2+EnumWindowsProc]{
        param([IntPtr]$hWnd, [IntPtr]$lParam)
        $owner = 0
        [void][WinShot2]::GetWindowThreadProcessId($hWnd, [ref]$owner)
        if ($processIds -contains [int]$owner -and [WinShot2]::IsWindowVisible($hWnd)) {
            $title = New-Object System.Text.StringBuilder 512
            [void][WinShot2]::GetWindowText($hWnd, $title, $title.Capacity)
            if ($title.ToString() -match '^Mihon Desktop \d') {
                $script:found = $hWnd
                return $false
            }
        }
        return $true
    }
    [void][WinShot2]::EnumWindows($callback, [IntPtr]::Zero)
    return $script:found
}

Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
Start-Sleep -Seconds 2

$tracePath = Join-Path $env:LOCALAPPDATA "Mihon\logs\startup-trace.json"
if (Test-Path $tracePath) { [System.IO.File]::Delete($tracePath) }

$launchedAt = Get-Date
$proc = Start-Process -FilePath (Join-Path $DistPath "Mihon Desktop.exe") -PassThru

# Wait for the application to report its first drawn frame.
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$firstFrameAt = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 20
    if (Test-Path $tracePath) {
        try {
            $trace = Get-Content -Raw -Encoding UTF8 $tracePath | ConvertFrom-Json
            $frame = $trace.marks | Where-Object { $_.phase -eq "first-frame" } | Select-Object -Last 1
            if ($frame) {
                $firstFrameAt = $frame.sinceProcessStartMs
                break
            }
        } catch { }
    }
    if ($proc.HasExited) { break }
}

if ($null -eq $firstFrameAt) {
    Write-Host "FAILED: the application never reported a first frame within $WaitSeconds seconds."
    Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
    exit 1
}
Write-Host "first frame reported at ${firstFrameAt} ms since process start"

$handle = [IntPtr]::Zero
$ids = @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
if ($ids.Count -gt 0) { $handle = Find-AppWindow $ids }
if ($handle -eq [IntPtr]::Zero) {
    Write-Host "FAILED: the application window was not found."
    Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
    exit 1
}

$rect = New-Object WinShot2+RECT
[void][WinShot2]::GetWindowRect($handle, [ref]$rect)
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top

$bitmap = New-Object System.Drawing.Bitmap $width, $height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$hdc = $graphics.GetHdc()
$printed = [WinShot2]::PrintWindow($handle, $hdc, [WinShot2]::PW_RENDERFULLCONTENT)
$graphics.ReleaseHdc($hdc)
if (-not $printed) {
    Write-Host "PrintWindow failed, falling back to a screen capture."
    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
}
$bitmap.Save($ScreenshotPath, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()
Write-Host "Screenshot at the first frame: $ScreenshotPath (${width}x${height})"

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
