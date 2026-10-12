# Research only. Run using Windows PowerShell 5.1, not PowerShell 7.
# Uses installed Windows OCR packs, does not install or download models.
param([Parameter(Mandatory=$true)][string]$ImageDirectory,
      [Parameter(Mandatory=$true)][string]$OutputFile)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType=WindowsRuntime] | Out-Null
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
function Await-WinRT($Operation, $ResultType) {
    $task = $asTask.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
    if (-not $task.Wait(15000)) { throw 'Windows OCR operation timed out' }
    $task.Result
}
$languages = @([Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages)
$rows = @()
foreach ($image in Get-ChildItem -LiteralPath $ImageDirectory -Filter '*.png' | Sort-Object Name) {
    $file = Await-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync($image.FullName)) ([Windows.Storage.StorageFile])
    $stream = Await-WinRT ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Await-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await-WinRT ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        try {
            foreach ($language in $languages) {
                $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($language)
                $timer = [System.Diagnostics.Stopwatch]::StartNew()
                $result = Await-WinRT ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
                $timer.Stop()
                $rows += [pscustomobject]@{sample=$image.BaseName; language=$language.LanguageTag;
                    text=$result.Text; lines=@($result.Lines | ForEach-Object { $_.Text });
                    elapsedMs=$timer.ElapsedMilliseconds}
            }
        } finally { $bitmap.Dispose() }
    } finally { $stream.Dispose() }
}
$report = [pscustomobject]@{engine='Windows.Media.Ocr'; os=[Environment]::OSVersion.Version.ToString();
    packs=@($languages | ForEach-Object { $_.LanguageTag }); results=$rows}
$json = $report | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($OutputFile, $json + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "OCR completed: $($rows.Count) sample-pack pairs; $OutputFile"
