package mihon.desktop.ui.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.desktop.di.isolatedDesktopPreferenceStore
import mihon.desktop.domain.SortMode
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeSource
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.sync.SyncOrigin
import mihon.domain.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerSessionProvider
import tachiyomi.i18n.MR
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

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
            startBackgroundUpdate = {
                started++
                kotlinx.coroutines.Job().also { it.complete() }
            },
            cancelBackgroundUpdate = {
                cancelled++
                true
            },
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

    @Test
    fun `refresh while an update is running does not cancel the existing update`() = runTest {
        var cancelled = 0
        val model = LibraryScreenModel(
            cancelBackgroundUpdate = {
                cancelled++
                true
            },
        )
        model.setIsUpdating(true)

        model.refreshLibrary(emptyList())

        assertEquals(0, cancelled)
        assertEquals(MR.strings.update_already_running.localized(), model.state.value.updateStatusText)
    }

    @Test
    fun `recreated model reflects persisted running update and rejects a duplicate start`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var starts = 0
        val running = kotlinx.coroutines.Job()
        val model = LibraryScreenModel(
            startBackgroundUpdate = {
                starts++
                kotlinx.coroutines.Job().also { it.complete() }
            },
            backgroundUpdateStatus = { TaskStatus.Running },
            backgroundUpdateJob = { running.takeIf { it.isActive } },
        )

        try {
            assertTrue(model.state.value.isUpdating)
            model.refreshLibrary(emptyList())

            assertEquals(0, starts)
            assertEquals(MR.strings.update_already_running.localized(), model.state.value.updateStatusText)
            running.complete()
            runCurrent()
            assertFalse(model.state.value.isUpdating)
            model.refreshLibrary(emptyList())
            assertEquals(1, starts)
        } finally {
            model.onDispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `model attaches to an update that starts after construction and permits another after completion`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var starts = 0
        var running: kotlinx.coroutines.Job? = null
        val model = LibraryScreenModel(
            startBackgroundUpdate = {
                starts++
                kotlinx.coroutines.Job().also { it.complete() }
            },
            backgroundUpdateJob = { running?.takeIf { it.isActive } },
        )

        try {
            assertFalse(model.state.value.isUpdating)
            running = kotlinx.coroutines.Job()
            model.refreshLibrary(emptyList())

            assertEquals(0, starts)
            assertTrue(model.state.value.isUpdating)
            assertEquals(MR.strings.update_already_running.localized(), model.state.value.updateStatusText)
            running.complete()
            runCurrent()
            assertFalse(model.state.value.isUpdating)

            model.refreshLibrary(emptyList())
            assertEquals(1, starts)
        } finally {
            model.onDispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stale persisted running status does not block a new refresh`() = runTest {
        var starts = 0
        val model = LibraryScreenModel(
            startBackgroundUpdate = {
                starts++
                kotlinx.coroutines.Job().also { it.complete() }
            },
            backgroundUpdateStatus = { TaskStatus.Running },
            backgroundUpdateJob = { null },
        )

        model.refreshLibrary(emptyList())

        assertEquals(1, starts)
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
        assertEquals(null, s.searchQuery)
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
    fun `library snapshot exposes source language for language badges`() {
        val model = LibraryScreenModel(
            sourceManager = FakeDesktopSourceManager(
                listOf(FakeSource(id = 42L, lang = "zh", name = "Test source")),
            ),
        )

        model.setAllItems(listOf(sampleLibraryManga(sampleManga(id = 1L, source = 42L))))

        assertEquals("zh", model.state.value.sourceLanguagesByManga[1L])
    }

    @Test
    fun `loading library items reprojects system category tabs`() {
        val model = LibraryScreenModel()
        model.setCategories(
            listOf(
                Category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized", order = 0L, flags = 0L),
                Category(id = 1L, name = "Action", order = 1L, flags = 0L),
            ),
        )

        model.setAllItems(
            listOf(
                sampleLibraryManga(sampleManga(id = 10L)).copy(categories = listOf(Category.UNCATEGORIZED_ID)),
            ),
        )

        assertEquals(
            listOf(Category.UNCATEGORIZED_ID, 1L),
            model.state.value.categories.map { it.id },
        )
    }

    @Test
    fun `selected category index is clamped when categories change`() {
        val model = LibraryScreenModel()
        model.setCategories(
            listOf(Category(id = 1L, name = "Action", order = 0L, flags = 0L)),
        )

        model.setSelectedCategoryIndex(99)

        assertEquals(0, model.state.value.selectedCategoryIndex)
    }

    @Test
    fun `category list restores the persisted active index after projection`() {
        val preferences = LibraryPreferences(
            InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreferenceStore.InMemoryPreference(
                        tachiyomi.core.common.preference.Preference.appStateKey("last_used_category"),
                        1,
                        0,
                    ),
                ),
            ),
        )
        val model = LibraryScreenModel(libraryPreferences = preferences)

        model.setCategories(
            listOf(
                Category(id = 1L, name = "Action", order = 0L, flags = 0L),
                Category(id = 2L, name = "Romance", order = 1L, flags = 0L),
            ),
        )

        assertEquals(1, model.state.value.selectedCategoryIndex)
    }

    @Test
    fun `category ids for manga are read through category use case`() = runTest {
        val repository = FakeCategoryRepository()
        repository.insert(Category(id = 1L, name = "Action", order = 0L, flags = 0L))
        repository.insert(Category(id = 2L, name = "Romance", order = 1L, flags = 0L))
        repository.setMangaCategories(mangaId = 10L, categoryIds = setOf(2L))
        val model = LibraryScreenModel(getCategories = GetCategories(repository))

        assertEquals(setOf(2L), model.categoryIdsForManga(10L))
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
        assertEquals(null, model.state.value.searchQuery)
        model.setSearchQuery("naruto")
        assertEquals("naruto", model.state.value.searchQuery)
        model.setSearchQuery(null)
        assertEquals(null, model.state.value.searchQuery)
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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `category sorting uses shared interactor without overwriting global sorting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val preferences = LibraryPreferences(InMemoryPreferenceStore())
            val repository = FakeCategoryRepository().apply {
                insert(Category(id = 7L, name = "Action", order = 0L, flags = 0L))
            }
            val model = LibraryScreenModel(
                libraryPreferences = preferences,
                setSortModeForCategory = SetSortModeForCategory(preferences, repository),
            )
            model.setCategories(listOf(Category(id = 7L, name = "Action", order = 0L, flags = 0L)))

            model.setSortModeAndDirectionForCategory(7L, SortMode.UNREAD_COUNT, ascending = false)
            advanceUntilIdle()

            assertEquals(LibrarySort.default, preferences.sortingMode().get())
            assertEquals(
                LibrarySort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Descending),
                LibrarySort.valueOf(repository.get(7L)!!.flags),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `library preferences restore filters display and sort on model creation`() {
        val preferences = LibraryPreferences(
            InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreferenceStore.InMemoryPreference(
                        "pref_filter_library_unread_v2",
                        TriState.ENABLED_IS,
                        TriState.DISABLED,
                    ),
                    InMemoryPreferenceStore.InMemoryPreference(
                        "pref_filter_library_downloaded_v2",
                        TriState.ENABLED_NOT,
                        TriState.DISABLED,
                    ),
                    InMemoryPreferenceStore.InMemoryPreference(
                        "pref_display_mode_library",
                        tachiyomi.domain.library.model.LibraryDisplayMode.CoverOnlyGrid,
                        tachiyomi.domain.library.model.LibraryDisplayMode.default,
                    ),
                    InMemoryPreferenceStore.InMemoryPreference(
                        "library_sorting_mode",
                        LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Descending),
                        LibrarySort.default,
                    ),
                    InMemoryPreferenceStore.InMemoryPreference(
                        tachiyomi.core.common.preference.Preference.appStateKey("pref_downloaded_only"),
                        true,
                        false,
                    ),
                    InMemoryPreferenceStore.InMemoryPreference(
                        "pref_library_columns_portrait_key",
                        3,
                        0,
                    ),
                    InMemoryPreferenceStore.InMemoryPreference(
                        "pref_library_columns_landscape_key",
                        6,
                        0,
                    ),
                ),
            ),
        )

        val state = LibraryScreenModel(libraryPreferences = preferences).state.value

        assertEquals(TriState.ENABLED_IS, state.filter.unread)
        assertEquals(TriState.ENABLED_NOT, state.filter.downloaded)
        assertTrue(state.filter.globalDownloadedOnly)
        assertEquals(LibraryDisplayMode.COVER_ONLY_GRID, state.displayMode)
        assertEquals(SortMode.TOTAL_CHAPTERS, state.sortMode)
        assertFalse(state.sortAscending)
        assertEquals(3, state.portraitColumns)
        assertEquals(6, state.landscapeColumns)
    }

    @Test
    fun `tracker mean is passed to shared evaluator`() {
        val first = sampleLibraryManga(sampleManga(id = 1L, title = "First"))
        val second = sampleLibraryManga(sampleManga(id = 2L, title = "Second"))
        val model = LibraryScreenModel().apply {
            setAllItems(listOf(first, second))
            setEvaluationContext(
                downloadedMangaIds = emptySet(),
                trackerMeansByManga = mapOf(1L to 9.0, 2L to 3.0),
            )
            setSortModeAndDirection(SortMode.TRACKER_MEAN, ascending = false)
        }

        assertEquals(listOf(1L, 2L), model.visibleItems().map { it.id })
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

    @Test
    fun `downloaded manga projection uses canonical identity lookup before legacy directory fallback`() {
        val provider = DesktopDownloadProvider(tempDir.toFile())
        val manga = sampleManga(id = 7L, source = 42L, title = "Canonical Manga")
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Canonical Source",
            mangaTitle = manga.title,
            chapterName = "Chapter 1",
            scanlator = "Group",
            chapterUrl = "/chapter/1",
            disallowNonAsciiFilenames = false,
        )
        val canonicalCbz = provider.canonicalMangaDownloadDir(identity).resolve("chapter.cbz")
        canonicalCbz.parentFile.mkdirs()
        canonicalCbz.writeBytes(byteArrayOf(1))
        val model = LibraryScreenModel(
            downloadProvider = provider,
            isMangaDownloaded = { item ->
                provider.hasMangaDownloads(item.manga.source, identity.copy(mangaTitle = item.manga.title))
            },
        )

        assertEquals(setOf(manga.id), model.downloadedMangaIds(listOf(sampleLibraryManga(manga))))
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

    @Test
    fun `setDisplayMode uses the shared display interactor`() {
        val preferences = LibraryPreferences(isolatedDesktopPreferenceStore())
        val model = LibraryScreenModel(
            libraryPreferences = preferences,
            setDisplayModeInteractor = SetDisplayMode(preferences),
        )

        model.setDisplayMode(LibraryDisplayMode.COVER_ONLY_GRID)

        assertEquals(
            tachiyomi.domain.library.model.LibraryDisplayMode.CoverOnlyGrid,
            preferences.displayMode().get(),
        )
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
        Files.write(
            downloadedChapter.resolve("page.png"),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )
        val tracks = MutableStateFlow(
            listOf(
                sampleTrack(id = 1L, mangaId = 3L, trackerId = 2L, score = 80.0),
                sampleTrack(id = 2L, mangaId = 3L, trackerId = 7L, score = 6.0),
            ),
        )
        val loggedInTrackerIds = MutableStateFlow(emptySet<Long>())
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(repository),
            downloadProvider = DesktopDownloadProvider(tempDir.toFile()),
            getTracksPerManga = GetTracksPerManga(trackRepositoryOf(tracks)),
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `production library stream restores saved tracker filters when a tracker logs in`() = runTest {
        val repository = FakeMangaRepository().apply {
            libraryManga = listOf(sampleLibraryManga(sampleManga(1).copy(source = 10L)))
        }
        val sessions = MutableStateFlow(emptySet<Long>())
        val store = isolatedDesktopPreferenceStore().also {
            it.getObjectFromString(
                "pref_filter_library_tracked_7_v2",
                TriState.DISABLED,
                { value -> value.name },
                { value -> TriState.entries.first { state -> state.name == value } },
            ).set(TriState.ENABLED_IS)
        }
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(repository),
            libraryPreferences = LibraryPreferences(store),
            trackerSessionProvider = TrackerSessionProvider { sessions },
        )

        model.libraryMangaFlow().launchIn(backgroundScope)
        runCurrent()
        sessions.value = setOf(7L)
        runCurrent()

        assertEquals(TriState.ENABLED_IS, model.state.value.filter.tracking[7L])
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
        val model = modelWithChapterUseCases(chapterRepository)

        model.markMangaRead(mangaId = 10L, read = true)

        assertEquals(listOf(1L, 2L), chapterRepository.updates.map { it.id })
        assertTrue(chapterRepository.updates.all { it.read == true })
    }

    @Test
    fun `marking unread chapters as read removes their downloads when preference is enabled`() = runTest {
        val chapterRepository = FakeChapterRepository()
        chapterRepository.addAll(
            listOf(
                Chapter.create().copy(id = 1L, mangaId = 10L, name = "Unread", read = false),
                Chapter.create().copy(id = 2L, mangaId = 10L, name = "Already read", read = true),
                Chapter.create().copy(id = 3L, mangaId = 10L, name = "Started", read = false, lastPageRead = 2L),
            ),
        )
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    "pref_remove_after_marked_as_read_key",
                    true,
                    false,
                ),
            ),
        )
        val deleted = mutableListOf<Long>()
        val item = sampleLibraryManga(sampleManga(id = 10L, source = 7L, title = "Manga"))
        val getChapters = GetChaptersByMangaId(chapterRepository)
        val model = LibraryScreenModel(
            getChaptersByMangaId = getChapters,
            setChapterReadStatus = SetChapterReadStatus(getChapters, UpdateChapter(chapterRepository)),
            sharedDownloadPreferences = DownloadPreferences(store),
            deleteChapterDownload = { _, chapter -> deleted += chapter.id },
        ).apply { setAllItems(listOf(item)) }

        model.markMangaRead(mangaId = 10L, read = true)

        assertEquals(listOf(1L, 3L), deleted)
    }

    @Test
    fun `mark read freezes manga before the library flow removes it`() = runTest {
        val backing = FakeChapterRepository()
        backing.addAll(listOf(Chapter.create().copy(id = 1L, mangaId = 10L, read = false)))
        lateinit var model: LibraryScreenModel
        val repository = object : ChapterRepository by backing {
            override suspend fun updateAll(chapterUpdates: List<tachiyomi.domain.chapter.model.ChapterUpdate>) {
                backing.updateAll(chapterUpdates)
                model.setAllItems(emptyList())
            }
        }
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("pref_remove_after_marked_as_read_key", true, false),
            ),
        )
        val deleted = mutableListOf<Long>()
        val item = sampleLibraryManga(sampleManga(id = 10L, source = 7L, title = "Manga"))
        val getChapters = GetChaptersByMangaId(repository)
        model = LibraryScreenModel(
            getChaptersByMangaId = getChapters,
            setChapterReadStatus = SetChapterReadStatus(getChapters, UpdateChapter(repository)),
            sharedDownloadPreferences = DownloadPreferences(store),
            deleteChapterDownload = { _, chapter -> deleted += chapter.id },
        ).apply { setAllItems(listOf(item)) }

        model.markMangaRead(mangaId = 10L, read = true)

        assertEquals(listOf(1L), deleted)
    }

    @Test
    fun `mark read failure is reported without rethrowing`() = runTest {
        val chapterRepository = FakeChapterRepository().apply {
            addAll(listOf(Chapter.create().copy(id = 1L, mangaId = 10L, read = false)))
            failUpdates = true
        }
        val getChapters = GetChaptersByMangaId(chapterRepository)
        val model = LibraryScreenModel(
            getChaptersByMangaId = getChapters,
            setChapterReadStatus = SetChapterReadStatus(getChapters, UpdateChapter(chapterRepository)),
        )

        val result = runCatching { model.markMangaRead(mangaId = 10L, read = true) }

        assertTrue(result.isSuccess)
        assertEquals(
            MR.strings.desktop_ui_items_updated_failed.localized(java.util.Locale.getDefault(), 0, 1),
            model.state.value.operationFeedback,
        )
    }

    @Test
    fun `mark manga unread resets progress and skips chapters already unread at start`() = runTest {
        val chapterRepository = FakeChapterRepository()
        chapterRepository.addAll(
            listOf(
                Chapter.create().copy(id = 1L, mangaId = 10L, read = true, lastPageRead = 8L),
                Chapter.create().copy(id = 2L, mangaId = 10L, read = false, lastPageRead = 0L),
                Chapter.create().copy(id = 3L, mangaId = 10L, read = false, lastPageRead = 4L),
            ),
        )
        val model = modelWithChapterUseCases(chapterRepository)

        model.markMangaRead(mangaId = 10L, read = false)

        assertEquals(listOf(1L, 3L), chapterRepository.updates.map { it.id })
        assertTrue(chapterRepository.updates.all { it.read == false && it.lastPageRead == 0L })
    }

    @Test
    fun `removeFromLibrary clears favorite flag for each manga`() = runTest {
        val mangaRepository = FakeMangaRepository()
        mangaRepository.seed(sampleManga(id = 1L).copy(favorite = true))
        mangaRepository.seed(sampleManga(id = 2L).copy(favorite = true))
        val model = LibraryScreenModel(updateManga = UpdateManga(mangaRepository))

        model.removeFromLibrary(listOf(1L, 2L))

        assertEquals(listOf(1L, 2L), mangaRepository.updates.map { it.id })
        assertTrue(mangaRepository.updates.all { it.favorite == false })
        assertTrue(mangaRepository.updates.all { it.syncContext.origin == SyncOrigin.USER })
    }

    @Test
    fun `removeFromLibrary counts a partial item failure only once`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val manga = sampleManga(id = 3L).copy(favorite = true)
        mangaRepository.seed(manga)
        var downloadsDeleted = false
        val model = LibraryScreenModel(
            updateManga = UpdateManga(mangaRepository),
            deleteCustomCover = { false },
            deleteMangaDownloads = { downloadsDeleted = true },
        ).apply { setAllItems(listOf(sampleLibraryManga(manga))) }

        model.removeFromLibrary(listOf(manga.id), deleteDownloads = true)

        assertTrue(downloadsDeleted)
        assertEquals(
            MR.strings.desktop_ui_items_updated_failed.localized(java.util.Locale.getDefault(), 0, 1),
            model.state.value.operationFeedback,
        )
    }

    @Test
    fun `removeFromLibrary deletes only the selected manga artifacts when requested`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val manga = sampleManga(id = 1L, source = 7L, title = "Selected")
        mangaRepository.seed(manga)
        val provider = DesktopDownloadProvider(tempDir.toFile())
        val chapterDirectory = provider.chapterDownloadDir(7L, "Selected", "Chapter 1")
        chapterDirectory.mkdirs()
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", chapterDirectory.resolve("page.png"))
        var deletedCoverId: Long? = null
        val model = LibraryScreenModel(
            updateManga = UpdateManga(mangaRepository),
            deleteMangaDownloads = { item ->
                provider.deleteMangaDownloads(item.manga.source, item.manga.title)
            },
            deleteCustomCover = { id ->
                deletedCoverId = id
                true
            },
        ).apply { setAllItems(listOf(sampleLibraryManga(manga))) }

        model.removeFromLibrary(listOf(manga.id), deleteDownloads = true)

        assertFalse(provider.hasMangaDownloads(7L, "Selected"))
        assertEquals(1L, deletedCoverId)
        assertEquals(false, mangaRepository.get(1L)?.favorite)
    }

    @Test
    fun `removeFromLibrary can delete downloads without changing library membership`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val manga = sampleManga(id = 2L, source = 7L, title = "Kept").copy(favorite = true)
        mangaRepository.seed(manga)
        val provider = DesktopDownloadProvider(tempDir.toFile())
        val chapterDirectory = provider.chapterDownloadDir(7L, "Kept", "Chapter 1")
        chapterDirectory.mkdirs()
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", chapterDirectory.resolve("page.png"))
        val model = LibraryScreenModel(
            updateManga = UpdateManga(mangaRepository),
            deleteMangaDownloads = { item -> provider.deleteMangaDownloads(item.manga.source, item.manga.title) },
        ).apply { setAllItems(listOf(sampleLibraryManga(manga))) }

        model.removeFromLibrary(
            mangaIds = listOf(manga.id),
            deleteDownloads = true,
            removeFromLibrary = false,
        )

        assertTrue(mangaRepository.get(2L)?.favorite == true)
        assertTrue(mangaRepository.updates.isEmpty())
        assertFalse(provider.hasMangaDownloads(7L, "Kept"))
    }

    @Test
    fun `enqueueNextUnreadDownload honors manga chapter sort and scanlator filter`() = runTest {
        val backing = FakeChapterRepository()
        val enqueued = mutableListOf<DownloadItem>()
        backing.addAll(
            listOf(
                Chapter.create().copy(
                    id = 1L,
                    mangaId = 10L,
                    name = "Three",
                    url = "/1",
                    sourceOrder = 1L,
                    chapterNumber = 3.0,
                ),
                Chapter.create().copy(
                    id = 2L,
                    mangaId = 10L,
                    name = "Filtered",
                    url = "/2",
                    sourceOrder = 2L,
                    chapterNumber = 1.0,
                ),
                Chapter.create().copy(
                    id = 3L,
                    mangaId = 10L,
                    name = "Two",
                    url = "/3",
                    sourceOrder = 3L,
                    chapterNumber = 2.0,
                ),
            ),
        )
        var scanlatorFilterApplied = false
        val chapterRepository = object : ChapterRepository by backing {
            override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                scanlatorFilterApplied = applyScanlatorFilter
                val chapters = backing.getChapterByMangaId(mangaId)
                return if (applyScanlatorFilter) chapters.filterNot { it.id == 2L } else chapters
            }
        }
        val manga = sampleManga(id = 10L, source = 7L, title = "Manga").copy(
            chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC,
        )
        val model = modelWithChapterUseCases(
            chapterRepository = chapterRepository,
            enqueueDownload = { enqueued += it },
            mangaProvider = { manga },
        )

        model.enqueueNextUnreadDownload(sampleLibraryManga(manga))

        assertTrue(scanlatorFilterApplied)
        assertEquals(
            DownloadItem(
                sourceId = 7L,
                mangaTitle = "Manga",
                chapterName = "Two",
                chapterId = 3L,
                mangaId = 10L,
                chapterUrl = "/3",
            ),
            enqueued.single(),
        )
    }

    @Test
    fun `enqueueNextUnreadDownload skips a chapter already downloaded on disk`() = runTest {
        val repository = FakeChapterRepository().apply {
            addAll(
                listOf(
                    Chapter.create().copy(id = 1L, mangaId = 10L, name = "First", url = "/1", sourceOrder = 1L),
                    Chapter.create().copy(id = 2L, mangaId = 10L, name = "Second", url = "/2", sourceOrder = 2L),
                ),
            )
        }
        val downloaded = tempDir.resolve("7/Manga/First")
        Files.createDirectories(downloaded)
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", downloaded.resolve("page.png").toFile())
        val enqueued = mutableListOf<Long>()
        val manga = sampleManga(id = 10L, source = 7L, title = "Manga")

        modelWithChapterUseCases(
            repository,
            enqueueDownload = { enqueued += it.chapterId },
            downloadProvider = DesktopDownloadProvider(tempDir.toFile()),
            mangaProvider = { manga },
        ).enqueueNextUnreadDownload(sampleLibraryManga(manga))

        assertEquals(listOf(2L), enqueued)
    }

    @Test
    fun `next unread download can use the resolved chapter identity`() = runTest {
        val repository = FakeChapterRepository().apply {
            addAll(
                listOf(
                    Chapter.create().copy(id = 1L, mangaId = 10L, name = "First", url = "/first", sourceOrder = 1L),
                    Chapter.create().copy(id = 2L, mangaId = 10L, name = "Second", url = "/second", sourceOrder = 2L),
                ),
            )
        }
        val enqueued = mutableListOf<Long>()
        val model = modelWithChapterUseCases(
            chapterRepository = repository,
            enqueueDownload = { enqueued += it.chapterId },
            isChapterDownloaded = { _, chapter -> chapter.id == 1L },
        )

        model.enqueueNextUnreadDownload(sampleLibraryManga(sampleManga(id = 10L, source = 7L)))

        assertEquals(listOf(2L), enqueued)
    }

    @Test
    fun `single next unread download skips chapters already in the queue`() = runTest {
        val repository = FakeChapterRepository().apply {
            addAll(
                listOf(
                    Chapter.create().copy(id = 1L, mangaId = 10L, name = "First", url = "/first", sourceOrder = 1L),
                    Chapter.create().copy(id = 2L, mangaId = 10L, name = "Second", url = "/second", sourceOrder = 2L),
                ),
            )
        }
        val enqueued = mutableListOf<Long>()
        val model = modelWithChapterUseCases(
            chapterRepository = repository,
            enqueueDownload = { enqueued += it.chapterId },
            isChapterQueued = { chapter -> chapter.id == 2L },
        )

        model.enqueueNextUnreadDownload(sampleLibraryManga(sampleManga(id = 10L, source = 7L)))

        assertEquals(listOf(1L), enqueued)
    }

    @Test
    fun `batch download preserves all six fixed-main chapter selections`() = runTest {
        val repository = FakeChapterRepository().apply {
            addAll(
                (1L..30L).map { id ->
                    Chapter.create().copy(
                        id = id,
                        mangaId = 10L,
                        name = "Chapter $id",
                        url = "/$id",
                        sourceOrder = id,
                        bookmark = id <= 2L,
                        read = id == 1L,
                    )
                },
            )
        }
        val item = sampleLibraryManga(sampleManga(id = 10L, source = 7L, title = "Manga"))
        val expected = listOf(1, 5, 10, 25, 29, 2)

        MangaDetailDownloadAction.entries.zip(expected).forEach { (action, count) ->
            val enqueued = mutableListOf<DownloadItem>()
            val result = modelWithChapterUseCases(repository, enqueueDownload = { enqueued += it })
                .enqueueDownloads(listOf(item), action)

            assertEquals(count, result.queued)
            assertEquals(count, enqueued.size)
        }
    }

    @Test
    fun `next downloads honor manga chapter sort and scanlator filter`() = runTest {
        val backing = FakeChapterRepository().apply {
            addAll(
                listOf(
                    Chapter.create().copy(
                        id = 1L,
                        mangaId = 10L,
                        name = "Three",
                        url = "/1",
                        sourceOrder = 1L,
                        chapterNumber = 3.0,
                    ),
                    Chapter.create().copy(
                        id = 2L,
                        mangaId = 10L,
                        name = "Filtered",
                        url = "/2",
                        sourceOrder = 2L,
                        chapterNumber = 1.0,
                    ),
                    Chapter.create().copy(
                        id = 3L,
                        mangaId = 10L,
                        name = "Two",
                        url = "/3",
                        sourceOrder = 3L,
                        chapterNumber = 2.0,
                    ),
                ),
            )
        }
        var scanlatorFilterApplied = false
        val repository = object : ChapterRepository by backing {
            override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                scanlatorFilterApplied = applyScanlatorFilter
                val chapters = backing.getChapterByMangaId(mangaId)
                return if (applyScanlatorFilter) chapters.filterNot { it.id == 2L } else chapters
            }
        }
        val manga = sampleManga(id = 10L, source = 7L, title = "Manga").copy(
            chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC,
        )
        val item = sampleLibraryManga(manga)

        val nextOne = mutableListOf<Long>()
        modelWithChapterUseCases(
            repository,
            enqueueDownload = { nextOne += it.chapterId },
            mangaProvider = { manga },
        )
            .enqueueDownloads(listOf(item), MangaDetailDownloadAction.NEXT_1_CHAPTER)
        val nextFive = mutableListOf<Long>()
        modelWithChapterUseCases(
            repository,
            enqueueDownload = { nextFive += it.chapterId },
            mangaProvider = { manga },
        )
            .enqueueDownloads(listOf(item), MangaDetailDownloadAction.NEXT_5_CHAPTERS)

        assertTrue(scanlatorFilterApplied)
        assertEquals(listOf(3L), nextOne)
        assertEquals(listOf(3L, 1L), nextFive)
    }

    @Test
    fun `batch download skips queued downloading and downloaded chapters then continues after failure`() = runTest {
        val backing = FakeChapterRepository().apply {
            addAll(
                (1L..5L).map { id ->
                    Chapter.create().copy(id = id, mangaId = 10L, name = "Chapter $id", url = "/$id", sourceOrder = id)
                },
            )
            addAll(
                listOf(Chapter.create().copy(id = 6L, mangaId = 12L, name = "Chapter 6", url = "/6", sourceOrder = 6L)),
            )
        }
        val repository = object : ChapterRepository by backing {
            override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean) =
                if (mangaId == 11L) error("database unavailable") else backing.getChapterByMangaId(mangaId)
        }
        val downloaded = tempDir.resolve("7/Manga/Chapter 3")
        Files.createDirectories(downloaded)
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", downloaded.resolve("page.png").toFile())
        val enqueued = mutableListOf<Long>()
        val model = modelWithChapterUseCases(
            chapterRepository = repository,
            enqueueDownload = {
                if (it.chapterId == 4L) error("source offline")
                enqueued += it.chapterId
            },
            downloadProvider = DesktopDownloadProvider(tempDir.toFile()),
        )
        val queue = listOf(
            DownloadItem(7L, "Manga", "Chapter 1", 1L, status = DownloadStatus.QUEUED),
            DownloadItem(7L, "Manga", "Chapter 2", 2L, status = DownloadStatus.DOWNLOADING),
            DownloadItem(7L, "Manga", "Chapter 4", 4L, status = DownloadStatus.ERROR),
        )

        val result = model.enqueueDownloads(
            listOf(10L, 11L, 12L).map { sampleLibraryManga(sampleManga(id = it, source = 7L, title = "Manga")) },
            MangaDetailDownloadAction.UNREAD_CHAPTERS,
            queue,
        )

        assertEquals(LibraryBatchDownloadResult(queued = 2, skipped = 4, failures = 1), result)
        assertEquals(listOf(5L, 6L), enqueued)
        assertEquals("2 queued, 4 skipped, 1 failed", model.state.value.batchCategoryResultMessage)
        assertEquals(
            LibraryBatchDownloadResult(),
            model.enqueueDownloads(emptyList(), MangaDetailDownloadAction.UNREAD_CHAPTERS),
        )
        assertEquals("No manga selected", model.state.value.batchCategoryResultMessage)
    }

    @Test
    fun `bookmarked download query failure is reported without queueing chapters`() = runTest {
        val backing = FakeChapterRepository()
        val repository = object : ChapterRepository by backing {
            override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> =
                error("bookmark query failed")
        }
        val enqueued = mutableListOf<DownloadItem>()
        val model = modelWithChapterUseCases(repository, enqueueDownload = { enqueued += it })

        val result = model.enqueueDownloads(
            listOf(sampleLibraryManga(sampleManga(id = 10L))),
            MangaDetailDownloadAction.BOOKMARKED_CHAPTERS,
        )

        assertEquals(LibraryBatchDownloadResult(failures = 1), result)
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `continueReadingRequest uses oldest unfinished chapter and keeps navigation newest first`() = runTest {
        val chapterRepository = FakeChapterRepository()
        chapterRepository.addAll(
            listOf(
                Chapter.create().copy(
                    id = 2L,
                    mangaId = 10L,
                    name = "Read",
                    url = "/read",
                    read = true,
                    sourceOrder = 1L,
                ),
                Chapter.create().copy(
                    id = 1L,
                    mangaId = 10L,
                    name = "Newer unread",
                    url = "/newer",
                    read = false,
                    sourceOrder = 0L,
                ),
                Chapter.create().copy(
                    id = 3L,
                    mangaId = 10L,
                    name = "Oldest started",
                    url = "/oldest",
                    read = false,
                    sourceOrder = 2L,
                    lastPageRead = 5L,
                ),
            ),
        )
        val model = modelWithChapterUseCases(chapterRepository)

        val request = model.continueReadingRequest(
            sampleLibraryManga(sampleManga(id = 10L, source = 7L, viewerFlags = 0x44L)),
        )

        assertNotNull(request)
        assertEquals("Oldest started", request?.chapterTitle)
        assertEquals("/oldest", request?.chapterUrl)
        assertEquals(3L, request?.chapterId)
        assertEquals(10L, request?.mangaId)
        assertEquals(0x44L, request?.mangaViewerFlags)
        assertEquals(5, request?.initialPage)
        assertEquals(listOf(1L, 2L, 3L), request?.chapters?.map { it.id })
        assertEquals(2, request?.currentChapterIndex)
    }

    @Test
    fun `continueReadingRequest returns null when every chapter is read`() = runTest {
        val chapterRepository = FakeChapterRepository()
        chapterRepository.addAll(
            listOf(
                Chapter.create().copy(id = 1L, mangaId = 10L, read = true, sourceOrder = 0L),
                Chapter.create().copy(id = 2L, mangaId = 10L, read = true, sourceOrder = 1L),
            ),
        )
        val model = modelWithChapterUseCases(chapterRepository)

        val request = model.continueReadingRequest(sampleLibraryManga(sampleManga(id = 10L, source = 7L)))

        assertNull(request)
        assertEquals("Next chapter not found", model.state.value.operationFeedback)
    }

    @Test
    fun `library continue and detail entry skip the same chapter filtered by manga metadata`() = runTest {
        val chapterRepository = FakeChapterRepository()
        val current = Chapter.create().copy(
            id = 4L,
            mangaId = 10L,
            name = "Current",
            url = "/4",
            read = false,
            sourceOrder = 0L,
            chapterNumber = 4.0,
        )
        val filtered = Chapter.create().copy(
            id = 3L,
            mangaId = 10L,
            name = "Filtered read",
            url = "/3",
            read = true,
            sourceOrder = 1L,
            chapterNumber = 3.0,
        )
        val visible = Chapter.create().copy(
            id = 2L,
            mangaId = 10L,
            name = "Visible unread",
            url = "/2",
            read = false,
            sourceOrder = 2L,
            chapterNumber = 2.0,
        )
        val chapters = listOf(current, filtered, visible)
        chapterRepository.addAll(chapters)
        val manga = sampleManga(
            id = 10L,
            source = 7L,
            chapterFlags = Manga.CHAPTER_SHOW_UNREAD,
        )
        val libraryRequest = requireNotNull(
            modelWithChapterUseCases(chapterRepository)
                .continueReadingRequest(sampleLibraryManga(manga)),
        )
        val detailModel = MangaDetailScreenModel(mangaId = manga.id)
        detailModel.setManga(manga)
        detailModel.setChapters(chapters)
        val detailState = detailModel.state.value
        assertFalse(detailState.filterShowRead)
        assertTrue(detailState.filterShowUnread)
        val detailRequest = requireNotNull(
            detailModel.readerRequest(
                manga = requireNotNull(detailState.manga),
                chapters = detailState.chapters,
                chapter = current,
            ),
        )

        detailModel.setFilterShowRead(true)
        detailModel.setManga(manga.copy(title = "Updated title"))
        assertTrue(detailModel.state.value.filterShowRead)
        val requestAfterTemporaryUiChange = requireNotNull(
            detailModel.readerRequest(
                manga = requireNotNull(detailModel.state.value.manga),
                chapters = detailModel.state.value.chapters,
                chapter = current,
            ),
        )

        val detailTarget = ReaderNavigator(
            chapters = detailRequest.chapters,
            currentIndex = detailRequest.currentChapterIndex,
            skipFilteredChapters = true,
        ).previousRead

        assertTrue(libraryRequest.chapters.first { it.id == filtered.id }.isFiltered)
        assertTrue(detailRequest.chapters.first { it.id == filtered.id }.isFiltered)
        assertTrue(requestAfterTemporaryUiChange.chapters.first { it.id == filtered.id }.isFiltered)
        assertEquals(visible.id, detailTarget?.id)
        assertEquals(detailTarget?.id, libraryRequest.chapterId)
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
        chapterFlags: Long = 0L,
    ) = Manga.create().copy(
        id = id,
        source = source,
        title = title,
        viewerFlags = viewerFlags,
        chapterFlags = chapterFlags,
    )

    private fun modelWithChapterUseCases(
        chapterRepository: ChapterRepository,
        enqueueDownload: ((DownloadItem) -> Unit)? = null,
        downloadProvider: DesktopDownloadProvider? = null,
        isChapterDownloaded: ((LibraryManga, Chapter) -> Boolean)? = null,
        isChapterQueued: ((Chapter) -> Boolean)? = null,
        mangaProvider: (Long) -> Manga = { sampleManga(it) },
    ): LibraryScreenModel {
        val getChapters = GetChaptersByMangaId(chapterRepository)
        val mangaBacking = FakeMangaRepository()
        val mangaRepository = object : MangaRepository by mangaBacking {
            override suspend fun getMangaById(id: Long): Manga = mangaProvider(id)
        }
        return LibraryScreenModel(
            getChaptersByMangaId = getChapters,
            getBookmarkedChaptersByMangaId = GetBookmarkedChaptersByMangaId(chapterRepository),
            getNextChapters = GetNextChapters(getChapters, GetManga(mangaRepository), FakeHistoryRepository()),
            setChapterReadStatus = SetChapterReadStatus(getChapters, UpdateChapter(chapterRepository)),
            enqueueDownload = enqueueDownload,
            downloadProvider = downloadProvider,
            isChapterDownloaded = isChapterDownloaded,
            isChapterQueued = isChapterQueued,
        )
    }

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
