param(
    [string]$Output = "data/src/commonTest/resources/backup/desktop-first-writer.tachibk"
)

$ErrorActionPreference = "Stop"
$commit = "8c6d18c20bf86c37a11da274f12eb65f31378a8b"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$scratch = Join-Path $root ".test-tmp/desktop-first-writer-fixture-generator"
$sourceRoot = Join-Path $scratch "src/main/kotlin"

Remove-Item -Recurse -Force $scratch -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $sourceRoot | Out-Null

@'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "desktop-first-writer-fixture-generator"
'@ | Set-Content -Encoding ASCII (Join-Path $scratch "settings.gradle.kts")

@'
buildscript {
    dependencies {
        classpath files(fileTree(System.getenv("FIXTURE_GRADLE_CACHE") + "/org.jetbrains.kotlin") {
            include "**/2.3.10/**/*.jar"
            exclude "**/kotlin-compiler-embeddable/**"
        })
    }
}
apply plugin: "org.jetbrains.kotlin.jvm"
apply plugin: "org.jetbrains.kotlin.plugin.serialization"
apply plugin: "application"
dependencies {
    implementation files(fileTree(System.getenv("FIXTURE_GRADLE_CACHE") + "/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm") { include "**/1.10.0/**/*.jar" })
    implementation files(fileTree(System.getenv("FIXTURE_GRADLE_CACHE") + "/org.jetbrains.kotlinx/kotlinx-serialization-protobuf-jvm") { include "**/1.10.0/**/*.jar" })
}
application { mainClass = "fixture.MainKt" }
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
    compilerOptions.freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
}
'@ | Set-Content -Encoding ASCII (Join-Path $scratch "build.gradle")

$model = "app-desktop/src/main/kotlin/mihon/desktop/backup/models/BackupModels.kt"
$destination = Join-Path $sourceRoot "mihon/desktop/backup/models/BackupModels.kt"
New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
git -C $root show "${commit}:$model" | Set-Content -Encoding ASCII $destination

$mainDirectory = Join-Path $sourceRoot "fixture"
New-Item -ItemType Directory -Force $mainDirectory | Out-Null
@'
package fixture

import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.desktop.backup.models.*
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

fun main(args: Array<String>) {
    val manga = BackupManga(
        source = 101, url = "/desktop-manga", title = "Historical Desktop manga",
        artist = "Desktop Artist", author = "Desktop Author", description = "Desktop Description",
        genre = listOf("Action", "History"), status = 2, thumbnailUrl = "https://desktop/cover.jpg",
        dateAdded = 11, viewer = 13,
        chapters = listOf(BackupChapter("/desktop-chapter", "Desktop chapter", "Desktop Scanlator", true, true, 7, 12, 13, 1.5f, 2, 14, 3)),
        categories = listOf(7),
        tracking = listOf(BackupTracking(9, 10, "https://desktop/tracking", "Desktop tracked title", 2.5f, 20, 8.5f, 1, 22, 23, 15)),
        favorite = true, chapterFlags = 21,
        history = listOf(BackupHistory("/desktop-chapter", 18, 19)),
        lastModifiedAt = 14, notes = "Desktop notes", initialized = true,
    )
    val backup = Backup(
        backupManga = listOf(manga),
        backupCategories = listOf(BackupCategory("Desktop Category", 1, 7, 2)),
        backupSources = listOf(BackupSource("Desktop Source", 101)),
    )
    val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
    GZIPOutputStream(FileOutputStream(args.single())).use { it.write(bytes) }
}
'@ | Set-Content -Encoding ASCII (Join-Path $mainDirectory "Main.kt")

$gradle = Join-Path $root ".gradle-local/gradle-8.14.4/bin/gradle.bat"
$env:GRADLE_USER_HOME = Join-Path $root ".gradle-local-home"
$env:FIXTURE_GRADLE_CACHE = Join-Path $env:GRADLE_USER_HOME "caches/modules-2/files-2.1"
$env:TEMP = Join-Path $root ".test-tmp"
$env:TMP = $env:TEMP
$outputPath = Join-Path $root $Output
New-Item -ItemType Directory -Force (Split-Path $outputPath) | Out-Null
& $gradle -p $scratch run --args="`"$outputPath`"" --offline --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Fixture generator failed with exit code $LASTEXITCODE" }
Get-FileHash $outputPath -Algorithm SHA256
