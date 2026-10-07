package mihon.data.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import mihon.domain.sync.runtime.SyncNetworkFailurePhase
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
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

open class SyncHttpException(
    val code: Int? = null,
    message: String,
    val retryable: Boolean = false,
    val failureClass: SyncHttpFailureClass = SyncHttpFailureClass.UNKNOWN,
    val retryAfterMillis: Long? = null,
    val rateLimitResetEpochSeconds: Long? = null,
    val networkPhase: mihon.domain.sync.runtime.SyncNetworkFailurePhase? = null,
) : IllegalStateException(message)

internal enum class SyncRequiredResource { REPOSITORY, SPACE_DATA }

internal class SyncRequiredResourceUnavailable(val resource: SyncRequiredResource, code: Int = 404) :
    SyncHttpException(code, "required sync resource is unavailable")

/** Applies the same status/rate-limit distinction to onboarding account and repository reads. */
internal fun SyncHttpResponse.requireSyncSuccess(): SyncHttpResponse {
    if (code in 200..299) return this
    val now = System.currentTimeMillis()
    val message = body.decodeToString().lowercase()
    val secondaryLimit = message.contains("secondary rate") || message.contains("abuse detection")
    val limited = code == 429 || (
        code == 403 && (
            headers["x-ratelimit-remaining"] == "0" || headers["retry-after"] != null || secondaryLimit
            )
        )
    val failure = when {
        limited -> SyncHttpFailureClass.RATE_LIMITED
        code == 401 || code == 403 -> SyncHttpFailureClass.AUTHORIZATION
        code >= 500 -> SyncHttpFailureClass.SERVER
        else -> SyncHttpFailureClass.UNKNOWN
    }
    throw SyncHttpException(
        code,
        "GitHub sync request failed",
        limited || code >= 500,
        failure,
        retryAfterMillis = rateLimitNotBeforeMillis(now)?.let { (it - now).coerceAtLeast(0) },
        networkPhase = SyncNetworkFailurePhase.HTTP_RESPONSE,
    )
}

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
    val retryAfterHint = headers["retry-after"] != null
    if (code != 429 && !(code == 403 && (primaryExhausted || secondary || retryAfterHint))) return null

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

enum class SyncHttpBodyDirection { UPLOAD, DOWNLOAD }

/** Stable identity of a logical body across transport retries; scoped by the caller's work round. */
data class SyncHttpBodyWork(val key: String)

/** In-memory observations of application HTTP bodies, never headers or TLS traffic. */
interface SyncHttpBodyObserver {
    fun onBodyBytes(direction: SyncHttpBodyDirection, requestId: Long, bytes: Long, total: Long?)

    fun onBodyWorkBytes(
        direction: SyncHttpBodyDirection,
        requestId: Long,
        bytes: Long,
        total: Long?,
        workKey: String?,
    ) = onBodyBytes(direction, requestId, bytes, total)

