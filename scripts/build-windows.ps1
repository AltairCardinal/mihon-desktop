param(
    [switch]$TestOnly,
    [switch]$FullTests,
    [switch]$SkipTests,
    [switch]$PackageMsi,
    [switch]$VersionAllocated,
    [switch]$EvidenceProvenance,
    [string]$ExpectedVersion
)

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$Gradle = Join-Path $RepoRoot "gradlew.bat"
$AppVersionFile = Join-Path $RepoRoot "app-desktop\src\main\kotlin\mihon\desktop\AppVersion.kt"
$BuildOutputExe = Join-Path $RepoRoot "app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe"
$BuildOutputApp = Split-Path $BuildOutputExe
$MsiOutputDir = Join-Path $RepoRoot "app-desktop\tmp\mihon-dist\main\msi"
$ArtifactOutputDir = Join-Path $RepoRoot "app-desktop\artifacts\windows"
$ArtifactPackager = Join-Path $RepoRoot "scripts\package-windows-distributable.ps1"
$UnpackedPublisher = Join-Path $RepoRoot "scripts\publish-windows-unpacked.ps1"
$ExtensionRuntimeValidator = Join-Path $RepoRoot "scripts\validate-windows-extension-runtime.ps1"
$ExtensionRuntimeFixture = Join-Path $RepoRoot "app-desktop\src\test\resources\extensions\real\keiyoushi-manhuagui-1.4.28.apk"
$ProvenanceTool = Join-Path $RepoRoot "scripts\task15-build-provenance.py"
$ProvenanceSource = Join-Path ([IO.Path]::GetTempPath()) "mihon-task151-source-$([Guid]::NewGuid().ToString('N')).json"
$Python = if ($env:MIHON_PYTHON) {
    $env:MIHON_PYTHON
} else {
    (Get-Command python -ErrorAction SilentlyContinue).Source
}

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
    if ($EvidenceProvenance) {
        if (-not $VersionAllocated) {
            throw "Evidence provenance requires an already committed version allocation"
        }
        if (-not $Python) { throw "Python 3 is required; set MIHON_PYTHON to its executable" }
        & $Python $ProvenanceTool source --repo $RepoRoot --require-version-allocation --output $ProvenanceSource
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
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

    if (-not (Test-Path $BuildOutputExe)) {
        throw "Canonical unpackaged executable was not created: $BuildOutputExe"
    }
    if (-not (Test-Path $ArtifactPackager)) {
        throw "Windows distributable packager was not found: $ArtifactPackager"
    }
    if (-not (Test-Path $UnpackedPublisher)) {
        throw "Windows unpackaged publisher was not found: $UnpackedPublisher"
    }
    if (-not (Test-Path $ExtensionRuntimeValidator)) {
        throw "Windows extension runtime validator was not found: $ExtensionRuntimeValidator"
    }
    if (-not (Test-Path $ExtensionRuntimeFixture)) {
        throw "Windows extension runtime fixture was not found: $ExtensionRuntimeFixture"
    }

    $exeInfo = Get-Item $BuildOutputExe
    if ($exeInfo.LastWriteTimeUtc -lt $BuildStartedAt.AddSeconds(-2)) {
        throw "Canonical unpackaged executable is stale: $BuildOutputExe"
    }

    Write-Host ""
    Write-Host "Validating unpackaged runtime version and production APK installation..."
    & $ExtensionRuntimeValidator `
        -Executable $BuildOutputExe `
        -ArtifactPath $ExtensionRuntimeFixture `
        -PackageName "eu.kanade.tachiyomi.extension.zh.manhuagui" `
        -DisplayName "ManHuaGui" `
        -VersionName "1.4.28" `
        -VersionCode 28 `
        -RepositoryFingerprint "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2" `
        -ArtifactSha256 "200cfc4b3b9e98f387824e3cecb13f97f4b0971f8fb678ce49c60aab6856c0c8" `
        -ExpectedVersion $ExpectedVersion

    Write-Host ""
    Write-Host "Validated Mihon Desktop $FullVersion"
    if ($EvidenceProvenance) {
        & $Python $ProvenanceTool seal --repo $RepoRoot --require-version-allocation `
            --source $ProvenanceSource --artifact $BuildOutputApp `
            --output "$BuildOutputApp.task151-provenance.json"
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    & $UnpackedPublisher -SourceDirectory $BuildOutputApp -OutputRoot $ArtifactOutputDir -FullVersion $FullVersion
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $FinalUnpackedApp = Join-Path $ArtifactOutputDir "Mihon-Desktop-$FullVersion-unpacked"
    $FinalUnpackedExe = Join-Path $FinalUnpackedApp "Mihon Desktop.exe"
    if (-not (Test-Path -LiteralPath $FinalUnpackedExe -PathType Leaf)) {
        throw "Final unpackaged executable was not published: $FinalUnpackedExe"
    }

    $ArtifactArchive = Join-Path $ArtifactOutputDir "Mihon-Desktop-$FullVersion-windows.zip"
    & $ArtifactPackager -SourceDirectory $FinalUnpackedApp -OutputArchive $ArtifactArchive
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if (-not (Test-Path -LiteralPath $ArtifactArchive -PathType Leaf)) {
        throw "Deliverable Windows archive was not created: $ArtifactArchive"
    }
    if (-not (Test-Path -LiteralPath "$ArtifactArchive.sha256" -PathType Leaf)) {
        throw "Deliverable Windows archive checksum was not created: $ArtifactArchive.sha256"
    }

    Write-Host "Final unpacked EXE: $FinalUnpackedExe"
    Write-Host "Deliverable ZIP: $ArtifactArchive"
    Write-Host "Deliverable SHA-256: $ArtifactArchive.sha256"
    if ($PackageMsi) {
        Write-Host "MSI output: $MsiOutputDir"
    }
} finally {
    Remove-Item -LiteralPath $ProvenanceSource -Force -ErrorAction SilentlyContinue
    Pop-Location
}
