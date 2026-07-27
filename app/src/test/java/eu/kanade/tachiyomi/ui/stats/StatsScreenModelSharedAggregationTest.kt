package eu.kanade.tachiyomi.ui.stats

import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.interactor.AggregateLibraryStats
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import kotlin.time.Duration.Companion.seconds

class StatsScreenModelSharedAggregationTest {

    @Test
    fun `current Android stats screen consumes shared title and chapter aggregation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val library = listOf(
                libraryItem(mangaId = 1, categories = listOf(1), total = 4, read = 2),
                libraryItem(mangaId = 1, categories = listOf(2), total = 4, read = 2),
                libraryItem(mangaId = 2, categories = listOf(1), total = 3, read = 0),
            )
            val getLibraryManga = mockk<GetLibraryManga>()
            coEvery { getLibraryManga.await() } returns library
            val getTracks = mockk<GetTracks>()
            coEvery { getTracks.await(any()) } returns emptyList()
            val preferences = mockk<LibraryPreferences>()
            every { preferences.updateCategories().get() } returns emptySet()
            every { preferences.updateCategoriesExclude().get() } returns emptySet()
            every { preferences.autoUpdateMangaRestrictions().get() } returns emptySet()
            val trackerManager = mockk<TrackerManager>()
            every { trackerManager.loggedInTrackers() } returns emptyList()
            val aggregate = spyk(AggregateLibraryStats())
            val model = StatsScreenModel(
                downloadManager = mockk<DownloadManager> {
                    every { getDownloadCount() } returns 5
                },
                getLibraryManga = getLibraryManga,
                getTotalReadDuration = mockk<GetTotalReadDuration> {
                    coEvery { await() } returns 60
                },
                getTracks = getTracks,
                preferences = preferences,
                trackerManager = trackerManager,
                aggregateLibraryStats = aggregate,
            )

            val state = assertInstanceOf(
                StatsScreenState.Success::class.java,
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5.seconds) { model.state.first { it is StatsScreenState.Success } }
                },
            )
            assertEquals(2, state.overview.libraryMangaCount)
            assertEquals(1, state.titles.startedMangaCount)
            assertEquals(7, state.chapters.totalChapterCount)
            assertEquals(2, state.chapters.readChapterCount)
            verify(exactly = 1) { aggregate(library) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun libraryItem(
        mangaId: Long,
        categories: List<Long>,
        total: Long,
        read: Long,
    ) = LibraryManga(
        manga = Manga.create().copy(id = mangaId, source = 10 + mangaId, status = 1),
        categories = categories,
        totalChapters = total,
        readCount = read,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )
}
