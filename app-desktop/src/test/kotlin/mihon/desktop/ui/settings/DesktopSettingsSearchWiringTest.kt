package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.tab.Tab
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.selectableAppThemes
import eu.kanade.presentation.theme.colorscheme.AppThemeColorScheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.platform.DesktopLocaleAdapter
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.ui.theme.DesktopTheme
import mihon.desktop.ui.theme.desktopColorScheme
import mihon.domain.settings.SearchableSettingsScreen
import mihon.domain.settings.SettingsLayoutDirection
import mihon.domain.settings.SettingsSearchPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import java.util.Locale
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
@org.junit.jupiter.api.parallel.Isolated
class DesktopSettingsSearchWiringTest {
    @Test
    fun `catalog preserves fixed-main prefix routes and shared top ten`() {
        val previous = Locale.getDefault()
        assertThrows(IllegalStateException::class.java) { withRestoredLocale { Locale.setDefault(Locale.JAPAN); error("expected") } }
        assertEquals(previous, Locale.getDefault())
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            val screens = DesktopSettingsCatalog.screens()
            val fixedMain = "AppearanceSettingsScreen,LibrarySettingsScreen,ReaderSettingsScreen,DownloadSettingsScreen,TrackingSettingsScreen,BackupSettingsScreen,SecuritySettingsScreen,AdvancedSettingsScreen"
            assertEquals(fixedMain, screens.take(8).joinToString(",") { it.route::class.simpleName.orEmpty() })
            assertEquals(listOf("GeneralSettingsScreen", "ExtensionRepoScreen", "AboutScreen"), screens.drop(8).map { it.route::class.simpleName })
            assertTrue(screens.none { it.route is Tab })
            val originalRoutes = screens.take(8).map { it.route::class }.toSet()
            val results = DesktopSettingsCatalog.search("e")
            assertTrue(results.all { it.route::class in originalRoutes })
            assertTrue(
                DesktopSettingsCatalog.search(MR.strings.desktop_reader_prefetch_next_chapter.localized())
                    .any { it.route is ReaderSettingsScreen },
            )
        }
        assertEquals(previous, Locale.getDefault())
    }
    @Test
    fun `catalog delegates search to shared policy`() {
        mockkObject(SettingsSearchPolicy)
        try {
            val expected = emptyList<mihon.domain.settings.SettingsSearchResult<Screen>>()
            every {
                SettingsSearchPolicy.search(any<List<SearchableSettingsScreen<Screen>>>(), "needle", SettingsLayoutDirection.Ltr)
            } returns expected
            assertSame(expected, DesktopSettingsCatalog.search("needle", SettingsLayoutDirection.Ltr))
            verify(exactly = 1) {
                SettingsSearchPolicy.search(any<List<SearchableSettingsScreen<Screen>>>(), "needle", SettingsLayoutDirection.Ltr)
            }
        } finally {
            unmockkObject(SettingsSearchPolicy)
        }
    }
    @Test
    fun `search has feedback focus submission keys and result navigation`() = runBlocking {
        withRestoredLocale {
            listOf(Locale.US, Locale.forLanguageTag("zh-CN")).forEach { locale ->
                Locale.setDefault(locale)
                withSearchScene { scene ->
                    render(scene)
                    assertTrue(text(scene).contains(MR.strings.desktop_settings_search_empty.localized(locale)))
                    assertTrue(field(scene).config[SemanticsProperties.Focused])
                    setText(scene, "no-such-setting-42")
                    render(scene)
                    assertTrue(text(scene).contains(MR.strings.no_results_found.localized(locale)))
                    requireNotNull(field(scene).config[SemanticsActions.OnImeAction].action).invoke()
                    render(scene)
                    assertFalse(field(scene).config[SemanticsProperties.Focused])
                }
            }
            Locale.setDefault(Locale.US)
            listOf(Key.Enter, Key.NumPadEnter).forEach { key ->
                withSearchScene { scene ->
                    render(scene)
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                    render(scene)
                    assertTrue(field(scene).config[SemanticsProperties.Focused])
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyDown))
                    render(scene)
                    assertFalse(field(scene).config[SemanticsProperties.Focused])
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                }
            }
            withSearchScene { scene ->
                render(scene)
                scene.sendKeyEvent(composeKeyEvent(Key.Spacebar, KeyEventType.KeyDown))
                render(scene)
                assertTrue(field(scene).config[SemanticsProperties.Focused])
                assertEquals(AnnotatedString(""), field(scene).config[SemanticsProperties.EditableText])
            }
            withSearchScene(height = 260) { scene ->
                lateinit var navigator: Navigator
                scene.setContent { dependencies { Navigator(SettingsSearchScreen()) { nav -> navigator = nav; CurrentScreen() } } }
                render(scene)
                val anchorTitle = MR.strings.desktop_appearance_library_grid.localized(Locale.US)
                setText(scene, anchorTitle)
                render(scene)
                val result = action(scene, anchorTitle)
                assertEquals(Role.Button, result.config[SemanticsProperties.Role])
                assertEquals(1, flatten(result).count { it.config.contains(SemanticsActions.OnClick) })
                click(scene, anchorTitle)
                render(scene)
                assertTrue(navigator.lastItem is AppearanceSettingsScreen)
                assertEquals(1, navigator.items.size)
                val highlighted = nodes(scene, true).single { it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted] }
                assertTrue(anchorTitle in text(highlighted))
                val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(scroll.value() > 0f, "scroll=${scroll.value()} highlighted=${highlighted.boundsInRoot}")
                requireNotNull(nodes(scene, true).single { it.config.contains(SemanticsActions.SetProgress) }
                    .config[SemanticsActions.SetProgress].action).invoke(6f)
                assertEquals(6, currentPreferences.libraryGridColumns.get())
            }
            withSearchScene { scene ->
                lateinit var navigator: Navigator
                scene.setContent { dependencies { Navigator(SettingsSearchScreen()) { nav -> navigator = nav; CurrentScreen() } } }
                render(scene)
                val anchorTitle = MR.strings.pref_incognito_mode.localized(Locale.US)
                setText(scene, anchorTitle)
                render(scene)
                click(scene, anchorTitle)
                render(scene)
                assertTrue(navigator.lastItem is GeneralSettingsScreen)
                assertTrue(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted] && flatten(it).any { child -> anchorTitle in text(child) } })
                currentPreferences.incognitoMode.set(false)
                click(scene, anchorTitle)
                assertTrue(currentPreferences.incognitoMode.get())
            }
        }
    }

    @Test
    fun `settings roles activate once on key down and never on key up`() = runBlocking {
        listOf("button", "radio", "switch").forEach { fixture ->
            listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar).forEach { key ->
                val scene = SearchScene(kotlinx.coroutines.currentCoroutineContext(), 300)
                var calls = 0
                try {
                    scene.setContent {
                        MaterialTheme {
                            Column {
                                when (fixture) {
                                    "button" -> SettingsEntry(
                                        Icons.Default.Settings,
                                        "Button",
                                        "Summary",
                                    ) { calls++ }
                                    "radio" -> RadioSettingsItem("Radio", false, { calls++ })
                                    else -> SwitchSettingsItem("Switch", "Summary", false, { calls++ })
                                }
                            }
                        }
                    }
                    render(scene)
                    scene.takeFocus()
                    val target = nodes(scene, true).single { it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(requireNotNull(target.config[SemanticsActions.RequestFocus].action).invoke())
                    render(scene)
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                    render(scene)
                    assertEquals(0, calls, "$fixture $key KeyUp")
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyDown))
                    render(scene)
                    assertEquals(1, calls, "$fixture $key KeyDown")
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                    render(scene)
                    assertEquals(1, calls, "$fixture $key second KeyUp")
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun `settings action focus order follows the rendered rows`() = runBlocking {
        val scene = SearchScene(kotlinx.coroutines.currentCoroutineContext(), 400)
        try {
            scene.setContent {
                MaterialTheme {
                    Column {
                        SettingsEntry(Icons.Default.Settings, "Button", "Summary") {}
                        RadioSettingsItem("Radio", false, {})
                        SwitchSettingsItem("Switch", "Summary", false, {})
                    }
                }
            }
            render(scene)
            fun actions() = nodes(scene, true).filter { it.config.contains(SemanticsActions.OnClick) }
            assertTrue(requireNotNull(actions()[0].config[SemanticsActions.RequestFocus].action).invoke())
            render(scene)
            assertTrue(actions()[0].config[SemanticsProperties.Focused])
            scene.sendKeyEvent(composeKeyEvent(Key.Tab, KeyEventType.KeyDown))
            render(scene)
            assertTrue(actions()[1].config[SemanticsProperties.Focused])
            scene.sendKeyEvent(composeKeyEvent(Key.Tab, KeyEventType.KeyDown))
            render(scene)
            assertTrue(actions()[2].config[SemanticsProperties.Focused])
        } finally {
            scene.close()
        }
    }
    @Test
    fun `reader search anchor scrolls highlights once and preserves mode writes`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            val anchorTitle = MR.strings.desktop_reader_pager_mode.localized(Locale.US)
            withSearchScene(height = 180) { scene ->
                lateinit var navigator: Navigator
                scene.setContent { dependencies { Navigator(SettingsSearchScreen()) { nav -> navigator = nav; CurrentScreen() } } }
                render(scene)
                currentPreferences.defaultReaderMode.set(mihon.desktop.settings.ReaderDefaultMode.WEBTOON)
                setText(scene, anchorTitle)
                render(scene)
                click(scene, anchorTitle)
                render(scene)
                assertTrue(navigator.lastItem is ReaderSettingsScreen)
                val highlighted = nodes(scene, true).single { it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted] }
                assertTrue(flatten(highlighted).any { anchorTitle in text(it) })
                assertTrue(highlighted.boundsInRoot.height > 0f)
                val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(scroll.value() > 0f)
                click(scene, anchorTitle)
                assertEquals(mihon.desktop.settings.ReaderDefaultMode.PAGER, currentPreferences.defaultReaderMode.get())
            }
            withSearchScene(ReaderSettingsScreen(), height = 180) { scene ->
                render(scene)
                assertFalse(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
                val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals(0f, scroll.value())
            }
        }
    }
    @Test
    fun `library search anchor scrolls highlights once and preserves update writes`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            val anchorTitle = MR.strings.pref_category_display.localized(Locale.US)
            withSearchScene(height = 260) { scene ->
                lateinit var navigator: Navigator
                scene.setContent { dependencies { Navigator(SettingsSearchScreen()) { nav -> navigator = nav; CurrentScreen() } } }
                render(scene)
                currentPreferences.libraryUpdateInterval.set(mihon.desktop.settings.LibraryUpdateInterval.OFF)
                setText(scene, anchorTitle)
                render(scene)
                click(scene, anchorTitle)
                render(scene)
                assertTrue(navigator.lastItem is LibrarySettingsScreen)
                val highlighted = nodes(scene, true).single { it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted] }
                assertTrue(anchorTitle in text(highlighted))
                assertTrue(highlighted.boundsInRoot.height > 0f)
                val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(scroll.value() > 0f)
                click(scene, MR.strings.update_6hour.localized(Locale.US))
                assertEquals(mihon.desktop.settings.LibraryUpdateInterval.EVERY_6H, currentPreferences.libraryUpdateInterval.get())
            }
            withSearchScene(LibrarySettingsScreen(), height = 260) { scene ->
                render(scene)
                assertFalse(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
                val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals(0f, scroll.value())
            }
        }
    }
    @Test
    fun `More settings entry opens the settings directory`() = runBlocking {
        withSearchScene(MoreRootScreen()) { scene ->
            lateinit var navigator: Navigator
            scene.setContent { dependencies { Navigator(MoreRootScreen()) { nav -> navigator = nav; CurrentScreen() } } }
            render(scene)
            click(scene, MR.strings.label_settings.localized(Locale.getDefault()))
            assertEquals("SettingsRootScreen", navigator.lastItem::class.simpleName)
            render(scene)
            listOf(
                MR.strings.pref_category_general,
                MR.strings.pref_category_appearance,
                MR.strings.pref_category_library,
                MR.strings.pref_category_reader,
                MR.strings.pref_category_downloads,
                MR.strings.pref_category_tracking,
                MR.strings.browse,
                MR.strings.label_data_storage,
                MR.strings.pref_category_security,
                MR.strings.pref_category_advanced,
                MR.strings.pref_category_about,
            ).forEach { resource ->
                assertTrue(resource.localized(Locale.getDefault()) in text(scene))
            }
            clickDescription(scene, MR.strings.action_search_settings.localized(Locale.getDefault()))
            assertTrue(navigator.lastItem is SettingsSearchScreen)
        }
    }

    @Test
    fun `search top bar stays within compact width and exposes back navigation`() = runBlocking {
        withSearchScene(MoreRootScreen(), width = 320) { scene ->
            lateinit var navigator: Navigator
            scene.setContent { dependencies { Navigator(MoreRootScreen()) { nav -> navigator = nav; CurrentScreen() } } }
            render(scene)
            navigator.push(SettingsSearchScreen())
            render(scene)
            setText(scene, "appearance")
            render(scene)

            val rootBounds = nodes(scene).first().boundsInRoot
            val fieldBounds = field(scene).boundsInRoot
            assertTrue(fieldBounds.left >= rootBounds.left, "field=$fieldBounds root=$rootBounds")
            assertTrue(fieldBounds.right <= rootBounds.right, "field=$fieldBounds root=$rootBounds")
            assertTrue(
                nodes(scene, true).any {
                    it.config.contains(SemanticsProperties.ContentDescription) &&
                        MR.strings.action_bar_up_description.localized(Locale.getDefault()) in
                        it.config[SemanticsProperties.ContentDescription]
                },
            )
        }
    }

    @Test
    fun `desktop theme consumes shared static theme and amoled preferences`() = runBlocking {
        assertSame(
            AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = false, isAmoled = false),
            desktopColorScheme(AppTheme.YINYANG, ThemeMode.SYSTEM, systemIsDark = false, isAmoled = false),
        )
        assertSame(
            AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = true, isAmoled = false),
            desktopColorScheme(AppTheme.YINYANG, ThemeMode.SYSTEM, systemIsDark = true, isAmoled = false),
        )

        withSearchScene { scene ->
            lateinit var observed: ColorScheme
            scene.setContent {
                dependencies {
                    DesktopTheme { observed = MaterialTheme.colorScheme }
                }
            }
            render(scene)
            currentPreferences.themeMode.set(ThemeMode.DARK)
            currentPreferences.appTheme.set(AppTheme.YINYANG)
            currentPreferences.themeDarkAmoled.set(false)
            render(scene)
            assertEquals(
                AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = true, isAmoled = false),
                observed,
            )

            currentPreferences.themeDarkAmoled.set(true)
            render(scene)
            assertEquals(
                AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = true, isAmoled = true).toString(),
                observed.toString(),
            )

            currentPreferences.themeMode.set(ThemeMode.LIGHT)
            render(scene)
            assertSame(
                AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = false, isAmoled = true),
                observed,
            )
        }
    }

    @Test
    fun `appearance selects static theme and amoled while preserving grid`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            withSearchScene(AppearanceSettingsScreen(), height = 2_000) { scene ->
                render(scene)
                val rendered = text(scene)
                selectableAppThemes(dynamicColorAvailable = false).forEach { theme ->
                    assertTrue(requireNotNull(theme.titleRes).localized(Locale.US) in rendered)
                }
                assertFalse(MR.strings.theme_monet.localized(Locale.US) in rendered)
                listOf(AppTheme.DARK_BLUE, AppTheme.HOT_PINK, AppTheme.BLUE).forEach { deprecated ->
                    assertFalse(deprecated.name in rendered)
                }

                click(scene, MR.strings.theme_yinyang.localized(Locale.US))
                assertEquals(AppTheme.YINYANG, currentPreferences.appTheme.get())
                click(scene, MR.strings.pref_dark_theme_pure_black.localized(Locale.US))
                assertTrue(currentPreferences.themeDarkAmoled.get())
                requireNotNull(nodes(scene, true).single { it.config.contains(SemanticsActions.SetProgress) }
                    .config[SemanticsActions.SetProgress].action).invoke(6f)
                assertEquals(6, currentPreferences.libraryGridColumns.get())
            }
        }
    }

    @Test
    fun `appearance theme search entries scroll to production anchors`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            listOf(MR.strings.pref_app_theme, MR.strings.pref_dark_theme_pure_black).forEach { resource ->
                withSearchScene(height = 260) { scene ->
                    lateinit var navigator: Navigator
                    scene.setContent { dependencies { Navigator(SettingsSearchScreen()) { nav -> navigator = nav; CurrentScreen() } } }
                    render(scene)
                    val title = resource.localized(Locale.US)
                    setText(scene, title)
                    render(scene)
                    click(scene, title)
                    render(scene)
                    assertTrue(navigator.lastItem is AppearanceSettingsScreen)
                    val highlighted = nodes(scene, true).single {
                        it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
                    }
                    assertTrue(flatten(highlighted).any { title in text(it) })
                    val scroll = nodes(scene, true).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                        .config[SemanticsProperties.VerticalScrollAxisRange]
                    assertTrue(scroll.value() > 0f)
                }
            }
        }
    }
    private suspend fun withSearchScene(
        screen: Screen = SettingsSearchScreen(),
        width: Int = 900,
        height: Int = 900,
        block: suspend (SearchScene) -> Unit,
    ) {
        val scene = SearchScene(kotlinx.coroutines.currentCoroutineContext(), height, width)
        if (screen is SettingsSearchScreen) {
            val showScreen = mutableStateOf(false)
            scene.setContent {
                dependencies {
                    if (showScreen.value) Navigator(screen) { CurrentScreen() } else BasicTextField("", {})
                }
            }
            render(scene)
            scene.takeFocus()
            showScreen.value = true
        } else {
            scene.setContent { dependencies { Navigator(screen) { CurrentScreen() } } }
        }
        try {
            block(scene)
        } finally {
            scene.close()
        }
    }
    @androidx.compose.runtime.Composable
    private fun dependencies(content: @androidx.compose.runtime.Composable () -> Unit) {
        val downloads = mockk<DesktopDownloadManager> { every { queue } returns MutableStateFlow(emptyList()) }
        currentPreferences = androidx.compose.runtime.remember { mihon.desktop.settings.DesktopAppPreferences(InMemoryPreferenceStore()) }
        currentReaderPreferences = androidx.compose.runtime.remember {
            mihon.desktop.reader.ReaderPreferences(InMemoryPreferenceStore())
        }
        val localeAdapter = androidx.compose.runtime.remember(currentPreferences) {
            DesktopLocaleAdapter(currentPreferences.appLanguage)
        }
        val network = androidx.compose.runtime.remember(currentPreferences) {
            mockk<DesktopNetworkHelper> {
                every { routeObservations } returns MutableStateFlow(emptyList())
                every { activeGlobalMode } returns currentPreferences.globalNetworkMode.get()
                every { activeGlobalProxy } returns currentPreferences.proxyRuntimeConfig()
            }
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns currentPreferences
            every { readerPreferences } returns currentReaderPreferences
            every { this@mockk.localeAdapter } returns localeAdapter
            every { downloadManager } returns downloads
            every { downloadQueuePort } returns downloads
            every { networkHelper } returns network
            every { networkRoutingPort } returns network
        }
        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies, content = content)
    }
    private suspend fun render(scene: SearchScene) = repeat(5) {
        scene.render()
        kotlinx.coroutines.yield()
    }
    private fun composeKeyEvent(key: Key, type: KeyEventType): ComposeKeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        val native = factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null)
        return ComposeKeyEvent(native)
    }
    private fun action(scene: SearchScene, label: String) =
        nodes(scene).single { it.config.contains(SemanticsActions.OnClick) && label in flatten(it).flatMap(::text) }
    private fun field(scene: SearchScene) = nodes(scene, true).single { it.config.contains(SemanticsActions.SetText) }
    private fun setText(scene: SearchScene, value: String) = requireNotNull(field(scene).config[SemanticsActions.SetText].action).invoke(AnnotatedString(value))
    private fun click(scene: SearchScene, label: String) = requireNotNull(
        nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && flatten(it).any { node -> label in text(node) } }
            .config[SemanticsActions.OnClick].action,
    ).invoke()
    private fun clickDescription(scene: SearchScene, description: String) = requireNotNull(
        nodes(scene, true).first {
            it.config.contains(SemanticsActions.OnClick) &&
                flatten(it).any { child ->
                    child.config.contains(SemanticsProperties.ContentDescription) &&
                        description in child.config[SemanticsProperties.ContentDescription]
                }
        }.config[SemanticsActions.OnClick].action,
    ).invoke()
    private fun text(scene: SearchScene) = nodes(scene).flatMap(::text).joinToString()
    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()
    private fun nodes(scene: SearchScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap { flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private inline fun <T> withRestoredLocale(block: () -> T): T {
        val previous = Locale.getDefault()
        return try { block() } finally { Locale.setDefault(previous) }
    }
    private lateinit var currentPreferences: mihon.desktop.settings.DesktopAppPreferences
    private lateinit var currentReaderPreferences: mihon.desktop.reader.ReaderPreferences

    private class SearchScene(context: CoroutineContext, height: Int, width: Int = 900) : AutoCloseable {
        val semanticsOwners = linkedSetOf<SemanticsOwner>()
        private val canvas = Canvas(ImageBitmap(width, height))
        private val scene: ComposeScene = CanvasLayersComposeScene(
            size = IntSize(width, height),
            coroutineContext = context,
            platformContext = object : PlatformContext {
                override val windowInfo = object : WindowInfo { override val isWindowFocused = true }
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
        fun sendKeyEvent(event: ComposeKeyEvent) = scene.sendKeyEvent(event)
        fun takeFocus() = scene.focusManager.takeFocus(FocusDirection.Enter)
        override fun close() = scene.close()
    }
}
