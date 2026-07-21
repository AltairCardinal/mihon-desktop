package mihon.desktop.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.APP_VERSION
import mihon.desktop.update.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.model.Release
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class AboutUpdateWiringTest {
    @Test
    fun `about renders full version and routes ready confirmation intents`() = runBlocking {
        val intents = mutableListOf<DesktopUpdateIntent>()
        val scene = ImageComposeScene(800, 600, coroutineContext = coroutineContext) {}
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            scene.setContent {
                MaterialTheme {
                    AboutUpdateSection(APP_VERSION, DesktopUpdateState.Idle.presentation(), intents::add)
                }
            }
            scene.render()
            assertTrue("Version $APP_VERSION" in texts(scene))
            click(scene, "Check for updates")
            assertEquals(listOf(DesktopUpdateIntent.CHECK), intents)

            scene.setContent {
                MaterialTheme {
                    AboutUpdateSection(
                        APP_VERSION,
                        DesktopUpdatePresentation("ready", "Ready to install", setOf(DesktopUpdateIntent.CONFIRM, DesktopUpdateIntent.DECLINE)),
                        intents::add,
                    )
                }
            }
            scene.render()
            assertTrue("Ready to install" in texts(scene))
            click(scene, "Open on GitHub")
            assertEquals(DesktopUpdateIntent.MANUAL, intents.last())
            click(scene, "Install")
            assertEquals(DesktopUpdateIntent.CONFIRM, intents.last())
        } finally {
            Locale.setDefault(previousLocale)
            scene.close()
        }
    }

    @Test
    fun `screen model owns cancellation arguments and manual fallback intent`() = runBlocking {
        var calls = 0
        var arguments: tachiyomi.domain.release.interactor.GetApplicationRelease.Arguments? = null
        var opened: String? = null
        val release = Release("0.12.0", "", "https://release", "")
        val controller = DesktopUpdateController(
            { args ->
                arguments = args
                if (calls++ == 0) awaitCancellation() else tachiyomi.domain.release.interactor.GetApplicationRelease.Result.NewUpdate(release)
            },
            { _, _ -> ManualOnly(release.releaseLink) },
            { _, _ -> InstallManualOnly },
            { _, _ -> InstallCancelled },
        )
        val model = DesktopUpdateScreenModel(controller, this) { opened = it; true }
        model.intent(DesktopUpdateIntent.CHECK)
        yield()
        model.intent(DesktopUpdateIntent.CANCEL)
        yield()
        assertTrue(controller.state.value is DesktopUpdateState.Cancelled)
        model.intent(DesktopUpdateIntent.CHECK)
        yield()
        assertEquals("0.11.14", arguments?.versionName)
        assertEquals("mihonapp/mihon", arguments?.repository)
        model.intent(DesktopUpdateIntent.MANUAL)
        assertEquals(release.releaseLink, opened)
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && label in texts(it) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap(::texts)
    private fun texts(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
