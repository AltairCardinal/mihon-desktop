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

$resolvedExecutable = (Resolve-Path -LiteralPath $Executable).Path
$resolvedArtifact = (Resolve-Path -LiteralPath $ArtifactPath).Path
$actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedArtifact).Hash.ToLowerInvariant()
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
$passed = $false

New-Item -ItemType Directory -Path $serverRoot, $profileRoot -Force | Out-Null
Copy-Item -LiteralPath $resolvedArtifact -Destination $servedArtifact -Force

try {
    $port = Get-FreeLoopbackPort
    $artifactUrl = "http://127.0.0.1:$port/$artifactName"
    $serverProcess = Start-Process -FilePath $python `
        -ArgumentList @("-m", "http.server", "$port", "--bind", "127.0.0.1") `
        -WorkingDirectory $serverRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $serverOutput -RedirectStandardError $serverError
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

    $appProcess = Start-Process -FilePath $resolvedExecutable -ArgumentList $appArguments -WindowStyle Hidden -PassThru
    $observedProcessIds.Add($appProcess.Id) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $result = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($appProcess) {
            foreach ($processId in @(Get-DescendantProcessIds $appProcess.Id)) {
                $observedProcessIds.Add($processId) | Out-Null
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
} finally {
    $cleanupIds = @($observedProcessIds)
    [array]::Reverse($cleanupIds)
    foreach ($processId in ($cleanupIds | Select-Object -Unique)) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
    if ($serverProcess) {
        Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($passed -and (Test-Path -LiteralPath $tempRoot)) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    } elseif (Test-Path -LiteralPath $tempRoot) {
        Write-Host "Extension runtime acceptance diagnostics retained: $tempRoot"
    }
}
