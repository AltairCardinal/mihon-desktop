package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
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
import mihon.desktop.ui.home.HomeNavigationHost
import mihon.desktop.ui.more.MoreTab
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@Isolated
class CategoryManagementScreenTest {
    @Test
    fun `More categories enters a real child page and returning restores the root`(@TempDir root: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-tests/categories-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(root, DesktopPreferenceStore(node), startDownloadWorker = false)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = CategoryScene(coroutineContext)
        lateinit var categoryModel: LibraryScreenModel
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory({ mihon.desktop.library.LibraryScreenModelFactory.create().also { categoryModel = it } }) { MaterialTheme {
                        TabNavigator(MoreTab) { tabs ->
                            HomeNavigationHost(tabs.current, { tabs.current = it }, true, 0) { CurrentTab() }
                        }
                    } }
                }
            }
            render(scene)
            assertTrue(nodes(scene).any { it.tag("desktop-root-rail") })
            click(scene, MR.strings.categories.localized())
            render(scene)
            assertFalse(nodes(scene).any { it.tag("desktop-root-rail") }, "More categories must push into the actual More navigator")
            assertTrue(nodes(scene).any { it.config.contains(SemanticsActions.OnClick) && MR.strings.action_add.localized() in copy(it) })
            val ownerJob = categoryModel.screenModelScope.coroutineContext[kotlinx.coroutines.Job]!!
            click(scene, MR.strings.action_add.localized())
            render(scene)
            scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyDown))
            scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyUp))
            render(scene)
            assertFalse(nodes(scene).any { it.config.contains(SemanticsActions.SetText) }, "Escape closes only the name dialog")
            assertFalse(nodes(scene).any { it.tag("desktop-root-rail") }, "dialog Escape must keep its category page")
            assertTrue(nodes(scene).single { it.tag("category-add") }.config[SemanticsProperties.Focused])
            clickDescription(scene, MR.strings.action_bar_up_description.localized())
            render(scene)
            assertTrue(nodes(scene).any { it.tag("desktop-root-rail") })
            assertTrue(ownerJob.isCancelled, "leaving the category screen must release its receiver-owned model")
            click(scene, MR.strings.categories.localized())
            render(scene)
            val nextJob = categoryModel.screenModelScope.coroutineContext[kotlinx.coroutines.Job]!!
            scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyDown))
            scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyUp))
            render(scene)
            assertTrue(nodes(scene).any { it.tag("desktop-root-rail") }, "page Escape returns one navigator layer")
            assertTrue(nextJob.isCancelled)
            val screen: Any = CategoryManagementScreen()
            assertTrue(screen is Screen)
            assertFalse(screen is Tab)
            assertFalse(CategoryManagementScreen().key == CategoryManagementScreen().key, "instances in independent navigators cannot share owners")
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    @Test
    fun `category names validate before writing and both card and edit open a focused rename dialog`(@TempDir root: File) = runBlocking {
        withCategoryScene(root) { scene, repository ->
            repository.insert(Category(0, "Existing", 0, 0))
            render(scene)
            click(scene, MR.strings.action_add.localized())
            render(scene)
            assertTrue(MR.strings.action_add_category.localized() in nodes(scene).flatMap(::copy), "Add must open a dedicated name dialog")
            val field = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(field.config[SemanticsProperties.Focused], "the dialog name must receive focus")
            fun confirm() = nodes(scene).last { it.config.contains(SemanticsActions.OnClick) && MR.strings.action_add.localized() in copy(it) }
            assertTrue(confirm().config.contains(SemanticsProperties.Disabled), "empty names cannot be confirmed")
            requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString("Existing"))
            render(scene)
            assertTrue(confirm().config.contains(SemanticsProperties.Disabled), "duplicate names cannot be confirmed")
            assertTrue(MR.strings.error_category_exists.localized() in nodes(scene).flatMap(::copy))
            requireNotNull(nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action).invoke(AnnotatedString("New"))
            render(scene)
            click(scene, MR.strings.action_add.localized())
            render(scene)
            assertEquals(listOf("Existing", "New"), repository.getAll().filterNot(Category::isSystemCategory).map { it.name })
            click(scene, "Existing")
            render(scene)
            assertEquals("Existing", nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.config[SemanticsProperties.EditableText].text)
            assertTrue(nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && MR.strings.action_ok.localized() in copy(it) }.config.contains(SemanticsProperties.Disabled))
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            val existingId = repository.getAll().filterNot(Category::isSystemCategory).first().id
            assertTrue(flatten(nodes(scene).single { it.tag("category-card-$existingId") }).any {
                it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused]
            }, "cancel must restore focus to the category card")
            val edit = nodes(scene).single { it.tag("category-rename-$existingId") }
            requireNotNull(edit.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            assertEquals("Existing", nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.config[SemanticsProperties.EditableText].text)
            fun renameTo(value: String) {
                requireNotNull(nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value))
            }
            renameTo("")
            render(scene)
            assertTrue(nodes(scene).last { it.config.contains(SemanticsActions.OnClick) && MR.strings.action_ok.localized() in copy(it) }.config.contains(SemanticsProperties.Disabled))
            renameTo("New")
            render(scene)
            assertTrue(MR.strings.error_category_exists.localized() in nodes(scene).flatMap(::copy))
            renameTo(" Existing ")
            render(scene)
            assertTrue(MR.strings.desktop_category_name_unchanged.localized() in nodes(scene).flatMap(::copy))
            renameTo(" Renamed ")
            render(scene)
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals("Renamed", repository.get(existingId)!!.name)
            assertTrue(flatten(nodes(scene).single { it.tag("category-card-$existingId") }).any {
                it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused]
            }, "saving must restore focus to the stable category card")
        }
    }

    @Test
    fun `library root has no direct category management control`(@TempDir root: File) = runBlocking {
        withCategoryScene(root) { scene, _ ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    MaterialTheme { Navigator(LibraryRootScreen()) { CurrentScreen() } }
                }
            }
            render(scene)
            val descriptions = nodes(scene).flatMap {
                if (it.config.contains(SemanticsProperties.ContentDescription)) it.config[SemanticsProperties.ContentDescription] else emptyList()
            }
            assertTrue(MR.strings.action_search.localized() in descriptions)
            assertFalse(MR.strings.desktop_ui_manage_categories_eceede45.localized() in descriptions, "More must own the direct category management entry")
        }
    }

    @Test
    fun `real drag persists the handle target`(@TempDir root: File) = runBlocking {
        withCategoryScene(root) { scene, repository ->
            repository.insert(Category(0, "Drag first", 0, 0))
            repository.insert(Category(0, "Drag second", 1, 0))
            render(scene)
            val categories = repository.getAll().filterNot(Category::isSystemCategory)
            val from = nodes(scene).single { it.tag("category-handle-${categories.first().id}") }.boundsInRoot.center
            val to = nodes(scene).single { it.tag("category-handle-${categories.last().id}") }.boundsInRoot.center
            scene.pointer(PointerEventType.Press, from, true, PointerButton.Primary)
            render(scene)
            repeat(12) { step ->
                scene.pointer(PointerEventType.Move, Offset(from.x, from.y + (to.y - from.y) * (step + 1) / 12), true)
                render(scene)
            }
            scene.pointer(PointerEventType.Release, to, false, PointerButton.Primary)
            render(scene)
            assertEquals(listOf("Drag second", "Drag first"), repository.getAll().filterNot(Category::isSystemCategory).map { it.name }, "pointer drag must reach the persistent reorder use case")
        }
    }

    @Test
    fun `failed create keeps its name draft and retries the existing SQLite use case`(@TempDir root: File) = runBlocking {
        var fail = true
        withCategoryScene(root, repositoryAdapter = { actual ->
            object : CategoryRepository by actual {
                override suspend fun insert(category: Category) {
                    if (fail) throw java.io.IOException("Injected category create failure")
                    actual.insert(category)
                }
            }
        }) { scene, repository ->
            render(scene)
            click(scene, MR.strings.action_add.localized())
            render(scene)
            requireNotNull(nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action).invoke(AnnotatedString("Retry name"))
            render(scene)
            click(scene, MR.strings.action_add.localized())
            render(scene)
            assertEquals("Retry name", nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.config[SemanticsProperties.EditableText].text)
            assertTrue(repository.getAll().filterNot(Category::isSystemCategory).isEmpty())
            fail = false
            click(scene, MR.strings.action_add.localized())
            render(scene)
            assertEquals(listOf("Retry name"), repository.getAll().filterNot(Category::isSystemCategory).map { it.name })
        }
    }

    @Test
    fun `narrow long category list keeps the final delete action clear of the Add FAB`(@TempDir root: File) = runBlocking {
        withCategoryScene(root) { scene, repository ->
            repeat(20) { repository.insert(Category(0, "Long category $it", it.toLong(), 0)) }
            scene.resize(320, 900)
            render(scene)
            val list = nodes(scene).single { it.config.contains(SemanticsActions.ScrollToIndex) }
            requireNotNull(list.config[SemanticsActions.ScrollToIndex].action).invoke(19)
            render(scene)
            val last = repository.getAll().filterNot(Category::isSystemCategory).last().id
            val delete = nodes(scene).single { it.tag("category-delete-$last") }.boundsInRoot
            val fab = nodes(scene).single { it.tag("category-add") }.boundsInRoot
            assertFalse(delete.overlaps(fab), "Add FAB must not cover the final category's delete control")
        }
    }

    @Test
    fun `detail More category dialog discards drafts before the shared editor and saves default membership`(@TempDir root: File) = runBlocking {
        var mangaId = 0L
        withCategoryScene(root) { scene, repository ->
            repository.insert(Category(0, "Saved category", 0, 0))
            repository.insert(Category(0, "Draft category", 1, 0))
            val categories = repository.getAll().filterNot(Category::isSystemCategory)
            val mangas = Injekt.get<MangaRepository>()
            val manga = mangas.insertNetworkManga(listOf(Manga.create().copy(source = 0, url = "/categories-detail", title = "Category detail", initialized = true))).single()
            mangaId = manga.id
            mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(manga.id, true, 1, listOf(categories.first().id))))
            lateinit var navigator: Navigator
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    MaterialTheme { Navigator(MangaDetailScreen(manga.id)) { nav -> navigator = nav; CurrentScreen() } }
                }
            }
            render(scene)
            fun open() {
                val menu = nodes(scene).firstOrNull { it.tag("manga-category-menu") }
                assertTrue(menu != null, "favorite detail must expose Edit categories through its More menu")
                requireNotNull(menu!!.config[SemanticsActions.OnClick].action).invoke()
            }
            open()
            render(scene)
            click(scene, MR.strings.action_edit_categories.localized())
            render(scene)
            assertFalse(nodes(scene).any { it.tag("manga-category-0") })
            click(scene, "Draft category")
            render(scene)
            click(scene, MR.strings.action_edit.localized())
            render(scene)
            assertTrue(navigator.lastItem is CategoryManagementScreen)
            assertTrue(navigator.items.first() is MangaDetailScreen)
            assertEquals(listOf(categories.first().id), repository.getCategoriesByMangaId(manga.id).map { it.id })
            navigator.pop()
            render(scene)
            assertTrue(navigator.lastItem is MangaDetailScreen)
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ToggleableState) })
            assertTrue(nodes(scene).single { it.tag("manga-category-menu") }.config[SemanticsProperties.Focused], "editor return restores the More trigger")
            open()
            render(scene)
            click(scene, MR.strings.action_edit_categories.localized())
            render(scene)
            val savedRow = nodes(scene).single { it.tag("manga-category-${categories.first().id}") }
            val draftRow = nodes(scene).single { it.tag("manga-category-${categories.last().id}") }
            assertEquals(androidx.compose.ui.state.ToggleableState.On, savedRow.config[SemanticsProperties.ToggleableState])
            assertEquals(androidx.compose.ui.state.ToggleableState.Off, draftRow.config[SemanticsProperties.ToggleableState])
            click(scene, "Draft category")
            render(scene)
            scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyDown))
            scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyUp))
            render(scene)
            assertTrue(navigator.lastItem is MangaDetailScreen)
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ToggleableState) })
            assertTrue(nodes(scene).single { it.tag("manga-category-menu") }.config[SemanticsProperties.Focused], "dialog Escape restores the More trigger")
            assertEquals(listOf(categories.first().id), repository.getCategoriesByMangaId(manga.id).map { it.id }, "Escape discards the membership draft")
            open()
            render(scene)
            click(scene, MR.strings.action_edit_categories.localized())
            render(scene)
            click(scene, "Saved category")
            render(scene)
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertTrue(repository.getCategoriesByMangaId(manga.id).isEmpty())
            assertTrue(mangas.getMangaById(manga.id).favorite)
        }
        withCategoryScene(root) { _, repository ->
            assertTrue(repository.getCategoriesByMangaId(mangaId).isEmpty())
            assertTrue(Injekt.get<MangaRepository>().getMangaById(mangaId).favorite)
        }
    }

    @Test
    fun `review favorite detail has no independent category action`(@TempDir root: File) = runBlocking {
        withCategoryScene(root) { scene, _ ->
            val mangas = Injekt.get<MangaRepository>()
            val manga = mangas.insertNetworkManga(listOf(Manga.create().copy(source = 0, url = "/category-entry", title = "Menu entry", initialized = true))).single()
            mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(manga.id, true, 1, emptyList())))
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    MaterialTheme { Navigator(MangaDetailScreen(manga.id)) { CurrentScreen() } }
                }
            }
            render(scene)
            val edit = MR.strings.action_edit_categories.localized()
            assertFalse(nodes(scene).any { node -> node.config.contains(SemanticsActions.OnClick) && flatten(node).any {
                it.config.contains(SemanticsProperties.ContentDescription) && edit in it.config[SemanticsProperties.ContentDescription]
            } }, "Edit categories must only appear after opening More")
            assertTrue(nodes(scene).any { it.tag("manga-category-menu") })
        }
    }

    @Test
    fun `review narrow detail category dialog scrolls to its last choice and saves it`(@TempDir root: File) = runBlocking {
        withCategoryScene(root) { scene, repository ->
            repeat(24) { repository.insert(Category(0, "Detail choice $it", it.toLong(), 0)) }
            val last = repository.getAll().filterNot(Category::isSystemCategory).last()
            val mangas = Injekt.get<MangaRepository>()
            val manga = mangas.insertNetworkManga(listOf(Manga.create().copy(source = 0, url = "/category-scroll", title = "Long choices", initialized = true))).single()
            mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(manga.id, true, 1, emptyList())))
            scene.resize(360, 600)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    MaterialTheme { Navigator(MangaDetailScreen(manga.id)) { CurrentScreen() } }
                }
            }
            render(scene)
            requireNotNull(nodes(scene).single { it.tag("manga-category-menu") }.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            click(scene, MR.strings.action_edit_categories.localized())
            render(scene)
            val list = nodes(scene).firstOrNull { it.tag("manga-category-list") && it.config.contains(SemanticsActions.ScrollToIndex) }
            assertTrue(list != null, "long category choices need a bounded scrollable list")
            requireNotNull(list!!.config[SemanticsActions.ScrollToIndex].action).invoke(23)
            render(scene)
            val row = nodes(scene).single { it.tag("manga-category-${last.id}") }
            val bounds = nodes(scene).single { it.tag("manga-category-list") }.boundsInRoot
            assertTrue(row.boundsInRoot.top >= bounds.top && row.boundsInRoot.bottom <= bounds.bottom, "last entity must be fully reachable inside the list: row=${row.boundsInRoot}, list=$bounds")
            assertTrue(nodes(scene).any { it.config.contains(SemanticsActions.OnClick) && MR.strings.action_edit.localized() in copy(it) })
            assertTrue(nodes(scene).any { it.config.contains(SemanticsActions.OnClick) && MR.strings.action_cancel.localized() in copy(it) })
            requireNotNull(row.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(listOf(last.id), repository.getCategoriesByMangaId(manga.id).map { it.id })
        }
    }

    @Test
    fun `review focused category handle accepts keyboard reorder`(@TempDir root: File) = runBlocking {
        withCategoryScene(root) { scene, repository ->
            repository.insert(Category(0, "Keyboard first", 0, 0))
            repository.insert(Category(0, "Keyboard second", 1, 0))
            render(scene)
            val first = repository.getAll().filterNot(Category::isSystemCategory).first().id
            val handle = nodes(scene).single { it.tag("category-handle-$first") }
            requireNotNull(handle.config[SemanticsActions.RequestFocus].action).invoke()
            render(scene)
            assertTrue(nodes(scene).single { it.tag("category-handle-$first") }.config[SemanticsProperties.Focused])
            scene.sendKeyEvent(keyEvent(Key.DirectionDown, KeyEventType.KeyDown, alt = true))
            scene.sendKeyEvent(keyEvent(Key.DirectionDown, KeyEventType.KeyUp, alt = true))
            render(scene)
            assertEquals(listOf("Keyboard second", "Keyboard first"), repository.getAll().filterNot(Category::isSystemCategory).map { it.name })
        }
    }

    @Test
    fun `delete names the category confirms once keeps manga and clears existing preference references`(@TempDir root: File) = runBlocking {
        withCategoryScene(root) { scene, repository ->
            repository.insert(Category(0, "Delete target", 0, 0))
            val target = repository.getAll().filterNot(Category::isSystemCategory).single()
            val mangas = Injekt.get<MangaRepository>()
            val manga = mangas.insertNetworkManga(listOf(Manga.create().copy(source = 0, url = "/category-delete", title = "Keep manga"))).single()
            mangas.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(manga.id, true, 1, listOf(target.id))))
            val preferences = Injekt.get<LibraryPreferences>()
            val downloads = Injekt.get<DownloadPreferences>()
            preferences.defaultCategory().set(target.id.toInt())
            preferences.updateCategories().set(setOf(target.id.toString()))
            preferences.updateCategoriesExclude().set(setOf(target.id.toString()))
            downloads.removeExcludeCategories().set(setOf(target.id.toString()))
            downloads.downloadNewChapterCategories().set(setOf(target.id.toString()))
            downloads.downloadNewChapterCategoriesExclude().set(setOf(target.id.toString()))
            render(scene)
            assertFalse(nodes(scene).any { it.tag("category-card-0") })
            assertTrue(nodes(scene).any { it.tag("category-delete-${target.id}") }, "each category needs the real delete action")
            fun openDelete() = requireNotNull(nodes(scene).single { it.tag("category-delete-${target.id}") }.config[SemanticsActions.OnClick].action).invoke()
            openDelete()
            render(scene)
            assertTrue(MR.strings.delete_category_confirmation.localized(java.util.Locale.getDefault(), target.name) in nodes(scene).flatMap(::copy))
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertEquals(listOf(target.id), repository.getAll().filterNot(Category::isSystemCategory).map { it.id })
            openDelete()
            render(scene)
            click(scene, MR.strings.action_delete.localized())
            render(scene)
            assertTrue(repository.getAll().filterNot(Category::isSystemCategory).isEmpty())
            assertTrue(mangas.getMangaById(manga.id).favorite)
            assertTrue(repository.getCategoriesByMangaId(manga.id).isEmpty(), "unassigned favorite projects to system default without deleting manga")
            assertEquals(-1, preferences.defaultCategory().get())
            assertTrue(preferences.updateCategories().get().isEmpty())
            assertTrue(preferences.updateCategoriesExclude().get().isEmpty())
            assertTrue(downloads.removeExcludeCategories().get().isEmpty())
            assertTrue(downloads.downloadNewChapterCategories().get().isEmpty())
            assertTrue(downloads.downloadNewChapterCategoriesExclude().get().isEmpty())
            assertTrue(nodes(scene).any { it.config.contains(SemanticsActions.OnClick) && MR.strings.action_add.localized() in copy(it) }, "empty categories must keep Add")
        }
    }

    @Test
    fun `accessible reorder uses real SQLite restores failed order can retry and survives reopening`(@TempDir root: File) = runBlocking {
        var fail = true
        withCategoryScene(root, repositoryAdapter = { actual ->
            object : CategoryRepository by actual {
                override suspend fun updatePartial(updates: List<CategoryUpdate>) {
                    if (fail) throw java.io.IOException("Injected category order write failure")
                    actual.updatePartial(updates)
                }
            }
        }) { scene, repository ->
            repository.insert(Category(0, "First", 0, 0))
            repository.insert(Category(0, "Second", 1, 0))
            render(scene)
            val first = repository.getAll().filterNot(Category::isSystemCategory).first().id
            assertTrue(nodes(scene).any { it.tag("category-handle-$first") }, "category ordering must have an accessible drag handle")
            fun move() {
                val handle = nodes(scene).single { it.tag("category-handle-$first") }
                val action = handle.config[SemanticsActions.CustomActions].single { it.label == MR.strings.action_move_to_bottom.localized() }
                assertTrue(action.action())
            }
            move()
            render(scene)
            assertEquals(listOf("First", "Second"), repository.getAll().filterNot(Category::isSystemCategory).map { it.name })
            assertEquals(listOf("First", "Second"), nodes(scene).filter { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag].startsWith("category-card-") }.map { copy(it).first() })
            assertTrue(MR.strings.internal_error.localized() in nodes(scene).flatMap(::copy), "failed order must have retryable feedback")
            fail = false
            move()
            render(scene)
            assertEquals(listOf("Second", "First"), repository.getAll().filterNot(Category::isSystemCategory).map { it.name })
        }
        withCategoryScene(root) { scene, repository ->
            render(scene)
            assertEquals(listOf("Second", "First"), repository.getAll().filterNot(Category::isSystemCategory).map { it.name })
            assertEquals(listOf(0L, 1L), repository.getAll().filterNot(Category::isSystemCategory).map { it.order })
        }
    }

    private suspend fun withCategoryScene(
        root: File,
        repositoryAdapter: ((CategoryRepository) -> CategoryRepository)? = null,
        block: suspend (CategoryScene, CategoryRepository) -> Unit,
    ) {
        val node = Preferences.userRoot().node("mihon-tests/categories-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(root, DesktopPreferenceStore(node), startDownloadWorker = false)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = CategoryScene(kotlinx.coroutines.currentCoroutineContext())
        val repository = repositoryAdapter?.invoke(Injekt.get()) ?: Injekt.get<CategoryRepository>()
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory({
                        if (repositoryAdapter == null) mihon.desktop.library.LibraryScreenModelFactory.create()
                        else LibraryScreenModel(
                            getCategories = GetCategories(repository), reorderCategory = ReorderCategory(repository),
                            createCategory = CreateCategoryWithName(repository, Injekt.get()), renameCategory = RenameCategory(repository),
                            deleteCategory = DeleteCategory(repository, Injekt.get(), Injekt.get()),
                        )
                    }) { MaterialTheme { Navigator(CategoryManagementScreen()) { CurrentScreen() } } }
                }
            }
            block(scene, repository)
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private suspend fun render(scene: CategoryScene) = repeat(8) { scene.render(); delay(10) }
    private fun nodes(scene: CategoryScene) = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun copy(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { value -> value.text } else emptyList()
    }
    private fun SemanticsNode.tag(value: String) = config.contains(SemanticsProperties.TestTag) && config[SemanticsProperties.TestTag] == value
    private fun click(scene: CategoryScene, label: String) = requireNotNull(
        nodes(scene).last { it.config.contains(SemanticsActions.OnClick) && label in copy(it) }.config[SemanticsActions.OnClick].action,
    ).invoke()
    private fun clickDescription(scene: CategoryScene, label: String) = requireNotNull(
        nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child ->
                child.config.contains(SemanticsProperties.ContentDescription) && label in child.config[SemanticsProperties.ContentDescription]
            }
        }.config[SemanticsActions.OnClick].action,
    ).invoke()

    private fun keyEvent(key: Key, type: KeyEventType, alt: Boolean = false): ComposeKeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp").invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        return ComposeKeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, alt, false, null))
    }

    private class CategoryScene(context: CoroutineContext) : AutoCloseable {
        val semanticsOwners = linkedSetOf<SemanticsOwner>()
        private var windowSize by androidx.compose.runtime.mutableStateOf(IntSize(1400, 900))
        private val canvas = Canvas(ImageBitmap(1400, 900))
        private val scene = CanvasLayersComposeScene(
            size = IntSize(1400, 900),
            coroutineContext = context,
            platformContext = object : PlatformContext {
                override val windowInfo = object : WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize get() = windowSize
                    override val containerDpSize get() = androidx.compose.ui.unit.DpSize(windowSize.width.dp, windowSize.height.dp)
                }
                override val inputModeManager = object : InputModeManager {
                    override val inputMode = InputMode.Keyboard
                    override fun requestInputMode(inputMode: InputMode) = true
                }
                override fun requestFocus() = true
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { semanticsOwners += semanticsOwner }
                    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { semanticsOwners -= semanticsOwner }
                    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
                    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
                }
            },
            invalidate = {},
        )
        fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) = scene.setContent(content)
        fun render() = scene.render(canvas, System.nanoTime())
        fun resize(width: Int, height: Int) { windowSize = IntSize(width, height); scene.size = windowSize }
        fun sendKeyEvent(event: ComposeKeyEvent) = scene.sendKeyEvent(event)
        fun pointer(type: PointerEventType, position: Offset, pressed: Boolean, button: PointerButton? = null) =
            scene.sendPointerEvent(type, position, buttons = PointerButtons(isPrimaryPressed = pressed), button = button)
        override fun close() = scene.close()
    }
}
