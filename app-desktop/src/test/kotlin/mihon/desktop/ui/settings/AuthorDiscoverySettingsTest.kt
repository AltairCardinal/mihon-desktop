package mihon.desktop.ui.settings

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.domain.CreatorDiscoveryScheduler
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import mihon.domain.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.creator.service.CreatorDiscoveryResult
import tachiyomi.domain.creator.service.CreatorDiscoverySourceResult
import tachiyomi.domain.creator.service.CreatorSourceFailure
import tachiyomi.i18n.MR
import java.nio.file.Path
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class AuthorDiscoverySettingsTest {

    @TempDir lateinit var directory: Path

    @Test
    fun `settings section renders running state with a working cancel action`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = CreatorDiscoveryScheduler(
            taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json"))),
            discoverDue = {
                entered.complete(Unit)
                release.await()
                CreatorDiscoveryResult(0, 0, emptyList())
            },
            discoverCreator = { CreatorDiscoveryResult(0, 0, emptyList()) },
        )
        val job = scheduler.runNow()
        entered.await()

        val scene = ImageComposeScene(900, 500, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { AuthorDiscoverySettingsSection(scheduler) }
            val running = MR.strings.desktop_ui_author_discovery_running.localized()
            withTimeout(5_000) {
                while (running !in texts(scene)) scene.render()
            }
            assertTrue(running in texts(scene))

            val cancel = MR.strings.desktop_ui_author_discovery_cancel.localized()
            clickableTextNode(scene, cancel).config[SemanticsActions.OnClick].action?.invoke()
            release.complete(Unit)
            job.join()

            withTimeout(5_000) {
                while (MR.strings.desktop_ui_author_discovery_cancelled.localized() !in texts(scene)) scene.render()
            }
            assertTrue(
                MR.strings.desktop_ui_author_discovery_cancelled.localized() in texts(scene),
            )
            assertEquals(TaskStatus.Cancelled, scheduler.taskSnapshot()?.status)
        } finally {
            scene.close()
            scheduler.stop()
        }
    }

    @Test
    fun `settings section run now drives the scheduler and shows the typed result`() = runBlocking {
        var runs = 0
        val scheduler = CreatorDiscoveryScheduler(
            taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json"))),
            discoverDue = {
                runs += 1
                CreatorDiscoveryResult(
                    newCandidateCount = 3,
                    errorCount = 1,
                    candidates = emptyList(),
                    sourceResults = listOf(
                        sourceResult(1L),
                        sourceResult(2L, CreatorSourceFailure.Network("offline")),
                    ),
                    completedSources = 1,
                    totalSources = 2,
                )
            },
            discoverCreator = { CreatorDiscoveryResult(0, 0, emptyList()) },
        )
        val scene = ImageComposeScene(900, 500, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { AuthorDiscoverySettingsSection(scheduler) }
            scene.render()
            assertTrue(MR.strings.desktop_ui_author_discovery_idle.localized() in texts(scene))

            val runNow = MR.strings.desktop_ui_author_discovery_run_now.localized()
            clickableTextNode(scene, runNow).config[SemanticsActions.OnClick].action?.invoke()

            val expected = MR.strings.desktop_ui_author_discovery_result.localized(
                Locale.getDefault(),
                3,
                1,
            )
            withTimeout(5_000) {
                while (expected !in texts(scene)) {
                    delay(10)
                    scene.render()
                }
            }
            assertEquals(1, runs)
            assertEquals(TaskStatus.Failed, scheduler.taskSnapshot()?.status)
            assertTrue(expected in texts(scene))
        } finally {
            scene.close()
            scheduler.stop()
        }
    }

    private fun clickableTextNode(scene: ImageComposeScene, text: String): SemanticsNode = nodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text == text }
    }

    private fun texts(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        flatten(owner.rootSemanticsNode)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun sourceResult(
        sourceId: Long,
        failure: CreatorSourceFailure? = null,
    ) = CreatorDiscoverySourceResult(
        sourceId = sourceId,
        pageCount = 1,
        matchedCount = 1,
        possibleCount = 0,
        insertedVerifiedCount = 1,
        notificationEligibleCount = 0,
        truncated = false,
        failure = failure,
    )
}
