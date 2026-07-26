package mihon.desktop.test

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.domain.fakes.FakeUpdatesRepository
import mihon.desktop.history.HistoryScreenModel
import mihon.desktop.test.http.HistoryTestModeController
import mihon.desktop.test.http.UpdatesTestModeController
import mihon.desktop.updates.UpdatesScreenModel
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.download.DownloadRepository
import mihon.domain.download.EnqueueDownload
import mihon.domain.download.IsChapterDownloaded
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import java.util.Date

class TestModeTimelineHydrationTest {
    @Test
    fun `startup hydration populates updates and history owners before server state is exposed`() = runBlocking {
        val updatesRepository = FakeUpdatesRepository().also { it.addUpdate(update()) }
        val historyRepository = FakeHistoryRepository().also { it.addHistory(history()) }
        val updates = UpdatesTestModeController(updatesModel(updatesRepository))
        val history = HistoryTestModeController(historyModel(historyRepository))

        hydrateTimelineTestModeOwners(updates, history)

        assertEquals(listOf(11L), updates.snapshot().rows.map { it.chapterId })
        assertEquals(listOf(21L), history.snapshot().rows.map { it.chapterId })
    }

    private fun updatesModel(repository: FakeUpdatesRepository): UpdatesScreenModel {
        val downloads = object : DownloadRepository {
            override val queueEntries = flowOf(emptyList<DownloadQueueEntry>())
            override fun enqueue(entry: DownloadQueueEntry) = Unit
            override fun isDownloaded(sourceId: Long, mangaTitle: String, chapterName: String) = false
            override fun cancel(chapterId: Long) = false
            override fun retry(chapterId: Long) = false
            override fun transition(chapterId: Long, target: DownloadQueueStatus) = false
            override fun recover() = emptyList<DownloadQueueEntry>()
        }
        return UpdatesScreenModel(
            GetUpdates(repository),
            UpdateChapter(FakeChapterRepository()),
            GetManga(FakeMangaRepository()),
            UpdatesPreferences(InMemoryPreferenceStore()),
            IsChapterDownloaded(downloads),
            EnqueueDownload(downloads),
        )
    }

    private fun historyModel(repository: FakeHistoryRepository) = HistoryScreenModel(
        GetHistory(repository),
        RemoveHistory(repository),
        GetChapter(FakeChapterRepository()),
        GetManga(FakeMangaRepository()),
    )

    private fun update() = UpdatesWithRelations(
        mangaId = 10,
        mangaTitle = "Manga",
        chapterId = 11,
        chapterName = "Chapter 11",
        scanlator = null,
        chapterUrl = "/chapter/11",
        read = false,
        bookmark = false,
        lastPageRead = 0,
        sourceId = 1,
        dateFetch = System.currentTimeMillis(),
        coverData = MangaCover(10, 1, true, null, 0),
    )

    private fun history() = HistoryWithRelations(
        id = 1,
        chapterId = 21,
        mangaId = 20,
        title = "History",
        chapterNumber = 1.0,
        readAt = Date(),
        readDuration = 1,
        coverData = MangaCover(20, 1, true, null, 0),
    )
}
