package mihon.domain.migration.usecases

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

class MigrateMangaUseCaseChapterAdapterTest {
    @Test
    fun `Android chapter adapter only writes read when shared patch changes target`() = runTest {
        val flags = mockk<Preference<Set<MigrationFlag>>>()
        every { flags.get() } returns setOf(MigrationFlag.CHAPTER)
        val preferences = mockk<SourcePreferences>()
        every { preferences.migrationFlags() } returns flags
        val targetSource = mockk<Source>()
        coEvery { targetSource.getChapterList(any()) } returns emptyList()
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.get(22) } returns targetSource
        every { sourceManager.get(11) } returns null
        val getChapters = mockk<GetChaptersByMangaId>()
        coEvery { getChapters.await(1) } returns listOf(
            chapter(1, 1, 2.0, read = true),
            chapter(2, 1, Double.NaN, read = true),
        )
        coEvery { getChapters.await(2) } returns listOf(
            chapter(11, 2, 1.0, read = false),
            chapter(12, 2, 3.0, read = true),
            chapter(13, 2, Double.NaN, read = true),
        )
        val updates = slot<List<ChapterUpdate>>()
        val updateChapter = mockk<UpdateChapter>()
        coEvery { updateChapter.awaitAll(capture(updates)) } just Runs
        val getTracks = mockk<GetTracks>()
        coEvery { getTracks.await(1) } returns emptyList()
        val trackerManager = mockk<TrackerManager>()
        every { trackerManager.trackers } returns emptyList()
        val useCase = MigrateMangaUseCase(
            sourcePreferences = preferences,
            trackerManager = trackerManager,
            sourceManager = sourceManager,
            downloadManager = mockk<DownloadManager>(relaxed = true),
            updateManga = mockk<UpdateManga>(relaxed = true),
            getChaptersByMangaId = getChapters,
            syncChaptersWithSource = mockk<SyncChaptersWithSource>(relaxed = true),
            updateChapter = updateChapter,
            getCategories = mockk<GetCategories>(relaxed = true),
            setMangaCategories = mockk<SetMangaCategories>(relaxed = true),
            getTracks = getTracks,
            insertTrack = mockk<InsertTrack>(relaxed = true),
            coverCache = mockk<CoverCache>(relaxed = true),
        )

        useCase(
            current = Manga.create().copy(id = 1, source = 11, title = "Source"),
            target = Manga.create().copy(id = 2, source = 22, title = "Target"),
            replace = false,
        )

        assertEquals(null, updates.captured.single { it.id == 11L }.read)
        assertEquals(null, updates.captured.single { it.id == 12L }.read)
        assertEquals(null, updates.captured.single { it.id == 13L }.read)
    }

    @Test
    fun `missing target source returns an explicit migration failure`() = runTest {
        val flags = mockk<Preference<Set<MigrationFlag>>>()
        every { flags.get() } returns emptySet()
        val preferences = mockk<SourcePreferences>()
        every { preferences.migrationFlags() } returns flags
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.get(22) } returns null
        val trackerManager = mockk<TrackerManager>()
        every { trackerManager.trackers } returns emptyList()
        val useCase = MigrateMangaUseCase(
            sourcePreferences = preferences,
            trackerManager = trackerManager,
            sourceManager = sourceManager,
            downloadManager = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            getChaptersByMangaId = mockk(relaxed = true),
            syncChaptersWithSource = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            getCategories = mockk(relaxed = true),
            setMangaCategories = mockk(relaxed = true),
            getTracks = mockk(relaxed = true),
            insertTrack = mockk(relaxed = true),
            coverCache = mockk(relaxed = true),
        )

        val result = useCase(
            current = Manga.create().copy(id = 1, source = 11, title = "Source"),
            target = Manga.create().copy(id = 2, source = 22, title = "Target"),
            replace = false,
        )

        assertEquals(true, result.isFailure)
        assertEquals("Target source 22 is unavailable", result.exceptionOrNull()?.message)
    }

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Boolean) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        chapterNumber = number,
        read = read,
    )
}
