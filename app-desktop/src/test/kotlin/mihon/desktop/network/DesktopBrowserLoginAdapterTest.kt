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
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.BrowserLoginResult
import tachiyomi.domain.source.service.BrowserOpenResult
import tachiyomi.domain.source.service.SourceLoginRequest
import tachiyomi.domain.source.service.SourceLoginSession
import tachiyomi.domain.source.service.SourceLoginState
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserLoginAdapterTest {

    @Test
    fun `opens external browser then commits controlled completion through the real jar`() = runTest {
        val opened = mutableListOf<URI>()
        val tickets = mutableListOf<DesktopBrowserLoginTicket>()
        val completion = DesktopBrowserLoginCompletion()
        val adapter = DesktopBrowserLoginAdapter(
            browserOpener = DesktopBrowserOpener { uri, ticket ->
                opened += uri
                tickets += ticket
                true
            },
            completion = completion,
        )
        val jar = DesktopCookieJar()
        val request = request(required = setOf("session", "clearance"))
        val login = SourceLoginSession(adapter, DesktopAuthenticatedSessionCommitter(jar))
        val result = async { login.login(request) }
        runCurrent()

        assertTrue(
            tickets.single().complete(
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
        val tickets = mutableListOf<DesktopBrowserLoginTicket>()
        val adapter = DesktopBrowserLoginAdapter(
            browserOpener = DesktopBrowserOpener { _, ticket -> tickets += ticket; false },
            completion = completion,
        )

        assertEquals(BrowserOpenResult.Unavailable, adapter.open(request()))
        assertFalse(tickets.single().complete(authenticated("late", "secret")))
    }

    @Test
    fun `controlled cancellation completes once and late cookies are rejected`() = runTest {
        val completion = DesktopBrowserLoginCompletion()
        lateinit var ticket: DesktopBrowserLoginTicket
        val adapter = DesktopBrowserLoginAdapter(
            DesktopBrowserOpener { _, openedTicket -> ticket = openedTicket; true },
            completion,
        )
        val opened = assertInstanceOf(BrowserOpenResult.Opened::class.java, adapter.open(request()))
        val result = async { opened.session.awaitResult() }
        runCurrent()

        assertTrue(ticket.cancel())
        assertEquals(BrowserLoginResult.Cancelled, result.await())
        assertFalse(ticket.complete(authenticated("session", "late-secret")))
        assertFalse(ticket.cancel())
    }

    @Test
    fun `duplicate completion accepts only the first complete session`() = runTest {
        val completion = DesktopBrowserLoginCompletion()
        lateinit var ticket: DesktopBrowserLoginTicket
        val adapter = DesktopBrowserLoginAdapter(
            DesktopBrowserOpener { _, openedTicket -> ticket = openedTicket; true },
            completion,
        )
        val opened = assertInstanceOf(BrowserOpenResult.Opened::class.java, adapter.open(request()))

        assertTrue(ticket.complete(authenticated("first", "one")))
        assertFalse(ticket.complete(authenticated("second", "two")))
        val completed = assertInstanceOf(BrowserLoginResult.Completed::class.java, opened.session.awaitResult())
        assertEquals(setOf("first"), completed.session.cookieNames)
    }

    @Test
    fun `same-host concurrent opens receive bound opaque tickets without cross-wiring`() = runTest {
        val completion = DesktopBrowserLoginCompletion()
        val tickets = mutableListOf<DesktopBrowserLoginTicket>()
        val adapter = DesktopBrowserLoginAdapter(
            DesktopBrowserOpener { _, ticket -> tickets += ticket; true },
            completion,
        )

        val first = assertInstanceOf(BrowserOpenResult.Opened::class.java, adapter.open(request()))
        val second = assertInstanceOf(BrowserOpenResult.Opened::class.java, adapter.open(request()))
        val firstResult = async { first.session.awaitResult() }
        val secondResult = async { second.session.awaitResult() }
        runCurrent()

        assertEquals(2, tickets.size)
        assertNotEquals(tickets[0], tickets[1])
        assertTrue(tickets.none { it.toString().contains("reader.example.com") })
        assertTrue(tickets[1].complete(authenticated("second", "account-two")))
        assertTrue(tickets[0].complete(authenticated("first", "account-one")))
        assertEquals(setOf("first"), completed(firstResult.await()).session.cookieNames)
        assertEquals(setOf("second"), completed(secondResult.await()).session.cookieNames)
        assertFalse(tickets[0].complete(authenticated("late", "late")))
        assertFalse(tickets[1].cancel())
    }

    @Test
    fun `production committer late persistence failure publishes commit failed and restores old jar and file`(
        @TempDir tempDir: Path,
    ) =
        runTest {
            val file = tempDir.resolve("cookies.json").toFile()
            val request = request()
            val oldCookie = okhttp3.Cookie.Builder()
                .name("old")
                .value("old-value")
                .hostOnlyDomain(request.url.host)
                .path("/")
                .expiresAt(System.currentTimeMillis() + 3_600_000)
                .build()
            DesktopCookieJar(file).saveFromResponse(request.url, listOf(oldCookie))
            val oldFile = file.readBytes()
            val jar = DesktopCookieJar(file) { source, target ->
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                throw IOException("replace reported failure after moving target")
            }
            lateinit var ticket: DesktopBrowserLoginTicket
            val login = SourceLoginSession(
                DesktopBrowserLoginAdapter(
                    DesktopBrowserOpener { _, openedTicket -> ticket = openedTicket; true },
                    DesktopBrowserLoginCompletion(),
                ),
                DesktopAuthenticatedSessionCommitter(jar),
            )
            val result = async { login.login(request) }
            runCurrent()

            assertTrue(ticket.complete(authenticated("session", "new-secret")))

            assertEquals(SourceLoginState.CommitFailed, result.await())
            assertEquals(listOf("old"), jar.get(request.url).map { it.name })
            assertTrue(oldFile.contentEquals(file.readBytes()))
        }

    private fun request(required: Set<String> = emptySet()) = SourceLoginRequest(
        url = "https://reader.example.com/login".toHttpUrl(),
        requiredCookieNames = required,
        timeoutMillis = 30_000,
    )

    private fun authenticated(name: String, value: String) = AuthenticatedSession(
        listOf(authenticatedCookie(name, value)),
    )

    private fun completed(result: BrowserLoginResult) =
        assertInstanceOf(BrowserLoginResult.Completed::class.java, result)

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
