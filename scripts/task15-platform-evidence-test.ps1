param(
    [ValidateSet("uri-cold", "uri-running", "host-share", "credential-roundtrip", "capture")]
    [string]$Case,
    [string]$EvidenceDir,
    [string]$Executable,
    [int]$Port = 18151,
    [int]$TimeoutSeconds = 90,
    [string]$ColdReviewFile,
    [string]$ReviewFile,
    [string]$ProcessPolicyFixture,
    [switch]$ListCases
)

$ErrorActionPreference = "Stop"
if ($ListCases) {
    "uri-cold", "uri-running", "host-share", "credential-roundtrip", "capture"
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
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect { public int Left; public int Top; public int Right; public int Bottom; }
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr window, out Rect rect);
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr window);
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

function Capture-MihonWindow([Diagnostics.Process]$Process, [string]$Name) {
    Add-Type -AssemblyName System.Drawing
    Initialize-Task151NativeWindow
    $Process.Refresh()
    if ($Process.MainWindowHandle -eq [IntPtr]::Zero) { throw "Mihon main window handle is unavailable" }
    [Task151NativeWindow]::SetForegroundWindow($Process.MainWindowHandle) | Out-Null
    Start-Sleep -Milliseconds 300
    if ([Task151NativeWindow]::GetForegroundWindow() -ne $Process.MainWindowHandle) {
        throw "Mihon window could not be activated for an exact screenshot"
    }
    $rect = [Task151NativeWindow+Rect]::new()
    if (-not [Task151NativeWindow]::GetWindowRect($Process.MainWindowHandle, [ref]$rect)) {
        throw "Unable to read Mihon window bounds"
    }
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -le 0 -or $height -le 0) { throw "Invalid Mihon window bounds" }
    $bitmap = [Drawing.Bitmap]::new($width, $height)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $path = Join-Path $EvidenceDir "$Name.png"
    try {
        $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
        $bitmap.Save($path, [Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
    $path
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
        $protectedScreenshot = Capture-MihonWindow $protectedProcess "task152-windows-capture-protected"
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
        $clearScreenshot = Capture-MihonWindow $clearProcess "task152-windows-capture-cleared"
        $feedback = Capture-Screenshot ($Port + 1) "task152-windows-window-privacy-feedback"
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
            $screenshots += Capture-MihonWindow $window "task151-windows-uri-cold-$_"
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
        $screenshotPath = Capture-MihonWindow $window "task151-windows-uri-running"
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
    }
    if ($payload.result.status -ne "PASS") { $exitCode = 1 }
} catch {
    $exitCode = 1
    $payload.result = [ordered]@{ status = "BLOCKED" }
    $payload.error = $_.Exception.ToString()
    Stop-OwnedAppProcesses
}
$payload.finishedAtUtc = [DateTime]::UtcNow.ToString("o")
$payload | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $ResultPath -Encoding utf8
Get-Content -LiteralPath $ResultPath
exit $exitCode
