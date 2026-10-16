package eu.kanade.presentation.more.settings.screen.about

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
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
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        MockWebServer().also { it.start() }.use { server ->
            val id = "123e4567-e89b-42d3-a456-426614174000"
            val secret = "s".repeat(43)
            val expiry = System.currentTimeMillis() / 1000 + 600
            val verificationUrl = "https://mihon-feedback.mihon-altaircardinal.workers.dev/verify.html#$id"
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
                compose.onNodeWithTag("feedback-title").performScrollTo().performTextInput("Fictional native report")
                compose.onNodeWithTag(
                    "feedback-description",
                ).performScrollTo().performTextInput(
                    "A fictional problem described only for this native integration test.",
                )
                compose.runOnIdle {
                    assertTrue(opened.isEmpty())
                    assertEquals(0, server.requestCount)
                }
                compose.onNodeWithTag("feedback-public-consent").performScrollTo().performClick()
                compose.onNodeWithTag("feedback-submit").performClick()
                compose.waitUntil(15_000) { server.requestCount >= 3 }
                compose.onNodeWithText("r".repeat(43)).performScrollTo().assertIsDisplayed()
                assertEquals(listOf(verificationUrl), opened)
                val create = server.takeRequest().body!!.utf8()
                assertFalse(create.contains("Fictional"))
                assertEquals("Bearer $secret", server.takeRequest().headers["Authorization"])
                val body = server.takeRequest().body!!.utf8()
                assertTrue(body.contains("Fictional native report"))
                assertFalse(body.contains("Installation ID"))
                assertFalse(body.contains("crashLog"))
                assertFalse(body.contains("turnstileToken"))
            } finally {
                activity.pause().stop().destroy()
                Injekt = previous
            }
        }
    }
}
