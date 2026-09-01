package mihon.desktop.download

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.mockk
import java.awt.image.BufferedImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.platform.DesktopDownloadDirectorySelection
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.ReaderPreferences
import mihon.domain.error.AppError
import mihon.domain.reader.content.DownloadArtifactKind
import mihon.domain.reader.content.DownloadArtifactNamingPolicy
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.session.ReaderPageLoadState
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import javax.imageio.ImageIO

class DesktopDownloadDirectoryCompatibilityIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `saving and restoring roots never mutates or migrates existing artifacts`() {
        val defaultRoot = tempDir.resolve("default")
        val oldRoot = tempDir.resolve("custom-old").also(File::mkdirs)
        val newRoot = tempDir.resolve("custom-new")
        val oldProvider = DesktopDownloadProvider(oldRoot)
        createRepresentativeTree(oldProvider)
        val before = byteSnapshot(oldRoot)

        val directoryPreference = DesktopDownloadPreferences(InMemoryPreferenceStore())
            .downloadDirectory(defaultRoot)
        directoryPreference.save(oldRoot.path)
        val startupState = directoryPreference.state()
        val controller = DesktopDownloadDirectoryController(directoryPreference, startupState)

        val selection = controller.selectDirectory(newRoot)
        val pending = controller.currentState()

        assertTrue(selection is DesktopDownloadDirectorySelection.ValidCustom)
        assertEquals(oldRoot.normalized(), pending.activeDirectory)
        assertEquals(newRoot.normalized(), pending.pendingDirectory)
        assertTrue(pending.restartRequired)
        assertEquals(before, byteSnapshot(oldRoot))
        assertNull(DesktopDownloadProvider(newRoot).downloadArtifactLookup(SOURCE_ID).locate(identity()))

        controller.restoreDefault()
        val restored = controller.currentState()
        assertEquals(oldRoot.normalized(), restored.activeDirectory)
        assertEquals(defaultRoot.normalized(), restored.pendingDirectory)
        assertTrue(restored.restartRequired)
        assertEquals(before, byteSnapshot(oldRoot))
        assertFalse(defaultRoot.exists(), "Restoring the preference must not create or delete either root")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `manual whole tree copy preserves every shared and historical artifact offline reading and deletion`() = runTest {
        DownloadArtifactNamingPolicy.chapterCandidates(identity()).distinct().forEachIndexed { index, candidate ->
            val oldRoot = tempDir.resolve("shared-old-$index")
            val newRoot = tempDir.resolve("shared-new-$index")
            val oldProvider = DesktopDownloadProvider(oldRoot)
            val artifact = oldProvider.canonicalMangaDownloadDir(identity()).resolve(candidate.name)
            createArtifact(artifact, candidate.kind)
            assertTrue(oldRoot.copyRecursively(newRoot))

            val provider = DesktopDownloadProvider(newRoot)
            val match = provider.downloadArtifactLookup(SOURCE_ID).locate(identity())

            assertEquals(candidate, match?.candidate)
            assertTrue(provider.isChapterDownloaded(SOURCE_ID, identity()))
            assertMigratedArtifactReadsOffline(provider, index.toLong())
            provider.deleteChapterDownload(SOURCE_ID, identity())
            assertFalse(newRoot.resolve(artifact.relativeTo(oldRoot).path).exists())
        }

        listOf(DownloadArtifactKind.DIRECTORY, DownloadArtifactKind.CBZ).forEachIndexed { index, kind ->
            val oldRoot = tempDir.resolve("desktop-old-$index")
            val newRoot = tempDir.resolve("desktop-new-$index")
            val oldProvider = DesktopDownloadProvider(oldRoot)
            val oldDirectory = oldProvider.chapterDownloadDir(SOURCE_ID, identity().mangaTitle, identity().chapterName)
            val artifact = if (kind == DownloadArtifactKind.DIRECTORY) {
                oldDirectory
            } else {
                File(oldDirectory.parentFile, "${oldDirectory.name}.cbz")
            }
            createArtifact(artifact, kind)
            assertTrue(oldRoot.copyRecursively(newRoot))

            val provider = DesktopDownloadProvider(newRoot)
            assertNotNull(provider.downloadArtifactLookup(SOURCE_ID).locate(identity()))
            assertTrue(provider.isChapterDownloaded(SOURCE_ID, identity()))
            assertMigratedArtifactReadsOffline(provider, 100L + index)
            provider.deleteChapterDownload(SOURCE_ID, identity())
            assertFalse(newRoot.resolve(artifact.relativeTo(oldRoot).path).exists())
        }
    }

