package mihon.desktop.network

import mihon.domain.error.AppError
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull

class NetworkErrorContractTest {
    private val server = MockWebServer().also { it.start() }
    private val client = OkHttpClient()

    @AfterEach fun close() = server.close()

    @Test fun `success has no app error`() = assertNull(request(200, "{\"data\":[]}"))
    @Test fun `empty response is malformed`() = assertInstanceOf(AppError.MalformedData::class.java, request(200, ""))
    @Test fun `403 is authentication`() = assertInstanceOf(AppError.Authentication::class.java, request(403, "forbidden"))
    @Test fun `429 is retryable rate limit`() {
        val error = assertInstanceOf(AppError.RateLimited::class.java, request(429, "slow down", "12"))
        assertEquals(12, error.retryAfterSeconds)
    }
    @Test fun `500 is retryable server failure`() = assertInstanceOf(AppError.Server::class.java, request(500, "oops"))
    @Test fun `malformed payload is malformed`() = assertInstanceOf(AppError.MalformedData::class.java, request(200, "not-json"))

    private fun request(code: Int, body: String, retryAfter: String? = null): AppError? {
        server.enqueue(
            MockResponse(
                code = code,
                headers = retryAfter?.let { Headers.headersOf("Retry-After", it) } ?: Headers.EMPTY,
                body = body,
            ),
        )
        val response = client.newCall(Request.Builder().url(server.url("/data")).build()).execute()
        return NetworkErrorMapper.fromResponse(response) { payload ->
            if (!payload.trim().startsWith("{")) error("malformed")
        }
    }
}
