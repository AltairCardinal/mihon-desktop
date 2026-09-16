# One-time local key creation. Never use this key for test fixtures or check its files into Git.
[CmdletBinding()]
param(
    [string]$SigningDirectory = 'D:/Android/Signing/mihon-desktop-fork',
    [string]$Keytool = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/keytool.exe'
)

$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\', '/')
if (-not [IO.Path]::IsPathFullyQualified($SigningDirectory)) { throw 'Use an absolute signing directory' }
$directory = [IO.Path]::GetFullPath($SigningDirectory).TrimEnd('\', '/')
if ($directory.Equals($repository, [StringComparison]::OrdinalIgnoreCase) -or
    $directory.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Release signing files must remain outside the repository'
}
if (Test-Path -LiteralPath $directory) { throw 'Signing directory already exists; refusing to replace or rotate a key' }
if (-not (Test-Path -LiteralPath $Keytool -PathType Leaf)) { throw 'JDK keytool is required' }

New-Item -ItemType Directory -Path $directory | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($identity)
$acl.SetAccessRuleProtection($true, $false)
$rule = [Security.AccessControl.FileSystemAccessRule]::new(
    $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
)
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $directory -AclObject $acl

$keyFile = Join-Path $directory 'release.p12'
$passwordFile = Join-Path $directory 'password.dpapi.xml'
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = [byte[]]::new(48)
try {
    $random.GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes)
    $securePassword = ConvertTo-SecureString -String $password -AsPlainText -Force
    # Windows Export-Clixml encrypts SecureString with DPAPI for this user on this machine.
    $securePassword | Export-Clixml -LiteralPath $passwordFile
    $env:MIHON_RELEASE_STORE_PASSWORD = $password
    & $Keytool '-J-Duser.language=en' '-J-Duser.country=US' -genkeypair -storetype PKCS12 `
        -keystore $keyFile -alias mihon-desktop-fork -keyalg RSA -keysize 4096 -validity 10000 `
        -storepass:env MIHON_RELEASE_STORE_PASSWORD -keypass:env MIHON_RELEASE_STORE_PASSWORD `
        -dname 'CN=Mihon Desktop Fork Release' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed; preserve the directory for diagnosis, do not overwrite it' }
    $certificate = & $Keytool '-J-Duser.language=en' '-J-Duser.country=US' -list -v `
        -keystore $keyFile -alias mihon-desktop-fork -storepass:env MIHON_RELEASE_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Generated key verification failed' }
    $certificate | Select-String -Pattern 'SHA256:' | ForEach-Object { $_.Line.Trim() }
    Write-Output "Release keystore: $keyFile"
    Write-Output "Encrypted local password: $passwordFile"
} finally {
    Remove-Item Env:MIHON_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    if ($securePassword) { $securePassword.Dispose() }
    [Array]::Clear($bytes, 0, $bytes.Length)
    $random.Dispose()
}
