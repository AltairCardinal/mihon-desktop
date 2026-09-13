[CmdletBinding()]
param(
    [string] $OutputDirectory = '',
    [ValidateSet('v15', 'v16')]
    [string] $ApiVersion = 'v15',
    [string] $AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\Sdk' })
)

$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([String]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = "app-desktop/tmp/aex00-external-$ApiVersion"
}
$outputPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDirectory))
$repoPrefix = $repoRoot.TrimEnd('\') + '\'
if (-not $outputPath.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "输出目录必须位于仓库内：$outputPath"
}

$upstreamRepository = 'https://github.com/mihonapp/mihon'
if ($ApiVersion -eq 'v15') {
    $upstreamRef = 'f9f938f5afb3661cb6a7b7d8b11b8ae1d662a7c5'
    $upstreamTag = 'v0.19.4'
    $projectName = 'aex00-external-v15'
    $samplePackage = 'aex00/external/v15'
    $sampleClass = 'LegacySuspendOnlySource'
    $testClass = 'LegacySuspendOnlySourceTest'
    $sourceFiles = @(
        @{ Name = 'Source.kt'; RelativePath = 'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/Source.kt' }
        @{ Name = 'Page.kt'; RelativePath = 'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/Page.kt' }
        @{ Name = 'SChapter.kt'; RelativePath = 'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SChapter.kt' }
        @{ Name = 'SChapterImpl.kt'; RelativePath = 'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SChapterImpl.kt' }
        @{ Name = 'SManga.kt'; RelativePath = 'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SManga.kt' }
        @{ Name = 'SMangaImpl.kt'; RelativePath = 'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SMangaImpl.kt' }
        @{ Name = 'UpdateStrategy.kt'; RelativePath = 'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/UpdateStrategy.kt' }
        @{ Name = 'ProgressListener.kt'; RelativePath = 'core/common/src/main/kotlin/eu/kanade/tachiyomi/network/ProgressListener.kt' }
        @{ Name = 'RxCoroutineBridge.kt'; RelativePath = 'core/common/src/main/kotlin/tachiyomi/core/common/util/lang/RxCoroutineBridge.kt' }
        @{ Name = 'RxExtension.kt'; RelativePath = 'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/util/RxExtension.kt' }
    )
} else {
    $upstreamRef = 'df6507256acce8e7f3660783a3db6dbd1a31b6b5'
    $upstreamTag = 'v0.20.4'
    $projectName = 'aex00-external-v16'
    $samplePackage = 'aex00/external/v16'
    $sampleClass = 'V16Source'
    $testClass = 'V16ContractTest'
    $sourceFiles = @(
        @{ Name = 'Source.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/Source.kt' }
        @{ Name = 'SourceFactory.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/SourceFactory.kt' }
        @{ Name = 'CatalogueSource.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/CatalogueSource.kt' }
        @{ Name = 'Filter.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/Filter.kt' }
        @{ Name = 'FilterList.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/FilterList.kt' }
        @{ Name = 'MangasPage.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/MangasPage.kt' }
        @{ Name = 'Page.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/Page.kt' }
        @{ Name = 'SChapter.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SChapter.kt' }
        @{ Name = 'SChapterImpl.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SChapterImpl.kt' }
        @{ Name = 'SManga.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SManga.kt' }
        @{ Name = 'SMangaImpl.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SMangaImpl.kt' }
        @{ Name = 'SMangaUpdate.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SMangaUpdate.kt' }
        @{ Name = 'UpdateStrategy.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/UpdateStrategy.kt' }
        @{ Name = 'ProgressListener.kt'; RelativePath = 'core/common/src/main/kotlin/eu/kanade/tachiyomi/network/ProgressListener.kt' }
        @{ Name = 'RxCoroutineBridge.kt'; RelativePath = 'core/common/src/main/kotlin/tachiyomi/core/common/util/lang/RxCoroutineBridge.kt' }
        @{ Name = 'JsonObject.kt'; RelativePath = 'core/common/src/main/kotlin/mihon/core/common/extensions/JsonObject.kt' }
        @{ Name = 'RxExtension.kt'; RelativePath = 'source-api/src/main/kotlin/eu/kanade/tachiyomi/util/RxExtension.kt' }
    )
}
$archiveName = if ($ApiVersion -eq 'v15') {
    'aex00-external-v15-suspend-only.jar'
} else {
    "$projectName-sample.jar"
}
$expectedSourceSha256 = @{
    'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/Source.kt' = 'f20af6409d9449284eda5208c7c95a3963fc1b5696ce0fd145f414f56e5f2ebd'
    'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/Page.kt' = '13bddc0f0fb954c45e6ca3294174d44b67eec59ef86b4ece4bc431a559425332'
    'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SChapter.kt' = '90d87653ad62ca1f7b4ebe90bd9da56df1ef50e4ba98ddb2adaf7957a4e644d9'
    'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SChapterImpl.kt' = '848fb9b1a312f922c7f5579dc775bd5c0e7bf5c90cd40185aa2fbef38a263a4d'
    'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SManga.kt' = 'eba70b03fdb696c29b6a3a9ba44ef9ac23b2dd59972cd904dc90908c42af39e1'
    'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SMangaImpl.kt' = '2eaabd1eaa2a868350da2ab9a9ca7ecbc0d2eee0707efb516faa3ad9552c8494'
    'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/UpdateStrategy.kt' = 'dcdf5b3a60108c7cc1ffc8f2954fc21ac9bacc5ca066c40037533cf17b03b3eb'
    'core/common/src/main/kotlin/eu/kanade/tachiyomi/network/ProgressListener.kt' = 'a42a1c653053608d5a4b593d7600708778d1cbb05a20391eef99678c868c1670'
    'core/common/src/main/kotlin/tachiyomi/core/common/util/lang/RxCoroutineBridge.kt' = '126cf2276f3816c1426736ee612ed8d9d2ab912d4ebd5a23b9fce196520499b9'
    'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/util/RxExtension.kt' = 'bfbd0c556439af43dbfdfeca7e099701400d5d9395ef419456e655661b6ffda5'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/Source.kt' = 'c31c6b24ef9ef038e749500032df0a8a707b9ce9074abaa4fb0df121e19816f5'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/SourceFactory.kt' = '4ff3f8e591d8d059dc665735f0cb1a7fd478c00db73e7b3928b95407c383bc44'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/CatalogueSource.kt' = 'f83a7f5d698bc98213d0a07fd30ae50a38f15bac3e38d9e6101a18d78ed671ea'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/Filter.kt' = '077c1014fb22786502aa91e402e4516c7717351e4fc0bce5d8cbf09c39fdc201'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/FilterList.kt' = '0d914693cc6302260fe56294d58cebf67404a54f31744178e9cef10ae5512d39'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/MangasPage.kt' = 'd8beea21945b98493f6531c4c9b5ba1169cd5dce5fac4de0701adb1f5401fad8'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/Page.kt' = '13bddc0f0fb954c45e6ca3294174d44b67eec59ef86b4ece4bc431a559425332'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SChapter.kt' = '39c03a0b9011afbead061434daf79d696b12c0a5a0ae4a64f258f886f66499db'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SChapterImpl.kt' = '732dd62c5539049656a3791481a3411956e5d162476e70aee11a4ea2cc58e1ea'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SManga.kt' = '1848776bbd108f90063a04004286eacd82ab0cf1821be0b7ea59a09f7e8a7cdd'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SMangaImpl.kt' = 'e2608c2ba7e6712a10948802769a99a623947b109bd5b1b898863f7941e1b00d'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SMangaUpdate.kt' = 'f298847f29e2b70170487ef3bc3425c80f23f1e5ade7bb22f66851779f3c5b18'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/UpdateStrategy.kt' = 'dcdf5b3a60108c7cc1ffc8f2954fc21ac9bacc5ca066c40037533cf17b03b3eb'
    'core/common/src/main/kotlin/mihon/core/common/extensions/JsonObject.kt' = 'a7003917b321a7a59a3401aa35ff4f05529a480ec89aa741813eccd9d1c07e41'
    'source-api/src/main/kotlin/eu/kanade/tachiyomi/util/RxExtension.kt' = '609f23b727c6ec388d109c5d06530ba952b336dbc166bf79bd5e26c06cdeedb7'
}
$rawBase = "$upstreamRepository/raw/$upstreamRef/"

$sourceRoot = Join-Path $outputPath 'src/main/kotlin'
New-Item -ItemType Directory -Force -Path $sourceRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $outputPath 'src/test/kotlin') | Out-Null

