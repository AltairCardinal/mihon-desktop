package mihon.desktop.extension

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExtensionIconLoadingTest {
    @Test
    fun `icon is downloaded through extension API client`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val png = "PNG".encodeToByteArray()
            server.enqueue(MockResponse(headers = okhttp3.Headers.headersOf("Content-Type", "image/png"), body = "PNG"))
            val api = DesktopExtensionApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, FakeExtensionRepoRepository())

            assertArrayEquals(png, api.loadExtensionIcon(server.url("/icon.png").toString()))
        } finally {
            server.close()
        }
    }

    @Test
    fun `non-image icon response is rejected`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse(headers = okhttp3.Headers.headersOf("Content-Type", "text/html"), body = "not an image"))
            val api = DesktopExtensionApi(OkHttpClient(), Json, FakeExtensionRepoRepository())

            assertNull(api.loadExtensionIcon(server.url("/icon.png").toString()))
        } finally {
            server.close()
        }
    }
}
