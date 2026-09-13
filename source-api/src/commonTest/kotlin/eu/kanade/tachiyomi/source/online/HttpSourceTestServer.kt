package eu.kanade.tachiyomi.source.online

import okhttp3.OkHttpClient

internal data class HttpTestResponse(
    val code: Int = 200,
    val body: String = "",
    val chunked: Boolean = false,
    val chunkSize: Int = 0,
    val headersDelayMillis: Long = 0L,
    val throttleBytes: Long? = null,
    val throttlePeriodMillis: Long = 0L,
)

internal expect class HttpSourceTestServer() {
    val baseUrl: String
    val client: OkHttpClient
    val requestCount: Int
    val lastRange: String?
    val cancelled: Boolean

    fun enqueue(response: HttpTestResponse)
    fun requestArrived(timeoutMillis: Long): Boolean
    fun close()
}
