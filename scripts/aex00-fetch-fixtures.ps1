param(
    [string] $Commit = '6e963672174bd6536691af526de37ea5b5bab342',
    [string] $OutputDirectory = 'app-desktop/tmp/aex00-fixtures'
)

$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:HTTP_PROXY = 'http://127.0.0.1:10808'
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
$env:NO_PROXY = 'localhost,127.0.0.1,::1'

if ($Commit -notmatch '^[0-9a-f]{40}$') {
    throw "固定扩展仓库 ref 必须是 40 位 commit：$Commit"
}

$repositoryRoot = (Get-Location).Path
$resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputDirectory))
$resolvedRoot = [System.IO.Path]::GetFullPath($repositoryRoot).TrimEnd('\') + '\'
if (-not $resolvedOutput.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "fixture 输出目录必须位于仓库内：$resolvedOutput"
}
New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null

$files = @(
    @{ Name = 'index.min.json'; Sha256 = 'a8a88c2ade5bd5a5ba8169b2d7da7b237fc597b059d214b52aee4f646bae9221'; Size = 765 },
    @{ Name = 'index.pb'; Sha256 = '5135fef342c60f83c60b9abb865bc69630f0cc4ebc47a1cb75bc9fbebb2814ba'; Size = 104412 },
    @{ Name = 'repo.json'; Sha256 = '5e400e546b91b3977a463205c788a8fac744c1017c7f33b2770f1be14b26968e'; Size = 262 },
    @{ Name = 'keiyoushi-mangadex-1.6.0.apk'; Url = 'https://github.com/keiyoushi/extensions/releases/download/f303b9c/tachiyomi-all.mangadex-v1.6.0.apk'; Sha256 = '35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35'; Size = 98014 },
    @{ Name = 'keiyoushi-mangadex-1.6.0.jar'; Url = 'https://github.com/keiyoushi/extensions/releases/download/f303b9c/tachiyomi-all.mangadex-v1.6.0.jar'; Sha256 = '2781d8593d68f2e79ad54f20949399c44afb703d5679299f84f7356df697008e'; Size = 244643 }
)

foreach ($file in $files) {
    $destination = Join-Path $resolvedOutput $file.Name
    $uri = if ($file.Url) { $file.Url } else { "https://raw.githubusercontent.com/keiyoushi/extensions/$Commit/$($file.Name)" }
    Invoke-WebRequest -Uri $uri -OutFile $destination
    $actualHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    $actualSize = (Get-Item -LiteralPath $destination).Length
    if ($actualHash -ne $file.Sha256 -or $actualSize -ne $file.Size) {
        throw "固定 fixture 校验失败：$($file.Name)，实际 size=$actualSize sha256=$actualHash"
    }
    Write-Output "已核验 $($file.Name)：size=$actualSize sha256=$actualHash"
}

Write-Output '未找到可追溯的 1.5 发布样本或独立 1.5 extension-lib tag；1.5 缺失状态及 1.6 provenance 见 source-api/src/commonTest/resources/aex00/fixture-manifest.json。'
