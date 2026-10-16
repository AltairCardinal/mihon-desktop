package eu.kanade.presentation.more.settings.screen.about

import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import tachiyomi.domain.feedback.NativeFeedbackSession

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidNativeFeedbackNetworkTest {
    @Test
    fun `private poll uses nonCloudflare client and safely fails on WAF`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(
                MockResponse(
                    code = 403,
                    headers = okhttp3.Headers.headersOf("Server", "cloudflare"),
                    body = "<html><div id='challenge-error-title'>Challenge</div></html>",
                ),
            )
            val client = OkHttpClient.Builder().build()
            val helper = mockk<NetworkHelper> {
                every { nonCloudflareClient } returns client
                every { this@mockk.client } throws IllegalStateException("Source WebView chain must not be used")
            }
            val service = createAndroidNativeFeedbackService(helper, server.url("/"))
            val session = NativeFeedbackSession(
                "123e4567-e89b-42d3-a456-426614174000",
                "s".repeat(43),
                System.currentTimeMillis() / 1000 + 600,
                "unused",
            )
            val failure = try {
                service.pollSession(session)
                null
            } catch (error: NativeFeedbackException) {
                error
            }
            assertEquals(NativeFeedbackFailure.EXPIRED, failure?.failure)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("Bearer ${"s".repeat(43)}", request.headers["Authorization"])
            assertEquals(0L, request.bodySize)
        }
    }
}
