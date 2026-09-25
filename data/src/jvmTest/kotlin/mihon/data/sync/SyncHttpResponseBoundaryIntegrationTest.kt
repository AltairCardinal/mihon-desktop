package mihon.data.sync

import kotlinx.coroutines.runBlocking
import mihon.data.sync.http.SyncHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncHttpResponseBoundaryIntegrationTest {
    @Test
    fun `response byte limit accepts limit minus one and limit but rejects limit plus one`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val limit = 16
            val http = SyncHttpClient(OkHttpClient(), setOf(server.url("/").host), maxBodyBytes = limit.toLong())

            for (size in listOf(limit - 1, limit, limit + 1)) {
                val body = "x".repeat(size)
                server.enqueue(MockResponse(body = body))
                val result = runCatching { http.execute(http.request(server.url("/response").toString(), "GET")) }
                assertEquals(size > limit, result.isFailure, "unexpected result for a $size byte response")
                if (size <= limit) assertEquals(body, result.getOrThrow().body.decodeToString())
            }
        }
    }
}
