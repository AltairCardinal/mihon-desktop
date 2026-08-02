package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.SavedStateHandle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

class ReaderProgressSettlementRaceTest {

    @Test
    fun `newer current settlement rejects older adjacent activation and progress`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val adjacentLoadStarted = CompletableDeferred<Unit>()
            val releaseAdjacentLoad = CompletableDeferred<Unit>()
            val recorded = Channel<ReadingProgressEvent>(Channel.UNLIMITED)
            val manga = Manga.create().copy(
                id = 1,
                source = 7,
                title = "Reader settlement race",
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            )
            val chapters = (1L..3L).map(::chapter)
            val source = mockk<Source>()
            val sourceManager = mockk<SourceManager> {
                every { isInitialized } returns MutableStateFlow(true)
                every { getOrStub(manga.source) } returns source
            }
            val getManga = mockk<GetManga>()
            coEvery { getManga.await(manga.id) } returns manga
            val getChapters = mockk<GetChaptersByMangaId>()
            coEvery { getChapters.await(manga.id, applyScanlatorFilter = true) } returns chapters
            val chapterLoader = mockk<ChapterLoader>()
            coEvery { chapterLoader.loadChapter(any()) } coAnswers {
                materializeChapter(firstArg())
            }
            coEvery { chapterLoader.loadChapter(any(), any()) } coAnswers {
                val readerChapter = firstArg<ReaderChapter>()
                if (readerChapter.chapter.id == 3L) {
                    adjacentLoadStarted.complete(Unit)
                    releaseAdjacentLoad.await()
                }
                materializeChapter(readerChapter)
            }
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
            val recorder = RecordReadingProgress(
                object : ReadingProgressRepository {
                    override suspend fun record(event: ReadingProgressEvent) {
                        recorded.send(event)
                    }
                },
            )
            val viewModel = ReaderViewModel(
                savedState = SavedStateHandle(),
                sourceManager = sourceManager,
                downloadManager = mockk(relaxed = true),
                downloadProvider = mockk(relaxed = true),
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
                recordReadingProgress = recorder,
                setMangaViewerFlags = mockk(relaxed = true),
                getIncognitoState = getIncognitoState,
                libraryPreferences = libraryPreferences,
                chapterLoaderFactory = { _: Manga, _: Source -> chapterLoader },
            )

            assertTrue(viewModel.init(manga.id, initialChapterId = 2).isSuccess)
            val current = requireNotNull(viewModel.state.value.currentChapter)
            val next = requireNotNull(viewModel.state.value.viewerChapters?.nextChapter)
            materializeChapter(next)

            viewModel.onPageSelected(requireNotNull(next.pages)[1])
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) { adjacentLoadStarted.await() }
            }
            viewModel.onPageSelected(requireNotNull(current.pages)[0])

            val latestEvent = awaitRecordedEvent(recorded)
            assertEquals(2L, latestEvent.chapterId)
            assertEquals(0, latestEvent.lastPageRead)

            releaseAdjacentLoad.complete(Unit)

            val staleEvent = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeoutOrNull(2_000) { recorded.receive() }
            }
            assertNull(staleEvent)
            assertEquals(2L, viewModel.state.value.currentChapter?.chapter?.id)
            assertEquals(1, viewModel.state.value.currentPage)
            assertEquals(0, next.requestedPage)
            assertEquals(0, next.chapter.last_page_read)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun awaitRecordedEvent(events: Channel<ReadingProgressEvent>): ReadingProgressEvent =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { events.receive() }
        }

    private fun materializeChapter(readerChapter: ReaderChapter) {
        if (readerChapter.state !is ReaderChapter.State.Loaded) {
            readerChapter.state = ReaderChapter.State.Loading
            readerChapter.state = ReaderChapter.State.Loaded(
                (0..2).map { index ->
                    ReaderPage(index = index).apply { chapter = readerChapter }
                },
            )
        }
    }

    private fun chapter(id: Long) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        name = "Chapter $id",
        chapterNumber = id.toDouble(),
    )
}
