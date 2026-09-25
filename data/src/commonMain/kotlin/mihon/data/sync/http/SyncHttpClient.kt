package mihon.data.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

enum class SyncHttpFailureClass {
    NETWORK,
    AUTHORIZATION,
    RATE_LIMITED,
    CONFLICT,
    INVALID_REQUEST,
    SERVER,
    UNKNOWN,
}

class SyncHttpException(
    val code: Int? = null,
    message: String,
    val retryable: Boolean = false,
    val failureClass: SyncHttpFailureClass = SyncHttpFailureClass.UNKNOWN,
    val retryAfterMillis: Long? = null,
    val rateLimitResetEpochSeconds: Long? = null,
) : IllegalStateException(message)

data class SyncHttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    override fun toString(): String =
        "SyncHttpResponse(code=$code, headers=<redacted>, body=<redacted>, size=${body.size})"
}

/** Returns the latest applicable GitHub rate-limit deadline, if this response is rate-limited. */
internal fun SyncHttpResponse.rateLimitNotBeforeMillis(nowMillis: Long): Long? {
    val message = body.decodeToString().lowercase()
    val secondary = message.contains("secondary rate") || message.contains("abuse detection")
    val primaryExhausted = headers["x-ratelimit-remaining"] == "0"
    if (code != 429 && !(code == 403 && (primaryExhausted || secondary))) return null

    val retryAfterDeadline = headers["retry-after"]?.let { value ->
        value.trim().toLongOrNull()?.let { seconds ->
            seconds.coerceAtLeast(0L).saturatedMultiply(1_000L).saturatedAdd(nowMillis)
        } ?: runCatching {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }
    val resetDeadline = if (primaryExhausted) {
        headers["x-ratelimit-reset"]?.toLongOrNull()?.takeIf { it > 0L }?.saturatedMultiply(1_000L)
    } else {
        null
    }
    val secondaryFallback = if ((secondary || code == 429) && retryAfterDeadline == null) {
        nowMillis.saturatedAdd(60_000L)
    } else {
        null
    }
    return listOfNotNull(retryAfterDeadline, resetDeadline, secondaryFallback).maxOrNull()
}

private fun Long.saturatedMultiply(multiplier: Long): Long =
    if (this > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else this * multiplier

private fun Long.saturatedAdd(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

/** Account-scoped admission and response observation for persisted GitHub rate limits. */
interface SyncHttpRequestGate {
    suspend fun beforeRequest()

    suspend fun afterResponse(response: SyncHttpResponse)
}

/**
 * A narrow wrapper around the already configured application client. Its builder clone retains
 * proxy, TLS and DNS configuration while isolating cookies and disabling redirects for sync.
 */
class SyncHttpClient(
    productionClient: OkHttpClient,
    private val allowedHosts: Set<String>,
    private val maxBodyBytes: Long = 2L * 1024 * 1024,
    private val metrics: SyncMetrics = NoopSyncMetrics,
    private val requestGate: SyncHttpRequestGate? = null,
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
        requestGate?.beforeRequest()
        val call = client.newCall(request.newBuilder().removeHeader("Cookie").build())
        val result = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    metrics.recordHttp(0L, failed = true)
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(
                            SyncHttpException(message = "sync network request failed", retryable = true),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val headers = it.headers.toMap().mapKeys { (key, _) -> key.lowercase() }
                            // Persist a throttle known from status and headers before streaming
                            // its body. The continuation remains cancellable while readResponse runs.
                            if (it.code == 429 || (it.code == 403 && headers["x-ratelimit-remaining"] == "0")) {
                                runBlocking {
                                    requestGate?.afterResponse(SyncHttpResponse(it.code, headers, ByteArray(0)))
                                }
                            }
                            readResponse(it)
                        }
                        metrics.recordHttp(result.body.size.toLong(), failed = result.code !in 200..299)
                        if (!continuation.isCancelled) continuation.resume(result)
                    } catch (error: CancellationException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    } catch (error: Exception) {
                        metrics.recordHttp(0L, failed = true)
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
        requestGate?.afterResponse(result)
        return result
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
