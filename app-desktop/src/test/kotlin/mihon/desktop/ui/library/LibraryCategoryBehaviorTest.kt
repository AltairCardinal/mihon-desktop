package mihon.desktop.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SortMode
import io.mockk.every
import io.mockk.mockk
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.test.http.LibraryMangaTestModeController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Isolated
class LibraryCategoryBehaviorTest {
    @Test
    fun `last used category survives either initial category and favorite emission order`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(preferencesNode),
            startDownloadWorker = false,
        )
        try {
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.lastUsedCategory().set(2)
            val rawCategories = listOf(
                Category(Category.UNCATEGORIZED_ID, "Default", 0L, 0L),
                Category(1L, "A", 1L, 0L),
                Category(2L, "B", 2L, 0L),
            )
            val uncategorized = LibraryManga(
                manga = Manga.create().copy(id = 10L, source = 1L, title = "Uncategorized"),
                categories = listOf(Category.UNCATEGORIZED_ID),
                totalChapters = 0L,
                readCount = 0L,
                bookmarkCount = 0L,
                latestUpload = 0L,
                chapterFetchedAt = 0L,
                lastRead = 0L,
            )

            suspend fun verify(categoriesFirst: Boolean, selectedBeforeLibrary: Int? = null, expectedIndex: Int = 2) {
                val categoryFlow = MutableSharedFlow<List<Category>>()
                val libraryFlow = MutableSharedFlow<List<LibraryManga>>()
                val getCategories = mockk<GetCategories> {
                    every { subscribe() } returns categoryFlow
                }
                val getLibrary = mockk<GetLibraryManga> {
                    every { subscribe() } returns libraryFlow
                }
                val model = LibraryScreenModel(
                    getLibraryManga = getLibrary,
                    getCategories = getCategories,
                    libraryPreferences = preferences,
                )
                val categoriesJob = launch(start = CoroutineStart.UNDISPATCHED) { model.observeCategories() }
                val libraryJob = launch(start = CoroutineStart.UNDISPATCHED) { model.libraryMangaFlow().collect {} }
                yield()
                try {
                    if (categoriesFirst) {
                        categoryFlow.emit(rawCategories)
                        assertTrue(model.state.value.selectedCategoryIndex in model.state.value.categories.indices)
                        selectedBeforeLibrary?.let(model::setSelectedCategoryIndex)
                        libraryFlow.emit(listOf(uncategorized))
                    } else {
                        libraryFlow.emit(listOf(uncategorized))
                        categoryFlow.emit(rawCategories)
                    }
                    withTimeout(5_000) {
                        while (model.state.value.categories.map { it.id } != listOf(0L, 1L, 2L)) yield()
                    }
                    assertEquals(expectedIndex, model.state.value.selectedCategoryIndex)
                } finally {
                    categoriesJob.cancelAndJoin()
                    libraryJob.cancelAndJoin()
                }
            }

            verify(categoriesFirst = true)
            verify(categoriesFirst = false)
            verify(categoriesFirst = true, selectedBeforeLibrary = 0, expectedIndex = 0)
        } finally {
            context.closeAndJoin()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `test mode category sort persists through the production category chain`(@TempDir tempDir: File) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(preferencesNode), startDownloadWorker = false)
        var controller: LibraryMangaTestModeController? = null
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.categorizedDisplaySettings().set(true)
            preferences.sortingMode().set(LibrarySort.default)
            val model = LibraryScreenModelFactory.create()
            model.createCategory("A")
            model.createCategory("B")
            controller = LibraryMangaTestModeController(model)
            val categoryAIndex = model.state.value.categories.indexOfFirst { it.name == "A" }
            assertTrue(controller.execute("select", mapOf("type" to "category", "index" to categoryAIndex.toString())).success)
            assertTrue(controller.execute("sort", mapOf("mode" to "unreadCount", "ascending" to "false")).success)
            assertTrue(controller.execute("search", mapOf("query" to "needle")).success)

            val repository = Injekt.get<CategoryRepository>()
            val categoryA = repository.getAll().single { it.name == "A" }
            val categoryB = repository.getAll().single { it.name == "B" }
            withTimeout(5_000) {
                while (LibrarySort.valueOf(repository.get(categoryA.id)!!.flags).type != LibrarySort.Type.UnreadCount) {
                    delay(10)
                }
            }
            assertEquals(LibrarySort.Type.UnreadCount, LibrarySort.valueOf(repository.get(categoryA.id)!!.flags).type)
            assertEquals(LibrarySort.Type.Alphabetical, LibrarySort.valueOf(categoryB.flags).type)
            assertEquals(LibrarySort.Type.Alphabetical, preferences.sortingMode().get().type)
            assertEquals("needle", controller.snapshot().searchQuery)

            val categoryBIndex = model.state.value.categories.indexOfFirst { it.id == categoryB.id }
            assertTrue(controller.execute("select", mapOf("type" to "category", "index" to categoryBIndex.toString())).success)
            assertEquals(SortMode.TITLE, model.state.value.sortMode)
            val refreshedAIndex = model.state.value.categories.indexOfFirst { it.id == categoryA.id }
            assertTrue(controller.execute("select", mapOf("type" to "category", "index" to refreshedAIndex.toString())).success)
            assertEquals(SortMode.UNREAD_COUNT, model.state.value.sortMode)

            val recreated = LibraryScreenModelFactory.create()
            recreated.refreshCategories()
            recreated.applyCategoryPreferences(categoryA.id)
            assertEquals(SortMode.UNREAD_COUNT, recreated.state.value.sortMode)

            repository.insert(Category(id = 99L, name = "Observed", order = 2L, flags = LibrarySort.default.flag))
            withTimeout(5_000) {
                while (model.state.value.categories.none { it.name == "Observed" }) delay(10)
            }
            preferences.categoryTabs().set(false)
            withTimeout(5_000) {
                while (model.state.value.showCategoryTabs) delay(10)
            }
        } finally {
            controller?.closeAndJoin()
            Dispatchers.resetMain()
            context.closeAndJoin()
            preferencesNode.removeNode()
        }
    }
    @Test
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    fun `root sort click survives category round trip and model recreation without changing global sort`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(preferencesNode),
            startDownloadWorker = false,
        )
        val mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        var scene: ImageComposeScene? = null
        var rootModel: LibraryScreenModel? = null
        var recreatedModel: LibraryScreenModel? = null
        try {
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.categorizedDisplaySettings().set(true)
            preferences.sortingMode().set(LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending))
            val model = LibraryScreenModelFactory.create().also { rootModel = it }
            model.createCategory("A")
            model.createCategory("B")
            val repository = Injekt.get<CategoryRepository>()
            val categoryA = repository.getAll().single { it.name == "A" }
            val categoryB = repository.getAll().single { it.name == "B" }
            val mangaRepository = Injekt.get<MangaRepository>()
            val chapterRepository = Injekt.get<ChapterRepository>()
            val alpha = mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 1L, url = "/alpha", title = "Alpha high unread")),
            ).single()
            val zulu = mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 1L, url = "/zulu", title = "Zulu low unread")),
            ).single()
            mangaRepository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(alpha.id, true, 1L, listOf(categoryA.id, categoryB.id)),
                    LibraryMembershipUpdate(zulu.id, true, 2L, listOf(categoryA.id, categoryB.id)),
                ),
            )
            chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(mangaId = alpha.id, url = "/alpha/1", name = "A1"),
                    Chapter.create().copy(mangaId = alpha.id, url = "/alpha/2", name = "A2"),
                    Chapter.create().copy(mangaId = alpha.id, url = "/alpha/3", name = "A3"),
                    Chapter.create().copy(mangaId = zulu.id, url = "/zulu/1", name = "Z1"),
                ),
            )

            scene = ImageComposeScene(1_400, 900, coroutineContext = coroutineContext) {}
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory(factory = { model }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }

            render(scene)
            val titles = setOf(alpha.title, zulu.title)
            assertEquals(listOf(alpha.title, zulu.title), renderedTitleOrder(scene, titles))
            click(scene, MR.strings.action_sort.localized())
            render(scene)
            click(scene, MR.strings.action_sort_unread_count.localized())
            withTimeout(5_000) {
                while (LibrarySort.valueOf(repository.get(categoryA.id)!!.flags).type != LibrarySort.Type.UnreadCount) {
                    render(scene)
                    delay(10)
                }
            }
            render(scene)
            assertEquals(listOf(zulu.title, alpha.title), renderedTitleOrder(scene, titles))

            click(scene, "B")
            render(scene)
            click(scene, "A")
            render(scene)

            assertEquals(SortMode.UNREAD_COUNT, model.state.value.sortMode)
            assertEquals(listOf(zulu.title, alpha.title), renderedTitleOrder(scene, titles))
            assertEquals(LibrarySort.Type.Alphabetical, preferences.sortingMode().get().type)

            val recreated = LibraryScreenModelFactory.create().also { recreatedModel = it }
            recreated.refreshCategories()
            recreated.applyCategoryPreferences(categoryA.id)
            assertEquals(SortMode.UNREAD_COUNT, recreated.state.value.sortMode)
            assertEquals(LibrarySort.Type.Alphabetical, preferences.sortingMode().get().type)

            preferences.categorizedDisplaySettings().set(false)
            recreated.setSortModeAndDirectionForCategory(categoryB.id, SortMode.DATE_ADDED, ascending = false)
            withTimeout(5_000) {
                while (preferences.sortingMode().get().type != LibrarySort.Type.DateAdded) delay(10)
            }
            recreated.refreshCategories()
            recreated.applyCategoryPreferences(categoryA.id)
            assertEquals(SortMode.DATE_ADDED, recreated.state.value.sortMode)
            assertEquals(LibrarySort.Type.DateAdded, preferences.sortingMode().get().type)
        } finally {
            scene?.close()
            recreatedModel?.onDispose()
            rootModel?.onDispose()
            Dispatchers.resetMain()
            context.closeAndJoin()
            preferencesNode.removeNode()
        }
    }

    @Test
    fun `category dialog intents perform create rename reorder and delete through production DI`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(preferencesNode),
            startDownloadWorker = false,
        )
        try {
            val model = LibraryScreenModelFactory.create()
            model.setShowCategoryDialog(true)

            model.createCategory("  First  ")
            model.createCategory("Second")
            assertEquals(listOf("First", "Second"), model.userCategories().map { it.name })

            val first = model.userCategories().first()
            val second = model.userCategories().last()
            model.renameCategory(first.id, "  Renamed  ")
            model.reorderCategory(second.id, 0)
            assertEquals(listOf("Second", "Renamed"), model.userCategories().map { it.name })
            assertEquals(listOf(0L, 1L), model.userCategories().map { it.order })

            model.deleteCategory(second.id)
            assertEquals(listOf("Renamed"), model.userCategories().map { it.name })
            assertEquals(
                model.state.value.categories.indices.map(Int::toLong),
                model.state.value.categories.map { it.order },
            )

            model.setShowCategoryDialog(false)
            assertFalse(model.state.value.showCategoryDialog)
            assertTrue(model.state.value.categories.none { it.id == second.id })
        } finally {
            context.closeAndJoin()
            preferencesNode.removeNode()
        }
    }

    @Test
    fun `category create failure stays visible as operation feedback`() = runBlocking {
        val backing = FakeCategoryRepository()
        val failing = object : CategoryRepository by backing {
            override suspend fun insert(category: Category) {
                error("insert failed")
            }
        }
        val model = LibraryScreenModel(
            createCategory = CreateCategoryWithName(
                categoryRepository = failing,
                preferences = LibraryPreferences(InMemoryPreferenceStore()),
            ),
            getCategories = GetCategories(failing),
        )

        model.createCategory("Broken")

        assertEquals(MR.strings.internal_error.localized(), model.state.value.operationFeedback)
        assertTrue(model.state.value.categories.isEmpty())
    }

    private fun LibraryScreenModel.userCategories() = state.value.categories.filter { it.name.isNotBlank() }

    private suspend fun render(scene: ImageComposeScene) {
        repeat(3) {
            scene.render()
            yield()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && it.semanticLabels().contains(label)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun renderedTitleOrder(scene: ImageComposeScene, titles: Set<String>): List<String> =
        nodes(scene).flatMap { it.semanticLabels() }.filter { it in titles }.distinct()

    private fun SemanticsNode.semanticLabels(): List<String> =
        (if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }) + if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
}
