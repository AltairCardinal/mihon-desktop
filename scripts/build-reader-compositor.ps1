param([string]$OutputDirectory = "$PSScriptRoot\..\app-desktop\artifacts\diagnostics\compositor-native")
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$source = (Resolve-Path -LiteralPath "$PSScriptRoot\native\reader-compositor.cpp").Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
$allowed = [IO.Path]::GetFullPath("$PSScriptRoot\..\app-desktop\artifacts\diagnostics\")
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Capture build output must stay within diagnostic artifacts' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'The Windows C++ build tools are required' }
$environment = Join-Path $installation 'VC\Auxiliary\Build\vcvars64.bat'
$executable = Join-Path $output 'reader-compositor.exe'
$object = Join-Path $output 'reader-compositor.obj'
$command = 'call "' + $environment + '" >nul && cl /nologo /std:c++20 /EHsc /O2 /Fo"' + $object + '" /Fe"' + $executable + '" "' + $source + '" windowsapp.lib d3d11.lib dxgi.lib user32.lib'
& $env:ComSpec /d /c $command
if ($LASTEXITCODE -ne 0) { throw 'Native window capture build failed' }
[ordered]@{sourceSha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash; executable=$executable; executableSha256=(Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'backend.json') -Encoding UTF8
Write-Output "Window capture helper: $executable"
