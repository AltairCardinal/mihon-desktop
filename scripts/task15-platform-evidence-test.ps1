param(
    [ValidateSet("uri-cold", "uri-running", "host-share", "credential-roundtrip", "capture", "installer-handoff")]
    [string]$Case,
    [string]$EvidenceDir,
    [string]$Executable,
    [Alias("Artifact")]
    [string]$InstallerArtifact,
    [string]$InstallerProvenance,
    [string]$TrustedPublisher,
    [switch]$ConfirmInstallerHandoff,
    [int]$Port = 18151,
    [int]$TimeoutSeconds = 90,
    [string]$ColdReviewFile,
    [string]$ReviewFile,
    [string]$ProcessPolicyFixture,
    [string]$ActivationPolicyFixture,
    [string]$CapturePriorityPolicyFixture,
    [string]$InstallerPolicyFixture,
    [switch]$ListCases
)

$ErrorActionPreference = "Stop"
if ($ListCases) {
    "uri-cold", "uri-running", "host-share", "credential-roundtrip", "capture", "installer-handoff"
    exit 0
}
if (-not $Case) { throw "-Case is required" }
if (-not $EvidenceDir) { throw "-EvidenceDir is required" }
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([IO.Path]::IsPathRooted($EvidenceDir)) {
    $EvidenceDir = [IO.Path]::GetFullPath($EvidenceDir)
} else {
    $EvidenceDir = [IO.Path]::GetFullPath((Join-Path $RepoRoot $EvidenceDir))
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
if (-not $Executable) {
    $Executable = Join-Path $RepoRoot "app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe"
}
$Executable = [IO.Path]::GetFullPath($Executable)
$ArtifactRoot = Split-Path $Executable
$StartedAt = [DateTime]::UtcNow
$ResultPath = Join-Path $EvidenceDir "$Case.json"
$ProtocolKey = "HKCU:\Software\Classes\tachiyomi\shell\open\command"
$AcceptanceHeader = "X-Mihon-Platform-Acceptance-Token"
$TextPayload = "Mihon Task 151 platform share acceptance"
$OwnedProcessIds = [Collections.Generic.HashSet[int]]::new()
$ProvenanceTool = Join-Path $RepoRoot "scripts\task15-build-provenance.py"
$Python = if ($env:MIHON_PYTHON) {
    $env:MIHON_PYTHON
} else {
    (Get-Command python -ErrorAction SilentlyContinue).Source
}
if (-not $Python) { throw "Python 3 is required; set MIHON_PYTHON to its executable" }

function Get-VerifiedProvenance {
    $provenancePath = "$ArtifactRoot.task151-provenance.json"
    $json = & $Python $ProvenanceTool verify --repo $RepoRoot --require-version-allocation `
        --artifact $ArtifactRoot --provenance $provenancePath
    if ($LASTEXITCODE -ne 0) { throw "Build provenance verification failed" }
    $json | ConvertFrom-Json
}

function Invoke-RunnerPolicy([string]$Kind, $Payload) {
    $inputPath = Join-Path $EvidenceDir ".task151-policy-$([Guid]::NewGuid().ToString('N')).json"
    try {
        $Payload | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $inputPath -Encoding utf8
        $json = & $Python $ProvenanceTool policy --kind $Kind --input $inputPath
        if ($LASTEXITCODE -ne 0) { throw "Task151 $Kind policy rejected runtime evidence" }
        $json | ConvertFrom-Json
    } finally {
        Remove-Item -LiteralPath $inputPath -Force -ErrorAction SilentlyContinue
    }
}

function Get-ArtifactIdentity($Provenance) {
    if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
        throw "Packaged executable not found: $Executable"
    }
    $item = Get-Item -LiteralPath $Executable
    $executableHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash.ToLowerInvariant()
    $provenancePath = "$ArtifactRoot.task151-provenance.json"
    [ordered]@{
        path = $ArtifactRoot
        algorithm = $Provenance.artifact.algorithm
        fileCount = $Provenance.artifact.fileCount
        sha256 = $Provenance.artifact.sha256
        size = $Provenance.artifact.size
        executablePath = $item.FullName
        executableSha256 = $executableHash
        modifiedUtc = $item.LastWriteTimeUtc.ToString("o")
        fileVersion = $item.VersionInfo.FileVersion
        productVersion = $item.VersionInfo.ProductVersion
        provenancePath = $provenancePath
        provenanceSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $provenancePath).Hash.ToLowerInvariant()
    }
}

function Get-ExactAppProcesses {
    @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and ([IO.Path]::GetFullPath($_.ExecutablePath) -eq $Executable)
    })
}

function Assert-NoExistingAppProcesses {
    $existing = @(Get-ExactAppProcesses)
    Invoke-RunnerPolicy "pid-empty" @{ pids = @($existing.ProcessId) } | Out-Null
}

function Register-OwnedAppProcesses {
    foreach ($process in (Get-ExactAppProcesses)) {
        $OwnedProcessIds.Add([int]$process.ProcessId) | Out-Null
    }
}

function Get-OwnedExactAppProcesses {
    @(Get-ExactAppProcesses | Where-Object { $OwnedProcessIds.Contains([int]$_.ProcessId) })
}

function Select-RootProcessIds($Processes) {
    $items = @($Processes | Where-Object { $null -ne $_.ProcessId })
    $ids = [Collections.Generic.HashSet[int]]::new()
    foreach ($process in $items) {
        $ids.Add([int]$process.ProcessId) | Out-Null
    }
    @(
        $items |
            Where-Object { -not $ids.Contains([int]$_.ParentProcessId) } |
            ForEach-Object { [int]$_.ProcessId }
    )
}

function Get-OwnedRootProcessIds {
    @(Select-RootProcessIds (Get-OwnedExactAppProcesses))
}

function Get-OwnedWindowProcessIds {
    @(
        Get-OwnedExactAppProcesses |
            ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue } |
            Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } |
            ForEach-Object { [int]$_.Id }
    )
}

function Wait-OwnedWindowProcessId {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ([DateTime]::UtcNow -lt $deadline) {
        $windowPids = @(Get-OwnedWindowProcessIds)
        if ($windowPids.Count -gt 1) { throw "Expected one owned Mihon window, found $($windowPids.Count)" }
        if ($windowPids.Count -eq 1) { return $windowPids[0] }
        Start-Sleep -Milliseconds 200
    }
    throw "Timed out waiting for the owned Mihon window"
}

function Stop-OwnedAppProcesses {
    $rootPids = @(Get-OwnedRootProcessIds)
    $cleanup = Invoke-RunnerPolicy "pid-cleanup" @{
        owned = $rootPids
        current = $rootPids
    }
    foreach ($processId in @($cleanup.kill)) {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & taskkill.exe /PID $processId /T /F 2>$null | Out-Null
        } finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while ((Get-OwnedExactAppProcesses).Count -gt 0 -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 200
    }
    if ((Get-OwnedExactAppProcesses).Count -gt 0) { throw "Owned Mihon process tree did not stop" }
    $OwnedProcessIds.Clear()
}

function Wait-Health([int]$HttpPort) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            return Invoke-RestMethod -Uri "http://127.0.0.1:$HttpPort/test/health" -TimeoutSec 2
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    throw "Timed out waiting for package HTTP endpoint on $HttpPort"
}

function Expand-ActionHistory($Response) {
    if ($null -eq $Response) { return }
    foreach ($record in $Response) {
        $record
    }
}

function Get-ActionHistory([int]$HttpPort) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$HttpPort/test/history" -TimeoutSec 3
    Expand-ActionHistory ($response.Content | ConvertFrom-Json)
}

function Wait-ParserRejected([int]$HttpPort, [int]$AfterCursor) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $history = @(Get-ActionHistory $HttpPort)
        $candidate = Invoke-RunnerPolicy "terminal" @{
            cursor = $AfterCursor
            history = $history
        }
        if ($candidate.status -eq "VALID") {
            Start-Sleep -Seconds 1
            $stable = Invoke-RunnerPolicy "terminal" @{
                cursor = $AfterCursor
                history = @(Get-ActionHistory $HttpPort)
            }
            if ($stable.status -ne "VALID") {
                throw "Running URI terminal action did not remain stable"
            }
            return $stable.record
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Running URI reached no new ParserRejected terminal action"
}

function Capture-Screenshot([int]$HttpPort, [string]$Name) {
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$HttpPort/test/screenshot" `
        -ContentType "application/json" -Body (@{ name = $Name } | ConvertTo-Json -Compress) -TimeoutSec 15
    $result = $response.Content | ConvertFrom-Json
    (Invoke-RunnerPolicy "screenshot" $result).screenshot
}

function Initialize-Task151NativeWindow {
    if (-not ("Task151NativeWindow" -as [type])) {
        Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class Task151NativeWindow {
    public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
    public static readonly IntPtr HWND_NOTOPMOST = new IntPtr(-2);
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect { public int Left; public int Top; public int Right; public int Bottom; }
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr window, out Rect rect);
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")]
    public static extern void SwitchToThisWindow(IntPtr window, bool altTab);
    [DllImport("kernel32.dll")]
    public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")]
    public static extern bool AttachThreadInput(uint fromThread, uint toThread, bool attach);
    [DllImport("user32.dll")]
    public static extern bool BringWindowToTop(IntPtr window);
    [DllImport("user32.dll")]
    public static extern IntPtr SetFocus(IntPtr window);
    [DllImport("user32.dll")]
    public static extern bool SetWindowPos(
        IntPtr window,
        IntPtr insertAfter,
        int x,
        int y,
        int width,
        int height,
        uint flags
    );
    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr window, int command);
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool GetWindowDisplayAffinity(IntPtr window, out uint affinity);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int GetClassName(IntPtr window, StringBuilder className, int maxCount);
}
"@
    }
}

