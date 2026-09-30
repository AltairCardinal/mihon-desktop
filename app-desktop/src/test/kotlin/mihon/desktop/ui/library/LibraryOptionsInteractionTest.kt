package mihon.desktop.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SortMode
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.platform.OperatingSystem
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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
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
    fun `native twenty category modal scrolls bounded rows traps keys and restores category focus`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            val manga = model().state.value.allItems.single().manga
            val categories = Injekt.get<CategoryRepository>()
            repeat(22) { categories.insert(Category(0, "Category ${it.toString().padStart(2, '0')}", it.toLong(), 0)) }
            val last = categories.getAll().last()
            scene.resize(320, 680)
            scene.fontScale = 2f
            render(scene)
            selectWithMouse(scene, manga.title)
            click(scene, MR.strings.action_move_category.localized())
            render(scene)
            fun modalNodes() = flatten(scene.semanticsOwners.last().rootSemanticsNode)
            assertFalse(
                modalNodes().any {
                    MR.strings.label_default.localized() in labels(it)
                },
                "system default is not a batch choice",
            )
            val scroll = modalNodes().firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
            assertTrue(scroll != null, "actual long category dialog must expose a working native scroll container")
            requireNotNull(requireNotNull(scroll).config[SemanticsActions.ScrollBy].action).invoke(0f, 10_000f)
            repeat(3) { render(scene) }
            val lastRow = modalNodes().first {
                last.name in labels(it) &&
                    it.config.contains(SemanticsProperties.ToggleableState)
            }
            val cancel = modalNodes().single {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_cancel.localized() in labels(it)
            }
            assertTrue(lastRow.boundsInRoot.top >= 0f)
            assertTrue(
                lastRow.boundsInRoot.bottom <= cancel.boundsInRoot.top,
                "last row must stay inside the actual dialog body",
            )
            assertTrue(cancel.boundsInRoot.bottom <= 680f)
            requireNotNull(lastRow.config[SemanticsActions.RequestFocus].action).invoke()
            key(scene, Key.Spacebar)
            render(scene)
            assertEquals(
                ToggleableState.On,
                modalNodes().single {
                    last.name in labels(it) && it.config.contains(SemanticsProperties.ToggleableState)
                }.config[SemanticsProperties.ToggleableState],
                "Space changes only the current dialog draft",
            )
            key(scene, Key.Spacebar)
            render(scene)
            assertEquals(emptySet<Long>(), model().categoryIdsForManga(manga.id))
            requireNotNull(cancel.config[SemanticsActions.RequestFocus].action).invoke()
            val cancelId = cancel.id
            for (backward in listOf(false, true)) {
                val visited = mutableSetOf<Int>()
                var wrapped = false
                for (step in 0 until 70) {
                    key(scene, Key.Tab, shift = backward)
                    render(scene)
                    val focus = modalNodes().single { it.config.getOrElse(SemanticsProperties.Focused) { false } }
                    visited += focus.id
                    assertFalse(MR.strings.desktop_ui_clear_selection.localized() in labels(focus))
                    if (focus.id == cancelId) {
                        wrapped = true
                        break
                    }
                }
                assertTrue(wrapped, "real modal focus must wrap in both directions")
                assertTrue(visited.size >= 3)
            }
            key(scene, Key.Escape)
            render(scene)
            assertFalse(model().state.value.showBatchCategoryDialog, "Escape closes only the actual category dialog")
            assertEquals(1, scene.semanticsOwners.size, "the category dialog owner is removed")
            assertTrue(nodes(scene).any { MR.strings.desktop_ui_clear_selection.localized() in labels(it) })
            assertTrue(
                nodes(scene).any {
                    MR.strings.action_move_category.localized() in labels(it) &&
                        it.config.getOrElse(SemanticsProperties.Focused) { false }
                },
                "Escape returns to the real category trigger: " +
                    nodes(scene).filter {
                        it.config.getOrElse(SemanticsProperties.Focused) { false }
                    }.map { flatten(it).flatMap(::labels) },
            )
            assertEquals(emptySet<Long>(), model().categoryIdsForManga(manga.id))
            click(scene, MR.strings.action_move_category.localized())
            render(scene)
            val reopenedScroll = modalNodes().single { it.config.contains(SemanticsActions.ScrollBy) }
            requireNotNull(reopenedScroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 10_000f)
            repeat(3) { render(scene) }
            val target = modalNodes().first {
                last.name in labels(it) &&
                    it.config.contains(SemanticsProperties.ToggleableState)
            }
            click(target)
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(
                setOf(last.id),
                model().categoryIdsForManga(manga.id),
                "the reachable last row writes the actual SQL membership",
            )
        }
    }

    @Test
    fun `native batch category whole rows preserve mixed membership and discard canceled drafts`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            val repository = Injekt.get<MangaRepository>()
            val first = model().state.value.allItems.single().manga
            val second = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/mixed", title = "Mixed work", initialized = true)),
            ).single()
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Mixed A", 0, 0))
            categories.insert(Category(0, "Mixed B", 1, 0))
            val custom = categories.getAll().filterNot(Category::isSystemCategory)
            repository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(first.id, true, 1, custom.map { it.id }),
                    LibraryMembershipUpdate(second.id, true, 1, listOf(custom[1].id)),
                ),
            )
            render(scene)
            selectWithMouse(scene, first.title)
            click(scene, "Mixed B")
            render(scene)
            selectWithMouse(scene, second.title)
            assertEquals(custom.map { it.id }.toSet(), model().categoryIdsForManga(first.id))
            assertEquals(setOf(custom[1].id), model().categoryIdsForManga(second.id))
            click(scene, MR.strings.action_move_category.localized())
            render(scene)
            repeat(20) {
                if (nodes(scene).none { it.config.contains(SemanticsProperties.ToggleableState) }) render(scene)
            }
            assertTrue(
                nodes(scene).any { it.config.contains(SemanticsProperties.ToggleableState) },
                "the actual dialog loads its current category memberships",
            )
            fun firstState() = nodes(scene).first { it.config.contains(SemanticsProperties.ToggleableState) }
                .config[SemanticsProperties.ToggleableState]
            suspend fun rowClick() {
                val text = flatten(scene.semanticsOwners.last().rootSemanticsNode).first {
                    it.config.contains(SemanticsProperties.Text) && "Mixed A" in labels(it)
                }
                val point = text.boundsInRoot.center
                scene.pointer(PointerEventType.Press, point, true, PointerButton.Primary)
                scene.pointer(PointerEventType.Release, point, false, PointerButton.Primary)
                render(scene)
            }
            assertEquals(ToggleableState.Indeterminate, firstState())
            assertEquals(
                ToggleableState.On,
                nodes(scene).single {
                    it.config.contains(SemanticsProperties.ToggleableState) && "Mixed B" in labels(it)
                }.config[SemanticsProperties.ToggleableState],
            )
            key(scene, Key.Escape)
            render(scene)
            assertFalse(model().state.value.showBatchCategoryDialog, "C21 Escape closes the actual dialog")
            assertEquals(1, scene.semanticsOwners.size)
            assertTrue(nodes(scene).any { MR.strings.desktop_ui_clear_selection.localized() in labels(it) })
            assertTrue(
                nodes(scene).any {
                    MR.strings.action_move_category.localized() in labels(it) &&
                        it.config.getOrElse(SemanticsProperties.Focused) { false }
                },
                "C21 Escape restores the category trigger: " +
                    nodes(scene).filter {
                        it.config.getOrElse(SemanticsProperties.Focused) { false }
                    }.map { flatten(it).flatMap(::labels) },
            )
            assertEquals(custom.map { it.id }.toSet(), model().categoryIdsForManga(first.id))
            assertEquals(setOf(custom[1].id), model().categoryIdsForManga(second.id))
            click(scene, MR.strings.action_move_category.localized())
            render(scene)

            for (expected in listOf(ToggleableState.Off, ToggleableState.On, ToggleableState.Indeterminate)) {
                val checkbox = nodes(scene).first { it.config.contains(SemanticsProperties.ToggleableState) }
                scene.pointer(PointerEventType.Press, checkbox.boundsInRoot.center, true, PointerButton.Primary)
                scene.pointer(PointerEventType.Release, checkbox.boundsInRoot.center, false, PointerButton.Primary)
                render(scene)
                assertEquals(
                    expected,
                    firstState(),
                    "real native checkbox pointer establishes the dialog coordinate fixture",
                )
            }
            for (expected in listOf(ToggleableState.Off, ToggleableState.On, ToggleableState.Indeterminate)) {
                rowClick()
                assertEquals(expected, firstState(), "the actual category label row uses the SOURCE mixed cycle")
            }
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(custom.map { it.id }.toSet(), model().categoryIdsForManga(first.id))
            assertEquals(
                setOf(custom[1].id),
                model().categoryIdsForManga(second.id),
                "mixed confirmation preserves each actual member",
            )
            selectWithMouse(scene, second.title)
            click(scene, "Mixed A")
            render(scene)
            selectWithMouse(scene, first.title)
            click(scene, MR.strings.action_move_category.localized())
            render(scene)
            rowClick()
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertTrue(nodes(scene).any { MR.strings.desktop_ui_clear_selection.localized() in labels(it) })
            assertEquals(custom.map { it.id }.toSet(), model().categoryIdsForManga(first.id))
            assertEquals(setOf(custom[1].id), model().categoryIdsForManga(second.id))
            click(scene, MR.strings.action_move_category.localized())
            render(scene)
            rowClick()
            click(scene, MR.strings.action_edit_categories.localized())
            render(scene)
            assertTrue(scene.stack.items.last() is CategoryManagementScreen)
            click(scene, MR.strings.action_bar_up_description.localized())
            render(scene)
            assertTrue(scene.stack.items.last() is LibraryRootScreen)
            assertTrue(nodes(scene).any { MR.strings.action_search.localized() in labels(it) })
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ToggleableState) })
            assertEquals(custom.map { it.id }.toSet(), model().categoryIdsForManga(first.id))
            assertEquals(setOf(custom[1].id), model().categoryIdsForManga(second.id))
        }
    }

    @Test
    fun `stale root body long and continue callbacks cannot revive filtered or removed targets`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, preferences, model ->
            preferences.unreadBadge().set(false)
            preferences.showContinueReadingButton().set(true)
            val repository = Injekt.get<MangaRepository>()
            val first = model().state.value.allItems.single().manga
            val second = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/valid", title = "Valid work", initialized = true)),
            ).single()
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(second.id, true, 1, emptyList())))
            Injekt.get<ChapterRepository>().addAll(
                listOf(first, second).map { Chapter.create().copy(mangaId = it.id, name = "Unread", url = "/unread") },
            )
            render(scene)
            val staleBody = nodes(scene).first {
                it.config.contains(SemanticsActions.OnLongClick) &&
                    first.title in labels(it)
            }
            val bodyClick = requireNotNull(staleBody.config[SemanticsActions.OnClick].action)
            val longClick = requireNotNull(staleBody.config[SemanticsActions.OnLongClick].action)
            val resumeClick = requireNotNull(
                nodes(scene).first {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.desktop_ui_continue_reading.localized() in labels(it)
                }
                    .config[SemanticsActions.OnClick].action,
            )
            selectWithMouse(scene, second.title)
            model().setSearchQuery(second.title)
            render(scene)
            for (callback in listOf(bodyClick, longClick, resumeClick)) {
                callback()
                render(scene)
                assertTrue(scene.stack.items.last() is LibraryRootScreen)
                val bar = nodes(scene).single {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } ==
                        "library-selection-top-bar"
                }
                assertTrue(
                    flatten(bar).any {
                        "1" in labels(it)
                    },
                    "a stale callback must preserve only the one valid selection",
                )
            }
            click(scene, MR.strings.desktop_ui_clear_selection.localized())
            render(scene)
            model().setSearchQuery(null)
            render(scene)
            val selected = nodes(scene).filter {
                it.config.contains(SemanticsActions.OnLongClick) &&
                    it.config.getOrElse(SemanticsProperties.Selected) { false }
            }.flatMap(::labels)
            assertFalse(first.title in selected, "stale filter callbacks must not select a now-hidden target")
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(first.id, false, 1, emptyList())))
            render(scene)
            for (callback in listOf(bodyClick, longClick, resumeClick)) {
                callback()
                render(scene)
                assertTrue(
                    scene.stack.items.last() is LibraryRootScreen,
                    "a removed object can neither select nor open detail or Reader",
                )
            }
            assertTrue(nodes(scene).any { MR.strings.action_search.localized() in labels(it) })
            val end = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/end", title = "Zulu end", initialized = true)),
            ).single()
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Stale A", 0, 0))
            categories.insert(Category(0, "Stale B", 1, 0))
            val custom = categories.getAll().filterNot(Category::isSystemCategory)
            repository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(first.id, true, 1, custom.map { it.id }),
                    LibraryMembershipUpdate(second.id, true, 1, listOf(custom[1].id)),
                    LibraryMembershipUpdate(end.id, true, 1, listOf(custom[1].id)),
                ),
            )
            render(scene)
            click(scene, "Stale B")
            render(scene)
            selectWithMouse(scene, second.title)
            bodyClick()
            render(scene)
            mouseSelect(scene, end.title, PointerKeyboardModifiers(isShiftPressed = true))
            val currentSelection = nodes(scene).filter {
                it.config.contains(SemanticsActions.OnLongClick) &&
                    it.config.getOrElse(SemanticsProperties.Selected) { false }
            }.flatMap(::labels)
            assertTrue(
                listOf(first.title, second.title, end.title).all { it in currentSelection },
                "an old valid callback must use the current category anchor and real sorted visible range",
            )
        }
    }

    @Test
    fun `accepted mark completion never clears a later mouse selection and its actual SQL target stays frozen`(
        @TempDir root: File,
    ) = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withRoot(
            root,
            chapterRepositoryOverride = { delegate ->
                object : ChapterRepository by delegate {
                    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
                        entered.complete(Unit)
                        release.await()
                        delegate.updateAll(chapterUpdates)
                    }
                }
            },
        ) { scene, _, _, model ->
            val repository = Injekt.get<MangaRepository>()
            val first = model().state.value.allItems.single().manga
            val second = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/later", title = "Later selection", initialized = true)),
            ).single()
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(second.id, true, 1, emptyList())))
            val chapters = Injekt.get<ChapterRepository>()
            chapters.addAll(
                listOf(first, second).map { Chapter.create().copy(mangaId = it.id, name = "Unread", url = "/unread") },
            )
            render(scene)
            selectWithMouse(scene, first.title)
            click(scene, MR.strings.action_mark_as_read.localized())
            try {
                withTimeout(5_000) { entered.await() }
                click(scene, MR.strings.desktop_ui_clear_selection.localized())
                render(scene)
                selectWithMouse(scene, second.title)
            } finally {
                release.complete(Unit)
            }
            repeat(3) { render(scene) }
            assertTrue(chapters.getChapterByMangaId(first.id).single().read)
            assertFalse(
                chapters.getChapterByMangaId(second.id).single().read,
                "completion never broadens its frozen DB working set",
            )
            val selected = nodes(scene).filter {
                it.config.contains(SemanticsActions.OnLongClick) &&
                    it.config.getOrElse(SemanticsProperties.Selected) { false }
            }.flatMap(::labels)
            assertTrue(second.title in selected, "old successful completion must retain the later selection session")
            assertTrue(nodes(scene).any { MR.strings.desktop_ui_clear_selection.localized() in labels(it) })
        }
    }

    @Test
    fun `root cross category ranges preserve hidden members and real removal prunes only invalid library IDs`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, preferences, model ->
            val repository = Injekt.get<MangaRepository>()
            val original = model().state.value.allItems.single().manga
            val extra = repository.insertNetworkManga(
                (1..3).map {
                    Manga.create().copy(source = 0, url = "/range-$it", title = "Range $it", initialized = true)
                },
            )
            val mangas = listOf(original) + extra
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Range A", 0, 0))
            categories.insert(Category(0, "Range B", 1, 0))
            val custom = categories.getAll().filterNot(Category::isSystemCategory)
            repository.updateMembershipsAtomically(
                mangas.mapIndexed { index, manga ->
                    LibraryMembershipUpdate(manga.id, true, 1, listOf(custom[if (index < 2) 0 else 1].id))
                },
            )
            preferences.displayMode().set(tachiyomi.domain.library.model.LibraryDisplayMode.List)
            render(scene)
            selectWithMouse(scene, original.title)
            mouseSelect(scene, extra[0].title, PointerKeyboardModifiers(isShiftPressed = true))
            click(scene, "Range B")
            render(scene)
            mouseSelect(scene, extra[2].title, PointerKeyboardModifiers(isShiftPressed = true))
            fun selectedTitles() = nodes(scene).filter {
                it.config.contains(SemanticsActions.OnLongClick) &&
                    it.config.getOrElse(SemanticsProperties.Selected) { false }
            }.flatMap(::labels).toSet()
            assertTrue(extra[2].title in selectedTitles())
            assertFalse(extra[1].title in selectedTitles(), "cross category anchor rebuilds at the target")
            mouseSelect(scene, extra[1].title, PointerKeyboardModifiers(isShiftPressed = true))
            assertTrue(extra[1].title in selectedTitles() && extra[2].title in selectedTitles())
            click(scene, MR.strings.desktop_ui_invert_selection.localized())
            render(scene)
            assertTrue(
                nodes(scene).any {
                    MR.strings.desktop_ui_clear_selection.localized() in labels(it)
                },
                "hidden A selection survives inversion of B",
            )
            assertFalse(extra[1].title in selectedTitles() || extra[2].title in selectedTitles())
            click(scene, "Range A")
            render(scene)
            assertTrue(original.title in selectedTitles() && extra[0].title in selectedTitles())
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(original.id, false, 1, emptyList())))
            render(scene)
            assertFalse(original.title in selectedTitles())
            click(scene, "Range B")
            render(scene)
            assertTrue(
                nodes(scene).any {
                    MR.strings.desktop_ui_clear_selection.localized() in labels(it)
                },
                "hidden valid A selection is not pruned by B visibility",
            )
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(extra[0].id, false, 1, emptyList())))
            render(scene)
            assertTrue(
                nodes(scene).any {
                    MR.strings.action_search.localized() in labels(it)
                },
                "removing the last actual favorite leaves no invalid selection",
            )
            assertFalse(model().state.value.isUpdating)
        }
    }

    @Test
    fun `root six download choices enqueue real remote chapter snapshots and failure keeps mixed retry selection`(
        @TempDir root: File,
    ) = runBlocking {
        var rejectRead = false
        withRoot(root, mangaSource = 99, chapterRepositoryOverride = { delegate ->
            object : ChapterRepository by delegate {
                override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                    val actual = delegate.getChapterByMangaId(mangaId, applyScanlatorFilter)
                    if (rejectRead) throw java.io.IOException("chapter read refused")
                    return actual
                }
            }
        }) { scene, _, _, model ->
            val repository = Injekt.get<MangaRepository>()
            val remote = model().state.value.allItems.single().manga
            val local = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/local", title = "Local selected", initialized = true)),
            ).single()
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(local.id, true, 1, emptyList())))
            val chapters = Injekt.get<ChapterRepository>()
            chapters.addAll(
                listOf(remote, local).flatMap { manga ->
                    (1..30).map { number ->
                        Chapter.create().copy(
                            mangaId = manga.id,
                            name = "Chapter $number",
                            url = "/chapter/$number",
                            chapterNumber = number.toDouble(),
                            sourceOrder = (30 - number).toLong(),
                            bookmark = number > 27,
                        )
                    }
                },
            )
            val manager = Injekt.get<mihon.desktop.download.DesktopDownloadManager>()
            render(scene)
            selectWithMouse(scene, remote.title)
            selectWithMouse(scene, local.title)
            click(scene, MR.strings.action_download.localized())
            render(scene)
            rejectRead = true
            try {
                click(scene, MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 1))
                render(scene)
                assertTrue(manager.queue.value.isEmpty())
                assertTrue(model().state.value.batchCategoryResultMessage != null)
                assertTrue(
                    nodes(scene).any { MR.strings.desktop_ui_clear_selection.localized() in labels(it) },
                    "real chapter read failure retains the complete valid mixed retry selection",
                )
            } finally {
                rejectRead = false
            }
            val choices = listOf(
                MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 1) to 1,
                MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 5) to 5,
                MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 10) to 10,
                MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 25) to 25,
                MR.strings.desktop_ui_all_unread_chapters.localized() to 30,
                MR.strings.desktop_ui_bookmarked_chapters.localized() to 3,
            )
            for ((index, choice) in choices.withIndex()) {
                if (index > 0) {
                    selectWithMouse(scene, remote.title)
                    selectWithMouse(scene, local.title)
                }
                click(scene, MR.strings.action_download.localized())
                render(scene)
                assertEquals(
                    6,
                    nodes(scene).count {
                        it.config.contains(SemanticsActions.OnClick) &&
                            choices.any { choice -> choice.first in labels(it) }
                    },
                )
                click(scene, choice.first)
                render(scene)
                val queued = manager.queue.value
                assertEquals(choice.second, queued.size, "actual Root choice $index reaches the real manager")
                val persisted = tachiyomi.data.download.PersistentDownloadStore(
                    Injekt.get<tachiyomi.data.Database>(),
                ).entries()
                assertEquals(queued.map { it.chapterId }.toSet(), persisted.map { it.chapterId }.toSet())
                assertTrue(queued.all { it.mangaId == remote.id && it.sourceId == 99L })
                assertTrue(queued.all { it.chapterId in chapters.getChapterByMangaId(remote.id).map { it.id } })
                if (index == choices.lastIndex) {
                    assertTrue(
                        queued.all {
                            it.chapterId in
                                chapters.getChapterByMangaId(remote.id).filter { it.bookmark }.map { it.id }
                        },
                    )
                }
                assertTrue(
                    nodes(scene).any { MR.strings.action_search.localized() in labels(it) },
                    "accepted enqueue clears selection",
                )
                assertTrue(manager.cancelAndAwaitRetirements(queued.map { it.chapterId }))
                render(scene)
            }
        }
    }

    @Test
    fun `delete downloads only is a bounded native modal cancel is inert and Escape restores the actual More trigger`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root, mangaSource = 99) { scene, _, _, model ->
            val manga = model().state.value.allItems.single().manga
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, name = "Downloaded", url = "/downloaded")),
            ).single()
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val directory = provider.chapterDownloadDir(manga.source, manga.title, chapter.name)
            directory.mkdirs()
            ImageIO.write(
                java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png",
                File(directory, "001.png"),
            )
            render(scene)
            selectWithMouse(scene, manga.title)
            suspend fun openDelete() {
                click(scene, MR.strings.action_menu.localized())
                render(scene)
                click(scene, MR.strings.action_delete.localized())
                render(scene)
            }
            openDelete()
            fun modal() = flatten(scene.semanticsOwners.last().rootSemanticsNode)
            assertEquals(2, modal().count { it.config.contains(SemanticsProperties.ToggleableState) })
            val cancel = modal().single {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_cancel.localized() in labels(it)
            }
            requireNotNull(cancel.config[SemanticsActions.RequestFocus].action).invoke()
            for (backward in listOf(false, true)) {
                val visited = mutableSetOf<Int>()
                var wrapped = false
                repeat(8) {
                    if (!wrapped) {
                        key(scene, Key.Tab, shift = backward)
                        render(scene)
                        val focus = modal().single { it.config.getOrElse(SemanticsProperties.Focused) { false } }
                        visited += focus.id
                        wrapped = focus.id == cancel.id
                    }
                }
                assertTrue(wrapped)
                assertTrue(visited.size >= 3)
            }
            val background = nodes(scene).first {
                it.config.contains(SemanticsActions.OnLongClick) &&
                    manga.title in labels(it)
            }
            scene.pointer(PointerEventType.Press, background.boundsInRoot.center, true, PointerButton.Primary)
            scene.pointer(PointerEventType.Release, background.boundsInRoot.center, false, PointerButton.Primary)
            render(scene)
            assertTrue(scene.stack.items.last() is LibraryRootScreen, "a modal blocks the real background card")
            assertEquals(1, scene.semanticsOwners.size, "outside click dismisses without operating the background")
            assertTrue(Injekt.get<MangaRepository>().getMangaById(manga.id).favorite)
            assertTrue(provider.isChapterDownloaded(manga.source, manga.title, chapter.name))
            assertTrue(nodes(scene).any { MR.strings.desktop_ui_clear_selection.localized() in labels(it) })
            openDelete()
            val downloads = modal().filter { it.config.contains(SemanticsProperties.ToggleableState) }.last()
            requireNotNull(downloads.config[SemanticsActions.RequestFocus].action).invoke()
            key(scene, Key.Spacebar)
            render(scene)
            assertEquals(
                ToggleableState.On,
                modal().filter {
                    it.config.contains(SemanticsProperties.ToggleableState)
                }.last().config[SemanticsProperties.ToggleableState],
            )
            key(scene, Key.Escape)
            render(scene)
            assertEquals(1, scene.semanticsOwners.size, "Escape closes just the removal dialog")
            assertTrue(
                nodes(scene).any {
                    MR.strings.action_menu.localized() in labels(it) &&
                        it.config.getOrElse(SemanticsProperties.Focused) { false }
                },
            )
            assertTrue(Injekt.get<MangaRepository>().getMangaById(manga.id).favorite)
            assertTrue(provider.isChapterDownloaded(manga.source, manga.title, chapter.name))
            openDelete()
            click(modal().filter { it.config.contains(SemanticsProperties.ToggleableState) }.last())
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertTrue(
                provider.isChapterDownloaded(manga.source, manga.title, chapter.name),
                "Cancel discards the draft",
            )
            openDelete()
            click(modal().filter { it.config.contains(SemanticsProperties.ToggleableState) }.last())
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertFalse(provider.isChapterDownloaded(manga.source, manga.title, chapter.name))
            assertTrue(
                Injekt.get<MangaRepository>().getMangaById(manga.id).favorite,
                "downloads-only keeps actual membership",
            )
            assertTrue(nodes(scene).any { MR.strings.action_search.localized() in labels(it) })
        }
    }

    @Test
    fun `opened deletion freezes IDs and partial real file failure prunes removed IDs but keeps hidden valid retries`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            val repository = Injekt.get<MangaRepository>()
            val first = model().state.value.allItems.single().manga
            val second = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/retry", title = "Retry hidden", initialized = true)),
            ).single()
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(second.id, true, 1, emptyList())))
            val covers = Injekt.get<mihon.desktop.domain.DesktopCustomCoverStore>()
            val image = java.io.ByteArrayOutputStream()
            ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image)
            covers.write(first.id, image.toByteArray())
            val oldCover = covers.getCustomCoverFile(first.id)
            render(scene)
            selectWithMouse(scene, first.title)
            selectWithMouse(scene, second.title)
            click(scene, MR.strings.action_delete.localized())
            render(scene)
            val newcomer = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 0,
                        url = "/new",
                        title = "New after confirmation opens",
                        initialized = true,
                    ),
                ),
            ).single()
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(newcomer.id, true, 1, emptyList())))
            model().setSearchQuery(first.title)
            render(scene)
            val driver = Injekt.get<SqlDriver>()
            driver.execute(
                null,
                """CREATE TRIGGER reject_one_delete BEFORE UPDATE OF favorite ON mangas
                WHEN NEW._id = ${second.id} AND NEW.favorite = 0
                BEGIN SELECT RAISE(ABORT, 'retry membership refused'); END""",
                0,
            )
            val restoreFile = denyCoverDeletion(oldCover)
            try {
                click(nodes(scene).single { it.config.contains(SemanticsProperties.ToggleableState) })
                click(scene, MR.strings.action_ok.localized())
                render(scene)
                assertFalse(
                    repository.getMangaById(first.id).favorite,
                    "membership committed before the real file failure",
                )
                assertTrue(oldCover.exists(), "the actual locked custom file refused deletion")
                assertTrue(repository.getMangaById(second.id).favorite)
                assertTrue(
                    repository.getMangaById(newcomer.id).favorite,
                    "an opened confirmation never broadens its frozen ID set",
                )
                assertTrue(model().state.value.operationFeedback != null)
                val bar = nodes(scene).single {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } ==
                        "library-selection-top-bar"
                }
                assertEquals(
                    listOf("1"),
                    flatten(bar).filter { it.config.contains(SemanticsProperties.Text) }.flatMap(::labels),
                    "only the hidden but still-favorite retry survives; removed objects are not restored",
                )
            } finally {
                driver.execute(null, "DROP TRIGGER reject_one_delete", 0)
                restoreFile()
            }
            model().setSearchQuery(null)
            render(scene)
            val selected = nodes(scene).filter {
                it.config.contains(SemanticsActions.OnLongClick) &&
                    it.config.getOrElse(SemanticsProperties.Selected) { false }
            }.flatMap(::labels)
            assertTrue(second.title in selected)
            assertFalse(first.title in selected || newcomer.title in selected)
            click(scene, MR.strings.action_delete.localized())
            render(scene)
            click(nodes(scene).single { it.config.contains(SemanticsProperties.ToggleableState) })
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertFalse(repository.getMangaById(second.id).favorite)
            assertTrue(repository.getMangaById(newcomer.id).favorite)
            assertTrue(nodes(scene).any { MR.strings.action_search.localized() in labels(it) })
        }
    }

    private fun denyCoverDeletion(file: File): () -> Unit {
        val path = file.toPath()
        if (OperatingSystem.detect() == OperatingSystem.WINDOWS) {
            val handle = java.nio.channels.FileChannel.open(
                path,
                java.nio.file.StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE,
            )
            return { handle.close() }
        }
        val parent = path.parent
        val original = java.nio.file.Files.getPosixFilePermissions(parent)
        val writes = setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
        )
        java.nio.file.Files.setPosixFilePermissions(parent, original - writes)
        return { java.nio.file.Files.setPosixFilePermissions(parent, original) }
    }

    @Test
    fun `batch mark SQLite refusal keeps valid selection feedback and retry uses the same production action`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            val manga = model().state.value.allItems.single().manga
            val chapters = Injekt.get<ChapterRepository>()
            chapters.addAll(listOf(Chapter.create().copy(mangaId = manga.id, name = "Unread", url = "/unread")))
            render(scene)
            selectWithMouse(scene, manga.title)
            val driver = Injekt.get<SqlDriver>()
            driver.execute(
                null,
                """CREATE TRIGGER reject_batch_read BEFORE UPDATE OF read ON chapters
                BEGIN SELECT RAISE(ABORT, 'read refused'); END""",
                0,
            )
            try {
                click(scene, MR.strings.action_mark_as_read.localized())
                render(scene)
                assertFalse(chapters.getChapterByMangaId(manga.id).single().read)
                assertTrue(model().state.value.operationFeedback != null)
                assertTrue(
                    nodes(scene).any {
                        it.config.contains(SemanticsActions.OnClick) &&
                            MR.strings.desktop_ui_clear_selection.localized() in labels(it)
                    },
                    "failed real DB write must retain retry selection",
                )
            } finally {
                driver.execute(null, "DROP TRIGGER reject_batch_read", 0)
            }
            click(scene, MR.strings.action_mark_as_read.localized())
            render(scene)
            assertTrue(chapters.getChapterByMangaId(manga.id).single().read)
            assertTrue(nodes(scene).any { MR.strings.action_search.localized() in labels(it) })
        }
    }

    @Test
    fun `batch categories SQLite refusal preserves assignment selection and successful retry clears it`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            val manga = model().state.value.allItems.single().manga
            val repository = Injekt.get<CategoryRepository>()
            repository.insert(Category(0, "Retry category", 0, 0))
            val category = repository.getAll().single { !it.isSystemCategory }
            render(scene)
            selectWithMouse(scene, manga.title)
            val driver = Injekt.get<SqlDriver>()
            driver.execute(
                null,
                """CREATE TRIGGER reject_batch_category BEFORE INSERT ON mangas_categories
                BEGIN SELECT RAISE(ABORT, 'category refused'); END""",
                0,
            )
            try {
                click(scene, MR.strings.action_move_category.localized())
                render(scene)
                click(nodes(scene).single { it.config.contains(SemanticsProperties.ToggleableState) })
                click(scene, MR.strings.action_ok.localized())
                render(scene)
                assertEquals(emptySet<Long>(), model().categoryIdsForManga(manga.id))
                assertTrue(model().state.value.batchCategoryResultMessage != null)
                assertTrue(
                    nodes(scene).any {
                        it.config.contains(SemanticsActions.OnClick) &&
                            MR.strings.desktop_ui_clear_selection.localized() in labels(it)
                    },
                    "category rejection must not clear retry selection",
                )
            } finally {
                driver.execute(null, "DROP TRIGGER reject_batch_category", 0)
            }
            click(scene, MR.strings.action_move_category.localized())
            render(scene)
            click(nodes(scene).single { it.config.contains(SemanticsProperties.ToggleableState) })
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(setOf(category.id), model().categoryIdsForManga(manga.id))
            assertTrue(nodes(scene).any { MR.strings.action_search.localized() in labels(it) })
        }
    }

    @Test
    fun `batch delete SQLite refusal retains favorite and selection then confirmation retry removes only its target`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            val manga = model().state.value.allItems.single().manga
            val repository = Injekt.get<MangaRepository>()
            selectWithMouse(scene, manga.title)
            val driver = Injekt.get<SqlDriver>()
            driver.execute(
                null,
                """CREATE TRIGGER reject_batch_delete BEFORE UPDATE OF favorite ON mangas
                WHEN NEW.favorite = 0 BEGIN SELECT RAISE(ABORT, 'membership refused'); END""",
                0,
            )
            try {
                click(scene, MR.strings.action_delete.localized())
                render(scene)
                val confirm = nodes(scene).single {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.action_ok.localized() in labels(it)
                }
                assertTrue(confirm.config.contains(SemanticsProperties.Disabled), "no removal choice means no confirm")
                assertFalse(nodes(scene).any { MR.strings.downloaded_chapters.localized() in labels(it) })
                click(nodes(scene).single { it.config.contains(SemanticsProperties.ToggleableState) })
                click(scene, MR.strings.action_ok.localized())
                render(scene)
                assertTrue(repository.getMangaById(manga.id).favorite)
                assertTrue(model().state.value.operationFeedback != null)
                assertTrue(
                    nodes(scene).any {
                        it.config.contains(SemanticsActions.OnClick) &&
                            MR.strings.desktop_ui_clear_selection.localized() in labels(it)
                    },
                    "membership refusal must keep the valid retry object",
                )
            } finally {
                driver.execute(null, "DROP TRIGGER reject_batch_delete", 0)
            }
            click(scene, MR.strings.action_delete.localized())
            render(scene)
            click(nodes(scene).single { it.config.contains(SemanticsProperties.ToggleableState) })
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertFalse(repository.getMangaById(manga.id).favorite)
            assertTrue(nodes(scene).any { MR.strings.action_search.localized() in labels(it) })
        }
    }

    @Test
    fun `native source selection bars show pure count ordered actions and More escapes before selection`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root, mangaSource = 99) { scene, _, _, _ ->
            scene.resize(320, 680)
            scene.fontScale = 2f
            render(scene)
            selectWithMouse(scene, "Options work")
            val bar = nodes(scene).single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "library-selection-top-bar"
            }
            assertEquals(
                listOf("1"),
                flatten(bar).filter {
                    it.config.contains(SemanticsProperties.Text)
                }.flatMap(::labels),
            )
            val titles = listOf(
                MR.strings.action_move_category,
                MR.strings.action_mark_as_read,
                MR.strings.action_mark_as_unread,
                MR.strings.action_download,
                MR.strings.action_menu,
            ).map { it.localized() }
            val buttons = titles.map { title ->
                nodes(scene).single { it.config.contains(SemanticsActions.OnClick) && title in labels(it) }
            }
            assertTrue(
                buttons.zipWithNext().all { (first, second) ->
                    first.boundsInRoot.right <=
                        second.boundsInRoot.left
                },
            )
            val navigation = nodes(scene).single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } ==
                    "desktop-root-bar"
            }
            assertTrue(buttons.all { it.boundsInRoot.bottom <= navigation.boundsInRoot.top })
            assertFalse(
                nodes(scene).any {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.action_search.localized() in labels(it)
                },
            )
            assertFalse(
                nodes(scene).any {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.action_filter.localized() in labels(it)
                },
            )
            assertFalse(
                nodes(scene).any {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.sync_title.localized() in labels(it)
                },
            )
            click(buttons.last())
            render(scene)
            assertTrue(
                nodes(scene).any {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.migrate.localized() in labels(it)
                },
            )
            assertTrue(
                nodes(scene).any {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.action_delete.localized() in labels(it)
                },
            )
            key(scene, Key.Escape)
            render(scene)
            assertTrue(nodes(scene).any { "1" in labels(it) })
            assertTrue(MR.strings.action_menu.localized() in labels(focused(scene)))
            key(scene, Key.Escape)
            render(scene)
            assertTrue(
                nodes(scene).any {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.action_search.localized() in labels(it)
                },
            )
        }
    }

    @Test
    fun `all local selection exposes migrate delete directly and mixed selection keeps remote download`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, _ ->
            val repository = Injekt.get<MangaRepository>()
            val remote = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 99, url = "/remote", title = "Remote work", initialized = true)),
            ).single()
            repository.updateMembershipsAtomically(listOf(LibraryMembershipUpdate(remote.id, true, 1, emptyList())))
            render(scene)
            selectWithMouse(scene, "Options work")
            assertFalse(
                nodes(scene).any {
                    MR.strings.action_download.localized() in labels(it)
                },
                "all local has no disabled download placeholder",
            )
            assertFalse(nodes(scene).any { MR.strings.action_menu.localized() in labels(it) })
            assertTrue(nodes(scene).any { MR.strings.migrate.localized() in labels(it) })
            assertTrue(nodes(scene).any { MR.strings.action_delete.localized() in labels(it) })
            selectWithMouse(scene, "Remote work")
            assertTrue(
                nodes(scene).any {
                    MR.strings.action_download.localized() in labels(it)
                },
                "mixed selection keeps the applicable remote action",
            )
            assertTrue(nodes(scene).any { MR.strings.action_menu.localized() in labels(it) })
            assertTrue(nodes(scene).any { "2" in labels(it) })
        }
    }

    private suspend fun selectWithMouse(scene: NativeScene, title: String) =
        mouseSelect(scene, title, PointerKeyboardModifiers(isCtrlPressed = true))

    private suspend fun mouseSelect(scene: NativeScene, title: String, modifiers: PointerKeyboardModifiers) {
        val point = nodes(scene).first {
            it.config.contains(SemanticsActions.OnLongClick) && title in labels(it)
        }.boundsInRoot.center
        scene.pointer(PointerEventType.Press, point, true, PointerButton.Primary, modifiers)
        scene.pointer(PointerEventType.Release, point, false, PointerButton.Primary, modifiers)
        render(scene)
    }

    @Test
    fun `root wheel excludes modifiers horizontal toolbar rail and actual focused search editing`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            prepareWheelCategories(scene, model())
            val point = nodes(scene).first { it.config.contains(SemanticsActions.ScrollToIndex) }.boundsInRoot.center
            var time = 10_000L
            suspend fun excluded(
                message: String,
                modifiers: PointerKeyboardModifiers,
                delta: Offset = Offset(0f, 1f),
                position: Offset = point,
            ) {
                val old = model().state.value.selectedCategoryIndex
                scene.wheel(position, delta, time, modifiers)
                time += 500
                render(scene)
                assertEquals(old, model().state.value.selectedCategoryIndex, message)
            }
            excluded(
                "Ctrl+Alt never changes category",
                PointerKeyboardModifiers(isCtrlPressed = true, isAltPressed = true),
            )
            excluded(
                "Ctrl+Shift never changes category",
                PointerKeyboardModifiers(isCtrlPressed = true, isShiftPressed = true),
            )
            excluded(
                "horizontal wheel never changes category",
                PointerKeyboardModifiers(isCtrlPressed = true),
                Offset(1f, 1f),
            )
            val rootButton = nodes(scene).single {
                it.config.contains(SemanticsActions.OnClick) &&
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "desktop-root-${LibraryTab.key}"
            }
            excluded(
                "navigation rail is outside the content area",
                PointerKeyboardModifiers(isCtrlPressed = true),
                position = rootButton.boundsInRoot.center,
            )
            val search = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_search.localized() in labels(it)
            }
            excluded(
                "toolbar is outside the content area",
                PointerKeyboardModifiers(isCtrlPressed = true),
                position = search.boundsInRoot.center,
            )
            click(search)
            render(scene)
            val input = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(input.config[SemanticsProperties.Focused], "guard observes the actual editing focus chain")
            input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Options"))
            render(scene)
            excluded(
                "focused search including IME editing must retain category",
                PointerKeyboardModifiers(isCtrlPressed = true),
            )
            assertEquals("Options", model().state.value.searchQuery)
            assertTrue(
                nodes(scene).single {
                    it.config.contains(SemanticsActions.SetText)
                }.config[SemanticsProperties.Focused],
            )
            key(scene, Key.Tab)
            render(scene)
            assertFalse(
                nodes(scene).single {
                    it.config.contains(SemanticsActions.SetText)
                }.config[SemanticsProperties.Focused],
                "search must actually lose editing focus before testing an expanded query",
            )
            val old = model().state.value.selectedCategoryIndex
            scene.wheel(point, Offset(0f, 1f), time, PointerKeyboardModifiers(isCtrlPressed = true))
            render(scene)
            assertEquals(
                old + if (OperatingSystem.detect() == OperatingSystem.WINDOWS) 1 else 0,
                model().state.value.selectedCategoryIndex,
                "expanded search alone does not block a non-editing root",
            )
            assertEquals("Options", model().state.value.searchQuery)
        }
    }

    @Test
    fun `root wheel excludes More options sync category removal context and detail foreground`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            prepareWheelCategories(scene, model())
            val point = nodes(scene).first { it.config.contains(SemanticsActions.ScrollToIndex) }.boundsInRoot.center
            var time = 20_000L
            suspend fun excluded(name: String) {
                val old = model().state.value.selectedCategoryIndex
                scene.wheel(point, Offset(0f, 1f), time, PointerKeyboardModifiers(isCtrlPressed = true))
                time += 500
                render(scene)
                assertEquals(old, model().state.value.selectedCategoryIndex, "$name foreground blocks root wheel")
            }
            click(scene, MR.strings.action_menu.localized())
            render(scene)
            excluded("More")
            key(scene, Key.Escape)
            render(scene)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            excluded("Options")
            click(scene, MR.strings.action_close.localized())
            render(scene)
            click(scene, MR.strings.sync_title.localized())
            render(scene)
            assertTrue(Injekt.get<mihon.data.sync.runtime.SyncRuntime>().panel.state.value.visible)
            excluded("Sync")
            click(scene, MR.strings.sync_close.localized())
            render(scene)
            val card = nodes(scene).first { it.config.contains(SemanticsActions.OnLongClick) }
            card.config[SemanticsActions.OnLongClick].action!!.invoke()
            render(scene)
            click(scene, MR.strings.action_move_category.localized())
            render(scene)
            excluded("Categories")
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            click(scene, MR.strings.action_delete.localized())
            render(scene)
            excluded("Removal")
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            click(scene, MR.strings.desktop_ui_clear_selection.localized())
            render(scene)
            model().setContextMenuManga(model().state.value.allItems.single())
            render(scene)
            excluded("Context menu")
            model().setContextMenuManga(null)
            render(scene)
            click(nodes(scene).first { it.config.contains(SemanticsActions.OnLongClick) })
            render(scene)
            assertTrue(scene.stack.items.last() is MangaDetailScreen)
            excluded("Detail")
        }
    }

    private suspend fun prepareWheelCategories(scene: NativeScene, model: LibraryScreenModel) {
        val repository = Injekt.get<CategoryRepository>()
        repeat(3) { repository.insert(Category(0, "Guard ${'A' + it}", it.toLong(), 0)) }
        val custom = repository.getAll().filterNot { it.isSystemCategory }
        Injekt.get<MangaRepository>().setMangaCategories(model.state.value.allItems.single().id, custom.map { it.id })
        render(scene)
        model.setSelectedCategoryIndex(model.state.value.categories.indexOfFirst { it.name == "Guard A" })
        render(scene)
    }

    @Test
    fun `root ctrl wheel retains a burst across category composition and consumes boundaries without refresh`(
        @TempDir root: File,
    ) = runBlocking {
        withRoot(root) { scene, _, _, model ->
            val categories = Injekt.get<CategoryRepository>()
            repeat(5) { categories.insert(Category(0, "Wheel ${'A' + it}", it.toLong(), 0)) }
            val custom = categories.getAll().filterNot { it.isSystemCategory }
            val manga = model().state.value.allItems.single().manga
            Injekt.get<MangaRepository>().setMangaCategories(manga.id, custom.map { it.id })
            render(scene)
            val owner = model()
            owner.setSelectedCategoryIndex(owner.state.value.categories.indexOfFirst { it.name == "Wheel A" })
            render(scene)
            val point = nodes(scene).first { it.config.contains(SemanticsActions.OnLongClick) }.boundsInRoot.center
            fun current() = owner.state.value.categories[owner.state.value.selectedCategoryIndex].name
            suspend fun wheel(time: Long, dy: Float = 1f, ctrl: Boolean = true) {
                scene.wheel(point, Offset(0f, dy), time, PointerKeyboardModifiers(isCtrlPressed = ctrl))
                render(scene)
            }
            wheel(1_000)
            if (OperatingSystem.detect() != OperatingSystem.WINDOWS) {
                assertEquals("Wheel A", current(), "this root binding is Windows only")
                assertFalse(scene.lastWheelConsumed)
                return@withRoot
            }
            assertEquals("Wheel B", current(), "the first native Ctrl wheel must move one adjacent category")
            assertTrue(scene.lastWheelConsumed)
            for (time in listOf(1_100L, 1_200L, 1_300L, 1_400L)) {
                wheel(time)
                assertEquals("Wheel B", current(), "every event extends one burst even after keyed content rebuild")
                assertTrue(scene.lastWheelConsumed)
            }
            wheel(1_650)
            assertEquals("Wheel C", current(), "exactly 250ms starts a new burst")
            wheel(1_700, -1f)
            assertEquals("Wheel B", current(), "reverse direction starts a new burst")
            scene.wheel(point, Offset(0f, 1f), 1_701, PointerKeyboardModifiers(isCtrlPressed = true))
            assertTrue(scene.lastWheelConsumed)
            scene.wheel(point, Offset(0f, -1f), 1_702, PointerKeyboardModifiers(isCtrlPressed = true))
            assertTrue(scene.lastWheelConsumed)
            render(scene)
            assertEquals(
                "Wheel B",
                current(),
                "two opposite valid wheel events need no composition frame between category writes",
            )

            assertTrue(
                nodes(scene).single {
                    it.config.contains(SemanticsActions.OnClick) &&
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "desktop-root-${LibraryTab.key}"
                }
                    .config[SemanticsActions.RequestFocus].action!!.invoke(),
                "the keyboard release can occur with navigation focused outside the library content",
            )
            render(scene)
            val release = keyEvent(Key.CtrlLeft, KeyEventType.KeyUp, false)
            val press = keyEvent(Key.CtrlLeft, KeyEventType.KeyDown, false, ctrl = true)
            assertEquals(Key.CtrlLeft, release.key, "the native fixture retains the actual Ctrl key identity")
            assertTrue(press.isCtrlPressed)
            scene.sendKeyEvent(release)
            render(scene)
            scene.sendKeyEvent(press)
            render(scene)
            wheel(1_705, -1f)
            assertEquals("Wheel A", current(), "release then press Ctrl resets a burst with no intervening scroll")
            wheel(1_710, ctrl = false)
            wheel(1_720)
            assertEquals("Wheel B", current(), "a scroll with Ctrl released also resets a burst")
            scene.sendKeyEvent(release)
            scene.sendKeyEvent(press)
            wheel(1_730)
            assertEquals("Wheel C", current(), "release and press need no forced frame to start a new segment")
            owner.setSelectedCategoryIndex(owner.state.value.categories.indexOfFirst { it.name == "Wheel E" })
            render(scene)
            wheel(2_000)
            assertEquals("Wheel E", current(), "last category never wraps")
            assertTrue(scene.lastWheelConsumed, "valid boundary events are consumed")
            wheel(2_010, -1f)
            assertEquals("Wheel D", current())
            owner.setSelectedCategoryIndex(owner.state.value.categories.indexOfFirst { it.name == "Wheel A" })
            render(scene)
            wheel(2_300, -1f)
            assertEquals("Wheel A", current(), "first category never wraps")
            assertTrue(scene.lastWheelConsumed)
            for (arrow in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                scene.sendKeyEvent(keyEvent(arrow, KeyEventType.KeyDown, false, ctrl = true))
                scene.sendKeyEvent(keyEvent(arrow, KeyEventType.KeyUp, false, ctrl = true))
                render(scene)
                assertEquals("Wheel A", current(), "Ctrl arrows must not switch library categories")
            }
            assertTrue(model() === owner, "wheel must retain the actual root ScreenModel")
            assertFalse(owner.state.value.isUpdating)
            assertEquals(null, Injekt.get<mihon.desktop.domain.LibraryUpdateScheduler>().taskSnapshot())
        }
    }

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
            val bar = nodes(scene).single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "library-selection-top-bar"
            }
            assertEquals(
                listOf("1"),
                flatten(bar).filter { it.config.contains(SemanticsProperties.Text) }.flatMap(::labels),
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
        chapterRepositoryOverride: ((ChapterRepository) -> ChapterRepository)? = null,
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
            chapterRepositoryOverride = chapterRepositoryOverride,
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
        val scene = NativeScene(kotlin.coroutines.coroutineContext)
        val host = VoyagerLibraryNavigationHost(onStackAttached = { scene.stack = it })
        lateinit var model: LibraryScreenModel
        var rootModelCaptured = false
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalDensity provides Density(1f, scene.fontScale),
                ) {
                    ProvideLibraryScreenModelFactory({
                        LibraryScreenModelFactory.create().also {
                            if (!rootModelCaptured) {
                                model = it
                                rootModelCaptured = true
                            }
                        }
                    }) {
                        ProvideLibraryNavigationHost(host) {
                            DesktopTheme {
                                Box(
                                    Modifier.fillMaxSize().pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Final)
                                                if (event.type == PointerEventType.Scroll) {
                                                    scene.lastWheelConsumed = event.changes.all { it.isConsumed }
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Navigator(HomeScreen()) { CurrentScreen() }
                                }
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
    private fun keyEvent(key: Key, type: KeyEventType, shift: Boolean, ctrl: Boolean = false): ComposeKeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single {
            it.name.startsWith("KeyEvent-") &&
                !it.name.endsWith("\$default")
        }
        return ComposeKeyEvent(
            factory.invoke(null, key.keyCode, eventType, key.nativeKeyLocation, ctrl, false, false, shift, null),
        )
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
        var lastWheelConsumed = false
        private var windowSize by mutableStateOf(IntSize(1200, 900))
        private var keyboardModifiers by mutableStateOf(PointerKeyboardModifiers())
        var fontScale by mutableFloatStateOf(1f)
        private val bitmap = ImageBitmap(1400, 1000)
        private val canvas = Canvas(bitmap)
        private val scene = CanvasLayersComposeScene(
            size = windowSize,
            coroutineContext = context,
            platformContext = object : PlatformContext {
                override val windowInfo = object : WindowInfo {
                    override val isWindowFocused = true
                    override val keyboardModifiers get() = this@NativeScene.keyboardModifiers
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
        fun render() {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            scene.render(canvas, System.nanoTime())
        }
        fun sendKeyEvent(event: ComposeKeyEvent): Boolean {
            keyboardModifiers = PointerKeyboardModifiers(
                isCtrlPressed = event.isCtrlPressed,
                isAltPressed = event.isAltPressed,
                isShiftPressed = event.isShiftPressed,
            )
            return scene.sendKeyEvent(event)
        }
        fun resize(width: Int, height: Int) {
            windowSize = IntSize(width, height)
            scene.size = windowSize
        }
        fun pointer(
            type: PointerEventType,
            position: Offset,
            pressed: Boolean,
            button: PointerButton? = null,
            modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        ) {
            keyboardModifiers = modifiers
            scene.sendPointerEvent(
                type,
                position,
                buttons = PointerButtons(isPrimaryPressed = pressed),
                button = button,
                keyboardModifiers = modifiers,
            )
        }
        fun wheel(position: Offset, delta: Offset, time: Long, modifiers: PointerKeyboardModifiers) {
            lastWheelConsumed = false
            keyboardModifiers = modifiers
            scene.sendPointerEvent(
                PointerEventType.Scroll,
                position,
                scrollDelta = delta,
                timeMillis = time,
                keyboardModifiers = modifiers,
            )
        }
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
