package mihon.desktop.feedback

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.desktop.network.DesktopCloudflareCredentialInterceptor
import mihon.desktop.network.DesktopCloudflareInterceptor
import mihon.desktop.platform.DesktopNetworkHelper
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import java.net.Proxy

class DesktopNativeFeedbackNetworkTest {
    @Test
    fun `private API preserves route TLS auth and avoids source challenge`() = runBlocking {
        var challengeCalls = 0
        val challenge = mockk<DesktopCloudflareInterceptor> {
            every { intercept(any()) } answers
                {
                    challengeCalls++
                    error("Source challenge must not see a private API")
                }
        }
        val credentials = mockk<DesktopCloudflareCredentialInterceptor> {
            every { intercept(any()) } answers
                {
                    challengeCalls++
                    error("Source cookies must not be attached")
                }
        }
        val authenticator = Authenticator { _, _ -> null }
        val production = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).proxyAuthenticator(authenticator)
            .addInterceptor(challenge).addNetworkInterceptor(credentials).build()
        val helper = mockk<DesktopNetworkHelper> { every { client } returns production }
        val privateRoute = helper.nativeFeedbackClient()
        assertSame(production.proxy, privateRoute.proxy)
        assertSame(production.proxySelector, privateRoute.proxySelector)
        assertSame(production.proxyAuthenticator, privateRoute.proxyAuthenticator)
        assertSame(production.dns, privateRoute.dns)
        assertSame(production.sslSocketFactory, privateRoute.sslSocketFactory)
        assertSame(production.eventListenerFactory, privateRoute.eventListenerFactory)
        assertSame(production.dispatcher, privateRoute.dispatcher)
        assertSame(production.connectionPool, privateRoute.connectionPool)
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(
                MockResponse(
                    code = 403,
                    headers = okhttp3.Headers.headersOf("Server", "cloudflare"),
                    body = "<html><div id='challenge-error-title'>Challenge</div></html>",
                ),
            )
            val api = createDesktopNativeFeedbackService(helper, server.url("/"))
            val failure = try {
                api.createSession("a".repeat(64))
                null
            } catch (
                error: NativeFeedbackException,
            ) {
                error
            }
            assertEquals(NativeFeedbackFailure.EXPIRED, failure?.failure)
            assertEquals(0, challengeCalls)
            assertEquals(1, server.requestCount)
        }
    }
}