$downloaded = @()
foreach ($sourceFile in $sourceFiles) {
    $destination = Join-Path $outputPath ('upstream/' + $sourceFile.RelativePath)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    $url = $rawBase + $sourceFile.RelativePath
    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        Write-Host "复用已下载的固定上游源码：$($sourceFile.RelativePath)"
    } else {
        Write-Host "固定上游源码：$($sourceFile.RelativePath)"
        Invoke-WebRequest -Uri $url -OutFile $destination -UseBasicParsing
    }
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    $expectedHash = $expectedSourceSha256[$sourceFile.RelativePath]
    if ($hash -ne $expectedHash) {
        throw "固定上游源码 SHA-256 不匹配：$($sourceFile.RelativePath)，实际 $hash，预期 $expectedHash"
    }
    $blobSpec = "{0}:{1}" -f $upstreamRef, $sourceFile.RelativePath
    $expectedBlobSha1 = (& git rev-parse $blobSpec 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $expectedBlobSha1 -notmatch '^[0-9a-f]{40}$') {
        throw "固定上游源码原始 blob 不可解析：$blobSpec"
    }
    $actualBlobSha1 = (& git hash-object --no-filters $destination).Trim()
    if ($LASTEXITCODE -ne 0 -or $actualBlobSha1 -ne $expectedBlobSha1) {
        throw "固定上游源码原始 blob 不匹配：$($sourceFile.RelativePath)，实际 $actualBlobSha1，预期 $expectedBlobSha1"
    }
    $downloaded += [PSCustomObject]@{
        name = $sourceFile.Name
        path = $sourceFile.RelativePath
        url = $url
        sha256 = $hash
        gitBlobSha1 = $actualBlobSha1
        sizeBytes = (Get-Item -LiteralPath $destination).Length
    }
}

