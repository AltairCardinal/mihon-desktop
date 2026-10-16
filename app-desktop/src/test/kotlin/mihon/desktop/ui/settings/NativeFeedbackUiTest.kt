package mihon.desktop.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.feedback.FeedbackLogReader
import tachiyomi.domain.feedback.NativeFeedbackController
import tachiyomi.domain.feedback.NativeFeedbackPayload
import tachiyomi.domain.feedback.NativeFeedbackPhase
import tachiyomi.domain.feedback.NativeFeedbackService
import tachiyomi.domain.feedback.NativeFeedbackSession
import tachiyomi.domain.feedback.NativeFeedbackVerification
import tachiyomi.presentation.feedback.NativeFeedbackDialog
import java.util.Locale

@Isolated
@OptIn(ExperimentalComposeUiApi::class)
class NativeFeedbackUiTest {
    private class Service : NativeFeedbackService {
        var sessions = 0
        var verified = false
        val submissions = mutableListOf<NativeFeedbackPayload>()
        override suspend fun createSession(contentHash: String): NativeFeedbackSession {
            sessions++
            return NativeFeedbackSession(
                "123e4567-e89b-42d3-a456-426614174000",
                "s".repeat(43),
                System.currentTimeMillis() / 1000 + 600,
                "https://mihon-feedback.mihon-altaircardinal.workers.dev/verify.html#123e4567-e89b-42d3-a456-426614174000",
            )
        }
        override suspend fun pollSession(
            session: NativeFeedbackSession,
        ) = NativeFeedbackVerification(verified, session.expiresAt)
        override suspend fun submit(payload: NativeFeedbackPayload, session: NativeFeedbackSession): String {
            submissions += payload
            return "r".repeat(43)
        }
    }

    @Test
    fun `real Compose fields require independent consent and cancellation keeps private draft`() = runBlocking {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val service = Service()
        var reads = 0
        val opened = mutableListOf<String>()
        val controller =
            NativeFeedbackController(
                service,
                FeedbackLogReader {
                    reads++
                    "Fictional private crash\n"
                },
                "Application: Mihon Desktop",
                CoroutineScope(coroutineContext),
                opened::add,
            )
        val scene = ImageComposeScene(720, 1200, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    NativeFeedbackDialog(controller, "Application: Mihon Desktop", {}, {}, { it.localized() })
                }
            }
            render(scene)
            input(scene, "feedback-title", "Fictional report")
            input(scene, "feedback-description", "A fictional crash happened while opening this test page.")
            click(scene, "feedback-submit")
            render(scene)
            assertEquals(0, service.sessions)
            assertEquals(0, reads)
            click(scene, "feedback-public-consent")
            click(scene, "feedback-read-log")
            render(scene)
            assertEquals(1, reads)
            click(scene, "feedback-submit")
            render(scene)
            assertEquals(0, service.sessions)
            click(scene, "feedback-log-consent")
            input(scene, "feedback-log", "Edited fictional private crash\n")
            render(scene)
            assertFalse(controller.state.value.draft.logConsent)
            click(scene, "feedback-log-consent")
            click(scene, "feedback-submit")
            render(scene)
            assertEquals(NativeFeedbackPhase.WAITING_VERIFICATION, controller.state.value.phase)
            assertEquals(1, opened.size)
            assertFalse("Fictional" in opened.single())
            assertTrue(node(scene, "feedback-title").config.contains(SemanticsProperties.Disabled))
            click(scene, "feedback-cancel")
            render(scene)
            service.verified = true
            delay(2100)
            render(scene)
            assertTrue(service.submissions.isEmpty())
            assertEquals("Edited fictional private crash\n", controller.state.value.draft.crashLog)
            assertFalse(controller.state.value.busy)
        } finally {
            controller.cancel()
            scene.close()
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `successful native form receives receipt in app and locks all submitted fields`() = runBlocking {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val service = Service().apply { verified = true }
        val controller =
            NativeFeedbackController(
                service,
                FeedbackLogReader {
                    error("must not read")
                },
                "Application: Mihon Desktop",
                CoroutineScope(coroutineContext),
                {},
            )
        val scene = ImageComposeScene(720, 1200, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    NativeFeedbackDialog(controller, "Application: Mihon Desktop", {}, {}, { it.localized() })
                }
            }
            render(scene)
            input(scene, "feedback-title", "Fictional report")
            input(scene, "feedback-description", "A fictional problem detailed only in this native UI test.")
            click(scene, "feedback-public-consent")
            click(scene, "feedback-submit")
            render(scene)
            delay(2100)
            render(scene)
            assertEquals(NativeFeedbackPhase.RECEIVED, controller.state.value.phase)
            assertEquals(1, service.submissions.size)
            assertEquals("", service.submissions.single().crashLog)
            assertEquals("", service.submissions.single().summary)
            assertTrue(node(scene, "feedback-title").config.contains(SemanticsProperties.Disabled))
            assertTrue(
                flatten(scene.semanticsOwners.last().unmergedRootSemanticsNode).any {
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text ->
                        "r".repeat(43) in
                            text.text
                    }
                },
            )
        } finally {
            controller.cancel()
            scene.close()
            Locale.setDefault(previous)
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun node(
        scene: ImageComposeScene,
        tag: String,
    ): SemanticsNode = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }.single {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" } ==
            tag
    }
    private fun click(
        scene: ImageComposeScene,
        tag: String,
    ) {
        assertTrue(requireNotNull(node(scene, tag).config[SemanticsActions.OnClick].action).invoke())
    }
    private fun input(
        scene: ImageComposeScene,
        tag: String,
        value: String,
    ) {
        assertTrue(
            requireNotNull(node(scene, tag).config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)),
        )
    }
    private suspend fun render(
        scene: ImageComposeScene,
    ) {
        repeat(3) {
            scene.render(System.nanoTime()).close()
            yield()
        }
    }
}