    fun onBodyComplete(direction: SyncHttpBodyDirection, requestId: Long, successful: Boolean) = Unit
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
    private val bodyObserver: SyncHttpBodyObserver? = null,
) {
    private val nextRequestId = AtomicLong()
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
        val requestId = nextRequestId.incrementAndGet()
        val workKey = request.tag(SyncHttpBodyWork::class.java)?.key
        val observedBody = request.body?.takeIf { bodyObserver != null }?.let { body ->
            object : RequestBody() {
                override fun contentType() = body.contentType()

                override fun contentLength() = body.contentLength()

                override fun isDuplex() = body.isDuplex()

                override fun isOneShot() = body.isOneShot()

                override fun writeTo(sink: BufferedSink) {
                    var sent = 0L
                    val total = body.contentLength().takeIf { it >= 0L }
                    val observedSink = object : ForwardingSink(sink) {
                        override fun write(source: Buffer, byteCount: Long) {
                            super.write(source, byteCount)
                            sent += byteCount
                            runCatching {
                                bodyObserver?.onBodyWorkBytes(
                                    SyncHttpBodyDirection.UPLOAD,
                                    requestId,
                                    sent,
                                    total,
                                    workKey,
                                )
                            }
                        }
                    }.buffer()
                    var successful = false
                    try {
                        body.writeTo(observedSink)
                        observedSink.flush()
                        successful = true
                    } finally {
                        runCatching {
                            bodyObserver?.onBodyComplete(SyncHttpBodyDirection.UPLOAD, requestId, successful)
                        }
                    }
                }
            }
        }
        val call = client.newCall(
            request.newBuilder().removeHeader("Cookie").method(request.method, observedBody ?: request.body).build(),
        )
        val result = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!call.isCanceled()) SyncFailureDiagnostics.record(SyncFailurePhase.HTTP_CALL, error)
                    metrics.recordHttp(0L, failed = true)
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(
                            SyncHttpException(
                                message = "sync network request failed",
                                retryable = true,
                                failureClass = SyncHttpFailureClass.NETWORK,
                                networkPhase = networkFailurePhase(error),
                            ),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.code !in 200..299) {
                        SyncFailureDiagnostics.record(
                            SyncFailurePhase.HTTP_RESPONSE,
                            status = response.code,
                            kind = SyncFailureKind.HTTP,
                        )
                    }
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
                            readResponse(it, requestId, workKey)
                        }
                        metrics.recordHttp(result.body.size.toLong(), failed = result.code !in 200..299)
                        if (!continuation.isCancelled) continuation.resume(result)
                    } catch (error: CancellationException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    } catch (error: Exception) {
                        SyncFailureDiagnostics.record(SyncFailurePhase.HTTP_BODY, error, response.code)
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
                                        failureClass = if (error is IOException) {
                                            SyncHttpFailureClass.NETWORK
                                        } else {
                                            SyncHttpFailureClass.UNKNOWN
                                        },
                                        networkPhase = SyncNetworkFailurePhase.HTTP_BODY,
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

    private fun readResponse(response: Response, requestId: Long, workKey: String?): SyncHttpResponse {
        val length = response.body.contentLength()
        if (length > maxBodyBytes) {
            throw SyncHttpException(response.code, "sync response exceeds limit")
        }
        var successful = false
        val body = try {
            response.body.source().use { source ->
                val buffer = Buffer()
                var total = 0L
                while (true) {
                    val read = source.read(buffer, min(8_192L, maxBodyBytes + 1 - total))
                    if (read == -1L) break
                    total += read
                    if (total > maxBodyBytes) {
                        throw SyncHttpException(response.code, "sync response exceeds limit")
                    }
                    runCatching {
                        bodyObserver?.onBodyWorkBytes(
                            SyncHttpBodyDirection.DOWNLOAD,
                            requestId,
                            total,
                            length.takeIf { it >= 0L },
                            workKey,
                        )
                    }
                }
                successful = true
                buffer.readByteArray()
            }
        } finally {
            runCatching { bodyObserver?.onBodyComplete(SyncHttpBodyDirection.DOWNLOAD, requestId, successful) }
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

private fun networkFailurePhase(error: Throwable): SyncNetworkFailurePhase {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = error
    repeat(8) {
        val cause = current?.takeIf { visited.add(it) } ?: return SyncNetworkFailurePhase.UNKNOWN
        when {
            cause is java.net.UnknownHostException -> return SyncNetworkFailurePhase.DNS
            cause is javax.net.ssl.SSLException -> return SyncNetworkFailurePhase.TLS
            cause is java.io.InterruptedIOException -> return SyncNetworkFailurePhase.TIMEOUT
            cause is java.net.ConnectException -> return SyncNetworkFailurePhase.CONNECT
            cause is IOException && (
                cause.message?.startsWith("Unexpected response code for CONNECT:") == true ||
                    cause.message == "Failed to authenticate with proxy" ||
                    cause.message?.startsWith("SOCKS:") == true
                ) -> return SyncNetworkFailurePhase.PROXY_HANDSHAKE
        }
        current = cause.cause
    }
    return SyncNetworkFailurePhase.UNKNOWN
}
