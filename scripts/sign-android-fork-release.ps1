# Sign only the verified release identity, keeping secrets out of Gradle and process arguments.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [switch]$Instrumentation,
    [string]$SigningDirectory = 'D:/Android/Signing/mihon-desktop-fork',
    [string]$BuildTools = 'D:/Android/Sdk/build-tools/36.0.0',
    [string]$ExpectedCertificate = 'bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3'
)

$ErrorActionPreference = 'Stop'
$inputFile = (Resolve-Path -LiteralPath $InputApk).Path
$outputFile = [IO.Path]::GetFullPath($OutputApk)
if (Test-Path -LiteralPath $outputFile) { throw 'Refusing to overwrite an existing signed artifact' }
$badging = & (Join-Path $BuildTools 'aapt2.exe') dump badging $inputFile
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect input APK' }
$packageLine = $badging | Where-Object { $_ -like 'package:*' }
if ($Instrumentation) {
    $manifest = (& (Join-Path $BuildTools 'aapt2.exe') dump xmltree $inputFile --file AndroidManifest.xml) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $packageLine -notmatch "name='app\.mihon\.desktop\.fork\.test'" -or
        $manifest -notmatch ':targetPackage[^=\r\n]*="app\.mihon\.desktop\.fork"') {
        throw 'Instrumentation APK must target only the fork release host'
    }
} elseif ($packageLine -notmatch "name='app\.mihon\.desktop\.fork'" -or
    $packageLine -notmatch "versionCode='26'" -or $packageLine -notmatch "versionName='0\.19\.4-aex\.8'" -or
    ($badging | Where-Object { $_ -like 'application-debuggable*' })) {
    throw 'Input APK does not match the non-debuggable fork release identity'
}
& (Join-Path $BuildTools 'zipalign.exe') -c -p 4 $inputFile
if ($LASTEXITCODE -ne 0) { throw 'Input APK is not aligned' }

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
