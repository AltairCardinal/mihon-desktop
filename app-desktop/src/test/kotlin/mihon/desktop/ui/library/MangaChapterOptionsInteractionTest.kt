package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyLocation
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.ui.theme.DesktopTheme
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

@Isolated
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class MangaChapterOptionsInteractionTest {
    @Test
    fun `real source save initializes new defaults and preserves favorite and nonfavorite overrides`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            val preferences = Injekt.get<LibraryPreferences>()
            val desired = Manga.CHAPTER_SHOW_READ or Manga.CHAPTER_SHOW_NOT_DOWNLOADED or
                Manga.CHAPTER_SHOW_BOOKMARKED or Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC or
                Manga.CHAPTER_DISPLAY_NUMBER
            preferences.setChapterSettingsDefault(manga.copy(chapterFlags = desired))
            val service = Injekt.get<mihon.desktop.domain.SaveSourceMangaForDetails>()
            val listed = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                url = "/new-default-listed"
                title =
                    "New defaults"
            }
            val saved = service.awaitListed(listed, 42L)
            assertEquals(
                desired,
                saved.chapterFlags,
                "real production source saving must consume the shared default authority on first insert",
            )
            val repository = Injekt.get<MangaRepository>()
            assertEquals(desired, repository.getMangaById(saved.id).chapterFlags)
            scene.navigator.push(MangaDetailScreen(saved.id))
            render(scene)
            val child = scene.createdModels.last()
            assertEquals(saved.id, child.mangaId)
            assertEquals(desired, child.state.value.manga!!.chapterFlags)
            assertTrue(child.setChapterBookmarkFilter(TriState.ENABLED_NOT))
            val override = repository.getMangaById(saved.id).chapterFlags
            scene.navigator.pop()
            render(scene)
            preferences.setChapterSettingsDefault(manga.copy(chapterFlags = 0L))
            assertEquals(override, service.awaitListed(listed, 42L).chapterFlags)
            assertEquals(override, service.awaitSearchResults(listOf(listed), 42L).single().chapterFlags)
            assertEquals(
                override,
                service.await(
                    listed,
                    42L,
                    listOf(
                        eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                            url = "/retained-default-chapter"
                            name = "Chapter 1"
                            chapter_number = 1f
                        },
                    ),
                ).chapterFlags,
            )
            repository.update(MangaUpdate(id = saved.id, favorite = true))
            assertEquals(
                override,
                service.await(
                    listed,
                    42L,
                    listOf(
                        eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                            url = "/retained-default-chapter"
                            name = "Chapter 1"
                            chapter_number = 1f
                        },
                    ),
                ).chapterFlags,
            )
            preferences.setChapterSettingsDefault(manga.copy(chapterFlags = desired))
            val search = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                url = "/new-default-search"
                title =
                    "Search defaults"
            }
            assertEquals(desired, service.awaitSearchResults(listOf(search), 42L).single().chapterFlags)
            val refreshed = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                url = "/new-default-refresh"
                title =
                    "Refresh defaults"
            }
            assertEquals(
                desired,
                service.await(
                    refreshed,
                    42L,
                    listOf(
                        eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                            url = "/new-default-chapter"
                            name = "Chapter 1"
                            chapter_number = 1f
                        },
                    ),
                ).chapterFlags,
            )
            assertEquals(manga.chapterFlags, repository.getMangaById(manga.id).chapterFlags)
        }
    }

    @Test
    fun `activity indicator reflects excluded filters global override and scanlators in the mounted detail`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(
                eu.kanade.domain.ui.model.ThemeMode.LIGHT,
            )
            render(scene)
            fun primaryPixels(): Int {
                val icon = activeNodes(scene).first {
                    MR.strings.desktop_ui_filter_chapters.localized() in labels(it)
                }.boundsInRoot
                val pixels = scene.snapshot()
                return (icon.left.toInt() until icon.right.toInt()).sumOf { x ->
                    (icon.top.toInt() until icon.bottom.toInt()).count { y ->
                        pixels.getRGB(x, y) == 0xFF0058CA.toInt()
                    }
                }
            }
            assertEquals(0, primaryPixels())
            val repository = Injekt.get<MangaRepository>()
            repository.update(MangaUpdate(id = manga.id, chapterFlags = Manga.CHAPTER_SHOW_NOT_BOOKMARKED))
            render(scene)
            assertTrue(
                primaryPixels() > 0,
                "ENABLED_NOT is an active filter even though the old include-only boolean was false",
            )
            repository.update(MangaUpdate(id = manga.id, chapterFlags = 0L))
            render(scene)
            assertEquals(0, primaryPixels())
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.downloadedOnly().set(true)
            render(scene)
            assertTrue(primaryPixels() > 0)
            preferences.downloadedOnly().set(false)
            render(scene)
            assertEquals(0, primaryPixels())
            Injekt.get<mihon.desktop.domain.SetExcludedScanlators>().await(manga.id, setOf("Team A"))
            render(scene)
            assertTrue(primaryPixels() > 0)
            Injekt.get<mihon.desktop.domain.SetExcludedScanlators>().await(manga.id, emptySet())
            render(scene)
            assertEquals(0, primaryPixels())
        }
    }

    @Test
    fun `real three state events persist read inversion and local downloaded value survives forced mode`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            val repository = Injekt.get<MangaRepository>()
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            for (raw in listOf(Manga.CHAPTER_SHOW_READ, Manga.CHAPTER_SHOW_UNREAD, Manga.SHOW_ALL)) {
                click(scene, MR.strings.label_read_chapters.localized())
                render(scene)
                assertEquals(raw, repository.getMangaById(manga.id).unreadFilterRaw)
            }
            click(scene, MR.strings.label_downloaded.localized())
            render(scene)
            click(scene, MR.strings.label_downloaded.localized())
            render(scene)
            assertEquals(Manga.CHAPTER_SHOW_NOT_DOWNLOADED, repository.getMangaById(manga.id).downloadedFilterRaw)
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.downloadedOnly().set(true)
            render(scene)
            val row = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.Role) &&
                    MR.strings.label_downloaded.localized() in labels(it)
            }
            assertTrue(row.config.contains(SemanticsProperties.Disabled))
            assertTrue(
                row.config.contains(SemanticsProperties.ToggleableState),
                "the accessible whole checkbox row must expose its actual three-state value",
            )
            assertEquals(androidx.compose.ui.state.ToggleableState.On, row.config[SemanticsProperties.ToggleableState])
            assertEquals(Manga.CHAPTER_SHOW_NOT_DOWNLOADED, repository.getMangaById(manga.id).downloadedFilterRaw)
            preferences.downloadedOnly().set(false)
            render(scene)
            val restored = activeNodes(scene).first {
                it.config.contains(SemanticsProperties.Role) &&
                    MR.strings.label_downloaded.localized() in labels(it)
            }
            assertFalse(restored.config.contains(SemanticsProperties.Disabled))
            assertEquals(
                androidx.compose.ui.state.ToggleableState.Indeterminate,
                restored.config[SemanticsProperties.ToggleableState],
            )
            click(scene, MR.strings.action_sort.localized())
            render(scene)
            val modes = listOf(
                MR.strings.sort_by_source to Manga.CHAPTER_SORTING_SOURCE,
                MR.strings.sort_by_number to Manga.CHAPTER_SORTING_NUMBER,
                MR.strings.sort_by_upload_date to Manga.CHAPTER_SORTING_UPLOAD_DATE,
                MR.strings.action_sort_alpha to Manga.CHAPTER_SORTING_ALPHABET,
            )
            for ((label, mode) in modes) {
                val item = activeNodes(scene).first {
                    it.config.contains(SemanticsActions.OnClick) &&
                        labels(it).any { text -> text.startsWith(label.localized()) }
                }
                requireNotNull(item.config[SemanticsActions.OnClick].action).invoke()
                render(scene)
                assertEquals(mode, repository.getMangaById(manga.id).sorting)
                val direction = repository.getMangaById(manga.id).sortDescending()
                val again = activeNodes(scene).first {
                    it.config.contains(SemanticsActions.OnClick) &&
                        labels(it).any { text -> text.startsWith(label.localized()) }
                }
                requireNotNull(again.config[SemanticsActions.OnClick].action).invoke()
                render(scene)
                assertEquals(!direction, repository.getMangaById(manga.id).sortDescending())
            }
        }
    }

    @Test
    fun `native chapter panel traps both tab directions and Escape returns one layer to real triggers`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, chapters ->
            val backgroundAction = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) &&
                    it.config.contains(SemanticsProperties.Selected) &&
                    chapters.first().name in labels(it)
            }.boundsInRoot.center
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            val initial = focused(scene).id
            val expected = activeNodes(scene).filter {
                it.config.contains(SemanticsProperties.Focused) && !it.config.contains(SemanticsProperties.Disabled)
            }.map { it.id }.toSet()
            for (shift in listOf(false, true)) {
                val visited = mutableSetOf(initial)
                repeat(expected.size) {
                    key(scene, Key.Tab, shift)
                    render(scene)
                    visited += focused(scene).id
                    assertEquals(2, scene.owners.size, "native focus stays in the mounted modal owner")
                }
                assertEquals(expected, visited)
                assertEquals(initial, focused(scene).id, "the complete native focus ring closes in both directions")
            }
            repeat(3) {
                key(scene, Key.Tab)
                render(scene)
            }
            assertTrue(MR.strings.label_read_chapters.localized() in labels(focused(scene)))
            key(scene, Key.Spacebar)
            render(scene)
            assertEquals(Manga.CHAPTER_SHOW_READ, Injekt.get<MangaRepository>().getMangaById(manga.id).unreadFilterRaw)
            repeat(2) {
                key(scene, Key.Spacebar)
                render(scene)
            }
            assertEquals(Manga.SHOW_ALL, Injekt.get<MangaRepository>().getMangaById(manga.id).unreadFilterRaw)
            click(scene, MR.strings.scanlator.localized())
            render(scene)
            key(scene, Key.Escape)
            render(scene)
            assertTrue(model.state.value.showFilterMenu)
            assertEquals(2, scene.owners.size)
            assertTrue(MR.strings.scanlator.localized() in labels(focused(scene)))
            click(scene, MR.strings.set_chapter_settings_as_default.localized())
            render(scene)
            key(scene, Key.Escape)
            render(scene)
            assertEquals(2, scene.owners.size)
            assertTrue(MR.strings.set_chapter_settings_as_default.localized() in labels(focused(scene)))
            scene.pointerClick(backgroundAction)
            render(scene)
            assertFalse(
                nodes(scene).single {
                    it.config.contains(SemanticsProperties.Selected) && chapters.first().name in labels(it)
                }.config[SemanticsProperties.Selected],
                "the modal must not select the actual chapter row behind it",
            )
            assertEquals(1, scene.navigator.size)
            if (!model.state.value.showFilterMenu) {
                click(scene, MR.strings.desktop_ui_filter_chapters.localized())
                render(scene)
            }
            key(scene, Key.Escape)
            render(scene)
            assertFalse(model.state.value.showFilterMenu)
            assertEquals(1, scene.owners.size)
            assertTrue(MR.strings.desktop_ui_filter_chapters.localized() in labels(focused(scene)))
        }
    }

    @Test
    fun `native narrow large font pages and long scanlator draft stay reachable across resize and theme`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            val originalModel = model
            val preferences = Injekt.get<mihon.desktop.settings.DesktopAppPreferences>()
            preferences.themeMode.set(eu.kanade.domain.ui.model.ThemeMode.LIGHT)
            scene.resize(320, 680)
            scene.fontScale = 2f
            render(scene)
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            val scroll = activeNodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
            requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 10_000f)
            render(scene)
            val oldScroll = activeNodes(scene).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            val close = activeNodes(scene).first { MR.strings.action_close.localized() in labels(it) }
            assertTrue(close.boundsInRoot.bottom <= 680f && close.boundsInRoot.top >= 0f)
            click(scene, MR.strings.action_display.localized())
            render(scene)
            val number = activeNodes(scene).first { MR.strings.show_chapter_number.localized() in labels(it) }
            assertTrue(number.boundsInRoot.bottom <= 680f && number.boundsInRoot.top >= 0f)
            click(scene, MR.strings.show_chapter_number.localized())
            render(scene)
            assertEquals(Manga.CHAPTER_DISPLAY_NUMBER, Injekt.get<MangaRepository>().getMangaById(manga.id).displayMode)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            assertEquals(
                oldScroll,
                activeNodes(scene).first {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                }.config[SemanticsProperties.VerticalScrollAxisRange].value(),
            )
            click(scene, MR.strings.action_display.localized())
            render(scene)
            scene.savePng(visualFile(root, "ri07-chapter-display-light-320-font200.png"))
            preferences.themeMode.set(eu.kanade.domain.ui.model.ThemeMode.DARK)
            scene.resize(1000, 800)
            render(scene)
            assertTrue(
                activeNodes(scene).single {
                    it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == Role.Tab &&
                        it.config[SemanticsProperties.Selected]
                }.let { MR.strings.action_display.localized() in labels(it) },
            )
            assertEquals(listOf(originalModel), scene.createdModels)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            Injekt.get<ChapterRepository>().addAll(
                (1..24).map {
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/group-$it",
                        name = "Special",
                        scanlator = "Team %02d".format(it),
                        sourceOrder =
                        it.toLong() + 1,
                    )
                },
            )
            render(scene)
            scene.resize(320, 680)
            render(scene)
            click(scene, MR.strings.scanlator.localized())
            render(scene)
            val scanScroll = activeNodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
            requireNotNull(scanScroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 10_000f)
            render(scene)
            val last = activeNodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) && "Team B" in labels(it)
            }
            assertTrue(last.boundsInRoot.top >= 0f && last.boundsInRoot.bottom <= 680f)
            val cancel = activeNodes(scene).first { MR.strings.action_cancel.localized() in labels(it) }
            assertTrue(cancel.boundsInRoot.top >= 0f && cancel.boundsInRoot.bottom <= 680f)
            click(scene, "Team B")
            render(scene)
            scene.savePng(visualFile(root, "ri07-scanlators-dark-320-font200.png"))
            key(scene, Key.Escape)
            render(scene)
            assertTrue(Injekt.get<mihon.desktop.domain.GetExcludedScanlators>().await(manga.id).isEmpty())
            assertTrue(MR.strings.scanlator.localized() in labels(focused(scene)))
        }
    }

    private fun visualFile(root: File, name: String) = File(
        System.getenv("MIHON_RI07_VISUAL_DIR") ?: File(root, "visual").absolutePath,
        name,
    )

    @Test
    fun `default six preferences recover before and after flush failures and retain confirmation for retry`(
        @TempDir root: File,
    ) = runBlocking {
        lateinit var faults: FaultPreferences
        withDetail(root, backendFactory = { FaultPreferences(it).also { faults = it } }) { scene, model, manga, _ ->
            val desired = Manga.CHAPTER_SHOW_READ or Manga.CHAPTER_SHOW_DOWNLOADED or Manga.CHAPTER_SHOW_BOOKMARKED or
                Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC or Manga.CHAPTER_DISPLAY_NUMBER
            Injekt.get<MangaRepository>().update(MangaUpdate(id = manga.id, chapterFlags = desired))
            val preferences = Injekt.get<LibraryPreferences>()
            val entries = listOf(
                preferences.filterChapterByRead(),
                preferences.filterChapterByDownloaded(),
                preferences.filterChapterByBookmarked(),
                preferences.sortChapterBySourceOrNumber(),
                preferences.sortChapterByAscendingOrDescending(),
                preferences.displayChapterByNameOrNumber(),
            )
            val expected = listOf(
                Manga.CHAPTER_SHOW_READ,
                Manga.CHAPTER_SHOW_DOWNLOADED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                Manga.CHAPTER_SORTING_NUMBER,
                Manga.CHAPTER_SORT_ASC,
                Manga.CHAPTER_DISPLAY_NUMBER,
            )
            render(scene)
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            for (after in listOf(false, true)) {
                entries.forEach { it.delete() }
                val previous = entries.map { it.get() }
                faults.fail(entries.last().key(), after)
                click(scene, MR.strings.set_chapter_settings_as_default.localized())
                render(scene)
                click(scene, MR.strings.action_ok.localized())
                render(scene)
                assertTrue(model.state.value.chapterSettingsFeedbackIsError)
                assertEquals(previous, entries.map { it.get() })
                assertTrue(entries.none { it.isSet() }, "failed writes restore original unset authority too")
                assertTrue(activeNodes(scene).any { MR.strings.action_ok.localized() in labels(it) })
                click(scene, MR.strings.action_ok.localized())
                render(scene)
                assertEquals(expected, entries.map { it.get() })
                assertFalse(model.state.value.chapterSettingsFeedbackIsError)
            }
        }
    }

    @Test
    fun `default favorite rejection keeps saved defaults and reports partial apply for explicit retry`(
        @TempDir root: File,
    ) = runBlocking {
        var rejectedId: Long? = null
        withDetail(root, mangaRepositoryOverride = { actual ->
            object : MangaRepository by actual {
                override suspend fun update(update: MangaUpdate): Boolean {
                    if (update.id == rejectedId && update.chapterFlags != null) {
                        rejectedId = null
                        return false
                    }
                    return actual.update(update)
                }
            }
        }) { scene, model, manga, _ ->
            val repository = Injekt.get<MangaRepository>()
            val favorites = repository.insertNetworkManga(
                listOf("First", "Second").map {
                    Manga.create().copy(source = 42, url = "/partial-$it", title = it, initialized = true)
                },
            )
            favorites.forEach { repository.update(MangaUpdate(id = it.id, favorite = true)) }
            val desired = Manga.CHAPTER_SHOW_BOOKMARKED or Manga.CHAPTER_DISPLAY_NUMBER
            repository.update(MangaUpdate(id = manga.id, chapterFlags = desired))
            rejectedId = favorites.last().id
            render(scene)
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            click(scene, MR.strings.set_chapter_settings_as_default.localized())
            render(scene)
            click(scene, MR.strings.also_set_chapter_settings_for_library.localized())
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(
                Manga.CHAPTER_SHOW_BOOKMARKED,
                Injekt.get<LibraryPreferences>().filterChapterByBookmarked().get(),
            )
            assertEquals(desired, repository.getMangaById(favorites.first().id).chapterFlags)
            assertEquals(0L, repository.getMangaById(favorites.last().id).chapterFlags)
            assertEquals(
                MR.strings.desktop_chapter_defaults_apply_failed.localized(),
                model.state.value.chapterSettingsFeedback,
            )
            assertTrue(activeNodes(scene).any { MR.strings.action_ok.localized() in labels(it) })
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            favorites.forEach { assertEquals(desired, repository.getMangaById(it.id).chapterFlags) }
            assertFalse(model.state.value.chapterSettingsFeedbackIsError)
        }
    }

    @Test
    fun `scanlator SQLite rejection preserves exclusions and draft then retries the same dialog`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            val driver = Injekt.get<app.cash.sqldelight.db.SqlDriver>()
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            click(scene, MR.strings.scanlator.localized())
            render(scene)
            click(scene, "Team A")
            driver.execute(
                null,
                "CREATE TRIGGER reject_scan BEFORE INSERT ON excluded_scanlators BEGIN SELECT RAISE(ABORT, 'rejected exclusion'); END",
                0,
            )
            try {
                click(scene, MR.strings.action_ok.localized())
                render(scene)
                assertTrue(Injekt.get<mihon.desktop.domain.GetExcludedScanlators>().await(manga.id).isEmpty())
                assertTrue(model.state.value.chapterSettingsFeedbackIsError)
                assertTrue(activeNodes(scene).any { MR.strings.action_ok.localized() in labels(it) })
            } finally {
                driver.execute(null, "DROP TRIGGER reject_scan", 0)
            }
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(setOf("Team A"), Injekt.get<mihon.desktop.domain.GetExcludedScanlators>().await(manga.id))
            assertFalse(hasChapter(scene, "Chapter Alpha"))
        }
    }

    @Test
    fun `mounted download filter follows real completed and deleted files without queue changes`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, chapters ->
            val provider = Injekt.get<mihon.desktop.download.DesktopDownloadProvider>()
            val manager = Injekt.get<mihon.desktop.download.DesktopDownloadManager>()
            val chapter = chapters.first { it.name == "Chapter Alpha" }
            suspend fun publish() {
                val tmp = provider.chapterTmpDir(manga.source, manga.title, chapter.name)
                assertTrue(tmp.mkdirs())
                assertTrue(
                    javax.imageio.ImageIO.write(
                        java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                        "png",
                        File(tmp, "001.png"),
                    ),
                )
                assertTrue(provider.renameTmpToFinal(manga.source, manga.title, chapter.name))
            }
            publish()
            Injekt.get<MangaRepository>().update(
                MangaUpdate(id = manga.id, chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED),
            )
            render(scene)
            assertTrue(
                hasChapter(scene, chapter.name),
                "actual completed image must qualify before the tested deletion",
            )
            assertTrue(manager.queue.value.isEmpty())
            manager.deleteDownload(manga.source, manga.title, chapter.name)
            render(scene)
            assertFalse(
                hasChapter(scene, chapter.name),
                "deletion must invalidate the mounted projection even with an unchanged queue",
            )
            assertTrue(manager.queue.value.isEmpty())
            publish()
            render(scene)
            assertTrue(hasChapter(scene, chapter.name), "published files must re-enter the same mounted detail")
            assertTrue(manager.queue.value.isEmpty())
            assertTrue(provider.deleteMangaDownloads(manga.source, manga.title))
            render(scene)
            assertFalse(
                hasChapter(scene, chapter.name),
                "whole manga deletion also invalidates mounted chapter availability",
            )
            publish()
            render(scene)
            assertTrue(hasChapter(scene, chapter.name))
            val identity = Injekt.get<mihon.desktop.download.DesktopDownloadIdentityResolver>().resolve(manga, chapter)
            assertTrue(provider.deleteMangaDownloads(manga.source, manga.title, identity))
            render(scene)
            assertFalse(
                hasChapter(scene, chapter.name),
                "canonical identity whole manga deletion uses the same availability flow",
            )
        }
    }

    @Test
    fun `one missing preference controls shared total and real list indicators`(@TempDir root: File) = runBlocking {
        withDetail(root) { scene, model, _, _ ->
            val expected = model.state.value.chapters.map { it.chapterNumber }.missingChaptersCount()
            val label = missingChapterCountText(expected)
            assertTrue(
                nodes(scene).any {
                    label in labels(it)
                },
                "the shared missing total must be visible alongside actual chapters",
            )
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().hideMissingChapterIndicators.set(true)
            render(scene)
            assertFalse(nodes(scene).any { labels(it).any { text -> text.contains("missing", ignoreCase = true) } })
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().hideMissingChapterIndicators.set(false)
            render(scene)
            assertTrue(nodes(scene).any { label in labels(it) })
        }
    }

    @Test
    fun `concurrent preference events serialize fresh authoritative flag snapshots`(@TempDir root: File) = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var delayFirstWrite = true
        withDetail(root, mangaRepositoryOverride = { actual ->
            object : MangaRepository by actual {
                override suspend fun update(update: MangaUpdate): Boolean {
                    if (update.chapterFlags != null && delayFirstWrite) {
                        delayFirstWrite = false
                        started.complete(Unit)
                        release.await()
                    }
                    return actual.update(update)
                }
            }
        }) { scene, model, manga, _ ->
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            click(scene, MR.strings.action_filter_bookmarked.localized())
            started.await()
            click(scene, MR.strings.action_display.localized())
            render(scene)
            click(scene, MR.strings.show_chapter_number.localized())
            render(scene)
            release.complete(Unit)
            render(scene)
            val saved = Injekt.get<MangaRepository>().getMangaById(manga.id)
            assertEquals(Manga.CHAPTER_SHOW_BOOKMARKED, saved.bookmarkedFilterRaw)
            assertEquals(
                Manga.CHAPTER_DISPLAY_NUMBER,
                saved.displayMode,
                "second event must read after the first write",
            )
            assertEquals(saved.chapterFlags, model.state.value.manga!!.chapterFlags)
        }
    }

    @Test
    fun `real detail chapter settings opens a single filter sort display modal`(@TempDir root: File) = runBlocking {
        withDetail(root) { scene, _, _, _ ->
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            val tabs = nodes(scene).filter {
                it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == Role.Tab
            }
            assertEquals(
                listOf(MR.strings.action_filter, MR.strings.action_sort, MR.strings.action_display).map {
                    it.localized()
                },
                tabs.sortedBy { it.boundsInRoot.left }.flatMap(::labels),
            )
            assertTrue(tabs.first().config[SemanticsProperties.Selected])
        }
    }

    @Test
    fun `mounted remote detail responds to global downloaded override without changing local flags`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            assertTrue(hasChapter(scene, "Chapter Alpha"))
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.downloadedOnly().set(true)
            render(scene)
            assertFalse(
                hasChapter(scene, "Chapter Alpha"),
                "non-downloaded chapter must leave mounted list immediately",
            )
            assertEquals(Manga.SHOW_ALL, model.state.value.manga!!.downloadedFilterRaw)
            preferences.downloadedOnly().set(false)
            render(scene)
            assertTrue(hasChapter(scene, "Chapter Alpha"))
            assertEquals(manga.chapterFlags, Injekt.get<MangaRepository>().getMangaById(manga.id).chapterFlags)
        }
    }

    @Test
    fun `chapter bookmark preference event persists then filters current repository rows`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            val row = nodes(scene).first {
                it.config.contains(SemanticsActions.OnClick) && labels(it).any { label ->
                    label.contains(MR.strings.desktop_ui_bookmarked_only.localized()) ||
                        label == MR.strings.action_filter_bookmarked.localized()
                }
            }
            assertTrue(requireNotNull(row.config[SemanticsActions.OnClick].action).invoke())
            render(scene)
            assertEquals(
                Manga.CHAPTER_SHOW_BOOKMARKED,
                Injekt.get<MangaRepository>().getMangaById(manga.id).bookmarkedFilterRaw,
                "the actual preference event must persist manga flags before reporting success",
            )
        }
    }

    @Test
    fun `display setting write rejection and post commit failure restore authority and allow retry`(
        @TempDir root: File,
    ) = runBlocking {
        var failure = 1
        withDetail(root, mangaRepositoryOverride = { actual ->
            object : MangaRepository by actual {
                override suspend fun update(update: MangaUpdate): Boolean {
                    if (update.chapterFlags != null && failure == 1) {
                        failure = 0
                        return false
                    }
                    if (update.chapterFlags != null && failure == 2) {
                        failure = 0
                        actual.update(update)
                        val committed = actual.getMangaById(update.id)
                        actual.update(
                            MangaUpdate(
                                id = update.id,
                                chapterFlags =
                                committed.chapterFlags or Manga.CHAPTER_SHOW_BOOKMARKED,
                            ),
                        )
                        error("after actual SQLite commit")
                    }
                    return actual.update(update)
                }
            }
        }) { scene, model, manga, _ ->
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            click(scene, MR.strings.action_display.localized())
            render(scene)
            click(scene, MR.strings.show_chapter_number.localized())
            render(scene)
            assertEquals(Manga.CHAPTER_DISPLAY_NAME, Injekt.get<MangaRepository>().getMangaById(manga.id).displayMode)
            assertTrue(
                model.state.value.chapterSettingsFeedback != null,
                "rejected writes must have visible retry feedback",
            )
            click(scene, MR.strings.show_chapter_number.localized())
            render(scene)
            assertEquals(Manga.CHAPTER_DISPLAY_NUMBER, Injekt.get<MangaRepository>().getMangaById(manga.id).displayMode)
            failure = 2
            click(scene, MR.strings.show_title.localized())
            render(scene)
            val current = Injekt.get<MangaRepository>().getMangaById(manga.id)
            assertEquals(Manga.CHAPTER_DISPLAY_NUMBER, current.displayMode)
            assertEquals(
                Manga.CHAPTER_SHOW_BOOKMARKED,
                current.bookmarkedFilterRaw,
                "rollback only restores the failed field",
            )
            assertEquals(current.chapterFlags, model.state.value.manga!!.chapterFlags)
            assertTrue(model.state.value.chapterSettingsFeedback != null)
        }
    }

    @Test
    fun `defaults require explicit confirmation and apply to existing favorites only when checked`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, model, manga, _ ->
            val repository = Injekt.get<MangaRepository>()
            val defaults = Injekt.get<LibraryPreferences>()
            val desired = Manga.CHAPTER_SHOW_READ or Manga.CHAPTER_SHOW_NOT_DOWNLOADED or
                Manga.CHAPTER_SHOW_BOOKMARKED or Manga.CHAPTER_SORTING_NUMBER or
                Manga.CHAPTER_SORT_ASC or Manga.CHAPTER_DISPLAY_NUMBER
            repository.update(MangaUpdate(id = manga.id, chapterFlags = desired))
            val other = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 42,
                        url = "/other-default",
                        title = "Existing favorite",
                        initialized = true,
                    ),
                ),
            ).single()
            val nonfavorite = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 42,
                        url = "/not-favorite",
                        title = "Not a favorite",
                        initialized = true,
                    ),
                ),
            ).single()
            repository.update(MangaUpdate(id = other.id, favorite = true, chapterFlags = Manga.CHAPTER_SHOW_UNREAD))
            render(scene)
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            assertTrue(
                nodes(scene).any { MR.strings.set_chapter_settings_as_default.localized() in labels(it) },
                "the unified chapter settings must provide the real default strategy entry",
            )
            click(scene, MR.strings.set_chapter_settings_as_default.localized())
            render(scene)
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertEquals(Manga.SHOW_ALL, defaults.filterChapterByRead().get())
            assertEquals(Manga.CHAPTER_SHOW_UNREAD, repository.getMangaById(other.id).chapterFlags)
            click(scene, MR.strings.set_chapter_settings_as_default.localized())
            render(scene)
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(Manga.CHAPTER_SHOW_READ, defaults.filterChapterByRead().get())
            assertFalse(model.state.value.chapterSettingsFeedbackIsError, "successful defaults are ordinary feedback")
            assertEquals(Manga.CHAPTER_SHOW_NOT_DOWNLOADED, defaults.filterChapterByDownloaded().get())
            assertEquals(Manga.CHAPTER_SHOW_BOOKMARKED, defaults.filterChapterByBookmarked().get())
            assertEquals(Manga.CHAPTER_SORTING_NUMBER, defaults.sortChapterBySourceOrNumber().get())
            assertEquals(Manga.CHAPTER_SORT_ASC, defaults.sortChapterByAscendingOrDescending().get())
            assertEquals(Manga.CHAPTER_DISPLAY_NUMBER, defaults.displayChapterByNameOrNumber().get())
            assertEquals(Manga.CHAPTER_SHOW_UNREAD, repository.getMangaById(other.id).chapterFlags)
            click(scene, MR.strings.action_close.localized())
            render(scene)
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            assertNull(model.state.value.chapterSettingsFeedback, "a fresh settings session must not replay success")
            click(scene, MR.strings.set_chapter_settings_as_default.localized())
            render(scene)
            click(scene, MR.strings.also_set_chapter_settings_for_library.localized())
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(desired, repository.getMangaById(other.id).chapterFlags)
            assertEquals(nonfavorite.chapterFlags, repository.getMangaById(nonfavorite.id).chapterFlags)
            repository.update(MangaUpdate(id = other.id, chapterFlags = Manga.CHAPTER_SHOW_UNREAD))
            repository.update(MangaUpdate(id = manga.id, chapterFlags = Manga.SHOW_ALL))
            render(scene)
            click(scene, MR.strings.action_reset.localized())
            render(scene)
            assertEquals(desired, repository.getMangaById(manga.id).chapterFlags)
            assertEquals(desired, model.state.value.manga!!.chapterFlags)
            assertEquals(Manga.CHAPTER_SHOW_UNREAD, repository.getMangaById(other.id).chapterFlags)
        }
    }

    @Test
    fun `scanlator settings uses an explicit draft dialog rather than immediate exclusion writes`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, _, _ ->
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            assertTrue(nodes(scene).any { MR.strings.scanlator.localized() in labels(it) })
            val excluded = Injekt.get<mihon.desktop.domain.GetExcludedScanlators>()
            val mangaId = Injekt.get<MangaRepository>().getMangaByUrlAndSourceId("/chapter-options", 42)!!.id
            click(scene, MR.strings.scanlator.localized())
            render(scene)
            click(scene, MR.strings.action_select_all.localized())
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertTrue(excluded.await(mangaId).isEmpty())
            assertTrue(hasChapter(scene, "Chapter Alpha"))
            click(scene, MR.strings.scanlator.localized())
            render(scene)
            click(scene, "Team A")
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertEquals(setOf("Team A"), excluded.await(mangaId))
            assertFalse(hasChapter(scene, "Chapter Alpha"))
            assertTrue(hasChapter(scene, "Chapter Beta"))
            click(scene, MR.strings.scanlator.localized())
            render(scene)
            click(scene, MR.strings.action_reset.localized())
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertEquals(setOf("Team A"), excluded.await(mangaId))
            click(scene, MR.strings.scanlator.localized())
            render(scene)
            click(scene, MR.strings.action_reset.localized())
            click(scene, MR.strings.action_ok.localized())
            render(scene)
            assertTrue(excluded.await(mangaId).isEmpty())
            assertTrue(hasChapter(scene, "Chapter Alpha"))
        }
    }

    @Test
    fun `authoritative initial read failure returns feedback without changing persistent chapter flags`(
        @TempDir root: File,
    ) = runBlocking {
        var failRead = false
        withDetail(root, mangaRepositoryOverride = { actual ->
            object : MangaRepository by actual {
                override suspend fun getMangaById(id: Long): Manga {
                    if (failRead) error("authoritative read denied")
                    return actual.getMangaById(id)
                }
            }
        }) { scene, model, manga, _ ->
            click(scene, MR.strings.desktop_ui_filter_chapters.localized())
            render(scene)
            failRead = true
            val attempted = runCatching { model.setChapterBookmarkFilter(TriState.ENABLED_IS) }
            assertNull(
                attempted.exceptionOrNull(),
                "a repository read failure must use the same product feedback boundary",
            )
            assertEquals(false, attempted.getOrNull())
            assertTrue(model.state.value.chapterSettingsFeedback != null)
            failRead = false
            assertEquals(manga.chapterFlags, Injekt.get<MangaRepository>().getMangaById(manga.id).chapterFlags)
        }
    }

    @Test
    fun `external flags hide chapters and prune detail selection unlike library hidden selection`(
        @TempDir root: File,
    ) = runBlocking {
        withDetail(root) { scene, _, manga, _ ->
            val row = nodes(scene).first {
                it.config.contains(SemanticsActions.OnLongClick) && "Chapter Alpha" in labels(it)
            }
            assertTrue(requireNotNull(row.config[SemanticsActions.OnLongClick].action).invoke())
            render(scene)
            assertTrue(nodes(scene).any { MR.strings.desktop_ui_clear_selection.localized() in labels(it) })
            Injekt.get<MangaRepository>().update(
                MangaUpdate(id = manga.id, chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED),
            )
            render(scene)
            assertFalse(hasChapter(scene, "Chapter Alpha"))
            assertFalse(
                nodes(scene).any { MR.strings.desktop_ui_clear_selection.localized() in labels(it) },
                "hidden chapters must leave the detail selection when the visible projection changes",
            )
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

    private suspend fun withDetail(
        root: File,
        mangaRepositoryOverride: ((MangaRepository) -> MangaRepository)? = null,
        backendFactory: (Preferences) -> Preferences = { it },
        block: suspend (NativeScene, MangaDetailScreenModel, Manga, List<Chapter>) -> Unit,
    ) {
        val node = Preferences.userRoot().node("mihon-tests/chapter-options-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            root,
            DesktopPreferenceStore(backendFactory(node)),
            startDownloadWorker = false,
            mangaRepositoryOverride = mangaRepositoryOverride,
        )
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = NativeScene(kotlin.coroutines.coroutineContext)
        try {
            val mangas = Injekt.get<MangaRepository>()
            val manga = mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 42L,
                        url = "/chapter-options",
                        title = "Chapter options",
                        initialized = true,
                    ),
                ),
            ).single()
            val chapters = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/alpha",
                        name = "Chapter Alpha",
                        scanlator = "Team A",
                        chapterNumber = 3.0,
                        sourceOrder = 0,
                    ),
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/beta",
                        name = "Chapter Beta",
                        scanlator = "Team B",
                        chapterNumber = 5.0,
                        sourceOrder = 1,
                        read = true,
                        bookmark = true,
                    ),
                ),
            )
            lateinit var model: MangaDetailScreenModel
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt(),
                    LocalDensity provides Density(1f, scene.fontScale),
                ) {
                    ProvideMangaDetailScreenModelFactory({ id ->
                        MangaDetailScreenModelFactory.create(id).also {
                            model =
                                it
                            scene.createdModels += it
                        }
                    }) {
                        DesktopTheme {
                            Navigator(MangaDetailScreen(manga.id)) {
                                scene.navigator = it
                                CurrentScreen()
                            }
                        }
                    }
                }
            }
            render(scene)
            assertEquals(manga.id, model.state.value.manga?.id)
            assertTrue(hasChapter(scene, "Chapter Alpha"), "fixture must render a real chapter before the tested event")
            block(scene, model, manga, chapters)
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private class NativeScene(context: CoroutineContext) : AutoCloseable {
        val owners = linkedSetOf<SemanticsOwner>()
        val createdModels = mutableListOf<MangaDetailScreenModel>()
        lateinit var navigator: Navigator
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
                    override fun onSemanticsOwnerAppended(owner: SemanticsOwner) {
                        owners += owner
                    }
                    override fun onSemanticsOwnerRemoved(owner: SemanticsOwner) {
                        owners -= owner
                    }
                    override fun onSemanticsChange(owner: SemanticsOwner) = Unit
                    override fun onLayoutChange(owner: SemanticsOwner, nodeId: Int) = Unit
                }
            },
            invalidate = {},
        )
        fun setContent(content: @Composable () -> Unit) = scene.setContent(content)
        fun render() {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            scene.render(canvas, System.nanoTime())
        }
        fun sendKeyEvent(event: ComposeKeyEvent) = scene.sendKeyEvent(event)
        fun pointerClick(position: Offset) {
            scene.sendPointerEvent(
                PointerEventType.Press,
                position,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            scene.sendPointerEvent(
                PointerEventType.Release,
                position,
                buttons = PointerButtons(),
                button = PointerButton.Primary,
            )
        }
        fun resize(width: Int, height: Int) {
            windowSize = IntSize(width, height)
            scene.size = windowSize
        }
        fun savePng(file: File) {
            file.parentFile.mkdirs()
            Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                val pixels = ImageIO.read(ByteArrayInputStream(requireNotNull(image.encodeToData()).bytes))
                ImageIO.write(pixels.getSubimage(0, 0, windowSize.width, windowSize.height), "png", file)
            }
        }
        fun snapshot(): java.awt.image.BufferedImage = Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
            ImageIO.read(ByteArrayInputStream(requireNotNull(image.encodeToData()).bytes))
        }
        override fun close() = scene.close()
    }

    private suspend fun render(scene: NativeScene) {
        repeat(16) {
            scene.render()
            delay(15)
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
        return ComposeKeyEvent(
            factory.invoke(null, key.keyCode, eventType, key.nativeKeyLocation, false, false, false, shift, null),
        )
    }
    private fun focused(
        scene: NativeScene,
    ) = activeNodes(scene).single {
        it.config.contains(SemanticsProperties.Focused) &&
            it.config[SemanticsProperties.Focused]
    }

    private fun nodes(scene: NativeScene) = scene.owners.flatMap { flatten(it.rootSemanticsNode) }
    private fun activeNodes(scene: NativeScene) = flatten(scene.owners.last().rootSemanticsNode)
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun labels(node: SemanticsNode): List<String> =
        (
            if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].map {
                    it.text
                }
            } else {
                emptyList()
            }
            ) +
            (
                if (node.config.contains(
                        SemanticsProperties.ContentDescription,
                    )
                ) {
                    node.config[SemanticsProperties.ContentDescription]
                } else {
                    emptyList()
                }
                )
    private fun click(scene: NativeScene, title: String) {
        val node = flatten(scene.owners.last().rootSemanticsNode).first {
            it.config.contains(SemanticsActions.OnClick) && title in labels(it)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun hasChapter(scene: NativeScene, title: String) = nodes(scene).any { title in labels(it) }
}
