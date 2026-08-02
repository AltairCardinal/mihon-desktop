package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.SavedStateHandle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.failPageListForTest
import eu.kanade.tachiyomi.ui.reader.model.openPageListForTest
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class ReaderChapterWindowProductionWiringTest {

    @Test
    fun `ReaderViewModel preloads only an adjacent target and commits the shared window on activation`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val manga = Manga.create().copy(
                id = 1,
                source = 7,
                title = "Reader window",
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            )
            val chapters = (1L..4L).map { id -> chapter(id) }
            val source = mockk<Source>()
            val sourceManager = mockk<SourceManager> {
                every { isInitialized } returns MutableStateFlow(true)
                every { getOrStub(manga.source) } returns source
            }
            val getManga = mockk<GetManga>()
            coEvery { getManga.await(manga.id) } returns manga
            val getChapters = mockk<GetChaptersByMangaId>()
            coEvery { getChapters.await(manga.id, applyScanlatorFilter = true) } returns chapters
            val downloadManager = mockk<DownloadManager>(relaxed = true)
            val loadAttempts = mutableListOf<Long>()
            val windowLoadEntryStates = mutableListOf<Pair<Long, ReaderChapterLoadState>>()
            val chapterLoader = mockk<ChapterLoader>()
            val materializeChapter: (ReaderChapter) -> Unit = { readerChapter ->
                loadAttempts += checkNotNull(readerChapter.chapter.id)
                if (readerChapter.state !is ReaderChapter.State.Loaded) {
                    readerChapter.publishLoadedPageListForTest(
                        listOf(ReaderPage(index = 0).apply { this.chapter = readerChapter }),
                    )
                }
            }
            coEvery { chapterLoader.loadChapter(any()) } coAnswers {
                materializeChapter(firstArg())
            }
            coEvery { chapterLoader.loadChapter(any(), any()) } coAnswers {
                val readerChapter = firstArg<ReaderChapter>()
                windowLoadEntryStates += checkNotNull(readerChapter.chapter.id) to
                    readerChapter.sharedSessionStateFlow.value.activeChapter.loadState
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
            val viewModel = ReaderViewModel(
                savedState = SavedStateHandle(),
                sourceManager = sourceManager,
                downloadManager = downloadManager,
                downloadProvider = mockk(relaxed = true),
                imageSaver = mockk(relaxed = true),
                readerPreferences = readerPreferences,
                basePreferences = basePreferences,
                downloadPreferences = downloadPreferences,
                trackPreferences = mockk(relaxed = true),
                trackChapter = mockk(relaxed = true),
                getManga = getManga,
                getChaptersByMangaId = getChapters,
                getNextChapters = mockk(relaxed = true),
                upsertHistory = mockk(relaxed = true),
                updateChapter = mockk(relaxed = true),
                recordReadingProgress = mockk(relaxed = true),
                setMangaViewerFlags = mockk(relaxed = true),
                getIncognitoState = mockk(relaxed = true),
                libraryPreferences = mockk(relaxed = true),
                chapterLoaderFactory = { _: Manga, _: Source -> chapterLoader },
            )

            assertTrue(viewModel.init(manga.id, initialChapterId = 2).isSuccess)
            val initialWindow = requireNotNull(viewModel.state.value.chapterWindow)
            assertEquals(ReaderChapterId(2), initialWindow.currentChapterId)
            assertEquals(ReaderChapterId(1), initialWindow.previousChapterId)
            assertEquals(ReaderChapterId(3), initialWindow.nextChapterId)

            val next = requireNotNull(viewModel.state.value.viewerChapters?.nextChapter)
            val staleOnlineLoader = object : PageLoader() {
                override var isLocal = false
                override suspend fun getPages() = emptyList<ReaderPage>()
            }
            next.failPageListForTest(
                generation = next.openPageListForTest(),
                error = IllegalStateException("online load failed"),
            )
            next.pageLoader = staleOnlineLoader
            every {
                downloadManager.isChapterDownloaded(any(), any(), any(), any(), any(), any())
            } returns true
            viewModel.preload(next)
            val outsider = ReaderChapter(chapter(99))
            viewModel.preload(outsider)

            assertTrue(staleOnlineLoader.isRecycled)
            assertEquals(listOf(3L to ReaderChapterLoadState.Wait), windowLoadEntryStates)
            assertSame(initialWindow, viewModel.state.value.chapterWindow)
            assertEquals(listOf(2L, 3L), loadAttempts)

            viewModel.loadNextChapter()

            val activated = requireNotNull(viewModel.state.value.chapterWindow)
            assertEquals(ReaderChapterId(3), activated.currentChapterId)
            assertEquals(ReaderChapterId(2), activated.previousChapterId)
            assertEquals(ReaderChapterId(4), activated.nextChapterId)
            assertEquals(1, activated.activationSequence)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun chapter(id: Long) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        name = "Chapter $id",
        chapterNumber = id.toDouble(),
    )
}