function Assert-ControlledSaveDialogForeground([IntPtr]$DialogHandle, [int]$AppPid) {
    if ([Task151NativeWindow]::GetForegroundWindow() -ne $DialogHandle) {
        throw "Controlled Save dialog lost foreground focus"
    }
    [uint32]$nativePid = 0
    [Task151NativeWindow]::GetWindowThreadProcessId($DialogHandle, [ref]$nativePid) | Out-Null
    if ($nativePid -ne $AppPid) {
        throw "Controlled Save dialog native process no longer matches the owned app"
    }
    $className = [Text.StringBuilder]::new(256)
    if ([Task151NativeWindow]::GetClassName($DialogHandle, $className, $className.Capacity) -le 0 -or
        $className.ToString() -ne "SunAwtDialog") {
        throw "Controlled Save dialog native class no longer matches SunAwtDialog"
    }
}

function Throw-WindowActivationBlocked([string]$Message, $Diagnostics) {
    $exception = [InvalidOperationException]::new($Message)
    $exception.Data["Task152WindowActivation"] = $Diagnostics
    throw $exception
}

function Activate-OwnedMihonWindow(
    [int]$TargetPid,
    [IntPtr]$TargetHandle,
    [string]$ExpectedExecutable = $Executable,
    $Operations = $null,
    [int]$TimeoutMilliseconds = 2000,
    [int]$PollMilliseconds = 50
) {
    Initialize-Task151NativeWindow
    if ($null -eq $Operations) {
        $Operations = @{
            ResolveOwnedProcesses = {
                param([int]$ProcessId)
                @(Get-ExactAppProcesses | Where-Object { [int]$_.ProcessId -eq $ProcessId })
            }
            ShowWindow = {
                param([IntPtr]$Window)
                [Task151NativeWindow]::ShowWindowAsync($Window, 9)
            }
            AutomationFocus = {
                param([IntPtr]$Window)
                Add-Type -AssemblyName UIAutomationClient
                $element = [Windows.Automation.AutomationElement]::FromHandle($Window)
                if ($null -eq $element) {
                    return [ordered]@{ elementFound = $false; focusApplied = $false }
                }
                $element.SetFocus()
                [ordered]@{ elementFound = $true; focusApplied = $true }
            }
            SetForeground = {
                param([IntPtr]$Window)
                [Task151NativeWindow]::SetForegroundWindow($Window)
            }
            AppActivate = {
                param([int]$ProcessId)
                $shell = New-Object -ComObject WScript.Shell
                try {
                    [bool]$shell.AppActivate($ProcessId)
                } finally {
                    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell)
                }
            }
            SwitchToWindow = {
                param([IntPtr]$Window)
                [Task151NativeWindow]::SwitchToThisWindow($Window, $true)
                $true
            }
            GetCurrentThread = {
                [Task151NativeWindow]::GetCurrentThreadId()
            }
            GetWindowThread = {
                param([IntPtr]$Window)
                [uint32]$processId = 0
                $threadId = [Task151NativeWindow]::GetWindowThreadProcessId($Window, [ref]$processId)
                [ordered]@{ threadId = $threadId; processId = $processId }
            }
            AttachThreads = {
                param([uint32]$FromThread, [uint32]$ToThread, [bool]$Attach)
                [Task151NativeWindow]::AttachThreadInput($FromThread, $ToThread, $Attach)
            }
            BringToTop = {
                param([IntPtr]$Window)
                [Task151NativeWindow]::BringWindowToTop($Window)
            }
            FocusWindow = {
                param([IntPtr]$Window)
                [Task151NativeWindow]::SetFocus($Window).ToInt64()
            }
            GetForeground = {
                $window = [Task151NativeWindow]::GetForegroundWindow()
                [uint32]$processId = 0
                $className = [Text.StringBuilder]::new(256)
                if ($window -ne [IntPtr]::Zero) {
                    [Task151NativeWindow]::GetWindowThreadProcessId($window, [ref]$processId) | Out-Null
                    if ([Task151NativeWindow]::GetClassName($window, $className, $className.Capacity) -le 0) {
                        $className.Clear() | Out-Null
                    }
                }
                [ordered]@{
                    handle = $window.ToInt64()
                    processId = [int]$processId
                    className = $className.ToString()
                }
            }
        }
    }

    $diagnostics = [ordered]@{
        status = "BLOCKED"
        targetHwnd = $TargetHandle.ToInt64()
        targetPid = $TargetPid
        expectedExecutable = [IO.Path]::GetFullPath($ExpectedExecutable)
        ownedProcessValidated = $false
        ownedExecutablePath = $null
        showWindowAsync = $null
        showWindowError = $null
        automationElementFound = $false
        automationSetFocus = $false
        automationFocusError = $null
        setForegroundWindow = $null
        setForegroundError = $null
        appActivate = $null
        appActivateError = $null
        switchToThisWindow = $null
        switchToThisWindowError = $null
        inputAttachment = $null
        inputAttachmentCleanupComplete = $null
        pollAttempts = 0
        finalForegroundHwnd = $null
        finalForegroundPid = $null
        finalForegroundClass = $null
    }

    $owned = @(& $Operations.ResolveOwnedProcesses $TargetPid)
    $matching = @($owned | Where-Object { [int]$_.ProcessId -eq $TargetPid })
    if ($matching.Count -ne 1) {
        Throw-WindowActivationBlocked "Mihon activation target is not one exact owned process" $diagnostics
    }
    $resolvedExecutable = [IO.Path]::GetFullPath([string]$matching[0].ExecutablePath)
    $diagnostics.ownedExecutablePath = $resolvedExecutable
    if (-not $resolvedExecutable.Equals($diagnostics.expectedExecutable, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-WindowActivationBlocked "Mihon activation target executable does not match the accepted artifact" $diagnostics
    }
    $diagnostics.ownedProcessValidated = $true

    try {
        $diagnostics.showWindowAsync = [bool](& $Operations.ShowWindow $TargetHandle)
    } catch {
        $diagnostics.showWindowAsync = $false
        $diagnostics.showWindowError = $_.Exception.Message
    }
    try {
        $focus = & $Operations.AutomationFocus $TargetHandle
        $diagnostics.automationElementFound = [bool]$focus.elementFound
        $diagnostics.automationSetFocus = [bool]$focus.focusApplied
    } catch {
        $diagnostics.automationFocusError = $_.Exception.Message
    }
    try {
        $diagnostics.setForegroundWindow = [bool](& $Operations.SetForeground $TargetHandle)
    } catch {
        $diagnostics.setForegroundWindow = $false
        $diagnostics.setForegroundError = $_.Exception.Message
    }

    $observeForeground = {
        $foreground = & $Operations.GetForeground
        $diagnostics.pollAttempts = [int]$diagnostics.pollAttempts + 1
        $diagnostics.finalForegroundHwnd = [long]$foreground.handle
        $diagnostics.finalForegroundPid = [int]$foreground.processId
        $diagnostics.finalForegroundClass = [string]$foreground.className
        $diagnostics.finalForegroundHwnd -eq $TargetHandle.ToInt64() -and
            $diagnostics.finalForegroundPid -eq $TargetPid
    }
    if (& $observeForeground) {
        $diagnostics.status = "PASS"
        return $diagnostics
    }

    try {
        $diagnostics.appActivate = [bool](& $Operations.AppActivate $TargetPid)
    } catch {
        $diagnostics.appActivate = $false
        $diagnostics.appActivateError = $_.Exception.Message
    }

    $activationDelay = [Math]::Min(
        250,
        [Math]::Max($PollMilliseconds, [int]([Math]::Max(0, $TimeoutMilliseconds) / 4))
    )
    if ($activationDelay -gt 0) { Start-Sleep -Milliseconds $activationDelay }
    if (& $observeForeground) {
        $diagnostics.status = "PASS"
        return $diagnostics
    }

    try {
        $diagnostics.switchToThisWindow = [bool](& $Operations.SwitchToWindow $TargetHandle)
    } catch {
        $diagnostics.switchToThisWindow = $false
        $diagnostics.switchToThisWindowError = $_.Exception.Message
    }

    if ($activationDelay -gt 0) { Start-Sleep -Milliseconds $activationDelay }
    if (& $observeForeground) {
        $diagnostics.status = "PASS"
        return $diagnostics
    }

    $attachment = [ordered]@{
        currentThreadId = $null
        foregroundThreadId = $null
        targetThreadId = $null
        targetThreadProcessId = $null
        attachedThreadIds = @()
        detachedThreadIds = @()
        detachErrors = @()
        bringWindowToTop = $null
        setForegroundWindow = $null
        focusHandle = $null
        error = $null
    }
    $attachedThreadIds = [Collections.Generic.List[uint32]]::new()
    try {
        $currentThreadId = [uint32](& $Operations.GetCurrentThread)
        $foregroundThread = & $Operations.GetWindowThread ([IntPtr][long]$diagnostics.finalForegroundHwnd)
        $targetThread = & $Operations.GetWindowThread $TargetHandle
        $attachment.currentThreadId = $currentThreadId
        $attachment.foregroundThreadId = [uint32]$foregroundThread.threadId
        $attachment.targetThreadId = [uint32]$targetThread.threadId
        $attachment.targetThreadProcessId = [int]$targetThread.processId
        if ($attachment.targetThreadProcessId -ne $TargetPid) {
            throw "Target HWND thread no longer belongs to the exact owned process"
        }
        foreach ($threadId in @($attachment.foregroundThreadId, $attachment.targetThreadId) | Select-Object -Unique) {
            if ([uint32]$threadId -eq $currentThreadId) { continue }
            if (-not [bool](& $Operations.AttachThreads $currentThreadId ([uint32]$threadId) $true)) {
                throw "AttachThreadInput failed for thread $threadId"
            }
            [void]$attachedThreadIds.Add([uint32]$threadId)
            $attachment.attachedThreadIds = @($attachedThreadIds)
        }
        $attachment.bringWindowToTop = [bool](& $Operations.BringToTop $TargetHandle)
        $attachment.setForegroundWindow = [bool](& $Operations.SetForeground $TargetHandle)
        $attachment.focusHandle = [long](& $Operations.FocusWindow $TargetHandle)
    } catch {
        $attachment.error = $_.Exception.Message
    } finally {
        $detachedThreadIds = [Collections.Generic.List[uint32]]::new()
        $detachErrors = [Collections.Generic.List[string]]::new()
        for ($index = $attachedThreadIds.Count - 1; $index -ge 0; $index--) {
            $threadId = $attachedThreadIds[$index]
            try {
                if ([bool](& $Operations.AttachThreads ([uint32]$attachment.currentThreadId) $threadId $false)) {
                    [void]$detachedThreadIds.Add($threadId)
                } else {
                    [void]$detachErrors.Add("AttachThreadInput detach returned false for thread $threadId")
                }
            } catch {
                [void]$detachErrors.Add("AttachThreadInput detach threw for thread ${threadId}: $($_.Exception.Message)")
            }
        }
        $attachment.detachedThreadIds = @($detachedThreadIds)
        $attachment.detachErrors = @($detachErrors)
        $diagnostics.inputAttachmentCleanupComplete =
            $detachedThreadIds.Count -eq $attachedThreadIds.Count -and $detachErrors.Count -eq 0
    }
    $diagnostics.inputAttachment = $attachment
    if ($attachment.error -or -not $diagnostics.inputAttachmentCleanupComplete) {
        Throw-WindowActivationBlocked "Controlled foreground input attachment failed or did not detach cleanly" $diagnostics
    }

    $deadline = [DateTime]::UtcNow.AddMilliseconds([Math]::Max(0, $TimeoutMilliseconds))
    do {
        if (& $observeForeground) {
            $diagnostics.status = "PASS"
            return $diagnostics
        }
        if ([DateTime]::UtcNow -ge $deadline) { break }
        if ($PollMilliseconds -gt 0) { Start-Sleep -Milliseconds $PollMilliseconds }
    } while ($true)

    Throw-WindowActivationBlocked "Mihon window could not be activated as the exact owned foreground target" $diagnostics
}

