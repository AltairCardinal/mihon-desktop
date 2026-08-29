package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.mockk
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.domain.reader.content.DownloadArtifactCandidate
import mihon.domain.reader.content.DownloadArtifactKind
import mihon.domain.reader.content.DownloadArtifactProbe
import mihon.domain.reader.content.DownloadChapterIdentity
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

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
