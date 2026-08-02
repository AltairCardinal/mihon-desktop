package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.SavedStateHandle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.domain.reader.ReaderNavigationCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class ReaderChapterErrorUiIntegrationTest {

    @Test
    fun `current chapter failure stays visible and retry reloads the same chapter`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val chapterId = 10L
            val manga = Manga.create().copy(id = 1, source = 7, title = "Reader test")
            val chapter = Chapter.create().copy(id = chapterId, mangaId = manga.id, name = "Chapter 10")
            val source = mockk<Source>()
            val sourceManager = mockk<SourceManager> {
                every { isInitialized } returns MutableStateFlow(true)
                every { getOrStub(manga.source) } returns source
            }
            val getManga = mockk<GetManga>()
            coEvery { getManga.await(manga.id) } returns manga
            val getChapters = mockk<GetChaptersByMangaId>()
            coEvery { getChapters.await(manga.id, applyScanlatorFilter = true) } returns listOf(chapter)
            val loadAttempts = CopyOnWriteArrayList<Long>()
            val chapterLoader = mockk<ChapterLoader>()
            coEvery { chapterLoader.loadChapter(any()) } coAnswers {
                loadAttempts += firstArg<ReaderChapter>().chapter.id!!
                throw IOException("chapter failed ${loadAttempts.size}")
            }
            val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
            every { downloadPreferences.autoDownloadWhileReading().get() } returns 0
            val readerPreferences = mockk<ReaderPreferences>(relaxed = true)
            every { readerPreferences.skipRead().get() } returns false
            every { readerPreferences.skipFiltered().get() } returns false
            every { readerPreferences.skipDupe().get() } returns false
            val basePreferences = mockk<BasePreferences>(relaxed = true)
            every { basePreferences.downloadedOnly().get() } returns false
            val viewModel = ReaderViewModel(
                savedState = SavedStateHandle(),
                sourceManager = sourceManager,
                downloadManager = mockk(relaxed = true),
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

            val initialResult = viewModel.init(manga.id, chapterId)

            assertTrue(initialResult.isFailure)
            assertInstanceOf(IOException::class.java, initialResult.exceptionOrNull())
            val initialDialog = assertInstanceOf(
                ReaderViewModel.Dialog.ChapterError::class.java,
                viewModel.state.value.dialog,
            )
            assertEquals(ReaderNavigationCommand.RetryChapter(chapterId), initialDialog.state.retryCommand())

            val retryHandler = ReaderChapterErrorRetryHandler(viewModel::retryChapter)
            retryHandler.retry(initialDialog)
            val retryDialog = withTimeout(5.seconds) {
                viewModel.state.first {
                    it.dialog is ReaderViewModel.Dialog.ChapterError && loadAttempts.size == 2
                }.dialog as ReaderViewModel.Dialog.ChapterError
            }

            assertEquals(listOf(chapterId, chapterId), loadAttempts)
            assertEquals(ReaderNavigationCommand.RetryChapter(chapterId), retryDialog.state.retryCommand())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
