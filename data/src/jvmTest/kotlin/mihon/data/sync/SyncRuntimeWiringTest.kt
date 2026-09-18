package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.crypto.SyncRecoveryCodec
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.security.SyncSecureStoreException
import mihon.domain.sync.transport.SyncRepository
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.UUID
import java.util.prefs.Preferences

class SyncRuntimeWiringTest {
    @Test
    fun `temporary token refresh failures retain credentials and report retryable network failure`() = runBlocking {
        Fixture().use { f ->
            mockwebserver3.MockWebServer().use { auth ->
                auth.start()
                SyncOnboardingFixture(f.storage, f.preferences, f.client, auth.url("/token").toString()).use { setup ->
                    setup.existing("")
                    setup.authorize()
                    setup.begin()
                    val runtime = setup.runtime
                    val expiring = runtime.credentials.replace(
                        runtime.credentials.read()!!.revision,
                        GitHubAccessToken("access-secret", "refresh-secret", "bearer", emptySet(), 1000, 1_000_000),
                    )
                    for (code in listOf(500, 429)) {
                        auth.enqueue(mockwebserver3.MockResponse(code = code, body = "private diagnostic"))
                        val result = runtime.coordinator.synchronize(SyncTrigger.PERIODIC)
                        assertEquals(SyncRunProblem.NETWORK, result.problem)
                        assertEquals(expiring, runtime.credentials.read())
                        assertFalse(runtime.preferences.history.get().contains("private diagnostic"))
                    }
                }
            }
        }
    }

    @Test
    fun `configured graph uses credentials production client database exchange and local history`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage, f.preferences, f.client).use { setup ->
                setup.existing("")
                setup.authorize("access-secret")
                setup.begin()
                val runtime = setup.runtime
                f.storage.favorite("/runtime")
                val result = runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                assertEquals(SyncRunStatus.SUCCESS, result.status)
                assertEquals(1, result.uploaded)
                assertTrue(f.networkCalls > 0)
                assertEquals(1000L, runtime.preferences.lastSuccess.get())
                assertTrue(runtime.preferences.history.get().contains("SUCCESS"))
                assertTrue(f.preferences.getAll().keys.all { Preference.isAppState(it) })
                assertFalse(f.preferences.getAll().toString().contains("access-secret"))
                val reopened = setup.runtime()
                assertEquals("access-secret", reopened.accessToken())
                assertEquals(0, reopened.coordinator.synchronize(SyncTrigger.STARTUP).uploaded)
                runtime.disconnect()
                f.storage.favorite("/after-disconnect")
                val before = f.networkCalls
                assertEquals(SyncRunStatus.SKIPPED, reopened.coordinator.synchronize(SyncTrigger.MANUAL).status)
                assertEquals(before, f.networkCalls)
                assertNull(runtime.credentials.read())
            }
        }
    }

    @Test
    fun `missing authorization and unavailable secure storage are distinct safe failures`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage).use { setup ->
                setup.existing("")
                setup.authorize()
                setup.begin()
                val runtime = setup.runtime
                runtime.credentials.clear()
                assertEquals(SyncRunProblem.AUTHORIZATION, runtime.coordinator.synchronize(SyncTrigger.MANUAL).problem)
                setup.secure.fail = true
                assertEquals(SyncRunProblem.STORAGE, runtime.coordinator.synchronize(SyncTrigger.MANUAL).problem)
            }
        }
    }

    @Test
    fun `unconfigured runtime does not access credentials or network and invalid periods are rejected`() = runBlocking {
        Fixture().use { f ->
            val runtime = f.runtime("https://example.invalid")
            f.secure.fail = true
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.synchronize(SyncTrigger.STARTUP).status)
            assertEquals(0, f.networkCalls)
            assertEquals(60, runtime.preferences.intervalMinutes())
            assertThrows(IllegalArgumentException::class.java) { runtime.preferences.setInterval(7) }
            runtime.preferences.setInterval(0)
            assertEquals(0, runtime.preferences.intervalMinutes())
        }
    }

    @Test
    fun `restoring a database without this devices secure binding renews actor before reconnecting`() = runBlocking {
        Fixture().use { f ->
            SyncOnboardingFixture(f.storage).use { setup ->
                setup.existing("")
                setup.authorize()
                f.storage.connect("old-device", setup.repository)
                setup.begin()
                val first = f.storage.handler.await { sync_journalQueries.getActiveActor().executeAsOne() }
                assertNotEquals("old-device", first.actor_id)
                assertEquals(2L, first.epoch)
                setup.begin()
                assertEquals(first, f.storage.handler.await { sync_journalQueries.getActiveActor().executeAsOne() })
            }
        }
    }

    private fun token() = GitHubAccessToken("access-secret", null, "bearer", emptySet(), null, null)

    private class MemorySecureStore : SyncSecureStore {
        val values = mutableMapOf<String, String>()
        var fail = false
        override suspend fun read(key: String): String? {
            if (fail) throw SyncSecureStoreException()
            return values[key]
        }
        override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean {
            if (read(key) != expected) return false
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
    }

    private class Fixture : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = run {
            Database.Schema.create(driver)
            Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
        }
        val storage = SyncRuntimeStorageContract.Storage(driver, JvmDatabaseHandler(database, driver))
        val secure = MemorySecureStore()
        private val node = Preferences.userRoot().node("mihon-sync-runtime-test-" + UUID.randomUUID())
        val preferences = DesktopPreferenceStore(node)
        var networkCalls = 0
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                networkCalls++
            }
        }).build()
        fun runtime(baseUrl: String, tokenUrl: String = "https://github.com/login/oauth/access_token") = SyncRuntime(
            storage.handler, storage.bootstrap, storage.creators, storage.creators, { true }, secure,
            preferences, client, GitHubAuthEndpoints(accessTokenUrl = tokenUrl, apiBaseUrl = baseUrl), { 1000L },
        )
        override fun close() {
            storage.close()
            node.removeNode()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
