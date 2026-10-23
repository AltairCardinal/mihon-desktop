param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop",
    [int]$WaitSeconds = 12
)

$ErrorActionPreference = "Continue"

Add-Type @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class WinEnum {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
}
"@

Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
Start-Sleep -Seconds 2

$proc = Start-Process -FilePath (Join-Path $DistPath "Mihon Desktop.exe") -PassThru
Start-Sleep -Seconds $WaitSeconds

$targets = @(Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
Write-Host "Mihon Desktop process ids: $($targets -join ', ')"

$rows = New-Object System.Collections.Generic.List[object]
$callback = [WinEnum+EnumWindowsProc]{
    param([IntPtr]$hWnd, [IntPtr]$lParam)
    $pidValue = 0
    [void][WinEnum]::GetWindowThreadProcessId($hWnd, [ref]$pidValue)
    if ($targets -contains [int]$pidValue) {
        $title = New-Object System.Text.StringBuilder 512
        [void][WinEnum]::GetWindowText($hWnd, $title, $title.Capacity)
        $class = New-Object System.Text.StringBuilder 256
        [void][WinEnum]::GetClassName($hWnd, $class, $class.Capacity)
        $rows.Add([pscustomobject]@{
            ProcessId = $pidValue
            Handle = $hWnd.ToInt64()
            Class = $class.ToString()
            Title = $title.ToString()
            Visible = [WinEnum]::IsWindowVisible($hWnd)
            Minimized = [WinEnum]::IsIconic($hWnd)
        })
    }
    return $true
}
[void][WinEnum]::EnumWindows($callback, [IntPtr]::Zero)

$rows | Format-Table -AutoSize | Out-String | Write-Host

Get-Process -Name "Mihon Desktop" -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch { } }
