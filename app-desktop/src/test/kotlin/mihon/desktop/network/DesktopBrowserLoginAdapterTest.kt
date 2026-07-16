package mihon.desktop.network

import eu.kanade.tachiyomi.network.DesktopCookieJar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.BrowserLoginResult
import tachiyomi.domain.source.service.BrowserOpenResult
import tachiyomi.domain.source.service.SourceLoginRequest
import tachiyomi.domain.source.service.SourceLoginSession
import tachiyomi.domain.source.service.SourceLoginState
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserLoginAdapterTest {

    @Test
    fun `opens external browser then commits controlled completion through the real jar`() = runTest {
        val opened = mutableListOf<URI>()
        val completion = DesktopBrowserLoginCompletion()
        val adapter = DesktopBrowserLoginAdapter(
            browserOpener = DesktopBrowserOpener { uri -> opened += uri; true },
            completion = completion,
        )
        val jar = DesktopCookieJar()
        val request = request(required = setOf("session", "clearance"))
        val login = SourceLoginSession(adapter, DesktopAuthenticatedSessionCommitter(jar))
        val result = async { login.login(request) }
        runCurrent()
        val pending = completion.pendingSessionIds().single()

        assertTrue(
            completion.complete(
                pending,
                AuthenticatedSession(
                    listOf(
                        authenticatedCookie("session", "secret"),
                        authenticatedCookie("clearance", "clear-secret"),
                    ),
                ),
            ),
        )

        assertInstanceOf(SourceLoginState.Authenticated::class.java, result.await())
        assertEquals(listOf(request.url.toUri()), opened)
        assertEquals(setOf("clearance", "session"), jar.get(request.url).map { it.name }.toSet())
    }

    @Test
    fun `unsupported browser reports unavailable without registering completion`() = runTest {
        val completion = DesktopBrowserLoginCompletion()
        val adapter = DesktopBrowserLoginAdapter(
            browserOpener = DesktopBrowserOpener { false },
            completion = completion,
        )

        assertEquals(BrowserOpenResult.Unavailable, adapter.open(request()))
        assertTrue(completion.pendingSessionIds().isEmpty())
    }

    @Test
    fun `controlled cancellation completes once and late cookies are rejected`() = runTest {
        val completion = DesktopBrowserLoginCompletion()
        val adapter = DesktopBrowserLoginAdapter(DesktopBrowserOpener { true }, completion)
        val opened = assertInstanceOf(BrowserOpenResult.Opened::class.java, adapter.open(request()))
        val sessionId = completion.pendingSessionIds().single()
        val result = async { opened.session.awaitResult() }
        runCurrent()

        assertTrue(completion.cancel(sessionId))
        assertEquals(BrowserLoginResult.Cancelled, result.await())
        assertFalse(completion.complete(sessionId, authenticated("session", "late-secret")))
        assertTrue(completion.pendingSessionIds().isEmpty())
    }

    @Test
    fun `duplicate completion accepts only the first complete session`() = runTest {
        val completion = DesktopBrowserLoginCompletion()
        val adapter = DesktopBrowserLoginAdapter(DesktopBrowserOpener { true }, completion)
        val opened = assertInstanceOf(BrowserOpenResult.Opened::class.java, adapter.open(request()))
        val sessionId = completion.pendingSessionIds().single()

        assertTrue(completion.complete(sessionId, authenticated("first", "one")))
        assertFalse(completion.complete(sessionId, authenticated("second", "two")))
        val completed = assertInstanceOf(BrowserLoginResult.Completed::class.java, opened.session.awaitResult())
        assertEquals(setOf("first"), completed.session.cookieNames)
    }

    @Test
    fun `each browser open receives an opaque unique session id`() = runTest {
        val completion = DesktopBrowserLoginCompletion()
        val adapter = DesktopBrowserLoginAdapter(DesktopBrowserOpener { true }, completion)

        adapter.open(request())
        adapter.open(request())
        val ids = completion.pendingSessionIds().toList()

        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        assertTrue(ids.none { it.contains("reader.example.com") })
    }

    private fun request(required: Set<String> = emptySet()) = SourceLoginRequest(
        url = "https://reader.example.com/login".toHttpUrl(),
        requiredCookieNames = required,
        timeoutMillis = 30_000,
    )

    private fun authenticated(name: String, value: String) = AuthenticatedSession(
        listOf(authenticatedCookie(name, value)),
    )

    private fun authenticatedCookie(name: String, value: String) = AuthenticatedCookie(
        name = name,
        value = value,
        domain = "reader.example.com",
        hostOnly = true,
        path = "/",
        expiresAt = null,
        secure = true,
        httpOnly = true,
    )
}
