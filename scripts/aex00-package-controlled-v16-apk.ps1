[CmdletBinding()]
param(
    [ValidateSet('v15', 'v16')]
    [string] $ApiVersion = 'v16',
    [string] $FixtureProject = '',
    [string] $OutputPath = '',
    [string] $AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\Sdk' }),
    [string] $SigningKeystore = 'app-desktop/tmp/aex00-v15-signing/repository.p12',
    [string] $SigningAlias = 'aex00-v15',
    [string] $SigningPassword = 'changeit'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([String]::IsNullOrWhiteSpace($FixtureProject)) {
    $FixtureProject = "app-desktop/tmp/aex00-external-$ApiVersion"
}
if ([String]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = "app-desktop/src/test/resources/extensions/real/aex00-external-$ApiVersion-controlled-sample.apk"
}
$projectPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $FixtureProject))
$outputFile = [IO.Path]::GetFullPath((Join-Path $repoRoot $OutputPath))
$keystorePath = [IO.Path]::GetFullPath((Join-Path $repoRoot $SigningKeystore))
$repoPrefix = $repoRoot.TrimEnd('\') + '\'
foreach ($path in @($projectPath, $outputFile, $keystorePath)) {
    if (-not $path.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "路径必须位于仓库内：$path"
    }
}

$buildTools = Join-Path $AndroidSdk 'build-tools/36.0.0'
$androidJar = Join-Path $AndroidSdk 'platforms/android-36/android.jar'
if ($ApiVersion -eq 'v15') {
    $sampleJar = Join-Path $projectPath 'build/outputs/aex00-external-v15-suspend-only.jar'
    $packageName = 'aex00.external.v15.controlled'
    $versionCode = '150000'
    $versionName = '1.5.0'
    $fixtureLabel = 'AEX-00 v1.5 controlled'
    $entryClass = 'aex00.external.v15.LegacySuspendOnlySource'
} else {
    $sampleJar = Join-Path $projectPath 'build/outputs/aex00-external-v16-sample.jar'
    $packageName = 'aex00.external.v16.controlled'
    $versionCode = '160000'
    $versionName = '1.6.0'
    $fixtureLabel = 'AEX-00 v1.6 controlled'
    $entryClass = 'aex00.external.v16.V16SourceFactory'
}
$extensionLibVersion = if ($ApiVersion -eq 'v15') { '1.5' } else { '1.6' }
foreach ($path in @(
        (Join-Path $buildTools 'd8.bat'),
        (Join-Path $buildTools 'aapt2.exe'),
        (Join-Path $buildTools 'apksigner.bat'),
        $androidJar,
        $sampleJar,
        $keystorePath
    )) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "缺少 APK fixture 输入：$path"
    }
}

$workPath = Join-Path $projectPath "build/aex00-controlled-apk-$ApiVersion"
$dexPath = Join-Path $workPath 'dex'
New-Item -ItemType Directory -Force -Path $dexPath | Out-Null
$manifestPath = Join-Path $workPath 'AndroidManifest.xml'
$unsignedPath = Join-Path $workPath "aex00-external-$ApiVersion-controlled-unsigned.apk"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputFile) | Out-Null

@"
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$packageName"
    android:versionCode="$versionCode"
    android:versionName="$versionName">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
    <uses-feature android:name="tachiyomi.extension" />
    <application
        android:label="$fixtureLabel"
        android:allowBackup="false"
        android:extractNativeLibs="false">
        <meta-data android:name="tachiyomix.name" android:value="$fixtureLabel" />
        <meta-data android:name="tachiyomi.extension.class" android:value="$entryClass" />
        <meta-data android:name="tachiyomi.extension.factory" android:value="$entryClass" />
        <meta-data android:name="tachiyomi.extension.nsfw" android:value="1" />
        <meta-data android:name="tachiyomix.contentWarning" android:value="1" />
        <meta-data android:name="tachiyomix.extensionLib" android:value="$extensionLibVersion" />
    </application>
</manifest>
"@ | Set-Content -LiteralPath $manifestPath -Encoding utf8

Write-Host "将受控 $ApiVersion JAR 转换为 dex（仅包含外部 API 编译的样本类）。"
& (Join-Path $buildTools 'd8.bat') --lib $androidJar --output $dexPath $sampleJar
if ($LASTEXITCODE -ne 0) { throw "d8 失败，退出码：$LASTEXITCODE" }

Write-Host "生成受控 $ApiVersion APK metadata。"
& (Join-Path $buildTools 'aapt2.exe') link -o $unsignedPath -I $androidJar --manifest $manifestPath
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败，退出码：$LASTEXITCODE" }

& jar --update --file $unsignedPath '--date=1980-01-01T00:00:02Z' -C $dexPath classes.dex
if ($LASTEXITCODE -ne 0) { throw "写入 classes.dex 失败，退出码：$LASTEXITCODE" }

Write-Host '使用受控 fixture 签名材料签署 APK。'
& (Join-Path $buildTools 'apksigner.bat') sign --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --ks $keystorePath --ks-type PKCS12 --ks-pass "pass:$SigningPassword" --ks-key-alias $SigningAlias --out $outputFile $unsignedPath
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败，退出码：$LASTEXITCODE" }

& (Join-Path $buildTools 'apksigner.bat') verify --verbose $outputFile
if ($LASTEXITCODE -ne 0) { throw "apksigner verify 失败，退出码：$LASTEXITCODE" }
Write-Host "已生成受控 $ApiVersion APK：$outputFile"
Write-Host "SHA-256：$((Get-FileHash -LiteralPath $outputFile -Algorithm SHA256).Hash.ToLowerInvariant())"
