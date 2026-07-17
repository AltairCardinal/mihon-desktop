package tachiyomi.domain.source.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceLoginSessionTest {

    @Test
    fun `successful login validates and commits the complete session exactly once`() = runTest {
        val browserSession = TestBrowserSession()
        val commits = mutableListOf<AuthenticatedSession>()
        val login = SourceLoginSession(
            browserAdapter = BrowserLoginAdapter { BrowserOpenResult.Opened(browserSession) },
            committer = AuthenticatedSessionCommitter { _, session -> commits += session },
        )
        val request = request(required = setOf("session", "clearance"))
        val result = async { login.login(request) }
        runCurrent()

        browserSession.complete(
            AuthenticatedSession(
                listOf(
                    cookie("session", "session-secret", "reader.example.com"),
                    cookie("clearance", "clearance-secret", "example.com", hostOnly = false),
                ),
            ),
        )

        assertEquals(
            SourceLoginState.Authenticated(setOf("clearance", "session"), cookieCount = 2),
            result.await(),
        )
        assertEquals(1, commits.size)
        assertEquals(setOf("session", "clearance"), commits.single().cookies.map { it.name }.toSet())
    }

    @Test
    fun `missing required cookie rejects the whole session without commit`() = runTest {
        val commits = mutableListOf<AuthenticatedSession>()
        val browserSession = TestBrowserSession()
        val login = session(browserSession) { commits += it }
        val result = async { login.login(request(required = setOf("session", "clearance"))) }
        runCurrent()

        browserSession.complete(AuthenticatedSession(listOf(cookie("session", "secret", "reader.example.com"))))

        assertInstanceOf(SourceLoginState.InvalidCookies::class.java, result.await())
        assertTrue(commits.isEmpty())
    }

    @Test
    fun `required cookie with blank value rejects the whole session without exposing values`() = runTest {
        val commits = mutableListOf<AuthenticatedSession>()
        val browserSession = TestBrowserSession()
        val login = session(browserSession) { commits += it }
        val result = async { login.login(request(required = setOf("session", "clearance"))) }
        runCurrent()

        browserSession.complete(
            AuthenticatedSession(
                listOf(
                    cookie("session", "session-secret", "reader.example.com"),
                    cookie("clearance", "   ", "reader.example.com"),
                    cookie("optional-empty", "", "reader.example.com"),
                ),
            ),
        )

        val state = assertInstanceOf(SourceLoginState.InvalidCookies::class.java, result.await())
        assertEquals(emptySet<String>(), state.missingRequiredCookieNames)
        assertEquals(setOf("clearance"), state.rejectedCookieNames)
        assertTrue(commits.isEmpty())
        assertFalse(state.toString().contains("session-secret"))
        assertFalse(state.toString().contains("   "))
    }

    @Test
    fun `required cookie accepts one domain-valid nonblank candidate alongside blank duplicate`() = runTest {
        val commits = mutableListOf<AuthenticatedSession>()
        val browserSession = TestBrowserSession()
        val login = session(browserSession) { commits += it }
        val result = async { login.login(request(required = setOf("clearance"))) }
        runCurrent()

        browserSession.complete(
            AuthenticatedSession(
                listOf(
                    cookie("clearance", "", "reader.example.com"),
                    cookie("clearance", "valid-secret", "example.com", hostOnly = false),
                ),
            ),
        )

        assertInstanceOf(SourceLoginState.Authenticated::class.java, result.await())
        assertEquals(1, commits.size)
        assertEquals(listOf("valid-secret"), commits.single().cookies.map { it.value })
        assertEquals(SourceLoginState.Authenticated(setOf("clearance"), 1), login.state.value)
    }

    @Test
    fun `required cookie normalization is order safe and preserves distinct canonical identities`() = runTest {
        val orders = listOf(
            listOf("valid-secret", ""),
            listOf("", "valid-secret"),
        )

        orders.forEach { values ->
            val commits = mutableListOf<AuthenticatedSession>()
            val browserSession = TestBrowserSession()
            val login = session(browserSession) { commits += it }
            val result = async { login.login(request(required = setOf("clearance"))) }
            runCurrent()
            browserSession.complete(
                AuthenticatedSession(
                    values.map { cookie("clearance", it, "READER.EXAMPLE.COM") } +
                        cookie("clearance", "parent-secret", ".EXAMPLE.COM", hostOnly = false, path = "/reader"),
                ),
            )

            assertEquals(SourceLoginState.Authenticated(setOf("clearance"), 2), result.await())
            assertEquals(1, commits.size)
            assertEquals(
                setOf(
                    Triple("reader.example.com", "/", "valid-secret"),
                    Triple("example.com", "/reader", "parent-secret"),
                ),
                commits.single().cookies.map { Triple(it.domain, it.path, it.value) }.toSet(),
            )
        }
    }

    @Test
    fun `non-required blank cookie remains compatible`() = runTest {
        val commits = mutableListOf<AuthenticatedSession>()
        val browserSession = TestBrowserSession()
        val login = session(browserSession) { commits += it }
        val result = async { login.login(request(required = setOf("clearance"))) }
        runCurrent()

        browserSession.complete(
            AuthenticatedSession(
                listOf(
                    cookie("clearance", "valid-secret", "reader.example.com"),
                    cookie("optional", "", "reader.example.com"),
                ),
            ),
        )

        assertInstanceOf(SourceLoginState.Authenticated::class.java, result.await())
        assertEquals(1, commits.size)
    }

    @Test
    fun `invalid-domain nonblank candidate does not satisfy required cookie`() = runTest {
        val commits = mutableListOf<AuthenticatedSession>()
        val browserSession = TestBrowserSession()
        val login = session(browserSession) { commits += it }
        val result = async { login.login(request(required = setOf("clearance"))) }
        runCurrent()

        browserSession.complete(
            AuthenticatedSession(
                listOf(
                    cookie("clearance", "", "reader.example.com"),
                    cookie("clearance", "not-for-this-source", "other.test", hostOnly = false),
                ),
            ),
        )

        val state = assertInstanceOf(SourceLoginState.InvalidCookies::class.java, result.await())
        assertEquals(setOf("clearance"), state.rejectedCookieNames)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun `unrelated and child-domain cookies reject the whole session`() = runTest {
        val commits = mutableListOf<AuthenticatedSession>()
        val browserSession = TestBrowserSession()
        val login = session(browserSession) { commits += it }
        val result = async { login.login(request()) }
        runCurrent()

        browserSession.complete(
            AuthenticatedSession(
                listOf(
                    cookie("valid", "one", "reader.example.com"),
                    cookie("unrelated", "two", "other.test", hostOnly = false),
                    cookie("child", "three", "auth.reader.example.com", hostOnly = false),
                ),
            ),
        )

        val state = assertInstanceOf(SourceLoginState.InvalidCookies::class.java, result.await())
        assertEquals(setOf("child", "unrelated"), state.rejectedCookieNames)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun `parent-domain cookie is accepted while host-only parent cookie is rejected`() = runTest {
        val acceptedSession = TestBrowserSession()
        val acceptedCommits = mutableListOf<AuthenticatedSession>()
        val accepted = session(acceptedSession) { acceptedCommits += it }
        val acceptedResult = async { accepted.login(request()) }
        runCurrent()
        acceptedSession.complete(
            AuthenticatedSession(listOf(cookie("parent", "secret", "example.com", hostOnly = false))),
        )

        assertInstanceOf(SourceLoginState.Authenticated::class.java, acceptedResult.await())
        assertEquals(1, acceptedCommits.size)

        val rejectedSession = TestBrowserSession()
        val rejectedCommits = mutableListOf<AuthenticatedSession>()
        val rejected = session(rejectedSession) { rejectedCommits += it }
        val rejectedResult = async { rejected.login(request()) }
        runCurrent()
        rejectedSession.complete(
            AuthenticatedSession(listOf(cookie("parent", "secret", "example.com", hostOnly = true))),
        )

        assertInstanceOf(SourceLoginState.InvalidCookies::class.java, rejectedResult.await())
        assertTrue(rejectedCommits.isEmpty())
    }

    @Test
    fun `public suffix domain cookies reject the whole session without leaking values`() = runTest {
        val cases = listOf(
            Triple("https://reader.example.com/login", "com", "public-com"),
            Triple("https://reader.example.co.uk/login", "co.uk", "public-co-uk"),
        )

        cases.forEach { (requestUrl, publicSuffix, cookieName) ->
            val commits = mutableListOf<AuthenticatedSession>()
            val browserSession = TestBrowserSession()
            val login = session(browserSession) { commits += it }
            val request = SourceLoginRequest(
                url = requestUrl.toHttpUrl(),
                timeoutMillis = 30_000,
            )
            val result = async { login.login(request) }
            runCurrent()
            browserSession.complete(
                AuthenticatedSession(
                    listOf(cookie(cookieName, "secret-$cookieName", publicSuffix, hostOnly = false)),
                ),
            )

            val state = assertInstanceOf(SourceLoginState.InvalidCookies::class.java, result.await())
            assertEquals(setOf(cookieName), state.rejectedCookieNames)
            assertTrue(commits.isEmpty())
            assertFalse(state.toString().contains("secret-$cookieName"))
            assertFalse(login.state.value.toString().contains("secret-$cookieName"))
        }
    }

    @Test
    fun `host-only localhost and IP cookies remain valid while domain forms are rejected`() = runTest {
        listOf("localhost", "127.0.0.1").forEach { host ->
            val acceptedSession = TestBrowserSession()
            val acceptedCommits = mutableListOf<AuthenticatedSession>()
            val accepted = session(acceptedSession) { acceptedCommits += it }
            val acceptedResult = async {
                accepted.login(
                    SourceLoginRequest(
                        url = "http://$host/login".toHttpUrl(),
                        timeoutMillis = 30_000,
                    ),
                )
            }
            runCurrent()
            acceptedSession.complete(
                AuthenticatedSession(listOf(cookie("host-only", "secret", host, hostOnly = true))),
            )

            assertInstanceOf(SourceLoginState.Authenticated::class.java, acceptedResult.await())
            assertEquals(1, acceptedCommits.size)

            val rejectedSession = TestBrowserSession()
            val rejectedCommits = mutableListOf<AuthenticatedSession>()
            val rejected = session(rejectedSession) { rejectedCommits += it }
            val rejectedResult = async {
                rejected.login(
                    SourceLoginRequest(
                        url = "http://$host/login".toHttpUrl(),
                        timeoutMillis = 30_000,
                    ),
                )
            }
            runCurrent()
            rejectedSession.complete(
                AuthenticatedSession(listOf(cookie("domain", "secret", host, hostOnly = false))),
            )

            val state = assertInstanceOf(SourceLoginState.InvalidCookies::class.java, rejectedResult.await())
            assertEquals(setOf("domain"), state.rejectedCookieNames)
            assertTrue(rejectedCommits.isEmpty())
        }
    }

    @Test
    fun `cancelled login performs zero commits`() = runTest {
        val browserSession = TestBrowserSession()
        var commits = 0
        val result = async { session(browserSession) { commits += 1 }.login(request()) }
        runCurrent()

        browserSession.cancelFromUser()

        assertEquals(SourceLoginState.Cancelled, result.await())
        assertEquals(0, commits)
    }

    @Test
    fun `timeout under virtual time cancels browser session and performs zero commits`() = runTest {
        val browserSession = TestBrowserSession()
        var commits = 0
        val result = async {
            session(browserSession) { commits += 1 }.login(request(timeoutMillis = 1_000))
        }
        runCurrent()

        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(SourceLoginState.TimedOut, result.await())
        assertTrue(browserSession.cancelled)
        assertEquals(0, commits)
    }

    @Test
    fun `deadline stops at browser result and cannot report timeout after atomic commit starts`() = runTest {
        val browserSession = TestBrowserSession()
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var commits = 0
        val login = SourceLoginSession(
            browserAdapter = BrowserLoginAdapter { BrowserOpenResult.Opened(browserSession) },
            committer = AuthenticatedSessionCommitter { _, _ ->
                commitStarted.complete(Unit)
                releaseCommit.await()
                commits += 1
            },
        )
        val result = async { login.login(request(timeoutMillis = 1_000)) }
        runCurrent()
        browserSession.complete(AuthenticatedSession(listOf(cookie("session", "secret", "reader.example.com"))))
        commitStarted.await()

        advanceTimeBy(1_001)
        runCurrent()

        assertFalse(result.isCompleted, "an in-flight atomic commit must finish with its real outcome")
        assertFalse(login.state.value is SourceLoginState.TimedOut)
        releaseCommit.complete(Unit)
        assertInstanceOf(SourceLoginState.Authenticated::class.java, result.await())
        assertEquals(1, commits)
    }

    @Test
    fun `browser unavailable performs zero commits`() = runTest {
        var commits = 0
        val login = SourceLoginSession(
            browserAdapter = BrowserLoginAdapter { BrowserOpenResult.Unavailable },
            committer = AuthenticatedSessionCommitter { _, _ -> commits += 1 },
        )

        assertEquals(SourceLoginState.BrowserUnavailable, login.login(request()))
        assertEquals(0, commits)
    }

    @Test
    fun `commit failure is recoverable and does not expose cookie values`() = runTest {
        val browserSession = TestBrowserSession()
        val login = SourceLoginSession(
            browserAdapter = BrowserLoginAdapter { BrowserOpenResult.Opened(browserSession) },
            committer = AuthenticatedSessionCommitter { _, _ -> error("disk failed for secret-value") },
        )
        val result = async { login.login(request()) }
        runCurrent()
        val authenticated = AuthenticatedSession(listOf(cookie("session", "secret-value", "reader.example.com")))

        browserSession.complete(authenticated)

        assertEquals(SourceLoginState.CommitFailed, result.await())
        assertFalse(authenticated.toString().contains("secret-value"))
        assertFalse(login.state.value.toString().contains("secret-value"))
        assertTrue(login.state.value.toString().contains("CommitFailed"))
    }

    @Test
    fun `caller cancellation while opening publishes cancelled`() = runTest {
        val openStarted = CompletableDeferred<Unit>()
        val login = SourceLoginSession(
            browserAdapter = BrowserLoginAdapter {
                openStarted.complete(Unit)
                awaitCancellation()
            },
            committer = AuthenticatedSessionCommitter { _, _ -> error("must not commit") },
        )
        val result = async { login.login(request()) }
        openStarted.await()

        result.cancel()
        runCurrent()

        assertEquals(SourceLoginState.Cancelled, login.state.value)
    }

    @Test
    fun `caller cancellation while awaiting cookies publishes cancelled and rejects late completion`() = runTest {
        val browserSession = TestBrowserSession()
        var commits = 0
        val login = session(browserSession) { commits += 1 }
        val result = async { login.login(request()) }
        runCurrent()

        result.cancel()
        runCurrent()
        browserSession.complete(AuthenticatedSession(listOf(cookie("late", "secret", "reader.example.com"))))
        runCurrent()

        assertEquals(SourceLoginState.Cancelled, login.state.value)
        assertTrue(browserSession.cancelled)
        assertEquals(0, commits)
    }

    @Test
    fun `caller cancellation after atomic commit starts waits for authenticated outcome`() = runTest {
        val browserSession = TestBrowserSession()
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val login = SourceLoginSession(
            browserAdapter = BrowserLoginAdapter { BrowserOpenResult.Opened(browserSession) },
            committer = AuthenticatedSessionCommitter { _, _ ->
                commitStarted.complete(Unit)
                releaseCommit.await()
            },
        )
        val result = async { login.login(request()) }
        runCurrent()

        browserSession.complete(AuthenticatedSession(listOf(cookie("session", "secret", "reader.example.com"))))
        commitStarted.await()
        result.cancel(CancellationException("caller cancelled"))
        releaseCommit.complete(Unit)
        runCurrent()

        assertInstanceOf(SourceLoginState.Authenticated::class.java, login.state.value)
    }

    @Test
    fun `caller cancellation after atomic commit starts waits for commit failed outcome`() = runTest {
        val browserSession = TestBrowserSession()
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val login = SourceLoginSession(
            browserAdapter = BrowserLoginAdapter { BrowserOpenResult.Opened(browserSession) },
            committer = AuthenticatedSessionCommitter { _, _ ->
                commitStarted.complete(Unit)
                releaseCommit.await()
                error("persistence failed")
            },
        )
        val result = async { login.login(request()) }
        runCurrent()

        browserSession.complete(AuthenticatedSession(listOf(cookie("session", "secret", "reader.example.com"))))
        commitStarted.await()
        result.cancel(CancellationException("caller cancelled"))
        releaseCommit.complete(Unit)
        runCurrent()

        assertEquals(SourceLoginState.CommitFailed, login.state.value)
    }

    private fun session(
        browserSession: TestBrowserSession,
        commit: (AuthenticatedSession) -> Unit,
    ) = SourceLoginSession(
        browserAdapter = BrowserLoginAdapter { BrowserOpenResult.Opened(browserSession) },
        committer = AuthenticatedSessionCommitter { _, authenticated -> commit(authenticated) },
    )

    private fun request(
        required: Set<String> = emptySet(),
        timeoutMillis: Long = 30_000,
    ) = SourceLoginRequest(
        url = "https://reader.example.com/login".toHttpUrl(),
        requiredCookieNames = required,
        timeoutMillis = timeoutMillis,
    )

    private fun cookie(
        name: String,
        value: String,
        domain: String,
        hostOnly: Boolean = true,
        path: String = "/",
    ) = AuthenticatedCookie(
        name = name,
        value = value,
        domain = domain,
        hostOnly = hostOnly,
        path = path,
        expiresAt = null,
        secure = true,
        httpOnly = true,
    )

    private class TestBrowserSession : BrowserLoginSession {
        private val result = CompletableDeferred<BrowserLoginResult>()
        var cancelled = false
            private set

        override suspend fun awaitResult(): BrowserLoginResult = result.await()

        override fun cancel() {
            cancelled = true
            result.complete(BrowserLoginResult.Cancelled)
        }

        fun complete(session: AuthenticatedSession) {
            result.complete(BrowserLoginResult.Completed(session))
        }

        fun cancelFromUser() {
            result.complete(BrowserLoginResult.Cancelled)
        }
    }
}
