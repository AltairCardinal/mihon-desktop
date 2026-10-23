param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\artifacts\windows\Mihon-Desktop-0.11.20.8.6c64250-unpacked",
    [string]$ScreenshotPath = "D:\Github\mihon\app-desktop\tmp\startup-acceptance.png",
    [int]$WaitSeconds = 60
)

# Acceptance check for the packaged application: start it, wait until the real user
# interface is on screen and capture the window as evidence.

$ErrorActionPreference = "Continue"

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class WinShot {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint flags);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    public struct RECT { public int Left, Top, Right, Bottom; }
    public const uint WM_CLOSE = 0x0010;
    public const uint PW_RENDERFULLCONTENT = 0x00000002;
}
"@

function Find-AppWindow([int[]]$processIds) {
    $script:found = [IntPtr]::Zero
    $callback = [WinShot+EnumWindowsProc]{
        param([IntPtr]$hWnd, [IntPtr]$lParam)
        $owner = 0
        [void][WinShot]::GetWindowThreadProcessId($hWnd, [ref]$owner)
        if ($processIds -contains [int]$owner -and [WinShot]::IsWindowVisible($hWnd)) {
            $title = New-Object System.Text.StringBuilder 512
            [void][WinShot]::GetWindowText($hWnd, $title, $title.Capacity)
            if ($title.ToString() -match '^Mihon Desktop \d') {
                $script:found = $hWnd
                return $false
            }
        }
        return $true
    }
    [void][WinShot]::EnumWindows($callback, [IntPtr]::Zero)
    return $script:found
}

Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
Start-Sleep -Seconds 2

$launchedAt = Get-Date
$proc = Start-Process -FilePath (Join-Path $DistPath "Mihon Desktop.exe") -PassThru

$handle = [IntPtr]::Zero
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ($handle -eq [IntPtr]::Zero -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 250
    $ids = @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    if ($ids.Count -gt 0) { $handle = Find-AppWindow $ids }
}

if ($handle -eq [IntPtr]::Zero) {
    Write-Host "FAILED: the application window did not appear within $WaitSeconds seconds."
    Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
    exit 1
}

$rect = New-Object WinShot+RECT
[void][WinShot]::GetWindowRect($handle, [ref]$rect)
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
Write-Host "Window appeared after $([math]::Round(((Get-Date) - $launchedAt).TotalMilliseconds)) ms at ${width}x${height}"

Start-Sleep -Seconds 4

$bitmap = New-Object System.Drawing.Bitmap $width, $height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$hdc = $graphics.GetHdc()
$printed = [WinShot]::PrintWindow($handle, $hdc, [WinShot]::PW_RENDERFULLCONTENT)
$graphics.ReleaseHdc($hdc)
if (-not $printed) {
    Write-Host "PrintWindow failed, falling back to a screen capture."
    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
}
$bitmap.Save($ScreenshotPath, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()
Write-Host "Screenshot: $ScreenshotPath"

[void][WinShot]::PostMessage($handle, [WinShot]::WM_CLOSE, [IntPtr]::Zero, [IntPtr]::Zero)
Start-Sleep -Seconds 3
Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
Write-Host "Acceptance check finished."
