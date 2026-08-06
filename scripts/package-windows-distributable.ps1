param(
    [Parameter(Mandatory = $true)]
    [string]$SourceDirectory,
    [Parameter(Mandatory = $true)]
    [string]$OutputArchive
)

$ErrorActionPreference = "Stop"

$source = Get-Item -LiteralPath $SourceDirectory -ErrorAction Stop
if (-not $source.PSIsContainer) {
    throw "Windows distributable source is not a directory: $SourceDirectory"
}

$launcher = Join-Path $source.FullName "Mihon Desktop.exe"
$appDirectory = Join-Path $source.FullName "app"
$runtimeDirectory = Join-Path $source.FullName "runtime"
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "Windows distributable launcher is missing: $launcher"
}
if (-not (Test-Path -LiteralPath $appDirectory -PathType Container)) {
    throw "Windows distributable app directory is missing: $appDirectory"
}
if (-not (Test-Path -LiteralPath $runtimeDirectory -PathType Container)) {
    throw "Windows distributable runtime directory is missing: $runtimeDirectory"
}
if (-not (Get-ChildItem -LiteralPath $appDirectory -Recurse -File | Select-Object -First 1)) {
    throw "Windows distributable app directory is empty: $appDirectory"
}
if (-not (Get-ChildItem -LiteralPath $runtimeDirectory -Recurse -File | Select-Object -First 1)) {
    throw "Windows distributable runtime directory is empty: $runtimeDirectory"
}

$archive = [IO.Path]::GetFullPath($OutputArchive)
$archiveParent = Split-Path -Parent $archive
New-Item -ItemType Directory -Path $archiveParent -Force | Out-Null
$temporaryArchive = "$archive.$([Guid]::NewGuid().ToString('N')).tmp.zip"

try {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open($temporaryArchive, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in Get-ChildItem -LiteralPath $source.FullName -Recurse -File) {
            $relative = $file.FullName.Substring($source.FullName.Length).TrimStart([char]'\', [char]'/')
            $entryName = "$($source.Name)/$($relative.Replace('\', '/'))"
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip,
                $file.FullName,
                $entryName,
                [IO.Compression.CompressionLevel]::Optimal
            ) | Out-Null
        }
    } finally {
        $zip.Dispose()
    }

    $zip = [IO.Compression.ZipFile]::OpenRead($temporaryArchive)
    try {
        $prefix = "$($source.Name)/"
        $entries = @($zip.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        $requiredLauncher = "${prefix}Mihon Desktop.exe"
        if ($requiredLauncher -notin $entries) {
            throw "Packaged archive is missing launcher entry: $requiredLauncher"
        }
        if (-not ($entries | Where-Object { $_.StartsWith("${prefix}app/") -and -not $_.EndsWith("/") })) {
            throw "Packaged archive is missing application files under ${prefix}app/"
        }
        if (-not ($entries | Where-Object { $_.StartsWith("${prefix}runtime/") -and -not $_.EndsWith("/") })) {
            throw "Packaged archive is missing runtime files under ${prefix}runtime/"
        }
    } finally {
        $zip.Dispose()
    }

    Move-Item -LiteralPath $temporaryArchive -Destination $archive -Force
    $stream = [IO.File]::OpenRead($archive)
    try {
        $sha256 = [Security.Cryptography.SHA256]::Create()
        try {
            $hash = [BitConverter]::ToString($sha256.ComputeHash($stream)).Replace("-", "").ToLowerInvariant()
        } finally {
            $sha256.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
    $checksum = "$hash  $([IO.Path]::GetFileName($archive))"
    [IO.File]::WriteAllText("$archive.sha256", "$checksum`n", [Text.UTF8Encoding]::new($false))

    Write-Host "Packaged Windows distributable: $archive"
    Write-Host "SHA-256: $hash"
} finally {
    Remove-Item -LiteralPath $temporaryArchive -Force -ErrorAction SilentlyContinue
}
