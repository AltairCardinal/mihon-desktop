package mihon.desktop.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.db.SqlDriver
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.domain.ui.model.ThemeMode
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
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SortMode
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.home.HomeNavigationHost
import mihon.desktop.ui.home.HomeScreen
import mihon.desktop.ui.theme.DesktopTheme
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@Isolated
class LibraryOptionsInteractionTest {
    @Test
    fun `ordinary toolbar removes independent settings sort display and random buttons`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, _ ->
            val descriptions = nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) }.flatMap(::labels)
            assertFalse(MR.strings.action_settings.localized() in descriptions, "settings must use More root")
            assertFalse(MR.strings.action_sort.localized() in descriptions, "sort belongs to the single panel")
            assertFalse(
                MR.strings.action_display_mode.localized() in descriptions,
                "display belongs to the single panel",
            )
            assertFalse(MR.strings.desktop_ui_random_manga.localized() in descriptions, "random belongs to More menu")
        }
    }

    @Test
    fun `filter trigger opens three real tabs and normal reopening starts with Filter`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, preferences, _ ->
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            assertEquals(
                listOf(MR.strings.action_filter, MR.strings.action_sort, MR.strings.action_display).map {
                    it.localized()
                },
                panelTabs(scene).flatMap(::labels),
                "Filter must open the single three-page options panel",
            )
            click(scene, MR.strings.action_display.localized())
            render(scene)
            click(scene, MR.strings.action_display_list.localized())
            render(scene)
            assertEquals(tachiyomi.domain.library.model.LibraryDisplayMode.List, preferences.displayMode().get())
            click(scene, MR.strings.action_close.localized())
            render(scene)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            val active = panelTabs(scene).single { it.config[SemanticsProperties.Selected] }
            assertTrue(MR.strings.action_filter.localized() in labels(active))
        }
    }

    @Test
    fun `real Home root reselect keeps one panel session and detail ignores reselect`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, host, _, model ->
            val rootButton = nodes(scene).single {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "desktop-root-${LibraryTab.key}" &&
                    it.config.contains(SemanticsActions.OnClick)
            }
            click(rootButton)
            render(scene)
            assertEquals(3, panelTabs(scene).size, "root reselect must open the actual three-page panel")
            click(scene, MR.strings.action_sort.localized())
            render(scene)
            click(rootButton)
            render(scene)
            assertEquals(3, panelTabs(scene).size, "reselect cannot stack another modal")
            assertTrue(
                labels(panelTabs(scene).single { it.config[SemanticsProperties.Selected] })
                    .contains(MR.strings.action_sort.localized()),
            )
            click(scene, MR.strings.action_close.localized())
            render(scene)
            val item = model().state.value.allItems.single()
            click(scene, item.manga.title)
            render(scene)
            host.onReselect()
            render(scene)
            assertTrue(panelTabs(scene).isEmpty(), "front detail must not open a hidden root options panel")
        }
    }

    @Test
    fun `panel preference writes before and after failures restore authority and permit retry`(
        @TempDir root: File,
    ) = runBlocking {
        lateinit var backend: FaultPreferences
        withRoot(root, backendFactory = { FaultPreferences(it).also { backend = it } }) {
                scene,
                _,
                preferences,
                model,
            ->
            for (afterWrite in listOf(false, true)) {
                click(scene, MR.strings.action_filter.localized())
                render(scene)
                val cases = listOf(
                    preferences.filterUnread() to MR.strings.unread.localized(),
                    preferences.filterStarted() to MR.strings.desktop_ui_started.localized(),
                    preferences.filterBookmarked() to MR.strings.action_filter_bookmarked.localized(),
                    preferences.filterCompleted() to MR.strings.completed.localized(),
                    preferences.filterDownloaded() to MR.strings.label_downloaded.localized(),
                    preferences.filterIntervalCustom() to MR.strings.desktop_ui_custom_interval.localized(),
                )
                for ((preference, label) in cases) {
                    val old = preference.get()
                    val wasSet = preference.isSet()
                    backend.fail(preference.key(), afterWrite)
                    assertDoesNotThrow { click(scene, label) }
                    render(scene)
                    assertEquals(old, preference.get(), "$label authority after=$afterWrite")
                    assertEquals(wasSet, preference.isSet(), "$label exact unset state")
                    assertTrue(
                        MR.strings.desktop_appearance_save_failed.localized() in nodes(scene)
                            .flatMap(::labels),
                        "failed action needs feedback",
                    )
                    click(scene, label)
                    render(scene)
                    assertTrue(old != preference.get(), "$label must permit retry")
                }
                click(scene, MR.strings.action_sort.localized())
                render(scene)
                val oldSort = preferences.sortingMode().get()
                val selected = if (oldSort.type == tachiyomi.domain.library.model.LibrarySort.Type.LastRead) {
                    MR.strings.action_sort_alpha.localized()
                } else {
                    MR.strings.action_sort_last_read.localized()
                }
                backend.fail(preferences.sortingMode().key(), afterWrite)
                click(scene, selected)
                render(scene)
                assertEquals(oldSort, preferences.sortingMode().get())
                assertTrue(model().state.value.operationFeedback != null)
                click(scene, selected)
                render(scene)
                assertTrue(oldSort != preferences.sortingMode().get())
                click(scene, MR.strings.action_display.localized())
                render(scene)
                click(scene, MR.strings.action_display_grid.localized())
                render(scene)
                val oldDisplay = preferences.displayMode().get()
                backend.fail(preferences.displayMode().key(), afterWrite)
                assertDoesNotThrow { click(scene, MR.strings.action_display_list.localized()) }
                render(scene)
                assertEquals(oldDisplay, preferences.displayMode().get())
                click(scene, MR.strings.action_display_list.localized())
                render(scene)
                assertEquals(tachiyomi.domain.library.model.LibraryDisplayMode.List, preferences.displayMode().get())
                for ((preference, title) in listOf(
                    preferences.downloadBadge() to MR.strings.action_display_download_badge,
                    preferences.unreadBadge() to MR.strings.action_display_unread_badge,
                    preferences.localBadge() to MR.strings.action_display_local_badge,
                    preferences.languageBadge() to MR.strings.action_display_language_badge,
                    preferences.showContinueReadingButton() to MR.strings.action_display_show_continue_reading_button,
                    preferences.categoryTabs() to MR.strings.action_display_show_tabs,
                    preferences.categoryNumberOfItems() to MR.strings.action_display_show_number_of_items,
                )) {
                    val old = preference.get()
                    val wasSet = preference.isSet()
                    backend.fail(preference.key(), afterWrite)
                    assertDoesNotThrow { click(scene, title.localized()) }
                    render(scene)
                    assertEquals(old, preference.get())
                    assertEquals(wasSet, preference.isSet())
                    click(scene, title.localized())
                    render(scene)
                    assertEquals(!old, preference.get())
                }
                for ((index, preference) in listOf(preferences.portraitColumns(), preferences.landscapeColumns())
                    .withIndex()) {
                    val old = preference.get()
                    val newValue = if (old == 10) 0f else 10f
                    backend.fail(preference.key(), afterWrite)
                    val slider = nodes(scene).filter { it.config.contains(SemanticsActions.SetProgress) }[index]
                    assertDoesNotThrow {
                        requireNotNull(slider.config[SemanticsActions.SetProgress].action)
                            .invoke(newValue)
                    }
                    render(scene)
                    assertEquals(old, preference.get())
                    requireNotNull(
                        nodes(scene).filter { it.config.contains(SemanticsActions.SetProgress) }[index]
                            .config[SemanticsActions.SetProgress].action,
                    ).invoke(newValue)
                    render(scene)
                    assertEquals(newValue.toInt(), preference.get())
                }
                click(scene, MR.strings.action_close.localized())
                render(scene)
                click(scene, MR.strings.action_filter.localized())
                render(scene)
                assertTrue(MR.strings.unread.localized() in nodes(scene).flatMap(::labels))
                click(scene, MR.strings.action_close.localized())
                render(scene)
            }
        }
    }

    @Test
    fun `SQLite rejection and post commit sort failure restore sort bits for real and empty categories`(
        @TempDir root: File,
    ) = runBlocking {
        lateinit var fault: FaultCategoryRepository
        withRoot(root, categoryRepositoryOverride = { FaultCategoryRepository(it).also { fault = it } }) {
                scene,
                _,
                preferences,
                model,
            ->
            val repository = Injekt.get<CategoryRepository>()
            repository.insert(Category(0, "Actual category", 0, LibrarySort.default.flag))
            repository.insert(Category(0, "Empty category", 1, LibrarySort.default.flag))
            val categories = repository.getAll()
            val current = categories.first { !it.isSystemCategory }
            Injekt.get<MangaRepository>().updateMembershipsAtomically(
                model().state.value.allItems.map { LibraryMembershipUpdate(it.id, true, 1, listOf(current.id)) },
            )
            render(scene)
            click(scene, current.name)
            render(scene)
            val driver = Injekt.get<SqlDriver>()
            for (categorized in listOf(true, false)) {
                preferences.categorizedDisplaySettings().set(categorized)
                render(scene)
                val oldFlags = repository.getAll().associate { it.id to it.flags }
                click(scene, MR.strings.action_filter.localized())
                render(scene)
                click(scene, MR.strings.action_sort.localized())
                render(scene)
                driver.execute(
                    null,
                    """CREATE TRIGGER reject_sort BEFORE UPDATE OF flags ON categories
                    BEGIN SELECT RAISE(ABORT, 'sort refused'); END""",
                    0,
                )
                try {
                    click(scene, MR.strings.action_sort_last_read.localized())
                    render(scene)
                    assertEquals(oldFlags, repository.getAll().associate { it.id to it.flags })
                    assertEquals(SortMode.TITLE, model().state.value.sortMode)
                    assertTrue(model().state.value.operationFeedback != null)
                } finally {
                    driver.execute(null, "DROP TRIGGER reject_sort", 0)
                }
                fault.failAfterCommit = true
                click(scene, MR.strings.action_sort_last_read.localized())
                render(scene)
                val actual = repository.getAll().associate { it.id to it.flags }
                for ((id, old) in oldFlags) {
                    assertEquals(
                        old and LibrarySort.default.mask,
                        actual.getValue(id) and LibrarySort.default.mask,
                        "categorized=$categorized id=$id failed sort must restore original DB sort bits",
                    )
                }
                assertEquals(1L, actual.getValue(current.id) and 1L, "a concurrent non-sort flag must survive rollback")
                assertEquals(SortMode.TITLE, model().state.value.sortMode)
                assertTrue(model().state.value.operationFeedback != null)
                click(scene, MR.strings.action_sort_last_read.localized())
                render(scene)
                assertEquals(LibrarySort.Type.LastRead, LibrarySort.valueOf(repository.get(current.id)!!.flags).type)
                assertEquals(
                    if (categorized) LibrarySort.Type.Alphabetical else LibrarySort.Type.LastRead,
                    LibrarySort.valueOf(repository.get(categories.last().id)!!.flags).type,
                    "global sorting includes empty DB categories; local sorting preserves them",
                )
                click(scene, MR.strings.action_sort_alpha.localized())
                render(scene)
                click(scene, MR.strings.action_close.localized())
                render(scene)
            }
        }
    }

    @Test
    fun `native modal traps both tab directions restores trigger and escapes selection then search`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            val trigger = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_filter.localized() in labels(it)
            }
            requireNotNull(trigger.config[SemanticsActions.RequestFocus].action).invoke()
            key(scene, Key.Spacebar)
            render(scene)
            assertEquals(3, panelTabs(scene).size, "Space must open the real options trigger")
            val first = focused(scene).id
            val forward = mutableListOf(first)
            repeat(18) {
                key(scene, Key.Tab)
                render(scene)
                val focus = focused(scene)
                assertTrue(focusOwnerContainsPanel(scene, focus), "Tab cannot focus the background")
                forward += focus.id
            }
            assertTrue(forward.toSet().size >= 8, "Tab must visit the full reachable Filter-page controls")
            assertTrue(forward.drop(1).contains(first), "forward focus must complete a modal loop")
            val backwards = mutableSetOf<Int>()
            repeat(18) {
                key(scene, Key.Tab, shift = true)
                render(scene)
                val focus = focused(scene)
                assertTrue(focusOwnerContainsPanel(scene, focus), "Shift+Tab cannot focus the background")
                backwards += focus.id
            }
            assertTrue(
                backwards.containsAll(forward.toSet()),
                "both directions must visit the same reachable modal controls",
            )
            key(scene, Key.Escape)
            render(scene)
            assertTrue(panelTabs(scene).isEmpty())
            assertTrue(
                MR.strings.action_filter.localized() in labels(focused(scene)),
                "Escape must restore the options trigger",
            )
            val searchTrigger = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_search.localized() in labels(it)
            }
            requireNotNull(searchTrigger.config[SemanticsActions.RequestFocus].action).invoke()
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            key(scene, Key.Escape)
            render(scene)
            assertTrue(
                MR.strings.action_filter.localized() in labels(focused(scene)),
                "an accessibility click must return focus to its actual options trigger",
            )
            click(scene, MR.strings.action_search.localized())
            render(scene)
            val field = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString("Options"))
            render(scene)
            val work = nodes(scene).first {
                it.config.contains(SemanticsActions.OnLongClick) &&
                    "Options work" in labels(it)
            }
            requireNotNull(work.config[SemanticsActions.OnLongClick].action).invoke()
            render(scene)
            click(
                nodes(scene).single {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "desktop-root-${LibraryTab.key}"
                },
            )
            render(scene)
            click(scene, MR.strings.completed.localized())
            render(scene)
            key(scene, Key.Escape)
            render(scene)
            assertTrue(panelTabs(scene).isEmpty())
            assertEquals("Options", model().state.value.searchQuery)
            assertTrue(
                MR.strings.desktop_ui_clear_selection.localized() in nodes(scene).flatMap(::labels),
                "hidden selection remains after the panel closes",
            )
            assertTrue(
                nodes(scene).flatMap(::labels)
                    .contains(MR.strings.desktop_ui_selected_count.localized(Locale.getDefault(), 1)),
            )
            key(scene, Key.Escape)
            render(scene)
            assertEquals("Options", model().state.value.searchQuery)
            assertTrue(nodes(scene).any { it.config.contains(SemanticsProperties.EditableText) })
            key(scene, Key.Escape)
            render(scene)
            assertEquals(null, model().state.value.searchQuery)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            val backgroundSearch = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_search.localized() in labels(it)
            }
            scene.pointer(PointerEventType.Press, backgroundSearch.boundsInRoot.center, true, PointerButton.Primary)
            scene.pointer(PointerEventType.Release, backgroundSearch.boundsInRoot.center, false, PointerButton.Primary)
            render(scene)
            assertTrue(panelTabs(scene).isEmpty(), "the outer scrim dismisses exactly the panel")
            assertEquals(
                null,
                model().state.value.searchQuery,
                "the background search cannot be operated through the scrim",
            )
        }
    }

    @Test
    fun `global download lock and disabled interval retain local authority and real empty feedback`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root, mangaSource = 99) { scene, _, preferences, model ->
            preferences.downloadedOnly().set(true)
            render(scene)
            assertTrue(
                MR.strings.error_no_match.localized() in nodes(scene).flatMap(::labels),
                "only-downloaded is an effective filter, not an empty category",
            )
            preferences.filterDownloaded().set(TriState.ENABLED_NOT)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            val downloaded = nodes(scene).single {
                it.config.contains(SemanticsProperties.Role) &&
                    it.config[SemanticsProperties.Role] == Role.Checkbox &&
                    MR.strings.label_downloaded.localized() in labels(it)
            }
            assertTrue(downloaded.config.contains(SemanticsProperties.Disabled))
            assertEquals(
                MR.strings.desktop_ui_filter_include.localized(),
                downloaded.config[SemanticsProperties.StateDescription],
            )
            scene.pointer(PointerEventType.Press, downloaded.boundsInRoot.center, true, PointerButton.Primary)
            scene.pointer(PointerEventType.Release, downloaded.boundsInRoot.center, false, PointerButton.Primary)
            render(scene)
            assertEquals(TriState.ENABLED_NOT, preferences.filterDownloaded().get())
            preferences.downloadedOnly().set(false)
            render(scene)
            assertEquals(TriState.ENABLED_NOT, model().state.value.filter.downloaded)
            preferences.filterDownloaded().set(TriState.DISABLED)
            preferences.filterIntervalCustom().set(TriState.ENABLED_IS)
            preferences.autoUpdateMangaRestrictions().set(emptySet())
            render(scene)
            val interval = nodes(scene).single {
                it.config.contains(SemanticsProperties.Role) &&
                    it.config[SemanticsProperties.Role] == Role.Checkbox &&
                    MR.strings.desktop_ui_custom_interval.localized() in labels(it)
            }
            assertTrue(
                interval.config.contains(SemanticsProperties.Disabled),
                "restriction changes must update the mounted panel",
            )
            assertFalse(model().state.value.hasActiveFilters)
            assertTrue(
                MR.strings.desktop_library_interval_requires_restriction.localized() in nodes(scene)
                    .flatMap(::labels),
            )
            assertEquals(TriState.ENABLED_IS, preferences.filterIntervalCustom().get())
        }
    }

    @Test
    fun `random reselection reshuffles actual visible cards without inventing ascending descending`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, preferences, _ ->
            val repository = Injekt.get<MangaRepository>()
            val extra = repository.insertNetworkManga(
                (0 until 20).map {
                    Manga.create().copy(
                        source = 0,
                        url = "/random-$it",
                        title = "Random work ${it.toString().padStart(2, '0')}",
                    )
                },
            )
            repository.updateMembershipsAtomically(extra.map { LibraryMembershipUpdate(it.id, true, 1, emptyList()) })
            render(scene)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            click(scene, MR.strings.action_sort.localized())
            render(scene)
            click(scene, MR.strings.action_sort_random.localized())
            render(scene)
            fun actualOrder() = nodes(scene).filter {
                it.config.contains(SemanticsActions.OnLongClick) && labels(it).any { title ->
                    title.startsWith("Random work")
                }
            }.sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left })).map { node ->
                labels(node)
                    .first { it.startsWith("Random work") }
            }
            val first = actualOrder()
            val seed = preferences.randomSortSeed().get()
            assertTrue(first.size >= 6, "the fixture must mount actual root cards")
            click(scene, MR.strings.action_sort_random.localized())
            render(scene)
            assertTrue(seed != preferences.randomSortSeed().get())
            assertTrue(
                first != actualOrder(),
                "a new persisted seed must recompose and reshuffle the actual root cards",
            )
            val random = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_sort_random.localized() in labels(it)
            }
            assertFalse(labels(random).any { it.contains("↑") || it.contains("↓") })
        }
    }

    @Test
    fun `native pages retain independent scroll and focus across resize theme and large fonts`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, preferences, _ ->
            Injekt.get<DesktopAppPreferences>().themeMode.set(ThemeMode.LIGHT)
            scene.resize(320, 680)
            scene.fontScale = 2f
            render(scene)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            fun page(index: Int) = nodes(scene).single {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "library-options-page-$index"
            }
            suspend fun scroll(index: Int): Float {
                val node = page(index)
                requireNotNull(node.config[SemanticsActions.ScrollBy].action).invoke(0f, 10000f)
                render(scene)
                return page(index).config[SemanticsProperties.VerticalScrollAxisRange].value()
            }
            val filterOffset = scroll(0)
            assertTrue(filterOffset > 0)
            scene.savePng(File(visualDirectory(root), "ri05-options-filter-320-font200.png"))
            click(scene, MR.strings.action_sort.localized())
            render(scene)
            val sortOffset = scroll(1)
            assertTrue(sortOffset > 0)
            val random = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_sort_random.localized() in labels(it)
            }
            assertTrue(random.boundsInRoot.bottom <= page(1).boundsInRoot.bottom)
            click(scene, MR.strings.action_display.localized())
            render(scene)
            val displayOffset = scroll(2)
            assertTrue(displayOffset > 0)
            val last = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_display_show_number_of_items.localized() in labels(it)
            }
            assertTrue(last.boundsInRoot.top >= page(2).boundsInRoot.top)
            assertTrue(last.boundsInRoot.bottom <= page(2).boundsInRoot.bottom)
            val close = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_close.localized() in labels(it)
            }
            assertTrue(close.boundsInRoot.bottom <= 680f && close.boundsInRoot.top >= page(2).boundsInRoot.bottom)
            scene.savePng(File(visualDirectory(root), "ri05-options-display-320-font200.png"))
            requireNotNull(close.config[SemanticsActions.RequestFocus].action).invoke()
            val focus = focused(scene).id
            Injekt.get<DesktopAppPreferences>().themeMode.set(ThemeMode.DARK)
            render(scene)
            assertEquals(focus, focused(scene).id)
            assertTrue(panelTabs(scene).last().config[SemanticsProperties.Selected])
            assertEquals(displayOffset, page(2).config[SemanticsProperties.VerticalScrollAxisRange].value())
            scene.savePng(File(visualDirectory(root), "ri05-options-display-dark-320-font200.png"))
            scene.resize(1000, 680)
            render(scene)
            assertEquals(focus, focused(scene).id)
            assertTrue(panelTabs(scene).last().config[SemanticsProperties.Selected])
            click(panelTabs(scene).first())
            render(scene)
            // A wider page has a smaller range; its preserved offset is bounded by the actual range.
            assertEquals(
                filterOffset.coerceAtMost(
                    page(0).config[SemanticsProperties.VerticalScrollAxisRange]
                        .maxValue(),
                ),
                page(0).config[SemanticsProperties.VerticalScrollAxisRange].value(),
            )
            click(scene, MR.strings.action_sort.localized())
            render(scene)
            assertEquals(
                sortOffset.coerceAtMost(
                    page(1).config[SemanticsProperties.VerticalScrollAxisRange]
                        .maxValue(),
                ),
                page(1).config[SemanticsProperties.VerticalScrollAxisRange].value(),
            )
            scene.fontScale = 1f
            scene.resize(1200, 900)
            render(scene)
            scene.savePng(File(visualDirectory(root), "ri05-options-sort-dark.png"))
            key(scene, Key.Escape)
            render(scene)
            assertFalse(preferences.categoryNumberOfItems().get())
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            assertTrue(panelTabs(scene).first().config[SemanticsProperties.Selected])
            assertEquals(0f, page(0).config[SemanticsProperties.VerticalScrollAxisRange].value())
        }
    }

    @Test
    fun `more menu scopes real scheduler actions closes before random and sync uses production panel`(
        @TempDir root: File,
    ) = runBlocking {
        val updated = mutableListOf<Long>()
        withRoot(root, updateManga = { manga ->
            updated += manga.id
            LibraryUpdateChecker.UpdateResult(0)
        }) { scene, host, preferences, model ->
            val repository = Injekt.get<MangaRepository>()
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Scope A", 0, 0))
            categories.insert(Category(0, "Scope B", 1, 0))
            val cats = categories.getAll().filterNot { it.isSystemCategory }
            val extra = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(source = 0, url = "/hidden-a", title = "Hidden A", initialized = true),
                    Manga.create().copy(source = 0, url = "/hidden-b", title = "Hidden B", initialized = true),
                ),
            )
            val first = repository.getLibraryManga().first { it.manga.title == "Options work" }.manga
            repository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(first.id, true, 1, listOf(cats.first().id)),
                    LibraryMembershipUpdate(extra[0].id, true, 1, listOf(cats.first().id)),
                    LibraryMembershipUpdate(extra[1].id, true, 1, listOf(cats.last().id)),
                ),
            )
            preferences.autoUpdateMangaRestrictions().set(emptySet())
            render(scene)
            model().setSelectedCategoryIndex(model().state.value.categories.indexOfFirst { it.id == cats.first().id })
            model().setSearchQuery("Options")
            render(scene)
            click(scene, MR.strings.action_menu.localized())
            render(scene)
            val menu = listOf(
                MR.strings.action_update_library,
                MR.strings.action_update_category,
                MR.strings.desktop_ui_random_manga,
            ).map { it.localized() }
            val rows = nodes(scene).filter {
                it.config.contains(SemanticsActions.OnClick) && labels(it)
                    .any(menu::contains)
            }.sortedBy { it.boundsInRoot.top }
            assertEquals(menu, rows.map { labels(it).first(menu::contains) })
            key(scene, Key.Escape)
            render(scene)
            assertFalse(nodes(scene).flatMap(::labels).contains(menu.first()))
            assertTrue(MR.strings.action_menu.localized() in labels(focused(scene)))
            click(scene, MR.strings.action_menu.localized())
            render(scene)
            click(scene, menu[1])
            render(scene)
            repeat(100) {
                if (model().state.value.isUpdating) {
                    delay(10)
                    scene.render()
                }
            }
            assertEquals(
                setOf(first.id, extra[0].id),
                updated.toSet(),
                "category update uses its complete membership despite search",
            )
            assertFalse(nodes(scene).flatMap(::labels).contains(menu.first()))
            updated.clear()
            click(scene, MR.strings.action_menu.localized())
            render(scene)
            click(scene, menu.first())
            render(scene)
            repeat(100) {
                if (model().state.value.isUpdating) {
                    delay(10)
                    scene.render()
                }
            }
            assertEquals(
                setOf(first.id, extra[0].id, extra[1].id),
                updated.toSet(),
                "whole-library update ignores both category and search projection",
            )
            model().setSearchQuery(null)
            render(scene)
            val actions = listOf(
                MR.strings.action_search,
                MR.strings.action_filter,
                MR.strings.sync_title,
                MR.strings.action_menu,
            ).map { title ->
                nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && title.localized() in labels(it) }
            }
            assertTrue(actions.zipWithNext().all { (a, b) -> a.boundsInRoot.left < b.boundsInRoot.left })
            click(scene, MR.strings.sync_title.localized())
            render(scene)
            assertTrue(Injekt.get<mihon.data.sync.runtime.SyncRuntime>().panel.state.value.visible)
            val syncClose = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.sync_close.localized() in labels(it)
            }
            requireNotNull(syncClose.config[SemanticsActions.RequestFocus].action).invoke()
            key(scene, Key.Escape)
            render(scene)
            assertFalse(Injekt.get<mihon.data.sync.runtime.SyncRuntime>().panel.state.value.visible)
            scene.resize(320, 680)
            render(scene)
            click(scene, MR.strings.action_menu.localized())
            render(scene)
            val narrowRows = nodes(scene).filter {
                it.config.contains(SemanticsActions.OnClick) && labels(it)
                    .any(menu::contains)
            }
            assertTrue(narrowRows.all { it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= 320 })
            scene.savePng(File(visualDirectory(root), "ri05-toolbar-more-320.png"))
            click(scene, menu.last())
            render(scene)
            assertTrue(scene.stack.items.last() is MangaDetailScreen)
            assertFalse(
                nodes(scene).flatMap(::labels).contains(menu.first()),
                "random must dismiss before pushing a real detail Screen",
            )
        }
    }

    @Test
    fun `sync toolbar paints source cloud cycle icon instead of ordinary arrows`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, _ ->
            val icon = nodes(scene).filter { MR.strings.sync_title.localized() in labels(it) }.minBy {
                it.boundsInRoot.width * it.boundsInRoot.height
            }
            assertEquals(48f, icon.boundsInRoot.width)
            val actual = scene.snapshot()
            val buttonBounds = icon.boundsInRoot
            val bounds = Rect(buttonBounds.center - Offset(12f, 12f), Size(24f, 24f))
            val background = actual.getRGB(buttonBounds.left.toInt(), buttonBounds.top.toInt())
            val reference = NativeScene(Dispatchers.Unconfined)
            try {
                reference.resize(24, 24)
                reference.setContent {
                    Box(Modifier.size(24.dp).background(Color.White)) {
                        Icon(Icons.Outlined.CloudSync, null, tint = Color.Black)
                    }
                }
                render(reference)
                val expected = reference.snapshot()
                val actualMask = (0 until 24).flatMap { y ->
                    (0 until 24).map { x ->
                        actual.getRGB(bounds.left.toInt() + x, bounds.top.toInt() + y) != background
                    }
                }
                val expectedMask = (0 until 24).flatMap { y ->
                    (0 until 24).map { x ->
                        expected.getRGB(
                            x,
                            y,
                        ) != java.awt.Color.WHITE.rgb
                    }
                }
                assertEquals(expectedMask, actualMask, "actual toolbar drawing must use the SOURCE cloud-cycle vector")
            } finally {
                reference.close()
            }
        }
    }

    @Test
    fun `all ten sort rows and seven filter conditions persist from the one panel`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, preferences, model ->
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            click(scene, MR.strings.action_sort.localized())
            render(scene)
            val sorts = listOf(
                SortMode.TITLE to MR.strings.action_sort_alpha,
                SortMode.LAST_READ to MR.strings.action_sort_last_read,
                SortMode.LAST_UPDATE to MR.strings.action_sort_last_manga_update,
                SortMode.UNREAD_COUNT to MR.strings.action_sort_unread_count,
                SortMode.TOTAL_CHAPTERS to MR.strings.action_sort_total,
                SortMode.LATEST_CHAPTER to MR.strings.action_sort_latest_chapter,
                SortMode.CHAPTER_FETCH_DATE to MR.strings.action_sort_chapter_fetch_date,
                SortMode.DATE_ADDED to MR.strings.action_sort_date_added,
                SortMode.TRACKER_MEAN to MR.strings.action_sort_tracker_score,
                SortMode.RANDOM to MR.strings.action_sort_random,
            )
            for ((mode, title) in sorts) {
                fun row() = nodes(scene).first {
                    it.config.contains(SemanticsActions.OnClick) && labels(it).any { text ->
                        text.startsWith(title.localized())
                    }
                }
                click(row())
                render(scene)
                assertEquals(mode, model().state.value.sortMode)
                assertTrue(preferences.sortingMode().isSet())
                if (mode != SortMode.RANDOM) {
                    val before = preferences.sortingMode().get().direction
                    click(row())
                    render(scene)
                    assertTrue(before != preferences.sortingMode().get().direction)
                    assertTrue(labels(row()).any { it.endsWith("↑") || it.endsWith("↓") })
                }
            }
            click(panelTabs(scene).first())
            render(scene)
            val filters = listOf(
                preferences.filterDownloaded() to MR.strings.label_downloaded,
                preferences.filterUnread() to MR.strings.unread,
                preferences.filterStarted() to MR.strings.desktop_ui_started,
                preferences.filterBookmarked() to MR.strings.action_filter_bookmarked,
                preferences.filterCompleted() to MR.strings.completed,
                preferences.filterIntervalCustom() to MR.strings.desktop_ui_custom_interval,
            )
            for ((preference, title) in filters) {
                for (expected in listOf(TriState.ENABLED_IS, TriState.ENABLED_NOT, TriState.DISABLED)) {
                    click(scene, title.localized())
                    render(scene)
                    assertEquals(expected, preference.get())
                }
                click(scene, title.localized())
                render(scene)
            }
            assertTrue(
                nodes(scene).flatMap(::labels).contains(MR.strings.action_filter_tracked.localized()),
                "tracking is the seventh condition even with no active provider",
            )
            click(scene, MR.strings.action_close.localized())
            render(scene)
            for ((_, title) in filters) {
                assertTrue(
                    nodes(scene).flatMap(::labels)
                        .contains("${title.localized()}: ${MR.strings.desktop_ui_filter_include.localized()}"),
                    "all effective conditions need a visible active hint",
                )
            }
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            click(scene, MR.strings.action_display.localized())
            render(scene)
            val badge = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_display_unread_badge.localized() in labels(it)
            }
            requireNotNull(badge.config[SemanticsActions.RequestFocus].action).invoke()
            val unread = preferences.unreadBadge().get()
            key(scene, Key.Spacebar)
            render(scene)
            assertEquals(!unread, preferences.unreadBadge().get(), "Space operates the actual focused display setting")
        }
    }

    @Test
    fun `selected page paints source primary indicator independently of keyboard focus`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, _ ->
            val appPreferences = Injekt.get<DesktopAppPreferences>()
            appPreferences.appTheme.set(eu.kanade.domain.ui.model.AppTheme.DEFAULT)
            appPreferences.themeMode.set(ThemeMode.LIGHT)
            scene.resize(320, 680)
            scene.fontScale = 2f
            render(scene)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            click(scene, MR.strings.action_display.localized())
            render(scene)
            val tabs = panelTabs(scene)
            assertTrue(tabs.last().config[SemanticsProperties.Selected])
            assertTrue(tabs.first().config[SemanticsProperties.Focused], "keyboard focus may remain on another tab")
            assertEquals(
                1,
                tabs.map {
                    it.boundsInRoot.height
                }.toSet().size,
                "large-font tabs retain equal reachable height",
            )
            val pixels = scene.snapshot()
            val selected = tabs.last().boundsInRoot
            val inactive = tabs.first().boundsInRoot
            assertEquals(
                0xFF0058CA.toInt(),
                pixels.getRGB(selected.center.x.toInt(), selected.bottom.toInt() - 1),
                "SOURCE light primary must visibly mark the selected page",
            )
            assertTrue(
                pixels.getRGB(inactive.center.x.toInt(), inactive.bottom.toInt() - 1) != 0xFF0058CA.toInt(),
                "focus alone must not paint a selected indicator",
            )
        }
    }

    private fun visualDirectory(root: File) = File(
        System.getenv("MIHON_RI05_VISUAL_DIR") ?: File(root, "visual")
            .absolutePath,
    ).also { it.mkdirs() }

    private suspend fun withRoot(
        root: File,
        backendFactory: (Preferences) -> Preferences = { it },
        categoryRepositoryOverride: ((CategoryRepository) -> CategoryRepository)? = null,
        mangaSource: Long = 0,
        updateManga: (suspend (Manga) -> LibraryUpdateChecker.UpdateResult)? = null,
        block: suspend (NativeScene, VoyagerLibraryNavigationHost, LibraryPreferences, () -> LibraryScreenModel) ->
        Unit,
    ) {
        val node = Preferences.userRoot().node("mihon-tests/library-options-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            root,
            DesktopPreferenceStore(backendFactory(node)),
            startDownloadWorker = false,
            categoryRepositoryOverride = categoryRepositoryOverride,
            updateManga = updateManga,
        )
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dependencies = DesktopUiDependencies.fromInjekt()
        val repository = Injekt.get<MangaRepository>()
        val manga = repository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = mangaSource,
                    url = "/options",
                    title = "Options work",
                    initialized = true,
                ),
            ),
        ).single()
        repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(manga.id, true, 1, emptyList())))
        val scene = NativeScene(Dispatchers.Unconfined)
        val host = VoyagerLibraryNavigationHost(onStackAttached = { scene.stack = it })
        lateinit var model: LibraryScreenModel
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalDensity provides Density(1f, scene.fontScale),
                ) {
                    ProvideLibraryScreenModelFactory({ LibraryScreenModelFactory.create().also { model = it } }) {
                        ProvideLibraryNavigationHost(host) {
                            DesktopTheme {
                                Navigator(HomeScreen()) { CurrentScreen() }
                            }
                        }
                    }
                }
            }
            render(scene)
            block(scene, host, Injekt.get(), { model })
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private class FaultCategoryRepository(private val delegate: CategoryRepository) : CategoryRepository by delegate {
        var failAfterCommit = false
        override suspend fun updatePartial(update: CategoryUpdate) {
            delegate.updatePartial(update)
            failAfterWrite(update.id)
        }
        override suspend fun updateAllFlags(flags: Long?) {
            delegate.updateAllFlags(flags)
            failAfterWrite(delegate.getAll().first { !it.isSystemCategory }.id)
        }
        private suspend fun failAfterWrite(id: Long) {
            if (!failAfterCommit) return
            failAfterCommit = false
            val current = delegate.get(id)!!
            delegate.updatePartial(CategoryUpdate(id = id, flags = current.flags or 1L))
            throw java.io.IOException("sort write committed before response failure")
        }
    }

    private class FaultPreferences(
        private val backing: Preferences,
        parent: AbstractPreferences? = null,
        name: String = "",
    ) : AbstractPreferences(parent, name) {
        private var failureKey: String? = null
        private var afterWrite = false
        private var writtenKey: String? = null
        fun fail(key: String, after: Boolean) {
            failureKey = key
            afterWrite = after
        }
        override fun putSpi(key: String, value: String) {
            if (key == failureKey && !afterWrite) {
                failureKey = null
                throw SecurityException("write blocked")
            }
            backing.put(key, value)
            writtenKey = key
        }
        override fun getSpi(key: String): String? = backing.get(key, null)
        override fun removeSpi(key: String) {
            backing.remove(key)
        }
        override fun removeNodeSpi() {
            backing.removeNode()
        }
        override fun keysSpi(): Array<String> = backing.keys()
        override fun childrenNamesSpi(): Array<String> = backing.childrenNames()
        override fun childSpi(name: String): AbstractPreferences = FaultPreferences(backing.node(name), this, name)
        override fun syncSpi() {
            backing.sync()
        }
        override fun flushSpi() {
            backing.flush()
            if (writtenKey == failureKey && afterWrite) {
                failureKey = null
                throw BackingStoreException("flush blocked after write")
            }
        }
    }

    private fun key(scene: NativeScene, key: Key, shift: Boolean = false) {
        scene.sendKeyEvent(keyEvent(key, KeyEventType.KeyDown, shift))
        scene.sendKeyEvent(keyEvent(key, KeyEventType.KeyUp, shift))
    }
    private fun keyEvent(key: Key, type: KeyEventType, shift: Boolean): ComposeKeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single {
            it.name.startsWith("KeyEvent-") &&
                !it.name.endsWith("\$default")
        }
        return ComposeKeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, shift, null))
    }
    private fun focused(scene: NativeScene): SemanticsNode {
        val modal = scene.semanticsOwners.lastOrNull { owner ->
            flatten(owner.rootSemanticsNode).any {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "library-options-panel"
            }
        }
        val candidates = modal?.let { flatten(it.rootSemanticsNode) } ?: nodes(scene)
        return candidates.single {
            it.config.contains(SemanticsProperties.Focused) &&
                it.config[SemanticsProperties.Focused]
        }
    }
    private fun focusOwnerContainsPanel(scene: NativeScene, focus: SemanticsNode): Boolean =
        scene.semanticsOwners.any { owner ->
            val all = flatten(owner.rootSemanticsNode)
            all.any { it.id == focus.id } && all.any {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "library-options-panel"
            }
        }

    private class NativeScene(context: CoroutineContext) : AutoCloseable {
        val semanticsOwners = linkedSetOf<SemanticsOwner>()
        lateinit var stack: LibraryScreenStack
        private var windowSize by mutableStateOf(IntSize(1200, 900))
        var fontScale by mutableFloatStateOf(1f)
        private val bitmap = ImageBitmap(1400, 1000)
        private val canvas = Canvas(bitmap)
        private val scene = CanvasLayersComposeScene(
            size = windowSize,
            coroutineContext = context,
            platformContext = object : PlatformContext {
                override val windowInfo = object : WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize get() = windowSize
                    override val containerDpSize get() = DpSize(windowSize.width.dp, windowSize.height.dp)
                }
                override val inputModeManager = object : InputModeManager {
                    override val inputMode = InputMode.Keyboard
                    override fun requestInputMode(inputMode: InputMode) = true
                }
                override fun requestFocus() = true
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                        semanticsOwners += semanticsOwner
                    }
                    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                        semanticsOwners -= semanticsOwner
                    }
                    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
                    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
                }
            },
            invalidate = {},
        )
        fun setContent(content: @Composable () -> Unit) = scene.setContent(content)
        fun render() = scene.render(canvas, System.nanoTime())
        fun sendKeyEvent(event: ComposeKeyEvent) = scene.sendKeyEvent(event)
        fun resize(width: Int, height: Int) {
            windowSize = IntSize(width, height)
            scene.size = windowSize
        }
        fun pointer(type: PointerEventType, position: Offset, pressed: Boolean, button: PointerButton? = null) =
            scene.sendPointerEvent(
                type,
                position,
                buttons = PointerButtons(isPrimaryPressed = pressed),
                button = button,
            )
        fun snapshot(): java.awt.image.BufferedImage {
            Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                return ImageIO.read(ByteArrayInputStream(requireNotNull(image.encodeToData()).bytes))
            }
        }
        fun savePng(file: File) {
            Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                val data = requireNotNull(image.encodeToData()).bytes
                val rendered = ImageIO.read(ByteArrayInputStream(data))
                ImageIO.write(rendered.getSubimage(0, 0, windowSize.width, windowSize.height), "png", file)
            }
        }
        override fun close() = scene.close()
    }

    private suspend fun render(scene: NativeScene) {
        repeat(12) {
            scene.render()
            delay(15)
        }
    }
    private fun panelTabs(scene: NativeScene) = nodes(scene).filter {
        it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == Role.Tab &&
            labels(it).any { label ->
                label in listOf(
                    MR.strings.action_filter,
                    MR.strings.action_sort,
                    MR.strings.action_display,
                ).map { resource -> resource.localized() }
            }
    }.sortedBy { it.boundsInRoot.left }
    private fun click(scene: NativeScene, label: String) = click(
        nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && label in labels(it)
        },
    )
    private fun click(node: SemanticsNode) = requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()
    private fun nodes(scene: NativeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun labels(node: SemanticsNode): List<String> {
        val text = if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
        val descriptions = if (node.config.contains(SemanticsProperties.ContentDescription)) {
            node.config[SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
        return text + descriptions
    }
}