# Compile the unchanged upstream API sources. RxExtension.kt is an expect declaration in the
# KMP project; the fixture supplies the corresponding JVM adapter used by the fixed API snapshot.
$compileFiles = $sourceFiles | Where-Object { $_.Name -ne 'RxExtension.kt' }
foreach ($sourceFile in $compileFiles) {
    $sourcePath = Join-Path $outputPath ('upstream/' + $sourceFile.RelativePath)
    $targetPath = Join-Path $sourceRoot $sourceFile.RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetPath) | Out-Null
    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
}

$adapterPath = Join-Path $sourceRoot 'eu/kanade/tachiyomi/util/RxExtensionJvm.kt'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $adapterPath) | Out-Null
@'
package eu.kanade.tachiyomi.util

import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle as awaitSingleUpstream

/** The upstream Android actual adapter, kept outside the unchanged API snapshot. */
suspend fun <T> Observable<T>.awaitSingle(): T = awaitSingleUpstream()
'@ | Set-Content -LiteralPath $adapterPath -Encoding utf8

$samplePath = Join-Path $sourceRoot "$samplePackage/$sampleClass.kt"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $samplePath) | Out-Null
if ($ApiVersion -eq 'v15') {
@'
package aex00.external.v15

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/** A controlled extension compiled only against the fixed v0.19.4 extensions-lib API. */
class LegacySuspendOnlySource : Source {
    override val id: Long = 0xAE0015L
    override val name: String = "AEX-00 v1.5 suspend-only fixture"

    override suspend fun getMangaDetails(manga: SManga): SManga = manga.copy().also {
        it.title = "${manga.title} (v1.5)"
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()

    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
}
'@ | Set-Content -LiteralPath $samplePath -Encoding utf8

} else {
@'
package aex00.external.v16

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Controlled v1.6 fixture: factory, languages, grading, memo, flags, and failure path. */
class V16Source(private val sourceLanguage: String) : Source {
    override val id: Long = if (sourceLanguage == "en") 0xAE001601L else 0xAE001602L
    override val name: String = "AEX-00 v1.6 $sourceLanguage fixture"
    override val lang: String = sourceLanguage
    override val supportsLatest: Boolean = true

    var lastFetchDetails: Boolean? = null
    var lastFetchChapters: Boolean? = null

    override fun getFilterList(): FilterList = FilterList(Filter.Header("AEX-00"))

    override suspend fun getPopularManga(page: Int): MangasPage =
        MangasPage(listOf(manga("popular-$sourceLanguage-$page")), hasNextPage = page < 2)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        MangasPage(listOf(manga("latest-$sourceLanguage-$page")), hasNextPage = false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(listOf(manga("search-$query-$sourceLanguage-$page")), hasNextPage = false)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        lastFetchDetails = fetchDetails
        lastFetchChapters = fetchChapters
        val updatedManga = if (fetchDetails) manga.copy().also {
            it.memo = buildJsonObject { put("aex00.memo", "preserved-$sourceLanguage") }
        } else manga
        val updatedChapters = if (fetchChapters) listOf(chapter()) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.url == "/aex00/error") error("AEX-00 controlled source failure")
        return emptyList()
    }

    private fun manga(key: String): SManga = SManga.create().apply {
        url = "/aex00/$key"
        title = "AEX-00 $key"
        memo = buildJsonObject { put("aex00.contentWarning", 1) }
    }

    private fun chapter(): SChapter = SChapter.create().apply {
        url = "/aex00/chapter-$sourceLanguage"
        name = "AEX-00 chapter $sourceLanguage"
        memo = buildJsonObject { put("aex00.chapterMemo", "preserved") }
    }
}

/**
 * Public binary probe for the later ABI RED. The parameter is the frozen Source interface,
 * rather than V16Source, so a host cannot accidentally pass a fixture-only method as proof.
 */
object V16SourceAbiProbe {
    suspend fun updateSummary(
        source: Source,
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): String {
        val update = source.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
        val mangaMemo = update.manga.memo["aex00.memo"]?.toString()?.trim('"').orEmpty()
        val chapterMemo = update.chapters.singleOrNull()?.memo?.get("aex00.chapterMemo")
            ?.toString()?.trim('"').orEmpty()
        return "manga=$mangaMemo;chapter=$chapterMemo;chapters=${update.chapters.size}"
    }
}

class V16SourceFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(V16Source("en"), V16Source("zh"))
}
'@ | Set-Content -LiteralPath $samplePath -Encoding utf8
}

