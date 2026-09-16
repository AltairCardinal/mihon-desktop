package mihon.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import mihon.data.sync.auth.GitHubAuthClient
import mihon.data.sync.auth.GitHubTokenRefresher
import mihon.data.sync.auth.InMemoryGitHubCredentialStore
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.auth.GitHubAuthWaiter
import mihon.domain.sync.auth.GitHubCredentialStore
import mihon.domain.sync.auth.GitHubDeviceAuthResult
import mihon.domain.sync.auth.GitHubStoredCredential
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AuthProtocolContractTest {
    @Test
    fun `device display precedes all polling and every poll observes the current interval`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = DEVICE))
            server.enqueue(MockResponse(body = """{"error":"authorization_pending"}"""))
            server.enqueue(MockResponse(body = """{"error":"slow_down","interval":20}"""))
            server.enqueue(MockResponse(body = TOKEN))
            val waits = mutableListOf<Long>()
            val requestCountsAtWait = mutableListOf<Int>()
            var displayed = false
            val result = auth(
                server,
                waiter = { millis ->
                    assertTrue(displayed)
                    waits += millis
                    requestCountsAtWait += server.requestCount
                },
            ).authorize("public-client") { displayed = true }
            assertTrue(result is GitHubDeviceAuthResult.Authorized)
            assertEquals(listOf(5_000L, 5_000L, 20_000L), waits)
            assertEquals(listOf(1, 2, 3), requestCountsAtWait)
            repeat(4) {
                val body = server.takeRequest().body!!.utf8()
                assertFalse(body.contains("client_secret"))
                assertFalse(body.contains("scope="))
            }
        }
    }

    @Test
    fun `device expiry includes display and never sends a poll after its deadline`() = runTest {
        for (expireDuringDisplay in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = DEVICE.replace("\"expires_in\":600", "\"expires_in\":2")))
                server.enqueue(MockResponse(body = TOKEN))
                var now = 1_000L
                val waits = mutableListOf<Long>()
                val result = auth(
                    server,
                    waiter = {
                        waits += it
                        now += it
                    },
                    now = { now },
                ).authorize("public-client") {
                    if (expireDuringDisplay) now += 3_000
                }
                assertEquals(
                    GitHubAuthFailureReason.EXPIRED,
                    (result as GitHubDeviceAuthResult.Failed).failure.reason,
                )
                assertEquals(1, server.requestCount)
                assertTrue(waits.sum() <= 2_000)
            }
        }
    }

    @Test
    fun `denied expired and malformed polling responses remain distinct terminal failures`() = runTest {
        val cases = listOf(
            """{"error":"access_denied","error_description":"$SECRET"}""" to GitHubAuthFailureReason.ACCESS_DENIED,
            """{"error":"expired_token"}""" to GitHubAuthFailureReason.EXPIRED,
            """{"access_token":123}""" to GitHubAuthFailureReason.MALFORMED_RESPONSE,
            "not-json $SECRET" to GitHubAuthFailureReason.MALFORMED_RESPONSE,
            "{}" to GitHubAuthFailureReason.MALFORMED_RESPONSE,
        )
        for ((body, expected) in cases) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = DEVICE))
                server.enqueue(MockResponse(body = body))
                val result = auth(server).authorize("public-client") {} as GitHubDeviceAuthResult.Failed
                assertEquals(expected, result.failure.reason)
                assertFalse(result.toString().contains(SECRET))
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `polling HTTP errors preserve retry and revoked classification`() = runTest {
        for (code in listOf(401, 403, 429, 500)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = DEVICE))
                server.enqueue(MockResponse(code = code, body = SECRET))
                val result = auth(server).authorize("public-client") {} as GitHubDeviceAuthResult.Failed
                assertEquals(code in listOf(429, 500), result.failure.retryable)
                if (code == 401) assertEquals(GitHubAuthFailureReason.REVOKED, result.failure.reason)
                assertFalse(result.toString().contains(SECRET))
            }
        }
    }

    @Test
    fun `malformed optional authorization fields and unsafe display URLs are rejected`() = runTest {
        val deviceCases = listOf(
            DEVICE.replace("\"interval\":5", "\"interval\":\"5\""),
            DEVICE.replace("\"interval\":5", "\"interval\":null"),
            DEVICE.replace("https://github.com/login/device", "https://github.com:444/login/device"),
            DEVICE.replace("https://github.com/login/device", "https://user:password@github.com/login/device"),
            DEVICE.dropLast(1) + ",\"error\":\"access_denied\"}",
        )
        for (body in deviceCases) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = body))
                server.enqueue(MockResponse(body = TOKEN))
                var displayed = false
                val result = auth(server).authorize("public-client") { displayed = true }
                assertTrue(result is GitHubDeviceAuthResult.Failed)
                assertFalse(displayed)
                assertEquals(1, server.requestCount)
            }
        }
        for (
        body in listOf(
            TOKEN.replace("\"refresh_token_expires_in\":86400", "\"refresh_token_expires_in\":-1"),
            TOKEN.replace("\"refresh_token\":\"new-refresh\"", "\"refresh_token\":{}"),
            TOKEN.dropLast(1) + ",\"scope\":42}",
        )
        ) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = DEVICE))
                server.enqueue(MockResponse(body = body))
                assertTrue(auth(server).authorize("public-client") {} is GitHubDeviceAuthResult.Failed)
            }
        }
    }

    @Test
    fun `cancellation in display or interval is propagated without another HTTP request`() = runTest {
        for (cancelDisplay in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = DEVICE))
                server.enqueue(MockResponse(body = """{"error":"authorization_pending"}"""))
                val cancellation = CancellationException("synthetic cancellation")
                val result = runCatching {
                    auth(server, waiter = { throw cancellation }).authorize("public-client") {
                        if (cancelDisplay) throw cancellation
                    }
                }
                assertTrue(result.exceptionOrNull() === cancellation)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `cancellation while awaiting auth response cancels the production call promptly`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(DEVICE).headersDelay(4, TimeUnit.SECONDS).build())
            val canceled = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        canceled.countDown()
                    }
                },
            ).build()
            val pending = async(Dispatchers.IO) { auth(server, client).authorize("public-client") {} }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            val start = System.nanoTime()
            pending.cancelAndJoin()
            assertTrue(canceled.await(1, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_500)
        }
    }

    @Test
    fun `real concurrent refresh replaces the complete credential exactly once`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = TOKEN))
            val store = InMemoryGitHubCredentialStore(oldCredential())
            val refresher = GitHubTokenRefresher(auth(server), store) { 1_000 }
            val results = (1..8).map { async { refresher.refreshIfNeeded("public-client") } }.awaitAll()
            assertTrue(results.all { it.isSuccess })
            assertEquals(1, server.requestCount)
            val saved = requireNotNull(store.read())
            assertEquals(2L, saved.revision)
            assertEquals("new-access", saved.credential.accessToken)
            assertEquals("new-refresh", saved.credential.refreshToken)
            assertEquals(3_601_000L, saved.credential.accessTokenExpiresAtMillis)
            assertEquals(86_401_000L, saved.credential.refreshTokenExpiresAtMillis)
            val body = server.takeRequest().body!!.utf8()
            assertTrue(body.contains("grant_type=refresh_token"))
            assertTrue(body.contains("refresh_token=old-refresh"))
            assertFalse(body.contains("client_secret"))
        }
    }

    @Test
    fun `refresh HTTP and malformed failures preserve the complete saved credential`() = runTest {
        for (
        response in listOf(
            MockResponse(code = 401, body = SECRET),
            MockResponse(code = 403, body = SECRET),
            MockResponse(code = 429, body = SECRET),
            MockResponse(code = 500, body = SECRET),
            MockResponse(body = "not-json $SECRET"),
            MockResponse(
                body = TOKEN.replace("\"refresh_token_expires_in\":86400", "\"refresh_token_expires_in\":-1"),
            ),
            MockResponse(body = """{"error":"bad_refresh_token"}"""),
        )
        ) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response)
                val old = oldCredential()
                val store = InMemoryGitHubCredentialStore(old)
                val result = GitHubTokenRefresher(auth(server), store) { 1_000 }.refreshIfNeeded("public-client")
                assertTrue(result.isFailure)
                assertFalse(result.toString().contains(SECRET))
                assertEquals(old, store.read())
            }
        }
    }

    @Test
    fun `real refresh storage failure and concurrent login preserve the winning full record`() = runTest {
        for (newLogin in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = TOKEN))
                val old = oldCredential()
                val delegate = InMemoryGitHubCredentialStore(old)
                val winner = old.credential.copy(accessToken = "other-account", refreshToken = "other-refresh")
                val store = object : GitHubCredentialStore {
                    override suspend fun read() = delegate.read()
                    override suspend fun replace(
                        expectedRevision: Long?,
                        value: GitHubAccessToken,
                    ): GitHubStoredCredential {
                        if (newLogin) delegate.replace(old.revision, winner) else error(SECRET)
                        return delegate.replace(expectedRevision, value)
                    }
                }
                val result = GitHubTokenRefresher(auth(server), store) { 1_000 }.refreshIfNeeded("public-client")
                assertTrue(result.isFailure)
                assertFalse(result.toString().contains(SECRET))
                assertEquals(if (newLogin) winner else old.credential, delegate.read()!!.credential)
            }
        }
    }

    @Test
    fun `nonexpiring user authorization is accepted and never needlessly refreshed`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = DEVICE))
            server.enqueue(
                MockResponse(body = """{"access_token":"permanent-access","token_type":"bearer","scope":""}"""),
            )
            val authorized = auth(server).authorize("public-client") {} as GitHubDeviceAuthResult.Authorized
            val stored = GitHubStoredCredential(authorized.token, 1)
            val store = InMemoryGitHubCredentialStore(stored)
            val result = GitHubTokenRefresher(auth(server), store) { 1_000 }.refreshIfNeeded("public-client")
            assertEquals(stored, result.getOrThrow())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `refresh expiry overflow and revoked responses fail with safe actionable errors`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = TOKEN))
            val old = oldCredential()
            val overflow = auth(server, now = { Long.MAX_VALUE - 1 }).refresh(
                "public-client",
                old.copy(credential = old.credential.copy(refreshTokenExpiresAtMillis = Long.MAX_VALUE)),
            )
            assertTrue(overflow.isFailure)
            server.enqueue(MockResponse(body = """{"error":"bad_refresh_token","error_description":"$SECRET"}"""))
            val revoked = auth(server).refresh("public-client", oldCredential())
            assertEquals("authorization revoked", revoked.exceptionOrNull()?.message)
            assertFalse(revoked.toString().contains(SECRET))
        }
    }

    @Test
    fun `credential read failure remains a safe failure result`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val store = object : GitHubCredentialStore {
                override suspend fun read(): GitHubStoredCredential? = error(SECRET)
                override suspend fun replace(
                    expectedRevision: Long?,
                    value: GitHubAccessToken,
                ): GitHubStoredCredential = error("not reached")
            }
            val outcome = runCatching {
                GitHubTokenRefresher(auth(server), store) { 1_000 }.refreshIfNeeded("public-client")
            }
            assertTrue(outcome.isSuccess)
            assertTrue(outcome.getOrThrow().isFailure)
            assertFalse(outcome.toString().contains(SECRET))
            assertEquals(0, server.requestCount)
        }
    }

    private fun auth(
        server: MockWebServer,
        client: OkHttpClient = OkHttpClient(),
        waiter: GitHubAuthWaiter = GitHubAuthWaiter {},
        now: () -> Long = { 1_000 },
    ) = GitHubAuthClient(
        client,
        GitHubAuthEndpoints(
            server.url("/login/device/code").toString(),
            server.url("/login/oauth/access_token").toString(),
            server.url("/").toString(),
        ),
        waiter,
        now,
    )

    private fun oldCredential() = GitHubStoredCredential(
        GitHubAccessToken("old-access", "old-refresh", "bearer", emptySet(), 0, 100_000),
        1,
    )

    companion object {
        private const val SECRET = "synthetic-private-response"
        private const val DEVICE = """{"device_code":"synthetic-device","user_code":"ABCD-EFGH",""" +
            """"verification_uri":"https://github.com/login/device","expires_in":600,"interval":5}"""
        private const val TOKEN =
            """{"access_token":"new-access","refresh_token":"new-refresh","token_type":"bearer",""" +
                """"expires_in":3600,"refresh_token_expires_in":86400}"""
    }
}
