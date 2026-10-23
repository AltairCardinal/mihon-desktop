param(
    [string]$DistPath = "D:\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop",
    [switch]$Restore,
    [switch]$ResetOriginal,
    [string]$PriorityModules = ""
)

# Reorders the launcher classpath so the jars that carry the startup classes are
# searched first. The JVM walks the classpath entry by entry for every class, so a
# jar at position 189 costs a lookup in 188 other archives before it is found.

$ErrorActionPreference = "Stop"
$cfg = Join-Path $DistPath "app\Mihon Desktop.cfg"
$backup = "$cfg.classpath-backup"
$originalCopy = "$cfg.classpath-original"

if ($ResetOriginal) {
    Copy-Item $cfg $originalCopy -Force
    Write-Host "Captured the original classpath order."
    return
}

if ($Restore) {
    if (Test-Path $originalCopy) {
        Copy-Item $originalCopy $cfg -Force
        Write-Host "Classpath order restored."
    } else {
        Write-Host "No captured original classpath order found."
    }
    return
}

$defaultPriority = @(
    "app-desktop-jvm", "ui-desktop", "ui-graphics-desktop", "ui-text-desktop", "ui-unit-desktop",
    "ui-util-desktop", "ui-tooling-preview-desktop", "ui-backhandler-desktop", "ui-geometry-desktop",
    "foundation-desktop", "foundation-layout-desktop", "material3-desktop", "material-desktop",
    "material-ripple-desktop", "material-icons-core-desktop", "animation-desktop", "animation-core-desktop",
    "animation-core", "runtime-desktop", "runtime-saveable-desktop", "runtime-retain-desktop",
    "runtime-annotation-desktop", "runtime", "skiko-awt", "skiko-awt-runtime-windows-x64",
    "lifecycle-common", "lifecycle-runtime", "lifecycle-runtime-compose", "lifecycle-viewmodel",
    "lifecycle-viewmodel-savedstate", "savedstate-compose-desktop", "savedstate-desktop",
    "savedstate", "navigationevent-desktop", "navigationevent", "collection-jvm", "annotation-jvm",
    "annotations", "jspecify", "jsr305", "kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8",
    "kotlin-reflect", "kotlinx-coroutines-core-jvm", "kotlinx-coroutines-swing",
    "kotlinx-coroutines-jdk8", "kotlinx-coroutines-slf4j", "kotlinx-coroutines-guava",
    "kotlinx-serialization-core-jvm", "kotlinx-serialization-json-jvm",
    "kotlinx-serialization-json-okio-jvm", "kotlinx-datetime-jvm", "okhttp-jvm", "okio-jvm",
    "coil-compose-core-jvm", "coil-compose-jvm", "coil-core-jvm", "coil-jvm", "coil-network-core-jvm",
    "coil-network-okhttp-jvm", "desktop-jvm", "graphics-jvm", "core-common", "core-jvmcommon",
    "core-metadata-jvm", "domain-jvm", "data-jvm", "source-api-jvm", "i18n-jvm", "common-jvm",
    "injekt", "injekt-api", "injekt-core", "jna", "jna-platform", "sqlite-jdbc",
    "markdown-jvm", "richeditor-compose-desktop", "multiplatform-markdown-renderer-jvm",
    "multiplatform-markdown-renderer-coil3-jvm", "voyager-core-desktop", "voyager-navigator-desktop",
    "voyager-screenmodel-desktop", "voyager-tab-navigator-desktop", "voyager-transitions-desktop",
    "kotlinx-collections-immutable-jvm", "tachiyomi-domain", "tachiyomi-core"
)

$priority = if ($PriorityModules) { $PriorityModules.Split(",") } else { $defaultPriority }

$lines = Get-Content $cfg
$classpathLines = @($lines | Where-Object { $_ -match '^app\.classpath=' })
if ($classpathLines.Count -eq 0) { throw "No classpath entries found in $cfg" }

function Get-ModuleKey([string]$path) {
    $name = ($path -split '\\')[-1]
    # Strip the trailing version and content hash: module-1.2.3-abcdef.jar
    return ($name -replace '-[0-9][^-]*(-[0-9a-f]{8,})?\.jar$', '')
}

$entries = @()
foreach ($line in $classpathLines) {
    $entries += [pscustomobject]@{
        Line = $line
        Key = Get-ModuleKey $line
        Priority = [int]::MaxValue
    }
}

for ($i = 0; $i -lt $entries.Count; $i++) {
    $index = $priority.IndexOf($entries[$i].Key)
    if ($index -ge 0) { $entries[$i].Priority = $index }
}

# Stable sort: the priority decides, the original order breaks ties. Modules that
# appear several times with different versions keep their relative order, so the
# resolved version does not change.
$sorted = $entries | Sort-Object -Property Priority, @{ Expression = { $entries.IndexOf($_) } }

$header = $lines | Where-Object { $_ -notmatch '^app\.classpath=' }
$sortedLines = @($sorted | ForEach-Object { $_.Line })

# The launcher configuration is section based, so the classpath entries have to stay
# inside the [Application] section. They are replaced in place instead of appended.
$updated = New-Object System.Collections.Generic.List[string]
$inserted = $false
foreach ($line in $lines) {
    if ($line -match '^app\.classpath=') {
        if (-not $inserted) {
            $inserted = $true
            foreach ($sortedLine in $sortedLines) { $updated.Add([string]$sortedLine) }
        }
        continue
    }
    $updated.Add($line)
}
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($cfg, $updated, $encoding)

Write-Host "Reordered $($entries.Count) classpath entries; first ten:"
$sorted | Select-Object -First 10 | ForEach-Object { Write-Host ("  {0}" -f (($_.Line -split '\\')[-1])) }
