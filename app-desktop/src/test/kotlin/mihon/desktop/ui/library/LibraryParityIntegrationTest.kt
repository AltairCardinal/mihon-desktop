package mihon.desktop.ui.library

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.migration.BatchMigrationRequest
import mihon.desktop.ui.migration.MigrationBatchQueueScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class LibraryParityIntegrationTest {
    @Test
    fun `invert selection toggles only visible manga`() {
        val selection = LibrarySelectionState().apply {
            selectAll(listOf(1L, 3L, 99L))
        }

        selection.invertVisible(listOf(1L, 2L, 3L))

        assertEquals(setOf(2L, 99L), selection.selectedIds)
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `selection action bar exposes invert download and migrate entries`() = runBlocking {
        var inverted = false
        var downloaded: MangaDetailDownloadAction? = null
        var submitted = emptyList<BatchMigrationRequest>()
        var destination: Screen? = null
        val selected = listOf(
            libraryManga(Manga.create().copy(id = 1L, source = 7L, title = "Remote")),
            libraryManga(Manga.create().copy(id = 2L, source = 0L, title = "Local")),
        )
        val actions = librarySelectionActions(
            selected = { selected },
            queue = { emptyList() },
            launch = { task -> launch { task() } },
            enqueue = { _, action, _ -> downloaded = action },
            submit = { submitted = it; "queue-7" },
            navigate = { destination = it },
            clear = {},
        )
        val scene = ImageComposeScene(1_400, 240, coroutineContext = coroutineContext) {}
        scene.setContent {
            SelectionActionBar(
                selectedCount = 2,
                onClose = {},
                onSelectAll = {},
                onInvertSelection = { inverted = true },
                actions = actions,
                onSetCategories = {},
                onMarkRead = {},
                onMarkUnread = {},
                onRemoveFromLibrary = {},
            )
        }
        scene.render()

        click(scene, "Invert selection")
        click(scene, "Download")
        scene.render()

        assertTrue(inverted)
        listOf("Next 1 chapter", "Next 5 chapters", "Next 10 chapters", "Next 25 chapters", "All unread chapters", "Bookmarked chapters")
            .forEach { label -> assertTrue(nodes(scene).any { it.config.toString().contains(label) }, label) }
        click(scene, "Next 1 chapter")
        click(scene, "Migrate")
        yield()
        assertEquals(MangaDetailDownloadAction.NEXT_1_CHAPTER, downloaded)
        assertEquals(listOf(1L), submitted.map { it.mangaId })
        assertEquals(MigrationBatchQueueScreen("queue-7"), destination)
        scene.close()
    }

    @Test
    fun `shift selection selects the inclusive visible range from the anchor`() {
        val selection = LibrarySelectionState()
        val visibleIds = listOf(10L, 20L, 30L, 40L, 50L)

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
