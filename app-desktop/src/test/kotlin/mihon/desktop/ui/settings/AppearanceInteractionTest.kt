package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import coil3.SingletonImageLoader
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.theme.colorscheme.AppThemeColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.desktop.DesktopLocalizedNavigatorContent
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.platform.LocalDesktopDateClock
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.home.HomeScreen
import mihon.desktop.ui.library.DesktopDescriptionImageState
import mihon.desktop.ui.library.LibraryScreenModel
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.library.MangaDetailScreenModel
import mihon.desktop.ui.library.ProvideLibraryScreenModelFactory
import mihon.desktop.ui.library.ProvideMangaDetailScreenModelFactory
import mihon.desktop.ui.more.MoreTab
import mihon.desktop.ui.theme.DesktopTheme
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.Long
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import coil3.PlatformContext as CoilPlatformContext

@OptIn(
    ExperimentalComposeUiApi::class,
    InternalComposeUiApi::class,
    ExperimentalCoroutinesApi::class,
    coil3.annotation.DelicateCoilApi::class,
)
@Isolated
class AppearanceInteractionTest {
    @Test
    fun `theme cards are horizontal source previews without an extra heading`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, _, _ ->
                render(scene)
                assertTrue(
                    nodes(scene).any {
                        it.tag("appearance-theme-cards")
                    },
                    "all static themes need the real horizontal card strip",
                )
                assertFalse(
                    nodes(scene).flatMap(::copy).contains(MR.strings.pref_app_theme.localized()),
                    "the custom theme widget has no extra visible heading",
                )
                assertTrue(nodes(scene).any { it.tag("theme-card-DEFAULT") })
                assertFalse(nodes(scene).any { it.tag("theme-card-MONET") })
            }
        }

    @Test
    fun `manual Light removes the complete AMOLED row while preserving its preference`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                deps.appPreferences.themeDarkAmoled.set(true)
                deps.appPreferences.themeMode.set(ThemeMode.LIGHT)
                render(scene)
                assertFalse(MR.strings.pref_dark_theme_pure_black.localized() in nodes(scene).flatMap(::copy))
                assertTrue(deps.appPreferences.themeDarkAmoled.get())
            }
        }

    @Test
    fun `appearance has source ordered display settings and a current date selection does not close`(
        @TempDir root: File,
    ) =
        runBlocking {
            withAppearance(root) { scene, _, store ->
                render(scene)
                val date = nodes(scene).firstOrNull {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.pref_date_format.localized() in copy(it)
                }
                assertNotNull(date, "date preference must be an actual reachable display entry")
                requireNotNull(date!!.config[SemanticsActions.OnClick].action).invoke()
                render(scene)
                val current = nodes(scene).single { it.tag("appearance-choice-date-default") }
                requireNotNull(current.config[SemanticsActions.OnClick].action).invoke()
                render(scene)
                assertTrue(nodes(scene).any { it.tag("appearance-choice-date-default") })
                assertFalse(store.getString("app_date_format", "").isSet())
                click(scene, MR.strings.action_cancel.localized())
                render(scene)
                assertFalse(nodes(scene).any { it.tag("appearance-choice-date-default") })
                assertFalse(store.getString("app_date_format", "").isSet())
            }
        }

    @Test
    fun `language selection stays in a normal child Screen through the real localized Home host`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                val models = mutableListOf<LibraryScreenModel>()
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides deps) {
                        ProvideLibraryScreenModelFactory({ LibraryScreenModelFactory.create().also { models += it } }) {
                            MaterialTheme {
                                Navigator(HomeScreen()) { nav ->
                                    DesktopLocalizedNavigatorContent(deps.localeAdapter, nav)
                                }
                            }
                        }
                    }
                }
                render(scene)
                val originalModel = models.single()
                originalModel.setSearchQuery("Keep this search")
                requireNotNull(
                    nodes(scene).single {
                        it.tag(
                            "desktop-root-${MoreTab.key}",
                        )
                    }.config[SemanticsActions.OnClick].action,
                ).invoke()
                render(scene)
                click(scene, MR.strings.label_settings.localized())
                render(scene)
                click(scene, MR.strings.pref_app_language.localized())
                render(scene)
                assertTrue(
                    nodes(scene).any {
                        it.tag("desktop-language-list")
                    },
                    "language must be a normal child page, not a modal",
                )
                val modelCount = models.size
                val options = deps.localeAdapter.availableLanguages()
                val englishIndex = options.indexOfFirst { it.languageTag == "en" } + 1
                requireNotNull(
                    nodes(scene).single {
                        it.tag("desktop-language-list")
                    }.config[SemanticsActions.ScrollToIndex].action,
                ).invoke(englishIndex)
                render(scene)
                requireNotNull(
                    nodes(scene).single {
                        it.tag("language-en")
                    }.config[SemanticsActions.OnClick].action,
                ).invoke()
                render(scene)
                assertTrue(
                    nodes(scene).any {
                        it.tag("desktop-language-list")
                    },
                    "changing locale must retain Home, More, Settings and the language child navigator",
                )
                assertEquals("en", deps.localeAdapter.activeLanguageTag.value)
                assertEquals(modelCount, models.size, "locale change must not recreate business owners")
                assertEquals("Keep this search", originalModel.state.value.searchQuery)
                assertTrue(MR.strings.pref_app_language.localized(Locale.ENGLISH) in nodes(scene).flatMap(::copy))

                fun back() =
                    requireNotNull(
                        nodes(scene)
                            .last { node ->
                                node.config.contains(SemanticsActions.OnClick) &&
                                    flatten(node).any {
                                        it.config.contains(
                                            SemanticsProperties.ContentDescription,
                                        ) &&
                                            MR.strings.action_bar_up_description.localized() in
                                            it.config[SemanticsProperties.ContentDescription]
                                    }
                            }.config[SemanticsActions.OnClick]
                            .action,
                    ).invoke()
                back()
                render(scene)
                assertTrue(
                    MR.strings.pref_category_appearance.localized(Locale.ENGLISH) in nodes(scene).flatMap(::copy),
                )
                back()
                render(scene)
                assertTrue(
                    MR.strings.label_library.localized(Locale.ENGLISH) in copy(
                        nodes(scene).single {
                            it.tag(
                                "desktop-root-${LibraryTab.key}",
                            )
                        },
                    ),
                )
            }
        }

    @Test
    fun `six saved date patterns render through SQLite chapters in the real detail Screen`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                deps.localeAdapter.select("en")
                deps.appPreferences.relativeTime.set(false)
                val chapters = Injekt.get<ChapterRepository>()
                val mangas = Injekt.get<MangaRepository>()
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 0,
                            url = "/date-detail",
                            title = "Date detail",
                            initialized = true,
                        ),
                    ),
                ).single()
                val date =
                    LocalDate
                        .of(2024, 2, 29)
                        .atTime(12, 0)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            name = "Dated chapter",
                            url = "/dated",
                            chapterNumber = 1.0,
                            dateUpload = date,
                        ),
                    ),
                )
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides deps) {
                        deps.localeAdapter.Provide {
                            MaterialTheme {
                                Navigator(MangaDetailScreen(manga.id)) {
                                    CurrentScreen()
                                }
                            }
                        }
                    }
                }
                render(scene)
                nodes(scene).firstOrNull {
                    it.tag("manga-detail-content")
                }?.let {
                    requireNotNull(it.config[SemanticsActions.ScrollToIndex].action).invoke(4)
                }
                render(scene)
                assertTrue(
                    "Dated chapter" in nodes(scene).flatMap(::copy),
                    "the actual database chapter must be visible",
                )
                listOf(
                    "" to "2/29/24",
                    "MM/dd/yy" to "02/29/24",
                    "dd/MM/yy" to "29/02/24",
                    "yyyy-MM-dd" to "2024-02-29",
                    "dd MMM yyyy" to "29 Feb 2024",
                    "MMM dd, yyyy" to "Feb 29, 2024",
                ).forEach { (pattern, expected) ->
                    deps.appPreferences.dateFormat.set(pattern)
                    render(scene)
                    assertTrue(
                        expected in nodes(scene).flatMap(::copy),
                        "$pattern must render the real chapter date as $expected",
                    )
                }
                deps.localeAdapter.select("fr")
                render(scene)
                assertTrue(
                    nodes(scene).flatMap(::copy).any {
                        it.contains("févr.")
                    },
                    "locale changes must update chapter month text immediately",
                )
            }
        }

    @Test
    fun `relative chapter dates use the selected local calendar through the real detail`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                deps.localeAdapter.select("en")
                deps.appPreferences.dateFormat.set("yyyy-MM-dd")
                val zone = ZoneId.of("America/New_York")
                val clock = Clock.fixed(Instant.parse("2024-03-11T03:30:00Z"), zone)
                val today = LocalDate.now(clock)
                val chapters = Injekt.get<ChapterRepository>()
                val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 0,
                            url = "/relative-detail",
                            title = "Relative detail",
                            initialized = true,
                        ),
                    ),
                ).single()
                val chapter = chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            name = "Relative chapter",
                            url = "/relative",
                            chapterNumber = 1.0,
                            dateUpload = clock.millis(),
                        ),
                    ),
                ).single()
                scene.setContent {
                    CompositionLocalProvider(
                        LocalDesktopUiDependencies provides deps,
                        LocalDesktopDateClock provides clock,
                    ) {
                        deps.localeAdapter.Provide {
                            MaterialTheme {
                                Navigator(MangaDetailScreen(manga.id)) {
                                    CurrentScreen()
                                }
                            }
                        }
                    }
                }
                render(scene)
                nodes(scene).firstOrNull {
                    it.tag("manga-detail-content")
                }?.let {
                    requireNotNull(it.config[SemanticsActions.ScrollToIndex].action).invoke(4)
                }
                render(scene)
                assertTrue(
                    MR.strings.relative_time_today.localized() in nodes(scene).flatMap(::copy),
                    "the actual chapter must consume the relative preference and local clock",
                )
                listOf(
                    -7L to MR.plurals.upcoming_relative_time.localized(Locale.ENGLISH, 7, 7),
                    -8L to today.plusDays(8).toString(),
                    6L to MR.plurals.relative_time.localized(Locale.ENGLISH, 6, 6),
                    7L to today.minusDays(7).toString(),
                ).forEach { (offset, expected) ->
                    val date =
                        today
                            .minusDays(offset)
                            .atTime(12, 0)
                            .atZone(zone)
                            .toInstant()
                            .toEpochMilli()
                    chapters.update(ChapterUpdate(chapter.id, dateUpload = date))
                    render(scene)
                    assertTrue(expected in nodes(scene).flatMap(::copy), "calendar offset $offset must be $expected")
                }
                deps.appPreferences.relativeTime.set(false)
                render(scene)
                assertTrue(today.minusDays(7).toString() in nodes(scene).flatMap(::copy))
            }
        }

    @Test
    fun `native appearance dialogs cycle focus in both directions and Escape only dismisses their owner`(
        @TempDir root: File,
    ) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                deps.localeAdapter.select("en")
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides deps) {
                        MaterialTheme {
                            Navigator(SettingsRootScreen()) {
                                CurrentScreen()
                            }
                        }
                    }
                }
                render(scene)
                for (
                (kind, title, current) in listOf(
                    Triple("date", MR.strings.pref_date_format, "default"),
                    Triple("tablet", MR.strings.pref_tablet_ui_mode, "AUTOMATIC"),
                )
                ) {
                    click(scene, title.localized())
                    render(scene)
                    assertEquals(
                        2,
                        scene.semanticsOwners.size,
                        "the actual native dialog must have its own semantics owner",
                    )
                    val dialogOwner = scene.semanticsOwners.last()

                    fun dialogNodes() = flatten(dialogOwner.unmergedRootSemanticsNode)
                    val controls = dialogNodes().filter {
                        it.config.contains(SemanticsActions.OnClick) &&
                            it.config.contains(SemanticsProperties.Focused)
                    }.map {
                        it.id
                    }.toSet()
                    assertEquals(
                        if (kind == "date") {
                            7
                        } else {
                            5
                        },
                        controls.size,
                        "all source options and Cancel must be keyboard targets",
                    )
                    val initial = dialogNodes().single { it.tag("appearance-choice-$kind-$current") }
                    requireNotNull(initial.config[SemanticsActions.RequestFocus].action).invoke()
                    render(scene)
                    for (shift in listOf(false, true)) {
                        val start = dialogNodes().single {
                            it.config.contains(SemanticsProperties.Focused) &&
                                it.config[SemanticsProperties.Focused]
                        }.id
                        val visited = linkedSetOf<Int>()
                        var returned = false
                        repeat(controls.size + 2) {
                            if (!returned) {
                                scene.sendKeyEvent(keyEvent(Key.Tab, KeyEventType.KeyDown, shift = shift))
                                scene.sendKeyEvent(keyEvent(Key.Tab, KeyEventType.KeyUp, shift = shift))
                                render(scene)
                                val active = dialogNodes().single {
                                    it.config.contains(SemanticsProperties.Focused) &&
                                        it.config[SemanticsProperties.Focused]
                                }
                                visited += active.id
                                returned = active.id == start
                                assertTrue(scene.semanticsOwners.last() === dialogOwner)
                            }
                        }
                        assertTrue(returned, "the actual dialog must wrap focus shift=$shift")
                        assertTrue(
                            visited.containsAll(controls),
                            "the complete keyboard loop must reach every option and Cancel shift=$shift",
                        )
                    }
                    scene.sendKeyEvent(keyEvent(Key.Spacebar, KeyEventType.KeyDown))
                    scene.sendKeyEvent(keyEvent(Key.Spacebar, KeyEventType.KeyUp))
                    render(scene)
                    assertEquals(2, scene.semanticsOwners.size, "current choice activation must keep the dialog")
                    assertFalse(deps.appPreferences.dateFormat.isSet())
                    assertFalse(deps.appPreferences.tabletUiMode.isSet())
                    assertFalse(deps.appPreferences.themeMode.isSet())
                    assertTrue(deps.appPreferences.imagesInDescription.get())
                    scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyDown))
                    scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyUp))
                    render(scene)
                    assertEquals(1, scene.semanticsOwners.size)
                    assertTrue(
                        nodes(scene).any {
                            it.tag("appearance-theme-cards")
                        },
                        "Escape must retain the mounted Appearance route",
                    )
                    assertTrue(
                        nodes(scene).any {
                            it.config.contains(SemanticsProperties.Focused) &&
                                it.config[SemanticsProperties.Focused] && title.localized() in copy(it)
                        },
                    )
                }
            }
        }

    @Test
    fun `real system theme signal recolors a mounted Appearance without resetting navigation focus or scrolling`(
        @TempDir root: File,
    ) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                scene.resize(IntSize(1400, 400))
                deps.localeAdapter.select("en")
                var signal by mutableStateOf(SystemTheme.Light)
                lateinit var navigator: Navigator
                lateinit var colors: ColorScheme
                scene.setContent {
                    CompositionLocalProvider(
                        LocalDesktopUiDependencies provides deps,
                        LocalSystemTheme provides signal,
                    ) {
                        DesktopTheme {
                            colors = MaterialTheme.colorScheme
                            Navigator(AppearanceSettingsScreen()) { nav ->
                                navigator = nav
                                CurrentScreen()
                            }
                        }
                    }
                }
                render(scene)
                click(scene, MR.strings.pref_dark_theme_pure_black.localized())
                render(scene)
                val strip = nodes(scene).single { it.tag("appearance-theme-cards") }
                requireNotNull(strip.config[SemanticsActions.ScrollToIndex].action).invoke(8)
                val scroller = nodes(scene).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                requireNotNull(scroller.config[SemanticsActions.ScrollBy].action).invoke(0f, 160f)
                render(scene)
                val date = nodes(scene).last {
                    it.config.contains(SemanticsActions.OnClick) &&
                        MR.strings.pref_date_format.localized() in copy(it)
                }
                requireNotNull(date.config[SemanticsActions.RequestFocus].action).invoke()
                render(scene)
                // Focus can animate bring-into-view; capture the stable position before changing theme.
                var previousScroll: Float? = null
                kotlinx.coroutines.withTimeout(2_000) {
                    var settled = false
                    while (!settled) {
                        val before = previousScroll
                        render(scene)
                        previousScroll = nodes(scene).first {
                            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                        }.config[SemanticsProperties.VerticalScrollAxisRange].value()
                        settled = previousScroll == before
                    }
                }
                val owner = navigator
                val page = navigator.lastItem
                val vertical = nodes(scene).first {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                }.config[SemanticsProperties.VerticalScrollAxisRange].value()
                val horizontal = nodes(scene).single {
                    it.tag("appearance-theme-cards")
                }.config[SemanticsProperties.HorizontalScrollAxisRange].value()
                assertTrue(vertical > 0 && horizontal > 0)
                for (system in listOf(SystemTheme.Dark, SystemTheme.Light)) {
                    signal = system
                    render(scene)
                    assertEquals(
                        if (system == SystemTheme.Dark) {
                            Color.Black
                        } else {
                            AppThemeColorScheme.colorScheme(AppTheme.DEFAULT, false, true).background
                        },
                        colors.background,
                    )
                    assertSame(owner, navigator)
                    assertSame(page, navigator.lastItem)
                    assertEquals(ThemeMode.SYSTEM, deps.appPreferences.themeMode.get())
                    assertFalse(deps.appPreferences.themeMode.isSet())
                    assertTrue(deps.appPreferences.themeDarkAmoled.get())
                    assertEquals(
                        vertical,
                        nodes(scene).first {
                            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                        }.config[SemanticsProperties.VerticalScrollAxisRange].value(),
                    )
                    assertEquals(
                        horizontal,
                        nodes(scene).single {
                            it.tag("appearance-theme-cards")
                        }.config[SemanticsProperties.HorizontalScrollAxisRange].value(),
                    )
                    assertTrue(
                        nodes(scene).any {
                            it.config.contains(SemanticsProperties.Focused) &&
                                it.config[SemanticsProperties.Focused] &&
                                MR.strings.pref_date_format.localized() in copy(
                                    it,
                                )
                        },
                    )
                }
            }
        }

    @Test
    fun `appearance UI catches real before put and after flush failures restores authority and retries`(
        @TempDir root: File,
    ) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                deps.localeAdapter.select("en")
                for (failure in listOf("before", "after")) {
                    for (
                    action in listOf("mode", "theme", "amoled", "relative", "images", "date", "tablet")
                    ) {
                        scene.setContent { }
                        render(scene)
                        val backend = FaultPreferences()
                        val preferences = DesktopAppPreferences(DesktopPreferenceStore(backend))
                        val owner = deps.copy(appPreferences = preferences)
                        val target: Preference<*> =
                            when (action) {
                                "mode" -> preferences.themeMode
                                "theme" -> preferences.appTheme
                                "amoled" -> preferences.themeDarkAmoled
                                "relative" -> preferences.relativeTime
                                "images" -> preferences.imagesInDescription
                                "date" -> preferences.dateFormat
                                else -> preferences.tabletUiMode
                            }
                        val old = target.get()
                        scene.setContent {
                            CompositionLocalProvider(LocalDesktopUiDependencies provides owner) {
                                MaterialTheme {
                                    Navigator(AppearanceSettingsScreen()) {
                                        CurrentScreen()
                                    }
                                }
                            }
                        }
                        render(scene)
                        if (action == "theme") {
                            requireNotNull(
                                nodes(scene).single {
                                    it.tag("appearance-theme-cards")
                                }.config[SemanticsActions.ScrollToIndex].action,
                            ).invoke(12)
                            render(scene)
                        }
                        if (action in listOf("date", "tablet")) {
                            click(
                                scene,
                                if (action == "date") {
                                    MR.strings.pref_date_format.localized()
                                } else {
                                    MR.strings.pref_tablet_ui_mode.localized()
                                },
                            )
                            render(scene)
                        }

                        fun trigger() =
                            when (action) {
                                "mode" -> click(scene, MR.strings.theme_light.localized())
                                "theme" -> requireNotNull(
                                    flatten(
                                        nodes(scene).single {
                                            it.tag("theme-card-TOKYONIGHT")
                                        },
                                    ).single {
                                        it.config.contains(SemanticsActions.OnClick)
                                    }.config[SemanticsActions.OnClick].action,
                                ).invoke()
                                "amoled" -> click(scene, MR.strings.pref_dark_theme_pure_black.localized())
                                "relative" -> click(scene, MR.strings.pref_relative_format.localized())
                                "images" -> click(scene, MR.strings.pref_display_images_description.localized())
                                else -> requireNotNull(
                                    flatten(
                                        nodes(scene).single {
                                            it.tag(
                                                if (action == "date") {
                                                    "appearance-choice-date-yyyy-MM-dd"
                                                } else {
                                                    "appearance-choice-tablet-ALWAYS"
                                                },
                                            )
                                        },
                                    ).first {
                                        it.config.contains(SemanticsActions.OnClick)
                                    }.config[SemanticsActions.OnClick].action,
                                ).invoke()
                            }
                        backend.failure = failure
                        assertDoesNotThrow({ trigger() }, "real UI $action $failure failure must not escape")
                        render(scene)
                        assertEquals(old, target.get(), "real UI $action $failure restores the old authority")
                        assertFalse(target.isSet(), "default/unset $action $failure must remain unset")
                        assertTrue(
                            MR.strings.desktop_appearance_save_failed.localized() in nodes(scene).flatMap(::copy),
                            "failure feedback $action $failure must be visible",
                        )
                        trigger()
                        render(scene)
                        assertTrue(target.isSet(), "a retry of $action $failure must persist")
                    }
                }
            }
        }

    @Test
    fun `failed rollback shows actual persisted authority and remains retryable`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                val backend = FaultPreferences()
                val preferences = DesktopAppPreferences(DesktopPreferenceStore(backend))
                scene.setContent {
                    CompositionLocalProvider(
                        LocalDesktopUiDependencies provides deps.copy(appPreferences = preferences),
                    ) {
                        MaterialTheme { Navigator(AppearanceSettingsScreen()) { CurrentScreen() } }
                    }
                }
                render(scene)
                backend.failure = "unrestorable"
                assertDoesNotThrow { click(scene, MR.strings.theme_light.localized()) }
                render(scene)
                assertEquals(
                    ThemeMode.LIGHT,
                    preferences.themeMode.get(),
                    "a failed restoration must not invent the old persisted value",
                )
                assertTrue(
                    nodes(scene).any {
                        it.config.contains(SemanticsProperties.Selected) &&
                            it.config[SemanticsProperties.Selected] &&
                            MR.strings.theme_light.localized() in copy(it)
                    },
                )
                assertTrue(MR.strings.desktop_appearance_save_failed.localized() in nodes(scene).flatMap(::copy))
                backend.failure = null
                click(scene, MR.strings.theme_dark.localized())
                render(scene)
                assertEquals(ThemeMode.DARK, preferences.themeMode.get())
            }
        }

    @Test
    fun `legacy Monet fallback preserves its raw preference and real mode card events recolor the application`(
        @TempDir root: File,
    ) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                deps.localeAdapter.select("en")
                deps.appPreferences.appTheme.set(AppTheme.MONET)
                lateinit var colors: ColorScheme
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides deps) {
                        DesktopTheme {
                            colors = MaterialTheme.colorScheme
                            Navigator(AppearanceSettingsScreen()) { CurrentScreen() }
                        }
                    }
                }
                render(scene)
                assertEquals(AppTheme.MONET, deps.appPreferences.appTheme.get())
                assertTrue(MR.strings.desktop_dynamic_theme_unavailable.localized() in nodes(scene).flatMap(::copy))
                assertFalse(nodes(scene).any { it.tag("theme-card-MONET") })
                assertTrue(
                    flatten(
                        nodes(scene).single {
                            it.tag("theme-card-DEFAULT")
                        },
                    ).any {
                        it.config.contains(SemanticsProperties.ContentDescription) &&
                            MR.strings.selected.localized() in it.config[SemanticsProperties.ContentDescription]
                    },
                )
                click(scene, MR.strings.theme_dark.localized())
                render(scene)
                assertEquals(ThemeMode.DARK, deps.appPreferences.themeMode.get())
                assertEquals(
                    AppThemeColorScheme.colorScheme(AppTheme.DEFAULT, true, false).background,
                    colors.background,
                )
                requireNotNull(
                    nodes(scene).single {
                        it.tag("appearance-theme-cards")
                    }.config[SemanticsActions.ScrollToIndex].action,
                ).invoke(12)
                render(scene)
                val tokyo = nodes(scene).single { it.tag("theme-card-TOKYONIGHT") }
                requireNotNull(
                    flatten(tokyo).single {
                        it.config.contains(SemanticsActions.OnClick)
                    }.config[SemanticsActions.OnClick].action,
                ).invoke()
                render(scene)
                assertEquals(AppTheme.TOKYONIGHT, deps.appPreferences.appTheme.get())
                assertEquals(AppThemeColorScheme.colorScheme(AppTheme.TOKYONIGHT, true, false).primary, colors.primary)
                assertFalse(MR.strings.desktop_dynamic_theme_unavailable.localized() in nodes(scene).flatMap(::copy))
                click(scene, MR.strings.pref_dark_theme_pure_black.localized())
                render(scene)
                assertEquals(Color.Black, colors.background)
                click(scene, MR.strings.theme_light.localized())
                render(scene)
                assertEquals(ThemeMode.LIGHT, deps.appPreferences.themeMode.get())
                assertTrue(deps.appPreferences.themeDarkAmoled.get())
                assertFalse(MR.strings.pref_dark_theme_pure_black.localized() in nodes(scene).flatMap(::copy))
                assertEquals(
                    AppThemeColorScheme.colorScheme(AppTheme.TOKYONIGHT, false, true).background,
                    colors.background,
                )
                click(scene, MR.strings.theme_system.localized())
                render(scene)
                assertEquals(ThemeMode.SYSTEM, deps.appPreferences.themeMode.get())
                assertTrue(MR.strings.pref_dark_theme_pure_black.localized() in nodes(scene).flatMap(::copy))
                val languageScreen: Screen = LanguageSettingsScreen()
                assertFalse(languageScreen is Tab)
            }
        }

    @Test
    fun `all fourteen source previews consume real light dark and AMOLED roles offscreen`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                deps.localeAdapter.select("en")
                scene.resize(IntSize(980, 620))
                val themes =
                    eu.kanade.domain.ui.model
                        .selectableAppThemes(false)
                val roles = linkedMapOf<AppTheme, ColorScheme>()
                for (
                (name, dark, amoled) in listOf(
                    Triple("light", false, false),
                    Triple("dark", true, false),
                    Triple("amoled", true, true),
                )
                ) {
                    roles.clear()
                    scene.setContent {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides deps) {
                            MaterialTheme {
                                Column(Modifier.then(Modifier)) {
                                    themes.chunked(7).forEach { row ->
                                        Row {
                                            row.forEach { theme ->
                                                val expected = AppThemeColorScheme.colorScheme(theme, dark, amoled)
                                                MaterialTheme(colorScheme = expected) {
                                                    roles[theme] = MaterialTheme.colorScheme
                                                    Column(Modifier.width(140.dp).padding(13.dp)) {
                                                        AppearanceThemePreview(true) { }
                                                        Text(theme.name, maxLines = 2)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    render(scene)
                    assertEquals(14, roles.size)
                    themes.forEachIndexed { index, theme ->
                        val actual = requireNotNull(roles[theme])
                        val expected =
                            mihon.desktop.ui.theme
                                .desktopColorScheme(theme, if (dark) ThemeMode.DARK else ThemeMode.LIGHT, false, amoled)
                        val getters = ColorScheme::class.java.declaredMethods.filter {
                            it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long.TYPE
                        }
                        assertTrue(getters.size >= 36)
                        getters.forEach { getter ->
                            assertEquals(
                                getter.invoke(expected),
                                getter.invoke(actual),
                                "actual Material3 role ${getter.name} $theme $name",
                            )
                        }
                        if (amoled) {
                            assertEquals(Color.Black, actual.background)
                            assertEquals(Color.White, actual.onSurface)
                            assertEquals(Color(0xFF0C0C0C), actual.surfaceContainerLowest)
                            assertEquals(Color(0xFF131313), actual.surfaceContainerHigh)
                            assertEquals(Color(0xFF1B1B1B), actual.surfaceContainerHighest)
                        }
                        val cardX = (index % 7) * 140 + 13
                        val cardY = (index / 7) * 271 + 13
                        if (index < 7) {
                            assertEquals(
                                actual.background.toArgb(),
                                scene.pixel(cardX + 95, cardY + 85),
                                "the production preview paints the actual background $theme $name",
                            )
                        }
                    }
                    scene.savePng(File(visualDirectory(root), "ri03-themes-$name.png"))
                }
            }
        }

    @Test
    fun `320dp and double font keep horizontal cards switches and dialog keyboard controls reachable`(
        @TempDir root: File,
    ) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                scene.resize(IntSize(320, 800))
                deps.localeAdapter.select("en")
                val captures = mutableListOf<File>()
                for (
                (name, mode, amoled) in listOf(
                    Triple("light", ThemeMode.LIGHT, false),
                    Triple("dark", ThemeMode.DARK, false),
                    Triple("amoled", ThemeMode.DARK, true),
                )
                ) {
                    deps.appPreferences.themeMode.set(mode)
                    deps.appPreferences.themeDarkAmoled.set(amoled)
                    scene.setContent {
                        CompositionLocalProvider(
                            LocalDesktopUiDependencies provides deps,
                            LocalDensity provides Density(1f, 2f),
                        ) {
                            DesktopTheme { Navigator(AppearanceSettingsScreen()) { CurrentScreen() } }
                        }
                    }
                    render(scene)
                    val modeBounds =
                        listOf(MR.strings.theme_system, MR.strings.theme_light, MR.strings.theme_dark).map { resource ->
                            nodes(scene).last {
                                it.config.contains(SemanticsActions.OnClick) &&
                                    resource.localized() in copy(it)
                            }.boundsInRoot
                        }
                    assertEquals(
                        1,
                        modeBounds.map {
                            it.top
                        }.distinct().size,
                        "all three source mode controls must share a top edge at 200% font",
                    )
                    assertEquals(
                        1,
                        modeBounds.map {
                            it.bottom
                        }.distinct().size,
                        "all three source mode controls must have equal reachable heights at 200% font",
                    )
                    val cards = nodes(scene).single { it.tag("appearance-theme-cards") }
                    requireNotNull(cards.config[SemanticsActions.ScrollToIndex].action).invoke(13)
                    render(scene)
                    assertTrue(nodes(scene).any { it.tag("theme-card-MONOCHROME") })
                    File(root, "$name-top.png").also {
                        scene.savePng(it)
                        captures += it
                    }
                    val scroll = nodes(scene).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 10000f)
                    render(scene)
                    val images = nodes(scene).last {
                        it.config.contains(SemanticsActions.OnClick) &&
                            MR.strings.pref_display_images_description.localized() in copy(it)
                    }
                    assertTrue(images.boundsInRoot.height >= 48f)
                    assertTrue(images.boundsInRoot.top >= 0 && images.boundsInRoot.bottom <= 800)
                    requireNotNull(images.config[SemanticsActions.RequestFocus].action).invoke()
                    scene.sendKeyEvent(keyEvent(Key.Spacebar, KeyEventType.KeyDown))
                    scene.sendKeyEvent(keyEvent(Key.Spacebar, KeyEventType.KeyUp))
                    render(scene)
                    assertFalse(deps.appPreferences.imagesInDescription.get())
                    deps.appPreferences.imagesInDescription.set(true)
                    val dateTrigger = nodes(scene).last {
                        it.config.contains(SemanticsActions.OnClick) &&
                            MR.strings.pref_date_format.localized() in copy(it)
                    }
                    requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(
                        0f,
                        dateTrigger.boundsInRoot.top - 100f,
                    )
                    render(scene)
                    val visibleDateTrigger = nodes(scene).last {
                        it.config.contains(SemanticsActions.OnClick) &&
                            MR.strings.pref_date_format.localized() in copy(it)
                    }
                    assertTrue(
                        visibleDateTrigger.boundsInRoot.top >= 0f && visibleDateTrigger.boundsInRoot.bottom <= 800f,
                    )
                    click(scene, MR.strings.pref_date_format.localized())
                    render(scene)
                    val dialogList = nodes(scene).first {
                        it.config.contains(SemanticsActions.ScrollToIndex) &&
                            !it.tag("appearance-theme-cards")
                    }
                    requireNotNull(dialogList.config[SemanticsActions.ScrollToIndex].action).invoke(5)
                    render(scene)
                    val lastDate = nodes(scene).single { it.tag("appearance-choice-date-MMM dd, yyyy") }
                    assertTrue(lastDate.boundsInRoot.height >= 48f)
                    assertTrue(lastDate.boundsInRoot.top >= 0 && lastDate.boundsInRoot.bottom <= 800)
                    val cancel = nodes(scene).last {
                        it.config.contains(SemanticsActions.OnClick) &&
                            MR.strings.action_cancel.localized() in copy(it)
                    }
                    assertTrue(cancel.boundsInRoot.height >= 48f)
                    assertTrue(cancel.boundsInRoot.top >= 0f && cancel.boundsInRoot.bottom <= 800f)
                    File(root, "$name-date.png").also {
                        scene.savePng(it)
                        captures += it
                    }
                    repeat(3) {
                        scene.sendKeyEvent(keyEvent(Key.Tab, KeyEventType.KeyDown))
                        scene.sendKeyEvent(keyEvent(Key.Tab, KeyEventType.KeyUp))
                        render(scene)
                        assertFalse(
                            nodes(scene).any {
                                it.config.contains(SemanticsProperties.Focused) &&
                                    it.config[SemanticsProperties.Focused] &&
                                    it.config.contains(SemanticsActions.OnClick) &&
                                    MR.strings.pref_date_format.localized() in copy(it) &&
                                    !it.tag("appearance-choice-date-MMM dd, yyyy")
                            },
                        )
                    }
                    scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyDown))
                    scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyUp))
                    render(scene)
                    assertFalse(deps.appPreferences.dateFormat.isSet())
                    click(scene, MR.strings.pref_tablet_ui_mode.localized())
                    render(scene)
                    val tabletList = nodes(scene).first {
                        it.config.contains(SemanticsActions.ScrollToIndex) &&
                            !it.tag("appearance-theme-cards")
                    }
                    requireNotNull(tabletList.config[SemanticsActions.ScrollToIndex].action).invoke(3)
                    render(scene)
                    val lastTablet = nodes(scene).single { it.tag("appearance-choice-tablet-NEVER") }
                    val tabletCancel = nodes(scene).last {
                        it.config.contains(SemanticsActions.OnClick) &&
                            MR.strings.action_cancel.localized() in copy(it)
                    }
                    for (control in listOf(lastTablet, tabletCancel)) {
                        assertTrue(control.boundsInRoot.height >= 48f)
                        assertTrue(control.boundsInRoot.top >= 0f && control.boundsInRoot.bottom <= 800f)
                    }
                    click(scene, MR.strings.action_cancel.localized())
                    render(scene)
                    assertFalse(deps.appPreferences.tabletUiMode.isSet())
                    assertTrue(
                        nodes(scene).any {
                            it.config.contains(SemanticsProperties.Focused) &&
                                it.config[SemanticsProperties.Focused] &&
                                MR.strings.pref_tablet_ui_mode.localized() in copy(
                                    it,
                                )
                        },
                    )
                }
                val contact = BufferedImage(960, 1600, BufferedImage.TYPE_INT_ARGB)
                val graphics = contact.createGraphics()
                captures.forEachIndexed {
                        index,
                        file,
                    ->
                    graphics.drawImage(ImageIO.read(file), (index / 2) * 320, (index % 2) * 800, null)
                }
                graphics.dispose()
                ImageIO.write(contact, "png", File(visualDirectory(root), "ri03-320dp-font200.png"))
            }
        }

    private fun visualDirectory(root: File): File = File(
        System.getenv("MIHON_RI03_VISUAL_DIR") ?: root.resolve("visual").absolutePath,
    ).also {
        it.mkdirs()
    }

    @Test
    fun `appearance choice dismissal restores focus and only a different value is saved`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                render(scene)
                val dateTitle = MR.strings.pref_date_format.localized()
                for (dismiss in listOf("cancel", "escape", "mask")) {
                    click(scene, dateTitle)
                    render(scene)
                    val current = nodes(scene).single { it.tag("appearance-choice-date-default") }
                    requireNotNull(
                        flatten(current).first {
                            it.config.contains(SemanticsActions.OnClick)
                        }.config[SemanticsActions.OnClick].action,
                    ).invoke()
                    render(scene)
                    assertTrue(nodes(scene).any { it.tag("appearance-choice-date-default") })
                    when (dismiss) {
                        "cancel" -> {
                            click(scene, MR.strings.action_cancel.localized())
                        }
                        "escape" -> {
                            scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyDown))
                            scene.sendKeyEvent(keyEvent(Key.Escape, KeyEventType.KeyUp))
                        }
                        "mask" -> {
                            scene.pointer(PointerEventType.Press, Offset(1f, 1f), true, PointerButton.Primary)
                            scene.pointer(PointerEventType.Release, Offset(1f, 1f), false, PointerButton.Primary)
                        }
                    }
                    render(scene)
                    assertFalse(nodes(scene).any { it.tag("appearance-choice-date-default") }, "dismiss $dismiss")
                    assertFalse(deps.appPreferences.dateFormat.isSet())
                    assertTrue(
                        nodes(scene).any {
                            it.config.contains(SemanticsProperties.Focused) &&
                                it.config[SemanticsProperties.Focused] && dateTitle in copy(it)
                        },
                        "dismiss $dismiss restores the trigger focus",
                    )
                }
                click(scene, dateTitle)
                render(scene)
                requireNotNull(
                    flatten(
                        nodes(scene).single {
                            it.tag("appearance-choice-date-yyyy-MM-dd")
                        },
                    ).first {
                        it.config.contains(SemanticsActions.OnClick)
                    }.config[SemanticsActions.OnClick].action,
                ).invoke()
                render(scene)
                assertEquals("yyyy-MM-dd", deps.appPreferences.dateFormat.get())
                assertFalse(nodes(scene).any { it.tag("appearance-choice-date-yyyy-MM-dd") })
                assertTrue(
                    nodes(scene).any {
                        it.config.contains(SemanticsProperties.Focused) &&
                            it.config[SemanticsProperties.Focused] && dateTitle in copy(it)
                    },
                )
                click(scene, MR.strings.pref_tablet_ui_mode.localized())
                render(scene)
                requireNotNull(
                    flatten(
                        nodes(scene).single {
                            it.tag("appearance-choice-tablet-AUTOMATIC")
                        },
                    ).first {
                        it.config.contains(SemanticsActions.OnClick)
                    }.config[SemanticsActions.OnClick].action,
                ).invoke()
                render(scene)
                assertFalse(deps.appPreferences.tabletUiMode.isSet())
                assertTrue(nodes(scene).any { it.tag("appearance-choice-tablet-AUTOMATIC") })
                click(scene, MR.strings.action_cancel.localized())
            }
        }

    @Test
    fun `all new appearance display entries open their real highlighted search targets`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                deps.localeAdapter.select("en")
                for (
                resource in listOf(
                    MR.strings.pref_app_language,
                    MR.strings.pref_tablet_ui_mode,
                    MR.strings.pref_date_format,
                    MR.strings.pref_relative_format,
                    MR.strings.pref_display_images_description,
                )
                ) {
                    scene.setContent { }
                    render(scene)
                    lateinit var navigator: Navigator
                    scene.setContent {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides deps) {
                            MaterialTheme {
                                Navigator(SettingsSearchScreen()) { nav ->
                                    navigator = nav
                                    CurrentScreen()
                                }
                            }
                        }
                    }
                    render(scene)
                    val title = resource.localized()
                    requireNotNull(
                        nodes(scene).single {
                            it.config.contains(SemanticsActions.SetText)
                        }.config[SemanticsActions.SetText].action,
                    ).invoke(AnnotatedString(title))
                    render(scene)
                    assertTrue(
                        nodes(scene).any {
                            it.config.contains(SemanticsActions.OnClick) &&
                                title in copy(it)
                        },
                        "catalog must expose $title",
                    )
                    click(scene, title)
                    render(scene)
                    assertTrue(navigator.lastItem is AppearanceSettingsScreen)
                    assertTrue(
                        nodes(scene).any {
                            it.config.contains(
                                DesktopSettingsAnchorHighlighted,
                            ) && it.config[DesktopSettingsAnchorHighlighted] && title in copy(it)
                        },
                        "search must highlight the production entry $title",
                    )
                }
            }
        }

    @Test
    fun `mounted description preserves decoded images links HTML and newlines`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                MockWebServer().also { it.start() }.use { server ->
                    val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
                    for (x in 0..3) for (y in 0..3) image.setRGB(x, y, 0xFFFF00FF.toInt())
                    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
                    server.dispatcher =
                        object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest): MockResponse =
                                if (request.url.encodedPath == "/illustration.png") {
                                    MockResponse
                                        .Builder()
                                        .setHeader("Content-Type", "image/png")
                                        .body(Buffer().write(png))
                                        .build()
                                } else {
                                    MockResponse.Builder().code(403).build()
                                }
                        }
                    val client =
                        OkHttpClient
                            .Builder()
                            .addInterceptor { chain ->
                                chain.proceed(
                                    chain
                                        .request()
                                        .newBuilder()
                                        .header("X-Mihon-Route", "source")
                                        .build(),
                                )
                            }.build()
                    val original = SingletonImageLoader.get(CoilPlatformContext.INSTANCE)
                    val loader =
                        mihon.desktop.image.createDesktopImageLoader(
                            CoilPlatformContext.INSTANCE,
                            OkHttpClient(),
                            { id ->
                                assertEquals(42L, id)
                                client
                            },
                            { Headers.headersOf("Referer", "https://source.example/") },
                        )
                    SingletonImageLoader.setUnsafe(loader)
                    val opened = mutableListOf<String>()
                    val uriHandler =
                        object : UriHandler {
                            override fun openUri(uri: String) {
                                opened += uri
                            }
                        }
                    try {
                        val imageUrl = server.url("/illustration.png").toString()
                        val failedUrl = server.url("/unavailable.png").toString()
                        val description = "**Intro** with [Details](https://example.com/details)\n" +
                            "Second line\n<b>literal</b>\n\n" +
                            "![Illustration]($imageUrl)\n\n![Unavailable]($failedUrl)"
                        val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                            listOf(
                                Manga.create().copy(
                                    source = 42,
                                    url = "/mounted-description",
                                    title = "Markdown detail",
                                    initialized = true,
                                    description = description,
                                ),
                            ),
                        ).single()
                        val models = mutableListOf<MangaDetailScreenModel>()
                        lateinit var navigator: Navigator
                        scene.setContent {
                            CompositionLocalProvider(
                                LocalDesktopUiDependencies provides deps,
                                LocalUriHandler provides uriHandler,
                            ) {
                                ProvideMangaDetailScreenModelFactory(
                                    { id ->
                                        MangaDetailScreenModelFactory.create(id).also(models::add)
                                    },
                                ) {
                                    MaterialTheme {
                                        Navigator(MangaDetailScreen(manga.id)) { nav ->
                                            navigator = nav
                                            CurrentScreen()
                                        }
                                    }
                                }
                            }
                        }
                        render(scene)
                        val owner = navigator
                        val page = navigator.lastItem

                        fun state(value: String) = nodes(scene).filter {
                            it.config.contains(
                                DesktopDescriptionImageState,
                            ) && it.config[DesktopDescriptionImageState] == value
                        }
                        repeat(20) { if (state("ready").isEmpty() || state("failed").isEmpty()) render(scene) }
                        assertTrue(
                            state("ready").any {
                                it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0
                            },
                            "enabled source image must decode and occupy a real rendered component",
                        )
                        assertTrue(state("failed").isNotEmpty(), "failed image must remain a bounded renderer state")
                        val imageBounds = state("ready").single().boundsInRoot
                        assertEquals(
                            0xFFFF00FF.toInt(),
                            scene.pixel(imageBounds.center.x.toInt(), imageBounds.center.y.toInt()),
                            "the known source image pixel must actually be drawn, not merely decoded",
                        )
                        repeat(2) {
                            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                            assertEquals("source", request.headers["X-Mihon-Route"])
                            assertEquals("https://source.example/", request.headers["Referer"])
                        }

                        suspend fun activate(text: String, url: String) {
                            val node = nodes(scene).first {
                                it.config.contains(SemanticsActions.GetTextLayoutResult) &&
                                    copy(it).any { value ->
                                        value.contains(text)
                                    }
                            }
                            val annotated = node.config[SemanticsProperties.Text].single()
                            assertTrue(
                                annotated.getLinkAnnotations(0, annotated.length).any {
                                    (it.item as? LinkAnnotation.Url)?.url == url
                                },
                            )
                            val layouts = mutableListOf<TextLayoutResult>()
                            requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
                            val point = node.boundsInRoot.topLeft + layouts.single().getBoundingBox(
                                annotated.text.indexOf(text),
                            ).center
                            scene.pointer(PointerEventType.Press, point, true, PointerButton.Primary)
                            scene.pointer(PointerEventType.Release, point, false, PointerButton.Primary)
                            render(scene)
                            assertEquals(url, opened.lastOrNull(), "actual rendered link $text must activate")
                        }
                        activate("Details", "https://example.com/details")
                        deps.appPreferences.imagesInDescription.set(false)
                        render(scene)
                        assertTrue(state("ready").isEmpty() && state("failed").isEmpty())
                        assertTrue(
                            nodes(scene).flatMap(::copy).any {
                                it.contains("Intro with Details\nSecond line\n<b>literal</b>")
                            },
                            "line breaks and disallowed HTML must remain literal source text",
                        )
                        activate("Details", "https://example.com/details")
                        activate("Illustration", imageUrl)
                        activate("Unavailable", failedUrl)
                        assertEquals(
                            null,
                            server.takeRequest(300, TimeUnit.MILLISECONDS),
                            "disabled images and image-link activation must not issue image requests",
                        )
                        deps.appPreferences.imagesInDescription.set(true)
                        render(scene)
                        repeat(20) { if (state("ready").isEmpty()) render(scene) }
                        assertTrue(state("ready").any { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 })
                        val restoredBounds = state("ready").single().boundsInRoot
                        assertEquals(
                            0xFFFF00FF.toInt(),
                            scene.pixel(restoredBounds.center.x.toInt(), restoredBounds.center.y.toInt()),
                        )
                        assertSame(owner, navigator)
                        assertSame(page, navigator.lastItem)
                        assertEquals(1, models.size, "image toggles must keep the actual detail ScreenModel owner")
                    } finally {
                        scene.setContent { }
                        SingletonImageLoader.setUnsafe(original)
                        loader.shutdown()
                        client.dispatcher.executorService.shutdown()
                        client.connectionPool.evictAll()
                    }
                }
            }
        }

    @Test
    fun `detail consumes the same four launch layout modes without changing its owner live`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                deps.localeAdapter.select("en")
                val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 0,
                            url = "/layout-detail",
                            title = "Layout detail",
                            initialized = true,
                        ),
                    ),
                ).single()
                Injekt.get<ChapterRepository>().addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            name = "Layout chapter",
                            url = "/layout",
                            chapterNumber = 1.0,
                        ),
                    ),
                )
                for ((mode, dimensions, expanded) in listOf(
                    Triple(TabletUiMode.AUTOMATIC, IntSize(1400, 900), true),
                    Triple(TabletUiMode.AUTOMATIC, IntSize(800, 500), false),
                    Triple(TabletUiMode.ALWAYS, IntSize(500, 800), true),
                    Triple(TabletUiMode.LANDSCAPE, IntSize(800, 500), true),
                    Triple(TabletUiMode.LANDSCAPE, IntSize(500, 800), false),
                    Triple(TabletUiMode.NEVER, IntSize(1400, 900), false),
                )) {
                    scene.setContent { }
                    render(scene)
                    scene.resize(dimensions)
                    deps.appPreferences.tabletUiMode.set(mode)
                    val owner = DesktopUiDependencies.fromInjekt()
                    scene.setContent {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides owner) {
                            owner.localeAdapter.Provide {
                                MaterialTheme {
                                    Navigator(MangaDetailScreen(manga.id)) {
                                        CurrentScreen()
                                    }
                                }
                            }
                        }
                    }
                    render(scene)
                    assertTrue("Layout detail" in nodes(scene).flatMap(::copy))
                    assertEquals(
                        expanded,
                        nodes(scene).any {
                            it.tag("manga-detail-information")
                        },
                        "detail qualification $mode $dimensions",
                    )
                    if (expanded) {
                        val title = nodes(scene).first {
                            it.config.contains(SemanticsProperties.Text) &&
                                it.config[SemanticsProperties.Text].any { text ->
                                    text.text == "Layout detail"
                                }
                        }
                        val chapter = nodes(scene).first {
                            it.config.contains(SemanticsProperties.Text) &&
                                it.config[SemanticsProperties.Text].any { text ->
                                    text.text == "Layout chapter"
                                }
                        }
                        assertTrue(
                            chapter.boundsInRoot.left > title.boundsInRoot.right,
                            "the real chapter must occupy the right pane",
                        )
                    }
                    deps.appPreferences.tabletUiMode.set(if (expanded) TabletUiMode.NEVER else TabletUiMode.ALWAYS)
                    render(scene)
                    assertEquals(
                        expanded,
                        nodes(scene).any {
                            it.tag("manga-detail-information")
                        },
                        "changing a saved preference cannot rebuild the current owner",
                    )
                }
            }
        }

    @Test
    fun `saved tablet preference only changes Home and Settings in the next launch owner`(@TempDir root: File) =
        runBlocking {
            withAppearance(root) { scene, deps, _ ->
                fun home(owner: DesktopUiDependencies) {
                    scene.setContent {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides owner) {
                            MaterialTheme { Navigator(HomeScreen()) { CurrentScreen() } }
                        }
                    }
                }
                home(deps)
                render(scene)
                assertTrue(nodes(scene).any { it.tag("desktop-root-rail") })
                deps.appPreferences.tabletUiMode.set(TabletUiMode.NEVER)
                render(scene)
                assertTrue(
                    nodes(scene).any {
                        it.tag("desktop-root-rail")
                    },
                    "saving layout must leave the current owner mounted",
                )
                val nextOwner = DesktopUiDependencies.fromInjekt()
                home(nextOwner)
                render(scene)
                assertTrue(
                    nodes(scene).any {
                        it.tag("desktop-root-bar")
                    },
                    "a new launch owner must consume the saved compact mode",
                )
                click(scene, MR.strings.label_more.localized())
                render(scene)
                click(scene, MR.strings.label_settings.localized())
                render(scene)
                assertTrue(nodes(scene).any { it.tag("desktop-settings-directory") })
                assertFalse(
                    nodes(scene).any {
                        it.tag("appearance-theme-cards")
                    },
                    "Settings must consume the same compact launch decision",
                )
            }
        }

    private suspend fun withAppearance(
        root: File,
        block: suspend (NativeScene, DesktopUiDependencies, DesktopPreferenceStore) -> Unit,
    ) {
        val originalLocale = Locale.getDefault()
        val node = Preferences.userRoot().node("mihon-tests/appearance-${UUID.randomUUID()}")
        val store = DesktopPreferenceStore(node)
        val context = initDesktopDIForTest(root, store, startDownloadWorker = false)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val deps = DesktopUiDependencies.fromInjekt()
        val scene = NativeScene(kotlinx.coroutines.currentCoroutineContext())
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides deps) {
                    MaterialTheme { Navigator(AppearanceSettingsScreen()) { CurrentScreen() } }
                }
            }
            block(scene, deps, store)
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
            Locale.setDefault(originalLocale)
        }
    }

    private suspend fun render(scene: NativeScene) =
        repeat(12) {
            scene.render()
            delay(15)
        }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun nodes(scene: NativeScene) = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }

    private fun copy(node: SemanticsNode) =
        flatten(node).flatMap {
            if (it.config.contains(SemanticsProperties.Text)) {
                it.config[SemanticsProperties.Text].map { value ->
                    value.text
                }
            } else {
                emptyList()
            }
        }

    private fun SemanticsNode.tag(value: String) =
        config.contains(SemanticsProperties.TestTag) &&
            config[SemanticsProperties.TestTag] == value

    private fun click(scene: NativeScene, label: String) =
        requireNotNull(
            nodes(scene)
                .last {
                    it.config.contains(SemanticsActions.OnClick) &&
                        label in copy(it)
                }.config[SemanticsActions.OnClick]
                .action,
        ).invoke()

    private fun keyEvent(key: Key, type: KeyEventType, alt: Boolean = false, shift: Boolean = false): ComposeKeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType =
            Class
                .forName("androidx.compose.ui.input.key.KeyEventType")
                .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
                .invoke(null)
        val factory = events.declaredMethods.single {
            it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
        }
        return ComposeKeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, alt, shift, null))
    }

    private class FaultPreferences : AbstractPreferences(null, "") {
        private val values = linkedMapOf<String, String>()
        var failure: String? = null

        override fun putSpi(key: String, value: String) {
            if (failure == "before") {
                failure = null
                throw SecurityException("before put")
            }
            values[key] = value
        }

        override fun getSpi(key: String): String? = values[key]

        override fun removeSpi(key: String) {
            if (failure == "rollback") throw SecurityException("restoration unavailable")
            values.remove(key)
        }

        override fun removeNodeSpi() {
            values.clear()
        }

        override fun keysSpi(): Array<String> = values.keys.toTypedArray()

        override fun childrenNamesSpi(): Array<String> = emptyArray()

        override fun childSpi(name: String): AbstractPreferences = error("no child requested")

        override fun syncSpi() = Unit

        override fun flushSpi() {
            if (failure in listOf("after", "unrestorable")) {
                failure = if (failure == "unrestorable") "rollback" else null
                throw BackingStoreException("after put, during flush")
            }
        }
    }

    private class NativeScene(context: CoroutineContext) : AutoCloseable {
        val semanticsOwners = linkedSetOf<SemanticsOwner>()
        private var windowSize by mutableStateOf(IntSize(1400, 900))
        private val bitmap = ImageBitmap(1400, 900)
        private val canvas = Canvas(bitmap)
        private val scene =
            CanvasLayersComposeScene(
                size = windowSize,
                coroutineContext = context,
                platformContext =
                object : PlatformContext {
                    override val windowInfo =
                        object : WindowInfo {
                            override val isWindowFocused = true
                            override val containerSize get() = windowSize
                            override val containerDpSize get() = DpSize(windowSize.width.dp, windowSize.height.dp)
                        }
                    override val inputModeManager =
                        object : InputModeManager {
                            override val inputMode = InputMode.Keyboard

                            override fun requestInputMode(inputMode: InputMode) = true
                        }

                    override fun requestFocus() = true

                    override val semanticsOwnerListener =
                        object : PlatformContext.SemanticsOwnerListener {
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

        fun pixel(x: Int, y: Int) = bitmap.asSkiaBitmap().getColor(x, y)

        fun savePng(file: File) {
            Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                val png = requireNotNull(image.encodeToData()).bytes
                val rendered = ImageIO.read(ByteArrayInputStream(png))
                ImageIO.write(rendered.getSubimage(0, 0, windowSize.width, windowSize.height), "png", file)
            }
        }

        fun sendKeyEvent(event: ComposeKeyEvent) = scene.sendKeyEvent(event)

        fun pointer(type: PointerEventType, position: Offset, pressed: Boolean, button: PointerButton? = null) =
            scene.sendPointerEvent(
                type,
                position,
                buttons = PointerButtons(isPrimaryPressed = pressed),
                button = button,
            )

        fun resize(size: IntSize) {
            windowSize = size
            scene.size = size
        }

        fun setContent(content: @Composable () -> Unit) = scene.setContent(content)

        fun render() = scene.render(canvas, System.nanoTime())

        override fun close() = scene.close()
    }
}
