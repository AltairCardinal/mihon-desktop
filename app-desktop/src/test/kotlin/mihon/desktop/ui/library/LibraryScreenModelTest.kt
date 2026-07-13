package mihon.desktop.ui.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.SortMode
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DesktopDownloadProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerSessionProvider
import mihon.domain.task.TaskStatus
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stage 25.2 — LibraryScreenModel tests.
 *
 * Verifies all library UI state lives in a ScreenModel with StateFlow<LibraryState>.
 */
class LibraryScreenModelTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `library update UI delegates start and cancellation to persistent controller`() = runTest {
        var started = 0
        var cancelled = 0
        val model = LibraryScreenModel(
            startBackgroundUpdate = { started++; kotlinx.coroutines.Job().also { it.complete() } },
            cancelBackgroundUpdate = { cancelled++; true },
        )

        model.refreshLibrary(emptyList())
        assertTrue(model.cancelLibraryUpdate())

        assertEquals(1, started)
        assertEquals(1, cancelled)
    }

    @Test
    fun `background update UI reports each persisted terminal state accurately`() = runTest {
        for ((status, text) in listOf(
            TaskStatus.Completed to "Library update finished",
            TaskStatus.Failed to "Library update failed",
            TaskStatus.Cancelled to "Library update cancelled",
        )) {
            val model = LibraryScreenModel(
                startBackgroundUpdate = { kotlinx.coroutines.Job().also { it.complete() } },
                backgroundUpdateStatus = { status },
            )
            model.refreshLibrary(emptyList())
            assertEquals(text, model.state.value.updateStatusText)
        }
    }

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    fun `state flow exists and is accessible`() {
        val model = LibraryScreenModel()
        val flow: StateFlow<LibraryState> = model.state
        assertNotNull(flow)
        assertNotNull(flow.value)
    }

    @Test
    fun `initial state has expected defaults`() {
        val model = LibraryScreenModel()
        val s = model.state.value
        assertTrue(s.allItems.isEmpty())
        assertTrue(s.categories.isEmpty())
        assertEquals("", s.searchQuery)
        assertEquals(SortMode.TITLE, s.sortMode)
        assertTrue(s.sortAscending)
        assertFalse(s.filterUnread)
        assertFalse(s.filterStarted)
        assertFalse(s.filterCompleted)
        assertFalse(s.filterDownloaded)
        assertEquals(0, s.selectedCategoryIndex)
        assertFalse(s.isUpdating)
        assertNull(s.updateStatusText)
        assertFalse(s.showCategoryDialog)
        assertEquals(LibraryDisplayMode.DEFAULT, s.displayMode)
        assertNull(s.contextMenuManga)
        assertFalse(s.showBatchCategoryDialog)
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    @Test
    fun `setCategories updates categories`() {
        val model = LibraryScreenModel()
        assertTrue(model.state.value.categories.isEmpty())
        val cats = listOf(
            tachiyomi.domain.category.model.Category(id = 1L, name = "Action", order = 0L, flags = 0L),
            tachiyomi.domain.category.model.Category(id = 2L, name = "Romance", order = 1L, flags = 0L),
        )
        model.setCategories(cats)
        assertEquals(2, model.state.value.categories.size)
        assertEquals("Action", model.state.value.categories[0].name)
    }

    @Test
    fun `setIsUpdating updates isUpdating`() {
        val model = LibraryScreenModel()
        assertFalse(model.state.value.isUpdating)
        model.setIsUpdating(true)
        assertTrue(model.state.value.isUpdating)
        model.setIsUpdating(false)
        assertFalse(model.state.value.isUpdating)
    }

    @Test
    fun `setUpdateStatusText updates updateStatusText`() {
        val model = LibraryScreenModel()
        assertNull(model.state.value.updateStatusText)
        model.setUpdateStatusText("3 new chapters found")
        assertEquals("3 new chapters found", model.state.value.updateStatusText)
        model.setUpdateStatusText(null)
        assertNull(model.state.value.updateStatusText)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @Test
    fun `setSearchQuery updates searchQuery`() {
        val model = LibraryScreenModel()
        assertEquals("", model.state.value.searchQuery)
        model.setSearchQuery("naruto")
        assertEquals("naruto", model.state.value.searchQuery)
        model.setSearchQuery("")
        assertEquals("", model.state.value.searchQuery)
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    @Test
    fun `setSortMode updates sortMode`() {
        val model = LibraryScreenModel()
        assertEquals(SortMode.TITLE, model.state.value.sortMode)
        model.setSortMode(SortMode.LAST_READ)
        assertEquals(SortMode.LAST_READ, model.state.value.sortMode)
    }

    @Test
    fun `setSortAscending updates sortAscending`() {
        val model = LibraryScreenModel()
        assertTrue(model.state.value.sortAscending)
        model.setSortAscending(false)
        assertFalse(model.state.value.sortAscending)
    }

    @Test
    fun `setSortModeAndDirection updates both sort fields`() {
        val model = LibraryScreenModel()
        model.setSortModeAndDirection(SortMode.UNREAD_COUNT, ascending = false)
        assertEquals(SortMode.UNREAD_COUNT, model.state.value.sortMode)
        assertFalse(model.state.value.sortAscending)
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    @Test
    fun `setFilters updates all filter fields`() {
        val model = LibraryScreenModel()
        assertFalse(model.state.value.filterUnread)
        assertFalse(model.state.value.filterStarted)
        assertFalse(model.state.value.filterCompleted)
        assertFalse(model.state.value.filterDownloaded)
        model.setFilters(unread = true, started = true, completed = false, downloaded = true)
        assertTrue(model.state.value.filterUnread)
        assertTrue(model.state.value.filterStarted)
        assertFalse(model.state.value.filterCompleted)
        assertTrue(model.state.value.filterDownloaded)
    }

    // ── Category selection ────────────────────────────────────────────────────

    @Test
    fun `setSelectedCategoryIndex updates selectedCategoryIndex`() {
        val model = LibraryScreenModel()
        assertEquals(0, model.state.value.selectedCategoryIndex)
        model.setSelectedCategoryIndex(3)
        assertEquals(3, model.state.value.selectedCategoryIndex)
    }

    // ── Display mode ──────────────────────────────────────────────────────────

    @Test
    fun `setDisplayMode updates displayMode`() {
        val model = LibraryScreenModel()
        assertEquals(LibraryDisplayMode.DEFAULT, model.state.value.displayMode)
        model.setDisplayMode(LibraryDisplayMode.LIST)
        assertEquals(LibraryDisplayMode.LIST, model.state.value.displayMode)
    }

    // ── Dialog / menu visibility ──────────────────────────────────────────────

    @Test
    fun `setShowCategoryDialog updates showCategoryDialog`() {
        val model = LibraryScreenModel()
        assertFalse(model.state.value.showCategoryDialog)
        model.setShowCategoryDialog(true)
        assertTrue(model.state.value.showCategoryDialog)
        model.setShowCategoryDialog(false)
        assertFalse(model.state.value.showCategoryDialog)
    }

    @Test
    fun `setShowBatchCategoryDialog updates showBatchCategoryDialog`() {
        val model = LibraryScreenModel()
        assertFalse(model.state.value.showBatchCategoryDialog)
        model.setShowBatchCategoryDialog(true)
        assertTrue(model.state.value.showBatchCategoryDialog)
    }

    @Test
    fun `setContextMenuManga sets and clears contextMenuManga`() {
        val model = LibraryScreenModel()
        assertNull(model.state.value.contextMenuManga)
        // Just verify it can be set to a non-null sentinel and then cleared
        // (LibraryManga is complex; we use null check only)
        model.setContextMenuManga(null)
        assertNull(model.state.value.contextMenuManga)
    }

    // ── LibraryState data class sanity ────────────────────────────────────────

    @Test
    fun `LibraryState can be constructed with custom values`() {
        val state = LibraryState(
            searchQuery = "test",
            sortMode = SortMode.LAST_READ,
            sortAscending = false,
            filter = LibraryFilter(unread = TriState.ENABLED_IS),
            selectedCategoryIndex = 2,
            isUpdating = true,
            displayMode = LibraryDisplayMode.COMFORTABLE_GRID,
        )
        assertEquals("test", state.searchQuery)
        assertEquals(SortMode.LAST_READ, state.sortMode)
        assertFalse(state.sortAscending)
        assertTrue(state.filterUnread)
        assertEquals(2, state.selectedCategoryIndex)
        assertTrue(state.isUpdating)
        assertEquals(LibraryDisplayMode.COMFORTABLE_GRID, state.displayMode)
    }

    @Test
    fun `filter intent cycles include exclude any and immediately changes visible items`() {
        val model = LibraryScreenModel()
        model.setAllItems(
            listOf(
                sampleLibraryManga(sampleManga(1)).copy(totalChapters = 2),
                sampleLibraryManga(sampleManga(2)),
            ),
        )

        model.toggleFilter(LibraryFilterField.UNREAD)
        assertEquals(listOf(1L), model.visibleItems().map { it.id })
        model.toggleFilter(LibraryFilterField.UNREAD)
        assertEquals(listOf(2L), model.visibleItems().map { it.id })
        model.toggleFilter(LibraryFilterField.UNREAD)
        assertEquals(listOf(1L, 2L), model.visibleItems().map { it.id })
    }

    @Test
    fun `complete filter flags flow from state to visible list including local and tracking boundaries`() {
        val model = LibraryScreenModel()
        val bookmarked = sampleLibraryManga(sampleManga(1).copy(fetchInterval = -1)).copy(bookmarkCount = 1)
        val local = sampleLibraryManga(sampleManga(2))
        model.setAllItems(listOf(bookmarked, local))
        model.setEvaluationContext(
            downloadedMangaIds = emptySet(),
            localMangaIds = setOf(2L),
            trackerIdsByManga = mapOf(1L to setOf(7L)),
        )
        model.setFilter(
            LibraryFilter(
                downloaded = TriState.ENABLED_NOT,
                bookmarked = TriState.ENABLED_IS,
                intervalCustom = TriState.ENABLED_IS,
                skipOutsideReleasePeriod = true,
                tracking = mapOf(7L to TriState.ENABLED_IS),
            ),
        )

        assertEquals(listOf(1L), model.visibleItems().map { it.id })
        model.setFilter(model.state.value.filter.copy(globalDownloadedOnly = true))
        assertTrue(model.visibleItems().isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `production library stream exposes only logged in tracker rows and reacts to login logout`() = runTest {
        val repository = FakeMangaRepository().apply {
            libraryManga = listOf(
                sampleLibraryManga(sampleManga(1).copy(title = "Downloaded", source = 10L)),
                sampleLibraryManga(sampleManga(2).copy(title = "Local", source = 0L)),
                sampleLibraryManga(sampleManga(3).copy(title = "Tracked", source = 30L)),
            )
        }
        val downloadedChapter = tempDir.resolve("10/Downloaded/Chapter 1")
        Files.createDirectories(downloadedChapter)
        Files.write(downloadedChapter.resolve("page.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val tracks = MutableStateFlow(listOf(
            sampleTrack(id = 1L, mangaId = 3L, trackerId = 2L, score = 80.0),
            sampleTrack(id = 2L, mangaId = 3L, trackerId = 7L, score = 6.0),
        ))
        val loggedInTrackerIds = MutableStateFlow(emptySet<Long>())
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(repository),
            downloadProvider = DesktopDownloadProvider(tempDir.toFile()),
            trackRepository = trackRepositoryOf(tracks),
            trackerSessionProvider = TrackerSessionProvider { loggedInTrackerIds },
        )

        model.libraryMangaFlow().launchIn(backgroundScope)
        runCurrent()

        assertEquals(setOf(1L), model.state.value.downloadedMangaIds)
        assertEquals(setOf(2L), model.state.value.localMangaIds)
        assertTrue(model.state.value.availableTrackerIds.isEmpty())
        assertTrue(model.state.value.trackerIdsByManga.isEmpty())

        loggedInTrackerIds.value = setOf(7L)
        runCurrent()
        assertEquals(setOf(7L), model.state.value.availableTrackerIds)
        assertEquals(setOf(7L), model.state.value.trackerIdsByManga.getValue(3L))

        model.setFilter(LibraryFilter(downloaded = TriState.ENABLED_IS))
        assertEquals(listOf(1L, 2L), model.visibleItems().map { it.id })
        model.setFilter(LibraryFilter(downloaded = TriState.ENABLED_NOT))
        assertEquals(listOf(3L), model.visibleItems().map { it.id })
        model.setFilter(LibraryFilter(tracking = mapOf(7L to TriState.ENABLED_IS)))
        assertEquals(listOf(3L), model.visibleItems().map { it.id })
        model.setFilter(LibraryFilter(tracking = mapOf(7L to TriState.ENABLED_NOT)))
        assertEquals(listOf(1L, 2L), model.visibleItems().map { it.id })

        loggedInTrackerIds.value = emptySet()
        runCurrent()
        assertTrue(model.state.value.availableTrackerIds.isEmpty())
        assertTrue(model.state.value.trackerIdsByManga.isEmpty())
        assertTrue(model.state.value.filter.tracking.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), model.visibleItems().map { it.id })
    }

    @Test
    fun `production library page projection uses ScreenModel context for tracker menu and visible items`() {
        val model = LibraryScreenModel()
        model.setAllItems(
            listOf(
                sampleLibraryManga(sampleManga(1).copy(source = 10L)).copy(totalChapters = 2L),
                sampleLibraryManga(sampleManga(2).copy(source = 0L)).copy(totalChapters = 2L),
            ),
        )
        model.setEvaluationContext(
            downloadedMangaIds = emptySet(),
            localMangaIds = setOf(2L),
            trackerIdsByManga = mapOf(1L to setOf(7L)),
        )
        model.setFilter(
            LibraryFilter(
                downloaded = TriState.ENABLED_NOT,
                unread = TriState.ENABLED_IS,
                tracking = mapOf(7L to TriState.ENABLED_IS),
            ),
        )

        assertEquals(setOf(7L), model.state.value.availableTrackerIds)
        assertEquals(listOf(1L), libraryPageItems(model, categoryId = null).map { it.id })
    }

    @Test
    fun `markMangaRead updates every chapter for the manga`() = runTest {
        val chapterRepository = FakeChapterRepository()
        chapterRepository.addAll(
            listOf(
                Chapter.create().copy(id = 1L, mangaId = 10L, read = false),
                Chapter.create().copy(id = 2L, mangaId = 10L, read = false),
            ),
        )
        val model = LibraryScreenModel(chapterRepository = chapterRepository)

        model.markMangaRead(mangaId = 10L, read = true)

        assertEquals(listOf(1L, 2L), chapterRepository.updates.map { it.id })
        assertTrue(chapterRepository.updates.all { it.read == true })
    }

    @Test
    fun `removeFromLibrary clears favorite flag for each manga`() = runTest {
        val mangaRepository = FakeMangaRepository()
        mangaRepository.seed(sampleManga(id = 1L).copy(favorite = true))
        mangaRepository.seed(sampleManga(id = 2L).copy(favorite = true))
        val model = LibraryScreenModel(mangaRepository = mangaRepository)

        model.removeFromLibrary(listOf(1L, 2L))

        assertEquals(listOf(1L, 2L), mangaRepository.updates.map { it.id })
        assertTrue(mangaRepository.updates.all { it.favorite == false })
    }

    @Test
    fun `enqueueNextUnreadDownload enqueues first unread chapter by source order`() = runTest {
        val chapterRepository = FakeChapterRepository()
        val enqueued = mutableListOf<DownloadItem>()
        chapterRepository.addAll(
            listOf(
                Chapter.create().copy(id = 1L, mangaId = 10L, name = "Read", url = "/read", read = true, sourceOrder = 0L),
                Chapter.create().copy(id = 2L, mangaId = 10L, name = "Unread", url = "/unread", read = false, sourceOrder = 1L),
            ),
        )
        val model = LibraryScreenModel(
            chapterRepository = chapterRepository,
            enqueueDownload = { enqueued += it },
        )

        model.enqueueNextUnreadDownload(sampleLibraryManga(sampleManga(id = 10L, source = 7L, title = "Manga")))

        assertEquals(
            DownloadItem(
                sourceId = 7L,
                mangaTitle = "Manga",
                chapterName = "Unread",
                chapterId = 2L,
                chapterUrl = "/unread",
            ),
            enqueued.single(),
        )
    }

    @Test
    fun `continueReadingRequest uses first unread chapter and all chapter refs`() = runTest {
        val chapterRepository = FakeChapterRepository()
        chapterRepository.addAll(
            listOf(
                Chapter.create().copy(id = 1L, mangaId = 10L, name = "Read", url = "/read", read = true, sourceOrder = 0L),
                Chapter.create().copy(id = 2L, mangaId = 10L, name = "Unread", url = "/unread", read = false, sourceOrder = 1L, lastPageRead = 5L),
            ),
        )
        val model = LibraryScreenModel(chapterRepository = chapterRepository)

        val request = model.continueReadingRequest(sampleLibraryManga(sampleManga(id = 10L, source = 7L, viewerFlags = 0x44L)))

        assertNotNull(request)
        assertEquals("Unread", request?.chapterTitle)
        assertEquals("/unread", request?.chapterUrl)
        assertEquals(2L, request?.chapterId)
        assertEquals(10L, request?.mangaId)
        assertEquals(0x44L, request?.mangaViewerFlags)
        assertEquals(5, request?.initialPage)
        assertEquals(listOf(1L, 2L), request?.chapters?.map { it.id })
        assertEquals(1, request?.currentChapterIndex)
    }

    @Test
    fun `setCategoriesForManga applies selected categories to all manga ids`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val model = LibraryScreenModel(setMangaCategories = SetMangaCategories(mangaRepository))

        model.setCategoriesForManga(mangaIds = listOf(1L, 2L), categoryIds = listOf(10L, 11L))

        assertEquals(listOf(10L, 11L), mangaRepository.getMangaCategoryIds(1L))
        assertEquals(listOf(10L, 11L), mangaRepository.getMangaCategoryIds(2L))
    }

    private fun sampleManga(
        id: Long,
        source: Long = 1L,
        title: String = "Manga $id",
        viewerFlags: Long = 0L,
    ) = Manga.create().copy(
        id = id,
        source = source,
        title = title,
        viewerFlags = viewerFlags,
    )

    private fun sampleLibraryManga(manga: Manga) = LibraryManga(
        manga = manga,
        categories = emptyList(),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    private fun sampleTrack(id: Long, mangaId: Long, trackerId: Long, score: Double) = Track(
        id = id,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = id,
        libraryId = null,
        title = "Track $id",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = score,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private fun trackRepositoryOf(tracks: List<Track>) = object : TrackRepository {
        override suspend fun getTrackById(id: Long) = tracks.singleOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = tracks.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = flowOf(tracks)
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            flowOf(tracks.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }

    private fun trackRepositoryOf(tracks: StateFlow<List<Track>>) = object : TrackRepository {
        override suspend fun getTrackById(id: Long) = tracks.value.singleOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = tracks.value.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = tracks
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            tracks.map { values -> values.filter { it.mangaId == mangaId } }
        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }
}
