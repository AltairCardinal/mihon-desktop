package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.CbzCreator
import mihon.desktop.download.DesktopDownloadProvider
import mihon.domain.reader.content.DownloadArtifactCandidate
import mihon.domain.reader.content.DownloadArtifactKind
import mihon.domain.reader.content.DownloadArtifactProbe
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.session.ReaderPageLoadState
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopReaderContentResolverWiringTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `desktop production composition root delegates artifact selection to shared locator contract`() = runTest {
        val downloaded = tempDir.resolve("located").also(File::mkdirs)
        ImageIO.write(BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB), "png", downloaded.resolve("001.png"))
        var receivedIdentity: DownloadChapterIdentity? = null
        val receivedCandidates = mutableListOf<DownloadArtifactCandidate>()
        val sourceManager = RecordingSourceManager()
        val context = DesktopReaderChapterContext(
            chapterId = 7L,
            sourceId = 42L,
            chapterUrl = "/chapter/1",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 1",
            chapterNumber = 1.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
        )
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("unused")),
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded"),
            downloadArtifactProbeFactory = {
                DownloadArtifactProbe { identity, candidate ->
                    receivedIdentity = identity
                    receivedCandidates += candidate
                    downloaded.absolutePath.takeIf {
                        candidate.name == "Chapter 1" && candidate.kind == DownloadArtifactKind.DIRECTORY
                    }
                }
            },
        )

        val runtime = factory.createRuntime(context, this)
        try {
            advanceUntilIdle()

            assertEquals(1, runtime.session.state.value.snapshot.activeChapter.pages.size)
            assertEquals("/chapter/1", receivedIdentity?.chapterUrl)
            assertTrue(receivedCandidates.any { it.name.endsWith(".cbz") })
            assertEquals(0, sourceManager.catalogueSourceCalls)
        } finally {
            runtime.close()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `desktop default adapter preserves old scanlator directory and download lexical page order`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("downloads"))
        val downloaded = provider.chapterDownloadDir(42L, "Manga", "Chapter 1").also(File::mkdirs)
        listOf("2.png", "10.png", "b.png", "A.png").forEach { name ->
            ImageIO.write(BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB), "png", downloaded.resolve(name))
        }
        val sourceManager = RecordingSourceManager()
        val context = DesktopReaderChapterContext(
            chapterId = 8L,
            sourceId = 42L,
            chapterUrl = "/chapter/1",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 1",
            chapterNumber = 1.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
            scanlator = "Group",
        )
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = provider,
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-default"),
        )

        val runtime = factory.createRuntime(context, this)
        try {
            advanceUntilIdle()

            assertEquals(
                listOf("10.png", "2.png", "A.png", "b.png"),
                runtime.session.state.value.snapshot.activeChapter.pages.map { it.url },
            )
            assertEquals(0, sourceManager.catalogueSourceCalls)
        } finally {
            runtime.close()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `production progress forwards the complete download identity used by migrated artifact deletion`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("downloads-progress-identity"))
        val context = DesktopReaderChapterContext(
            chapterId = 81L,
            sourceId = 42L,
            chapterUrl = "/chapter/hash-me",
            mangaTitle = "Manga 中文",
            chapterTitle = "Chapter 1",
            chapterNumber = 1.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
            scanlator = "Group",
            sourceDisplayName = "Source 中文",
        )
        val identity = DownloadChapterIdentity(
            sourceDisplayName = context.sourceDisplayName,
            mangaTitle = context.mangaTitle,
            chapterName = context.chapterTitle,
            scanlator = context.scanlator,
            chapterUrl = context.chapterUrl,
            disallowNonAsciiFilenames = context.disallowNonAsciiFilenames,
        )
        val downloaded = provider.canonicalChapterDownloadDir(identity).also(File::mkdirs)
        ImageIO.write(BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB), "png", downloaded.resolve("001.png"))
        val progressTracker = mockk<ReaderProgressTracker>(relaxed = true)
        val readingSession = RecordReadingProgress(mockk<ReadingProgressRepository>())
            .openSession(context.chapterId, ReadingSyncSnapshot())
        coEvery { progressTracker.openSession(context.chapterId, context.resumeSnapshot) } returns readingSession
        every { progressTracker.isIncognito(context.sourceId) } returns false
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = provider,
            sourceManager = RecordingSourceManager(),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = progressTracker,
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-progress-identity"),
        )

        val runtime = factory.createRuntime(context, this)
        try {
            advanceUntilIdle()
            val pageId = runtime.session.state.value.snapshot.activeChapter.pages.single().id
            runtime.session.settleViewport(setOf(pageId), pageId)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                progressTracker.openSession(context.chapterId, context.resumeSnapshot)
                progressTracker.track(
                    any(),
                    context.chapterId,
                    any(),
                    any(),
                    context.sourceId,
                    null,
                    null,
                    context.mangaId,
                    context.chapterNumber,
                    any(),
                    any(),
                    any(),
                    identity,
                    readingSession = readingSession,
                    incognitoAtAcceptance = false,
                )
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `production cbz creator output opens offline after source directory is removed`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("downloads-cbz"))
        val context = DesktopReaderChapterContext(
            chapterId = 9L,
            sourceId = 42L,
            chapterUrl = "/chapter/cbz",
            mangaTitle = "Manga 中文",
            chapterTitle = "Chapter CBZ",
            chapterNumber = 1.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
            scanlator = "Group",
            sourceDisplayName = "Source 中文",
        )
        val identity = DownloadChapterIdentity(
            sourceDisplayName = context.sourceDisplayName,
            mangaTitle = context.mangaTitle,
            chapterName = context.chapterTitle,
            scanlator = context.scanlator,
            chapterUrl = context.chapterUrl,
            disallowNonAsciiFilenames = context.disallowNonAsciiFilenames,
        )
        val sourceDirectory = provider.canonicalChapterDownloadDir(identity).also(File::mkdirs)
        ImageIO.write(BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB), "png", sourceDirectory.resolve("001.png"))
        val cbz = CbzCreator.defaultOutputFile(sourceDirectory)
        assertTrue(CbzCreator.create(sourceDirectory, cbz))
        assertTrue(sourceDirectory.deleteRecursively())
        val sourceManager = RecordingSourceManager()
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = provider,
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-cbz"),
        )

        val runtime = factory.createRuntime(context, this)
        try {
            advanceUntilIdle()
            val pageId = runtime.session.state.value.snapshot.activeChapter.pages.single().id
            runtime.session.settleViewport(setOf(pageId), pageId)
            advanceUntilIdle()

            val page = runtime.session.state.value.snapshot.activeChapter.pages.single()
            assertEquals(ReaderPageLoadState.Ready, page.loadState)
            assertEquals(0, sourceManager.catalogueSourceCalls)
        } finally {
            runtime.close()
        }

        assertTrue(cbz.delete(), "Closing the reader must release the downloaded CBZ handle")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `gated download root index scan cannot block production finite artifact lookup`() = runTest {
        val indexEntered = CountDownLatch(1)
        val releaseIndex = CountDownLatch(1)
        val blockingRoot = object : File(tempDir, "gated-download-index") {
            override fun listFiles(): Array<File>? {
                indexEntered.countDown()
                releaseIndex.await()
                return super.listFiles()
            }
        }
        val provider = DesktopDownloadProvider(blockingRoot)
        val context = DesktopReaderChapterContext(
            chapterId = 10L,
            sourceId = 42L,
            chapterUrl = "/chapter/index-gate",
            mangaTitle = "Manga",
            chapterTitle = "Chapter Gate",
            chapterNumber = 1.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
            sourceDisplayName = "42",
        )
        val identity = DownloadChapterIdentity(
            sourceDisplayName = context.sourceDisplayName,
            mangaTitle = context.mangaTitle,
            chapterName = context.chapterTitle,
            scanlator = null,
            chapterUrl = context.chapterUrl,
            disallowNonAsciiFilenames = false,
        )
        val downloaded = provider.canonicalChapterDownloadDir(identity).also(File::mkdirs)
        ImageIO.write(BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB), "png", downloaded.resolve("001.png"))
        val indexScan = backgroundScope.launch(Dispatchers.IO) { blockingRoot.listFiles() }
        withContext(Dispatchers.IO) {
            assertTrue(indexEntered.await(2, TimeUnit.SECONDS), "The simulated download index must hold root enumeration")
        }
        val sourceManager = RecordingSourceManager()
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = provider,
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-index-gate"),
        )
        val runtime = factory.createRuntime(context, this)
        try {
            withTimeout(5_000) {
                while (runtime.session.state.value.snapshot.activeChapter.pages.isEmpty()) {
                    advanceUntilIdle()
                }
            }

            assertEquals(1, runtime.session.state.value.snapshot.activeChapter.pages.size)
            assertTrue(!indexScan.isCompleted)
            assertEquals(0, sourceManager.catalogueSourceCalls)
        } finally {
            runtime.close()
            releaseIndex.countDown()
            indexScan.cancelAndJoin()
        }
    }

    private class RecordingSourceManager : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = flowOf(emptyList<CatalogueSource>())
        var catalogueSourceCalls = 0
            private set

        override fun get(sourceKey: Long): Source? = null

        override fun getOrStub(sourceKey: Long): Source = error("Source resolution must stay offline")

        override fun getOnlineSources(): List<HttpSource> = emptyList()

        override fun getCatalogueSources(): List<CatalogueSource> {
            catalogueSourceCalls++
            return emptyList()
        }

        override fun getStubSources(): List<StubSource> = emptyList()
    }
}
