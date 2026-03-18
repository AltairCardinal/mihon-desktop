package mihon.desktop.platform

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DesktopNetworkHelperTest {

    @Test
    fun `client is configured with sensible defaults`() {
        val helper = DesktopNetworkHelper(cacheDir = createTempCacheDir())
        val client = helper.client
        assertNotNull(client)
        assertTrue(client is OkHttpClient)
    }

    @Test
    fun `client has connection timeout set`() {
        val helper = DesktopNetworkHelper(cacheDir = createTempCacheDir())
        assertTrue(helper.client.connectTimeoutMillis > 0)
    }

    @Test
    fun `client has read timeout set`() {
        val helper = DesktopNetworkHelper(cacheDir = createTempCacheDir())
        assertTrue(helper.client.readTimeoutMillis > 0)
    }

    @Test
    fun `client has cache configured`() {
        val cacheDir = createTempCacheDir()
        val helper = DesktopNetworkHelper(cacheDir = cacheDir)
        assertNotNull(helper.client.cache)
    }

    @Test
    fun `cookieJar is available`() {
        val helper = DesktopNetworkHelper(cacheDir = createTempCacheDir())
        assertNotNull(helper.cookieJar)
    }

    private fun createTempCacheDir(): File {
        return File(System.getProperty("java.io.tmpdir"), "mihon-test-cache-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }
    }
}
