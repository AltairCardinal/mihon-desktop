package mihon.desktop.ui.library

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.material3.MaterialTheme
import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.ui.migration.LibraryBatchMigrationConfigScreen
import mihon.desktop.domain.SortMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.util.Locale

class LibraryParityIntegrationTest {
    @Test
    fun `invert selection toggles only visible manga`() {
        val selection = LibrarySelectionState().apply {
            selectAll(listOf(1L, 3L, 99L))
        }

        selection.invertVisible(listOf(1L, 2L, 3L))

        assertEquals(setOf(2L, 99L), selection.selectedIds)
    }

    // The real Root top-bar invert control is protected by
    // `selection controls live only in the top bar while batch actions remain at the bottom`.
    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `selection action bar exposes invert download and migrate entries`() = runBlocking {
        var downloaded: MangaDetailDownloadAction? = null
        var destination: Screen? = null
        val selected = listOf(
            libraryManga(Manga.create().copy(id = 1L, source = 7L, title = "Remote")),
            libraryManga(Manga.create().copy(id = 2L, source = 8L, title = "Remote two")),
        )
        val actions = librarySelectionActions(
            selected = { selected },
            queue = { emptyList() },
            launch = { task -> launch { task() } },
            enqueue = { _, action, _ -> downloaded = action },
            navigate = { destination = it },
            clear = {},
        )
        val scene = ImageComposeScene(1_400, 240, coroutineContext = coroutineContext) {}
        scene.setContent {
            SelectionActionBar(
                actions = actions,
                onSetCategories = {},
                onMarkRead = {},
                onMarkUnread = {},
                onRemoveFromLibrary = {},
            )
        }
        scene.render()

        click(scene, "Download")
        scene.render()

        listOf("Next 1 chapter", "Next 5 chapters", "Next 10 chapters", "Next 25 chapters", "All unread chapters", "Bookmarked chapters")
            .forEach { label -> assertTrue(nodes(scene).any { it.config.toString().contains(label) }, label) }
        click(scene, "Next 1 chapter")
        click(scene, "Migrate")
        yield()
        assertEquals(MangaDetailDownloadAction.NEXT_1_CHAPTER, downloaded)
        val config = destination as? LibraryBatchMigrationConfigScreen
        assertEquals(listOf(1L, 2L), config?.selectedManga?.map { it.mangaId })
        scene.close()
    }

    @Test
    fun `selection download clears selection before starting asynchronous work`() = runBlocking {
        var cleared = false
        var clearedBeforeEnqueue = false
        val selected = listOf(libraryManga(Manga.create().copy(id = 1L, title = "Remote", source = 7L)))
        val actions = librarySelectionActions(
            selected = { selected },
            queue = { emptyList() },
            launch = { task -> runBlocking { task() } },
            enqueue = { _, _, _ -> clearedBeforeEnqueue = cleared },
            navigate = {},
            clear = { cleared = true },
        )

        actions.download(MangaDetailDownloadAction.NEXT_1_CHAPTER)

        assertTrue(clearedBeforeEnqueue)
    }

    @Test
    fun `mark operation captures selection and clears before asynchronous work`() = runBlocking {
        var cleared = false
        var idsSeenByOperation: Set<Long>? = null

        clearSelectionBeforeAsync(
            selectedIds = setOf(1L, 2L),
            clear = { cleared = true },
            launch = { task -> runBlocking { task() } },
            operation = { ids -> idsSeenByOperation = ids },
        )

        assertTrue(cleared)
        assertEquals(setOf(1L, 2L), idsSeenByOperation)
    }

    @Test
    fun `remove operation captures target and clears before asynchronous work`() = runBlocking {
        var cleared = false
        var idsSeenByOperation: List<Long>? = null
        val items = listOf(
            libraryManga(Manga.create().copy(id = 1L, title = "First")),
            libraryManga(Manga.create().copy(id = 2L, title = "Second")),
        )

        clearSelectionBeforeRemoval(
            items = items,
            clear = { cleared = true },
            launch = { task -> runBlocking { task() } },
            operation = { ids -> idsSeenByOperation = ids },
        )

        assertTrue(cleared)
        assertEquals(listOf(1L, 2L), idsSeenByOperation)
    }

    @Test
    fun `shift selection selects the inclusive visible range from the anchor`() {
        val selection = LibrarySelectionState()
        val visibleIds = listOf(10L, 20L, 30L, 40L, 50L, 60L)

        selection.toggle(20L)
        selection.selectRange(visibleIds, 50L)

        assertEquals(setOf(20L, 30L, 40L, 50L), selection.selectedIds)
    }

