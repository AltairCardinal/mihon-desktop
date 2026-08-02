package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageLoadState
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.IOException
import java.nio.file.Path
import java.util.Collections

class AndroidReaderCoreProductionContractTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `online ReaderViewModel executes shared session scheduler encoded cache and progress chain`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val manga = Manga.create().copy(
                id = 1,
                source = 7,
                title = "Canonical reader",
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            )
            val chapters = (1L..3L).map(::chapter)
            val source = mockk<HttpSource>(relaxed = true)
            val imageFetchOrder = Collections.synchronizedList(mutableListOf<Int>())
            coEvery { source.getPageList(any()) } returns List(PAGE_COUNT) { index ->
                Page(index, "/page/$index", "https://reader.test/image/$index")
            }
            coEvery { source.getImage(any()) } coAnswers {
                imageFetchOrder += firstArg<Page>().index
                mockk<Response>(relaxed = true)
            }

            val cache = mockk<ChapterCache>()
            every { cache.getPageListFromCache(any()) } throws IOException("cache miss")
            every { cache.putPageListToCache(any(), any()) } returns Unit
            every { cache.getImageFile(any()) } answers { cacheFile(firstArg()) }
            every { cache.isImageInCache(any()) } answers { cacheFile(firstArg()).exists() }
            every { cache.putImageToCache(any(), any()) } answers {
                cacheFile(firstArg()).writeBytes(byteArrayOf(1, 2, 3))
                true
            }
            every { cache.removeImageFromCache(any()) } answers { cacheFile(firstArg()).delete() }

            val downloadManager = mockk<DownloadManager>(relaxed = true)
            every {
                downloadManager.isChapterDownloaded(any(), any(), any(), any(), any(), any())
            } returns false
            val downloadProvider = mockk<DownloadProvider>(relaxed = true)
            val recordedProgress = Channel<ReadingProgressEvent>(Channel.UNLIMITED)
            val previousInjekt = Injekt
            Injekt = InjektScope(DefaultRegistrar())
            try {
                Injekt.addSingleton<Application>(mockk(relaxed = true))
                Injekt.addSingleton<ChapterCache>(cache)
                val viewModel = productionReaderViewModel(
                    manga = manga,
                    chapters = chapters,
                    source = source,
                    downloadManager = downloadManager,
                    downloadProvider = downloadProvider,
                    recordReadingProgress = RecordReadingProgress(
                        object : ReadingProgressRepository {
                            override suspend fun record(event: ReadingProgressEvent) {
                                recordedProgress.send(event)
                            }
                        },
                    ),
                )

                assertTrue(viewModel.init(manga.id, initialChapterId = 2).isSuccess)
                val current = requireNotNull(viewModel.state.value.currentChapter)
                try {
                    assertInstanceOf(HttpPageLoader::class.java, current.pageLoader)
                    assertEquals(ReaderChapterId(2), viewModel.state.value.chapterWindow?.currentChapterId)
                    assertEquals(PAGE_COUNT, current.sharedSessionStateFlow.value.activeChapter.pages.size)

                    viewModel.onPageSelected(requireNotNull(current.pages).first())

                    val progress = withContext(Dispatchers.Default.limitedParallelism(1)) {
                        withTimeout(5_000) { recordedProgress.receive() }
                    }
                    val settled = withContext(Dispatchers.Default.limitedParallelism(1)) {
                        withTimeout(5_000) {
                            current.sharedSessionStateFlow.first { snapshot ->
                                snapshot.activeChapter.pages.take(ORIGINAL_NEARBY_WINDOW).all {
                                    it.loadState == ReaderPageLoadState.Ready
                                }
                            }
                        }
                    }

                    assertEquals((0 until ORIGINAL_NEARBY_WINDOW).toList(), imageFetchOrder)
                    assertEquals(
                        List(ORIGINAL_NEARBY_WINDOW) { ReaderPageLoadState.Ready } + ReaderPageLoadState.Queued,
                        settled.activeChapter.pages.map { it.loadState },
                    )
                    assertTrue(
                        settled.activeChapter.pages.take(ORIGINAL_NEARBY_WINDOW).all { it.encodedPageRef != null },
                    )
                    assertEquals(2L, progress.chapterId)
                    assertEquals(0, progress.lastPageRead)
                    assertFalse(progress.isRead)
                    assertEquals(ReaderSchedulerPolicy.originalMihon().nearbyForward + 1, imageFetchOrder.size)
                    verify(exactly = 1) { cache.getPageListFromCache(any()) }
                } finally {
                    current.pageLoader?.recycle()
                }
            } finally {
                Injekt = previousInjekt
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `legacy Android chapter state is a read only projection`() {
        assertTrue(ReaderChapter::class.java.methods.any { it.name == "getState" })
        assertFalse(ReaderChapter::class.java.methods.any { it.name == "setState" })
    }

    @Test
    fun `ReaderViewModel initialization preserves cooperative cancellation`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val manga = Manga.create().copy(id = 1, source = 7, title = "Cancelled reader")
            val cancellation = CancellationException("reader closed")
            val getManga = mockk<GetManga>()
            coEvery { getManga.await(manga.id) } throws cancellation
            val source = mockk<HttpSource>(relaxed = true)
            val failure = runCatching {
                productionReaderViewModel(
                    manga = manga,
                    chapters = listOf(chapter(1)),
                    source = source,
                    downloadManager = mockk(relaxed = true),
                    downloadProvider = mockk(relaxed = true),
                    recordReadingProgress = mockk(relaxed = true),
                    getMangaOverride = getManga,
                ).init(manga.id, initialChapterId = 1)
            }.exceptionOrNull()

            assertInstanceOf(CancellationException::class.java, failure)
            assertEquals(cancellation.message, failure?.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun productionReaderViewModel(
        manga: Manga,
        chapters: List<Chapter>,
        source: HttpSource,
        downloadManager: DownloadManager,
        downloadProvider: DownloadProvider,
        recordReadingProgress: RecordReadingProgress,
        getMangaOverride: GetManga? = null,
    ): ReaderViewModel {
        val sourceManager = mockk<SourceManager> {
            every { isInitialized } returns MutableStateFlow(true)
            every { getOrStub(manga.source) } returns source
        }
        val getManga = getMangaOverride ?: mockk<GetManga>().also { getter ->
            coEvery { getter.await(manga.id) } returns manga
        }
        val getChapters = mockk<GetChaptersByMangaId>()
        coEvery { getChapters.await(manga.id, applyScanlatorFilter = true) } returns chapters
        val readerPreferences = mockk<ReaderPreferences>(relaxed = true)
        every { readerPreferences.skipRead().get() } returns false
        every { readerPreferences.skipFiltered().get() } returns false
        every { readerPreferences.skipDupe().get() } returns false
        val basePreferences = mockk<BasePreferences>(relaxed = true)
        every { basePreferences.downloadedOnly().get() } returns false
        val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
        every { downloadPreferences.autoDownloadWhileReading().get() } returns 0
        every { downloadPreferences.removeAfterReadSlots().get() } returns -1
        val trackPreferences = mockk<TrackPreferences>(relaxed = true)
        every { trackPreferences.autoUpdateTrack().get() } returns false
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
        every { libraryPreferences.markDuplicateReadChapterAsRead().get() } returns emptySet()
        val getIncognitoState = mockk<GetIncognitoState>()
        every { getIncognitoState.await(any()) } returns false
        return ReaderViewModel(
            savedState = SavedStateHandle(),
            sourceManager = sourceManager,
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            imageSaver = mockk(relaxed = true),
            readerPreferences = readerPreferences,
            basePreferences = basePreferences,
            downloadPreferences = downloadPreferences,
            trackPreferences = trackPreferences,
            trackChapter = mockk(relaxed = true),
            getManga = getManga,
            getChaptersByMangaId = getChapters,
            getNextChapters = mockk(relaxed = true),
            upsertHistory = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            recordReadingProgress = recordReadingProgress,
            setMangaViewerFlags = mockk(relaxed = true),
            getIncognitoState = getIncognitoState,
            libraryPreferences = libraryPreferences,
        )
    }

    private fun cacheFile(imageUrl: String) = tempDir.resolve(imageUrl.substringAfterLast('/')).toFile()

    private fun chapter(id: Long) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        name = "Chapter $id",
        chapterNumber = id.toDouble(),
    )

    private companion object {
        const val PAGE_COUNT = 6
        const val ORIGINAL_NEARBY_WINDOW = 5
    }
}
