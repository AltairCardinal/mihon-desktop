package mihon.desktop.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import mihon.desktop.APP_VERSION
import mihon.desktop.update.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.model.Release
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch

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
                    AboutUpdateSection(APP_VERSION, DesktopUpdateState.Idle.presentation(), "Could not open https://release", intents::add)
                }
            }
            scene.render()
            assertTrue("Version $APP_VERSION" in texts(scene))
            assertTrue("Could not open https://release" in texts(scene))
            click(scene, "Check for updates")
            assertEquals(listOf(DesktopUpdateIntent.CHECK), intents)

            scene.setContent {
                MaterialTheme {
                    AboutUpdateSection(
                        APP_VERSION,
                        DesktopUpdatePresentation("ready", "Ready to install", setOf(DesktopUpdateIntent.CONFIRM, DesktopUpdateIntent.DECLINE)),
                        null,
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
    fun `screen model owns cancellation arguments and manual fallback intent`() {
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        ui.use {
            runBlocking(ui) {
                var calls = 0
                val entered = CompletableDeferred<Unit>()
                var arguments: tachiyomi.domain.release.interactor.GetApplicationRelease.Arguments? = null
                var opened: String? = null
                var openMode = 0
                val release = Release("0.12.0", "", "https://release", "")
                val controller = DesktopUpdateController(
                    { args ->
                        arguments = args
                        if (calls++ == 0) {
                            entered.complete(Unit)
                            runInterruptible { CountDownLatch(1).await() }
                            error("cancel expected")
                        } else {
                            tachiyomi.domain.release.interactor.GetApplicationRelease.Result.NewUpdate(release)
                        }
                    },
                    { _, _ -> ManualOnly(release.releaseLink) },
                    { _, _ -> InstallManualOnly },
                    { _, _ -> InstallCancelled },
                )
                val model = DesktopUpdateScreenModel(controller, this, openUrl = {
                    opened = it
                    if (openMode == 2) error("browser") else openMode == 1
                })
                assertTrue(model.intent(DesktopUpdateIntent.CHECK))
                entered.await()
                var uiAdvanced = false
                launch { uiAdvanced = true }.join()
                assertTrue(uiAdvanced)
                assertTrue(model.intent(DesktopUpdateIntent.CANCEL))
                model.state.first { it is DesktopUpdateState.Cancelled }
                assertTrue(model.intent(DesktopUpdateIntent.CHECK))
                model.state.first { it is DesktopUpdateState.UpdateAvailable }
                assertEquals("0.11.14", arguments?.versionName)
                assertEquals("mihonapp/mihon", arguments?.repository)
                model.intent(DesktopUpdateIntent.MANUAL)
                assertEquals(release.releaseLink, opened)
                assertTrue(model.feedback.value!!.contains(release.releaseLink))
                openMode = 1
                model.intent(DesktopUpdateIntent.MANUAL)
                assertEquals(null, model.feedback.value)
                openMode = 2
                model.intent(DesktopUpdateIntent.MANUAL)
                assertTrue(model.feedback.value!!.contains(release.releaseLink))
                val disposing = DesktopUpdateScreenModel(
                    DesktopUpdateController(
                        { awaitCancellation() },
                        { _, _ -> ManualOnly("") },
                        { _, _ -> InstallManualOnly },
                        { _, _ -> InstallCancelled },
                    ),
                    this,
                )
                assertTrue(disposing.intent(DesktopUpdateIntent.CHECK))
                disposing.state.first { it is DesktopUpdateState.Checking }
                disposing.dispose()
                disposing.state.first { it is DesktopUpdateState.Cancelled }
            }
        }
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
