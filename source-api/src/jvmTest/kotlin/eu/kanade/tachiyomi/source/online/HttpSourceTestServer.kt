package eu.kanade.tachiyomi.source.online

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal actual class HttpSourceTestServer actual constructor() {
    private val server = MockWebServer().also { it.start() }
    private val cancellation = AtomicBoolean(false)

    actual val baseUrl = server.url("/").toString().removeSuffix("/")
    actual val client: OkHttpClient = OkHttpClient.Builder()
        .eventListener(
            object : EventListener() {
                override fun canceled(call: okhttp3.Call) {
                    cancellation.set(true)
                }
            },
        )
        .build()
    actual val requestCount: Int
        get() = server.requestCount
    private var capturedRange: String? = null
    actual val lastRange: String?
        get() = capturedRange
    actual val cancelled: Boolean
        get() = cancellation.get()

    actual fun enqueue(response: HttpTestResponse) {
        val builder = MockResponse.Builder()
            .code(response.code)
            .body(response.body)
        if (response.chunked) builder.chunkedBody(response.body, response.chunkSize)
        if (response.headersDelayMillis > 0L) {
            builder.headersDelay(response.headersDelayMillis, TimeUnit.MILLISECONDS)
        }
        if (response.throttleBytes != null) {
            builder.throttleBody(response.throttleBytes, response.throttlePeriodMillis, TimeUnit.MILLISECONDS)
        }
        server.enqueue(builder.build())
    }

    actual fun requestArrived(timeoutMillis: Long): Boolean {
        val request = server.takeRequest(timeoutMillis, TimeUnit.MILLISECONDS) ?: return false
        capturedRange = request.headers["Range"]
        return true
    }

    actual fun close() {
        server.close()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }
}
