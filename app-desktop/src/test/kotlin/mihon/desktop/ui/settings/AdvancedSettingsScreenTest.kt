package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import kotlin.reflect.KClass

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class AdvancedSettingsScreenTest {

    @Test
    fun `AdvancedSettingsScreen implements Screen`() {
        assertInstanceOf(Screen::class.java, AdvancedSettingsScreen())
    }

    @Test
    fun `AdvancedSettingsScreen can be instantiated with no arguments`() {
        // Must not throw
        AdvancedSettingsScreen()
    }

    @Test
    fun `catalog result anchors once and preserves platform success and failure feedback`() = runBlocking {
        listOf(
            true to MR.strings.desktop_advanced_crash_log_opened.localized(),
            false to MR.strings.desktop_advanced_crash_log_open_failed.localized(),
        ).forEach { (opens, feedback) ->
            fixture(opens).use { fixture ->
                val title = MR.strings.desktop_advanced_crash_log_open.localized()
                open(fixture, title, AdvancedSettingsScreen::class)
                assertAnchor(fixture.scene, title)
                click(fixture.scene, title)
                assertTrue(feedback in render(fixture.scene))
                assertEquals(1, fixture.actions.openCalls)
                assertOneShot(fixture)
            }
        }
    }

    @Test
    fun `wrong route and unknown title never highlight`() = runBlocking {
        fixture(true).use { fixture ->
            val result = result(MR.strings.desktop_advanced_crash_log_open.localized(), AdvancedSettingsScreen::class)
            DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
            fixture.navigator.replace(GeneralSettingsScreen())
            render(fixture.scene)
            assertNoAnchor(fixture.scene)
            DesktopSettingsAnchorOwner.publish(AdvancedSettingsScreen(), "missing-title")
            fixture.navigator.replace(AdvancedSettingsScreen())
            render(fixture.scene)
            assertNoAnchor(fixture.scene)
        }
    }

    private suspend fun fixture(opens: Boolean): Fixture {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val actions = RecordingActions(opens)
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns preferences
        }
        val scene = ImageComposeScene(900, 170) {}
        lateinit var navigator: Navigator
        scene.setContent {
            CompositionLocalProvider(
                LocalDesktopUiDependencies provides dependencies,
                LocalAdvancedSettingsPlatformActions provides actions,
            ) {
                Navigator(EmptyScreen()) { nav -> navigator = nav; CurrentScreen() }
            }
        }
        render(scene)
        return Fixture(scene, navigator, actions)
    }

    private suspend fun open(fixture: Fixture, title: String, route: KClass<out Screen>) {
        val result = result(title, route)
        assertEquals(title, result.anchorTitle)
        DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
        fixture.navigator.replace(result.route)
        render(fixture.scene)
        assertTrue(route.isInstance(fixture.navigator.lastItem))
    }

    private fun result(title: String, route: KClass<out Screen>) =
        DesktopSettingsCatalog.search(title).single { route.isInstance(it.route) && it.anchorTitle == title }

    private fun assertAnchor(scene: ImageComposeScene, title: String) {
        val highlighted = nodes(scene, true).single {
            it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
        }
        assertTrue(flatten(highlighted).any { title in text(it) })
        assertTrue(highlighted.boundsInRoot.height > 0f)
        assertTrue(scroll(scene).value() > 0f)
    }

    private suspend fun assertOneShot(fixture: Fixture) {
        fixture.navigator.replace(EmptyScreen())
        render(fixture.scene)
        fixture.navigator.replace(AdvancedSettingsScreen())
        render(fixture.scene)
        assertNoAnchor(fixture.scene)
    }

    private fun assertNoAnchor(scene: ImageComposeScene) {
        assertFalse(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
        assertEquals(0f, scroll(scene).value())
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && label in text(it) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun scroll(scene: ImageComposeScene) = nodes(scene, true)
        .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        .config[SemanticsProperties.VerticalScrollAxisRange]

    private suspend fun render(scene: ImageComposeScene): Set<String> {
        repeat(6) { scene.render(); yield() }
        return nodes(scene).flatMap(::text).toSet()
    }

    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) {
        node.config[SemanticsProperties.Text].map { it.text }
    } else {
        emptyList()
    }

    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap {
        flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private data class Fixture(
        val scene: ImageComposeScene,
        val navigator: Navigator,
        val actions: RecordingActions,
    ) : AutoCloseable {
        override fun close() = scene.close()
    }

    private class RecordingActions(private val opens: Boolean) : AdvancedSettingsPlatformActions {
        var openCalls = 0
        override suspend fun loadNetworkCacheSize() = "12 KB"
        override suspend fun openCrashLogFolder(): Boolean {
            openCalls++
            return opens
        }
    }

    private class EmptyScreen : Screen {
        @androidx.compose.runtime.Composable
        override fun Content() = Unit
    }
}
