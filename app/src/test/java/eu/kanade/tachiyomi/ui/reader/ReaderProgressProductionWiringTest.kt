package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.SavedStateHandle
import eu.kanade.domain.DomainModule
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
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
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class ReaderProgressProductionWiringTest {

    @Test
    fun `Android domain DI resolves the canonical reading progress recorder`() {
        withIsolatedInjekt {
            Injekt.addSingleton<Database>(mockk(relaxed = true))
            Injekt.importModule(DomainModule())

            assertNotNull(Injekt.get<ReadingProgressRepository>())
            assertNotNull(Injekt.get<RecordReadingProgress>())
        }
    }

    @Test
    fun `Android current settled page records progress through the canonical shared transaction`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val manga = Manga.create().copy(
                id = 1,
                source = 7,
                title = "Reader progress",
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
            val recorded = Channel<ReadingProgressEvent>(Channel.UNLIMITED)
            val recorder = RecordReadingProgress(
                object : ReadingProgressRepository {
                    override suspend fun record(event: ReadingProgressEvent) {
                        recorded.send(event)
                    }
                },
            )
            val chapterLoader = mockk<ChapterLoader>()
            val materializeChapter: (ReaderChapter) -> Unit = { readerChapter ->
                if (readerChapter.state !is ReaderChapter.State.Loaded) {
                    readerChapter.state = ReaderChapter.State.Loading
                    readerChapter.state = ReaderChapter.State.Loaded(
                        (0..2).map { index ->
                            ReaderPage(index = index).apply { chapter = readerChapter }
                        },
                    )
                }
            }
            coEvery { chapterLoader.loadChapter(any()) } coAnswers {
                materializeChapter(firstArg())
            }
            coEvery { chapterLoader.loadChapter(any(), any()) } coAnswers {
                materializeChapter(firstArg())
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
            viewModel.onPageSelected(requireNotNull(current.pages)[1])

            val event = awaitRecordedEvent(recorded)
            assertEquals(2L, event.chapterId)
            assertEquals(1, event.lastPageRead)
            assertEquals(3, event.totalPages)
            assertFalse(event.isRead)
            assertFalse(event.recordHistory)
            assertTrue(event.idempotencyKey.contains(":2:1:"))
            assertEquals(1, current.chapter.last_page_read)

            viewModel.onPageSelected(requireNotNull(current.pages).last())

            val completion = awaitRecordedEvent(recorded)
            assertEquals(2L, completion.chapterId)
            assertEquals(2, completion.lastPageRead)
            assertTrue(completion.isRead)
            assertEquals("finished", completion.trackerEvent)
            assertTrue(current.chapter.read)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun awaitRecordedEvent(events: Channel<ReadingProgressEvent>): ReadingProgressEvent =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { events.receive() }
        }

    private inline fun <T> withIsolatedInjekt(block: () -> T): T {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        return try {
            block()
        } finally {
            Injekt = previous
        }
    }

    private fun chapter(id: Long) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        name = "Chapter $id",
        chapterNumber = id.toDouble(),
    )
}
