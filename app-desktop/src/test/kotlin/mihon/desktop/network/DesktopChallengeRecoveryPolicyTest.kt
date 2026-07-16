package mihon.desktop.network

import eu.kanade.tachiyomi.network.DesktopCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.BrowserLoginAdapter
import tachiyomi.domain.source.service.BrowserLoginResult
import tachiyomi.domain.source.service.BrowserLoginSession
import tachiyomi.domain.source.service.BrowserOpenResult
import tachiyomi.domain.source.service.SourceLoginRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DesktopChallengeRecoveryPolicyTest {
    @Test
    fun `403 and 503 only publish recovery and explicit cancel preserves existing cookies`() {
        listOf(403, 503).forEach { status ->
            val sourceServer = MockWebServer().also { it.start() }
            val solverServer = MockWebServer().also { it.start() }
            try {
                sourceServer.enqueue(cloudflareChallenge(status))
                solverServer.enqueue(MockResponse(body = """{"status":"error"}"""))
                val cookieJar = DesktopCookieJar()
                cookieJar.addManual(sourceServer.url("/"), CF_CLEARANCE_COOKIE_NAME, "existing-clearance")
                val solver = FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient())
                val manager = CloudflareChallengeManager(
                    committer = DesktopAuthenticatedSessionCommitter(cookieJar),
                    flareSolverrClient = solver,
                )
                val client = OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .addInterceptor(DesktopCloudflareInterceptor(cookieJar, manager))
                    .build()

                val call = executeAsync(client, sourceServer)
                val challenge = awaitChallenge(manager)

                assertNull(
                    solverServer.takeRequest(150, TimeUnit.MILLISECONDS),
                    "solver must not be called by the interceptor",
                )
                assertEquals(
                    "existing-clearance",
                    cookieJar.loadForRequest(sourceServer.url("/")).single { it.name == CF_CLEARANCE_COOKIE_NAME }.value,
                )
                runBlocking { manager.recover(challenge, ChallengeRecoveryIntent.Cancel) }

                assertThrows(ExecutionException::class.java) { call.get(5, TimeUnit.SECONDS) }
                assertEquals(1, sourceServer.requestCount)
                assertEquals(0, solverServer.requestCount)
                assertEquals(
                    "existing-clearance",
                    cookieJar.loadForRequest(sourceServer.url("/")).single { it.name == CF_CLEARANCE_COOKIE_NAME }.value,
                )
                assertEquals(ChallengeRecoveryTerminal.Cancelled, challenge.terminal)
            } finally {
                solverServer.close()
                sourceServer.close()
            }
        }
    }

    @Test
    fun `browser manual and solver adapters run only for their explicit intent`() = runTest {
        val browserOpens = AtomicInteger()
        val commits = RecordingCommitter()
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(solvedResponse(domain = "example.com", value = "solver-secret"))
            val browserAdapter = BrowserLoginAdapter {
                browserOpens.incrementAndGet()
                BrowserOpenResult.Opened(completedBrowserSession(authenticatedSession("browser-secret")))
            }
            val manager = CloudflareChallengeManager(
                browserAdapter = browserAdapter,
                committer = commits,
                flareSolverrClient = FlareSolverrClient(
                    solverServer.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
            )

            val browserChallenge = manager.publish(loginRequest())
            assertEquals(0, browserOpens.get())
            assertEquals(0, solverServer.requestCount)
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(browserChallenge, ChallengeRecoveryIntent.OpenBrowser),
            )
            assertEquals(1, browserOpens.get())
            assertEquals(0, solverServer.requestCount)

            val manualChallenge = manager.publish(loginRequest())
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(
                    manualChallenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("manual-secret")),
                ),
            )
            assertEquals(1, browserOpens.get())
            assertEquals(0, solverServer.requestCount)

            val solverChallenge = manager.publish(loginRequest())
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(solverChallenge, ChallengeRecoveryIntent.UseFlareSolverr),
            )
            assertEquals(1, browserOpens.get())
            assertEquals(1, solverServer.requestCount)
            assertEquals(3, commits.sessions.size)
            assertEquals(
                listOf("browser-secret", "manual-secret", "solver-secret"),
                commits.sessions.map { it.cookies.single().value },
            )
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `solver converts complete cookies and commits through the task 5A jar seam`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                solvedResponse(
                    domain = ".example.com",
                    value = "solver-secret",
                    path = "/reader",
                    expires = 2_000_000_000.0,
                    secure = true,
                    httpOnly = true,
                ),
            )
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(
                    server.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
            )
            val challenge = manager.publish(loginRequest(url = "https://example.com/reader/chapter"))

            val state = manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr)

            assertInstanceOf(ChallengeRecoveryState.Recovered::class.java, state)
            val cookie = jar.loadForRequest("https://example.com/reader/chapter".toHttpUrl()).single()
            assertEquals(CF_CLEARANCE_COOKIE_NAME, cookie.name)
            assertEquals("solver-secret", cookie.value)
            assertEquals("example.com", cookie.domain)
            assertFalse(cookie.hostOnly)
            assertEquals("/reader", cookie.path)
            assertEquals(2_000_000_000_000L, cookie.expiresAt)
            assertTrue(cookie.secure)
            assertTrue(cookie.httpOnly)
        } finally {
            server.close()
        }
    }

    @Test
    fun `invalid manual cookies are recoverable and retry performs no side effect`() = runTest {
        val commits = RecordingCommitter()
        val manager = CloudflareChallengeManager(committer = commits)
        val challenge = manager.publish(loginRequest())

        val failed = manager.recover(
            challenge,
            ChallengeRecoveryIntent.SubmitManualCookies(
                AuthenticatedSession(
                    listOf(authenticatedCookie(name = "unrelated", value = "not-a-clearance")),
                ),
            ),
        )

        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.InvalidCookies),
            failed,
        )
        assertNull(challenge.terminal)
        assertEquals(0, commits.sessions.size)
        assertEquals(ChallengeRecoveryState.AwaitingUserAction, manager.recover(challenge, ChallengeRecoveryIntent.Retry))
        assertEquals(0, commits.sessions.size)
        assertInstanceOf(
            ChallengeRecoveryState.Recovered::class.java,
            manager.recover(
                challenge,
                ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("valid-clearance")),
            ),
        )
        assertEquals(1, commits.sessions.size)
    }

    @Test
    fun `unavailable adapters and commit failures remain recoverable without terminal or writes`() = runTest {
        val unavailableManager = CloudflareChallengeManager(
            browserAdapter = BrowserLoginAdapter { BrowserOpenResult.Unavailable },
        )
        val browserChallenge = unavailableManager.publish(loginRequest())

        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.BrowserUnavailable),
            unavailableManager.recover(browserChallenge, ChallengeRecoveryIntent.OpenBrowser),
        )
        assertNull(browserChallenge.terminal)

        val commitAttempts = AtomicInteger()
        val failingManager = CloudflareChallengeManager(
            committer = AuthenticatedSessionCommitter { _, _ ->
                commitAttempts.incrementAndGet()
                error("persistence unavailable")
            },
        )
        val commitChallenge = failingManager.publish(loginRequest())
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
            failingManager.recover(
                commitChallenge,
                ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("never-written")),
            ),
        )
        assertEquals(1, commitAttempts.get())
        assertNull(commitChallenge.terminal)

        val solverChallenge = failingManager.publish(loginRequest())
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.SolverUnavailable),
            failingManager.recover(solverChallenge, ChallengeRecoveryIntent.UseFlareSolverr),
        )
        assertNull(solverChallenge.terminal)
    }

    @Test
    fun `solver failure empty cookies and malformed response preserve old credentials`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse(code = 500, body = solvedBody("example.com", "must-not-commit")))
            server.enqueue(MockResponse(body = "not-json"))
            server.enqueue(MockResponse(body = """{"status":"ok","solution":{"userAgent":"agent","cookies":[]}}"""))
            val commits = RecordingCommitter()
            val client = FlareSolverrClient(server.url("/").toString().removeSuffix("/"), OkHttpClient())
            val manager = CloudflareChallengeManager(committer = commits, flareSolverrClient = client)

            repeat(3) {
                val challenge = manager.publish(loginRequest())
                assertEquals(
                    ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.SolverFailed),
                    manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr),
                )
                assertNull(challenge.terminal)
            }

            assertEquals(0, commits.sessions.size)
            assertEquals(3, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `timeout wins over a late solver result without writing credentials`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse.Builder()
                    .headersDelay(250, TimeUnit.MILLISECONDS)
                    .body(solvedBody("example.com", "late-secret"))
                    .build(),
            )
            val commits = RecordingCommitter()
            val manager = CloudflareChallengeManager(
                committer = commits,
                flareSolverrClient = FlareSolverrClient(
                    server.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
            )
            val challenge = manager.publish(loginRequest(timeoutMillis = 50))
            val recovery = async(Dispatchers.IO) {
                manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr)
            }

            val terminal = withContext(Dispatchers.IO) { challenge.awaitTerminal() }
            withTimeout(5_000) { recovery.join() }

            assertEquals(ChallengeRecoveryTerminal.TimedOut, terminal)
            assertTrue(recovery.isCancelled)
            assertEquals(0, commits.sessions.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `explicit cancel preempts an in-flight solver without writing credentials`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse.Builder()
                    .headersDelay(5, TimeUnit.SECONDS)
                    .body(solvedBody("example.com", "cancelled-secret"))
                    .build(),
            )
            val commits = RecordingCommitter()
            val manager = CloudflareChallengeManager(
                committer = commits,
                flareSolverrClient = FlareSolverrClient(
                    server.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
            )
            val challenge = manager.publish(loginRequest())
            val recovery = async(Dispatchers.IO) {
                manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr)
            }
            server.takeRequest(5, TimeUnit.SECONDS) ?: error("solver request was not sent")

            val cancelled = manager.recover(challenge, ChallengeRecoveryIntent.Cancel)
            withTimeout(1_000) { recovery.join() }

            assertEquals(ChallengeRecoveryState.Cancelled, cancelled)
            assertEquals(ChallengeRecoveryTerminal.Cancelled, challenge.terminal)
            assertEquals(0, commits.sessions.size)
            assertTrue(recovery.isCancelled)
        } finally {
            server.close()
        }
    }

    @Test
    fun `terminal is exactly once and duplicate late actions do not commit or invoke solver`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(solvedResponse("example.com", "late-solver-secret"))
            val commits = RecordingCommitter()
            val manager = CloudflareChallengeManager(
                committer = commits,
                flareSolverrClient = FlareSolverrClient(
                    server.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
            )
            val challenge = manager.publish(loginRequest())

            val first = manager.recover(
                challenge,
                ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("first-secret")),
            )
            val duplicate = manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr)
            val lateCancel = manager.recover(challenge, ChallengeRecoveryIntent.Cancel)

            assertEquals(first, duplicate)
            assertEquals(first, lateCancel)
            assertEquals(ChallengeRecoveryTerminal.Recovered, challenge.terminal)
            assertEquals(1, commits.sessions.size)
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `successful recovery retries the intercepted request at most once`() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(cloudflareChallenge(503))
            server.enqueue(cloudflareChallenge(503))
            server.enqueue(MockResponse(code = 200, body = "must not be requested"))
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(committer = DesktopAuthenticatedSessionCommitter(jar))
            val client = OkHttpClient.Builder()
                .cookieJar(jar)
                .addInterceptor(DesktopCloudflareInterceptor(jar, manager))
                .build()
            val call = executeAsync(client, server)
            val challenge = awaitChallenge(manager)

            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(
                        authenticatedSession("new-clearance", domain = server.url("/").host),
                    ),
                )
            }

            assertEquals(503, call.get(5, TimeUnit.SECONDS))
            assertEquals(2, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `challenge states and solver models redact cookie values`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(solvedResponse("example.com", "super-secret-value"))
            val result = FlareSolverrClient(server.url("/").toString().removeSuffix("/"), OkHttpClient())
                .solve("https://example.com")
            val state = ChallengeRecoveryState.Recovered(setOf(CF_CLEARANCE_COOKIE_NAME), 1)

            assertFalse(result.toString().contains("super-secret-value"))
            assertFalse(result?.cookies?.single().toString().contains("super-secret-value"))
            assertFalse(state.toString().contains("super-secret-value"))
            assertTrue(result.toString().contains(CF_CLEARANCE_COOKIE_NAME))
        } finally {
            server.close()
        }
    }

    private fun executeAsync(client: OkHttpClient, server: MockWebServer): CompletableFuture<Int> =
        CompletableFuture.supplyAsync {
            client.newCall(Request.Builder().url(server.url("/chapter")).build()).execute().use { it.code }
        }

    private fun awaitChallenge(manager: CloudflareChallengeManager): CloudflareChallenge {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            manager.tryReceive()?.let { return it }
            Thread.yield()
        }
        error("challenge was not published")
    }

    private fun loginRequest(
        url: String = "https://example.com/chapter",
        timeoutMillis: Long = 5_000,
    ) = SourceLoginRequest(
        url = url.toHttpUrl(),
        requiredCookieNames = setOf(CF_CLEARANCE_COOKIE_NAME),
        timeoutMillis = timeoutMillis,
    )

    private fun authenticatedSession(
        value: String,
        domain: String = "example.com",
    ) = AuthenticatedSession(listOf(authenticatedCookie(value = value, domain = domain)))

    private fun authenticatedCookie(
        name: String = CF_CLEARANCE_COOKIE_NAME,
        value: String,
        domain: String = "example.com",
    ) = AuthenticatedCookie(
        name = name,
        value = value,
        domain = domain,
        hostOnly = true,
        path = "/",
        expiresAt = null,
        secure = true,
        httpOnly = true,
    )

    private fun completedBrowserSession(session: AuthenticatedSession) = object : BrowserLoginSession {
        override suspend fun awaitResult(): BrowserLoginResult = BrowserLoginResult.Completed(session)
        override fun cancel() = Unit
    }

    private fun cloudflareChallenge(status: Int) = MockResponse(
        code = status,
        headers = Headers.headersOf("Server", "cloudflare"),
        body = "<html><div id=\"challenge-error-title\">challenge</div></html>",
    )

    private fun solvedResponse(
        domain: String,
        value: String,
        path: String = "/",
        expires: Double? = null,
        secure: Boolean = true,
        httpOnly: Boolean = true,
    ) = MockResponse(body = solvedBody(domain, value, path, expires, secure, httpOnly))

    private fun solvedBody(
        domain: String,
        value: String,
        path: String = "/",
        expires: Double? = null,
        secure: Boolean = true,
        httpOnly: Boolean = true,
    ): String {
        val expiresField = expires?.let { "\"expires\":$it," }.orEmpty()
        return """{"status":"ok","solution":{"userAgent":"desktop-agent","cookies":[{"name":"cf_clearance","value":"$value","domain":"$domain","path":"$path",$expiresField"secure":$secure,"httpOnly":$httpOnly}]}}"""
    }

    private class RecordingCommitter : AuthenticatedSessionCommitter {
        val sessions = mutableListOf<AuthenticatedSession>()

        override suspend fun commit(request: SourceLoginRequest, session: AuthenticatedSession) {
            sessions += session
        }
    }
}
