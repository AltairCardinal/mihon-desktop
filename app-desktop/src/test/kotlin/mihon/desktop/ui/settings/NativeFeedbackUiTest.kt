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
        override suspend fun capabilities() = tachiyomi.domain.feedback.NativeFeedbackCapabilities(true, true, true)
        var sessions = 0
        var verified = false
        val submissions = mutableListOf<NativeFeedbackPayload>()
        override suspend fun createSession(contentHash: String): NativeFeedbackSession {
            sessions++
            return NativeFeedbackSession(
                "123e4567-e89b-42d3-a456-426614174000",
                "s".repeat(43),
                System.currentTimeMillis() / 1000 + 600,
                "https://mihon-feedback.mihon-altaircardinal.workers.dev/verify.html#" +
                    "123e4567-e89b-42d3-a456-426614174000",
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
    fun `real Compose public consent and optional logs preserve draft on cancellation`() = runBlocking {
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
            click(scene, "feedback-category")
            render(scene)
            click(scene, "feedback-category-other")
            render(scene)
            input(scene, "feedback-description", "A fictional crash happened while opening this test page.")
            click(scene, "feedback-submit")
            render(scene)
            assertEquals(0, service.sessions)
            assertEquals(0, reads)
            click(scene, "feedback-public-consent")
            click(scene, "feedback-read-log")
            render(scene)
            assertEquals(1, reads)
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
            assertTrue(node(scene, "feedback-category").config.contains(SemanticsProperties.Disabled))
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
            click(scene, "feedback-category")
            render(scene)
            click(scene, "feedback-category-other")
            render(scene)
            input(scene, "feedback-description", "A fictional problem detailed only in this native UI test.")
            click(scene, "feedback-public-consent")
            click(scene, "feedback-submit")
            render(scene)
            delay(2100)
            render(scene)
            assertEquals(NativeFeedbackPhase.RECEIVED, controller.state.value.phase)
            assertEquals(1, service.submissions.size)
            assertEquals("", service.submissions.single().crashLog)
            assertEquals("Application: Mihon Desktop", service.submissions.single().summary)
            assertTrue(node(scene, "feedback-category").config.contains(SemanticsProperties.Disabled))
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

    @Test
    fun `received receipt survives failed durable logging stop and visible retry works`() = runBlocking {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val root = java.nio.file.Files.createTempDirectory("fictional-receipt-stop").toFile()
        val storeScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val failStop = java.util.concurrent.atomic.AtomicBoolean(false)
        val persistence = object : tachiyomi.core.common.feedback.DiagnosticPersistence {
            var saved: tachiyomi.core.common.feedback.DiagnosticSession? = null
            override fun load() = saved
            override fun save(session: tachiyomi.core.common.feedback.DiagnosticSession?) {
                if (session == null && failStop.get()) throw java.io.IOException("Fictional persistence failure")
                saved = session
            }
        }
        val store = tachiyomi.core.common.feedback.DiagnosticStore(root, persistence, storeScope)
        store.start(tachiyomi.core.common.feedback.DiagnosticCategory.READER)
        val diagnostics = tachiyomi.domain.feedback.FeedbackDiagnosticController(store, FeedbackLogReader { "" }, this)
        val service = Service().apply { verified = true }
        val controller =
            NativeFeedbackController(service, FeedbackLogReader { "" }, "", this, {}, diagnostics = diagnostics)
        val scene = ImageComposeScene(720, 1600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme { NativeFeedbackDialog(controller, "", {}, {}, { it.localized() }) }
            }
            render(scene)
            controller.category(tachiyomi.domain.feedback.FeedbackCategory.OTHER)
            controller.description("A fictional feedback report with failed local logging persistence.")
            controller.publicConsent(true)
            failStop.set(true)
            controller.submit()
            kotlinx.coroutines.withTimeout(5000) {
                while (store.state.value.error == null) delay(10)
            }
            render(scene)
            assertEquals(NativeFeedbackPhase.RECEIVED, controller.state.value.phase)
            assertEquals("r".repeat(43), controller.state.value.receipt)
            assertTrue(store.state.value.session != null)
            assertTrue(
                allText(scene).contains("Could not save the logging mode. Logging is still on. Retry stopping it."),
            )
            failStop.set(false)
            click(scene, "feedback-diagnostics-stop")
            kotlinx.coroutines.withTimeout(5000) {
                while (store.state.value.session != null) delay(10)
            }
            render(scene)
            assertEquals(null, persistence.saved)
            assertEquals(NativeFeedbackPhase.RECEIVED, controller.state.value.phase)
            assertEquals("r".repeat(43), controller.state.value.receipt)
            assertFalse(allText(scene).contains("Logging is still on"))
        } finally {
            scene.close()
            storeScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            root.deleteRecursively()
            Locale.setDefault(previous)
        }
    }

    private fun allText(scene: ImageComposeScene): String = scene.semanticsOwners.flatMap {
        flatten(it.unmergedRootSemanticsNode)
    }.flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.joinToString("\n") { it.text }

    @Test
    fun `category choices replace title and real template edits survive cancelled replacement`() = runBlocking {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val controller = NativeFeedbackController(Service(), FeedbackLogReader { "" }, "Application: Mihon", this, {})
        val scene = ImageComposeScene(720, 1200, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    NativeFeedbackDialog(controller, "Application: Mihon", {}, {}, { it.localized() })
                }
            }
            render(scene)
            click(scene, "feedback-category")
            render(scene)
            click(scene, "feedback-category-reader")
            render(scene)
            assertTrue("[Where it happened]" in controller.state.value.draft.description)
            input(scene, "feedback-description", "A fictional reader problem that should remain in the draft.")
            click(scene, "feedback-category")
            render(scene)
            click(scene, "feedback-category-feature_request")
            render(scene)
            click(scene, "feedback-category-keep")
            render(scene)
            assertTrue("fictional reader" in controller.state.value.draft.description)
            assertEquals(tachiyomi.domain.feedback.FeedbackCategory.READER, controller.state.value.draft.category)
        } finally {
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
