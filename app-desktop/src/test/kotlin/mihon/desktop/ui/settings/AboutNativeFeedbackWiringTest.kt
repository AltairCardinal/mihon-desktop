package mihon.desktop.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.feedback.DesktopFeedbackRuntime
import mihon.desktop.feedback.createDesktopDiagnosticStore
import mihon.desktop.platform.DesktopFilePicker
import mihon.desktop.platform.DesktopFilePickerRequest
import mihon.desktop.platform.DesktopFilePickerResult
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.update.DesktopUpdateState
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvent
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticStage
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
@OptIn(ExperimentalComposeUiApi::class)
class AboutNativeFeedbackWiringTest {
    @Test
    fun `About submits with scoped logs`(@TempDir root: File) = runBlocking {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        MockWebServer().also { it.start() }.use { server ->
            val id = "123e4567-e89b-42d3-a456-426614174000"
            val secret = "s".repeat(43)
            val expiry = System.currentTimeMillis() / 1000 + 600
            val verificationUrl = "https://mihon-feedback.mihon-altaircardinal.workers.dev/verify.html#$id"
            server.enqueue(
                MockResponse(
                    body = """
{"accepting":true,
"siteKey":"public",
"capabilities":{"nativeV2":true,"screenshots":true},
"limits":{"maxScreenshots":2,"maxScreenshotBytes":524288,
"maxTotalScreenshotBytes":1048576,"maxScreenshotSide":1920,
"maxBodyBytes":2097152,"maxLogBytes":262144}}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse(
                    code = 201,
                    body = """
                {"sessionId":"$id","secret":"$secret","expiresAt":$expiry,
                "verificationUrl":"$verificationUrl"}
                    """.trimIndent(),
                ),
            )
            server.enqueue(MockResponse(body = """{"verified":true,"expiresAt":$expiry}"""))
            server.enqueue(
                MockResponse(code = 202, body = """{"status":"received_pending","receipt":"${"r".repeat(43)}"}"""),
            )
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val helper = mockk<DesktopNetworkHelper> { every { this@mockk.client } returns client }
            val paths = DesktopPlatformPaths.resolve("Linux", root.path, emptyMap())
            val feedbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val preferences = Preferences.userRoot().node("/mihon/test/about-feedback-${UUID.randomUUID()}")
            val feedback =
                DesktopFeedbackRuntime(
                    createDesktopDiagnosticStore(paths, DesktopPreferenceStore(preferences), feedbackScope),
                )
            feedback.store.start(DiagnosticCategory.READER)
            feedback.store.record(
                DiagnosticEvent(
                    DiagnosticCategory.READER,
                    DiagnosticOperation.READER_PAGE,
                    DiagnosticStage.SUCCESS,
                    count = 2,
                ),
            )
            feedback.store.flush()
            val update = mockk<DesktopUpdateScreenModel>(relaxed = true) {
                every { state } returns MutableStateFlow(DesktopUpdateState.Idle)
                every { this@mockk.feedback } returns MutableStateFlow(null)
                every { this@mockk.preferences } returns ApplicationUpdatePreferences(InMemoryPreferenceStore())
                every { presentation() } returns DesktopUpdateState.Idle.presentation()
            }
            val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
                every { networkHelper } returns helper
                every { feedbackRuntime } returns feedback
                every { updateScreenModel } returns update
                every { extensionPresentationService } returns DesktopExtensionManager()
            }
            val opened = mutableListOf<String>()
            val scene = ImageComposeScene(900, 1600, coroutineContext = coroutineContext) {}
            try {
                scene.setContent {
                    CompositionLocalProvider(
                        LocalDesktopUiDependencies provides dependencies,
                        LocalUriHandler provides
                            object : UriHandler {
                                override fun openUri(uri: String) {
                                    opened += uri
                                }
                            },
                    ) {
                        MaterialTheme { Navigator(AboutScreen(paths)) { CurrentScreen() } }
                    }
                }
                render(scene)
                val trigger = nodes(scene).single {
                    it.config.contains(SemanticsActions.OnClick) &&
                        texts(it).contains("Report a problem")
                }
                requireNotNull(trigger.config[SemanticsActions.OnClick].action).invoke()
                render(scene)
                click(scene, "feedback-category")
                render(scene)
                click(scene, "feedback-category-reader")
                render(scene)
                input(
                    scene,
                    "feedback-description",
                    "A fictional failure described in the native About integration test.",
                )
                repeat(20) {
                    delay(10)
                    render(scene)
                }
                assertTrue(
                    node(scene, "feedback-log").config[SemanticsProperties.EditableText].text.contains("READER_PAGE"),
                )
                assertEquals(1, server.requestCount)
                click(scene, "feedback-public-consent")
                click(scene, "feedback-submit")
                repeat(150) {
                    delay(20)
                    render(scene)
                }
                assertEquals(listOf(verificationUrl), opened)
                assertEquals(4, server.requestCount)
                assertEquals("/api/config", server.takeRequest().url?.encodedPath)
                val create = server.takeRequest()
                assertFalse(create.body!!.utf8().contains("Fictional"))
                assertEquals("Bearer $secret", server.takeRequest().headers["Authorization"])
                val sent = server.takeRequest().body!!.utf8()
                assertTrue(sent.contains("READER_PAGE"))
                assertTrue(feedback.store.state.value.session == null)
                assertFalse(sent.contains(root.path))
                assertTrue(nodes(scene).any { texts(it).contains("r".repeat(43)) })
            } finally {
                scene.close()
                feedbackScope.cancel()
                preferences.removeNode()
                Locale.setDefault(previous)
            }
        }
    }

    @Test
    fun `default About picks locally and previews only final JPEG`(@TempDir root: File) = runBlocking {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val png = File(root, "fictional-private-name.png")
        val original = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        javax.imageio.ImageIO.write(original, "png", png)
        var selections = 0
        val picker = object : DesktopFilePicker {
            override suspend fun choose(request: DesktopFilePickerRequest): DesktopFilePickerResult {
                val open = request as DesktopFilePickerRequest.OpenFile
                assertEquals(setOf("png", "jpg", "jpeg"), open.extensions)
                selections++
                return if (selections == 1) {
                    DesktopFilePickerResult.Selected(png)
                } else {
                    DesktopFilePickerResult.Cancelled
                }
            }
        }
        val configServer = MockWebServer().also { server ->
            server.start()
            repeat(2) {
                server.enqueue(
                    MockResponse(
                        body = """
{"accepting":true,
"siteKey":"public",
"capabilities":{"nativeV2":true,"screenshots":true},
"limits":{"maxScreenshots":2,"maxScreenshotBytes":524288,
"maxTotalScreenshotBytes":1048576,"maxScreenshotSide":1920,
"maxBodyBytes":2097152,"maxLogBytes":262144}}
                        """.trimIndent(),
                    ),
                )
            }
        }
        val configClient = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(configServer.url(chain.request().url.encodedPath)).build())
        }.build()
        val helper = mockk<DesktopNetworkHelper> { every { client } returns configClient }
        val paths = DesktopPlatformPaths.resolve("Linux", root.path, emptyMap())
        val feedbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = Preferences.userRoot().node("/mihon/test/about-feedback-${UUID.randomUUID()}")
        val feedback =
            DesktopFeedbackRuntime(
                createDesktopDiagnosticStore(paths, DesktopPreferenceStore(preferences), feedbackScope),
            )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { networkHelper } returns helper
            every { filePicker } returns picker
            every { feedbackRuntime } returns feedback
        }
        val mounted = mutableStateOf(true)
        val scene = ImageComposeScene(900, 1600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MaterialTheme {
                        if (mounted.value) {
                            AboutFeedbackSection(
                                platformPaths = paths,
                            )
                        } else {
                            Text("Fictional reproduction")
                        }
                    }
                }
            }
            render(scene)
            val trigger = nodes(scene).single {
                it.config.contains(SemanticsActions.OnClick) && "Report a problem" in texts(it)
            }
            requireNotNull(trigger.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            repeat(50) {
                delay(10)
                render(scene)
            }
            click(scene, "feedback-add-screenshot")
            repeat(80) {
                delay(10)
                render(scene)
            }
            var imageNode = nodes(scene).single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("feedback-screenshot-")
            }
            assertTrue(nodes(scene).any { texts(it).any { value -> "100%" in value } })
            click(scene, "feedback-category")
            render(scene)
            click(scene, "feedback-category-reader")
            render(scene)
            input(scene, "feedback-description", "A fictional reader problem retained while reproducing it.")
            click(scene, "feedback-cancel")
            render(scene)
            assertTrue(feedback.memory.snapshot != null)
            mounted.value = false
            render(scene)
            mounted.value = true
            render(scene)
            val reopened = nodes(scene).single {
                it.config.contains(SemanticsActions.OnClick) &&
                    "Report a problem" in texts(it)
            }
            requireNotNull(reopened.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            assertEquals(
                "A fictional reader problem retained while reproducing it.",
                node(scene, "feedback-description").config[SemanticsProperties.EditableText].text,
            )
            imageNode = nodes(scene).single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("feedback-screenshot-")
            }
            assertTrue(texts(nodes(scene).first()).none { "fictional-private-name" in it })
            requireNotNull(imageNode.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            assertTrue(node(scene, "feedback-final-preview").boundsInRoot.width > 0)
            val close = nodes(scene).last { it.config.contains(SemanticsActions.OnClick) && "Close" in texts(it) }
            requireNotNull(close.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            val tag = imageNode.config[SemanticsProperties.TestTag]
            click(scene, tag.replace("feedback-screenshot-", "feedback-remove-screenshot-"))
            render(scene)
            click(scene, "feedback-add-screenshot")
            render(scene)
            assertEquals(2, selections)
            assertTrue(
                nodes(scene).none {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("feedback-screenshot-")
                },
            )
        } finally {
            configServer.close()
            scene.close()
            feedbackScope.cancel()
            preferences.removeNode()
            Locale.setDefault(previous)
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun nodes(
        scene: ImageComposeScene,
    ): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    private fun texts(
        node: SemanticsNode,
    ): List<String> = flatten(node).flatMap {
        if (it.config.contains(
                SemanticsProperties.Text,
            )
        ) {
            it.config[SemanticsProperties.Text].map(AnnotatedString::text)
        } else {
            emptyList()
        }
    }
    private fun node(
        scene: ImageComposeScene,
        tag: String,
    ) = nodes(scene).single {
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