function New-MihonWindowCaptureOperations {
    @{
        ResolveOwnedProcesses = {
            param([int]$ProcessId)
            @(Get-OwnedExactAppProcesses | Where-Object { [int]$_.ProcessId -eq $ProcessId })
        }
        GetWindowOwnerPid = {
            param([IntPtr]$Window)
            [uint32]$processId = 0
            [Task151NativeWindow]::GetWindowThreadProcessId($Window, [ref]$processId) | Out-Null
            [int]$processId
        }
        SetWindowPos = {
            param([IntPtr]$Window, [IntPtr]$InsertAfter, [uint32]$Flags)
            [Task151NativeWindow]::SetWindowPos($Window, $InsertAfter, 0, 0, 0, 0, $Flags)
        }
        StopOwnedProcesses = {
            Stop-OwnedAppProcesses
        }
    }
}

function Assert-MihonWindowCaptureLease($Lease, $Operations) {
    $owned = @(& $Operations.ResolveOwnedProcesses ([int]$Lease.processId))
    if ($owned.Count -ne 1) { throw "Mihon capture target is not one exact owned process" }
    $ownedPath = [IO.Path]::GetFullPath([string]$owned[0].ExecutablePath)
    if (-not $ownedPath.Equals(
        [IO.Path]::GetFullPath([string]$Lease.executablePath),
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Mihon capture target executable no longer matches the accepted artifact"
    }
    $windowOwnerPid = [int](& $Operations.GetWindowOwnerPid ([IntPtr]$Lease.windowHandle))
    if ($windowOwnerPid -ne [int]$Lease.processId) {
        throw "Mihon capture target HWND no longer belongs to the exact owned process"
    }
}

function New-MihonWindowCaptureLease(
    [int]$ProcessId,
    [IntPtr]$WindowHandle,
    [string]$ExpectedExecutable = $Executable,
    $Operations = $null
) {
    Initialize-Task151NativeWindow
    if ($WindowHandle -eq [IntPtr]::Zero) { throw "Mihon main window handle is unavailable" }
    if ($null -eq $Operations) { $Operations = New-MihonWindowCaptureOperations }
    $lease = [ordered]@{
        processId = $ProcessId
        windowHandle = $WindowHandle.ToInt64()
        executablePath = [IO.Path]::GetFullPath($ExpectedExecutable)
    }
    Assert-MihonWindowCaptureLease $lease $Operations
    $lease
}

function Set-MihonWindowCapturePriority($Lease, [bool]$Topmost, $Operations = $null) {
    Initialize-Task151NativeWindow
    if ($null -eq $Operations) { $Operations = New-MihonWindowCaptureOperations }
    Assert-MihonWindowCaptureLease $Lease $Operations
    $insertAfter = if ($Topmost) {
        [Task151NativeWindow]::HWND_TOPMOST
    } else {
        [Task151NativeWindow]::HWND_NOTOPMOST
    }
    $flags = [uint32](0x0001 -bor 0x0002 -bor 0x0010 -bor 0x0040)
    $updated = & $Operations.SetWindowPos ([IntPtr][long]$Lease.windowHandle) $insertAfter $flags
    if (-not $updated) { throw "Unable to update exact owned Mihon window capture priority" }
}

function Exit-MihonWindowCapturePriority($Lease, $Operations = $null) {
    if ($null -eq $Operations) { $Operations = New-MihonWindowCaptureOperations }
    try {
        Set-MihonWindowCapturePriority $Lease $false $Operations
    } catch {
        $releaseError = $_
        try {
            & $Operations.StopOwnedProcesses
        } catch {
            # Preserve the release error; the caller must remain blocked.
        }
        throw $releaseError
    }
}

function Invoke-MihonWindowFeedbackCapture($Lease, [scriptblock]$CaptureFeedback, $Operations = $null) {
    try {
        & $CaptureFeedback
    } finally {
        Exit-MihonWindowCapturePriority $Lease $Operations
    }
}

function Invoke-MihonWindowCapture(
    $Lease,
    [string]$Name,
    [switch]$KeepTopmost,
    $Operations = $null
) {
    Add-Type -AssemblyName System.Drawing
    Initialize-Task151NativeWindow
    if ($null -eq $Operations) {
        $Operations = New-MihonWindowCaptureOperations
        $Operations.GetWindowRect = {
            param([IntPtr]$Window)
            $rect = [Task151NativeWindow+Rect]::new()
            if (-not [Task151NativeWindow]::GetWindowRect($Window, [ref]$rect)) {
                throw "Unable to read Mihon window bounds"
            }
            $rect
        }
        $Operations.NewBitmap = {
            param([int]$Width, [int]$Height)
            [Drawing.Bitmap]::new($Width, $Height)
        }
        $Operations.NewGraphics = {
            param($Bitmap)
            [Drawing.Graphics]::FromImage($Bitmap)
        }
        $Operations.CopyWindow = {
            param($Graphics, $Bitmap, $Rect)
            $Graphics.CopyFromScreen($Rect.Left, $Rect.Top, 0, 0, $Bitmap.Size)
        }
        $Operations.SaveBitmap = {
            param($Bitmap, [string]$Path)
            $Bitmap.Save($Path, [Drawing.Imaging.ImageFormat]::Png)
        }
        $Operations.DisposeGraphics = {
            param($Graphics)
            $Graphics.Dispose()
        }
        $Operations.DisposeBitmap = {
            param($Bitmap)
            $Bitmap.Dispose()
        }
    }
    $bitmap = $null
    $graphics = $null
    $path = Join-Path $EvidenceDir "$Name.png"
    Set-MihonWindowCapturePriority $Lease $true $Operations
    try {
        $rect = & $Operations.GetWindowRect ([IntPtr][long]$Lease.windowHandle)
        $width = $rect.Right - $rect.Left
        $height = $rect.Bottom - $rect.Top
        if ($width -le 0 -or $height -le 0) { throw "Invalid Mihon window bounds" }
        $bitmap = & $Operations.NewBitmap $width $height
        $graphics = & $Operations.NewGraphics $bitmap
        & $Operations.CopyWindow $graphics $bitmap $rect
        & $Operations.SaveBitmap $bitmap $path
    } finally {
        try {
            if ($null -ne $graphics) { & $Operations.DisposeGraphics $graphics }
        } finally {
            try {
                if ($null -ne $bitmap) { & $Operations.DisposeBitmap $bitmap }
            } finally {
                if (-not $KeepTopmost) {
                    Exit-MihonWindowCapturePriority $Lease $Operations
                }
            }
        }
    }
    $path
}

function Invoke-BoundedMihonWindowCapture(
    $Lease,
    [string]$Name,
    [switch]$KeepTopmost,
    $Operations = $null
) {
    try {
        Invoke-MihonWindowCapture $Lease $Name -KeepTopmost:$KeepTopmost -Operations $Operations
    } catch {
        if ($KeepTopmost) {
            try {
                Exit-MihonWindowCapturePriority $Lease $Operations
            } catch {
                # Exit-MihonWindowCapturePriority already stopped the exact owned process tree.
            }
        }
        throw
    }
}

function Capture-MihonWindow(
    [Diagnostics.Process]$Process,
    [string]$Name,
    [switch]$KeepTopmost
) {
    $Process.Refresh()
    $lease = New-MihonWindowCaptureLease $Process.Id $Process.MainWindowHandle
    Activate-OwnedMihonWindow $lease.processId ([IntPtr][long]$lease.windowHandle) | Out-Null
    $path = Invoke-BoundedMihonWindowCapture $lease $Name -KeepTopmost:$KeepTopmost
    [ordered]@{ path = $path; lease = $lease }
}

function New-PlatformProbe {
    $java = (Get-Command java -ErrorAction SilentlyContinue).Source
    if (-not $java) { throw "Java is required for packaged credential and preference probes" }
    $source = Join-Path $EvidenceDir ".Task152PlatformProbe.java"
    & $Python $ProvenanceTool write-probe --output $source | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to create the temporary Task152 product probe" }
    [ordered]@{
        java = $java
        source = $source
        classPath = Join-Path $ArtifactRoot "app\*"
    }
}

function Invoke-PlatformProbe($Probe, [string[]]$Arguments) {
    $output = @(& $Probe.java --class-path $Probe.classPath $Probe.source @Arguments 2>&1)
    $exit = $LASTEXITCODE
    $jsonLine = @($output | Where-Object { "$_".TrimStart().StartsWith("{") })[-1]
    if (-not $jsonLine) { throw "Task152 product probe returned no JSON" }
    $result = "$jsonLine" | ConvertFrom-Json
    if ($exit -ne 0 -or $result.status -ne "PASS") {
        throw "Task152 product probe failed: $($result.errorClass)"
    }
    $result
}

function Get-WindowAffinity([Diagnostics.Process]$Process) {
    Initialize-Task151NativeWindow
    $Process.Refresh()
    if ($Process.MainWindowHandle -eq [IntPtr]::Zero) { throw "Mihon main window handle is unavailable" }
    [uint32]$affinity = 0
    if (-not [Task151NativeWindow]::GetWindowDisplayAffinity($Process.MainWindowHandle, [ref]$affinity)) {
        throw "GetWindowDisplayAffinity failed for the owned Mihon window"
    }
    [ordered]@{
        handle = $Process.MainWindowHandle.ToInt64()
        affinity = [int]$affinity
    }
}

function Set-SecureScreenProbePreference($Probe, [string]$Value) {
    Invoke-PlatformProbe $Probe @("preference", "set", $Value) | Out-Null
}

function Restore-SecureScreenProbePreference($Probe, [string]$Value) {
    if ($Value -eq "__MISSING__") {
        Invoke-PlatformProbe $Probe @("preference", "delete") | Out-Null
    } else {
        Set-SecureScreenProbePreference $Probe $Value
    }
}

function Invoke-CredentialRoundtrip {
    $probe = New-PlatformProbe
    try {
        $result = Invoke-PlatformProbe $probe @(
            "credential",
            "mihon.task152.$([Guid]::NewGuid().ToString('N'))"
        )
        Invoke-RunnerPolicy "credential" $result | Out-Null
        $result
    } finally {
        Remove-Item -LiteralPath $probe.source -Force -ErrorAction SilentlyContinue
    }
}

function New-CaptureScreenshotRecord([string]$Role, [string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $absolute -PathType Leaf)) {
        throw "Capture screenshot is missing: $absolute"
    }
    [ordered]@{
        role = $Role
        path = $absolute
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $absolute).Hash.ToLowerInvariant()
    }
}

