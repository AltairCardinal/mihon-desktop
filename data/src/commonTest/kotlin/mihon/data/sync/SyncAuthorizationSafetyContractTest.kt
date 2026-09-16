package mihon.data.sync

import kotlinx.coroutines.test.runTest
import mihon.data.sync.auth.GitHubAuthClient
import mihon.data.sync.auth.InMemoryGitHubCredentialStore
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.auth.GitHubDeviceAuthResult
import mihon.domain.sync.auth.GitHubStoredCredential
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncAuthorizationSafetyContractTest {
    private val validDevice = """
        {"device_code":"synthetic-device-code","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":600,"interval":5}
    """.trimIndent()
    private val validToken = """
        {"access_token":"synthetic-access","refresh_token":"synthetic-refresh","token_type":"bearer","expires_in":3600,"refresh_token_expires_in":86400}
    """.trimIndent()

    @Test
    fun `device endpoint HTTP errors remain actionable HTTP failures rather than malformed data`() = runTest {
        for (status in listOf(403, 429, 500)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(code = status, body = "{\"message\":\"synthetic failure\"}"))
                val result = auth(server).authorize("public-client") {
                    error("HTTP failure must not display a device code")
                }
                assertTrue(result is GitHubDeviceAuthResult.Failed)
                val failure = (result as GitHubDeviceAuthResult.Failed).failure
                assertTrue(
                    failure.reason != GitHubAuthFailureReason.MALFORMED_RESPONSE,
                    "HTTP $status was misclassified",
                )
                if (status == 429 || status == 500) assertTrue(failure.retryable)
            }
        }
    }

    @Test
    fun `malformed token fields produce failed authorization without escaping parser exceptions`() = runTest {
        val invalidTokens = listOf(
            """{"access_token":{"unexpected":"object"},"token_type":"bearer"}""",
            """{"access_token":123,"token_type":"bearer"}""",
            """{"access_token":"synthetic-access","token_type":"basic"}""",
            """{"access_token":"synthetic-access","token_type":"bearer","expires_in":-1}""",
        )
        for (tokenBody in invalidTokens) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = validDevice))
                server.enqueue(MockResponse(body = tokenBody))
                val result = runCatching { auth(server).authorize("public-client") {} }
                assertTrue(result.isSuccess, "A malformed response escaped as a parser exception")
                assertTrue(
                    result.getOrThrow() is GitHubDeviceAuthResult.Failed,
                    "Malformed response was authorized: $tokenBody",
                )
            }
        }
    }

    @Test
    fun `invalid verification data is rejected before display`() = runTest {
        val unrelatedDeviceUrl = listOf("https://unrelated.example", "login", "device").joinToString("/")
        val insecureDeviceUrl = listOf(
            "http:",
            "",
            "github.com",
            "login",
            "device",
        ).joinToString("/")
        val invalidDevices = listOf(
            validDevice.replace("https://github.com/login/device", unrelatedDeviceUrl),
            validDevice.replace("https://github.com/login/device", insecureDeviceUrl),
            validDevice.replace("\"interval\":5", "\"interval\":9223372036854775807"),
        )
        for (deviceBody in invalidDevices) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = deviceBody))
                server.enqueue(MockResponse(body = validToken))
                var displayed = false
                val result = auth(server).authorize("public-client") { displayed = true }
                assertTrue(result is GitHubDeviceAuthResult.Failed)
                assertFalse(displayed)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `refresh error payload cannot be treated as success even if it also contains token fields`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = validToken.dropLast(1) + ",\"error\":\"bad_refresh_token\"}"))
            val result = auth(server).refresh("public-client", oldCredential())
            assertTrue(result.isFailure)
            val body = server.takeRequest().body!!.utf8()
            assertTrue(body.contains("grant_type=refresh_token"))
            assertFalse(body.contains("client_secret"))
        }
    }

    @Test
    fun `credential compare and replace does not overwrite a newer login when absence was expected`() = runTest {
        val old = oldCredential()
        val store = InMemoryGitHubCredentialStore(old)
        val replacement = old.credential.copy(accessToken = "synthetic-different-account")
        val outcome = runCatching { store.replace(expectedRevision = null, value = replacement) }
        assertTrue(outcome.isFailure)
        assertEquals(old, store.read())
    }

    private fun auth(server: MockWebServer) = GitHubAuthClient(
        OkHttpClient(),
        GitHubAuthEndpoints(
            server.url("/login/device/code").toString(),
            server.url("/login/oauth/access_token").toString(),
            server.url("/").toString(),
        ),
        waiter = {},
        nowMillis = { 1_000 },
    )

    private fun oldCredential() = GitHubStoredCredential(
        GitHubAccessToken("synthetic-old", "synthetic-refresh", "bearer", emptySet(), 0, 100_000),
        1,
    )
}
