param(
    [string]$Output = "data/src/commonTest/resources/backup/android-full.tachibk"
)

$ErrorActionPreference = "Stop"
$commit = "6fbf6dfca203d99d6dd32137f2df97ced40c81b8"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$scratch = Join-Path $root ".test-tmp/android-backup-fixture-generator"
$sourceRoot = Join-Path $scratch "src/main/kotlin"

Remove-Item -Recurse -Force $scratch -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $sourceRoot | Out-Null

@'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "android-backup-fixture-generator"
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

$modelPath = "app/src/main/java/eu/kanade/tachiyomi/data/backup/models"
$models = git -C $root ls-tree -r --name-only $commit $modelPath
foreach ($model in $models) {
    $destination = Join-Path $sourceRoot $model.Substring("app/src/main/java/".Length)
    New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
    git -C $root show "${commit}:$model" | Set-Content -Encoding ASCII $destination
}

$stubDirectory = Join-Path $sourceRoot "fixture/stubs"
New-Item -ItemType Directory -Force $stubDirectory | Out-Null
@'
package eu.kanade.tachiyomi.source.model
enum class UpdateStrategy { ALWAYS_UPDATE, ONLY_FETCH_ONCE, NEVER_UPDATE }
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "UpdateStrategy.kt")
@'
package tachiyomi.domain.category.model
data class Category(val id: Long, val name: String, val flags: Long, val order: Long)
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "Category.kt")
@'
package mihon.domain.extensionrepo.model
data class ExtensionRepo(val baseUrl: String, val name: String, val shortName: String?, val website: String, val signingKeyFingerprint: String)
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "ExtensionRepo.kt")
@'
package tachiyomi.domain.chapter.model
data class Chapter(val url: String = "", val name: String = "", val chapterNumber: Double = 0.0, val scanlator: String? = null, val read: Boolean = false, val bookmark: Boolean = false, val lastPageRead: Long = 0, val dateFetch: Long = 0, val dateUpload: Long = 0, val sourceOrder: Long = 0, val lastModifiedAt: Long = 0, val version: Long = 0) { companion object { fun create() = Chapter() } }
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "Chapter.kt")
@'
package tachiyomi.domain.history.model
import java.util.Date
data class History(val readAt: Date = Date(0), val readDuration: Long = 0) { companion object { fun create() = History() } }
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "History.kt")
@'
package tachiyomi.domain.manga.model
import eu.kanade.tachiyomi.source.model.UpdateStrategy
data class Manga(val url: String = "", val title: String = "", val artist: String? = null, val author: String? = null, val description: String? = null, val genre: List<String> = emptyList(), val status: Long = 0, val thumbnailUrl: String? = null, val favorite: Boolean = false, val source: Long = 0, val dateAdded: Long = 0, val viewerFlags: Long = 0, val chapterFlags: Long = 0, val updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE, val lastModifiedAt: Long = 0, val favoriteModifiedAt: Long? = null, val version: Long = 0, val notes: String = "", val initialized: Boolean = false) { companion object { fun create() = Manga() } }
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "Manga.kt")
@'
package tachiyomi.domain.track.model
data class Track(val id: Long, val mangaId: Long, val trackerId: Long, val remoteId: Long, val libraryId: Long, val title: String, val lastChapterRead: Double, val totalChapters: Long, val score: Double, val status: Long, val startDate: Long, val finishDate: Long, val remoteUrl: String, val private: Boolean)
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "Track.kt")

$mainDirectory = Join-Path $sourceRoot "fixture"
New-Item -ItemType Directory -Force $mainDirectory | Out-Null
@'
package fixture

import eu.kanade.tachiyomi.data.backup.models.*
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

fun main(args: Array<String>) {
    val manga = BackupManga(
        source = 101, url = "/manga", title = "Canonical manga", artist = "Artist", author = "Author",
        description = "Description", genre = listOf("Action", "Drama"), status = 1, thumbnailUrl = "https://example/cover.jpg",
        dateAdded = 11, viewer = 13, viewer_flags = 17, favorite = true, chapterFlags = 21,
        chapters = listOf(BackupChapter("/chapter", "Chapter 1", "Scanlator", true, true, 7, 12, 13, 1.5f, 2, 14, 3)),
        categories = listOf(7),
        history = listOf(BackupHistory("/chapter", 18, 19)),
        tracking = listOf(BackupTracking(9, 10, 11, "https://tracking", "Tracked title", 2.5f, 20, 8.5f, 1, 22, 23, true, 15)),
        excludedScanlators = listOf("Excluded"), version = 4, notes = "Notes", initialized = true,
    )
    val backup = Backup(
        backupManga = listOf(manga),
        backupCategories = listOf(BackupCategory("Category", 1, 7, 2)),
        backupSources = listOf(BackupSource("Source", 101)),
        backupPreferences = listOf(BackupPreference("theme", StringPreferenceValue("dark"))),
        backupSourcePreferences = listOf(BackupSourcePreferences("101", listOf(BackupPreference("quality", IntPreferenceValue(3))))),
        backupExtensionRepo = listOf(BackupExtensionRepos("https://repo", "Repo", "R", "https://repo/site", "fingerprint")),
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