    @Test
    fun `old partial is ignored and available source redownloads only into the restarted root`() = runBlocking {
        val oldRoot = tempDir.resolve("old-partial-root")
        val oldProvider = DesktopDownloadProvider(oldRoot)
        val item = downloadItem(chapterId = 71L)
        oldProvider.chapterTmpDir(item.sourceId, item.mangaTitle, item.chapterName).let { partial ->
            partial.mkdirs()
            partial.resolve("001.gif").writeText(PAGE_BODY)
        }
        val before = byteSnapshot(oldRoot)
        val newRoot = tempDir.resolve("new-restarted-root")
        val provider = DesktopDownloadProvider(newRoot)
        val server = MockWebServer().apply {
            enqueue(MockResponse(body = PAGE_BODY))
            start()
        }
        val manager = manager(provider)
        try {
            manager.enqueue(item.copy(pageUrls = listOf(server.url("/page.gif").toString())))
            manager.start()
            awaitCompleted(manager)

            assertTrue(provider.isChapterDownloaded(item.sourceId, item.mangaTitle, item.chapterName))
            assertEquals(before, byteSnapshot(oldRoot))
            assertEquals(1, server.requestCount)
        } finally {
            manager.stopAndJoin()
            server.close()
        }
    }

    @Test
    fun `disconnected configured root reports storage failure and retry after reconnect uses the same root`() = runBlocking {
        val defaultRoot = tempDir.resolve("unused-default")
        val configuredRoot = tempDir.resolve("configured-removable").apply { writeText("detached") }
        val provider = DesktopDownloadProvider(configuredRoot)
        val server = MockWebServer().apply {
            repeat(5) { enqueue(MockResponse(body = PAGE_BODY)) }
            start()
        }
        val item = downloadItem(chapterId = 72L).copy(pageUrls = listOf(server.url("/page.gif").toString()))
        val manager = manager(provider)
        try {
            manager.enqueue(item)
            manager.start()
            awaitError(manager)

            assertTrue(manager.queue.value.single().failure is AppError.Storage)
            assertEquals("detached", configuredRoot.readText())
            assertFalse(defaultRoot.exists(), "A disconnected configured root must not fall back to the default root")

            assertTrue(configuredRoot.delete())
            assertTrue(configuredRoot.mkdirs())
            manager.retryItem(item.chapterId)
            awaitCompleted(manager)

            assertTrue(provider.isChapterDownloaded(item.sourceId, item.mangaTitle, item.chapterName))
            assertFalse(defaultRoot.exists())
            assertEquals(5, server.requestCount)
        } finally {
            manager.stopAndJoin()
            server.close()
        }
    }

    private fun createRepresentativeTree(provider: DesktopDownloadProvider) {
        createArtifact(provider.canonicalChapterDownloadDir(identity()), DownloadArtifactKind.DIRECTORY)
        createArtifact(CbzCreator.defaultOutputFile(provider.canonicalChapterDownloadDir(identity())), DownloadArtifactKind.CBZ)
        provider.chapterDownloadDir(SOURCE_ID, identity().mangaTitle, identity().chapterName).let { legacy ->
            createArtifact(legacy, DownloadArtifactKind.DIRECTORY)
            createArtifact(File(legacy.parentFile, "${legacy.name}.cbz"), DownloadArtifactKind.CBZ)
        }
        provider.canonicalChapterTmpDir(identity()).let { partial ->
            partial.mkdirs()
            partial.resolve("001.gif").writeText("partial")
        }
    }

