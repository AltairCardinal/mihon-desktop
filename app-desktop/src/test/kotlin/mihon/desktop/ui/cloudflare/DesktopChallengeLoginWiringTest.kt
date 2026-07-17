package mihon.desktop.ui.cloudflare

import eu.kanade.tachiyomi.network.DesktopCookieJar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.network.ChallengeRecoveryIntent
import mihon.desktop.network.ChallengeRecoveryState
import mihon.desktop.network.CloudflareChallenge
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopAuthenticatedSessionCommitter
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopChallengeBrowserLoginBridge
import mihon.desktop.network.FlareSolverrClient
import mihon.desktop.settings.DesktopAppPreferences
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.SourceLoginRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChallengeLoginWiringTest {
    @Test
    fun `FlareSolverr preferences persist and resolve only enabled absolute http URLs dynamically`() {
        val node = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        try {
            val first = DesktopAppPreferences(DesktopPreferenceStore(node), node)
            assertEquals(listOf(false, "", null), listOf(first.flareSolverrEnabled.get(), first.flareSolverrUrl.get(), first.flareSolverrRuntimeConfig()))
            first.flareSolverrEnabled.set(true)
            listOf("", "not a url", "/relative", "ftp://solver.example", "http:///missing-host").forEach {
                first.flareSolverrUrl.set(it)
                assertNull(first.flareSolverrRuntimeConfig(), it)
            }
            first.flareSolverrUrl.set("HTTPS://solver.example/base/")
            val restored = DesktopAppPreferences(DesktopPreferenceStore(node), node)
            assertEquals(listOf(true, "https://solver.example/base/"), listOf(restored.flareSolverrEnabled.get(), restored.flareSolverrRuntimeConfig()?.baseUrl.toString()))
            restored.flareSolverrUrl.set("https://例子.测试/")
            assertEquals("https://xn--fsqu00a.xn--0zwm56d/", restored.flareSolverrRuntimeConfig()?.baseUrl.toString())
            restored.flareSolverrEnabled.set(false)
            assertNull(restored.flareSolverrRuntimeConfig())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `dynamic solver provider is observed only by explicit solver intent and disabled invalid settings send no HTTP`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
                val providerCalls = AtomicInteger()
                val jar = DesktopCookieJar()
                val manager = CloudflareChallengeManager(
                    committer = DesktopAuthenticatedSessionCommitter(jar),
                    flareSolverrClientProvider = {
                        providerCalls.incrementAndGet()
                        preferences.flareSolverrRuntimeConfig()?.let {
                            FlareSolverrClient(it.baseUrl.toString(), OkHttpClient())
                        }
                    },
                )
                assertEquals(ChallengeRecoveryState.AwaitingUserAction, manager.recover(manager.publish(request()), ChallengeRecoveryIntent.Retry))
                manager.recover(
                    manager.publish(request()),
                    ChallengeRecoveryIntent.SubmitManualCookies(session("manual-secret")),
                )
                manager.recover(manager.publish(request()), ChallengeRecoveryIntent.OpenBrowser)
                assertEquals(listOf(0, 0), listOf(providerCalls.get(), server.requestCount))
                assertEquals(
                    ChallengeRecoveryState.RecoverableFailure(mihon.desktop.network.ChallengeRecoveryFailure.SolverUnavailable),
                    manager.recover(manager.publish(request()), ChallengeRecoveryIntent.UseFlareSolverr),
                )
                preferences.flareSolverrEnabled.set(true)
                preferences.flareSolverrUrl.set("ftp://invalid")
                manager.recover(manager.publish(request()), ChallengeRecoveryIntent.UseFlareSolverr)
                assertEquals(listOf(2, 0), listOf(providerCalls.get(), server.requestCount))
                server.enqueue(solvedResponse())
                preferences.flareSolverrUrl.set(server.url("/").toString())
                assertTrue(
                    manager.recover(manager.publish(request()), ChallengeRecoveryIntent.UseFlareSolverr) is
                        ChallengeRecoveryState.Recovered,
                )
                assertEquals(listOf(3, 1), listOf(providerCalls.get(), server.requestCount))
                assertEquals("solver-secret", jar.get(request().url).single().value)
            }
        }

    @Test
    fun `same URL challenges own separate opaque browser tickets and reject cross late or duplicate completion`() = runTest {
        val opened = AtomicInteger()
        val bridge = DesktopChallengeBrowserLoginBridge(
            browserOpener = DesktopBrowserOpener { _, _ -> opened.incrementAndGet(); true },
        )
        val jar = DesktopCookieJar()
        val manager = CloudflareChallengeManager(
            browserAdapterProvider = bridge::adapterFor,
            committer = DesktopAuthenticatedSessionCommitter(jar),
        )
        val first = manager.publish(request())
        val second = manager.publish(request())
        val firstRecovery = async { manager.recover(first, ChallengeRecoveryIntent.OpenBrowser) }
        val secondRecovery = async { manager.recover(second, ChallengeRecoveryIntent.OpenBrowser) }
        runCurrent()
        val unbound = CloudflareChallenge(request())
        assertFalse(bridge.complete(unbound, session("cross-secret")))
        assertTrue(bridge.complete(second, session("second-secret", "second")))
        assertTrue(bridge.complete(first, session("first-secret", "first")))
        assertTrue(firstRecovery.await() is ChallengeRecoveryState.Recovered)
        assertTrue(secondRecovery.await() is ChallengeRecoveryState.Recovered)
        assertEquals(2, opened.get())
        assertFalse(bridge.complete(first, session("late-secret")))
        assertFalse(bridge.complete(second, session("duplicate-secret")))
        assertEquals("DesktopChallengeBrowserLoginBridge(pending=0)", bridge.toString())
        assertFalse(first.state.value.toString().contains("first-secret"))
        assertFalse(second.state.value.toString().contains("second-secret"))
    }

    @Test
    fun `browser cancel and timeout remove pending challenge tickets before late completion`() = runTest {
        val bridge = DesktopChallengeBrowserLoginBridge(
            browserOpener = DesktopBrowserOpener { _, _ -> true },
        )
        val manager = CloudflareChallengeManager(
            browserAdapterProvider = bridge::adapterFor,
            committer = DesktopAuthenticatedSessionCommitter(DesktopCookieJar()),
        )
        val cancelled = manager.publish(request())
        val cancelRecovery = async { manager.recover(cancelled, ChallengeRecoveryIntent.OpenBrowser) }
        runCurrent()
        assertTrue(bridge.cancel(cancelled))
        assertEquals(ChallengeRecoveryState.Cancelled, cancelRecovery.await())
        assertFalse(bridge.complete(cancelled, session("late-cancel-secret")))
        assertEquals("DesktopChallengeBrowserLoginBridge(pending=0)", bridge.toString())
        val timedOut = manager.publish(request(timeoutMillis = 10_000))
        val timeoutRecovery = async { manager.recover(timedOut, ChallengeRecoveryIntent.OpenBrowser) }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(ChallengeRecoveryState.TimedOut, timeoutRecovery.await())
        assertFalse(bridge.complete(timedOut, session("late-timeout-secret")))
        assertEquals("DesktopChallengeBrowserLoginBridge(pending=0)", bridge.toString())
    }

    private fun request(timeoutMillis: Long = 30_000) = SourceLoginRequest(
        url = "https://reader.example.com/chapter".toHttpUrl(),
        requiredCookieNames = setOf("cf_clearance"),
        timeoutMillis = timeoutMillis,
    )

    private fun session(value: String, name: String = "cf_clearance") = AuthenticatedSession(
        listOf(
            AuthenticatedCookie(name, value, "reader.example.com", true, "/", null, true, true),
            AuthenticatedCookie("cf_clearance", value, "reader.example.com", true, "/", null, true, true),
        ).distinctBy { it.name },
    )

    private fun solvedResponse() = MockResponse(
        body = """{"status":"ok","solution":{"userAgent":"solver-agent","cookies":[{"name":"cf_clearance","value":"solver-secret","domain":"reader.example.com","secure":true}]}}""",
    )
}
