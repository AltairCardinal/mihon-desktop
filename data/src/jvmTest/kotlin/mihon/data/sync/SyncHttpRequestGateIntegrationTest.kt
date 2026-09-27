package mihon.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.data.sync.http.SyncHttpClient
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.http.SyncHttpResponse
import mihon.data.sync.runtime.SyncAccountHttpRequestGate
import mihon.data.sync.runtime.SyncRunStore
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.concurrent.TimeUnit

class SyncHttpRequestGateIntegrationTest {
    @Test
    fun `header confirmed account cooldown survives oversized error body`() = runBlocking {
        for (code in listOf(429, 403)) {
            MockWebServer().use { server ->
                server.start()
                val headers = if (code == 429) {
                    headersOf("Retry-After", "60")
                } else {
                    headersOf("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "70")
                }
                server.enqueue(MockResponse(code = code, headers = headers, body = "x".repeat(32)))
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                Database.Schema.create(driver)
                val database = Database(
                    driver,
                    History.Adapter(DateColumnAdapter),
                    Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
                )
                val now = 10_000L
                val store = SyncRunStore(JvmDatabaseHandler(database, driver), clock = { now })
                val gate = SyncAccountHttpRequestGate(store, 101, { now })
                val client = SyncHttpClient(
                    OkHttpClient(),
                    allowedHosts = setOf(server.hostName),
                    maxBodyBytes = 16,
                    requestGate = gate,
                )
                val request = Request.Builder().url(server.url("/sync")).get().build()

                assertTrue(runCatching { client.execute(request) }.isFailure)
                assertEquals(70_000L, store.accountHttpNotBefore(101))
                assertTrue(runCatching { client.execute(request) }.exceptionOrNull() is SyncHttpException)
                assertEquals(1, server.requestCount, "persisted cooldown must block the next request")
            }
        }
    }

    @Test
    fun `header confirmed cooldown persists when slow error body is cancelled`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(code = 429, headers = headersOf("Retry-After", "60"), body = "x".repeat(256))
                    .newBuilder()
                    .throttleBody(1, 5, TimeUnit.SECONDS)
                    .build(),
            )
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver)
            val database = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            val now = 10_000L
            val store = SyncRunStore(JvmDatabaseHandler(database, driver), clock = { now })
            val gate = SyncAccountHttpRequestGate(store, 101, { now })
            val client = SyncHttpClient(OkHttpClient(), setOf(server.hostName), requestGate = gate)
            val request = Request.Builder().url(server.url("/sync")).get().build()
            val pending = async(Dispatchers.IO) { client.execute(request) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2_000) {
                while (store.accountHttpNotBefore(101) < now + 60_000L) delay(10)
            }
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertEquals(now + 60_000L, store.accountHttpNotBefore(101))
            assertTrue(runCatching { client.execute(request) }.exceptionOrNull() is SyncHttpException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `rate limit deadline is persisted and shared by account across http clients`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(code = 429, headers = headersOf("Retry-After", "60")),
            )
            server.enqueue(MockResponse(code = 200))
            server.enqueue(MockResponse(code = 200))
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver)
            val database = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            var now = 10_000L
            val store = SyncRunStore(JvmDatabaseHandler(database, driver), clock = { now })
            val reopenedStore = SyncRunStore(JvmDatabaseHandler(database, driver), clock = { now })
            val accountA = SyncAccountHttpRequestGate(store, 101, { now })
            val reopenedAccountA = SyncAccountHttpRequestGate(reopenedStore, 101, { now })
            val accountB = SyncAccountHttpRequestGate(reopenedStore, 202, { now })
            val request = Request.Builder().url(server.url("/sync")).get().build()
            fun client(gate: SyncHttpRequestGate) = SyncHttpClient(
                OkHttpClient(),
                allowedHosts = setOf(server.hostName),
                requestGate = gate,
            )

            assertEquals(429, client(accountA).execute(request).code)
            assertEquals(now + 60_000L, store.accountHttpNotBefore(101))
            assertNotNull(server.takeRequest())
            val blocked = runCatching { client(reopenedAccountA).execute(request) }.exceptionOrNull()
            assertTrue(blocked is SyncHttpException)
            assertEquals(60_000L, (blocked as SyncHttpException).retryAfterMillis)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))

            assertEquals(200, client(accountB).execute(request).code)
            assertNotNull(server.takeRequest())
            assertEquals(0L, store.accountHttpNotBefore(202))

            now += 60_000L
            assertEquals(200, client(reopenedAccountA).execute(request).code)
            assertNotNull(server.takeRequest())
        } finally {
            server.close()
        }
    }

    @Test
    fun `request gate persists a response cooldown before the next HTTP call`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(code = 429))
            server.enqueue(MockResponse(code = 200))
            val clock = FakeClock()
            val gate = TestCooldownGate(clock)
            val client = SyncHttpClient(
                OkHttpClient(),
                allowedHosts = setOf(server.hostName),
                requestGate = gate,
            )
            val request = Request.Builder().url(server.url("/sync")).get().build()

            assertEquals(429, client.execute(request).code)
            assertEquals(1, server.requestCount)
            assertNotNull(server.takeRequest())
            assertTrue(runCatching { client.execute(request) }.exceptionOrNull() is SyncHttpException)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount, "a future cooldown must prevent the HTTP call")

            clock.now += TestCooldownGate.COOLDOWN_MILLIS
            assertEquals(200, client.execute(request).code)
            assertEquals(2, server.requestCount)
        } finally {
            server.close()
        }
    }

    private class FakeClock(var now: Long = 10_000L)

    /** This test double checks SyncHttpClient wiring; deadline policy is covered with the SQL store below. */
    private class TestCooldownGate(private val clock: FakeClock) : SyncHttpRequestGate {
        private var notBefore = 0L

        override suspend fun beforeRequest() {
            if (notBefore > clock.now) {
                throw SyncHttpException(
                    message = "request delayed by persisted account cooldown",
                    retryable = true,
                    failureClass = mihon.data.sync.http.SyncHttpFailureClass.RATE_LIMITED,
                    retryAfterMillis = notBefore - clock.now,
                )
            }
        }

        override suspend fun afterResponse(response: SyncHttpResponse) {
            if (response.code == 429) notBefore = clock.now + COOLDOWN_MILLIS
        }

        companion object {
            const val COOLDOWN_MILLIS = 5_000L
        }
    }
}
