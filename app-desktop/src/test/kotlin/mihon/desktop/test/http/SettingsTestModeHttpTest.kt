package mihon.desktop.test.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.desktop.network.DesktopCloudflareCookieImportResult
import mihon.desktop.network.DesktopNetworkMaintenancePort
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.security.DesktopPassphraseVerifier
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.test.state.applicationState
import mihon.desktop.ui.settings.AdvancedSettingsPlatformActions
import mihon.desktop.ui.settings.DesktopSettingsCatalog
import mihon.desktop.ui.settings.ProductionAdvancedSettingsPlatformActions
import mihon.desktop.ui.settings.SecuritySettingsController
import mihon.desktop.ui.browse.SourceBrowseTestActionResult
import mihon.desktop.ui.browse.SourceBrowseTestFailureCode
import mihon.desktop.ui.browse.SourceBrowseTestSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.net.URI
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import java.util.prefs.Preferences

class SettingsTestModeHttpTest {
    @Test
    fun `settings search action invokes production search owner instead of unconditional success`() = runBlocking {
        val calls = AtomicInteger()
        val controller = SettingsTestModeController {
            calls.incrementAndGet()
            listOf(SettingsTestRow("Backup", "Settings > Backup"))
        }
        SettingsTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                post(baseUrl, "/test/action/setting_search", """{"query":"backup"}""")

                assertEquals(1, calls.get())
            }
        } finally {
            SettingsTestModeBridge.clear(controller)
        }
    }

    @Test
    fun `settings search executes production catalog and selects its real route`() = runBlocking {
        val fixture = fixture()
        val title = DesktopSettingsCatalog.screens().flatMap { it.preferences }.first().title
        assertTrue(DesktopSettingsCatalog.search(title).isNotEmpty())
        val controller = fixture.controller(searchOwner = ProductionSettingsSearchOwner())
        SettingsTestModeBridge.install(controller)
        TestNavigationController.reset()
        try {
            withServer { baseUrl ->
                val search = post(
                    baseUrl,
                    "/test/action/setting_search",
                    """{"query":"${jsonEscaped(title)}"}""",
                )
                assertEquals(200, search.statusCode())
                assertTrue(search.body().contains("\"rows\":[{"), search.body())

                assertEquals(200, post(baseUrl, "/test/action/setting_search_select", """{"index":"0"}""").statusCode())
                assertTrue(TestNavigationController.pendingScreenNavigation.value != null)
                assertEquals(404, post(baseUrl, "/test/action/setting_search_select", """{"index":"99"}""").statusCode())
            }
        } finally {
            controller.close()
            fixture.close()
            TestNavigationController.reset()
        }
    }

    @Test
    fun `settings search distinguishes missing row from rejected navigation`() = runBlocking {
        val fixture = fixture()
        val owner = object : SettingsSearchOwner {
            override fun search(query: String) = listOf(SettingsTestRow("Result", "Settings"))
            override fun select(index: Int) = if (index == 0) {
                SettingsSearchSelection.NAVIGATION_REJECTED
            } else {
                SettingsSearchSelection.ROW_NOT_FOUND
            }
        }
        val controller = fixture.controller(owner)
        SettingsTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                post(baseUrl, "/test/action/setting_search", """{"query":"result"}""")
                val rejected = post(baseUrl, "/test/action/setting_search_select", """{"index":"0"}""")
                assertEquals(409, rejected.statusCode())
                assertTrue(rejected.body().contains("NAVIGATION_REJECTED"))
                assertEquals(404, post(baseUrl, "/test/action/setting_search_select", """{"index":"1"}""").statusCode())
            }
        } finally {
            controller.close()
            fixture.close()
        }
    }

    @Test
    fun `security actions execute production controller and expose authentication feedback`() = runBlocking {
        val fixture = fixture()
        val controller = fixture.controller()
        SettingsTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val missing = post(baseUrl, "/test/action/setting_security_enable", "{}")
                assertEquals(400, missing.statusCode())
                assertTrue(missing.body().contains("MISSING_PARAMETER"))

                val enabled = post(
                    baseUrl,
                    "/test/action/setting_security_enable",
                    """{"passphrase":"secret","confirmation":"secret"}""",
                )
                assertEquals(200, enabled.statusCode())
                assertFalse(enabled.body().contains("secret"))
                assertTrue(fixture.preferences.useAuthenticator().get(), controller.snapshot().toString())

                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/setting_security_delay",
                        """{"minutes":"5","currentPassphrase":"secret"}""",
                    ).statusCode(),
                )
                assertEquals(5, fixture.preferences.lockAppAfter().get())

                val mismatch = post(
                    baseUrl,
                    "/test/action/setting_security_change_passphrase",
                    """{"currentPassphrase":"secret","replacement":"new","confirmation":"other"}""",
                )
                assertEquals(409, mismatch.statusCode())
                assertTrue(mismatch.body().contains("AUTHENTICATION_FAILED"))
                assertTrue(mismatch.body().contains("PassphraseMismatch"))

                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/setting_security_change_passphrase",
                        """{"currentPassphrase":"secret","replacement":"new","confirmation":"new"}""",
                    ).statusCode(),
                )
                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/setting_security_disable",
                        """{"currentPassphrase":"new"}""",
                    ).statusCode(),
                )
                assertFalse(fixture.preferences.useAuthenticator().get())
            }
        } finally {
            controller.close()
            fixture.close()
        }
    }

    @Test
    fun `platform maintenance requires confirmation and invokes production ports`() = runBlocking {
        val fixture = fixture()
        val controller = fixture.controller()
        SettingsTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                assertFalse(ProductionAdvancedSettingsPlatformActions.openCrashLogFolder())
                val confirmation = post(baseUrl, "/test/action/setting_clear_cookies", "{}")
                assertEquals(409, confirmation.statusCode())
                assertTrue(confirmation.body().contains("CONFIRMATION_REQUIRED"))
                assertEquals(0, fixture.network.clearCalls)

                assertEquals(
                    200,
                    post(baseUrl, "/test/action/setting_clear_cookies", """{"confirm":"true"}""").statusCode(),
                )
                assertEquals(1, fixture.network.clearCalls)

                fixture.network.clearFailure = IOException("cookie store unavailable")
                val portFailure = post(
                    baseUrl,
                    "/test/action/setting_clear_cookies",
                    """{"confirm":"true"}""",
                )
                assertEquals(503, portFailure.statusCode())
                assertTrue(portFailure.body().contains("PORT_FAILURE"))
                fixture.network.clearFailure = null

                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/setting_import_cloudflare_cookie",
                        """{"domain":"https://example.com","value":"cookie"}""",
                    ).statusCode(),
                )
                assertEquals("https://example.com" to "cookie", fixture.network.imported.single())

                assertEquals(409, post(baseUrl, "/test/action/setting_clear_network_cache", "{}").statusCode())
                assertEquals(
                    200,
                    post(baseUrl, "/test/action/setting_clear_network_cache", """{"confirm":"true"}""").statusCode(),
                )
                assertEquals(1, fixture.platform.clearCalls)

                assertEquals(200, post(baseUrl, "/test/action/setting_open_crash_logs", "{}").statusCode())
                assertEquals(1, fixture.platform.openCalls)

                val legacyNoOp = post(baseUrl, "/test/action/setting_change", """{"key":"unknown","value":"x"}""")
                assertEquals(400, legacyNoOp.statusCode())
                assertTrue(legacyNoOp.body().contains("UNSUPPORTED_ACTION"))
            }
        } finally {
            controller.close()
            fixture.close()
        }
    }

    @Test
    fun `platform maintenance cancellation and concurrent action are typed`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Boolean>()
        val fixture = fixture(
            platform = RecordingPlatformActions(
                clear = {
                    started.complete(Unit)
                    blocked.await()
                },
            ),
        )
        val controller = fixture.controller()
        SettingsTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val clearing = async(Dispatchers.IO) {
                    post(baseUrl, "/test/action/setting_clear_network_cache", """{"confirm":"true"}""")
                }
                started.await()
                val concurrent = post(baseUrl, "/test/action/setting_open_crash_logs", "{}")
                assertEquals(409, concurrent.statusCode())
                assertTrue(concurrent.body().contains("OPERATION_IN_PROGRESS"))

                val cancelled = post(baseUrl, "/test/action/setting_cancel", "{}")
                assertEquals(200, cancelled.statusCode())
                assertTrue(cancelled.body().contains("\"phase\":\"CANCELLED\""))
                assertEquals(409, clearing.await().statusCode())
            }
        } finally {
            controller.close()
            fixture.close()
        }
    }

    @Test
    fun `closing settings owner cancels active maintenance without leaking platform work`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val fixture = fixture(
            platform = RecordingPlatformActions(
                clear = {
                    started.complete(Unit)
                    try {
                        CompletableDeferred<Boolean>().await()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
            ),
        )
        val controller = fixture.controller()
        SettingsTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val clearing = async(Dispatchers.IO) {
                    post(baseUrl, "/test/action/setting_clear_network_cache", """{"confirm":"true"}""")
                }
                started.await()

                controller.close()

                cancelled.await()
                assertEquals(409, clearing.await().statusCode())
            }
        } finally {
            SettingsTestModeBridge.clear(controller)
            fixture.close()
        }
    }

    @Test
    fun `settings owner unavailable and closed failures are typed`() = runBlocking {
        SettingsTestModeBridge.controller?.let(SettingsTestModeBridge::clear)
        withServer { baseUrl ->
            assertEquals(503, post(baseUrl, "/test/action/setting_search", """{"query":"x"}""").statusCode())
        }
        val controller = SettingsTestModeController { emptyList() }
        controller.close()
        SettingsTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val closed = post(baseUrl, "/test/action/setting_search", """{"query":"x"}""")
                assertEquals(503, closed.statusCode())
                assertTrue(closed.body().contains("OWNER_CLOSED"))
            }
        } finally {
            SettingsTestModeBridge.clear(controller)
        }
    }

    @Test
    fun `sensitive settings params never enter in memory or HTTP action history`() = runBlocking {
        val fixture = fixture()
        val controller = fixture.controller()
        val sourceLoginController = mockk<BrowseSearchTestModeController>()
        coEvery {
            sourceLoginController.executeSourceLogin("source_login_complete", any())
        } returns SourceBrowseTestActionResult(
            success = false,
            snapshot = SourceBrowseTestSnapshot(sourceId = 175L),
            failureCode = SourceBrowseTestFailureCode.OPERATION_REJECTED,
        )
        SettingsTestModeBridge.install(controller)
        BrowseSearchTestModeBridge.install(sourceLoginController)
        applicationState.reset()
        try {
            withServer { baseUrl ->
                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/setting_security_enable",
                        """{"passphrase":"alpha-secret","confirmation":"alpha-secret"}""",
                    ).statusCode(),
                )
                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/setting_security_change_passphrase",
                        """{"currentPassphrase":"alpha-secret","replacement":"beta-secret","confirmation":"beta-secret"}""",
                    ).statusCode(),
                )
                assertEquals(
                    200,
                    post(
                        baseUrl,
                        "/test/action/setting_import_cloudflare_cookie",
                        """{"domain":"https://example.com","value":"cf-sensitive-cookie"}""",
                    ).statusCode(),
                )
                val sourceCookieSecret = "source-login-cookie-175-secret"
                assertEquals(
                    409,
                    post(
                        baseUrl,
                        "/test/action/source_login_complete",
                        """{"attemptToken":"history-safe-token","cookieHeader":"$sourceCookieSecret"}""",
                    ).statusCode(),
                )

                val inMemoryHistory = applicationState.actionHistory.value.joinToString { it.params.toString() }
                val httpHistory = get(baseUrl, "/test/history").body()
                listOf("alpha-secret", "beta-secret", "cf-sensitive-cookie", sourceCookieSecret).forEach { secret ->
                    assertFalse(secret in inMemoryHistory)
                    assertFalse(secret in httpHistory)
                }
                val sourceLoginHistory = applicationState.actionHistory.value.single {
                    it.action == "source_login_complete"
                }
                assertEquals("<redacted>", sourceLoginHistory.params["cookieHeader"])
                assertEquals("history-safe-token", sourceLoginHistory.params["attemptToken"])
                assertEquals("https://example.com" to "cf-sensitive-cookie", fixture.network.imported.single())
                coVerify(exactly = 1) {
                    sourceLoginController.executeSourceLogin(
                        "source_login_complete",
                        match {
                            it["attemptToken"] == "history-safe-token" &&
                                it["cookieHeader"] == sourceCookieSecret
                        },
                    )
                }
            }
        } finally {
            applicationState.reset()
            BrowseSearchTestModeBridge.clear(sourceLoginController)
            controller.close()
            fixture.close()
        }
    }

    @Test
    fun `maintenance cancel cannot miss a child between construction and active handle publication`() = runBlocking {
        val publishWindow = CompletableDeferred<Unit>()
        val continuePublish = CompletableDeferred<Unit>()
        val allowPlatformReturn = CompletableDeferred<Unit>()
        val fixture = fixture(
            platform = RecordingPlatformActions(
                clear = {
                    allowPlatformReturn.await()
                    true
                },
            ),
        )
        val controller = fixture.controller(
            beforeMaintenanceStart = {
                publishWindow.complete(Unit)
                continuePublish.await()
            },
        )
        SettingsTestModeBridge.install(controller)
        try {
            withServer { baseUrl ->
                val clearing = async(Dispatchers.IO) {
                    post(baseUrl, "/test/action/setting_clear_network_cache", """{"confirm":"true"}""")
                }
                publishWindow.await()

                assertEquals(200, post(baseUrl, "/test/action/setting_cancel", "{}").statusCode())
                continuePublish.complete(Unit)
                allowPlatformReturn.complete(Unit)

                assertEquals(409, clearing.await().statusCode())
                assertEquals("CANCELLED", controller.snapshot().phase)
            }
        } finally {
            controller.close()
            fixture.close()
        }
    }

    @Test
    fun `caller cancellation cancels and joins the production maintenance child`() = runBlocking {
        val childStarted = CompletableDeferred<Unit>()
        val childFinished = CompletableDeferred<Unit>()
        val fixture = fixture(
            platform = RecordingPlatformActions(
                clear = {
                    childStarted.complete(Unit)
                    try {
                        CompletableDeferred<Boolean>().await()
                    } finally {
                        childFinished.complete(Unit)
                    }
                },
            ),
        )
        val controller = fixture.controller()
        try {
            val caller = launch {
                controller.execute("setting_clear_network_cache", mapOf("confirm" to "true"))
            }
            childStarted.await()

            caller.cancel()
            caller.join()

            assertTrue(childFinished.isCompleted)
            assertEquals("CANCELLED", controller.snapshot().phase)
        } finally {
            controller.close()
            fixture.close()
        }
    }

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { testHttpServer() }.start()
        try {
            block("http://127.0.0.1:${server.resolvedConnectors().single().port}")
        } finally {
            server.stop(0, 0)
        }
    }

    private fun post(base: String, path: String, body: String) =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(base: String, path: String) =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun fixture(
        platform: RecordingPlatformActions = RecordingPlatformActions(),
    ): Fixture {
        val preferenceNode = Preferences.userRoot().node("/mihon-test/task175/${UUID.randomUUID()}")
        val preferences = SecurityPreferences(DesktopPreferenceStore(preferenceNode))
        val backend = MemoryCredentialBackend()
        val verifier = DesktopPassphraseVerifier(DesktopCredentialStore(backend))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Fixture(
            preferences,
            SecuritySettingsController(preferences, verifier),
            RecordingNetworkMaintenance(),
            platform,
            scope,
            preferenceNode,
        )
    }

    private data class Fixture(
        val preferences: SecurityPreferences,
        val security: SecuritySettingsController,
        val network: RecordingNetworkMaintenance,
        val platform: RecordingPlatformActions,
        val scope: CoroutineScope,
        val preferenceNode: Preferences,
    ) {
        fun controller(
            searchOwner: SettingsSearchOwner = ProductionSettingsSearchOwner(),
            beforeMaintenanceStart: suspend () -> Unit = {},
        ) = SettingsTestModeController(
            searchOwner,
            security,
            network,
            platform,
            scope,
            beforeMaintenanceStart,
        )

        fun close() {
            scope.cancel()
            preferenceNode.removeNode()
        }
    }

    private class RecordingNetworkMaintenance : DesktopNetworkMaintenancePort {
        val imported = mutableListOf<Pair<String, String>>()
        var clearCalls = 0
        var clearFailure: IOException? = null

        override fun importCloudflareCookie(domain: String, value: String): DesktopCloudflareCookieImportResult {
            imported += domain to value
            return DesktopCloudflareCookieImportResult.Imported("example.com")
        }

        override fun clearCookies() {
            clearCalls++
            clearFailure?.let { throw it }
        }
    }

    private class RecordingPlatformActions(
        private val clear: suspend () -> Boolean = { true },
        private val open: suspend () -> Boolean = { true },
    ) : AdvancedSettingsPlatformActions {
        var clearCalls = 0
        var openCalls = 0

        override suspend fun loadNetworkCacheSize() = "0 B"

        override suspend fun clearNetworkCache(): Boolean {
            clearCalls++
            return clear()
        }

        override suspend fun openCrashLogFolder(): Boolean {
            openCalls++
            return open()
        }
    }

    private class MemoryCredentialBackend : CredentialBackend {
        private var stored: CharArray? = null

        override fun save(account: String, secret: CharArray) {
            stored?.fill('\u0000')
            stored = secret.copyOf()
        }

        override fun load(account: String): CharArray? = stored?.copyOf()

        override fun delete(account: String) {
            stored?.fill('\u0000')
            stored = null
        }
    }

    private fun jsonEscaped(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
