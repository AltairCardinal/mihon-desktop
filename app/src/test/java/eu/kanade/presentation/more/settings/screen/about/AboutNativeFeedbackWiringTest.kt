package eu.kanade.presentation.more.settings.screen.about

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mihon.feedback.createAndroidDiagnosticStore
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.feedback.FeedbackDraftMemory
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en")
class AboutNativeFeedbackWiringTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `real About resolves production network provider and submits natively after browser verification`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(UiPreferences(InMemoryPreferenceStore()))
        Injekt.addSingleton(ApplicationUpdatePreferences(InMemoryPreferenceStore()))
        val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnosticStore =
            createAndroidDiagnosticStore(org.robolectric.RuntimeEnvironment.getApplication(), diagnosticScope)
        Injekt.addSingleton(diagnosticStore)
        Injekt.addSingleton(FeedbackDraftMemory())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
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
"capabilities":{"nativeV2":true,
"screenshots":true},
"limits":{"maxScreenshots":2,
"maxScreenshotBytes":524288,
"maxTotalScreenshotBytes":1048576,
"maxScreenshotSide":1920,
"maxBodyBytes":2097152,
"maxLogBytes":262144}}
""",
                ),
            )
            server.enqueue(
                MockResponse(
                    body = """
{"accepting":true,
"siteKey":"public",
"capabilities":{"nativeV2":true,
"screenshots":true},
"limits":{"maxScreenshots":2,
"maxScreenshotBytes":524288,
"maxTotalScreenshotBytes":1048576,
"maxScreenshotSide":1920,
"maxBodyBytes":2097152,
"maxLogBytes":262144}}
""",
                ),
            )
            server.enqueue(
                MockResponse(
                    code = 201,
                    body =
                    """{"sessionId":"$id","secret":"$secret","expiresAt":$expiry,""" +
                        """"verificationUrl":"$verificationUrl"}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"verified":true,"expiresAt":$expiry}"""))
            server.enqueue(
                MockResponse(code = 202, body = """{"status":"received_pending","receipt":"${"r".repeat(43)}"}"""),
            )
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            Injekt.addSingleton(
                mockk<NetworkHelper> {
                    every { nonCloudflareClient } returns client
                    every { this@mockk.client } throws IllegalStateException("Unexpected source challenge client")
                },
            )
            val opened = mutableListOf<String>()
            try {
                activity.get().setContent {
                    CompositionLocalProvider(
                        LocalUriHandler provides
                            object : UriHandler {
                                override fun openUri(uri: String) {
                                    opened += uri
                                }
                            },
                    ) {
                        MaterialTheme {
                            cafe.adriel.voyager.navigator.Navigator(AboutScreen) {
                                cafe.adriel.voyager.navigator.CurrentScreen()
                            }
                        }
                    }
                }
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Report a problem"))
                compose.onNodeWithText("Report a problem").performClick()
                compose.onNodeWithTag("feedback-category").performScrollTo().performClick()
                compose.onNodeWithTag("feedback-category-other").performScrollTo().performClick()
                compose.onNodeWithTag(
                    "feedback-description",
                ).performScrollTo().performTextReplacement(
                    "A fictional problem described only for this native integration test.",
                )
                compose.onNodeWithTag("feedback-diagnostics-start").performScrollTo().performClick()
                compose.waitUntil(5000) { diagnosticStore.state.value.session?.category == DiagnosticCategory.OTHER }
                compose.onNodeWithTag("feedback-diagnostics-leave").performScrollTo().performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Report a problem"))
                compose.onNodeWithText("Report a problem").performClick()
                compose.onNodeWithTag("feedback-category").assertTextContains("Other / not sure", substring = true)
                compose.onNodeWithTag(
                    "feedback-description",
                ).assertTextContains("A fictional problem described only for this native integration test.")
                compose.runOnIdle {
                    assertTrue(opened.isEmpty())
                    assertEquals(2, server.requestCount)
                }
                compose.onNodeWithTag("feedback-public-consent").performScrollTo().performClick()
                compose.waitUntil(5000) {
                    !compose.onNodeWithTag(
                        "feedback-submit",
                    ).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
                }
                compose.onNodeWithTag("feedback-submit").assertIsEnabled().performClick()
                try {
                    compose.waitUntil(15_000) { server.requestCount >= 5 }
                } catch (failure: Throwable) {
                    val status = compose.onNodeWithTag("feedback-status").fetchSemanticsNode().children
                        .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
                    throw AssertionError(
                        "requests=${server.requestCount}, browserOpens=${opened.size}, status=$status",
                        failure,
                    )
                }
                compose.onNodeWithText("r".repeat(43)).performScrollTo().assertIsDisplayed()
                assertEquals(listOf(verificationUrl), opened)
                compose.waitUntil(5000) { diagnosticStore.state.value.session == null }
                assertEquals("/api/config", server.takeRequest().url?.encodedPath)
                assertEquals("/api/config", server.takeRequest().url.encodedPath)
                val create = server.takeRequest().body!!.utf8()
                assertFalse(create.contains("Fictional"))
                assertEquals("Bearer $secret", server.takeRequest().headers["Authorization"])
                val body = server.takeRequest().body!!.utf8()
                assertTrue(body.contains("\"protocolVersion\":2"))
                assertTrue(body.contains("\"categoryId\":\"other\""))
                assertFalse(body.contains("Installation ID"))
                assertFalse(body.contains("crashLog"))
                assertFalse(body.contains("turnstileToken"))
            } finally {
                activity.pause().stop().destroy()
                diagnosticScope.cancel()
                Injekt = previous
            }
        }
    }

    @Test
    fun `failed durable stop stays active and offers an accurate visible retry`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = java.nio.file.Files.createTempDirectory("fictional-failed-stop").toFile()
        val fail = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = tachiyomi.core.common.feedback.DiagnosticStore(
            root,
            object : tachiyomi.core.common.feedback.DiagnosticPersistence {
                var value: tachiyomi.core.common.feedback.DiagnosticSession? = null
                override fun load() = value
                override fun save(session: tachiyomi.core.common.feedback.DiagnosticSession?) {
                    if (fail.get()) throw java.io.IOException("Fictional write failure")
                    value = session
                }
            },
            scope,
        )
        kotlinx.coroutines.runBlocking { store.start(DiagnosticCategory.READER) }
        fail.set(true)
        Injekt.addSingleton(store)
        Injekt.addSingleton(FeedbackDraftMemory())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(
                MockResponse(
                    body = """
{"accepting":true,
"capabilities":{"nativeV2":true,
"screenshots":true},
"limits":{"maxScreenshots":2,
"maxScreenshotBytes":524288,
"maxTotalScreenshotBytes":1048576,
"maxScreenshotSide":1920,
"maxBodyBytes":2097152,
"maxLogBytes":262144}}
""",
                ),
            )
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            Injekt.addSingleton(mockk<NetworkHelper> { every { nonCloudflareClient } returns client })
            try {
                activity.get().setContent { MaterialTheme { AboutFeedbackPreference() } }
                compose.onNodeWithText("Report a problem").performClick()
                compose.onNodeWithTag("feedback-diagnostics-stop").performScrollTo().performClick()
                compose.waitUntil(5000) {
                    store.state.value.error ==
                        tachiyomi.core.common.feedback.DiagnosticError.STORAGE
                }
                assertFalse(store.state.value.session!!.paused)
                compose.onNodeWithText("Could not save the logging mode. Logging is still on. Retry stopping it.")
                    .performScrollTo().assertIsDisplayed()
                fail.set(false)
                compose.onNodeWithTag("feedback-diagnostics-stop").performScrollTo().performClick()
                compose.waitUntil(5000) { store.state.value.session == null }
                fail.set(true)
                compose.onNodeWithTag("feedback-category").performScrollTo().performClick()
                compose.onNodeWithTag("feedback-category-other").performScrollTo().performClick()
                compose.onNodeWithTag("feedback-diagnostics-start").performScrollTo().performClick()
                compose.waitUntil(5000) {
                    store.state.value.error ==
                        tachiyomi.core.common.feedback.DiagnosticError.STORAGE
                }
                assertEquals(null, store.state.value.session)
                compose.onNodeWithText("Logging could not start or restore. Check local storage and retry.")
                    .performScrollTo().assertIsDisplayed()
            } finally {
                activity.pause().stop().destroy()
                scope.cancel()
                Injekt = previous
                root.deleteRecursively()
            }
        }
    }
}
