$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Python = if ($env:MIHON_PYTHON) {
    $env:MIHON_PYTHON
} else {
    (Get-Command python -ErrorAction SilentlyContinue).Source
}
$Bash = if ($env:MIHON_BASH) {
    $env:MIHON_BASH
} else {
    (Get-Command bash -ErrorAction SilentlyContinue).Source
}
if (-not $Python) { throw "Python 3 is required; set MIHON_PYTHON to its executable" }
if (-not $Bash) { throw "Bash is required; set MIHON_BASH to its executable" }
$WindowsRunner = Join-Path $RepoRoot "scripts\task15-platform-evidence-test.ps1"
$MacRunner = Join-Path $RepoRoot "scripts\task15-platform-evidence-test.sh"

$TempRoot = Join-Path ([IO.Path]::GetTempPath()) "mihon-task151-policy-$([Guid]::NewGuid().ToString('N'))"
$VersionPath = Join-Path $TempRoot "app-desktop\src\main\kotlin\mihon\desktop\AppVersion.kt"
$FeaturePath = Join-Path $TempRoot "app-desktop\src\main\kotlin\mihon\desktop\Feature.kt"
$HelperPath = Join-Path $TempRoot "scripts\task15-build-provenance.py"
$BuildPath = Join-Path $TempRoot "scripts\build-desktop.sh"
$ArtifactPath = Join-Path $TempRoot "artifact"
$ArtifactLauncher = Join-Path $ArtifactPath "Mihon Desktop.exe"
$ArtifactJar = Join-Path $ArtifactPath "app\mihon-desktop.jar"
$SourcePath = Join-Path $TempRoot "source.json"
$ProvenancePath = "$ArtifactPath.task151-provenance.json"
$WindowsRunner = Join-Path $RepoRoot "scripts\task15-platform-evidence-test.ps1"
$PowerShell = (Get-Command powershell.exe -ErrorAction SilentlyContinue).Source
if (-not $PowerShell) { throw "Windows PowerShell is required for the Windows runner contract" }

function Invoke-Helper(
    [string[]]$Arguments,
    [bool]$ShouldPass,
    [string]$InputJson = ""
) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($InputJson) {
            $InputJson | & $Python $HelperPath @Arguments 2>&1
        } else {
            & $Python $HelperPath @Arguments 2>&1
        }
        $passed = $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($passed -ne $ShouldPass) {
        throw "Helper expectation failed: pass=$ShouldPass args=$($Arguments -join ' ')"
    }
}

function Invoke-Policy(
    [string]$Kind,
    $Payload,
    [bool]$ShouldPass = $true
) {
    $inputPath = Join-Path $TempRoot "policy-$([Guid]::NewGuid().ToString('N')).json"
    $Payload | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $inputPath -Encoding utf8
    try {
        $output = Invoke-Helper @("policy", "--kind", $Kind, "--input", $inputPath) $ShouldPass
        if ($ShouldPass) {
            return ($output | Select-Object -Last 1) | ConvertFrom-Json
        }
    } finally {
        Remove-Item -LiteralPath $inputPath -Force -ErrorAction SilentlyContinue
    }
}

function RejectionRecord {
    @{
        action = "ExternalActionRejected"
        params = @{ target = "ParserRejected" }
        timestamp = "2026-07-26T00:00:00Z"
    }
}

