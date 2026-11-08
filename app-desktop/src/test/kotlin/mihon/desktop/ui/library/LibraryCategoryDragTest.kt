package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.library.LibraryScreenModelFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.prefs.Preferences

/** Real root screen, primary-pointer gestures, shared reorder use case and SQLite. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@Isolated
class LibraryCategoryDragTest {
    @Test
    fun `long press reorders tabs on release preserves selection and survives reopening`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, repository, model ->
            seed(repository)
            render(scene)
            click(scene, "Second")
            render(scene)
            val selectedId = selectedId(model)
            val start = tab(scene, "First").boundsInWindow.center
            val end = tab(scene, "Third").boundsInWindow.center
            press(scene, start)
            delay(650)
            render(scene)
            move(scene, start, end)
            assertEquals(
                listOf("First", "Second", "Third"),
                names(repository),
                "Drag preview must not write before release",
            )
            assertTrue(tab(scene, "Second").boundsInWindow.left < tab(scene, "Third").boundsInWindow.left)
            assertTrue(
                tab(scene, "First").boundsInWindow.center.x > tab(scene, "Third").boundsInWindow.center.x,
                "The dragged tab must visibly follow the pointer",
            )
            release(scene, end)
            render(scene)
            assertEquals(listOf("Second", "Third", "First"), names(repository))
            assertEquals(selectedId, selectedId(model), "Dragging must not click or change the selected category")
            assertTrue(tab(scene, "Second").boundsInWindow.left < tab(scene, "First").boundsInWindow.left)
        }
        withLibrary(root) { scene, repository, _ ->
            render(scene)
            assertEquals(listOf("Second", "Third", "First"), names(repository))
            assertTrue(tab(scene, "Third").boundsInWindow.left < tab(scene, "First").boundsInWindow.left)
        }
    }

    @Test
    fun `short drag does not save and repository changes cancel a pending drag`(@TempDir root: File) = runBlocking {
        withLibrary(root) { scene, repository, _ ->
            seed(repository)
            render(scene)
            var start = tab(scene, "First").boundsInWindow.center
            var end = tab(scene, "Third").boundsInWindow.center
            press(scene, start)
            move(scene, start, end)
            release(scene, end)
            render(scene)
            assertEquals(listOf("First", "Second", "Third"), names(repository))
            start = tab(scene, "First").boundsInWindow.center
            end = tab(scene, "Third").boundsInWindow.center
            press(scene, start)
            delay(650)
            move(scene, start, end)
            repository.insert(Category(0, "Fourth", 3, 0))
            render(scene)
            release(scene, end)
            render(scene)
            assertEquals(listOf("First", "Second", "Third", "Fourth"), names(repository))
            assertTrue(tab(scene, "First").boundsInWindow.left < tab(scene, "Second").boundsInWindow.left)
        }
    }

    @Test
    fun `failed drop restores authoritative order and can be retried`(@TempDir root: File) = runBlocking {
        var fail = true
        withLibrary(root, adapter = { actual ->
            object : CategoryRepository by actual {
                override suspend fun updatePartial(updates: List<CategoryUpdate>) {
                    if (fail) throw IOException("Injected category order write failure")
                    actual.updatePartial(updates)
                }
            }
        }) { scene, repository, _ ->
            seed(repository)
            render(scene)
            drag(scene, "First", "Third")
            assertEquals(listOf("First", "Second", "Third"), names(repository))
            assertTrue(tab(scene, "First").boundsInWindow.left < tab(scene, "Second").boundsInWindow.left)
            assertTrue(nodes(scene).any { text(it).contains(tachiyomi.i18n.MR.strings.internal_error.localized()) })
            fail = false
            drag(scene, "First", "Third")
            assertEquals(listOf("Second", "Third", "First"), names(repository))
        }
    }

    @Test
    fun `ordinary click still selects and system default stays before reordered custom tabs`(
        @TempDir root: File,
    ) = runBlocking {
        withLibrary(root) { scene, repository, model ->
            seed(repository)
            val mangas = Injekt.get<MangaRepository>()
            val manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/drag-default", title = "Uncategorized")),
            ).single()
            mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(manga.id, true, 1, emptyList())))
            render(scene)
            click(scene, "Third")
            render(scene)
            assertEquals("Third", model.state.value.categories[model.state.value.selectedCategoryIndex].name)
            val default = model.state.value.categories.first()
            assertTrue(default.isSystemCategory)
            val defaultCenter = nodes(scene).single {
                it.config.contains(SemanticsActions.OnClick) &&
                    text(it).contains(tachiyomi.i18n.MR.strings.desktop_library_default_category.localized())
            }.boundsInWindow.center
            press(scene, defaultCenter)
            delay(650)
            move(scene, defaultCenter, tab(scene, "Third").boundsInWindow.center)
            release(scene, tab(scene, "Third").boundsInWindow.center)
            render(scene)
            assertEquals(listOf("First", "Second", "Third"), names(repository))
            drag(scene, "Third", "First")
            assertEquals(listOf("Third", "First", "Second"), names(repository))
            assertEquals(default.id, model.state.value.categories.first().id)
        }
    }

    @Test
    fun `holding a dragged tab at the edge scrolls to offscreen categories`(@TempDir root: File) = runBlocking {
        withLibrary(root, width = 480) { scene, repository, _ ->
            repeat(12) { repository.insert(Category(0, "Category $it", it.toLong(), 0)) }
            render(scene)
            val start = tab(scene, "Category 0").boundsInWindow.center
            val edge = Offset(475f, start.y)
            press(scene, start)
            delay(650)
            move(scene, start, edge)
            repeat(100) {
                scene.render()
                delay(15)
            }
            release(scene, edge)
            render(scene)
            assertTrue(
                names(repository).indexOf("Category 0") > 3,
                "A narrow tab strip must allow dragging beyond the initially visible tabs",
            )
        }
    }

    private suspend fun withLibrary(
        root: File,
        adapter: ((CategoryRepository) -> CategoryRepository)? = null,
        width: Int = 1200,
        block: suspend (ImageComposeScene, CategoryRepository, LibraryScreenModel) -> Unit,
    ) {
        val node = Preferences.userRoot().node("mihon-tests/category-drag-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(root, DesktopPreferenceStore(node), startDownloadWorker = false)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = adapter?.invoke(Injekt.get()) ?: Injekt.get<CategoryRepository>()
        val model = if (adapter == null) {
            LibraryScreenModelFactory.create()
        } else {
            LibraryScreenModel(
                getLibraryManga = Injekt.get(),
                getCategories = GetCategories(repository),
                reorderCategory = ReorderCategory(repository),
            )
        }
        val scene = ImageComposeScene(width, 900, coroutineContext = Dispatchers.Unconfined)
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory({ model }) {
                        MaterialTheme { Navigator(LibraryRootScreen()) { CurrentScreen() } }
                    }
                }
            }
            render(scene)
            block(scene, repository, model)
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private suspend fun seed(repository: CategoryRepository) {
        listOf("First", "Second", "Third").forEachIndexed { index, name ->
            repository.insert(Category(0, name, index.toLong(), 0))
        }
    }

    private suspend fun names(repository: CategoryRepository) = repository.getAll().filterNot(
        Category::isSystemCategory,
    ).map {
        it.name
    }
    private fun selectedId(
        model: LibraryScreenModel,
    ) = model.state.value.let { it.categories[it.selectedCategoryIndex].id }
    private suspend fun render(scene: ImageComposeScene) = repeat(10) {
        scene.render()
        delay(15)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun text(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) {
            it.config[SemanticsProperties.Text].map { value ->
                value.text
            }
        } else {
            emptyList()
        }
    }
    private fun tab(
        scene: ImageComposeScene,
        name: String,
    ) = nodes(scene).single {
        it.config.contains(SemanticsActions.OnClick) &&
            name in text(it)
    }
    private fun press(
        scene: ImageComposeScene,
        point: Offset,
    ) = scene.sendPointerEvent(
        PointerEventType.Press,
        point,
        buttons = PointerButtons(isPrimaryPressed = true),
        button = PointerButton.Primary,
    )
    private fun release(
        scene: ImageComposeScene,
        point: Offset,
    ) = scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
    private fun click(scene: ImageComposeScene, name: String) {
        val point = tab(scene, name).boundsInWindow.center
        press(scene, point)
        release(scene, point)
    }
    private suspend fun move(scene: ImageComposeScene, start: Offset, end: Offset) {
        repeat(12) { step ->
            scene.sendPointerEvent(
                PointerEventType.Move,
                start + (end - start) * ((step + 1) / 12f),
                buttons = PointerButtons(isPrimaryPressed = true),
            )
            scene.render()
            delay(20)
        }
    }
    private suspend fun drag(scene: ImageComposeScene, from: String, to: String) {
        val start = tab(scene, from).boundsInWindow.center
        val end = tab(scene, to).boundsInWindow.center
        press(scene, start)
        delay(650)
        render(scene)
        move(scene, start, end)
        release(scene, end)
        render(scene)
    }
}
