param(
    [string]$Output = "data/src/commonTest/resources/backup/mihon-v0.21.1-full.tachibk"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$authorityRefResource = "data/src/commonTest/resources/backup/mihon-v0.21.1-ref"
$authorityRefPath = Join-Path $root $authorityRefResource
$commit = (Get-Content -Raw $authorityRefPath).Trim()
if ($commit -notmatch "^[0-9a-f]{40}$") {
    throw "Backup fixture authority ref must be a 40-character Git SHA: $authorityRefResource"
}
$scratch = Join-Path $root ".test-tmp/v0211-backup-fixture-generator"
$sourceRoot = Join-Path $scratch "src/main/kotlin"

Remove-Item -Recurse -Force $scratch -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $sourceRoot | Out-Null

@'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "v0211-backup-fixture-generator"
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
    git -C $root show "${commit}:$model" | Set-Content -Encoding UTF8 $destination
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
package mihon.domain.extension.model
data class ExtensionStore(
    val indexUrl: String,
    val name: String,
    val badgeLabel: String?,
    val signingKey: String,
    val contact: ExtensionStoreContact,
    val isLegacy: Boolean,
    val extensionListUrl: String?,
)
data class ExtensionStoreContact(val website: String, val discord: String?)
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "ExtensionStore.kt")
@'
package tachiyomi.domain.chapter.model
import kotlin.time.Instant
data class Chapter(val id: Long = -1, val mangaId: Long = -1, val url: String = "", val name: String = "", val chapterNumber: Double = 0.0, val scanlator: String? = null, val read: Boolean = false, val bookmark: Boolean = false, val lastPageRead: Int = 0, val dateFetch: Instant = Instant.fromEpochMilliseconds(0), val dateUpload: Instant? = null, val sourceOrder: Long = 0, val memo: String = "") { companion object { fun create() = Chapter() } }
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "Chapter.kt")
@'
package tachiyomi.domain.history.model
import kotlin.time.Instant
data class History(val readAt: Instant? = null, val readDuration: Long = 0) { companion object { fun create() = History() } }
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "History.kt")
@'
package tachiyomi.domain.manga.model
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlin.time.Instant
data class Manga(val url: String = "", val title: String = "", val artist: String? = null, val author: String? = null, val description: String? = null, val genre: List<String> = emptyList(), val status: Long = 0, val thumbnailUrl: String? = null, val favoriteAt: Instant? = null, val source: Long = 0, val viewerFlags: Long = 0, val chapterFlags: Long = 0, val updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE, val notes: String = "", val initialized: Boolean = false, val memo: String = "", val nextUpdate: Instant? = null) {
    companion object {
        fun create() = Manga()
        val UNKNOWN_FAVORITE_AT: Instant = Instant.fromEpochMilliseconds(0)
    }
}
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "Manga.kt")
@'
package tachiyomi.domain.track.model
import kotlin.time.Instant
data class Track(val id: Long, val mangaId: Long, val trackerId: Long, val remoteId: Long, val libraryId: Long?, val title: String, val lastChapterRead: Double, val totalChapters: Long, val score: Double, val status: Long, val startDate: Instant?, val finishDate: Instant?, val remoteUrl: String, val private: Boolean)
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "Track.kt")
@'
package mihon.core.common.extensions
import kotlin.time.Instant
val JsonObjectEmptyBytes: ByteArray = byteArrayOf(123, 125) // "{}"
fun ByteArray.toJsonObject(): String = String(this)
fun String.toByteArray(): ByteArray = toByteArray(Charsets.UTF_8)
fun Long.toInstantOrNull(): Instant? = if (this > 0) Instant.fromEpochMilliseconds(this) else null
fun Long.toEpochMillisOrZero(): Long = this
fun Instant?.toEpochMillisOrZero(): Long = this?.toEpochMilliseconds() ?: 0L
'@ | Set-Content -Encoding ASCII (Join-Path $stubDirectory "Extensions.kt")

$mainDirectory = Join-Path $sourceRoot "fixture"
New-Item -ItemType Directory -Force $mainDirectory | Out-Null
@'
package fixture

import eu.kanade.tachiyomi.data.backup.models.*
import mihon.core.common.extensions.JsonObjectEmptyBytes
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

fun main(args: Array<String>) {
    val manga = BackupManga(
        source = 101, url = "/manga", title = "V0.21.1 Canonical manga", artist = "Artist", author = "Author",
        description = "Description", genre = listOf("Action", "Drama"), status = 1, thumbnailUrl = "https://example/cover.jpg",
        dateAdded = 11, viewer = 13, viewer_flags = 17, favorite = true, chapterFlags = 21,
        chapters = listOf(BackupChapter("/chapter", "Chapter 1", "Scanlator", true, true, 7, 12, 13, 1.5f, 2, JsonObjectEmptyBytes)),
        categories = listOf(1),
        history = listOf(BackupHistory("/chapter", 18, 19)),
        tracking = listOf(BackupTracking(9, 10, 11, "https://tracking", "Tracked title", 2.5f, 20, 8.5f, 1, 22, 23, true, 15)),
        excludedScanlators = listOf("Excluded"), notes = "Notes", initialized = true,
        memo = JsonObjectEmptyBytes,
        nextUpdate = 1728900000000L,
    )
    val backup = Backup(
        backupManga = listOf(manga),
        backupCategories = listOf(BackupCategory("Category", 1, 7, 2)),
        backupSources = listOf(BackupSource("Source", 101)),
        backupPreferences = listOf(BackupPreference("theme", StringPreferenceValue("dark"))),
        backupSourcePreferences = listOf(BackupSourcePreferences("101", listOf(BackupPreference("quality", IntPreferenceValue(3))))),
        backupExtensionStores = listOf(
            BackupExtensionStore(
                indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
                name = "Keiyoushi",
                badgeLabel = "Keiyoushi",
                signingKey = "12:34:56:78:9a:bc:de:f0",
                contactWebsite = "https://keiyoushi.github.io",
                contactDiscord = "https://discord.gg/keiyoushi",
                isLegacy = true,
                extensionListUrl = null,
            ),
            BackupExtensionStore(
                indexUrl = "https://example.com/store/index.json",
                name = "Modern Store",
                badgeLabel = "Modern",
                signingKey = "fe:dc:ba:98:76:54:32:10",
                contactWebsite = "https://example.com",
                contactDiscord = "https://discord.gg/example",
                isLegacy = false,
                extensionListUrl = "https://example.com/store/extensions.json",
            ),
        ),
    )
    val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
    GZIPOutputStream(FileOutputStream(args.single())).use { it.write(bytes) }
}
'@ | Set-Content -Encoding UTF8 (Join-Path $mainDirectory "Main.kt")

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