function Invoke-CaptureAcceptance {
    $probe = New-PlatformProbe
    $original = (Invoke-PlatformProbe $probe @("preference", "get")).value
    $protectedScreenshot = $null
    $clearScreenshot = $null
    $clearProcess = $null
    try {
        $adapter = Invoke-PlatformProbe $probe @("privacy")
        Set-SecureScreenProbePreference $probe "ALWAYS"
        Start-TestApp $Port | Out-Null
        $protectedPid = Wait-OwnedWindowProcessId
        $protectedProcess = Get-Process -Id $protectedPid -ErrorAction Stop
        Start-Sleep -Milliseconds 500
        $applied = Get-WindowAffinity $protectedProcess
        $capability = switch ($applied.affinity) {
            0x11 { "Supported" }
            0x1 { "Limited" }
            default { throw "Product defect: protected Mihon window affinity is $($applied.affinity)" }
        }
        $protectedCapture = Capture-MihonWindow $protectedProcess "task152-windows-capture-protected"
        $protectedScreenshot = $protectedCapture.path
        Stop-OwnedAppProcesses

        Set-SecureScreenProbePreference $probe "NEVER"
        Start-TestApp ($Port + 1) | Out-Null
        $clearPid = Wait-OwnedWindowProcessId
        $clearProcess = Get-Process -Id $clearPid -ErrorAction Stop
        Start-Sleep -Milliseconds 500
        $cleared = Get-WindowAffinity $clearProcess
        if ($cleared.affinity -ne 0) {
            throw "Product defect: cleared Mihon window affinity is $($cleared.affinity)"
        }
        Invoke-RestMethod -Method Post `
            -Uri "http://127.0.0.1:$($Port + 1)/test/navigate/SecuritySettingsScreen" `
            -TimeoutSec 10 | Out-Null
        Start-Sleep -Milliseconds 500
        $clearCapture = Capture-MihonWindow $clearProcess "task152-windows-capture-cleared" -KeepTopmost
        $clearScreenshot = $clearCapture.path
        $clearLease = $clearCapture.lease
        try {
            $feedback = Invoke-MihonWindowFeedbackCapture $clearLease {
                Capture-Screenshot ($Port + 1) "task152-windows-window-privacy-feedback"
            }
        } finally {
            $clearLease = $null
        }
        $result = [ordered]@{
            status = "PENDING_REVIEW"
            os = "windows"
            capability = $capability
            windowHandle = $applied.handle
            adapter = $adapter
            appliedAffinity = $applied.affinity
            clearedAffinity = $cleared.affinity
            screenshots = @(
                (New-CaptureScreenshotRecord "protected" $protectedScreenshot),
                (New-CaptureScreenshotRecord "clear" $clearScreenshot),
                (New-CaptureScreenshotRecord "feedback" $feedback.path)
            )
            reviewRequired = "Review each screenshot by exact path/hash before declaring protected, clear, or feedback observations."
        }
        Invoke-RunnerPolicy "capture" $result | Out-Null
        $result
    } finally {
        Stop-OwnedAppProcesses
        Restore-SecureScreenProbePreference $probe $original
        Remove-Item -LiteralPath $probe.source -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-InstallerHandoff {
    $probe = New-PlatformProbe
    try {
        $blockers = [Collections.Generic.List[string]]::new()
        $path = if ($InstallerArtifact) {
            [IO.Path]::GetFullPath($InstallerArtifact)
        } else {
            Join-Path $EvidenceDir "mihon-desktop-windows-x86_64-missing.msi"
        }
        $name = [IO.Path]::GetFileName($path)
        $match = [regex]::Match($name, "^mihon-desktop-windows-x86_64-(?<tag>[^\\s]+)\.msi$")
        $releaseTag = if ($match.Success) { $match.Groups["tag"].Value } else { "invalid" }
        if (-not $match.Success) { $blockers.Add("CanonicalArtifactNameMismatch") }
        $productionPath = if ($match.Success) {
            $path
        } else {
            Join-Path $EvidenceDir "mihon-desktop-windows-x86_64-invalid.msi"
        }
        $file = Get-Item -LiteralPath $path -ErrorAction SilentlyContinue
        $sha256 = if ($file) {
            (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
        } else {
            "0" * 64
        }
        $size = if ($file) { [long]$file.Length } else { 0L }
        if (-not $file) { $blockers.Add("CanonicalSignedArtifactMissing") }
        $sidecar = if ($InstallerProvenance) {
            [IO.Path]::GetFullPath($InstallerProvenance)
        } else {
            "$path.task153-provenance.json"
        }
        $verifiedProvenance = $null
        if ($file -and (Test-Path -LiteralPath $sidecar -PathType Leaf)) {
            $previousPreference = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            try {
                $verifiedJson = & $Python $ProvenanceTool verify-installer --repo $RepoRoot `
                    --artifact $path --canonical-name $name --provenance $sidecar 2>$null
                if ($LASTEXITCODE -eq 0) { $verifiedProvenance = $verifiedJson | ConvertFrom-Json }
            } finally {
                $ErrorActionPreference = $previousPreference
            }
        }
        if (-not $verifiedProvenance) { $blockers.Add("InstallerProvenanceMissingOrInvalid") }

        $signature = $null
        if ($file) {
            $raw = Get-AuthenticodeSignature -LiteralPath $path
            $signature = [ordered]@{
                tool = "Get-AuthenticodeSignature"
                status = $raw.Status.ToString()
                publisher = if ($raw.SignerCertificate) { $raw.SignerCertificate.Subject } else { $null }
                statusMessage = $raw.StatusMessage
            }
            if (-not $TrustedPublisher) {
                $blockers.Add("IndependentTrustedPublisherMissing")
            } elseif ($signature.status -ne "Valid" -or
                -not [string]::Equals($signature.publisher, $TrustedPublisher, [StringComparison]::Ordinal)) {
                $blockers.Add("TrustedPublisherSignatureUnavailable")
            }
        } else {
            $signature = [ordered]@{
                tool = "Get-AuthenticodeSignature"
                status = "FileMissing"
                publisher = $null
            }
        }

        $production = Invoke-PlatformProbe $probe @(
            "installer",
            $productionPath,
            $releaseTag,
            "WINDOWS",
            "x86_64",
            $sha256,
            "$size"
            $(if ($TrustedPublisher) { $TrustedPublisher } else { "" })
            "$($ConfirmInstallerHandoff.IsPresent -and $blockers.Count -eq 0)"
        )
        if ($production.preparationResult -ne "ReadyToInstall") {
            $blockers.Add("ProductionDefaultTrustReturned$($production.preparationResult)")
        } elseif (-not $ConfirmInstallerHandoff) {
            $blockers.Add("ExplicitInstallerConfirmationRequired")
        }
        $passed = $blockers.Count -eq 0 -and
            $production.cancellationResult -eq "InstallCancelled" -and
            $production.launchResult -eq "InstallHandedOff"
        $result = [ordered]@{
            status = if ($passed) { "PASS" } else { "BLOCKED" }
            os = "windows"
            blockers = @($blockers)
            releaseTag = $releaseTag
            artifact = if ($file) {
                [ordered]@{ path = $path; name = $name; sha256 = $sha256; size = $size }
            } else {
                $null
            }
            signature = $signature
            trustedIdentity = $TrustedPublisher
            provenance = if ($verifiedProvenance) {
                [ordered]@{ repo = $RepoRoot; sidecarPath = $sidecar }
            } else {
                $null
            }
            production = $production
        }
        Invoke-RunnerPolicy "installer-handoff" $result | Out-Null
        $result
    } finally {
        Remove-Item -LiteralPath $probe.source -Force -ErrorAction SilentlyContinue
    }
}

function New-AcceptanceToken {
    $bytes = [byte[]]::new(32)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    } finally {
        $random.Dispose()
    }
    ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
}

function Start-TestApp([int]$HttpPort, [string]$Token = "") {
    Assert-NoExistingAppProcesses
    $arguments = @("--test-mode", "--test-http-port=$HttpPort", "--screenshot-dir=$EvidenceDir")
    if ($Token) { $arguments += "--platform-acceptance-token=$Token" }
    $process = Start-Process -FilePath $Executable -ArgumentList $arguments -PassThru
    try {
        Wait-Health $HttpPort | Out-Null
        Register-OwnedAppProcesses
        $owners = @(Get-OwnedRootProcessIds)
        $current = @(Select-RootProcessIds (Get-ExactAppProcesses))
        Invoke-RunnerPolicy "pid-owned" @{
            owned = $owners
            current = $current
        } | Out-Null
        Get-Process -Id $owners[0] -ErrorAction Stop
    } catch {
        Register-OwnedAppProcesses
        throw
    }
}

function Invoke-Share([int]$HttpPort, [string]$Token, [string]$Kind) {
    $headers = @{ $AcceptanceHeader = $Token }
    $response = Invoke-WebRequest -UseBasicParsing -Method Post `
        -Uri "http://127.0.0.1:$HttpPort/test/platform-acceptance/share/$Kind" `
        -Headers $headers -ContentType "application/json" -Body "{}" -TimeoutSec ($TimeoutSeconds + 40)
    $response.Content | ConvertFrom-Json
}

function Invoke-FileShareWithAutomation([int]$HttpPort, [string]$Token, [int]$AppPid) {
    $destination = Join-Path $EvidenceDir "task151-windows-host-share.png"
    Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
    $job = Start-Job -ScriptBlock {
        param($Port, $Header, $AcceptanceToken, $Timeout)
        $response = Invoke-WebRequest -UseBasicParsing -Method Post `
            -Uri "http://127.0.0.1:$Port/test/platform-acceptance/share/file" `
            -Headers @{ $Header = $AcceptanceToken } -ContentType "application/json" -Body "{}" `
            -TimeoutSec ($Timeout + 40)
        $response.Content | ConvertFrom-Json
    } -ArgumentList $HttpPort, $AcceptanceHeader, $Token, $TimeoutSeconds
    try {
        Add-Type -AssemblyName UIAutomationClient
        Add-Type -AssemblyName UIAutomationTypes
        Add-Type -AssemblyName System.Windows.Forms
        $root = [Windows.Automation.AutomationElement]::RootElement
        $pidCondition = [Windows.Automation.PropertyCondition]::new(
            [Windows.Automation.AutomationElement]::ProcessIdProperty,
            $AppPid
        )
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        $dialog = $null
        while ([DateTime]::UtcNow -lt $deadline) {
            $windows = $root.FindAll([Windows.Automation.TreeScope]::Children, $pidCondition)
            $dialogs = @($windows | Where-Object {
                $_.Current.ControlType -eq [Windows.Automation.ControlType]::Window -and
                $_.Current.IsEnabled -and
                $_.Current.ClassName -eq "SunAwtDialog"
            })
            if ($dialogs.Count -gt 1) {
                throw "Expected one controlled Save dialog, found $($dialogs.Count)"
            }
            $dialog = $dialogs | Select-Object -First 1
            if ($dialog) { break }
            Start-Sleep -Milliseconds 200
        }
        if (-not $dialog) { throw "Controlled Save dialog was not exposed through UI Automation" }
        $dialogHandle = [IntPtr]$dialog.Current.NativeWindowHandle
        if ($dialogHandle -eq [IntPtr]::Zero) {
            throw "Controlled Save dialog did not expose a native window handle"
        }
        $previousClipboard = Get-Clipboard -Raw -ErrorAction SilentlyContinue
        try {
            Set-Clipboard -Value $destination
            Initialize-Task151NativeWindow
            [Task151NativeWindow]::SetForegroundWindow($dialogHandle) | Out-Null
            Start-Sleep -Milliseconds 300
            Assert-ControlledSaveDialogForeground $dialogHandle $AppPid
            [Windows.Forms.SendKeys]::SendWait("%n")
            Start-Sleep -Milliseconds 200
            Assert-ControlledSaveDialogForeground $dialogHandle $AppPid
            [Windows.Forms.SendKeys]::SendWait("^a")
            Assert-ControlledSaveDialogForeground $dialogHandle $AppPid
            [Windows.Forms.SendKeys]::SendWait("^v")
            Assert-ControlledSaveDialogForeground $dialogHandle $AppPid
            [Windows.Forms.SendKeys]::SendWait("{ENTER}")
        } finally {
            Set-Clipboard -Value ([string]$previousClipboard)
        }

        $completed = Wait-Job -Job $job -Timeout ($TimeoutSeconds + 40)
        if (-not $completed) { throw "File share request did not reach a terminal result" }
        $response = Receive-Job -Job $job
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
            throw "Controlled Save dialog reported completion without the evidence file"
        }
        $signature = [IO.File]::ReadAllBytes($destination)
        if ($signature.Length -lt 8 -or
            -not ($signature[0] -eq 0x89 -and $signature[1] -eq 0x50 -and
                $signature[2] -eq 0x4e -and $signature[3] -eq 0x47)) {
            throw "Controlled Save output is not a PNG"
        }
        [ordered]@{ response = $response; destination = $destination }
    } finally {
        if ($job.State -eq "Running") { Stop-Job -Job $job }
        Remove-Job -Job $job -Force
    }
}

function Invoke-UriCold {
    Assert-NoExistingAppProcesses
    Start-TestApp ($Port + 10) | Out-Null
    Stop-OwnedAppProcesses
    Assert-NoExistingAppProcesses
    if (-not (Test-Path $ProtocolKey)) { throw "Production startup did not register tachiyomi protocol" }
    $productionCommand = (Get-Item -LiteralPath $ProtocolKey).GetValue("")
    if (-not $productionCommand) { throw "Production protocol command is empty" }
    $uri = "tachiyomi://task151-invalid/cold?nonce=$([Guid]::NewGuid().ToString('N'))"
    Start-Process $uri | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $processes = @()
    $window = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        Register-OwnedAppProcesses
        $processes = Get-ExactAppProcesses
        $window = @($processes | Where-Object { $_.ProcessId } | ForEach-Object {
            Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
        } | Where-Object { $_.MainWindowTitle }) | Select-Object -First 1
        if ($window) { break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $window) {
        Stop-OwnedAppProcesses
        throw "OS protocol did not cold-launch a visible Mihon window"
    }
    try {
        $screenshots = @()
        1..4 | ForEach-Object {
            $capture = Capture-MihonWindow $window "task151-windows-uri-cold-$_"
            $screenshots += [string]$capture.path
            Start-Sleep -Milliseconds 350
        }
        [ordered]@{
            status = "PENDING_REVIEW"
            uri = $uri
            productionProtocolCommand = $productionCommand
            launchMechanism = "Windows ShellExecute via Start-Process URI"
            processIds = @($processes.ProcessId)
            windowTitle = $window.MainWindowTitle
            visibleFeedback = [ordered]@{
                screenshots = $screenshots
                review = "Human visual review required; no test flags were added to the OS handler."
            }
        }
    } finally {
        Stop-OwnedAppProcesses
    }
}

function Invoke-UriRunning {
    Assert-NoExistingAppProcesses
    $owner = Start-TestApp $Port
    try {
        $ownerPids = @(Get-OwnedRootProcessIds)
        if ($ownerPids.Count -ne 1) { throw "Expected exactly one owner process before running URI" }
        $productionCommand = (Get-Item -LiteralPath $ProtocolKey).GetValue("")
        $actionCursor = @(Get-ActionHistory $Port).Count
        $uri = "tachiyomi://task151-invalid/running?nonce=$([Guid]::NewGuid().ToString('N'))"
        $dispatch = Start-Process $uri -PassThru
        if (-not $dispatch) { throw "Windows ShellExecute returned no URI handler process" }
        $OwnedProcessIds.Add([int]$dispatch.Id) | Out-Null
        $terminal = Wait-ParserRejected $Port $actionCursor
        if (-not $dispatch.WaitForExit(10000)) {
            Register-OwnedAppProcesses
            throw "Windows URI handler process did not exit after forwarding"
        }
        if ($dispatch.ExitCode -ne 0) {
            throw "Windows URI handler process failed with exit code $($dispatch.ExitCode)"
        }
        Start-Sleep -Seconds 1
        $afterPids = @(Select-RootProcessIds (Get-ExactAppProcesses))
        Invoke-RunnerPolicy "pid-owned" @{
            owned = $ownerPids
            current = $afterPids
        } | Out-Null
        $window = Get-Process -Id (Wait-OwnedWindowProcessId) -ErrorAction Stop
        $capture = Capture-MihonWindow $window "task151-windows-uri-running"
        $screenshotPath = [string]$capture.path
        [ordered]@{
            status = "PASS"
            uri = $uri
            productionProtocolCommand = $productionCommand
            ownerPid = $ownerPids[0]
            dispatchPid = $dispatch.Id
            dispatchExitCode = $dispatch.ExitCode
            uniqueOwnerPreserved = $true
            remainingExactAppProcesses = $afterPids.Count
            actionCursor = $actionCursor
            terminal = $terminal
            visibleFeedback = [ordered]@{
                path = $screenshotPath
                sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $screenshotPath).Hash.ToLowerInvariant()
            }
        }
    } finally {
        Stop-OwnedAppProcesses
    }
}

function Invoke-HostShare {
    Assert-NoExistingAppProcesses
    $textToken = New-AcceptanceToken
    $textApp = Start-TestApp $Port $textToken
    try {
        $text = Invoke-Share $Port $textToken "text"
        $clipboard = Get-Clipboard -Raw
    } finally {
        Stop-OwnedAppProcesses
    }

    $filePort = $Port + 1
    $fileToken = New-AcceptanceToken
    $fileApp = Start-TestApp $filePort $fileToken
    try {
        $appPid = Wait-OwnedWindowProcessId
        $fileAutomation = Invoke-FileShareWithAutomation $filePort $fileToken $appPid
        $file = $fileAutomation.response
    } finally {
        Stop-OwnedAppProcesses
    }
    $textPass = $text.launchResult -eq "CopiedToClipboard" -and
        $text.terminalResult -eq "CopiedToClipboard" -and $clipboard -eq $TextPayload
    $filePass = $file.launchResult -eq "Saved" -and $file.terminalResult -eq "Saved"
    [ordered]@{
        status = if ($textPass -and $filePass) { "PASS" } else { "FAIL" }
        nativeShareExpected = $false
        text = $text
        clipboardMatched = $clipboard -eq $TextPayload
        file = $file
        savedEvidencePath = $fileAutomation.destination
        userFeedback = @($text.terminalResult, $file.terminalResult)
        revealSuppressed = $true
    }
}

function Apply-ColdReview([string]$ReviewFile) {
    if ($Case -ne "uri-cold") { throw "-ColdReviewFile is valid only for uri-cold" }
    if (-not (Test-Path -LiteralPath $ResultPath -PathType Leaf)) {
        throw "Run uri-cold once before applying its review: $ResultPath"
    }
    if (-not (Test-Path -LiteralPath $ReviewFile -PathType Leaf)) {
        throw "Cold review file not found: $ReviewFile"
    }
    $prior = Get-Content -Raw -LiteralPath $ResultPath | ConvertFrom-Json
    $review = Get-Content -Raw -LiteralPath $ReviewFile | ConvertFrom-Json
    if ($prior.taskBaseCommit -ne $provenance.sourceCommit -or
        $prior.taskBaseTree -ne $provenance.sourceTree -or
        $prior.productSource.digest -ne $provenance.productSource.digest -or
        $prior.artifact.sha256 -ne $artifact.sha256) {
        throw "Cold evidence provenance no longer matches the committed build and artifact"
    }
    if ($prior.result.status -ne "PENDING_REVIEW") { throw "Cold evidence is not pending review" }
    if ($review.case -ne "uri-cold" -or $review.decision -notin @("PASS", "FAIL")) {
        throw "Cold review must identify uri-cold and decision PASS or FAIL"
    }
    if (-not $review.visibleFeedback -or -not $review.reviewer -or -not $review.reviewedAtUtc) {
        throw "Cold review requires visibleFeedback, reviewer, and reviewedAtUtc"
    }
    $screenshots = @($prior.result.visibleFeedback.screenshots)
    $reviewed = @($review.screenshots)
    if ($reviewed.Count -ne $screenshots.Count) { throw "Cold review screenshot count mismatch" }
    foreach ($path in $screenshots) {
        $entry = @($reviewed | Where-Object { [IO.Path]::GetFullPath($_.path) -eq [IO.Path]::GetFullPath($path) })
        if ($entry.Count -ne 1) { throw "Cold review does not identify screenshot: $path" }
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
        if ($actual -ne $entry[0].sha256.ToLowerInvariant()) {
            throw "Cold review screenshot hash mismatch: $path"
        }
    }
    $prior.result.status = $review.decision
    $prior.result.visibleFeedback.review = $review
    $prior.finishedAtUtc = [DateTime]::UtcNow.ToString("o")
    $prior | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $ResultPath -Encoding utf8
    Get-Content -LiteralPath $ResultPath
    if ($review.decision -eq "PASS") { exit 0 } else { exit 1 }
}

function Apply-CaptureReview([string]$ReviewPath) {
    if ($Case -ne "capture") { throw "-ReviewFile is valid only for capture" }
    if (-not (Test-Path -LiteralPath $ResultPath -PathType Leaf)) {
        throw "Run capture once before applying its review: $ResultPath"
    }
    if (-not (Test-Path -LiteralPath $ReviewPath -PathType Leaf)) {
        throw "Capture review file not found: $ReviewPath"
    }
    $prior = Get-Content -Raw -LiteralPath $ResultPath | ConvertFrom-Json
    $review = Get-Content -Raw -LiteralPath $ReviewPath | ConvertFrom-Json
    if ($prior.taskBaseCommit -ne $provenance.sourceCommit -or
        $prior.taskBaseTree -ne $provenance.sourceTree -or
        $prior.productSource.digest -ne $provenance.productSource.digest -or
        $prior.artifact.sha256 -ne $artifact.sha256) {
        throw "Capture evidence provenance no longer matches the committed build and artifact"
    }
    $validated = Invoke-RunnerPolicy "capture-review" @{
        runtime = $prior.result
        review = $review
    }
    $prior.result.status = $validated.decision
    $prior.result | Add-Member -MemberType NoteProperty -Name review -Value $validated.review -Force
    $prior.finishedAtUtc = [DateTime]::UtcNow.ToString("o")
    $prior.error = $null
    $prior | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $ResultPath -Encoding utf8
    Get-Content -LiteralPath $ResultPath
    if ($validated.decision -eq "PASS") { exit 0 } else { exit 1 }
}

if ($CapturePriorityPolicyFixture) {
    $fixture = Get-Content -Raw -LiteralPath $CapturePriorityPolicyFixture | ConvertFrom-Json
    $results = @(
        foreach ($scenario in @($fixture.scenarios)) {
            $events = [Collections.Generic.List[string]]::new()
            $setWindowPosCalls = [Collections.Generic.List[object]]::new()
            $windowOwnerPidCalls = [Collections.Generic.List[object]]::new()
            $ownedProcesses = @($scenario.ownedProcesses)
            $windowOwnerPid = [int]$scenario.windowOwnerPid
            $windowOwnerPidSequence = @(
                $scenario.windowOwnerPidSequence |
                    Where-Object { $null -ne $_ }
            )
            $windowOwnerPidState = @{ index = 0 }
            $releaseFailure = [bool]$scenario.releaseFailure
            $failureStage = [string]$scenario.failureStage
            $operations = @{
                ResolveOwnedProcesses = {
                    param([int]$ProcessId)
                    @($ownedProcesses | Where-Object { [int]$_.ProcessId -eq $ProcessId })
                }.GetNewClosure()
                GetWindowOwnerPid = {
                    param([IntPtr]$Window)
                    $ownerPid = if ($windowOwnerPidSequence.Count -gt 0) {
                        $index = [Math]::Min(
                            [int]$windowOwnerPidState.index,
                            $windowOwnerPidSequence.Count - 1
                        )
                        $windowOwnerPidState.index = [int]$windowOwnerPidState.index + 1
                        [int]$windowOwnerPidSequence[$index]
                    } else {
                        $windowOwnerPid
                    }
                    [void]$windowOwnerPidCalls.Add(
                        [ordered]@{ window = $Window.ToInt64(); processId = $ownerPid }
                    )
                    $ownerPid
                }.GetNewClosure()
                SetWindowPos = {
                    param([IntPtr]$Window, [IntPtr]$InsertAfter, [uint32]$Flags)
                    [void]$setWindowPosCalls.Add(
                        [ordered]@{
                            window = $Window.ToInt64()
                            insertAfter = $InsertAfter.ToInt64()
                            flags = $Flags
                        }
                    )
                    [void]$events.Add("priority:$($InsertAfter.ToInt64()):$($Window.ToInt64())")
                    if ($releaseFailure -and
                        $InsertAfter -eq [Task151NativeWindow]::HWND_NOTOPMOST) {
                        return $false
                    }
                    $true
                }.GetNewClosure()
                StopOwnedProcesses = {
                    [void]$events.Add("stop-owned")
                }.GetNewClosure()
                GetWindowRect = {
                    param([IntPtr]$Window)
                    [void]$events.Add("bounds:$($Window.ToInt64())")
                    if ($failureStage -eq "bounds") { throw "fixture bounds failure" }
                    [pscustomobject]@{ Left = 0; Top = 0; Right = 8; Bottom = 8 }
                }.GetNewClosure()
                NewBitmap = {
                    param([int]$Width, [int]$Height)
                    [void]$events.Add("new-bitmap")
                    [pscustomobject]@{ Size = [pscustomobject]@{ Width = $Width; Height = $Height } }
                }.GetNewClosure()
                NewGraphics = {
                    param($Bitmap)
                    [void]$events.Add("new-graphics")
                    [pscustomobject]@{ fixture = "graphics" }
                }.GetNewClosure()
                CopyWindow = {
                    param($Graphics, $Bitmap, $Rect)
                    [void]$events.Add("copy")
                    if ($failureStage -eq "copy") { throw "fixture copy failure" }
                }.GetNewClosure()
                SaveBitmap = {
                    param($Bitmap, [string]$Path)
                    [void]$events.Add("save")
                    if ($failureStage -eq "save") { throw "fixture save failure" }
                }.GetNewClosure()
                DisposeGraphics = {
                    param($Graphics)
                    [void]$events.Add("dispose-graphics")
                    if ($failureStage -eq "dispose-graphics") { throw "fixture graphics dispose failure" }
                }.GetNewClosure()
                DisposeBitmap = {
                    param($Bitmap)
                    [void]$events.Add("dispose-bitmap")
                    if ($failureStage -eq "dispose-bitmap") { throw "fixture bitmap dispose failure" }
                }.GetNewClosure()
            }
            try {
                $lease = New-MihonWindowCaptureLease `
                    -ProcessId ([int]$scenario.processId) `
                    -WindowHandle ([IntPtr][long]$scenario.windowHandle) `
                    -ExpectedExecutable ([string]$scenario.expectedExecutable) `
                    -Operations $operations
                if ([string]$scenario.action -eq "feedback") {
                    Set-MihonWindowCapturePriority $lease $true $operations
                    Invoke-MihonWindowFeedbackCapture $lease {
                        [void]$events.Add("feedback")
                        if ($failureStage -eq "feedback") { throw "fixture feedback failure" }
                        "feedback-result"
                    } $operations | Out-Null
                } else {
                    Invoke-BoundedMihonWindowCapture `
                        $lease `
                        "fixture-capture" `
                        -KeepTopmost:([bool]$scenario.keepTopmost) `
                        -Operations $operations | Out-Null
                }
                [ordered]@{
                    name = [string]$scenario.name
                    status = "PASS"
                    events = @($events)
                    setWindowPosCalls = @($setWindowPosCalls)
                    windowOwnerPidCalls = @($windowOwnerPidCalls)
                    error = $null
                }
            } catch {
                [ordered]@{
                    name = [string]$scenario.name
                    status = "BLOCKED"
                    events = @($events)
                    setWindowPosCalls = @($setWindowPosCalls)
                    windowOwnerPidCalls = @($windowOwnerPidCalls)
                    error = $_.Exception.Message
                }
            }
        }
    )
    [ordered]@{ results = $results } | ConvertTo-Json -Depth 10
    exit 0
}

if ($ActivationPolicyFixture) {
    $fixture = Get-Content -Raw -LiteralPath $ActivationPolicyFixture | ConvertFrom-Json
    $results = @(
        foreach ($scenario in @($fixture.scenarios)) {
            $ownedProcesses = @($scenario.ownedProcesses)
            $snapshots = @($scenario.foregroundSnapshots)
            $snapshotState = @{ index = 0 }
            $showWindowResult = [bool]$scenario.showWindowResult
            $automationFocusResult = [bool]$scenario.automationFocusResult
            $setForegroundResult = [bool]$scenario.setForegroundResult
            $appActivateResult = [bool]$scenario.appActivateResult
            $switchToWindowResult = [bool]$scenario.switchToWindowResult
            $currentThreadId = [uint32]$scenario.currentThreadId
            $foregroundThreadId = [uint32]$scenario.foregroundThreadId
            $targetThreadId = [uint32]$scenario.targetThreadId
            $targetThreadProcessId = if ($null -ne $scenario.targetThreadProcessId) {
                [int]$scenario.targetThreadProcessId
            } else {
                [int]$scenario.targetPid
            }
            $attachThreadResult = [bool]$scenario.attachThreadResult
            $attachmentOutcomes = @($scenario.attachmentOutcomes)
            $attachmentOutcomeState = @{ index = 0 }
            $foregroundRequiresAttachment = [bool]$scenario.foregroundRequiresAttachment
            $appActivateProcessIds = [Collections.Generic.List[int]]::new()
            $switchToWindowHandles = [Collections.Generic.List[long]]::new()
            $inputAttachmentCalls = [Collections.Generic.List[object]]::new()
            $bringToTopHandles = [Collections.Generic.List[long]]::new()
            $focusHandles = [Collections.Generic.List[long]]::new()
            $inputFallbackEvents = [Collections.Generic.List[string]]::new()
            $attachmentState = @{ attached = 0; detached = 0; completed = $false; started = $false }
            $operations = @{
                ResolveOwnedProcesses = {
                    param([int]$ProcessId)
                    @($ownedProcesses)
                }.GetNewClosure()
                ShowWindow = {
                    param([IntPtr]$Window)
                    $showWindowResult
                }.GetNewClosure()
                AutomationFocus = {
                    param([IntPtr]$Window)
                    [ordered]@{
                        elementFound = $automationFocusResult
                        focusApplied = $automationFocusResult
                    }
                }.GetNewClosure()
                SetForeground = {
                    param([IntPtr]$Window)
                    if ([bool]$attachmentState.started) {
                        [void]$inputFallbackEvents.Add("foreground:$($Window.ToInt64())")
                    }
                    $setForegroundResult
                }.GetNewClosure()
                AppActivate = {
                    param([int]$ProcessId)
                    [void]$appActivateProcessIds.Add($ProcessId)
                    $appActivateResult
                }.GetNewClosure()
                SwitchToWindow = {
                    param([IntPtr]$Window)
                    [void]$switchToWindowHandles.Add($Window.ToInt64())
                    $switchToWindowResult
                }.GetNewClosure()
                GetCurrentThread = {
                    $currentThreadId
                }.GetNewClosure()
                GetWindowThread = {
                    param([IntPtr]$Window)
                    if ($Window.ToInt64() -eq [long]$scenario.targetHandle) {
                        [ordered]@{ threadId = $targetThreadId; processId = $targetThreadProcessId }
                    } else {
                        [ordered]@{ threadId = $foregroundThreadId; processId = 780 }
                    }
                }.GetNewClosure()
                AttachThreads = {
                    param([uint32]$FromThread, [uint32]$ToThread, [bool]$Attach)
                    [void]$inputAttachmentCalls.Add(
                        [ordered]@{ from = $FromThread; to = $ToThread; attach = $Attach }
                    )
                    [void]$inputFallbackEvents.Add(
                        "$(if ($Attach) { 'attach' } else { 'detach' }):$FromThread->$ToThread"
                    )
                    $outcome = if ([int]$attachmentOutcomeState.index -lt $attachmentOutcomes.Count) {
                        $attachmentOutcomes[[int]$attachmentOutcomeState.index]
                    } else {
                        $null
                    }
                    $attachmentOutcomeState.index = [int]$attachmentOutcomeState.index + 1
                    if ($null -ne $outcome -and [string]$outcome.throwMessage) {
                        throw [string]$outcome.throwMessage
                    }
                    $result = if ($null -ne $outcome -and $null -ne $outcome.result) {
                        [bool]$outcome.result
                    } else {
                        $attachThreadResult
                    }
                    if ($result -and $Attach) {
                        $attachmentState.started = $true
                        $attachmentState.attached = [int]$attachmentState.attached + 1
                    } elseif ($result) {
                        $attachmentState.detached = [int]$attachmentState.detached + 1
                        if ([int]$attachmentState.attached -gt 0 -and
                            [int]$attachmentState.detached -ge [int]$attachmentState.attached) {
                            $attachmentState.completed = $true
                        }
                    }
                    $result
                }.GetNewClosure()
                BringToTop = {
                    param([IntPtr]$Window)
                    [void]$bringToTopHandles.Add($Window.ToInt64())
                    [void]$inputFallbackEvents.Add("bring:$($Window.ToInt64())")
                    $true
                }.GetNewClosure()
                FocusWindow = {
                    param([IntPtr]$Window)
                    [void]$focusHandles.Add($Window.ToInt64())
                    [void]$inputFallbackEvents.Add("focus:$($Window.ToInt64())")
                    0
                }.GetNewClosure()
                GetForeground = {
                    if ($snapshots.Count -eq 0) { throw "Activation fixture requires a foreground snapshot" }
                    $lastIndex = if ($foregroundRequiresAttachment -and -not [bool]$attachmentState.completed) {
                        [Math]::Max(0, $snapshots.Count - 2)
                    } else {
                        $snapshots.Count - 1
                    }
                    $current = $snapshots[[Math]::Min([int]$snapshotState.index, $lastIndex)]
                    if ([int]$snapshotState.index -lt $lastIndex) {
                        $snapshotState.index = [int]$snapshotState.index + 1
                    }
                    [ordered]@{
                        handle = [long]$current.handle
                        processId = [int]$current.processId
                        className = [string]$current.className
                    }
                }.GetNewClosure()
            }
            try {
                $activation = Activate-OwnedMihonWindow `
                    -TargetPid ([int]$scenario.targetPid) `
                    -TargetHandle ([IntPtr][long]$scenario.targetHandle) `
                    -ExpectedExecutable $Executable `
                    -Operations $operations `
                    -TimeoutMilliseconds 200 `
                    -PollMilliseconds 1
                [ordered]@{
                    name = [string]$scenario.name
                    status = "PASS"
                    activation = $activation
                    appActivateProcessIds = @($appActivateProcessIds)
                    switchToWindowHandles = @($switchToWindowHandles)
                    inputAttachmentCalls = @($inputAttachmentCalls)
                    bringToTopHandles = @($bringToTopHandles)
                    focusHandles = @($focusHandles)
                    inputFallbackEvents = @($inputFallbackEvents)
                    error = $null
                }
            } catch {
                $activation = $_.Exception.Data["Task152WindowActivation"]
                [ordered]@{
                    name = [string]$scenario.name
                    status = "BLOCKED"
                    activation = $activation
                    appActivateProcessIds = @($appActivateProcessIds)
                    switchToWindowHandles = @($switchToWindowHandles)
                    inputAttachmentCalls = @($inputAttachmentCalls)
                    bringToTopHandles = @($bringToTopHandles)
                    focusHandles = @($focusHandles)
                    inputFallbackEvents = @($inputFallbackEvents)
                    error = $_.Exception.Message
                }
            }
        }
    )
    [ordered]@{ results = $results } | ConvertTo-Json -Depth 10
    exit 0
}
if ($ProcessPolicyFixture) {
    $fixture = Get-Content -Raw -LiteralPath $ProcessPolicyFixture | ConvertFrom-Json
    foreach ($processId in @($fixture.ownedProcessIds)) {
        if ($null -ne $processId) {
            $OwnedProcessIds.Add([int]$processId) | Out-Null
        }
    }
    $ownedProcesses = @(
        $fixture.processes |
            Where-Object { $OwnedProcessIds.Contains([int]$_.ProcessId) }
    )
    [ordered]@{
        allRootProcessIds = @(Select-RootProcessIds $fixture.processes)
        ownedRootProcessIds = @(Select-RootProcessIds $ownedProcesses)
        emptyHistoryCount = @(Expand-ActionHistory $fixture.emptyHistory).Count
        populatedHistoryCount = @(Expand-ActionHistory $fixture.populatedHistory).Count
        acceptanceToken = New-AcceptanceToken
    } | ConvertTo-Json -Depth 5
    exit 0
}
if ($InstallerPolicyFixture) {
    $fixture = Get-Content -Raw -LiteralPath $InstallerPolicyFixture | ConvertFrom-Json
    Invoke-RunnerPolicy "installer-handoff" $fixture | ConvertTo-Json -Depth 12
    exit 0
}

$provenance = Get-VerifiedProvenance
$productSource = $provenance.productSource
$artifact = Get-ArtifactIdentity $provenance

if ($ColdReviewFile) {
    Apply-ColdReview ([IO.Path]::GetFullPath($ColdReviewFile))
}
if ($ReviewFile) {
    Apply-CaptureReview ([IO.Path]::GetFullPath($ReviewFile))
}

$payload = [ordered]@{
    schemaVersion = 1
    os = "windows"
    case = $Case
    startedAtUtc = $StartedAt.ToString("o")
    finishedAtUtc = $null
    taskBaseCommit = (& git -C $RepoRoot rev-parse HEAD).Trim()
    taskBaseTree = (& git -C $RepoRoot rev-parse "HEAD^{tree}").Trim()
    productSource = $productSource
    artifact = $artifact
    result = $null
    error = $null
}

$exitCode = 0
try {
    $payload.result = switch ($Case) {
        "uri-cold" { Invoke-UriCold }
        "uri-running" { Invoke-UriRunning }
        "host-share" { Invoke-HostShare }
        "credential-roundtrip" { Invoke-CredentialRoundtrip }
        "capture" { Invoke-CaptureAcceptance }
        "installer-handoff" { Invoke-InstallerHandoff }
    }
    if ($payload.result.status -ne "PASS") { $exitCode = 1 }
} catch {
    $exitCode = 1
    $payload.result = [ordered]@{ status = "BLOCKED" }
    $activation = $_.Exception.Data["Task152WindowActivation"]
    if ($null -eq $activation -and $null -ne $_.Exception.InnerException) {
        $activation = $_.Exception.InnerException.Data["Task152WindowActivation"]
    }
    if ($null -ne $activation) {
        $payload.result.activation = $activation
    }
    $payload.error = $_.Exception.ToString()
    Stop-OwnedAppProcesses
}
$payload.finishedAtUtc = [DateTime]::UtcNow.ToString("o")
$payload | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $ResultPath -Encoding utf8
Get-Content -LiteralPath $ResultPath
exit $exitCode
