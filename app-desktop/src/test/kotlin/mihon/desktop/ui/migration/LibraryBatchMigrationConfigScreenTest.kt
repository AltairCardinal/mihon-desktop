package mihon.desktop.ui.migration

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.migration.BatchMigrationRequest
import mihon.desktop.migration.DesktopBatchMigrationController
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR

@Isolated
class LibraryBatchMigrationConfigScreenTest {
    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `cancel does not submit and submit failure stays visible`(@TempDir tempDir: File) = runBlocking {
        val node = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            tempDir.resolve("context"),
            DesktopPreferenceStore(node),
            startDownloadWorker = false,
        )
        try {
            val normalDependencies = DesktopUiDependencies.fromInjekt()
            val cancelScene = scene(normalDependencies)
            try {
                render(cancelScene)
                click(cancelScene, MR.strings.action_cancel.localized())
                yield()
                assertTrue(normalDependencies.batchMigrationController.queues.value.isEmpty())
            } finally {
                cancelScene.close()
            }

            val blockedParent = tempDir.resolve("blocked-parent").apply { writeText("not a directory") }
            val failingController = DesktopBatchMigrationController(
                scheduler = DesktopTaskScheduler(FileTaskCheckpointStore(blockedParent.toPath().resolve("tasks.json"))),
                executeMigration = { _, _ -> },
                scope = CoroutineScope(coroutineContext),
                dispatcher = Dispatchers.Unconfined,
            )
            val failureScene = scene(normalDependencies.copy(batchMigrationController = failingController))
            try {
                render(failureScene)
                click(failureScene, MR.strings.action_migrate.localized())
                withTimeout(5_000) {
                    while (!labels(failureScene).contains(MR.strings.internal_error.localized())) {
                        render(failureScene)
                        delay(10)
                    }
                }
                assertTrue(failingController.queues.value.isEmpty())
                val migrateButton = nodes(failureScene).first {
                    MR.strings.action_migrate.localized() in it.labels() && it.config.contains(SemanticsActions.OnClick)
                }
                assertTrue(!migrateButton.config.contains(SemanticsProperties.Disabled))
            } finally {
                failureScene.close()
                failingController.stop()
            }
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `config submits the complete selection and current options only once`(@TempDir tempDir: File) = runBlocking {
        val node = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(node), startDownloadWorker = false)
        val controller = DesktopUiDependencies.fromInjekt().batchMigrationController
        val requests = listOf(
            BatchMigrationRequest(1L, "Remote migration target"),
            BatchMigrationRequest(2L, "Local migration target"),
        )
        val scene = ImageComposeScene(1_000, 760, coroutineContext = coroutineContext) {}
        var destination: Screen? = null
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    Navigator(LibraryBatchMigrationConfigScreen(requests)) { navigator ->
                        destination = navigator.lastItem
                        CurrentScreen()
                    }
                }
            }
            render(scene)
            assertTrue(labels(scene).containsAll(requests.map { it.title }))
            repeat(3) {
                clickToggle(scene, ToggleableState.On)
                render(scene)
            }
            click(scene, MR.strings.action_migrate.localized())
            clickToggle(scene, ToggleableState.Off)
            click(scene, MR.strings.action_migrate.localized())
            withTimeout(5_000) {
                while (controller.queues.value.isEmpty()) {
                    render(scene)
                    delay(10)
                }
            }

            val queue = controller.queues.value.values.single()
            assertEquals(listOf(1L, 2L), queue.items.map { it.mangaId })
            assertEquals(false, queue.defaultOptions.copyChapters)
            assertEquals(false, queue.defaultOptions.copyCategories)
            assertEquals(false, queue.defaultOptions.copyNotes)
            assertEquals(queue.id, (destination as MigrationBatchQueueScreen).queueId)
        } finally {
            scene.close()
            context.closeAndJoin()
            node.removeNode()
        }
    }

    private suspend fun render(scene: ImageComposeScene) {
        repeat(3) {
            scene.render()
            yield()
        }
    }

    private fun scene(dependencies: DesktopUiDependencies): ImageComposeScene =
        ImageComposeScene(1_000, 760, coroutineContext = Dispatchers.Unconfined) {}.also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(
                        LibraryBatchMigrationConfigScreen(listOf(BatchMigrationRequest(1L, "Migration target"))),
                    ) { CurrentScreen() }
                }
            }
        }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun click(scene: ImageComposeScene, label: String) {
        val target = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && label in it.labels()
        }
        assertTrue(requireNotNull(target.config[SemanticsActions.OnClick].action).invoke())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun clickToggle(scene: ImageComposeScene, state: ToggleableState) {
        val target = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.contains(SemanticsProperties.ToggleableState) &&
                it.config[SemanticsProperties.ToggleableState] == state
        }
        assertTrue(requireNotNull(target.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun labels(scene: ImageComposeScene) = nodes(scene).flatMap { it.labels() }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun SemanticsNode.labels(): List<String> =
        (if (config.contains(SemanticsProperties.Text)) config[SemanticsProperties.Text].map { it.text } else emptyList()) +
            if (config.contains(SemanticsProperties.ContentDescription)) {
                config[SemanticsProperties.ContentDescription]
            } else {
                emptyList()
            }
}
