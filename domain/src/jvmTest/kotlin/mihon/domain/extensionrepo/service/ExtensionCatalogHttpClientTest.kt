package mihon.domain.extensionrepo.service

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExtensionCatalogHttpClientTest {
    @Test
    fun `chunked catalog cannot bypass the streaming size limit`() {
        MockWebServer().also { it.start() }.use { server ->
            val body = Buffer()
            val block = ByteArray(8192)
            repeat(8193) { body.write(block) }
            server.enqueue(MockResponse.Builder().chunkedBody(body, 8192).build())
            val client = OkHttpClient().withCatalogRedirectPolicy()

            assertThrows(InvalidCatalogRequestException::class.java) {
                client.newCall(Request.Builder().url(server.url("/catalog.json")).build()).execute().use {
                    val discard = Buffer()
                    while (it.body.source().read(discard, 8192) != -1L) discard.clear()
                }
            }
        }
    }

    @Test
    fun `oversized catalog headers are rejected before buffering the body`() {
        MockWebServer().also { it.start() }.use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .setHeader("Content-Length", (64L * 1024 * 1024 + 1).toString())
                    .build(),
            )
            val client = OkHttpClient().withCatalogRedirectPolicy()

            assertThrows(InvalidCatalogRequestException::class.java) {
                client.newCall(Request.Builder().url(server.url("/catalog.json")).build()).execute().use { }
            }
        }
    }
}