    @Test
    fun `shift mouse click selects visible range and does not open manga`() {
        val selection = LibrarySelectionState().apply { toggle(20L) }
        var openedMangaId: Long? = null

        selection.handlePrimaryClick(
            visibleIds = listOf(10L, 20L, 30L, 40L),
            targetId = 40L,
            shiftPressed = true,
            onOpen = { openedMangaId = it },
        )

        assertEquals(setOf(20L, 30L, 40L), selection.selectedIds)
        assertEquals(null, openedMangaId)
    }

    @Test
    fun `range selection is scoped to the last category anchor`() {
        val selection = LibrarySelectionState()
        val visibleIds = listOf(10L, 20L, 30L, 40L, 50L)

        selection.toggle(10L, categoryId = 1L)
        selection.handlePrimaryClick(
            visibleIds = visibleIds,
            targetId = 60L,
            shiftPressed = true,
            categoryId = 2L,
            onOpen = {},
        )
        assertEquals(setOf(10L, 60L), selection.selectedIds)

        selection.selectAll(listOf(20L), categoryId = 2L)
        selection.handlePrimaryClick(
            visibleIds = visibleIds,
            targetId = 40L,
            shiftPressed = true,
            categoryId = 2L,
            onOpen = {},
        )
        assertEquals(setOf(10L, 20L, 40L, 60L), selection.selectedIds)

        selection.handlePrimaryClick(
            visibleIds = visibleIds,
            targetId = 30L,
            shiftPressed = true,
            categoryId = 2L,
            onOpen = {},
        )
        assertEquals(setOf(10L, 20L, 30L, 40L, 60L), selection.selectedIds)
    }

    @Test
    fun `batch category assignment reports partial failure and continues`() = runTest {
        val repository = FakeMangaRepository().apply { failCategoryAssignmentFor = 2L }
        val result = SetMangaCategories(repository).awaitBatch(
            mangaIds = listOf(1L, 2L, 3L),
            categoryIds = listOf(7L),
        )

        assertEquals(setOf(1L, 3L), result.succeededIds.toSet())
        assertEquals(listOf(2L), result.failures.map { it.id })
        assertEquals(listOf(7L), repository.getMangaCategoryIds(3L))
    }


    @Test
    fun `library model exposes batch category partial failure to UI`() = runTest {
        val repository = FakeMangaRepository().apply { failCategoryAssignmentFor = 2L }
        val model = LibraryScreenModel(setMangaCategories = SetMangaCategories(repository))

        model.setCategoriesForManga(listOf(1L, 2L, 3L), listOf(7L))

        assertEquals("2 updated, 1 failed", model.state.value.batchCategoryResultMessage)
    }

    @Test
    fun `library model applies category delta without overwriting each manga`() = runTest {
        val repository = FakeMangaRepository()
        repository.setMangaCategories(1L, listOf(1L, 4L))
        repository.setMangaCategories(2L, listOf(2L, 4L))
        val current = mapOf(1L to setOf(1L, 4L), 2L to setOf(2L, 4L))
        val model = LibraryScreenModel(
            setMangaCategories = SetMangaCategories(repository),
            getCategoryIdsForManga = { current[it].orEmpty() },
        )

        model.updateCategoriesForManga(
            mangaIds = listOf(1L, 2L),
            addCategoryIds = setOf(3L),
            removeCategoryIds = setOf(4L),
        )

        assertEquals(listOf(1L, 3L), repository.getMangaCategoryIds(1L).sorted())
        assertEquals(listOf(2L, 3L), repository.getMangaCategoryIds(2L).sorted())
    }

