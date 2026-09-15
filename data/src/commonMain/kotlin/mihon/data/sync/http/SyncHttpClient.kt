package mihon.data.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

class SyncHttpException(
    val code: Int? = null,
    message: String,
    val retryable: Boolean = false,
) : IllegalStateException(message)

data class SyncHttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    override fun toString(): String =
        "SyncHttpResponse(code=$code, headers=<redacted>, body=<redacted>, size=${body.size})"
}

/**
 * A narrow wrapper around the already configured application client. Its builder clone retains
 * proxy, TLS and DNS configuration while isolating cookies and disabling redirects for sync.
 */
class SyncHttpClient(
    productionClient: OkHttpClient,
    private val allowedHosts: Set<String>,
    private val maxBodyBytes: Long = 2L * 1024 * 1024,
) {
    private val client = productionClient.newBuilder().apply {
        // Sync credentials must never pass through source/application logging interceptors.
        // Proxy, DNS, TLS, dispatcher and connection-pool settings remain inherited.
        interceptors().clear()
        networkInterceptors().clear()
        cookieJar(CookieJar.NO_COOKIES)
        cache(null)
        followRedirects(false)
        followSslRedirects(false)
    }.build()

    suspend fun execute(request: Request): SyncHttpResponse {
        val url = request.url
        val host = url.host.lowercase()
        val localHttp = !url.isHttps && (host == "localhost" || host == "127.0.0.1" || host == "::1")
        require(url.isHttps || localHttp) { "sync endpoint must use HTTPS" }
        require(host in allowedHosts.map(String::lowercase).toSet()) { "sync endpoint host is not allowed" }
        val call = client.newCall(request.newBuilder().removeHeader("Cookie").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(
                            SyncHttpException(message = "sync network request failed", retryable = true),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            readResponse(it)
                        }
                        if (!continuation.isCancelled) continuation.resume(result)
                    } catch (error: CancellationException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    } catch (error: Exception) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWithException(
                                if (error is SyncHttpException) {
                                    error
                                } else {
                                    SyncHttpException(
                                        response.code,
                                        "sync response could not be read",
                                        retryable = error is IOException,
                                    )
                                },
                            )
                        }
                    }
                }
            })
        }
    }

    private fun readResponse(response: Response): SyncHttpResponse {
        val length = response.body.contentLength()
        if (length > maxBodyBytes) {
            throw SyncHttpException(response.code, "sync response exceeds limit")
        }
        val body = response.body.source().use { source ->
            val buffer = Buffer()
            var total = 0L
            while (true) {
                val read = source.read(buffer, min(8_192L, maxBodyBytes + 1 - total))
                if (read == -1L) break
                total += read
                if (total > maxBodyBytes) {
                    throw SyncHttpException(response.code, "sync response exceeds limit")
                }
            }
            buffer.readByteArray()
        }
        return SyncHttpResponse(
            code = response.code,
            headers = response.headers.toMap().mapKeys { (key, _) -> key.lowercase() },
            body = body,
        )
    }

    fun request(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: okhttp3.RequestBody? = null,
    ): Request = Request.Builder()
        .url(url)
        .method(method, body)
        .apply { headers.forEach { (key, value) -> header(key, value) } }
        .build()
}
