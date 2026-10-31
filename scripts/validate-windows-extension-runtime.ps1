param(
    [Parameter(Mandatory = $true)] [string]$Executable,
    [Parameter(Mandatory = $true)] [string]$ArtifactPath,
    [Parameter(Mandatory = $true)] [string]$PackageName,
    [Parameter(Mandatory = $true)] [string]$DisplayName,
    [Parameter(Mandatory = $true)] [string]$VersionName,
    [Parameter(Mandatory = $true)] [long]$VersionCode,
    [Parameter(Mandatory = $true)] [string]$RepositoryFingerprint,
    [Parameter(Mandatory = $true)] [string]$ArtifactSha256,
    [Parameter(Mandatory = $true)] [string]$ExpectedVersion,
    [Nullable[long]]$ExpectedSourceId,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

function ConvertTo-ProcessArgument([string]$Value) {
    # ProcessStartInfo.ArgumentList is unavailable in Windows PowerShell 5.1.
    # Quote each argv element using the Windows command-line backslash/quote rules.
    $quoted = [Text.StringBuilder]::new()
    [void]$quoted.Append('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $slashes++
        } elseif ($character -eq '"') {
            [void]$quoted.Append(('\' * (2 * $slashes + 1)))
            [void]$quoted.Append('"')
            $slashes = 0
        } else {
            [void]$quoted.Append(('\' * $slashes))
            [void]$quoted.Append($character)
            $slashes = 0
        }
    }
    [void]$quoted.Append(('\' * (2 * $slashes)))
    [void]$quoted.Append('"')
    return $quoted.ToString()
}

function Start-RuntimeProcess(
    [string]$FilePath,
    [string[]]$Arguments,
    [string]$WorkingDirectory,
    [string]$OutputPath,
    [string]$ErrorPath
) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath
    $start.Arguments = ($Arguments | ForEach-Object { ConvertTo-ProcessArgument $_ }) -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    if ($WorkingDirectory) { $start.WorkingDirectory = $WorkingDirectory }
    $start.RedirectStandardOutput = [bool]$OutputPath
    $start.RedirectStandardError = [bool]$ErrorPath
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $started = $false
    try {
        if (-not $process.Start()) { throw "Could not start runtime process: $FilePath" }
        $started = $true
        # Keep the exact owned process handle, rather than reopening a potentially reused PID.
        [void]$process.Handle
        $ownedProcesses.Add($process)
        foreach ($redirect in @(
            @{ Path = $OutputPath; Stream = $(if ($OutputPath) { $process.StandardOutput.BaseStream }) },
            @{ Path = $ErrorPath; Stream = $(if ($ErrorPath) { $process.StandardError.BaseStream }) }
        )) {
            if ($redirect.Path) {
                $stream = [IO.File]::Open($redirect.Path, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::Read)
                $outputCopies.Add(@{ Stream = $stream; Task = $redirect.Stream.CopyToAsync($stream) })
            }
        }
        return $process
    } catch {
        if ($started -and -not $process.HasExited) { $process.Kill() }
        if (-not $started) { $process.Dispose() }
        throw
    }
}

function Get-DescendantProcessIds([int]$RootProcessId) {
    $allProcesses = @(Get-CimInstance Win32_Process)
    $pending = [System.Collections.Generic.Queue[int]]::new()
    $pending.Enqueue($RootProcessId)
    $descendants = [System.Collections.Generic.List[int]]::new()
    while ($pending.Count -gt 0) {
        $parentId = $pending.Dequeue()
        foreach ($child in $allProcesses | Where-Object { $_.ParentProcessId -eq $parentId }) {
            $childId = [int]$child.ProcessId
            $descendants.Add($childId)
            $pending.Enqueue($childId)
        }
    }
    return $descendants.ToArray()
}

function Get-FreeLoopbackPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Wait-HttpFixture([string]$Url, [DateTime]$Deadline) {
    while ([DateTime]::UtcNow -lt $Deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -Method Head -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    throw "Local extension fixture server did not become ready: $Url"
}

function Get-Sha256Hex([string]$Path) {
    $sha256 = [Security.Cryptography.SHA256]::Create()
    $stream = $null
    try {
        $stream = [IO.File]::OpenRead($Path)
        return [BitConverter]::ToString($sha256.ComputeHash($stream)).Replace("-", "").ToLowerInvariant()
    } finally {
        if ($stream) {
            $stream.Dispose()
        }
        $sha256.Dispose()
    }
}

$resolvedExecutable = (Resolve-Path -LiteralPath $Executable).Path
$resolvedArtifact = (Resolve-Path -LiteralPath $ArtifactPath).Path
$actualSha256 = Get-Sha256Hex $resolvedArtifact
$normalizedSha256 = $ArtifactSha256.Replace(":", "").ToLowerInvariant()
if ($actualSha256 -ne $normalizedSha256) {
    throw "Extension runtime fixture digest mismatch: expected $normalizedSha256, actual $actualSha256"
}

$python = if ($env:MIHON_PYTHON) {
    $env:MIHON_PYTHON
} else {
    (Get-Command python -ErrorAction SilentlyContinue).Source
}
if (-not $python) {
    throw "Python 3 is required to serve the isolated extension runtime fixture"
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) "mihon-extension-runtime-$([Guid]::NewGuid().ToString('N'))"
$serverRoot = Join-Path $tempRoot "server"
$profileRoot = Join-Path $tempRoot "profile"
$resultFile = Join-Path $profileRoot "result.json"
$serverOutput = Join-Path $tempRoot "fixture-server.out.log"
$serverError = Join-Path $tempRoot "fixture-server.err.log"
$artifactName = [IO.Path]::GetFileName($resolvedArtifact)
$servedArtifact = Join-Path $serverRoot $artifactName
$serverProcess = $null
$appProcess = $null
$observedProcessIds = [System.Collections.Generic.HashSet[int]]::new()
$ownedProcesses = [System.Collections.Generic.List[Diagnostics.Process]]::new()
$outputCopies = [System.Collections.Generic.List[object]]::new()
$passed = $false
$runtimeError = $null

New-Item -ItemType Directory -Path $serverRoot, $profileRoot -Force | Out-Null
Copy-Item -LiteralPath $resolvedArtifact -Destination $servedArtifact -Force

try {
    $port = Get-FreeLoopbackPort
    $artifactUrl = "http://127.0.0.1:$port/$artifactName"
    $serverProcess = Start-RuntimeProcess -FilePath $python `
        -Arguments @("-m", "http.server", "$port", "--bind", "127.0.0.1") `
        -WorkingDirectory $serverRoot -OutputPath $serverOutput -ErrorPath $serverError
    Wait-HttpFixture $artifactUrl ([DateTime]::UtcNow.AddSeconds(15))

    $appArguments = @(
        "--test-mode",
        "--headless",
        "--test-extension-runtime",
        "--test-extension-runtime-profile=$profileRoot",
        "--test-extension-runtime-result=$resultFile",
        "--test-extension-runtime-url=$artifactUrl",
        "--test-extension-runtime-package=$PackageName",
        "--test-extension-runtime-name=$DisplayName",
        "--test-extension-runtime-version=$VersionName",
        "--test-extension-runtime-code=$VersionCode",
        "--test-extension-runtime-fingerprint=$RepositoryFingerprint",
        "--test-extension-runtime-sha256=$normalizedSha256"
    )
    if ($null -ne $ExpectedSourceId) {
        $appArguments += "--test-extension-runtime-source-id=$ExpectedSourceId"
    }

    $appProcess = Start-RuntimeProcess -FilePath $resolvedExecutable -Arguments $appArguments
    $observedProcessIds.Add($appProcess.Id) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $result = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($appProcess) {
            foreach ($processId in @(Get-DescendantProcessIds $appProcess.Id)) {
                if ($observedProcessIds.Add($processId)) {
                    $child = Get-Process -Id $processId -ErrorAction SilentlyContinue
                    if ($child -and $child.StartTime -ge $appProcess.StartTime) {
                        [void]$child.Handle
                        $ownedProcesses.Add($child)
                    }
                }
            }
        }
        if (Test-Path -LiteralPath $resultFile -PathType Leaf) {
            try {
                $result = Get-Content -Raw -Encoding UTF8 -LiteralPath $resultFile | ConvertFrom-Json
                break
            } catch {
                # The app may still be replacing the result file; retry until the deadline.
            }
        }
        if ($appProcess.HasExited -and $appProcess.ExitCode -ne 0) {
            throw "Published executable exited with exit code $($appProcess.ExitCode) without runtime acceptance output; diagnostics: $tempRoot"
        }
        Start-Sleep -Milliseconds 100
    }
    if ($null -eq $result) {
        throw "Published executable did not produce extension runtime acceptance output within $TimeoutSeconds seconds; diagnostics: $tempRoot"
    }
    if ($result.appVersion -ne $ExpectedVersion) {
        throw "Runtime version mismatch: expected '$ExpectedVersion', actual '$($result.appVersion)'"
    }
    if ($result.packageName -ne $PackageName) {
        throw "Runtime package mismatch: expected '$PackageName', actual '$($result.packageName)'"
    }
    if (-not $result.success) {
        throw "Extension runtime acceptance failed: $($result.error); diagnostics: $tempRoot"
    }
    if (@($result.sourceIds).Count -eq 0) {
        throw "Extension runtime acceptance loaded no sources; diagnostics: $tempRoot"
    }
    if ($null -ne $ExpectedSourceId -and $ExpectedSourceId -notin @($result.sourceIds)) {
        throw "Expected source $ExpectedSourceId was not loaded; actual=$(@($result.sourceIds) -join ',')"
    }

    $passed = $true
    Write-Host "Extension runtime acceptance passed: $PackageName -> sources $(@($result.sourceIds) -join ',')"
} catch {
    $runtimeError = $_
    throw
} finally {
    $cleanupErrors = [System.Collections.Generic.List[string]]::new()
    for ($index = $ownedProcesses.Count - 1; $index -ge 0; $index--) {
        $owned = $ownedProcesses[$index]
        try {
            if (-not $owned.HasExited) { $owned.Kill() }
        } catch {
            $cleanupErrors.Add("Kill owned process: $($_.Exception.Message)")
        }
        try {
            if (-not $owned.WaitForExit(5000)) { throw "Owned runtime process did not exit: $($owned.Id)" }
        } catch {
            $cleanupErrors.Add("Wait for owned process: $($_.Exception.Message)")
        }
        try {
            $owned.Dispose()
        } catch {
            $cleanupErrors.Add("Dispose owned process: $($_.Exception.Message)")
        }
    }
    foreach ($copy in $outputCopies) {
        try {
            if (-not $copy.Task.Wait(5000)) { throw "Runtime fixture output did not finish copying" }
        } catch {
            $cleanupErrors.Add("Copy runtime output: $($_.Exception.Message)")
        }
        try {
            $copy.Stream.Dispose()
        } catch {
            $cleanupErrors.Add("Dispose runtime output: $($_.Exception.Message)")
        }
    }
    if ($cleanupErrors.Count -gt 0) {
        $passed = $false
        Write-Warning ("Runtime cleanup failed: " + ($cleanupErrors -join '; '))
    }
    if ($passed -and (Test-Path -LiteralPath $tempRoot)) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    } elseif (Test-Path -LiteralPath $tempRoot) {
        Write-Host "Extension runtime acceptance diagnostics retained: $tempRoot"
    }
    if ($cleanupErrors.Count -gt 0 -and $null -eq $runtimeError) {
        throw ("Runtime cleanup failed: " + ($cleanupErrors -join '; '))
    }
}
