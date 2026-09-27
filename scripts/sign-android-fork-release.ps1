# Sign only the verified release identity, keeping secrets out of Gradle and process arguments.
[CmdletBinding()]
param(
    [string]$InputApk,
    [string]$OutputApk,
    [switch]$CheckOnly,
    [switch]$Instrumentation,
    [string]$SigningDirectory = 'D:/Android/Signing/mihon-desktop-fork',
    [string]$BuildTools = 'D:/Android/Sdk/build-tools/36.0.0'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$release = @{}
$configPath = Join-Path $PSScriptRoot '../gradle/android-release.properties'
foreach ($line in [IO.File]::ReadAllLines($configPath, [Text.Encoding]::UTF8)) {
    if ($line.Trim() -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
    $parts = $line.Split('=', 2)
    if ($parts.Count -ne 2 -or $release.ContainsKey($parts[0].Trim())) { throw 'Invalid Android release metadata' }
    $release[$parts[0].Trim()] = $parts[1].Trim()
}
if ($release.applicationId -ne 'app.mihon.desktop.fork' -or
    $release.versionCode -notmatch '^[1-9][0-9]*$' -or
    [string]::IsNullOrWhiteSpace($release.versionName) -or
    $release.releaseCertificateSha256 -notmatch '^[0-9a-f]{64}$') { throw 'Invalid Android release metadata' }
$ExpectedCertificate = $release.releaseCertificateSha256
$expectedIdPattern = [regex]::Escape($release.applicationId)
$expectedVersionPattern = [regex]::Escape($release.versionName)
if (-not $CheckOnly) {
if ([string]::IsNullOrWhiteSpace($InputApk) -or [string]::IsNullOrWhiteSpace($OutputApk)) { throw 'InputApk and OutputApk are required for signing' }
$inputFile = (Resolve-Path -LiteralPath $InputApk).Path
$outputFile = [IO.Path]::GetFullPath($OutputApk)
if (Test-Path -LiteralPath $outputFile) { throw 'Refusing to overwrite an existing signed artifact' }
$badging = & (Join-Path $BuildTools 'aapt2.exe') dump badging $inputFile
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect input APK' }
$packageLine = $badging | Where-Object { $_ -like 'package:*' }
if ($Instrumentation) {
    $manifest = (& (Join-Path $BuildTools 'aapt2.exe') dump xmltree $inputFile --file AndroidManifest.xml) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $packageLine -notmatch "name='$expectedIdPattern\.test'" -or
        $manifest -notmatch (':targetPackage[^=\r\n]*="' + $expectedIdPattern + '"')) {
        throw 'Instrumentation APK must target only the fork release host'
    }
} elseif ($packageLine -notmatch "name='$expectedIdPattern'" -or
    $packageLine -notmatch "versionCode='$($release.versionCode)'" -or $packageLine -notmatch "versionName='$expectedVersionPattern'" -or
    ($badging | Where-Object { $_ -like 'application-debuggable*' })) {
    throw 'Input APK does not match the non-debuggable fork release identity'
}
& (Join-Path $BuildTools 'zipalign.exe') -c -p 4 $inputFile
if ($LASTEXITCODE -ne 0) { throw 'Input APK is not aligned' }
}

$keyFile = Join-Path $SigningDirectory 'release.p12'
$passwordFile = Join-Path $SigningDirectory 'password.dpapi.xml'
if (-not (Test-Path -LiteralPath $keyFile -PathType Leaf)) { throw 'Release key is missing; do not create a replacement' }
$securePassword = Import-Clixml -LiteralPath $passwordFile
if ($securePassword -isnot [Security.SecureString]) { throw 'Expected a DPAPI-protected SecureString' }
$previousPassword = $env:MIHON_RELEASE_STORE_PASSWORD
$pointer = [IntPtr]::Zero
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $env:MIHON_RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { 'keytool.exe' }
    $certificateInfo = & $keytool '-J-Duser.language=en' '-J-Duser.country=US' -list -v `
        -keystore $keyFile -alias mihon-desktop-fork -storepass:env MIHON_RELEASE_STORE_PASSWORD 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Release key cannot be opened with the existing DPAPI credential' }
    $fingerprint = ($certificateInfo | Select-String -Pattern '^\s*SHA256:\s*(.*)$').Matches.Groups[1].Value.Replace(':', '').ToLowerInvariant()
    if ($fingerprint -ne $ExpectedCertificate) { throw 'Release key certificate differs from the established identity' }
    # Producing a CSR exercises the actual private key; discard this public diagnostic output.
    $request = & $keytool '-J-Duser.language=en' '-J-Duser.country=US' -certreq `
        -keystore $keyFile -alias mihon-desktop-fork -storepass:env MIHON_RELEASE_STORE_PASSWORD `
        -keypass:env MIHON_RELEASE_STORE_PASSWORD 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Release private key cannot sign with the current credential' }
    $request = $null
    if ($CheckOnly) {
        Write-Output "RELEASE_SIGNING_READY certificateSha256=$fingerprint"
        return
    }
    $directory = Split-Path -Parent $outputFile
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    & (Join-Path $BuildTools 'apksigner.bat') sign --ks $keyFile --ks-key-alias mihon-desktop-fork `
        --ks-pass env:MIHON_RELEASE_STORE_PASSWORD --key-pass env:MIHON_RELEASE_STORE_PASSWORD `
        --out $outputFile $inputFile
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed; any output is not a verified release artifact' }
} finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $securePassword.Dispose()
    $env:MIHON_RELEASE_STORE_PASSWORD = $previousPassword
}

$verification = & (Join-Path $BuildTools 'apksigner.bat') verify --verbose --print-certs $outputFile
if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed' }
$certificateLine = $verification | Where-Object { $_ -like 'Signer #1 certificate SHA-256 digest:*' }
$certificate = ($certificateLine -replace '^.*digest:\s*', '').Trim().ToLowerInvariant()
if ($certificate -ne $ExpectedCertificate.ToLowerInvariant()) { throw 'Certificate mismatch; artifact must not be released' }
$verification | Write-Output
Write-Output $packageLine
Write-Output "Final signed APK: $outputFile"
Write-Output "SHA256: $((Get-FileHash -Algorithm SHA256 -LiteralPath $outputFile).Hash.ToLowerInvariant())"
