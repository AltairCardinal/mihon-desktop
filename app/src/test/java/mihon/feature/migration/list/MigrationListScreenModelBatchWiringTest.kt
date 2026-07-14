package mihon.feature.migration.list

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.migration.BatchMigrationEvent
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.feature.migration.list.models.MigratingManga
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class MigrationListScreenModelBatchWiringTest {
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `production ScreenModel batch action calls shared runner`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val runner = mockk<AndroidBatchMigrationRunner<MigratingManga>>()
        every { runner.run(any(), any(), any()) } returns flowOf(BatchMigrationEvent.Completed(0))
        val falsePreference = mockk<Preference<Boolean>>()
        every { falsePreference.get() } returns false
        val sourceIdsPreference = mockk<Preference<List<Long>>>()
        every { sourceIdsPreference.get() } returns emptyList()
        val preferences = mockk<SourcePreferences>()
        every { preferences.migrationHideUnmatched() } returns falsePreference
        every { preferences.migrationHideWithoutUpdates() } returns falsePreference
        every { preferences.migrationPrioritizeByChapters() } returns falsePreference
        every { preferences.migrationDeepSearchMode() } returns falsePreference
        every { preferences.migrationSources() } returns sourceIdsPreference
        val screenModel = MigrationListScreenModel(
            mangaIds = emptyList(),
            extraSearchQuery = null,
            preferences = preferences,
            sourceManager = mockk<SourceManager>(relaxed = true),
            getManga = mockk<GetManga>(relaxed = true),
            networkToLocalManga = mockk<NetworkToLocalManga>(relaxed = true),
            updateManga = mockk<UpdateManga>(relaxed = true),
            syncChaptersWithSource = mockk<SyncChaptersWithSource>(relaxed = true),
            getChaptersByMangaId = mockk<GetChaptersByMangaId>(relaxed = true),
            migrateManga = mockk<MigrateMangaUseCase>(relaxed = true),
            batchMigrationRunner = runner,
        )

        screenModel.migrateMangas()
        advanceUntilIdle()

        verify(exactly = 1) { runner.run(emptyList(), 0, any()) }
    }

    @Test
    fun `batch failure stays visible with title reason and retries only failed manga`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val failedItem = MigratingManga(
            manga = Manga.create().copy(id = 7, title = "Failed title"),
            chapterCount = 0,
            latestChapter = null,
            source = "Source",
            parentContext = coroutineContext,
        )
        val runner = mockk<AndroidBatchMigrationRunner<MigratingManga>>()
        every { runner.run(any(), any(), any()) } returnsMany listOf(
            flowOf(
                BatchMigrationEvent.Failed(0, failedItem, "network unavailable"),
                BatchMigrationEvent.Completed(1),
            ),
            flowOf(
                BatchMigrationEvent.Succeeded(0, failedItem, Unit),
                BatchMigrationEvent.Completed(1),
            ),
        )
        val screenModel = screenModel(runner)

        screenModel.migrateMangas()
        val dialog = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                screenModel.state.mapNotNull { it.dialog as? MigrationListScreenModel.Dialog.Failures }.first()
            }
        }
        assertEquals(1, dialog.failures.size)
        assertEquals("Failed title", dialog.failures.single().title)
        assertEquals("network unavailable", dialog.failures.single().reason)

        val navigation = async { screenModel.navigateBackEvent.first() }
        screenModel.retryFailedMigrations()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { navigation.await() }
        }

        assertTrue(navigation.isCompleted)
        verify(exactly = 1) { runner.run(listOf(failedItem), 0, any()) }
    }

    @Test
    fun `production migration failure reaches dialog and retry skips successful manga`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val falsePreference = mockk<Preference<Boolean>>()
        every { falsePreference.get() } returns false
        val sourceIdsPreference = mockk<Preference<List<Long>>>()
        every { sourceIdsPreference.get() } returns emptyList()
        val flagsPreference = mockk<Preference<Set<MigrationFlag>>>()
        every { flagsPreference.get() } returns emptySet()
        val preferences = mockk<SourcePreferences>()
        every { preferences.migrationHideUnmatched() } returns falsePreference
        every { preferences.migrationHideWithoutUpdates() } returns falsePreference
        every { preferences.migrationPrioritizeByChapters() } returns falsePreference
        every { preferences.migrationDeepSearchMode() } returns falsePreference
        every { preferences.migrationSources() } returns sourceIdsPreference
        every { preferences.migrationFlags() } returns flagsPreference
        val enabledLanguages = mockk<Preference<Set<String>>>()
        every { enabledLanguages.get() } returns emptySet()
        every { preferences.enabledLanguages() } returns enabledLanguages
        Injekt.addSingleton(preferences)

        val currentSource = mockk<Source>(relaxed = true)
        val successfulTargetSource = mockk<Source>()
        coEvery { successfulTargetSource.getChapterList(any()) } returns emptyList()
        val retryingTargetSource = mockk<Source>()
        coEvery { retryingTargetSource.getChapterList(any()) } throws
            IllegalStateException("offline") andThen emptyList()
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.getOrStub(11) } returns currentSource
        every { sourceManager.get(11) } returns currentSource
        every { sourceManager.get(22) } returns successfulTargetSource
        every { sourceManager.get(33) } returns retryingTargetSource

        val first = Manga.create().copy(id = 1, source = 11, title = "Successful title")
        val second = Manga.create().copy(id = 2, source = 11, title = "Failed title")
        val firstTarget = Manga.create().copy(id = 101, source = 22, title = "First target")
        val secondTarget = Manga.create().copy(id = 102, source = 33, title = "Second target")
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(1) } returns first
        coEvery { getManga.await(2) } returns second
        val getChapters = mockk<GetChaptersByMangaId>()
        coEvery { getChapters.await(any()) } returns emptyList()
        val getTracks = mockk<GetTracks>()
        coEvery { getTracks.await(any()) } returns emptyList()
        val trackerManager = mockk<TrackerManager>()
        every { trackerManager.trackers } returns emptyList()
        val migrateManga = MigrateMangaUseCase(
            sourcePreferences = preferences,
            trackerManager = trackerManager,
            sourceManager = sourceManager,
            downloadManager = mockk<DownloadManager>(relaxed = true),
            updateManga = mockk<UpdateManga>(relaxed = true),
            getChaptersByMangaId = getChapters,
            syncChaptersWithSource = mockk<SyncChaptersWithSource>(relaxed = true),
            updateChapter = mockk<UpdateChapter>(relaxed = true),
            getCategories = mockk<GetCategories>(relaxed = true),
            setMangaCategories = mockk<SetMangaCategories>(relaxed = true),
            getTracks = getTracks,
            insertTrack = mockk<InsertTrack>(relaxed = true),
            coverCache = mockk<CoverCache>(relaxed = true),
        )
        val screenModel = MigrationListScreenModel(
            mangaIds = listOf(1, 2),
            extraSearchQuery = null,
            preferences = preferences,
            sourceManager = sourceManager,
            getManga = getManga,
            networkToLocalManga = mockk<NetworkToLocalManga>(relaxed = true),
            updateManga = mockk<UpdateManga>(relaxed = true),
            syncChaptersWithSource = mockk<SyncChaptersWithSource>(relaxed = true),
            getChaptersByMangaId = getChapters,
            migrateManga = migrateManga,
            batchMigrationRunner = AndroidBatchMigrationRunner(),
        )
        val items = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                screenModel.state.mapNotNull { it.items.takeIf { list -> list.size == 2 } }.first()
            }
        }
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { screenModel.state.first { it.finishedCount == 2 } }
        }
        items.single { it.manga.id == 1L }.searchResult.value = MigratingManga.SearchResult.Success(
            firstTarget,
            0,
            null,
            "Target",
        )
        items.single { it.manga.id == 2L }.searchResult.value = MigratingManga.SearchResult.Success(
            secondTarget,
            0,
            null,
            "Target",
        )

        screenModel.migrateMangas()
        val dialog = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                screenModel.state.mapNotNull { it.dialog as? MigrationListScreenModel.Dialog.Failures }.first()
            }
        }
        assertEquals("Failed title", dialog.failures.single().title)
        assertEquals("offline", dialog.failures.single().reason)

        val navigation = async { screenModel.navigateBackEvent.first() }
        screenModel.retryFailedMigrations()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { navigation.await() }
        }

        coVerify(exactly = 1) { successfulTargetSource.getChapterList(any()) }
        coVerify(exactly = 2) { retryingTargetSource.getChapterList(any()) }
    }

    private fun screenModel(runner: AndroidBatchMigrationRunner<MigratingManga>): MigrationListScreenModel {
        val falsePreference = mockk<Preference<Boolean>>()
        every { falsePreference.get() } returns false
        val sourceIdsPreference = mockk<Preference<List<Long>>>()
        every { sourceIdsPreference.get() } returns emptyList()
        val preferences = mockk<SourcePreferences>()
        every { preferences.migrationHideUnmatched() } returns falsePreference
        every { preferences.migrationHideWithoutUpdates() } returns falsePreference
        every { preferences.migrationPrioritizeByChapters() } returns falsePreference
        every { preferences.migrationDeepSearchMode() } returns falsePreference
        every { preferences.migrationSources() } returns sourceIdsPreference
        return MigrationListScreenModel(
            mangaIds = emptyList(),
            extraSearchQuery = null,
            preferences = preferences,
            sourceManager = mockk<SourceManager>(relaxed = true),
            getManga = mockk<GetManga>(relaxed = true),
            networkToLocalManga = mockk<NetworkToLocalManga>(relaxed = true),
            updateManga = mockk<UpdateManga>(relaxed = true),
            syncChaptersWithSource = mockk<SyncChaptersWithSource>(relaxed = true),
            getChaptersByMangaId = mockk<GetChaptersByMangaId>(relaxed = true),
            migrateManga = mockk<MigrateMangaUseCase>(relaxed = true),
            batchMigrationRunner = runner,
        )
    }
}
