package mihon.desktop.tracking

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.platform.DesktopOAuthCallbackServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DesktopTrackingIntegrationTest {
    private val client = OkHttpClient()

    @Test
    fun `oauth callback accepts matching state on loopback`() = runTest {
        DesktopOAuthCallbackServer().use { server ->
            val session = server.start("expected", Duration.ofSeconds(2))
            val result = async { session.awaitCode() }

            client.newCall(
                Request.Builder().url("${session.redirectUri}?code=abc&state=expected").build(),
            ).execute().use { assertEquals(200, it.code) }

            assertEquals("abc", result.await())
            assertTrue(session.redirectUri.startsWith("http://127.0.0.1:"))
        }
    }

    @Test
    fun `oauth callback rejects wrong state without consuming valid callback`() = runTest {
        DesktopOAuthCallbackServer().use { server ->
            val session = server.start("expected", Duration.ofSeconds(2))
            val result = async { session.awaitCode() }

            client.newCall(
                Request.Builder().url("${session.redirectUri}?code=attacker&state=wrong").build(),
            ).execute().use { assertEquals(400, it.code) }
            client.newCall(
                Request.Builder().url("${session.redirectUri}?code=valid&state=expected").build(),
            ).execute().close()

            assertEquals("valid", result.await())
        }
    }

    @Test
    fun `oauth callback times out and releases port`() = runTest {
        lateinit var executor: ExecutorService
        val server = DesktopOAuthCallbackServer(
            executorFactory = { Executors.newSingleThreadExecutor().also { executor = it } },
        )
        val session = server.start("state", Duration.ofMillis(30))
        val port = session.redirectUri.substringAfterLast(':').substringBefore('/').toInt()

        assertThrows(TimeoutCancellationException::class.java) {
            kotlinx.coroutines.runBlocking { session.awaitCode() }
        }
        assertTrue(executor.isShutdown)
        server.close()

        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { assertTrue(it.isBound) }
    }

    @Test
    fun `oauth server rejects non loopback binding and occupied port`() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOAuthCallbackServer(InetAddress.getByName("0.0.0.0"))
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            lateinit var executor: ExecutorService
            assertThrows(Exception::class.java) {
                DesktopOAuthCallbackServer(
                    port = occupied.localPort,
                    executorFactory = { Executors.newSingleThreadExecutor().also { executor = it } },
                ).start("state")
            }
            assertTrue(executor.isShutdown)
        }
    }

    @Test
    fun `oauth callback close shuts down its executor`() {
        lateinit var executor: ExecutorService
        val server = DesktopOAuthCallbackServer(
            executorFactory = { Executors.newSingleThreadExecutor().also { executor = it } },
        )
        server.start("state")

        server.close()

        assertTrue(executor.isShutdown)
    }

    @Test
    fun `credential store delegates secrets to OS backend and supports logout`() {
        val backend = MemoryCredentialBackend()
        val store = DesktopCredentialStore(backend)

        store.save("anilist", "refresh-token")
        assertEquals("refresh-token", store.load("anilist"))
        assertFalse(store.toString().contains("refresh-token"))

        store.delete("anilist")
        assertEquals(null, store.load("anilist"))
        assertEquals(listOf("save:anilist", "load:anilist", "delete:anilist", "load:anilist"), backend.calls)
    }

    private class MemoryCredentialBackend : CredentialBackend {
        val calls = mutableListOf<String>()
        private val values = mutableMapOf<String, CharArray>()

        override fun save(account: String, secret: CharArray) {
            calls += "save:$account"
            values[account] = secret.copyOf()
        }

        override fun load(account: String): CharArray? {
            calls += "load:$account"
            return values[account]?.copyOf()
        }

        override fun delete(account: String) {
            calls += "delete:$account"
            values.remove(account)?.fill('\u0000')
        }
    }
}
