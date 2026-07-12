param(
    [switch]$TestOnly,
    [switch]$FullTests,
    [switch]$SkipTests,
    [switch]$PackageMsi,
    [switch]$VersionAllocated,
    [string]$ExpectedVersion
)

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$Gradle = Join-Path $RepoRoot "gradlew.bat"
$AppVersionFile = Join-Path $RepoRoot "app-desktop\src\main\kotlin\mihon\desktop\AppVersion.kt"
$UnpackedExe = Join-Path $RepoRoot "app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe"
$MsiOutputDir = Join-Path $RepoRoot "app-desktop\tmp\mihon-dist\main\msi"

if (-not (Test-Path $Gradle)) {
    throw "Gradle wrapper not found at $Gradle"
}

function Read-VersionFields {
    $text = Get-Content -Raw -Encoding UTF8 $AppVersionFile
    $stage = [regex]::Match($text, 'const val STAGE = (\d+)').Groups[1].Value
    $feature = [regex]::Match($text, 'const val FEATURE = (\d+)').Groups[1].Value
    $build = [regex]::Match($text, 'const val BUILD = (\d+)').Groups[1].Value
    if (-not $stage -or -not $feature -or -not $build) {
        throw "Unable to read AppVersion STAGE/FEATURE/BUILD from $AppVersionFile"
    }
    return @([int]$stage, [int]$feature, [int]$build)
}

function Set-BuildNumber([int]$Build) {
    $text = Get-Content -Raw -Encoding UTF8 $AppVersionFile
    $updated = [regex]::Replace($text, 'const val BUILD = \d+', "const val BUILD = $Build")
    Set-Content -Encoding UTF8 -NoNewline -Path $AppVersionFile -Value $updated
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

$fields = Read-VersionFields
if (-not $TestOnly -and -not $VersionAllocated) {
    Set-BuildNumber ($fields[2] + 1)
    $fields = Read-VersionFields
}

$Stage = $fields[0]
$Feature = $fields[1]
$Build = $fields[2]
$GitHash = (& git -C $RepoRoot rev-parse --short=7 HEAD).Trim()
$FullVersion = "0.$Stage.$Feature.$Build.$GitHash"
$NativePackageVersion = "$Stage.$Feature.$Build"

if ($ExpectedVersion -and $ExpectedVersion -ne $FullVersion) {
    throw "Version allocation mismatch: expected $ExpectedVersion but AppVersion resolves to $FullVersion"
}
$ExpectedVersion = $FullVersion

Write-Host "Mihon Desktop Windows build"
Write-Host "Full app version: $FullVersion"
Write-Host "Native package version: $NativePackageVersion"

$BuildStartedAt = [DateTime]::UtcNow

Push-Location $RepoRoot
try {
    if (-not $SkipTests) {
        Write-Host ""
        Write-Host "Running desktop JVM tests..."
        $testArgs = @(':app-desktop:jvmTest')
        if ($FullTests) {
            $testArgs += "-PincludeIntegrationTests=true"
        }
        & $Gradle @testArgs
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

    if ($TestOnly) {
        Write-Host ""
        Write-Host "Windows test validation completed without building artifacts."
        return
    }

    if ($PackageMsi) {
        Write-Host ""
        Write-Host "Packaging Windows MSI..."
        & $Gradle :app-desktop:packageMsi
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

    # This must remain the final artifact task because packageMsi cleans the shared output tree.
    Write-Host ""
    Write-Host "Building canonical unpackaged Windows application..."
    & $Gradle --rerun-tasks :app-desktop:createDistributable
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    if (-not (Test-Path $UnpackedExe)) {
        throw "Canonical unpackaged executable was not created: $UnpackedExe"
    }

    $exeInfo = Get-Item $UnpackedExe
    if ($exeInfo.LastWriteTimeUtc -lt $BuildStartedAt.AddSeconds(-2)) {
        throw "Canonical unpackaged executable is stale: $UnpackedExe"
    }

    Write-Host ""
    Write-Host "Validating unpackaged runtime version..."
    $validationProcess = Start-Process -FilePath $UnpackedExe -PassThru
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        $actualTitle = ""
        while ([DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 250
            $processIds = @($validationProcess.Id) + @(Get-DescendantProcessIds $validationProcess.Id)
            foreach ($processId in $processIds) {
                $candidate = Get-Process -Id $processId -ErrorAction SilentlyContinue
                if ($candidate) {
                    $candidate.Refresh()
                    if ($candidate.MainWindowTitle) {
                        $actualTitle = $candidate.MainWindowTitle
                        break
                    }
                }
            }
            if ($actualTitle) {
                break
            }
        }
        if (-not $actualTitle.Contains($ExpectedVersion)) {
            throw "Runtime version mismatch: expected '$ExpectedVersion', window title was '$actualTitle'"
        }
    } finally {
        if ($validationProcess) {
            $cleanupIds = @($validationProcess.Id) + @(Get-DescendantProcessIds $validationProcess.Id)
            [array]::Reverse($cleanupIds)
            foreach ($processId in ($cleanupIds | Select-Object -Unique)) {
                Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            }
        }
    }

    Write-Host ""
    Write-Host "Validated Mihon Desktop $FullVersion"
    Write-Host "Unpacked EXE: $UnpackedExe"
    if ($PackageMsi) {
        Write-Host "MSI output: $MsiOutputDir"
    }
} finally {
    Pop-Location
}