try {
    $windowsCases = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $WindowsRunner -ListCases 2>$null
    if ($LASTEXITCODE -ne 0 -or "credential-roundtrip" -notin $windowsCases -or
        "capture" -notin $windowsCases -or "installer-handoff" -notin $windowsCases) {
        throw "Windows runner does not expose Task152 cases"
    }
    $unixCases = & $Bash $MacRunner --list-cases 2>$null
    if ($LASTEXITCODE -ne 0 -or "credential-roundtrip" -notin $unixCases -or
        "capture" -notin $unixCases -or "installer-handoff" -notin $unixCases) {
        throw "Unix runner does not expose Task152 cases"
    }

    New-Item -ItemType Directory -Force -Path (Split-Path $VersionPath), (
        Split-Path $HelperPath
    ), (Split-Path $ArtifactJar) | Out-Null
    $CaptureFixtureBin = Join-Path $TempRoot "capture-fixture-bin"
    $CaptureFixtureEvidence = Join-Path $TempRoot "capture-fixture-evidence"
    $CaptureFixtureScript = Join-Path $TempRoot "capture-function-contract.sh"
    New-Item -ItemType Directory -Force -Path $CaptureFixtureBin, $CaptureFixtureEvidence | Out-Null
    Set-Content -LiteralPath (Join-Path $CaptureFixtureBin "import") -Encoding ascii -Value @'
#!/usr/bin/env bash
output="${@: -1}"
printf 'fixture-png' >"$output"
'@
    $UnixCaptureFixtureBin = (& $Bash -c "cygpath -u '$CaptureFixtureBin'").Trim()
    $UnixCaptureFixtureEvidence = (& $Bash -c "cygpath -u '$CaptureFixtureEvidence'").Trim()
    $UnixMacRunner = (& $Bash -c "cygpath -u '$MacRunner'").Trim()
    $UnixPython = (& $Bash -c "cygpath -u '$Python'").Trim()
    Set-Content -LiteralPath $CaptureFixtureScript -Encoding ascii -Value @"
#!/usr/bin/env bash
set -euo pipefail
export PATH="$UnixCaptureFixtureBin`:`$PATH"
export EVIDENCE_DIR="$UnixCaptureFixtureEvidence"
export OS_ID=linux
mihon_window_geometry() { printf '%s\n' '0,0 2x2'; }
mihon_window_handle() { printf '%s\n' '42'; }
eval "`$(awk '/^capture_native_window\(\) \{/{copy=1} copy{print} copy && /^\}/{exit}' "$UnixMacRunner")"
result="`$(capture_native_window 42 task152-contract)"
[[ -s "`$result" ]]
"@
    & $Bash $CaptureFixtureScript
    if ($LASTEXITCODE -ne 0) {
        throw "Unix capture function did not reach its screenshot command under set -u"
    }
    $InstallerExitFixtureScript = Join-Path $TempRoot "installer-result-exit-contract.sh"
    Set-Content -LiteralPath $InstallerExitFixtureScript -Encoding ascii -Value @"
#!/usr/bin/env bash
set -euo pipefail
python3() { "$UnixPython" "`$@"; }
eval "`$(awk '/^installer_result_passed\(\) \{/{copy=1} copy{print} copy && /^\}/{exit}' "$UnixMacRunner")"
installer_result_passed '{"status":"PASS"}'
if installer_result_passed '{"status":"BLOCKED"}'; then
  exit 1
fi
"@
    & $Bash $InstallerExitFixtureScript
    if ($LASTEXITCODE -ne 0) {
        throw "Unix installer PASS/BLOCKED result did not map to exit code 0/non-zero"
    }
    Copy-Item -LiteralPath (Join-Path $RepoRoot "scripts\task15-build-provenance.py") -Destination $HelperPath
    Copy-Item -LiteralPath (Join-Path $RepoRoot "scripts\build-desktop.sh") -Destination $BuildPath
    Set-Content -LiteralPath $VersionPath -Encoding utf8 -Value @"
object AppVersion {
    const val STAGE = 1
    const val FEATURE = 2
    const val BUILD = 1
}
"@
    Set-Content -LiteralPath $FeaturePath -Encoding utf8 -NoNewline -Value "object Feature"
    Set-Content -LiteralPath $ArtifactLauncher -Encoding utf8 -NoNewline -Value "launcher-v1"
    Set-Content -LiteralPath $ArtifactJar -Encoding utf8 -NoNewline -Value "business-code-v1"
    & git -C $TempRoot init --quiet
    & git -C $TempRoot config user.email "task151@example.invalid"
    & git -C $TempRoot config user.name "Task151 Test"
    & git -C $TempRoot config core.autocrlf false
    & git -C $TempRoot add .
    & git -C $TempRoot commit --quiet -m "base"

    (Get-Content -Raw -LiteralPath $VersionPath).Replace(
        "const val BUILD = 1",
        "const val BUILD = 2"
    ) | Set-Content -LiteralPath $VersionPath -Encoding utf8 -NoNewline
    & git -C $TempRoot add $VersionPath
    & git -C $TempRoot commit --quiet -m "allocate build"

    # Actual terminal policy: one exact rejection passes; other or repeated terminal states fail.
    $single = Invoke-Policy "terminal" @{ cursor = 1; history = @(
        @{ action = "ExternalActionPending"; params = @{} },
        (RejectionRecord)
    ) }
    if ($single.status -ne "VALID") { throw "Single ParserRejected terminal did not pass" }
    Invoke-Policy "terminal" @{ cursor = 0; history = @(
        @{ action = "ExternalActionSucceeded"; params = @{ target = "GlobalSearchScreen" } }
    ) } $false
    Invoke-Policy "terminal" @{ cursor = 0; history = @(
        @{ action = "ExternalActionFailed"; params = @{} }
    ) } $false
    Invoke-Policy "terminal" @{ cursor = 0; history = @(
        (RejectionRecord),
        (RejectionRecord)
    ) } $false

    # Model the runner stability window: first observation is valid, delayed terminal invalidates final history.
    $firstObservation = Invoke-Policy "terminal" @{ cursor = 0; history = @((RejectionRecord)) }
    if ($firstObservation.status -ne "VALID") { throw "First terminal observation did not pass" }
    Invoke-Policy "terminal" @{ cursor = 0; history = @(
        (RejectionRecord),
        @{ action = "ExternalActionFailed"; params = @{} }
    ) } $false

    Invoke-Policy "screenshot" @{ success = $true; path = "window.png" } | Out-Null
    Invoke-Policy "screenshot" @{ success = $false; error = "capture failed" } $false
    Invoke-Policy "pid-empty" @{ pids = @() } | Out-Null
    Invoke-Policy "pid-empty" @{ pids = $null } | Out-Null
    Invoke-Policy "pid-empty" @{ pids = [object[]](, $null) } | Out-Null
    Invoke-Policy "pid-empty" @{ pids = @(101) } $false
    Invoke-Policy "pid-owned" @{ owned = 202; current = 202 } | Out-Null
    Invoke-Policy "pid-owned" @{ owned = @(202); current = @(202) } | Out-Null
    Invoke-Policy "pid-owned" @{ owned = @(202); current = @(202, 303) } $false
    $emptyCleanup = Invoke-Policy "pid-cleanup" @{ owned = $null; current = $null }
    if (@($emptyCleanup.kill).Count -ne 0) {
        throw "PID cleanup policy did not normalize null process lists"
    }
    $nullArrayCleanup = Invoke-Policy "pid-cleanup" @{
        owned = [object[]](, $null)
        current = [object[]](, $null)
    }
    if (@($nullArrayCleanup.kill).Count -ne 0) {
        throw "PID cleanup policy did not normalize arrays containing null"
    }
    $cleanup = Invoke-Policy "pid-cleanup" @{ owned = @(202); current = @(101, 202, 303) }
    if (@($cleanup.kill).Count -ne 1 -or $cleanup.kill[0] -ne 202) {
        throw "PID cleanup policy selected a process outside this run"
    }

    Invoke-Policy "credential" @{
        status = "PASS"
        os = "windows"
        backend = "DPAPI"
        storeIdentity = "DesktopCredentialStore(backend=OsCredentialBackend)"
        backendIdentity = "OsCredentialBackend(platform=WINDOWS)"
        service = "mihon-desktop-tracker"
        saved = $true
        firstReadMatched = $true
        overwritten = $true
        secondReadMatched = $true
        deleted = $true
        missingAfterDelete = $true
    } | Out-Null
    Invoke-Policy "credential" @{
        status = "PASS"
        os = "windows"
        backend = "DPAPI"
        storeIdentity = "DesktopCredentialStore(backend=OsCredentialBackend)"
        backendIdentity = "OsCredentialBackend(platform=WINDOWS)"
        service = "mihon-desktop-tracker"
        saved = $true
        firstReadMatched = $true
        overwritten = $false
        secondReadMatched = $true
        deleted = $true
        missingAfterDelete = $true
    } $false
    Invoke-Policy "credential" @{
        status = "PASS"
        os = "windows"
        backend = "DPAPI"
        storeIdentity = "DesktopCredentialStore(backend=MemoryBackend)"
        backendIdentity = "MemoryBackend"
        service = "mihon-desktop-tracker"
        saved = $true
        firstReadMatched = $true
        overwritten = $true
        secondReadMatched = $true
        deleted = $true
        missingAfterDelete = $true
    } $false

    $captureFiles = @{}
    foreach ($role in @("protected", "clear", "feedback")) {
        $path = Join-Path $TempRoot "$role-window.png"
        Set-Content -LiteralPath $path -Encoding utf8 -NoNewline -Value "$role-observation"
        $captureFiles[$role] = @{
            role = $role
            path = $path
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
        }
    }
    $windowsCapture = @{
        status = "PENDING_REVIEW"
        os = "windows"
        capability = "Supported"
        windowHandle = 123
        adapter = @{
            identity = "DesktopWindowPrivacy"
            os = "windows"
            attachResult = "Supported"
            applyResult = "Supported"
            queryResult = "Supported"
            clearResult = "Supported"
        }
        appliedAffinity = 17
        clearedAffinity = 0
        screenshots = @($captureFiles.protected, $captureFiles.clear, $captureFiles.feedback)
    }
    Invoke-Policy "capture" $windowsCapture | Out-Null
    $windowsReview = @{
        case = "capture"
        decision = "PASS"
        reviewer = "Task152 Reviewer"
        reviewedAtUtc = "2026-07-26T00:00:00Z"
        observations = @{
            protected = "MihonExcluded"
            clear = "MihonVisible"
            feedback = "Supported"
        }
        screenshots = @($captureFiles.protected, $captureFiles.clear, $captureFiles.feedback)
    }
    Invoke-Policy "capture-review" @{ runtime = $windowsCapture; review = $windowsReview } | Out-Null
    Invoke-Policy "capture-review" @{ runtime = $windowsCapture } $false

    $wrongHashReview = $windowsReview.Clone()
    $wrongHashReview.screenshots = @(
        @{ role = "protected"; path = $captureFiles.protected.path; sha256 = ("0" * 64) },
        $captureFiles.clear,
        $captureFiles.feedback
    )
    Invoke-Policy "capture-review" @{ runtime = $windowsCapture; review = $wrongHashReview } $false

    $wrongFeedbackReview = $windowsReview.Clone()
    $wrongFeedbackReview.observations = $windowsReview.observations.Clone()
    $wrongFeedbackReview.observations.feedback = "Unsupported"
    Invoke-Policy "capture-review" @{ runtime = $windowsCapture; review = $wrongFeedbackReview } $false

    Invoke-Policy "capture" @{
        status = "PENDING_REVIEW"
        os = "macos"
        capability = "Unsupported"
        windowHandle = 456
        adapter = @{
            identity = "DesktopWindowPrivacy"
            os = "macos"
            queryResult = "Unsupported"
            reason = "macos_capture_affinity_unavailable"
        }
        screenshots = @($captureFiles.protected, $captureFiles.clear, $captureFiles.feedback)
    } | Out-Null
    Invoke-Policy "capture" @{
        status = "PENDING_REVIEW"
        os = "macos"
        capability = "Unsupported"
        windowHandle = 456
        screenshots = @($captureFiles.protected, $captureFiles.clear, $captureFiles.feedback)
    } $false

    $installerArtifact = Join-Path $TempRoot "mihon-desktop-windows-x86_64-v1.2.3.msi"
    Set-Content -LiteralPath $installerArtifact -Encoding utf8 -NoNewline -Value "signed-installer-fixture"
    $installerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $installerArtifact).Hash.ToLowerInvariant()
    $installerSize = (Get-Item -LiteralPath $installerArtifact).Length
    $installerSidecar = "$installerArtifact.task153-provenance.json"
    Invoke-Helper @(
        "seal-installer", "--repo", $TempRoot, "--artifact", $installerArtifact,
        "--canonical-name", ([IO.Path]::GetFileName($installerArtifact)), "--output", $installerSidecar
    ) $true | Out-Null
    Invoke-Helper @(
        "verify-installer", "--repo", $TempRoot, "--artifact", $installerArtifact,
        "--canonical-name", ([IO.Path]::GetFileName($installerArtifact)), "--provenance", $installerSidecar
    ) $true | Out-Null
    $validInstaller = @{
        status = "PASS"
        os = "windows"
        releaseTag = "v1.2.3"
        trustedIdentity = "CN=Mihon Desktop Release"
        provenance = @{ repo = $TempRoot; sidecarPath = $installerSidecar }
        artifact = @{
            path = $installerArtifact
            name = "mihon-desktop-windows-x86_64-v1.2.3.msi"
            sha256 = $installerHash
            size = $installerSize
        }
        signature = @{
            tool = "Get-AuthenticodeSignature"
            status = "Valid"
            publisher = "CN=Mihon Desktop Release"
        }
        production = @{
            identity = "DesktopUpdateInstaller"
            preparationResult = "ReadyToInstall"
            cancellationResult = "InstallCancelled"
            userConfirmation = "Confirmed"
            launchResult = "InstallHandedOff"
            feedback = "InstallerHandedOff"
            productionRevalidation = "prepare+handoff"
        }
    }
    # A plain-text file cannot become a signed installer by forging JSON fields.
    Invoke-Policy "installer-handoff" $validInstaller $false
    foreach ($mutation in @("canonical", "publisher", "thirdParty", "trust", "checksum", "size", "cancel", "confirm", "launch", "feedback")) {
        $changed = $validInstaller | ConvertTo-Json -Depth 10 | ConvertFrom-Json
        switch ($mutation) {
            "canonical" { $changed.artifact.name = "renamed.msi" }
            "publisher" { $changed.signature.publisher = "" }
            "thirdParty" { $changed.signature.publisher = "CN=Third Party" }
            "trust" { $changed.trustedIdentity = "" }
            "checksum" { $changed.artifact.sha256 = "0" * 64 }
            "size" { $changed.artifact.size = $installerSize + 1 }
            "cancel" { $changed.production.cancellationResult = "" }
            "confirm" { $changed.production.userConfirmation = "" }
            "launch" { $changed.production.launchResult = "" }
            "feedback" { $changed.production.feedback = "" }
        }
        Invoke-Policy "installer-handoff" $changed $false
    }
    $manualPass = $validInstaller | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $manualPass.production.preparationResult = "InstallManualOnly"
    $manualPass.production.launchResult = "NotAttempted"
    $manualPass.production.feedback = "ManualOnly"
    Invoke-Policy "installer-handoff" $manualPass $false

    $blockedInstaller = @{
        status = "BLOCKED"
        os = "windows"
        blockers = @("TrustedPublisherSignatureUnavailable")
        artifact = $validInstaller.artifact
        provenance = $validInstaller.provenance
        production = @{
            identity = "DesktopUpdateInstaller"
            preparationResult = "InstallManualOnly"
            userConfirmation = "NotRequested"
            cancellationResult = "NotApplicable"
            launchResult = "NotAttempted"
            feedback = "ManualOnly"
        }
    }
    Invoke-Policy "installer-handoff" $blockedInstaller | Out-Null
    $missingProvenance = $validInstaller | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $missingProvenance.PSObject.Properties.Remove("provenance")
    Invoke-Policy "installer-handoff" $missingProvenance $false
    $wrongProvenance = Get-Content -Raw -LiteralPath $installerSidecar | ConvertFrom-Json
    $wrongProvenance.artifact.sha256 = "0" * 64
    $wrongProvenance | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $installerSidecar -Encoding utf8
    Invoke-Policy "installer-handoff" $blockedInstaller $false
    Invoke-Helper @(
        "verify-installer", "--repo", $TempRoot, "--artifact", $installerArtifact,
        "--canonical-name", ([IO.Path]::GetFileName($installerArtifact)), "--provenance", $installerSidecar
    ) $false | Out-Null
    Invoke-Helper @(
        "seal-installer", "--repo", $TempRoot, "--artifact", $installerArtifact,
        "--canonical-name", ([IO.Path]::GetFileName($installerArtifact)), "--output", $installerSidecar
    ) $true | Out-Null
    $installerFixture = Join-Path $TempRoot "installer-policy-fixture.json"
    $validInstaller | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $installerFixture -Encoding utf8
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $WindowsRunner `
            -Case installer-handoff -EvidenceDir (Join-Path $TempRoot "installer-runner") `
            -InstallerPolicyFixture $installerFixture 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { throw "Actual runner accepted a plain-text forged MSI" }
    } finally {
        $ErrorActionPreference = $previousPreference
    }

    # Execute the real Windows runner's process-tree selection. A packaged app may
    # expose a root and child process with the same executable; only the root owns
    # startup identity and cleanup, while an unrelated exact-path root stays foreign.
    $ProcessFixture = Join-Path $TempRoot "process-tree.json"
    @{
        ownedProcessIds = @(8216, 2696)
        processes = @(
            @{ ProcessId = 8216; ParentProcessId = 100 }
            @{ ProcessId = 2696; ParentProcessId = 8216 }
            @{ ProcessId = 4000; ParentProcessId = 100 }
        )
        emptyHistory = @()
        populatedHistory = @(
            @{ action = "ExternalActionPending"; params = @{} }
            (RejectionRecord)
        )
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ProcessFixture -Encoding utf8
    $runnerOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $WindowsRunner `
        -Case uri-cold `
        -EvidenceDir (Join-Path $TempRoot "runner-evidence") `
        -ProcessPolicyFixture $ProcessFixture
    if ($LASTEXITCODE -ne 0) { throw "Windows runner process-tree fixture failed" }
    $treeSelection = ($runnerOutput -join [Environment]::NewLine) | ConvertFrom-Json
    if (@($treeSelection.allRootProcessIds).Count -ne 2 -or
        8216 -notin @($treeSelection.allRootProcessIds) -or
        4000 -notin @($treeSelection.allRootProcessIds)) {
        throw "Windows runner did not identify the two independent process roots"
    }
    if (@($treeSelection.ownedRootProcessIds).Count -ne 1 -or
        $treeSelection.ownedRootProcessIds[0] -ne 8216) {
        throw "Windows runner treated an owned child as a second app owner"
    }
    if ($treeSelection.emptyHistoryCount -ne 0 -or $treeSelection.populatedHistoryCount -ne 2) {
        throw "Windows runner did not flatten Invoke-RestMethod JSON arrays into action records"
    }
    if ($treeSelection.acceptanceToken -notmatch "^[0-9a-f]{64}$") {
        throw "Windows runner did not generate a 256-bit lowercase hexadecimal acceptance token"
    }

    # Real source -> seal -> verify behavior.
    Invoke-Helper @(
        "source", "--repo", $TempRoot, "--require-version-allocation", "--output", $SourcePath
    ) $true | Out-Null
    Invoke-Helper @(
        "seal", "--repo", $TempRoot, "--require-version-allocation",
        "--source", $SourcePath, "--artifact", $ArtifactPath, "--output", $ProvenancePath
    ) $true | Out-Null
    Invoke-Helper @(
        "verify", "--repo", $TempRoot, "--require-version-allocation",
        "--artifact", $ArtifactPath, "--provenance", $ProvenancePath
    ) $true | Out-Null

    Set-Content -LiteralPath $FeaturePath -Encoding utf8 -NoNewline -Value "object FeatureChanged"
    Invoke-Helper @(
        "verify", "--repo", $TempRoot, "--require-version-allocation",
        "--artifact", $ArtifactPath, "--provenance", $ProvenancePath
    ) $false | Out-Null
    Set-Content -LiteralPath $FeaturePath -Encoding utf8 -NoNewline -Value "object Feature"

    $UntrackedPath = Join-Path $TempRoot "app-desktop\src\debug\kotlin\Untracked.kt"
    New-Item -ItemType Directory -Force -Path (Split-Path $UntrackedPath) | Out-Null
    Set-Content -LiteralPath $UntrackedPath -Encoding utf8 -Value "object Untracked"
    Invoke-Helper @(
        "verify", "--repo", $TempRoot, "--require-version-allocation",
        "--artifact", $ArtifactPath, "--provenance", $ProvenancePath
    ) $false | Out-Null
    Remove-Item -LiteralPath $UntrackedPath

    Set-Content -LiteralPath $ArtifactJar -Encoding utf8 -NoNewline -Value "business-code-v2"
    Invoke-Helper @(
        "verify", "--repo", $TempRoot, "--require-version-allocation",
        "--artifact", $ArtifactPath, "--provenance", $ProvenancePath
    ) $false | Out-Null
    Set-Content -LiteralPath $ArtifactJar -Encoding utf8 -NoNewline -Value "business-code-v1"

    # Execute the real Bash evidence dispatch branch with a fake PowerShell boundary.
    $FakePowerShell = Join-Path $TempRoot "fake-powershell.sh"
    $DispatchLog = Join-Path $TempRoot "dispatch.log"
    Set-Content -LiteralPath $FakePowerShell -Encoding ascii -Value @"
#!/usr/bin/env bash
printf '%s\n' "`$@" > "`$(dirname "`$0")/dispatch.log"
"@
    $UnixFakePowerShell = (& $Bash -c "export PATH=/usr/bin:/bin:/cmd; cygpath -u '$FakePowerShell'").Trim()
    $UnixBuildPath = (& $Bash -c "export PATH=/usr/bin:/bin:/cmd; cygpath -u '$BuildPath'").Trim()
    & $Bash -c "export PATH=/usr/bin:/bin:/cmd; chmod +x '$UnixFakePowerShell'"
    $env:MIHON_HOST_OS = "MINGW64_NT-TASK151"
    $env:MIHON_POWERSHELL_BIN = $UnixFakePowerShell
    try {
        & $Bash -c "export PATH=/usr/bin:/bin:/cmd; '$UnixBuildPath' evidence" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Evidence build dispatch failed" }
    } finally {
        Remove-Item Env:\MIHON_HOST_OS -ErrorAction SilentlyContinue
        Remove-Item Env:\MIHON_POWERSHELL_BIN -ErrorAction SilentlyContinue
    }
    $dispatch = Get-Content -LiteralPath $DispatchLog
    foreach ($required in @("-VersionAllocated", "-EvidenceProvenance", "-ExpectedVersion")) {
        if ($required -notin $dispatch) { throw "Evidence dispatch omitted $required" }
    }

    (Get-Content -Raw -LiteralPath $VersionPath).Replace(
        "const val BUILD = 2",
        "const val BUILD = 4"
    ) | Set-Content -LiteralPath $VersionPath -Encoding utf8 -NoNewline
    & git -C $TempRoot add $VersionPath
    & git -C $TempRoot commit --quiet -m "invalid skipped build allocation"
    Invoke-Helper @(
        "source", "--repo", $TempRoot, "--require-version-allocation", "--output", $SourcePath
    ) $false | Out-Null
} finally {
    if (Test-Path -LiteralPath $TempRoot) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}

Write-Output "Task151 runtime policy/provenance/build contract: PASS"
