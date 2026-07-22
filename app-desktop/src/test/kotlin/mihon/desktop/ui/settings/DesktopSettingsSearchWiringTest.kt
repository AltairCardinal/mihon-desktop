package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.tab.Tab
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadManager
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
            val fixedMain = "AppearanceSettingsScreen,LibrarySettingsScreen,ReaderSettingsScreen,DownloadSettingsScreen,TrackingSettingsScreen,ExtensionListScreen,BackupSettingsScreen,SecuritySettingsScreen,AdvancedSettingsScreen"
            assertEquals(fixedMain, screens.take(9).joinToString(",") { it.route::class.simpleName.orEmpty() })
            assertEquals(listOf("GeneralSettingsScreen", "ExtensionRepoScreen", "AboutScreen"), screens.drop(9).map { it.route::class.simpleName })
            assertTrue(screens.none { it.route is Tab })
            val originalRoutes = screens.take(9).map { it.route::class }.toSet()
            val results = DesktopSettingsCatalog.search("e")
            assertEquals(10, results.size)
            assertTrue(results.all { it.route::class in originalRoutes })
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
                    scene.sendKeyEvent(composeKeyEvent(key))
                    render(scene)
                    assertFalse(field(scene).config[SemanticsProperties.Focused])
                }
            }
            withSearchScene(height = 260) { scene ->
                lateinit var navigator: Navigator
                scene.setContent { dependencies { Navigator(SettingsSearchScreen()) { nav -> navigator = nav; CurrentScreen() } } }
                render(scene)
                val anchorTitle = MR.strings.desktop_appearance_library_grid.localized(Locale.US)
                setText(scene, anchorTitle)
                render(scene)
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
    fun `More search entry opens the production search screen`() = runBlocking {
        withSearchScene(MoreRootScreen()) { scene ->
            lateinit var navigator: Navigator
            scene.setContent { dependencies { Navigator(MoreRootScreen()) { nav -> navigator = nav; CurrentScreen() } } }
            render(scene)
            click(scene, MR.strings.action_search_settings.localized(Locale.getDefault()))
            assertTrue(navigator.lastItem is SettingsSearchScreen)
        }
    }
    private suspend fun withSearchScene(
        screen: Screen = SettingsSearchScreen(),
        height: Int = 900,
        block: suspend (SearchScene) -> Unit,
    ) {
        val scene = SearchScene(kotlinx.coroutines.currentCoroutineContext(), height)
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
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns currentPreferences
            every { downloadManager } returns downloads
        }
        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies, content = content)
    }
    private suspend fun render(scene: SearchScene) = repeat(5) {
        scene.render()
        kotlinx.coroutines.yield()
    }
    private fun composeKeyEvent(key: Key): ComposeKeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val keyDown = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod("access\$getKeyDown\$cp").invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        val native = factory.invoke(null, key.keyCode, keyDown, 0, false, false, false, false, null)
        return ComposeKeyEvent(native)
    }
    private fun field(scene: SearchScene) = nodes(scene, true).single { it.config.contains(SemanticsActions.SetText) }
    private fun setText(scene: SearchScene, value: String) = requireNotNull(field(scene).config[SemanticsActions.SetText].action).invoke(AnnotatedString(value))
    private fun click(scene: SearchScene, label: String) = requireNotNull(
        nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && flatten(it).any { node -> label in text(node) } }
            .config[SemanticsActions.OnClick].action,
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

    private class SearchScene(context: CoroutineContext, height: Int) : AutoCloseable {
        val semanticsOwners = linkedSetOf<SemanticsOwner>()
        private val canvas = Canvas(ImageBitmap(900, height))
        private val scene: ComposeScene = CanvasLayersComposeScene(
            size = IntSize(900, height),
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