    @Test
    fun `library model reports category read failure and continues the frozen target set`() = runTest {
        val repository = FakeMangaRepository()
        val current = mapOf(1L to setOf(1L), 2L to setOf(1L), 3L to setOf(2L))
        val model = LibraryScreenModel(
            setMangaCategories = SetMangaCategories(repository),
            getCategoryIdsForManga = { mangaId ->
                if (mangaId == 2L) error("category read failed")
                current.getValue(mangaId)
            },
        )

        model.updateCategoriesForManga(
            mangaIds = listOf(1L, 2L, 3L),
            addCategoryIds = setOf(3L),
            removeCategoryIds = emptySet(),
        )

        assertEquals(listOf(1L, 3L), repository.getMangaCategoryIds(1L).sorted())
        assertTrue(repository.getMangaCategoryIds(2L).isEmpty())
        assertEquals(listOf(2L, 3L), repository.getMangaCategoryIds(3L).sorted())
        assertEquals("2 updated, 1 failed", model.state.value.batchCategoryResultMessage)
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `refresh menu separates current category and full library actions`() {
        var currentRefreshes = 0
        var fullRefreshes = 0
        val scene = ImageComposeScene(1_400, 240) {}
        scene.setContent {
            LibraryToolbar(
                searchQuery = "",
                onSearchChange = {},
                sortMode = SortMode.TITLE,
                sortAscending = true,
                onSortChange = { _, _ -> },
                filter = LibraryFilter(),
                availableTrackerIds = emptySet(),
                onToggleFilter = {},
                onToggleTracking = {},
                isUpdating = false,
                displayMode = LibraryDisplayMode.COMPACT_GRID,
                onDisplayModeChange = {},
                onManageCategories = {},
                onOpenGlobalSearch = {},
                onOpenSettings = {},
                categories = listOf(Category(1L, "Action", 0L, 0L)),
                onRandomManga = {},
                onRefresh = { currentRefreshes++ },
                onRefreshAll = { fullRefreshes++ },
            )
        }
        scene.render()

        click(scene, MR.strings.check_for_updates.localized())
        scene.render()
        click(scene, MR.strings.action_update_library.localized())
        scene.render()
        click(scene, MR.strings.check_for_updates.localized())
        scene.render()
        click(scene, MR.strings.ext_update_all.localized())

        assertEquals(1, currentRefreshes)
        assertEquals(1, fullRefreshes)
        scene.close()
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `refresh action remains available while an update is running`() {
        var refreshes = 0
        val scene = ImageComposeScene(1_400, 240) {}
        scene.setContent {
            LibraryToolbar(
                searchQuery = "",
                onSearchChange = {},
                sortMode = SortMode.TITLE,
                sortAscending = true,
                onSortChange = { _, _ -> },
                filter = LibraryFilter(),
                availableTrackerIds = emptySet(),
                onToggleFilter = {},
                onToggleTracking = {},
                isUpdating = true,
                displayMode = LibraryDisplayMode.COMPACT_GRID,
                onDisplayModeChange = {},
                onManageCategories = {},
                onOpenGlobalSearch = {},
                onOpenSettings = {},
                categories = listOf(Category(1L, "Action", 0L, 0L)),
                onRandomManga = {},
                onRefresh = { refreshes++ },
                onRefreshAll = {},
            )
        }
        scene.render()

        click(scene, MR.strings.check_for_updates.localized())

        assertEquals(1, refreshes)
        scene.close()
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `list badges remain visible when continue reading is disabled`() = runBlocking {
        val scene = ImageComposeScene(1_200, 800, coroutineContext = coroutineContext) {}
        scene.setContent {
            MaterialTheme {
                LibraryList(
                    items = listOf(
                        libraryManga(Manga.create().copy(id = 1L, title = "Badge sample"))
                            .copy(totalChapters = 3L),
                    ),
                    selectionState = LibrarySelectionState(),
                    downloadedMangaIds = setOf(1L),
                    showDownloadBadge = true,
                    showUnreadBadge = false,
                    showContinueReadingButton = false,
                    onContextMenu = {},
                    onItemClick = { _, _ -> },
                    onItemLongClick = {},
                )
            }
        }
        scene.render()

        assertTrue(nodes(scene).any { it.config.toString().contains(MR.strings.label_downloaded.localized()) })
        assertTrue(
            nodes(scene).none {
                it.config.toString().contains(
                    MR.strings.desktop_ui_unread_count.localized(Locale.getDefault(), 3L),
                )
            },
        )
        scene.close()
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `continue reading entry is hidden when a manga has no unread chapters`() = runBlocking {
        val scene = ImageComposeScene(1_200, 800, coroutineContext = coroutineContext) {}
        scene.setContent {
            MaterialTheme {
                LibraryList(
                    items = listOf(libraryManga(Manga.create().copy(id = 1L, title = "Read sample"))),
                    selectionState = LibrarySelectionState(),
                    showContinueReadingButton = true,
                    onContextMenu = {},
                    onItemClick = { _, _ -> },
                    onItemLongClick = {},
                )
            }
        }
        scene.render()

        assertTrue(
            nodes(scene).none {
                it.config.toString().contains(MR.strings.desktop_ui_continue_reading.localized())
            },
        )
        scene.close()
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(label)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun libraryManga(manga: Manga) = LibraryManga(manga, emptyList(), 0L, 0L, 0L, 0L, 0L, 0L)
}
