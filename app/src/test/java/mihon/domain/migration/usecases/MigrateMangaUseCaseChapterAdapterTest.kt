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
        val command = slot<mihon.domain.migration.MigrationCommit>()
        val repository = mockk<tachiyomi.domain.manga.repository.MangaRepository>()
        coEvery { repository.commitMigration(capture(command)) } returns Manga.create().copy(id = 2)
        val updateChapter = mockk<UpdateChapter>()

        val getTracks = mockk<GetTracks>()
        coEvery { getTracks.await(1) } returns emptyList()
        val trackerManager = mockk<TrackerManager>()
        every { trackerManager.trackers } returns emptyList()
        val useCase = MigrateMangaUseCase(
            sourcePreferences = preferences,
            trackerManager = trackerManager,
            sourceManager = sourceManager,
            downloadManager = mockk<DownloadManager>(relaxed = true),
            syncChaptersWithSource = mockk<SyncChaptersWithSource>(relaxed = true),
            getTracks = getTracks,
            insertTrack = mockk<InsertTrack>(relaxed = true),
            coverCache = mockk<CoverCache>(relaxed = true),
            mangaRepository = repository,
        )

        useCase(
            current = Manga.create().copy(id = 1, source = 11, title = "Source"),
            target = Manga.create().copy(id = 2, source = 22, title = "Target"),
            replace = false,
        )

        assertEquals(1L, command.captured.sourceMangaId)
        assertEquals(2L, command.captured.targetMangaId)
        assertEquals(setOf(MigrationFlag.CHAPTER), command.captured.flags)
        // NaN cannot be stored in SQLite's NOT NULL REAL column; retain the common input contract.
        val patches = mihon.domain.migration.MigrationOrchestrator().chapterUpdates(
            getChapters.await(1).map { mihon.domain.migration.MigrationChapter(it.id, it.chapterNumber, it.read) },
            getChapters.await(2).map { mihon.domain.migration.MigrationChapter(it.id, it.chapterNumber, it.read) },
        )
        assertEquals(listOf(null, null, null), patches.map { it.read })
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
            syncChaptersWithSource = mockk(relaxed = true),
            getTracks = mockk(relaxed = true),
            insertTrack = mockk(relaxed = true),
            coverCache = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
        )

        val result = useCase(
            current = Manga.create().copy(id = 1, source = 11, title = "Source"),
            target = Manga.create().copy(id = 2, source = 22, title = "Target"),
            replace = false,
        )

        assertEquals(true, result.isFailure)
        assertEquals("Target source 22 is unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `rejected target directory cannot report a completed migration`() = runTest {
        val preferences = mockk<SourcePreferences>()
        val flags = mockk<Preference<Set<MigrationFlag>>>()
        every { preferences.migrationFlags() } returns flags
        every { flags.get() } returns emptySet()
        val source = mockk<Source>()
        coEvery { source.getChapterList(any()) } returns listOf(
            eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                url = "/1"
                name = "1"
            },
        )
        val sources = mockk<SourceManager>()
        every { sources.get(22) } returns source
        every { sources.get(11) } returns null
        val sync = mockk<SyncChaptersWithSource>()
        coEvery { sync.await(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("directory refused")
        val trackers = mockk<TrackerManager>()
        every { trackers.trackers } returns emptyList()
        val tracks = mockk<GetTracks>()
        coEvery { tracks.await(any()) } returns emptyList()
        val useCase = MigrateMangaUseCase(
            preferences, trackers, sources, mockk(relaxed = true), sync,
            tracks, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
        val result = useCase(
            Manga.create().copy(id = 1, source = 11, url = "/source", title = "Source"),
            Manga.create().copy(id = 2, source = 22, url = "/target", title = "Target"),
            true,
        )
        assertEquals(true, result.isFailure, "A rejected real directory command cannot be ignored")
        assertEquals("directory refused", result.exceptionOrNull()?.message)
        io.mockk.coVerify(exactly = 1) { sync.await(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rejected atomic membership cannot report a completed migration`() = runTest {
        val preferences = mockk<SourcePreferences>()
        val flags = mockk<Preference<Set<MigrationFlag>>>()
        every { preferences.migrationFlags() } returns flags
        every { flags.get() } returns emptySet()
        val source = mockk<Source>()
        coEvery { source.getChapterList(any()) } returns listOf(
            eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                url = "/1"
                name = "1"
            },
        )
        val sources = mockk<SourceManager>()
        every { sources.get(22) } returns source
        every { sources.get(11) } returns null
        val sync = mockk<SyncChaptersWithSource>()
        coEvery { sync.await(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        val repository = mockk<tachiyomi.domain.manga.repository.MangaRepository>()
        coEvery { repository.commitMigration(any()) } throws java.io.IOException("membership refused")
        val trackers = mockk<TrackerManager>()
        every { trackers.trackers } returns emptyList()
        val tracks = mockk<GetTracks>()
        coEvery { tracks.await(any()) } returns emptyList()
        val useCase = MigrateMangaUseCase(
            preferences, trackers, sources, mockk(relaxed = true), sync,
            tracks, mockk(relaxed = true), mockk(relaxed = true), repository,
        )
        val result = useCase(
            Manga.create().copy(id = 1, source = 11, url = "/source", title = "Source"),
            Manga.create().copy(id = 2, source = 22, url = "/target", title = "Target"),
            true,
        )
        assertEquals(true, result.isFailure, "A rejected atomic commit cannot be reported as success")
        assertEquals("membership refused", result.exceptionOrNull()?.message)
        io.mockk.coVerify(exactly = 1) { repository.commitMigration(any()) }
    }

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Boolean) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        chapterNumber = number,
        read = read,
    )
}
