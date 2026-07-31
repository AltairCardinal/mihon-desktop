package mihon.desktop.network

import eu.kanade.tachiyomi.network.DesktopCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.desktop.platform.DesktopNetworkHelper
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.Interceptor
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
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.BrowserLoginAdapter
import tachiyomi.domain.source.service.BrowserLoginResult
import tachiyomi.domain.source.service.BrowserLoginSession
import tachiyomi.domain.source.service.BrowserOpenResult
import tachiyomi.domain.source.service.SourceLoginRequest
import java.io.IOException
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DesktopChallengeRecoveryPolicyTest {
    @Test
    fun `production client exposes upstream cloudflare interceptor runtime contract`(
        @TempDir tempDir: Path,
    ) {
        DesktopNetworkHelper(
            cacheDir = tempDir.resolve("contract-cache").toFile(),
            cookieStorageFile = tempDir.resolve("contract-cookies.json").toFile(),
            challengeManager = CloudflareChallengeManager(),
        ).use { helper ->
            assertTrue(
                helper.client.interceptors.any { it.javaClass.simpleName == "CloudflareInterceptor" },
                "Keiyoushi sources require an application interceptor named CloudflareInterceptor",
            )
        }
    }

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
                    .addInterceptor(DesktopCloudflareInterceptor(manager))
                    .addNetworkInterceptor(DesktopCloudflareCredentialInterceptor(manager))
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
                authenticatedCookieLookup = desktopCookieLookup(jar),
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
    fun `browser and manual required cookies with blank values share zero-write validation`() = runTest {
        val commits = RecordingCommitter()
        val blankSession = authenticatedSession("   ")
        val manager = CloudflareChallengeManager(
            browserAdapter = BrowserLoginAdapter {
                BrowserOpenResult.Opened(completedBrowserSession(blankSession))
            },
            committer = commits,
        )

        val browserChallenge = manager.publish(loginRequest())
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.InvalidCookies),
            manager.recover(browserChallenge, ChallengeRecoveryIntent.OpenBrowser),
        )
        assertNull(browserChallenge.terminal)

        val manualChallenge = manager.publish(loginRequest())
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.InvalidCookies),
            manager.recover(
                manualChallenge,
                ChallengeRecoveryIntent.SubmitManualCookies(blankSession),
            ),
        )
        assertNull(manualChallenge.terminal)
        assertEquals(0, commits.sessions.size)
    }

    @Test
    fun `browser manual and solver accept a valid required candidate alongside compatible blanks`() = runTest {
        val mixedRequired = AuthenticatedSession(
            listOf(
                authenticatedCookie(value = ""),
                authenticatedCookie(value = "valid-clearance"),
            ),
        )
        val manualWithOptionalBlank = AuthenticatedSession(
            listOf(
                authenticatedCookie(value = "manual-clearance"),
                authenticatedCookie(name = "optional", value = ""),
            ),
        )
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(
                MockResponse(
                    body = """{"status":"ok","solution":{"userAgent":"solver-agent","cookies":[{"name":"cf_clearance","value":"","domain":"example.com"},{"name":"cf_clearance","value":"solver-clearance","domain":"example.com"}]}}""",
                ),
            )
            val commits = RecordingCommitter()
            val manager = CloudflareChallengeManager(
                browserAdapter = BrowserLoginAdapter {
                    BrowserOpenResult.Opened(completedBrowserSession(mixedRequired))
                },
                committer = commits,
                flareSolverrClient = FlareSolverrClient(
                    solverServer.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
            )

            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.OpenBrowser),
            )
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(
                    manager.publish(loginRequest()),
                    ChallengeRecoveryIntent.SubmitManualCookies(manualWithOptionalBlank),
                ),
            )
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.UseFlareSolverr),
            )
            assertEquals(3, commits.sessions.size)
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `required cookies must be deliverable before browser manual or solver can commit to the real jar`() = runTest {
        val pathJar = DesktopCookieJar()
        val pathSession = AuthenticatedSession(
            listOf(authenticatedCookie(value = "path-secret", path = "/auth")),
        )
        val browserManager = CloudflareChallengeManager(
            browserAdapter = BrowserLoginAdapter {
                BrowserOpenResult.Opened(completedBrowserSession(pathSession))
            },
            committer = DesktopAuthenticatedSessionCommitter(pathJar),
        )
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.InvalidCookies),
            browserManager.recover(
                browserManager.publish(loginRequest(url = "https://example.com/chapter")),
                ChallengeRecoveryIntent.OpenBrowser,
            ),
        )
        assertTrue(pathJar.loadForRequest("https://example.com/auth".toHttpUrl()).isEmpty())

        val secureJar = DesktopCookieJar()
        val manualManager = CloudflareChallengeManager(committer = DesktopAuthenticatedSessionCommitter(secureJar))
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.InvalidCookies),
            manualManager.recover(
                manualManager.publish(loginRequest(url = "http://example.com/chapter")),
                ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("secure-secret")),
            ),
        )
        assertTrue(secureJar.loadForRequest("https://example.com/chapter".toHttpUrl()).isEmpty())

        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(solvedResponse("example.com", "expired-secret", expires = 1.0))
            val expiredJar = DesktopCookieJar()
            val solverManager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(expiredJar),
                flareSolverrClient = FlareSolverrClient(
                    solverServer.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
            )
            assertEquals(
                ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.SolverFailed),
                solverManager.recover(
                    solverManager.publish(loginRequest()),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                ),
            )
            assertTrue(expiredJar.loadForRequest(loginRequest().url).isEmpty())
        } finally {
            solverServer.close()
        }

        val siblingJar = DesktopCookieJar()
        val siblingManager = CloudflareChallengeManager(committer = DesktopAuthenticatedSessionCommitter(siblingJar))
        assertInstanceOf(
            ChallengeRecoveryState.Recovered::class.java,
            siblingManager.recover(
                siblingManager.publish(loginRequest(url = "https://example.com/chapter")),
                ChallengeRecoveryIntent.SubmitManualCookies(
                    AuthenticatedSession(
                        listOf(
                            authenticatedCookie(value = "auth-secret", path = "/auth"),
                            authenticatedCookie(value = "root-secret"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "root-secret",
            siblingJar.loadForRequest("https://example.com/chapter".toHttpUrl()).single().value,
        )
        assertEquals(
            setOf("auth-secret", "root-secret"),
            siblingJar.loadForRequest("https://example.com/auth".toHttpUrl()).map { it.value }.toSet(),
        )

        val canonicalJar = DesktopCookieJar()
        val canonicalManager = CloudflareChallengeManager(committer = DesktopAuthenticatedSessionCommitter(canonicalJar))
        assertInstanceOf(
            ChallengeRecoveryState.Recovered::class.java,
            canonicalManager.recover(
                canonicalManager.publish(loginRequest(url = "http://example.com/chapter")),
                ChallengeRecoveryIntent.SubmitManualCookies(
                    AuthenticatedSession(
                        listOf(
                            authenticatedCookie(value = "same-secret", secure = true),
                            authenticatedCookie(value = "same-secret", secure = false),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "same-secret",
            canonicalJar.loadForRequest("http://example.com/chapter".toHttpUrl()).single().value,
        )
    }

    @Test
    fun `browser manual and solver reject conflicting canonical credentials in both input orders`() = runTest {
        val orders = listOf(
            listOf("old-account-secret", "new-account-secret"),
            listOf("new-account-secret", "old-account-secret"),
        )
        val solverServer = MockWebServer().also { it.start() }
        try {
            orders.forEach { values ->
                val conflictingSession = AuthenticatedSession(values.map { authenticatedCookie(value = it) })

                val browserCommits = RecordingCommitter()
                val browserManager = CloudflareChallengeManager(
                    browserAdapter = BrowserLoginAdapter {
                        BrowserOpenResult.Opened(completedBrowserSession(conflictingSession))
                    },
                    committer = browserCommits,
                )
                val browserState = browserManager.recover(
                    browserManager.publish(loginRequest()),
                    ChallengeRecoveryIntent.OpenBrowser,
                )
                assertEquals(
                    ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.InvalidCookies),
                    browserState,
                )
                assertTrue(browserCommits.sessions.isEmpty())

                val manualCommits = RecordingCommitter()
                val manualManager = CloudflareChallengeManager(committer = manualCommits)
                val manualState = manualManager.recover(
                    manualManager.publish(loginRequest()),
                    ChallengeRecoveryIntent.SubmitManualCookies(conflictingSession),
                )
                assertEquals(
                    ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.InvalidCookies),
                    manualState,
                )
                assertTrue(manualCommits.sessions.isEmpty())

                solverServer.enqueue(
                    MockResponse(
                        body = """{"status":"ok","solution":{"userAgent":"solver-agent","cookies":[{"name":"cf_clearance","value":"${values[0]}","domain":"example.com"},{"name":"cf_clearance","value":"${values[1]}","domain":"example.com"}]}}""",
                    ),
                )
                val solverCommits = RecordingCommitter()
                val solverManager = CloudflareChallengeManager(
                    committer = solverCommits,
                    flareSolverrClient = FlareSolverrClient(
                        solverServer.url("/").toString().removeSuffix("/"),
                        OkHttpClient(),
                    ),
                )
                val solverState = solverManager.recover(
                    solverManager.publish(loginRequest()),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )
                assertEquals(
                    ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.SolverFailed),
                    solverState,
                )
                assertTrue(solverCommits.sessions.isEmpty())

                listOf(browserState, manualState, solverState).forEach { state ->
                    values.forEach { assertFalse(state.toString().contains(it)) }
                }
            }
        } finally {
            solverServer.close()
        }
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
        assertEquals(ChallengeRecoveryTerminal.Failed, commitChallenge.terminal)

        val solverChallenge = failingManager.publish(loginRequest())
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.SolverUnavailable),
            failingManager.recover(solverChallenge, ChallengeRecoveryIntent.UseFlareSolverr),
        )
        assertNull(solverChallenge.terminal)
    }

    @Test
    fun `real persistence failure can retry a new atomic session without changing the old waiter result`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val url = loginRequest().url
        val storage = tempDir.resolve("cookies.json").toFile()
        val futureExpiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
        DesktopCookieJar(storage).saveFromResponse(
            url,
            listOf(
                Cookie.Builder()
                    .name(CF_CLEARANCE_COOKIE_NAME)
                    .value("old-clearance")
                    .hostOnlyDomain(url.host)
                    .path("/")
                    .expiresAt(futureExpiry)
                    .secure()
                    .httpOnly()
                    .build(),
            ),
        )
        val persistenceAttempts = AtomicInteger()
        val jar = DesktopCookieJar(storage) { source, target ->
            if (persistenceAttempts.incrementAndGet() == 1) {
                throw IOException("first persistence attempt fails")
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        val manager = CloudflareChallengeManager(committer = DesktopAuthenticatedSessionCommitter(jar))
        val challenge = manager.publish(loginRequest())
        val oldWaiter = CompletableFuture.supplyAsync { challenge.awaitTerminal() }

        val failed = manager.recover(
            challenge,
            ChallengeRecoveryIntent.SubmitManualCookies(
                authenticatedSession("first-new-clearance", expiresAt = futureExpiry),
            ),
        )

        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
            failed,
        )
        assertEquals(ChallengeRecoveryTerminal.Failed, oldWaiter.get(5, TimeUnit.SECONDS))
        assertEquals(
            "old-clearance",
            jar.loadForRequest(url).single { it.name == CF_CLEARANCE_COOKIE_NAME }.value,
        )

        assertEquals(ChallengeRecoveryState.AwaitingUserAction, manager.recover(challenge, ChallengeRecoveryIntent.Retry))
        assertNull(challenge.terminal)
        assertInstanceOf(
            ChallengeRecoveryState.Recovered::class.java,
            manager.recover(
                challenge,
                ChallengeRecoveryIntent.SubmitManualCookies(
                    authenticatedSession("second-new-clearance", expiresAt = futureExpiry),
                ),
            ),
        )
        assertEquals(ChallengeRecoveryTerminal.Failed, oldWaiter.get(5, TimeUnit.SECONDS))
        assertEquals(2, persistenceAttempts.get())
        assertEquals(
            "second-new-clearance",
            jar.loadForRequest(url).single { it.name == CF_CLEARANCE_COOKIE_NAME }.value,
        )
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
    fun `solver HTTP and incomplete solution matrix is recoverable with zero writes`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse(code = 403, body = solvedBody("example.com", "forbidden")))
            server.enqueue(MockResponse(code = 429, body = solvedBody("example.com", "rate-limited")))
            server.enqueue(MockResponse(code = 500, body = solvedBody("example.com", "server-error")))
            server.enqueue(MockResponse(body = """{"status":"ok"}"""))
            server.enqueue(MockResponse(body = "not-json"))
            server.enqueue(MockResponse(body = """{"status":"ok","solution":{"userAgent":"agent","cookies":[]}}"""))
            server.enqueue(
                MockResponse(
                    body = solvedBody(
                        domain = "example.com",
                        value = "blank-agent-must-not-commit",
                        userAgent = "   ",
                    ),
                ),
            )
            server.enqueue(
                MockResponse(
                    body = solvedBody(
                        domain = "example.com",
                        value = "   ",
                        userAgent = "agent",
                    ),
                ),
            )
            val commits = RecordingCommitter()
            val manager = CloudflareChallengeManager(
                committer = commits,
                flareSolverrClient = FlareSolverrClient(
                    server.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
            )

            repeat(8) { index ->
                val challenge = manager.publish(loginRequest())
                assertEquals(
                    ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.SolverFailed),
                    manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr),
                    "matrix case $index must remain recoverable",
                )
                assertNull(challenge.terminal)
                assertEquals(0, commits.sessions.size, "matrix case $index must not commit")
            }

            assertEquals(8, server.requestCount)
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
                    .headersDelay(30, TimeUnit.SECONDS)
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
            val challenge = manager.publish(loginRequest(timeoutMillis = 5_000))
            val recovery = async(Dispatchers.IO) {
                manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr)
            }
            server.takeRequest(5, TimeUnit.SECONDS) ?: error("solver request was not sent")

            withTimeout(10_000) { recovery.join() }

            assertEquals(ChallengeRecoveryTerminal.TimedOut, challenge.terminal)
            assertEquals(ChallengeRecoveryState.TimedOut, challenge.state.value)
            assertTrue(recovery.isCancelled)
            assertEquals(0, commits.sessions.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `deadline before action registration returns timeout without cancelling recovery`() = runBlocking {
        val nowNanos = AtomicLong()
        val challenge = CloudflareChallenge(
            loginRequest(timeoutMillis = 100),
            nanoTime = nowNanos::get,
        )
        val manager = CloudflareChallengeManager(committer = RecordingCommitter())
        nowNanos.set(TimeUnit.MILLISECONDS.toNanos(101))

        val recovery = async {
            manager.recover(challenge, ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("unused")))
        }

        assertEquals(ChallengeRecoveryState.TimedOut, recovery.await())
        assertFalse(recovery.isCancelled)
        assertEquals(ChallengeRecoveryTerminal.TimedOut, challenge.terminal)
        assertEquals(ChallengeRecoveryState.TimedOut, challenge.state.value)
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
    fun `browser user cancellation returns cancelled without cancelling the recovery caller`() = runTest {
        val manager = CloudflareChallengeManager(
            browserAdapter = BrowserLoginAdapter {
                BrowserOpenResult.Opened(
                    object : BrowserLoginSession {
                        override suspend fun awaitResult(): BrowserLoginResult = BrowserLoginResult.Cancelled
                        override fun cancel() = Unit
                    },
                )
            },
            committer = RecordingCommitter(),
        )
        val challenge = manager.publish(loginRequest())
        val recovery = async {
            manager.recover(challenge, ChallengeRecoveryIntent.OpenBrowser)
        }

        assertEquals(ChallengeRecoveryState.Cancelled, recovery.await())
        assertFalse(recovery.isCancelled)
        assertEquals(ChallengeRecoveryTerminal.Cancelled, challenge.terminal)
        assertEquals(ChallengeRecoveryState.Cancelled, challenge.state.value)
    }

    @Test
    fun `guarded real desktop commit leaves the caller dispatcher for IO`() {
        val callerExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "challenge-caller") }
        val commitExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "challenge-commit-io") }
        callerExecutor.asCoroutineDispatcher().use { callerDispatcher ->
            commitExecutor.asCoroutineDispatcher().use { commitDispatcher ->
                val callerThread = AtomicReference<String>()
                val commitThread = AtomicReference<String>()
                val jar = DesktopCookieJar()
                val delegate = DesktopAuthenticatedSessionCommitter(jar)
                val manager = CloudflareChallengeManager(
                    committer = AuthenticatedSessionCommitter { request, session ->
                        commitThread.set(Thread.currentThread().name)
                        delegate.commit(request, session)
                    },
                    commitDispatcher = commitDispatcher,
                )
                val challenge = manager.publish(loginRequest())

                val state = runBlocking {
                    withContext(callerDispatcher) {
                        callerThread.set(Thread.currentThread().name)
                        manager.recover(
                            challenge,
                            ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("new-clearance")),
                        )
                    }
                }

                assertInstanceOf(ChallengeRecoveryState.Recovered::class.java, state)
                assertTrue(callerThread.get().startsWith("challenge-caller"))
                assertTrue(commitThread.get().startsWith("challenge-commit-io"))
                assertFalse(commitThread.get().startsWith("challenge-caller"))
                assertEquals(
                    "new-clearance",
                    jar.loadForRequest(loginRequest().url).single { it.name == CF_CLEARANCE_COOKIE_NAME }.value,
                )
            }
        }
    }

    @Test
    fun `commit claim wins over cancel and publishes the real persisted outcome`() {
        val jar = DesktopCookieJar()
        val url = "https://example.com/chapter".toHttpUrl()
        jar.addManual(url, CF_CLEARANCE_COOKIE_NAME, "old-clearance")
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val commitCount = AtomicInteger()
        val delegate = DesktopAuthenticatedSessionCommitter(jar)
        val manager = CloudflareChallengeManager(
            committer = AuthenticatedSessionCommitter { request, session ->
                commitStarted.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
                delegate.commit(request, session)
                commitCount.incrementAndGet()
            },
        )
        val challenge = manager.publish(loginRequest())
        val recovery = CompletableFuture.supplyAsync {
            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("new-clearance")),
                )
            }
        }
        check(commitStarted.await(5, TimeUnit.SECONDS))

        val cancel = CompletableFuture.supplyAsync {
            runBlocking { manager.recover(challenge, ChallengeRecoveryIntent.Cancel) }
        }
        assertThrows(TimeoutException::class.java) { cancel.get(150, TimeUnit.MILLISECONDS) }
        releaseCommit.countDown()

        assertInstanceOf(ChallengeRecoveryState.Recovered::class.java, recovery.get(5, TimeUnit.SECONDS))
        assertInstanceOf(ChallengeRecoveryState.Recovered::class.java, cancel.get(5, TimeUnit.SECONDS))
        assertEquals(ChallengeRecoveryTerminal.Recovered, challenge.terminal)
        assertEquals(1, commitCount.get())
        assertEquals(
            "new-clearance",
            jar.loadForRequest(url).single { it.name == CF_CLEARANCE_COOKIE_NAME }.value,
        )
    }

    @Test
    fun `commit claim wins over deadline and terminal wait observes the real persisted outcome`() {
        val jar = DesktopCookieJar()
        val url = "https://example.com/chapter".toHttpUrl()
        jar.addManual(url, CF_CLEARANCE_COOKIE_NAME, "old-clearance")
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val delegate = DesktopAuthenticatedSessionCommitter(jar)
        val manager = CloudflareChallengeManager(
            committer = AuthenticatedSessionCommitter { request, session ->
                commitStarted.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
                delegate.commit(request, session)
            },
        )
        val challenge = manager.publish(loginRequest(timeoutMillis = 100))
        val recovery = CompletableFuture.supplyAsync {
            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("new-clearance")),
                )
            }
        }
        check(commitStarted.await(5, TimeUnit.SECONDS))
        val terminalWait = CompletableFuture.supplyAsync { challenge.awaitTerminal() }

        assertThrows(TimeoutException::class.java) { terminalWait.get(250, TimeUnit.MILLISECONDS) }
        releaseCommit.countDown()

        assertInstanceOf(ChallengeRecoveryState.Recovered::class.java, recovery.get(5, TimeUnit.SECONDS))
        assertEquals(ChallengeRecoveryTerminal.Recovered, terminalWait.get(5, TimeUnit.SECONDS))
        assertEquals(
            "new-clearance",
            jar.loadForRequest(url).single { it.name == CF_CLEARANCE_COOKIE_NAME }.value,
        )
    }

    @Test
    fun `commit claim wins over cancel and publishes the real failed outcome`() {
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val commitCount = AtomicInteger()
        val manager = CloudflareChallengeManager(
            committer = AuthenticatedSessionCommitter { _, _ ->
                commitStarted.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
                commitCount.incrementAndGet()
                error("persistence failed")
            },
        )
        val challenge = manager.publish(loginRequest())
        val recovery = CompletableFuture.supplyAsync {
            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("never-written")),
                )
            }
        }
        check(commitStarted.await(5, TimeUnit.SECONDS))

        val cancel = CompletableFuture.supplyAsync {
            runBlocking { manager.recover(challenge, ChallengeRecoveryIntent.Cancel) }
        }
        assertThrows(TimeoutException::class.java) { cancel.get(150, TimeUnit.MILLISECONDS) }
        releaseCommit.countDown()

        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
            recovery.get(5, TimeUnit.SECONDS),
        )
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
            cancel.get(5, TimeUnit.SECONDS),
        )
        assertEquals("Failed", challenge.terminal?.name)
        assertEquals(1, commitCount.get())
    }

    @Test
    fun `cancel bound to claimed attempt A returns A failure while retry B belongs to new waiters and actions`() {
        val commitStarted = CountDownLatch(1)
        val releaseFirstCommit = CountDownLatch(1)
        val cancelCaptured = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val attempts = AtomicInteger()
        val challenge = CloudflareChallenge(
            loginRequest(),
            afterCancelDecisionObserved = {
                cancelCaptured.countDown()
                check(releaseCancel.await(5, TimeUnit.SECONDS))
            },
        )
        val manager = CloudflareChallengeManager(
            committer = AuthenticatedSessionCommitter { _, _ ->
                if (attempts.incrementAndGet() == 1) {
                    commitStarted.countDown()
                    check(releaseFirstCommit.await(5, TimeUnit.SECONDS))
                    error("attempt A persistence failed")
                }
            },
        )
        val recoveryA = CompletableFuture.supplyAsync {
            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("attempt-a")),
                )
            }
        }
        check(commitStarted.await(5, TimeUnit.SECONDS))
        val cancelA = CompletableFuture.supplyAsync {
            runBlocking { manager.recover(challenge, ChallengeRecoveryIntent.Cancel) }
        }
        check(cancelCaptured.await(5, TimeUnit.SECONDS))

        releaseFirstCommit.countDown()
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
            recoveryA.get(5, TimeUnit.SECONDS),
        )
        assertEquals(ChallengeRecoveryState.AwaitingUserAction, runBlocking {
            manager.recover(challenge, ChallengeRecoveryIntent.Retry)
        })
        val waiterB = CompletableFuture.supplyAsync { challenge.awaitTerminal() }
        releaseCancel.countDown()
        assertInstanceOf(
            ChallengeRecoveryState.Recovered::class.java,
            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("attempt-b")),
                )
            },
        )

        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
            cancelA.get(5, TimeUnit.SECONDS),
        )
        assertEquals(ChallengeRecoveryTerminal.Recovered, waiterB.get(5, TimeUnit.SECONDS))
        assertEquals(ChallengeRecoveryTerminal.Recovered, challenge.terminal)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `cancel atomically returns terminal attempt A when retry B replaces the current attempt`() {
        val cancelCaptured = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val attempts = AtomicInteger()
        val challenge = CloudflareChallenge(
            loginRequest(),
            afterCancelDecisionObserved = {
                cancelCaptured.countDown()
                check(releaseCancel.await(5, TimeUnit.SECONDS))
            },
        )
        val manager = CloudflareChallengeManager(
            committer = AuthenticatedSessionCommitter { _, _ ->
                if (attempts.incrementAndGet() == 1) error("attempt A persistence failed")
            },
        )
        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("attempt-a")),
                )
            },
        )
        val cancelA = CompletableFuture.supplyAsync {
            runBlocking { manager.recover(challenge, ChallengeRecoveryIntent.Cancel) }
        }
        check(cancelCaptured.await(5, TimeUnit.SECONDS))

        assertEquals(ChallengeRecoveryState.AwaitingUserAction, runBlocking {
            manager.recover(challenge, ChallengeRecoveryIntent.Retry)
        })
        val waiterB = CompletableFuture.supplyAsync { challenge.awaitTerminal() }
        releaseCancel.countDown()
        assertInstanceOf(
            ChallengeRecoveryState.Recovered::class.java,
            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("attempt-b")),
                )
            },
        )

        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
            cancelA.get(5, TimeUnit.SECONDS),
        )
        assertEquals(ChallengeRecoveryTerminal.Recovered, waiterB.get(5, TimeUnit.SECONDS))
        assertEquals(2, attempts.get())
    }

    @Test
    fun `cancel before commit claim prevents delegate entry and late completion`() {
        val openStarted = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val commitCount = AtomicInteger()
        val manager = CloudflareChallengeManager(
            browserAdapter = BrowserLoginAdapter {
                openStarted.countDown()
                check(releaseOpen.await(5, TimeUnit.SECONDS))
                BrowserOpenResult.Opened(completedBrowserSession(authenticatedSession("late-clearance")))
            },
            committer = AuthenticatedSessionCommitter { _, _ -> commitCount.incrementAndGet() },
        )
        val challenge = manager.publish(loginRequest())
        val recovery = CompletableFuture.supplyAsync {
            runBlocking { manager.recover(challenge, ChallengeRecoveryIntent.OpenBrowser) }
        }
        check(openStarted.await(5, TimeUnit.SECONDS))

        assertEquals(
            ChallengeRecoveryState.Cancelled,
            runBlocking { manager.recover(challenge, ChallengeRecoveryIntent.Cancel) },
        )
        releaseOpen.countDown()
        runCatching { recovery.get(5, TimeUnit.SECONDS) }

        assertEquals(ChallengeRecoveryTerminal.Cancelled, challenge.terminal)
        assertEquals(ChallengeRecoveryState.Cancelled, challenge.state.value)
        assertEquals(0, commitCount.get())
    }

    @Test
    fun `deadline before commit claim publishes timeout without cancelling the recovery result`() = runTest {
        val nowNanos = AtomicLong()
        val commitCount = AtomicInteger()
        val request = loginRequest(timeoutMillis = 100)
        val challenge = CloudflareChallenge(request, nanoTime = nowNanos::get)
        val manager = CloudflareChallengeManager(
            browserAdapter = BrowserLoginAdapter {
                BrowserOpenResult.Opened(
                    object : BrowserLoginSession {
                        override suspend fun awaitResult(): BrowserLoginResult {
                            nowNanos.set(TimeUnit.MILLISECONDS.toNanos(101))
                            return BrowserLoginResult.Completed(authenticatedSession("too-late"))
                        }

                        override fun cancel() = Unit
                    },
                )
            },
            committer = AuthenticatedSessionCommitter { _, _ -> commitCount.incrementAndGet() },
        )

        val state = manager.recover(challenge, ChallengeRecoveryIntent.OpenBrowser)

        assertEquals(ChallengeRecoveryState.TimedOut, state)
        assertEquals(ChallengeRecoveryTerminal.TimedOut, challenge.terminal)
        assertEquals(ChallengeRecoveryState.TimedOut, challenge.state.value)
        assertEquals(0, commitCount.get())
    }

    @Test
    fun `cancel before atomic action registration prevents solver start and state regression`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(solvedResponse("example.com", "must-not-run"))
            val manager = CloudflareChallengeManager(
                committer = RecordingCommitter(),
                flareSolverrClient = FlareSolverrClient(
                    server.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
            )
            val challenge = manager.publish(loginRequest())
            challenge.actionMutex.lock()
            val recovery = async(Dispatchers.IO) {
                manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr)
            }

            assertEquals(
                ChallengeRecoveryState.Cancelled,
                manager.recover(challenge, ChallengeRecoveryIntent.Cancel),
            )
            assertEquals(
                ChallengeRecoveryState.Cancelled,
                challenge.beginAction(
                    Job(),
                    ChallengeRecoveryState.Running(ChallengeRecoveryAction.FlareSolverr),
                ),
                "an action registration that loses the terminal race must not start",
            )
            challenge.actionMutex.unlock()

            assertEquals(ChallengeRecoveryState.Cancelled, recovery.await())
            assertEquals(ChallengeRecoveryTerminal.Cancelled, challenge.terminal)
            assertEquals(ChallengeRecoveryState.Cancelled, challenge.state.value)
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `slow solver timeout cancels recovery and cleans the action`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse.Builder()
                    .headersDelay(500, TimeUnit.MILLISECONDS)
                    .code(500)
                    .body("server-error")
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
            val challenge = manager.publish(loginRequest(timeoutMillis = 200))

            val recovery = async(Dispatchers.IO) {
                manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr)
            }
            server.takeRequest(5, TimeUnit.SECONDS) ?: error("solver request was not sent")
            withTimeout(5_000) { recovery.join() }

            assertTrue(recovery.isCancelled)
            assertEquals(ChallengeRecoveryTerminal.TimedOut, challenge.terminal)
            assertEquals(ChallengeRecoveryState.TimedOut, challenge.state.value)
            assertEquals(0, commits.sessions.size)
            assertEquals(
                ChallengeRecoveryState.TimedOut,
                manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr),
            )
            assertEquals(1, server.requestCount, "a late or duplicate action must not start another solver call")
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
    fun `retry cannot reset recovered cancelled or timed out terminals`() = runTest {
        val manager = CloudflareChallengeManager(committer = RecordingCommitter())
        val recovered = manager.publish(loginRequest())
        manager.recover(
            recovered,
            ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("recovered-clearance")),
        )
        assertInstanceOf(
            ChallengeRecoveryState.Recovered::class.java,
            manager.recover(recovered, ChallengeRecoveryIntent.Retry),
        )
        assertEquals(ChallengeRecoveryTerminal.Recovered, recovered.terminal)

        val cancelled = manager.publish(loginRequest())
        manager.recover(cancelled, ChallengeRecoveryIntent.Cancel)
        assertEquals(ChallengeRecoveryState.Cancelled, manager.recover(cancelled, ChallengeRecoveryIntent.Retry))
        assertEquals(ChallengeRecoveryTerminal.Cancelled, cancelled.terminal)

        val timedOut = manager.publish(loginRequest())
        timedOut.completeFromActiveAction(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
        assertEquals(ChallengeRecoveryState.TimedOut, manager.recover(timedOut, ChallengeRecoveryIntent.Retry))
        assertEquals(ChallengeRecoveryTerminal.TimedOut, timedOut.terminal)
    }

    @Test
    fun `manual jar replacement between application interception and bridge never pairs old user agent with new clearance`(
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        val replacementCommitted = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val blockReplacement = AtomicBoolean()
        try {
            sourceServer.enqueue(MockResponse(code = 200, body = "ok"))
            solverServer.enqueue(
                solvedResponse(sourceServer.url("/").host, "old-clearance", secure = false, userAgent = "old-agent"),
            )
            val manager = productionManager(
                jarRef = jarRef,
                solverClient = FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
                afterCommit = { _, _ ->
                    if (blockReplacement.compareAndSet(true, false)) {
                        replacementCommitted.countDown()
                        check(releaseReplacement.await(5, TimeUnit.SECONDS))
                    }
                },
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("manual-cache").toFile(),
                cookieStorageFile = tempDir.resolve("manual-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                manager.recover(
                    manager.publish(loginRequest(url = sourceServer.url("/seed").toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )
                val beforeBridge = CountDownLatch(1)
                val releaseBridge = CountDownLatch(1)
                val request = executeAsync(barrierClient(helper.client, beforeBridge, releaseBridge), sourceServer)
                check(beforeBridge.await(5, TimeUnit.SECONDS))

                blockReplacement.set(true)
                val recovery = async(Dispatchers.Default) {
                    manager.recover(
                        manager.publish(loginRequest(url = sourceServer.url("/chapter").toString())),
                        ChallengeRecoveryIntent.SubmitManualCookies(
                            authenticatedSession(
                                "manual-clearance",
                                domain = sourceServer.url("/").host,
                                secure = false,
                            ),
                        ),
                    )
                }
                check(replacementCommitted.await(5, TimeUnit.SECONDS))
                releaseBridge.countDown()

                assertEquals(200, request.get(5, TimeUnit.SECONDS))
                val observed = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing source request")
                assertTrue(observed.headers["Cookie"]?.contains("manual-clearance") == true)
                assertTrue(observed.headers["User-Agent"] != "old-agent")

                releaseReplacement.countDown()
                assertInstanceOf(ChallengeRecoveryState.Recovered::class.java, recovery.await())
            }
        } finally {
            releaseReplacement.countDown()
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `jar rotation after application lookup but before bridge never emits stale paired credentials`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        try {
            sourceServer.enqueue(MockResponse(code = 200, body = "ok"))
            solverServer.enqueue(
                solvedResponse(sourceServer.url("/").host, "old-clearance", secure = false, userAgent = "old-agent"),
            )
            val manager = productionManager(
                jarRef,
                FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("rotation-cache").toFile(),
                cookieStorageFile = tempDir.resolve("rotation-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                manager.recover(
                    manager.publish(loginRequest(url = sourceServer.url("/seed").toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )
                val beforeBridge = CountDownLatch(1)
                val releaseBridge = CountDownLatch(1)
                val request = executeAsync(barrierClient(helper.client, beforeBridge, releaseBridge), sourceServer)
                check(beforeBridge.await(5, TimeUnit.SECONDS))
                helper.cookieJar.commitAuthenticatedSession(
                    sourceServer.url("/chapter"),
                    listOf(clearanceCookie(sourceServer.url("/").host, "rotated-clearance")),
                )
                releaseBridge.countDown()

                assertEquals(200, request.get(5, TimeUnit.SECONDS))
                val observed = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing source request")
                assertTrue(observed.headers["Cookie"]?.contains("rotated-clearance") == true)
                assertTrue(observed.headers["User-Agent"] != "old-agent")
            }
        } finally {
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `solver transition suppresses old user agent until the new binding is published`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        val newJarCommitted = CountDownLatch(1)
        val releaseNewBinding = CountDownLatch(1)
        val commitOrdinal = AtomicInteger()
        try {
            sourceServer.enqueue(MockResponse(code = 200, body = "transition"))
            sourceServer.enqueue(MockResponse(code = 200, body = "published"))
            solverServer.enqueue(
                solvedResponse(sourceServer.url("/").host, "old-clearance", secure = false, userAgent = "old-agent"),
            )
            solverServer.enqueue(
                solvedResponse(sourceServer.url("/").host, "new-clearance", secure = false, userAgent = "new-agent"),
            )
            val manager = productionManager(
                jarRef = jarRef,
                solverClient = FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
                afterCommit = { _, _ ->
                    if (commitOrdinal.incrementAndGet() == 2) {
                        newJarCommitted.countDown()
                        check(releaseNewBinding.await(5, TimeUnit.SECONDS))
                    }
                },
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("solver-transition-cache").toFile(),
                cookieStorageFile = tempDir.resolve("solver-transition-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                manager.recover(
                    manager.publish(loginRequest(url = sourceServer.url("/seed").toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )
                val beforeBridge = CountDownLatch(1)
                val releaseBridge = CountDownLatch(1)
                val transitionRequest = executeAsync(
                    barrierClient(helper.client, beforeBridge, releaseBridge),
                    sourceServer,
                )
                check(beforeBridge.await(5, TimeUnit.SECONDS))
                val recovery = async(Dispatchers.Default) {
                    manager.recover(
                        manager.publish(loginRequest(url = sourceServer.url("/chapter").toString())),
                        ChallengeRecoveryIntent.UseFlareSolverr,
                    )
                }
                check(newJarCommitted.await(5, TimeUnit.SECONDS))
                releaseBridge.countDown()

                assertEquals(200, transitionRequest.get(5, TimeUnit.SECONDS))
                val transition = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing transition request")
                assertTrue(transition.headers["Cookie"]?.contains("new-clearance") == true)
                assertTrue(transition.headers["User-Agent"] != "old-agent")

                releaseNewBinding.countDown()
                assertInstanceOf(ChallengeRecoveryState.Recovered::class.java, recovery.await())
                helper.client.newCall(Request.Builder().url(sourceServer.url("/published")).build()).execute().close()
                val published = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing published request")
                assertTrue(published.headers["Cookie"]?.contains("new-clearance") == true)
                assertEquals("new-agent", published.headers["User-Agent"])
            }
        } finally {
            releaseNewBinding.countDown()
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `network set cookie rotation cannot race a following request into stale credentials`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        try {
            sourceServer.enqueue(
                MockResponse(
                    code = 200,
                    headers = Headers.headersOf("Set-Cookie", "cf_clearance=network-clearance; Path=/"),
                    body = "rotated",
                ),
            )
            sourceServer.enqueue(MockResponse(code = 200, body = "following"))
            solverServer.enqueue(
                solvedResponse(sourceServer.url("/").host, "old-clearance", secure = false, userAgent = "old-agent"),
            )
            val manager = productionManager(
                jarRef,
                FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("set-cookie-cache").toFile(),
                cookieStorageFile = tempDir.resolve("set-cookie-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                manager.recover(
                    manager.publish(loginRequest(url = sourceServer.url("/seed").toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )
                val beforeBridge = CountDownLatch(1)
                val releaseBridge = CountDownLatch(1)
                val following = executeAsync(barrierClient(helper.client, beforeBridge, releaseBridge), sourceServer)
                check(beforeBridge.await(5, TimeUnit.SECONDS))

                helper.client.newCall(Request.Builder().url(sourceServer.url("/rotate")).build()).execute().close()
                releaseBridge.countDown()

                assertEquals(200, following.get(5, TimeUnit.SECONDS))
                val rotated = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing rotation request")
                val observed = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing following request")
                assertEquals("old-agent", rotated.headers["User-Agent"])
                assertTrue(observed.headers["Cookie"]?.contains("network-clearance") == true)
                assertTrue(observed.headers["User-Agent"] != "old-agent")
            }
        } finally {
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `desktop network helper wires solver credentials at the final outbound request stage`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        try {
            sourceServer.enqueue(MockResponse(code = 200, body = "ok"))
            solverServer.enqueue(
                solvedResponse(sourceServer.url("/").host, "paired-clearance", secure = false, userAgent = "paired-agent"),
            )
            val manager = productionManager(
                jarRef,
                FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("wiring-cache").toFile(),
                cookieStorageFile = tempDir.resolve("wiring-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                manager.recover(
                    manager.publish(loginRequest(url = sourceServer.url("/seed").toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )

                helper.client.newCall(Request.Builder().url(sourceServer.url("/chapter")).build()).execute().close()

                val observed = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing source request")
                assertEquals("paired-agent", observed.headers["User-Agent"])
                assertTrue(observed.headers["Cookie"]?.contains("paired-clearance") == true)
            }
        } finally {
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `desktop network helper publishes a challenge and retries once after explicit recovery`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        try {
            sourceServer.enqueue(cloudflareChallenge(503))
            sourceServer.enqueue(MockResponse(code = 200, body = "recovered"))
            val manager = CloudflareChallengeManager(
                committer = AuthenticatedSessionCommitter { request, session ->
                    DesktopAuthenticatedSessionCommitter(jarRef.get()).commit(request, session)
                },
                authenticatedCookieLookup = AuthenticatedCookieLookup { url ->
                    desktopCookieLookup(jarRef.get()).loadForRequest(url)
                },
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("challenge-wiring-cache").toFile(),
                cookieStorageFile = tempDir.resolve("challenge-wiring-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                val call = executeAsync(helper.client, sourceServer)
                val challenge = awaitChallenge(manager)

                assertInstanceOf(
                    ChallengeRecoveryState.Recovered::class.java,
                    manager.recover(
                        challenge,
                        ChallengeRecoveryIntent.SubmitManualCookies(
                            authenticatedSession(
                                value = "manual-clearance",
                                domain = sourceServer.url("/").host,
                                secure = false,
                            ),
                        ),
                    ),
                )

                assertEquals(200, call.get(5, TimeUnit.SECONDS))
                val requests = List(2) {
                    sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing source request")
                }
                assertNull(requests[0].headers["Cookie"])
                assertTrue(requests[1].headers["Cookie"]?.contains("manual-clearance") == true)
                assertEquals(2, sourceServer.requestCount)
            }
        } finally {
            sourceServer.close()
        }
    }

    @Test
    fun `caller cookie matching a cleared jar binding never revives the solver user agent`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        try {
            sourceServer.enqueue(MockResponse(code = 200, body = "explicit"))
            solverServer.enqueue(
                solvedResponse(sourceServer.url("/").host, "old-clearance", secure = false, userAgent = "old-agent"),
            )
            val manager = productionManager(
                jarRef,
                FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("matching-explicit-cache").toFile(),
                cookieStorageFile = tempDir.resolve("matching-explicit-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                manager.recover(
                    manager.publish(loginRequest(url = sourceServer.url("/seed").toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )
                helper.cookieJar.clear()

                helper.client.newCall(
                    Request.Builder()
                        .url(sourceServer.url("/explicit"))
                        .header("Cookie", "cf_clearance=old-clearance")
                        .header("User-Agent", "caller-agent")
                        .build(),
                ).execute().close()

                val observed = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing explicit request")
                assertEquals("cf_clearance=old-clearance", observed.headers["Cookie"])
                assertEquals("caller-agent", observed.headers["User-Agent"])
            }
        } finally {
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `caller cookie mismatch preserves the jar backed binding for the next normal request`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        try {
            sourceServer.enqueue(MockResponse(code = 200, body = "explicit"))
            sourceServer.enqueue(MockResponse(code = 200, body = "normal"))
            solverServer.enqueue(
                solvedResponse(sourceServer.url("/").host, "bound-clearance", secure = false, userAgent = "bound-agent"),
            )
            val manager = productionManager(
                jarRef,
                FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("mismatching-explicit-cache").toFile(),
                cookieStorageFile = tempDir.resolve("mismatching-explicit-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                manager.recover(
                    manager.publish(loginRequest(url = sourceServer.url("/seed").toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )

                helper.client.newCall(
                    Request.Builder()
                        .url(sourceServer.url("/explicit"))
                        .header("Cookie", "cf_clearance=caller-clearance")
                        .header("User-Agent", "caller-agent")
                        .build(),
                ).execute().close()
                helper.client.newCall(
                    Request.Builder()
                        .url(sourceServer.url("/normal"))
                        .header("User-Agent", "default-agent")
                        .build(),
                ).execute().close()

                val explicit = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing explicit request")
                val normal = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing normal request")
                assertEquals("cf_clearance=caller-clearance", explicit.headers["Cookie"])
                assertEquals("caller-agent", explicit.headers["User-Agent"])
                assertTrue(normal.headers["Cookie"]?.contains("bound-clearance") == true)
                assertEquals("bound-agent", normal.headers["User-Agent"])
            }
        } finally {
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `outbound cookie parser rejects non Bridge shapes without pruning the binding`() = runBlocking {
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(solvedResponse("example.com", "strict=value==", userAgent = "strict-agent"))
            val backingCookie = authenticatedCookie(value = "strict=value==")
            val manager = CloudflareChallengeManager(
                committer = RecordingCommitter(),
                flareSolverrClient = FlareSolverrClient(
                    solverServer.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
                authenticatedCookieLookup = AuthenticatedCookieLookup { listOf(backingCookie) },
            )
            val url = "https://example.com/chapter".toHttpUrl()
            manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.UseFlareSolverr)
            val malformedHeaders = listOf(
                listOf("cf_clearance =strict=value=="),
                listOf("cf_clearance= strict=value=="),
                listOf("\tcf_clearance=strict=value=="),
                listOf("cf_clearance=strict=value==;other=value"),
                listOf("cf_clearance=strict=value==; ; other=value"),
                listOf("cf_clearance=strict=value==; bad@name=value"),
                listOf("cf_clearance=strict=value==; other=bad value"),
                listOf("cf_clearance=strict=value==", "other=value"),
                listOf("cf_clearance=strict=value==; cf_clearance=strict=value=="),
            )

            malformedHeaders.forEach { headers ->
                assertNull(manager.solverUserAgentForOutboundRequest(url, headers), "malformed header was accepted")
                assertEquals("strict-agent", manager.solverUserAgentFor(url), "malformed header pruned the binding")
            }
            assertEquals(
                "strict-agent",
                manager.solverUserAgentForOutboundRequest(url, listOf("cf_clearance=strict=value==")),
            )
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `explicit or missing outbound clearance never receives a stale solver user agent`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        try {
            sourceServer.enqueue(MockResponse(code = 200, body = "explicit"))
            sourceServer.enqueue(MockResponse(code = 200, body = "missing"))
            solverServer.enqueue(
                solvedResponse(sourceServer.url("/").host, "old-clearance", secure = false, userAgent = "old-agent"),
            )
            val manager = productionManager(
                jarRef,
                FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("explicit-cache").toFile(),
                cookieStorageFile = tempDir.resolve("explicit-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                manager.recover(
                    manager.publish(loginRequest(url = sourceServer.url("/seed").toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )
                val explicitEntered = CountDownLatch(1)
                val releaseExplicit = CountDownLatch(1)
                val explicit = CompletableFuture.supplyAsync {
                    barrierClient(helper.client, explicitEntered, releaseExplicit).newCall(
                        Request.Builder()
                            .url(sourceServer.url("/explicit"))
                            .header("Cookie", "cf_clearance=explicit-clearance")
                            .build(),
                    ).execute().use { it.code }
                }
                check(explicitEntered.await(5, TimeUnit.SECONDS))
                helper.cookieJar.clear()
                releaseExplicit.countDown()
                assertEquals(200, explicit.get(5, TimeUnit.SECONDS))

                helper.client.newCall(Request.Builder().url(sourceServer.url("/missing")).build()).execute().close()

                val explicitObserved = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing explicit request")
                val missingObserved = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing cookie-free request")
                assertTrue(explicitObserved.headers["Cookie"]?.contains("explicit-clearance") == true)
                assertTrue(explicitObserved.headers["User-Agent"] != "old-agent")
                assertNull(missingObserved.headers["Cookie"])
                assertTrue(missingObserved.headers["User-Agent"] != "old-agent")
            }
        } finally {
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `duplicate path clearances reject a mixed outbound snapshot while retaining the valid binding`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val jarRef = AtomicReference<DesktopCookieJar>()
        try {
            sourceServer.enqueue(MockResponse(code = 200, body = "mixed"))
            solverServer.enqueue(
                MockResponse(
                    body = """{"status":"ok","solution":{"userAgent":"bound-agent","cookies":[{"name":"cf_clearance","value":"root-secret","domain":"${sourceServer.url("/").host}","path":"/","secure":false},{"name":"cf_clearance","value":"reader-secret","domain":"${sourceServer.url("/").host}","path":"/reader","secure":false}]}}""",
                ),
            )
            val manager = productionManager(
                jarRef,
                FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
            )
            DesktopNetworkHelper(
                cacheDir = tempDir.resolve("duplicate-cache").toFile(),
                cookieStorageFile = tempDir.resolve("duplicate-cookies.json").toFile(),
                challengeManager = manager,
            ).use { helper ->
                jarRef.set(helper.cookieJar)
                val readerUrl = sourceServer.url("/reader/chapter")
                manager.recover(
                    manager.publish(loginRequest(url = readerUrl.toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )
                val beforeBridge = CountDownLatch(1)
                val releaseBridge = CountDownLatch(1)
                val request = CompletableFuture.supplyAsync {
                    barrierClient(helper.client, beforeBridge, releaseBridge).newCall(
                        Request.Builder().url(readerUrl).build(),
                    ).execute().use { it.code }
                }
                check(beforeBridge.await(5, TimeUnit.SECONDS))
                helper.cookieJar.commitAuthenticatedSession(
                    readerUrl,
                    listOf(
                        clearanceCookie(sourceServer.url("/").host, "replacement-root"),
                        clearanceCookie(sourceServer.url("/").host, "reader-secret", path = "/reader"),
                    ),
                )
                releaseBridge.countDown()

                assertEquals(200, request.get(5, TimeUnit.SECONDS))
                val observed = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing mixed request")
                assertTrue(observed.headers["Cookie"]?.contains("replacement-root") == true)
                assertTrue(observed.headers["Cookie"]?.contains("reader-secret") == true)
                assertTrue(observed.headers["User-Agent"] != "bound-agent")
                assertEquals("bound-agent", manager.solverUserAgentFor(readerUrl))
            }
        } finally {
            solverServer.close()
            sourceServer.close()
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
                .addInterceptor(DesktopCloudflareInterceptor(manager))
                .addNetworkInterceptor(DesktopCloudflareCredentialInterceptor(manager))
                .build()
            val call = executeAsync(client, server)
            val challenge = awaitChallenge(manager)

            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(
                        authenticatedSession("new-clearance", domain = server.url("/").host, secure = false),
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
    fun `solver user agent is used for the unique retry and later same-host requests only`() {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        val unrelatedServer = MockWebServer().also { it.start() }
        try {
            sourceServer.enqueue(cloudflareChallenge(503))
            sourceServer.enqueue(MockResponse(code = 200, body = "recovered"))
            sourceServer.enqueue(MockResponse(code = 200, body = "same-host"))
            unrelatedServer.enqueue(MockResponse(code = 200, body = "unrelated-host"))
            solverServer.enqueue(
                solvedResponse(
                    domain = sourceServer.url("/").host,
                    value = "solver-clearance",
                    secure = false,
                    userAgent = "solver-agent",
                ),
            )
            val jar = DesktopCookieJar()
            val directClient = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .build()
            val manager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(
                    solverServer.url("/").toString().removeSuffix("/"),
                    directClient,
                ),
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )
            val client = directClient.newBuilder()
                .cookieJar(jar)
                .addInterceptor(DesktopCloudflareInterceptor(manager))
                .addNetworkInterceptor(DesktopCloudflareCredentialInterceptor(manager))
                .build()
            val firstCall = CompletableFuture.supplyAsync {
                client.newCall(
                    Request.Builder()
                        .url(sourceServer.url("/chapter"))
                        .header("User-Agent", "default-agent")
                        .build(),
                ).execute().use { it.code }
            }
            val challenge = awaitChallenge(manager)

            runBlocking { manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr) }

            assertEquals(200, firstCall.get(5, TimeUnit.SECONDS))
            client.newCall(
                Request.Builder()
                    .url(sourceServer.url("/next"))
                    .header("User-Agent", "default-agent")
                    .build(),
            ).execute().close()
            client.newCall(
                Request.Builder()
                    .url(unrelatedServer.url("/other").newBuilder().host("127.0.0.1").build())
                    .header("User-Agent", "default-agent")
                    .build(),
            ).execute().close()

            val observed = List(3) { sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing source request") }
            val unrelated = unrelatedServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing unrelated request")
            assertEquals("default-agent", observed[0].headers["User-Agent"])
            assertEquals("solver-agent", observed[1].headers["User-Agent"])
            assertEquals("solver-agent", observed[2].headers["User-Agent"])
            assertEquals("default-agent", unrelated.headers["User-Agent"])
            assertEquals(3, sourceServer.requestCount)
            assertEquals(1, unrelatedServer.requestCount)
            assertEquals(1, solverServer.requestCount)
        } finally {
            unrelatedServer.close()
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `solver user agent binding expires with its clearance cookie`() = runTest {
        val nowMillis = AtomicLong(System.currentTimeMillis())
        val solverServer = MockWebServer().also { it.start() }
        try {
            val expiresAt = nowMillis.get() + TimeUnit.MINUTES.toMillis(1)
            solverServer.enqueue(
                solvedResponse(
                    domain = "example.com",
                    value = "solver-clearance",
                    expires = expiresAt / 1_000.0,
                    userAgent = "solver-agent",
                ),
            )
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(
                    solverServer.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
                currentTimeMillis = nowMillis::get,
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )
            val challenge = manager.publish(loginRequest())

            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(challenge, ChallengeRecoveryIntent.UseFlareSolverr),
            )
            assertEquals("solver-agent", manager.solverUserAgentFor(loginRequest().url))

            nowMillis.set(expiresAt)
            assertNull(manager.solverUserAgentFor(loginRequest().url))
            assertNull(manager.solverUserAgentFor(loginRequest().url), "expired binding must stay evicted")
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `successful manual and browser sessions clear the previous solver user agent binding`() = runTest {
        val solverServer = MockWebServer().also { it.start() }
        try {
            repeat(2) {
                solverServer.enqueue(
                    solvedResponse(
                        domain = "example.com",
                        value = "solver-clearance-$it",
                        userAgent = "solver-agent-$it",
                    ),
                )
            }
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(
                browserAdapter = BrowserLoginAdapter {
                    BrowserOpenResult.Opened(completedBrowserSession(authenticatedSession("browser-clearance")))
                },
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(
                    solverServer.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )

            manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.UseFlareSolverr)
            assertEquals("solver-agent-0", manager.solverUserAgentFor(loginRequest().url))
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(
                    manager.publish(loginRequest()),
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("manual-clearance")),
                ),
            )
            assertNull(manager.solverUserAgentFor(loginRequest().url))

            manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.UseFlareSolverr)
            assertEquals("solver-agent-1", manager.solverUserAgentFor(loginRequest().url))
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.OpenBrowser),
            )
            assertNull(manager.solverUserAgentFor(loginRequest().url))
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `solver browser and manual failures preserve the previous clearance and user agent binding`(
        @TempDir tempDir: Path,
    ) = runTest {
        val solverServer = MockWebServer().also { it.start() }
        try {
            val futureExpiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
            solverServer.enqueue(
                solvedResponse(
                    domain = "example.com",
                    value = "stable-clearance",
                    expires = futureExpiry / 1_000.0,
                    userAgent = "stable-agent",
                ),
            )
            solverServer.enqueue(MockResponse(code = 500, body = "solver failed"))
            val persistenceAttempts = AtomicInteger()
            val jar = DesktopCookieJar(tempDir.resolve("cookies.json").toFile()) { source, target ->
                if (persistenceAttempts.incrementAndGet() == 2) {
                    throw IOException("replacement rolled back")
                }
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            val manager = CloudflareChallengeManager(
                browserAdapter = BrowserLoginAdapter {
                    BrowserOpenResult.Opened(
                        object : BrowserLoginSession {
                            override suspend fun awaitResult(): BrowserLoginResult = BrowserLoginResult.Cancelled
                            override fun cancel() = Unit
                        },
                    )
                },
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(
                    solverServer.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )
            val url = loginRequest().url

            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.UseFlareSolverr),
            )
            assertEquals("stable-agent", manager.solverUserAgentFor(url))

            assertEquals(
                ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.SolverFailed),
                manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.UseFlareSolverr),
            )
            assertEquals(ChallengeRecoveryState.Cancelled, manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.OpenBrowser))
            assertEquals(
                ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
                manager.recover(
                    manager.publish(loginRequest()),
                    ChallengeRecoveryIntent.SubmitManualCookies(
                        authenticatedSession("rolled-back-clearance", expiresAt = futureExpiry),
                    ),
                ),
            )

            assertEquals("stable-agent", manager.solverUserAgentFor(url))
            assertEquals(
                "stable-clearance",
                jar.loadForRequest(url).single { it.name == CF_CLEARANCE_COOKIE_NAME }.value,
            )
            assertEquals(2, persistenceAttempts.get())
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `same-host concurrent solver commits serialize cookie and user agent replacement`() = runBlocking {
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(solvedResponse("example.com", "clearance-first", userAgent = "agent-first"))
            solverServer.enqueue(solvedResponse("example.com", "clearance-second", userAgent = "agent-second"))
            val firstCommitEntered = CountDownLatch(1)
            val releaseFirstCommit = CountDownLatch(1)
            val secondCommitEntered = CountDownLatch(1)
            val commitOrdinal = AtomicInteger()
            val jar = DesktopCookieJar()
            val delegate = DesktopAuthenticatedSessionCommitter(jar)
            val manager = CloudflareChallengeManager(
                committer = AuthenticatedSessionCommitter { request, session ->
                    if (commitOrdinal.incrementAndGet() == 1) {
                        firstCommitEntered.countDown()
                        check(releaseFirstCommit.await(5, TimeUnit.SECONDS))
                    } else {
                        secondCommitEntered.countDown()
                    }
                    delegate.commit(request, session)
                },
                flareSolverrClient = FlareSolverrClient(
                    solverServer.url("/").toString().removeSuffix("/"),
                    OkHttpClient(),
                ),
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )
            val firstChallenge = manager.publish(loginRequest())
            val secondChallenge = manager.publish(loginRequest())
            val first = async(Dispatchers.Default) {
                manager.recover(firstChallenge, ChallengeRecoveryIntent.UseFlareSolverr)
            }
            check(firstCommitEntered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) {
                manager.recover(secondChallenge, ChallengeRecoveryIntent.UseFlareSolverr)
            }

            assertFalse(
                secondCommitEntered.await(200, TimeUnit.MILLISECONDS),
                "same-host cookie commit and its UA binding must be one serialized replacement",
            )
            releaseFirstCommit.countDown()

            assertInstanceOf(ChallengeRecoveryState.Recovered::class.java, first.await())
            assertInstanceOf(ChallengeRecoveryState.Recovered::class.java, second.await())
            assertEquals("agent-second", manager.solverUserAgentFor(loginRequest().url))
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `shared normalization makes mixed required cookies order safe in the real persisted jar`(
        @TempDir tempDir: Path,
    ) = runTest {
        listOf(
            listOf("valid-clearance", ""),
            listOf("", "valid-clearance"),
        ).forEachIndexed { index, values ->
            val expiresAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
            val storage = tempDir.resolve("cookies-$index.json").toFile()
            val jar = DesktopCookieJar(storage)
            val manager = CloudflareChallengeManager(committer = DesktopAuthenticatedSessionCommitter(jar))
            val session = AuthenticatedSession(
                values.map { authenticatedCookie(value = it, domain = "EXAMPLE.COM", expiresAt = expiresAt) } +
                    authenticatedCookie(
                        value = "reader-clearance",
                        domain = ".EXAMPLE.COM",
                        expiresAt = expiresAt,
                        path = "/reader",
                    ),
            )

            val state = assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(
                    manager.publish(loginRequest(url = "https://example.com/reader/chapter")),
                    ChallengeRecoveryIntent.SubmitManualCookies(session),
                ),
            )

            assertEquals(2, state.cookieCount)
            assertEquals(setOf(CF_CLEARANCE_COOKIE_NAME), state.cookieNames)
            val persisted = DesktopCookieJar(storage).loadForRequest("https://example.com/reader/chapter".toHttpUrl())
            assertEquals(
                setOf(Triple("/", "valid-clearance", "example.com"), Triple("/reader", "reader-clearance", "example.com")),
                persisted.map { Triple(it.path, it.value, it.domain) }.toSet(),
            )
        }
    }

    @Test
    fun `solver user agent lookup requires any still matching credential in the actual jar`() = runTest {
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(
                MockResponse(
                    body = """{"status":"ok","solution":{"userAgent":"bound-agent","cookies":[{"name":"cf_clearance","value":"root-secret","domain":"example.com","path":"/"},{"name":"cf_clearance","value":"reader-secret","domain":"example.com","path":"/reader"}]}}""",
                ),
            )
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )
            val readerUrl = "https://example.com/reader/chapter".toHttpUrl()
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(manager.publish(loginRequest(url = readerUrl.toString())), ChallengeRecoveryIntent.UseFlareSolverr),
            )
            assertEquals("bound-agent", manager.solverUserAgentFor(readerUrl))

            jar.commitAuthenticatedSession(
                readerUrl,
                listOf(
                    Cookie.Builder().name(CF_CLEARANCE_COOKIE_NAME).value("reader-secret")
                        .hostOnlyDomain("example.com").path("/reader").build(),
                ),
            )
            assertEquals("bound-agent", manager.solverUserAgentFor(readerUrl), "any still-bound identity keeps the UA valid")

            jar.commitAuthenticatedSession(
                readerUrl,
                listOf(
                    Cookie.Builder().name(CF_CLEARANCE_COOKIE_NAME).value("replacement")
                        .hostOnlyDomain("example.com").path("/reader").build(),
                ),
            )
            assertNull(manager.solverUserAgentFor(readerUrl))
            assertNull(manager.solverUserAgentFor(readerUrl), "a replaced credential must stay evicted")
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `catalog lookup preserves a reader scoped solver user agent binding`() = runTest {
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(solvedResponse("example.com", "reader-secret", path = "/reader"))
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )
            val readerUrl = "https://example.com/reader/chapter".toHttpUrl()
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(manager.publish(loginRequest(url = readerUrl.toString())), ChallengeRecoveryIntent.UseFlareSolverr),
            )

            assertNull(manager.solverUserAgentFor("https://example.com/catalog".toHttpUrl()))
            assertEquals("desktop-agent", manager.solverUserAgentFor(readerUrl))
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `replaced root credential does not evict a still valid reader credential`() = runTest {
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(
                MockResponse(
                    body = """{"status":"ok","solution":{"userAgent":"bound-agent","cookies":[{"name":"cf_clearance","value":"root-secret","domain":"example.com","path":"/"},{"name":"cf_clearance","value":"reader-secret","domain":"example.com","path":"/reader"}]}}""",
                ),
            )
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )
            val readerUrl = "https://example.com/reader/chapter".toHttpUrl()
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(manager.publish(loginRequest(url = readerUrl.toString())), ChallengeRecoveryIntent.UseFlareSolverr),
            )
            jar.commitAuthenticatedSession(
                readerUrl,
                listOf(
                    Cookie.Builder().name(CF_CLEARANCE_COOKIE_NAME).value("replacement-root")
                        .hostOnlyDomain("example.com").path("/").build(),
                    Cookie.Builder().name(CF_CLEARANCE_COOKIE_NAME).value("reader-secret")
                        .hostOnlyDomain("example.com").path("/reader").build(),
                ),
            )

            assertNull(manager.solverUserAgentFor("https://example.com/catalog".toHttpUrl()))
            assertEquals("bound-agent", manager.solverUserAgentFor(readerUrl))
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `last missing credential removes the binding instead of allowing later resurrection`() = runTest {
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(solvedResponse("example.com", "reader-secret", path = "/reader"))
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )
            val readerUrl = "https://example.com/reader/chapter".toHttpUrl()
            manager.recover(manager.publish(loginRequest(url = readerUrl.toString())), ChallengeRecoveryIntent.UseFlareSolverr)

            jar.commitAuthenticatedSession(readerUrl, emptyList())
            assertNull(manager.solverUserAgentFor(readerUrl))
            jar.commitAuthenticatedSession(
                readerUrl,
                listOf(
                    Cookie.Builder().name(CF_CLEARANCE_COOKIE_NAME).value("reader-secret")
                        .hostOnlyDomain("example.com").path("/reader").build(),
                ),
            )
            assertNull(manager.solverUserAgentFor(readerUrl))
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `jar validation racing a newer solver commit cannot evict the newer binding`() = runBlocking {
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(solvedResponse("example.com", "old-secret", userAgent = "old-agent"))
            solverServer.enqueue(solvedResponse("example.com", "new-secret", userAgent = "new-agent"))
            val jar = DesktopCookieJar()
            val blockNextLookup = AtomicBoolean()
            val lookupCaptured = CountDownLatch(1)
            val releaseLookup = CountDownLatch(1)
            val manager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
                authenticatedCookieLookup = AuthenticatedCookieLookup { url ->
                    val snapshot = desktopCookieLookup(jar).loadForRequest(url)
                    if (blockNextLookup.compareAndSet(true, false)) {
                        lookupCaptured.countDown()
                        check(releaseLookup.await(5, TimeUnit.SECONDS))
                    }
                    snapshot
                },
            )
            val url = loginRequest().url
            manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.UseFlareSolverr)
            assertEquals("old-agent", manager.solverUserAgentFor(url))

            blockNextLookup.set(true)
            val racingLookup = CompletableFuture.supplyAsync { manager.solverUserAgentFor(url) }
            check(lookupCaptured.await(5, TimeUnit.SECONDS))
            manager.recover(manager.publish(loginRequest()), ChallengeRecoveryIntent.UseFlareSolverr)
            releaseLookup.countDown()

            assertEquals("new-agent", racingLookup.get(5, TimeUnit.SECONDS))
            assertEquals("new-agent", manager.solverUserAgentFor(url))
        } finally {
            solverServer.close()
        }
    }

    @Test
    fun `manual recovery rebuilds the current source retry from the untouched original request`() {
        val sourceServer = MockWebServer().also { it.start() }
        val solverServer = MockWebServer().also { it.start() }
        try {
            solverServer.enqueue(
                solvedResponse(
                    sourceServer.url("/").host,
                    "solver-secret",
                    secure = false,
                    userAgent = "solver-agent",
                ),
            )
            sourceServer.enqueue(cloudflareChallenge(503))
            sourceServer.enqueue(MockResponse(code = 200, body = "recovered"))
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClient = FlareSolverrClient(solverServer.url("/").toString().removeSuffix("/"), OkHttpClient()),
                authenticatedCookieLookup = desktopCookieLookup(jar),
            )
            runBlocking {
                manager.recover(
                    manager.publish(loginRequest(url = sourceServer.url("/seed").toString())),
                    ChallengeRecoveryIntent.UseFlareSolverr,
                )
            }
            checkNotNull(manager.tryReceive())
            val client = OkHttpClient.Builder()
                .cookieJar(jar)
                .addInterceptor(DesktopCloudflareInterceptor(manager))
                .addNetworkInterceptor(DesktopCloudflareCredentialInterceptor(manager))
                .build()
            val call = CompletableFuture.supplyAsync {
                client.newCall(
                    Request.Builder().url(sourceServer.url("/chapter")).header("User-Agent", "original-agent").build(),
                ).execute().use { it.code }
            }
            val challenge = awaitChallenge(manager)
            runBlocking {
                manager.recover(
                    challenge,
                    ChallengeRecoveryIntent.SubmitManualCookies(
                        authenticatedSession("manual-secret", domain = sourceServer.url("/").host, secure = false),
                    ),
                )
            }

            assertEquals(200, call.get(5, TimeUnit.SECONDS))
            val first = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing first source request")
            val retry = sourceServer.takeRequest(5, TimeUnit.SECONDS) ?: error("missing retry source request")
            assertEquals("solver-agent", first.headers["User-Agent"])
            assertEquals("original-agent", retry.headers["User-Agent"])
        } finally {
            solverServer.close()
            sourceServer.close()
        }
    }

    @Test
    fun `retry creates an isolated full-timeout attempt while old waiter returns old failure`() = runBlocking {
        val nowNanos = AtomicLong()
        val oldWaiterPaused = CountDownLatch(1)
        val releaseOldWaiter = CountDownLatch(1)
        val challenge = CloudflareChallenge(
            loginRequest(timeoutMillis = 100),
            nanoTime = nowNanos::get,
            afterAttemptCompletionObserved = {
                oldWaiterPaused.countDown()
                check(releaseOldWaiter.await(5, TimeUnit.SECONDS))
            },
        )
        val attempts = AtomicInteger()
        val manager = CloudflareChallengeManager(
            committer = AuthenticatedSessionCommitter { _, _ ->
                if (attempts.incrementAndGet() == 1) {
                    nowNanos.set(TimeUnit.MILLISECONDS.toNanos(150))
                    error("first commit failed past the original deadline")
                }
            },
        )
        val oldWaiter = CompletableFuture.supplyAsync { challenge.awaitTerminal() }

        assertEquals(
            ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
            manager.recover(challenge, ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("first"))),
        )
        check(oldWaiterPaused.await(5, TimeUnit.SECONDS))
        assertEquals(ChallengeRecoveryState.AwaitingUserAction, manager.recover(challenge, ChallengeRecoveryIntent.Retry))
        assertInstanceOf(
            ChallengeRecoveryState.Recovered::class.java,
            manager.recover(challenge, ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("second"))),
        )
        releaseOldWaiter.countDown()

        assertEquals(ChallengeRecoveryTerminal.Failed, oldWaiter.get(5, TimeUnit.SECONDS))
        assertEquals(ChallengeRecoveryTerminal.Recovered, challenge.terminal)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `commit lock storage stays structurally bounded across arbitrary hosts`() = runTest {
        val manager = CloudflareChallengeManager(committer = RecordingCommitter())
        repeat(256) { index ->
            val host = "host-$index.example.com"
            assertInstanceOf(
                ChallengeRecoveryState.Recovered::class.java,
                manager.recover(
                    manager.publish(loginRequest(url = "https://$host/chapter")),
                    ChallengeRecoveryIntent.SubmitManualCookies(authenticatedSession("secret-$index", domain = host)),
                ),
            )
        }
        assertTrue(manager.commitLockCount <= 64, "striped commit locks must be bounded")
    }

    @Test
    fun `production publishing retains at most one challenge in the bounded polling bridge`() {
        val manager = CloudflareChallengeManager()
        repeat(1_024) { index ->
            manager.publish(loginRequest(url = "https://host-$index.example.com/chapter"))
        }

        var retained = 0
        while (manager.tryReceive() != null) retained++
        assertTrue(retained <= 1, "the polling bridge must not retain an unbounded history")
        assertTrue(manager.challenges.replayCache.isEmpty(), "SharedFlow must keep its production replay semantics")
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

    private fun barrierClient(
        client: OkHttpClient,
        entered: CountDownLatch,
        release: CountDownLatch,
    ): OkHttpClient = client.newBuilder()
        .addInterceptor(
            Interceptor { chain ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "request bridge barrier was not released" }
                chain.proceed(chain.request())
            },
        )
        .build()

    private fun productionManager(
        jarRef: AtomicReference<DesktopCookieJar>,
        solverClient: FlareSolverrClient,
        afterCommit: suspend (SourceLoginRequest, AuthenticatedSession) -> Unit = { _, _ -> },
    ) = CloudflareChallengeManager(
        committer = AuthenticatedSessionCommitter { request, session ->
            DesktopAuthenticatedSessionCommitter(jarRef.get()).commit(request, session)
            afterCommit(request, session)
        },
        flareSolverrClient = solverClient,
        authenticatedCookieLookup = AuthenticatedCookieLookup { url ->
            desktopCookieLookup(jarRef.get()).loadForRequest(url)
        },
    )

    private fun clearanceCookie(
        domain: String,
        value: String,
        path: String = "/",
    ) = Cookie.Builder()
        .name(CF_CLEARANCE_COOKIE_NAME)
        .value(value)
        .hostOnlyDomain(domain)
        .path(path)
        .build()

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
        expiresAt: Long? = null,
        secure: Boolean = true,
    ) = AuthenticatedSession(
        listOf(authenticatedCookie(value = value, domain = domain, expiresAt = expiresAt, secure = secure)),
    )

    private fun authenticatedCookie(
        name: String = CF_CLEARANCE_COOKIE_NAME,
        value: String,
        domain: String = "example.com",
        expiresAt: Long? = null,
        path: String = "/",
        secure: Boolean = true,
    ) = AuthenticatedCookie(
        name = name,
        value = value,
        domain = domain,
        hostOnly = true,
        path = path,
        expiresAt = expiresAt,
        secure = secure,
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
        userAgent: String = "desktop-agent",
    ) = MockResponse(body = solvedBody(domain, value, path, expires, secure, httpOnly, userAgent))

    private fun solvedBody(
        domain: String,
        value: String,
        path: String = "/",
        expires: Double? = null,
        secure: Boolean = true,
        httpOnly: Boolean = true,
        userAgent: String = "desktop-agent",
    ): String {
        val expiresField = expires?.let { "\"expires\":$it," }.orEmpty()
        return """{"status":"ok","solution":{"userAgent":"$userAgent","cookies":[{"name":"cf_clearance","value":"$value","domain":"$domain","path":"$path",$expiresField"secure":$secure,"httpOnly":$httpOnly}]}}"""
    }

    private class RecordingCommitter : AuthenticatedSessionCommitter {
        val sessions = mutableListOf<AuthenticatedSession>()

        override suspend fun commit(request: SourceLoginRequest, session: AuthenticatedSession) {
            sessions += session
        }
    }

    private fun desktopCookieLookup(jar: DesktopCookieJar) = AuthenticatedCookieLookup { url ->
        jar.loadForRequest(url).map { cookie ->
            AuthenticatedCookie(
                name = cookie.name,
                value = cookie.value,
                domain = cookie.domain,
                hostOnly = cookie.hostOnly,
                path = cookie.path,
                expiresAt = cookie.expiresAt.takeIf { cookie.persistent },
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
            )
        }
    }
}