    private fun createArtifact(artifact: File, kind: DownloadArtifactKind) {
        when (kind) {
            DownloadArtifactKind.DIRECTORY -> {
                artifact.mkdirs()
                ImageIO.write(
                    BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB),
                    "png",
                    artifact.resolve("001.png"),
                )
            }
            DownloadArtifactKind.CBZ -> {
                val source = tempDir.resolve("cbz-source-${artifact.parentFile.name}-${artifact.name}")
                    .also(File::mkdirs)
                ImageIO.write(
                    BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB),
                    "png",
                    source.resolve("001.png"),
                )
                assertTrue(CbzCreator.create(source, artifact))
                assertTrue(source.deleteRecursively())
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.assertMigratedArtifactReadsOffline(
        provider: DesktopDownloadProvider,
        chapterId: Long,
    ) {
        val sourceManager = OfflineSourceManager()
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = provider,
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-migrated-$chapterId"),
        )
        val runtime = factory.createRuntime(readerContext(chapterId), this)
        try {
            advanceUntilIdle()
            val page = runtime.session.state.value.snapshot.activeChapter.pages.single()
            runtime.session.settleViewport(setOf(page.id), page.id)
            advanceUntilIdle()

            assertEquals(
                ReaderPageLoadState.Ready,
                runtime.session.state.value.snapshot.activeChapter.pages.single().loadState,
            )
            assertEquals(0, sourceManager.sourceResolutionCalls)
            assertEquals(0, sourceManager.catalogueSourceCalls)
        } finally {
            runtime.close()
        }
    }

    private fun readerContext(chapterId: Long) = DesktopReaderChapterContext(
        chapterId = chapterId,
        sourceId = SOURCE_ID,
        chapterUrl = identity().chapterUrl,
        mangaTitle = identity().mangaTitle,
        chapterTitle = identity().chapterName,
        chapterNumber = 1.0,
        chapterIndex = 0,
        initialPage = 0,
        wasRead = false,
        scanlator = identity().scanlator,
        sourceDisplayName = identity().sourceDisplayName,
        disallowNonAsciiFilenames = identity().disallowNonAsciiFilenames,
    )

    private class OfflineSourceManager : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = flowOf(emptyList<CatalogueSource>())
        var sourceResolutionCalls = 0
            private set
        var catalogueSourceCalls = 0
            private set

        override fun get(sourceKey: Long): Source? = null

        override fun getOrStub(sourceKey: Long): Source {
            sourceResolutionCalls++
            error("Migrated downloads must open without source resolution")
        }

        override fun getOnlineSources(): List<HttpSource> = emptyList()

        override fun getCatalogueSources(): List<CatalogueSource> {
            catalogueSourceCalls++
            return emptyList()
        }

        override fun getStubSources(): List<StubSource> = emptyList()
    }

    private fun manager(provider: DesktopDownloadProvider) = DesktopDownloadManager(
        provider = provider,
        httpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        retryDelay = {},
    )

    private suspend fun awaitError(manager: DesktopDownloadManager) = withTimeout(5_000) {
        while (manager.queue.value.singleOrNull()?.status != DownloadStatus.ERROR) delay(10)
    }

    private suspend fun awaitCompleted(manager: DesktopDownloadManager) = withTimeout(5_000) {
        while (manager.queue.value.isNotEmpty()) delay(10)
    }

    private fun downloadItem(chapterId: Long) = DownloadItem(
        sourceId = SOURCE_ID,
        mangaTitle = identity().mangaTitle,
        chapterName = identity().chapterName,
        chapterId = chapterId,
    )

    private fun identity() = DownloadChapterIdentity(
        sourceDisplayName = "Source 中文",
        mangaTitle = "Manga 中文",
        chapterName = "Chapter 1",
        scanlator = "Group & 合作组",
        chapterUrl = "/chapter/hash-me",
        disallowNonAsciiFilenames = false,
    )

    private fun byteSnapshot(root: File): Map<String, List<Byte>> =
        if (!root.isDirectory) {
            emptyMap()
        } else {
            root.walkTopDown()
                .filter(File::isFile)
                .associate { file -> file.relativeTo(root).invariantSeparatorsPath to file.readBytes().toList() }
        }

    private fun File.normalized(): File = toPath().toAbsolutePath().normalize().toFile()

    private companion object {
        const val SOURCE_ID = 42L
        const val PAGE_BODY = "GIF89aDATA"
    }
}
