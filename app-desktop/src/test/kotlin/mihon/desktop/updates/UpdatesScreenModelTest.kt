package mihon.desktop.updates

import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.domain.fakes.FakeUpdatesRepository
import mihon.desktop.download.DownloadItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import java.time.Instant

class UpdatesScreenModelTest {

    @Test
    fun `initial state uses preference defaults`() {
        val model = buildModel()

        assertTrue(model.state.value.items.isEmpty())
        assertFalse(model.state.value.isRefreshing)
        assertFalse(model.state.value.showMarkAllReadDialog)
        assertFalse(model.state.value.showFilterDialog)
        assertEquals(TriState.DISABLED, model.state.value.filterUnread)
        assertEquals(TriState.DISABLED, model.state.value.filterDownloaded)
        assertFalse(model.state.value.filterExcludedScanlators)
    }

    @Test
    fun `loadUpdates applies downloaded filter without losing raw items`() = runTest {
        val updatesRepository = FakeUpdatesRepository()
        updatesRepository.addUpdate(sampleUpdate(chapterId = 1L))
        updatesRepository.addUpdate(sampleUpdate(chapterId = 2L))
        val model = buildModel(
            updatesRepository = updatesRepository,
            isDownloaded = { it.chapterId == 2L },
        )

        model.toggleDownloadedFilter()
        model.loadUpdates(since = Instant.EPOCH)

        assertEquals(listOf(2L), model.state.value.items.map { it.chapterId })

        model.toggleDownloadedFilter()

        assertEquals(listOf(1L), model.state.value.items.map { it.chapterId })

        model.toggleDownloadedFilter()

        assertEquals(listOf(1L, 2L), model.state.value.items.map { it.chapterId })
    }

    @Test
    fun `markAllRead marks unread visible items and closes dialog`() = runTest {
        val updatesRepository = FakeUpdatesRepository()
        val chapterRepository = FakeChapterRepository()
        updatesRepository.addUpdate(sampleUpdate(chapterId = 1L, read = false))
        updatesRepository.addUpdate(sampleUpdate(chapterId = 2L, read = true))
        val model = buildModel(
            updatesRepository = updatesRepository,
            chapterRepository = chapterRepository,
        )
        model.loadUpdates(since = Instant.EPOCH)
        model.setShowMarkAllReadDialog(true)

        model.markAllRead()

        assertEquals(listOf(1L), chapterRepository.updates.map { it.id })
        assertTrue(chapterRepository.updates.all { it.read == true })
        assertFalse(model.state.value.showMarkAllReadDialog)
        assertTrue(model.state.value.items.all { it.read })
    }

    @Test
    fun `reader request uses update item and manga viewer flags`() = runTest {
        val mangaRepository = FakeMangaRepository()
        mangaRepository.seed(
            Manga.create().copy(
                id = 10L,
                source = 5L,
                title = "Manga",
                viewerFlags = 0x33L,
            ),
        )
        val model = buildModel(mangaRepository = mangaRepository)

        val request = model.readerRequestFor(sampleUpdate(chapterId = 100L, mangaId = 10L, lastPageRead = 4L))

        assertEquals("Chapter 100", request.chapterTitle)
        assertEquals("Title", request.mangaTitle)
        assertEquals(1L, request.sourceId)
        assertEquals("/ch/100", request.chapterUrl)
        assertEquals(100L, request.chapterId)
        assertEquals(10L, request.mangaId)
        assertEquals(0x33L, request.mangaViewerFlags)
        assertEquals(4, request.initialPage)
    }

    @Test
    fun `enqueueDownload forwards update as download item`() {
        val enqueued = mutableListOf<DownloadItem>()
        val model = buildModel(enqueueDownload = { enqueued += it })

        model.enqueueDownload(sampleUpdate(chapterId = 7L))

        assertEquals(
            DownloadItem(
                sourceId = 1L,
                mangaTitle = "Title",
                chapterName = "Chapter 7",
                chapterId = 7L,
                chapterUrl = "/ch/7",
            ),
            enqueued.single(),
        )
    }

    private fun buildModel(
        updatesRepository: FakeUpdatesRepository = FakeUpdatesRepository(),
        chapterRepository: FakeChapterRepository = FakeChapterRepository(),
        mangaRepository: FakeMangaRepository = FakeMangaRepository(),
        isDownloaded: (UpdatesWithRelations) -> Boolean = { false },
        enqueueDownload: (DownloadItem) -> Unit = {},
    ): UpdatesScreenModel {
        return UpdatesScreenModel(
            getUpdates = GetUpdates(updatesRepository),
            updateChapter = UpdateChapter(chapterRepository),
            getManga = GetManga(mangaRepository),
            updatesPreferences = UpdatesPreferences(InMemoryPreferenceStore()),
            isDownloaded = isDownloaded,
            enqueueDownload = enqueueDownload,
        )
    }

    private fun sampleUpdate(
        chapterId: Long = 1L,
        mangaId: Long = 10L,
        read: Boolean = false,
        lastPageRead: Long = 0L,
        dateFetch: Long = System.currentTimeMillis(),
    ) = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = "Title",
        chapterId = chapterId,
        chapterName = "Chapter $chapterId",
        scanlator = null,
        chapterUrl = "/ch/$chapterId",
        read = read,
        bookmark = false,
        lastPageRead = lastPageRead,
        sourceId = 1L,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = 1L,
            isMangaFavorite = true,
            url = "https://example.com/cover.jpg",
            lastModified = 0L,
        ),
    )
}