$testPath = Join-Path $outputPath "src/test/kotlin/$samplePackage/$testClass.kt"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $testPath) | Out-Null
if ($ApiVersion -eq 'v15') {
@'
package aex00.external.v15

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacySuspendOnlySourceTest {
    @Test
    fun `fixed v1_5 API accepts suspend-only source override`() = runTest {
        val source = LegacySuspendOnlySource()
        val manga = SManga.create().apply {
            url = "/aex00/v15"
            title = "Legacy"
        }

        assertEquals("AEX-00 v1.5 suspend-only fixture", source.name)
        assertEquals("Legacy (v1.5)", source.getMangaDetails(manga).title)
        assertTrue(source.getChapterList(manga).isEmpty())
    }
}
'@ | Set-Content -LiteralPath $testPath -Encoding utf8
} else {
@'
package aex00.external.v16

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class V16ContractTest {
    @Test
    fun `fixed v1_6 SourceFactory preserves production contract`() = runTest {
        val factory: eu.kanade.tachiyomi.source.SourceFactory = V16SourceFactory()
        val sources = factory.createSources()
        assertEquals(listOf("en", "zh"), sources.map { it.lang })
        assertEquals(listOf("AEX-00 popular-en-1"), sources.first().getPopularManga(1).mangas.map(SManga::title))
        assertTrue(sources.first().getFilterList().isNotEmpty())

        val manga = SManga.create().apply {
            url = "/aex00/v16"
            title = "Before"
        }
        val source: Source = sources.first()
        val implementation = sources.first() as V16Source
        val existingChapter = SChapter.create().apply {
            url = "/aex00/existing-en"
            name = "existing"
            memo = kotlinx.serialization.json.buildJsonObject {
                put("aex00.chapterMemo", "existing")
            }
        }
        listOf(
            false to false,
            false to true,
            true to false,
            true to true,
        ).forEach { (fetchDetails, fetchChapters) ->
            val updateSummary = V16SourceAbiProbe.updateSummary(
                source,
                manga,
                listOf(existingChapter),
                fetchDetails,
                fetchChapters,
            )
            assertEquals(fetchDetails, implementation.lastFetchDetails)
            assertEquals(fetchChapters, implementation.lastFetchChapters)
            val expectedMangaMemo = if (fetchDetails) "preserved-en" else ""
            val expectedChapterMemo = if (fetchChapters) "preserved" else "existing"
            assertEquals(
                "manga=$expectedMangaMemo;chapter=$expectedChapterMemo;chapters=1",
                updateSummary,
            )
        }

        val errorChapter = SChapter.create().apply {
            url = "/aex00/error"
            name = "error"
        }
        val failure = runCatching { source.getPageList(errorChapter) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
'@ | Set-Content -LiteralPath $testPath -Encoding utf8
}

$settingsPath = Join-Path $outputPath 'settings.gradle.kts'
@"
pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.jetbrains.kotlin.jvm") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "$projectName"
"@ | Set-Content -LiteralPath $settingsPath -Encoding utf8

$buildPath = Join-Path $outputPath 'build.gradle.kts'
$androidJar = Join-Path $AndroidSdk 'platforms/android-36/android.jar'
if (-not (Test-Path -LiteralPath $androidJar -PathType Leaf)) {
    throw "缺少固定 Android SDK 编译输入：$androidJar"
}
$composeAnnotationRelativePath = 'ea491da34ba40ae9bd340c393566753ddfd95a76/runtime-annotation-jvm-1.10.4.jar'
$composeAnnotationJar = Get-Item -LiteralPath (Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/androidx.compose.runtime/runtime-annotation-jvm/1.10.4/$composeAnnotationRelativePath") -ErrorAction SilentlyContinue
if ($null -eq $composeAnnotationJar) {
    throw '缺少已缓存的 Compose runtime annotation JVM 编译输入：androidx.compose.runtime:runtime-annotation-jvm:1.10.4'
}
$composeAnnotationJarSha256 = (Get-FileHash -LiteralPath $composeAnnotationJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($composeAnnotationJarSha256 -ne 'f89dda8bcd73876d0fc16568844b2bd79443ee7ca62810874d25c7dcaa394bd6') {
    throw "Compose runtime annotation JVM 编译输入 SHA-256 不匹配：实际 $composeAnnotationJarSha256"
}
@"
import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm") version "2.3.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("$($androidJar.Replace('\', '/'))"))
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    compileOnly(files("$($composeAnnotationJar.FullName.Replace('\', '/'))"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("aex00ExportSampleJar") {
    dependsOn(tasks.named("classes"))
    archiveFileName.set("$archiveName")
    destinationDirectory.set(layout.buildDirectory.dir("outputs"))
    from(layout.buildDirectory.dir("classes/kotlin/main")) {
        include("$samplePackage/**")
    }
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}
"@ | Set-Content -LiteralPath $buildPath -Encoding utf8

$sampleRelativePath = "src/main/kotlin/$samplePackage/$sampleClass.kt"
$testRelativePath = "src/test/kotlin/$samplePackage/$testClass.kt"
$testFilter = "$($samplePackage.Replace('/', '.')).$testClass"
$testCommand = "python scripts/gradle-coordinator.py foreground --key aex00-external-$ApiVersion-test -- .\gradlew.bat -p `"$outputPath`" test --tests `"$testFilter`" --no-daemon --offline"
$exportCommand = "python scripts/gradle-coordinator.py foreground --key aex00-external-$ApiVersion-export -- .\gradlew.bat -p `"$outputPath`" aex00ExportSampleJar --no-daemon --offline"
$signedArchiveName = "$archiveName.signed.jar"
$signCommand = "jarsigner '-J-Duser.language=en' '-J-Duser.country=US' -keystore app-desktop/tmp/aex00-v15-signing/repository.p12 -storetype PKCS12 -storepass:env AEX00_FIXTURE_STOREPASS -signedjar `"$outputPath/build/outputs/$signedArchiveName`" `"$outputPath/build/outputs/$archiveName`" aex00-v15"
$verifySignedCommand = "jarsigner '-J-Duser.language=en' '-J-Duser.country=US' -verify -certs `"$outputPath/build/outputs/$signedArchiveName`""
$prerequisites = @(
    'PowerShell 7: pwsh',
    'JDK 21: java, jar, jarsigner',
    'Android SDK D:\Android\Sdk with platforms;android-36/android.jar and build-tools;36.0.0/{d8,aapt2,apksigner}',
    'local Git object: generator verifies each source SHA-256 and source git blob SHA-1 with git cat-file/rev-parse/hash-object',
    'offline Gradle cache: Kotlin Gradle plugin 2.3.10, declared Maven dependencies, and Compose annotation JAR at the recorded path/hash',
    'fixture signing password: set AEX00_FIXTURE_STOREPASS in the current process; the keystore and private key are not distributed'
)
$manifest = [ordered]@{
    schema = 'aex00-external-source-fixture/v2'
    status = 'generated'
    api = [ordered]@{
        repository = $upstreamRepository
        tag = $upstreamTag
        tagObject = if ($ApiVersion -eq 'v15') { '21e91e2007758a0d7035a46bff6cc6ff82ed1be0' } else { $null }
        commit = $upstreamRef
        license = 'Apache-2.0'
        licenseUrl = "$upstreamRepository/blob/$upstreamRef/LICENSE"
        sourceFiles = $downloaded
    }
    fixture = [ordered]@{
        source = $sampleRelativePath
        sourceSha256 = (Get-FileHash -LiteralPath $samplePath -Algorithm SHA256).Hash.ToLowerInvariant()
        sourceSizeBytes = (Get-Item -LiteralPath $samplePath).Length
        test = $testFilter
        testCommand = $testCommand
        exportCommand = $exportCommand
        apiKind = if ($ApiVersion -eq 'v15') { 'external-source-only; controlled signed artifact is supplied separately' } else { 'external-source-factory; controlled sample classes only' }
        signature = 'sign after deterministic export with authorized fixture key; source generation itself has no signature'
    }
    build = [ordered]@{
        kotlin = '2.3.10'
        jvmToolchain = '21'
        jvmDefault = 'compiler default (no host source-api or ABI jar)'
        androidJar = $androidJar
        androidJarSha256 = (Get-FileHash -LiteralPath $androidJar -Algorithm SHA256).Hash.ToLowerInvariant()
        composeAnnotationJar = $composeAnnotationJar.FullName
        composeAnnotationJarSha256 = $composeAnnotationJarSha256
        dependencies = @(
            'io.reactivex:rxjava:1.3.8',
            'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2',
            'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2',
            'org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0',
            'org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0',
            'androidx.compose.runtime:runtime-annotation-jvm:1.10.4',
            'org.junit.jupiter:junit-jupiter:6.0.3',
            'org.junit.platform:junit-platform-launcher'
        )
        prerequisites = $prerequisites
        localGitObjectCheck = 'git cat-file -e <ref>^{commit}; git rev-parse <ref>:<source-path>; git hash-object --no-filters <cached-source>'
        sequence = @(
            "1. generate: pwsh -File scripts/aex00-generate-external-v15-fixture.ps1 -ApiVersion $ApiVersion -OutputDirectory `"$OutputDirectory`"",
            "2. test: $testCommand",
            "3. export: $exportCommand",
            "4. sign: $signCommand; verify: $verifySignedCommand",
            "5. package: pwsh -File scripts/aex00-package-controlled-v16-apk.ps1 -ApiVersion $ApiVersion -FixtureProject `"$OutputDirectory`"; verify raw aapt2 metadata and apksigner v2/v3"
        )
        offline = $true
        signingBoundary = 'controlled JAR/APK signing uses an authorized non-distributed fixture key; self-signed/no-timestamp warnings are expected and this is not an official release key'
        signing = [ordered]@{
            command = $signCommand
            verificationCommand = $verifySignedCommand
            keyMaterial = 'app-desktop/tmp/aex00-v15-signing/repository.p12 is local ignored material; set AEX00_FIXTURE_STOREPASS, never record its value'
        }
    }
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputPath 'fixture-manifest.json') -Encoding utf8

Write-Host "已生成固定外部 $ApiVersion fixture：$outputPath"
Write-Host "上游 commit：$upstreamRef；生成 JAR 仅含受控样本类，不冒称为第三方发布物。"
Write-Host "测试命令（必须经 Gradle 协调器串行执行）：$($manifest.fixture.testCommand)"
