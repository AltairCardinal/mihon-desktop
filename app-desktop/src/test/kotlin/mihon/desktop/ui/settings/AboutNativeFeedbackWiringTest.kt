package mihon.desktop.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopExtensionManager
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import java.io.File
import java.util.Locale

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
            val update = mockk<DesktopUpdateScreenModel>(relaxed = true) {
                every { state } returns MutableStateFlow(DesktopUpdateState.Idle)
                every { feedback } returns MutableStateFlow(null)
                every { preferences } returns ApplicationUpdatePreferences(InMemoryPreferenceStore())
                every { presentation() } returns DesktopUpdateState.Idle.presentation()
            }
            val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
                every { networkHelper } returns helper
                every { updateScreenModel } returns update
                every { extensionPresentationService } returns DesktopExtensionManager()
            }
            val paths = DesktopPlatformPaths.resolve("Linux", root.path, emptyMap())
            paths.logsDir.mkdirs()
            File(paths.logsDir, "mihon_crash.log").writeText("Fictional About private crash\n")
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
                input(scene, "feedback-title", "Fictional About report")
                input(
                    scene,
                    "feedback-description",
                    "A fictional failure described in the native About integration test.",
                )
                click(scene, "feedback-read-log")
                repeat(20) {
                    delay(10)
                    render(scene)
                }
                assertEquals(
                    "Fictional About private crash\n",
                    node(scene, "feedback-log").config[SemanticsProperties.EditableText].text,
                )
                assertEquals(0, server.requestCount)
                click(scene, "feedback-log-consent")
                click(scene, "feedback-public-consent")
                click(scene, "feedback-submit")
                repeat(150) {
                    delay(20)
                    render(scene)
                }
                assertEquals(listOf(verificationUrl), opened)
                assertEquals(3, server.requestCount)
                val create = server.takeRequest()
                assertFalse(create.body!!.utf8().contains("Fictional"))
                assertEquals("Bearer $secret", server.takeRequest().headers["Authorization"])
                val sent = server.takeRequest().body!!.utf8()
                assertTrue(sent.contains("Fictional About private crash"))
                assertFalse(sent.contains(root.path))
                assertTrue(nodes(scene).any { texts(it).contains("r".repeat(43)) })
            } finally {
                scene.close()
                Locale.setDefault(previous)
            }
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
