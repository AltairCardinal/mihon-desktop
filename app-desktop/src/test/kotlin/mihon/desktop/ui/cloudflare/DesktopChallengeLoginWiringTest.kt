package mihon.desktop.ui.cloudflare

import eu.kanade.tachiyomi.network.DesktopCookieJar
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.network.ChallengeRecoveryIntent
import mihon.desktop.network.ChallengeRecoveryAction
import mihon.desktop.network.ChallengeRecoveryState
import mihon.desktop.network.CloudflareChallenge
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.network.DesktopAuthenticatedSessionCommitter
import mihon.desktop.network.DesktopBrowserOpener
import mihon.desktop.network.DesktopChallengeBrowserLoginBridge
import mihon.desktop.network.FlareSolverrClient
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.settings.FlareSolverrSettingsSectionContent
import mihon.desktop.ui.settings.FlareSolverrSwitchItem
import mihon.desktop.ui.settings.FlareSolverrUrlItem
import mihon.desktop.ui.settings.FlareSolverrUrlError
import mihon.desktop.ui.settings.cloudflareCookieImportedFeedback
import mihon.desktop.ui.settings.flareSolverrSettingsSection
import mihon.desktop.ui.settings.flareSolverrSettingsState
import mihon.desktop.ui.settings.updateFlareSolverrEnabled
import mihon.desktop.ui.settings.updateFlareSolverrUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.SourceLoginRequest
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChallengeLoginWiringTest {
    @Test
    fun `controller opens a bound browser ticket and completes it without direct jar access`() = runTest {
        val jar = DesktopCookieJar()
        val bridge = DesktopChallengeBrowserLoginBridge(
            browserOpener = DesktopBrowserOpener { _, _ -> true },
        )
        val manager = CloudflareChallengeManager(
            browserAdapterProvider = bridge::adapterFor,
            committer = DesktopAuthenticatedSessionCommitter(jar),
        )
        val controller = DesktopChallengeLoginController(manager, bridge, DesktopAppPreferences(InMemoryPreferenceStore()))
        val challenge = manager.publish(request())

        val recovery = async { controller.dispatch(challenge, ChallengeRecoveryIntent.OpenBrowser) }
        runCurrent()
        val running = controller.uiState(challenge)
        assertEquals(ChallengeRecoveryAction.Browser, running.runningAction)
        assertFalse(running.allowConflictingActions)
        controller.submitClearance(challenge, "browser-secret")

        assertTrue(recovery.await() is ChallengeRecoveryState.Recovered)
        val cookie = jar.get(request().url).single()
        assertEquals(listOf("reader.example.com", true, "/", true, true), listOf(cookie.domain, cookie.hostOnly, cookie.path, cookie.secure, cookie.httpOnly))
        assertEquals("browser-secret", cookie.value)
    }

    @Test
    fun `manual clearance recovered at chapter is sent by real OkHttp jar to browse`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            val jar = DesktopCookieJar()
            val manager = CloudflareChallengeManager(committer = DesktopAuthenticatedSessionCommitter(jar))
            val controller = DesktopChallengeLoginController(manager, DesktopChallengeBrowserLoginBridge(), DesktopAppPreferences(InMemoryPreferenceStore()))
            val loginRequest = SourceLoginRequest(server.url("/chapter"), setOf("cf_clearance"), 30_000)

            controller.submitClearance(manager.publish(loginRequest), "root-secret")
            OkHttpClient.Builder().cookieJar(jar).build().newCall(Request.Builder().url(server.url("/browse")).build()).execute().close()

            assertEquals("cf_clearance=root-secret", server.takeRequest().headers["Cookie"])
        }
    }

    @Test
    fun `Home action adapter drives bound browser commit feedback dismiss and stale identity`() = runTest {
        val jar = DesktopCookieJar()
        val bridge = DesktopChallengeBrowserLoginBridge(browserOpener = DesktopBrowserOpener { _, _ -> true })
        val manager = CloudflareChallengeManager(
            browserAdapterProvider = bridge::adapterFor,
            committer = DesktopAuthenticatedSessionCommitter(jar),
        )
        val adapter = DesktopChallengeHomeActionAdapter(
            DesktopChallengeLoginController(manager, bridge, DesktopAppPreferences(InMemoryPreferenceStore()), Locale.ENGLISH),
        )
        val challenge = manager.publish(request())
        var active: CloudflareChallenge? = challenge
        val browser = async { adapter.execute({ active }, challenge, DesktopChallengeHomeAction.Recover(ChallengeRecoveryIntent.OpenBrowser)) }
        runCurrent()
        adapter.execute({ active }, challenge, DesktopChallengeHomeAction.SubmitClearance("adapter-secret"))

        val recovered = browser.await()
        assertTrue(recovered.dismiss)
        assertTrue(recovered.feedback.orEmpty().isNotBlank())
        assertFalse(recovered.toString().contains("adapter-secret"))
        assertEquals("adapter-secret", jar.get(request().url).single().value)
        active = manager.publish(request())
        assertFalse(adapter.observe({ active }, challenge).dismiss)
        assertNull(adapter.observe({ active }, challenge).feedback)
    }

    @Test
    fun `timed out Home action offers pure close without dispatching another recovery`() = runTest {
        val manager = CloudflareChallengeManager()
        val adapter = DesktopChallengeHomeActionAdapter(
            DesktopChallengeLoginController(manager, DesktopChallengeBrowserLoginBridge(), DesktopAppPreferences(InMemoryPreferenceStore())),
        )
        val challenge = manager.publish(request(timeoutMillis = 1))
        Thread.sleep(5)
        var active: CloudflareChallenge? = challenge
        val timedOut = adapter.execute({ active }, challenge, DesktopChallengeHomeAction.Recover(ChallengeRecoveryIntent.OpenBrowser))
        assertEquals(ChallengeRecoveryState.TimedOut, challenge.state.value)
        assertFalse(timedOut.dismiss)

        val close = adapter.execute({ active }, challenge, DesktopChallengeHomeAction.Close)
        assertTrue(close.dismiss)
        assertNull(close.feedback)
        assertEquals(ChallengeRecoveryState.TimedOut, challenge.state.value)
    }

    @Test
    fun `controller manual solver cancel and retry actions obey state and current resolver`() = runTest {
        MockWebServer().use { solverServer ->
            solverServer.start()
            val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
            val bridge = DesktopChallengeBrowserLoginBridge(
                browserOpener = DesktopBrowserOpener { _, _ -> true },
            )
            val jar = DesktopCookieJar()
            val solverCalls = AtomicInteger()
            val manager = CloudflareChallengeManager(
                browserAdapterProvider = bridge::adapterFor,
                committer = DesktopAuthenticatedSessionCommitter(jar),
                flareSolverrClientProvider = {
                    solverCalls.incrementAndGet()
                    preferences.flareSolverrRuntimeConfig()?.let { FlareSolverrClient(it.baseUrl.toString(), OkHttpClient()) }
                },
            )
            val controller = DesktopChallengeLoginController(manager, bridge, preferences)
            val manual = manager.publish(request())
            assertTrue(controller.submitClearance(manual, "manual-secret") is ChallengeRecoveryState.Recovered)
            assertEquals("manual-secret", jar.get(request().url).single().value)

            val disabled = manager.publish(request())
            assertFalse(controller.uiState(disabled).showSolver)
            assertEquals(ChallengeRecoveryState.AwaitingUserAction, controller.dispatch(disabled, ChallengeRecoveryIntent.UseFlareSolverr))
            assertEquals(0, solverServer.requestCount)
            assertEquals(0, solverCalls.get())
            preferences.flareSolverrEnabled.set(true)
            preferences.flareSolverrUrl.set("not a URL")
            assertFalse(controller.uiState(disabled).showSolver)
            assertEquals(ChallengeRecoveryState.AwaitingUserAction, controller.dispatch(disabled, ChallengeRecoveryIntent.UseFlareSolverr))
            assertEquals(0, solverServer.requestCount)

            preferences.flareSolverrUrl.set(solverServer.url("/").toString())
            val running = manager.publish(request())
            val browserRecovery = async { controller.dispatch(running, ChallengeRecoveryIntent.OpenBrowser) }
            runCurrent()
            assertTrue(controller.uiState(running).showSolver)
            assertEquals(ChallengeRecoveryState.Running(ChallengeRecoveryAction.Browser), controller.dispatch(running, ChallengeRecoveryIntent.UseFlareSolverr))
            assertEquals(0, solverServer.requestCount)
            assertEquals(ChallengeRecoveryState.Cancelled, controller.dispatch(running, ChallengeRecoveryIntent.Cancel))
            assertTrue(browserRecovery.isCancelled || browserRecovery.await() == ChallengeRecoveryState.Cancelled)

            val failed = manager.publish(request())
            solverServer.enqueue(MockResponse(body = "{}"))
            assertTrue(controller.dispatch(failed, ChallengeRecoveryIntent.UseFlareSolverr) is ChallengeRecoveryState.RecoverableFailure)
            assertEquals(ChallengeRecoveryState.AwaitingUserAction, controller.dispatch(failed, ChallengeRecoveryIntent.Retry))
        }
    }

    @Test
    fun `controller feedback is domain only redacted and stale completion cannot dismiss a new challenge`() = runTest {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val manager = CloudflareChallengeManager(
            committer = DesktopAuthenticatedSessionCommitter(DesktopCookieJar()),
        )
        val controller = DesktopChallengeLoginController(manager, DesktopChallengeBrowserLoginBridge(), preferences, Locale.ENGLISH)
        val old = manager.publish(request())
        controller.submitClearance(old, "never-display-this-secret")
        val fresh = manager.publish(request())

        val state = controller.uiState(old)
        assertEquals("reader.example.com", state.targetHost)
        assertTrue(state.dismiss)
        assertTrue(state.feedback.isNotBlank())
        assertFalse(state.toString().contains("/chapter"))
        assertFalse(state.toString().contains("never-display-this-secret"))
        assertFalse(controller.shouldDismiss(fresh, old, old.state.value))
        assertTrue(controller.shouldDismiss(old, old, old.state.value))
    }

    @Test
    fun `base challenge resources resolve through MR`() {
        val resolved = listOf(
            MR.strings.desktop_challenge_title,
            MR.strings.desktop_challenge_open_browser,
            MR.strings.desktop_challenge_manual_cookie,
            MR.strings.desktop_challenge_manual_submit,
            MR.strings.desktop_challenge_solver_disabled,
            MR.strings.desktop_challenge_timed_out,
            MR.strings.desktop_challenge_recovered,
            MR.strings.desktop_settings_cloudflare_title,
            MR.strings.desktop_settings_cloudflare_description,
            MR.strings.desktop_settings_cloudflare_solver_title,
            MR.strings.desktop_settings_cloudflare_solver_url,
            MR.strings.desktop_settings_cloudflare_solver_explicit_only,
            MR.strings.desktop_settings_cloudflare_solver_url_required,
            MR.strings.desktop_settings_cloudflare_solver_url_invalid,
            MR.strings.desktop_settings_cloudflare_domain,
            MR.strings.desktop_settings_cloudflare_invalid_domain,
            MR.strings.desktop_settings_cloudflare_cookie_required,
            MR.strings.desktop_settings_cloudflare_domain_parse_failed,
            MR.strings.desktop_settings_cloudflare_cookie_imported,
            MR.strings.pref_clear_cookies,
            MR.strings.cookies_cleared,
            MR.strings.desktop_settings_clear_cookies_summary,
            MR.strings.desktop_settings_clear_cookies_warning,
            MR.strings.desktop_settings_clear_cookies_confirm,
            MR.strings.action_cancel,
        ).map { it.localized(Locale.ENGLISH) }
        assertTrue(resolved.all(String::isNotBlank))
    }

    @Test
    fun `advanced production section renders localized controls and routes both preference callbacks`() = runTest {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        var state = flareSolverrSettingsState(preferences)
        lateinit var renderedSwitch: FlareSolverrSwitchItem
        lateinit var renderedUrl: FlareSolverrUrlItem
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitTestApplier(), recomposer)
        val recomposerJob = launch(frameClock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        suspend fun render(frame: Long) {
            composition.setContent {
                FlareSolverrSettingsSectionContent(
                    section = flareSolverrSettingsSection(preferences, state, { it.localized(Locale.ENGLISH) }) {
                        state = it
                    },
                    renderSwitch = { renderedSwitch = it },
                    renderUrl = { renderedUrl = it },
                )
            }
            runCurrent()
            frameClock.sendFrame(frame)
            recomposer.awaitIdle()
        }
        render(0L)
        assertEquals("Enable FlareSolverr fallback", renderedSwitch.title)
        assertEquals("FlareSolverr URL", renderedUrl.label)
        renderedSwitch.onCheckedChange(true)
        render(1L)
        assertTrue(preferences.flareSolverrEnabled.get())
        assertEquals("Enter a FlareSolverr URL.", renderedUrl.error)
        renderedUrl.onValueChange("ftp://invalid")
        render(2L)
        assertEquals("Use an absolute HTTP or HTTPS URL with a host.", renderedUrl.error)
        renderedUrl.onValueChange("https://solver.example/base/")
        render(3L)
        assertNull(renderedUrl.error)
        assertTrue(preferences.flareSolverrRuntimeConfig() != null)
        val controller = DesktopChallengeLoginController(CloudflareChallengeManager(), DesktopChallengeBrowserLoginBridge(), preferences)
        assertTrue(controller.uiState(CloudflareChallenge(request())).showSolver)
        renderedSwitch.onCheckedChange(false)
        render(4L)
        assertEquals("https://solver.example/base/", preferences.flareSolverrUrl.get())
        assertNull(preferences.flareSolverrRuntimeConfig())
        assertFalse(controller.uiState(CloudflareChallenge(request())).showSolver)
        composition.dispose()
        recomposer.close()
        recomposerJob.cancelAndJoin()
    }

    @Test
    fun `manual cookie success feedback contains only the canonical domain`() {
        val feedback = cloudflareCookieImportedFeedback(
            "https://例子.测试/private/chapter".toHttpUrl(),
            Locale.ENGLISH,
        )
        assertEquals("Cookie imported for xn--fsqu00a.xn--0zwm56d", feedback)
        assertFalse(feedback.contains("private"))
        assertFalse(feedback.contains("cookie-secret"))
    }

    @Test
    fun `advanced solver settings write real preferences and share runtime availability`() {
        val node = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        try {
            val preferences = DesktopAppPreferences(DesktopPreferenceStore(node), node)
            assertEquals(
                listOf(false, "", false, null),
                flareSolverrSettingsState(preferences).let {
                    listOf(it.enabled, it.url, it.solverAvailable, it.urlError)
                },
            )

            val missing = updateFlareSolverrEnabled(preferences, true)
            assertEquals(FlareSolverrUrlError.Required, missing.urlError)
            assertEquals(missing.solverAvailable, preferences.flareSolverrRuntimeConfig() != null)

            listOf("not a URL", "/relative", "ftp://solver.example", "http:///missing-host").forEach { url ->
                val invalid = updateFlareSolverrUrl(preferences, url)
                assertEquals(FlareSolverrUrlError.Invalid, invalid.urlError, url)
                assertEquals(invalid.solverAvailable, preferences.flareSolverrRuntimeConfig() != null, url)
            }

            val valid = updateFlareSolverrUrl(preferences, "HTTPS://solver.example/base/")
            assertNull(valid.urlError)
            assertTrue(valid.solverAvailable)
            assertEquals(valid.solverAvailable, preferences.flareSolverrRuntimeConfig() != null)
            val controller = DesktopChallengeLoginController(
                CloudflareChallengeManager(),
                DesktopChallengeBrowserLoginBridge(),
                preferences,
            )
            assertTrue(controller.uiState(CloudflareChallenge(request())).showSolver)

            val disabled = updateFlareSolverrEnabled(preferences, false)
            assertEquals("HTTPS://solver.example/base/", disabled.url)
            assertFalse(disabled.solverAvailable)
            assertNull(disabled.urlError)
            assertNull(preferences.flareSolverrRuntimeConfig())
            assertFalse(controller.uiState(CloudflareChallenge(request())).showSolver)

            val reconstructed = DesktopAppPreferences(DesktopPreferenceStore(node), node)
            assertFalse(reconstructed.flareSolverrEnabled.get())
            assertEquals("HTTPS://solver.example/base/", reconstructed.flareSolverrUrl.get())
            val restored = updateFlareSolverrEnabled(reconstructed, true)
            assertTrue(restored.solverAvailable)
            assertEquals(restored.solverAvailable, reconstructed.flareSolverrRuntimeConfig() != null)
        } finally {
            node.removeNode()
        }
    }

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

    private class UnitTestApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
        override fun remove(index: Int, count: Int) = Unit
    }
}
