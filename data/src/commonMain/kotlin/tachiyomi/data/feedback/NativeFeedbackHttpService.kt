package tachiyomi.data.feedback

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import tachiyomi.domain.feedback.NativeFeedbackContent
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import tachiyomi.domain.feedback.NativeFeedbackPayload
import tachiyomi.domain.feedback.NativeFeedbackService
import tachiyomi.domain.feedback.NativeFeedbackSession
import tachiyomi.domain.feedback.NativeFeedbackVerification

class NativeFeedbackHttpService(
    private val productionClient: OkHttpClient,
    private val baseUrl: HttpUrl = ORIGIN.toHttpUrl(),
) : NativeFeedbackService {
    // Derive from the actual platform client, retaining its proxy, DNS, TLS and
    // route observers. Only private-feedback logging, cookies, cache and HTTP
    // redirects are disabled; other application callers retain their settings.
    private val client = productionClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).apply {
            interceptors().removeAll { it is HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is HttpLoggingInterceptor }
        }.build()

    override suspend fun createSession(contentHash: String): NativeFeedbackSession {
        if (!contentHash.matches(Regex("[0-9a-f]{64}"))) throw NativeFeedbackException(NativeFeedbackFailure.INVALID)
        val result =
            exchange("/api/native-session", buildJsonObject { put("contentHash", contentHash) }, expectedStatus = 201)
        return guarded {
            require(result.keys == setOf("sessionId", "secret", "expiresAt", "verificationUrl"))
            val id = result.string("sessionId")
            val secret = result.string("secret")
            val expiresAt = requireNotNull(result["expiresAt"]?.jsonPrimitive?.longOrNull)
            val verificationUrl = result.string("verificationUrl")
            require(UUID.matches(id) && SECRET.matches(secret) && expiresAt > 0)
            require(verificationUrl == "$ORIGIN/verify.html#$id")
            NativeFeedbackSession(id, secret, expiresAt, verificationUrl)
        }
    }

    override suspend fun pollSession(session: NativeFeedbackSession): NativeFeedbackVerification {
        if (!UUID.matches(session.sessionId) ||
            !SECRET.matches(session.secret)
        ) {
            throw NativeFeedbackException(NativeFeedbackFailure.INVALID)
        }
        val result = exchange("/api/native-session/${session.sessionId}", secret = session.secret)
        return guarded {
            require(result.keys == setOf("verified", "expiresAt"))
            val expiresAt = requireNotNull(result["expiresAt"]?.jsonPrimitive?.longOrNull)
            require(expiresAt == session.expiresAt)
            NativeFeedbackVerification(requireNotNull(result["verified"]?.jsonPrimitive?.booleanOrNull), expiresAt)
        }
    }

    override suspend fun submit(payload: NativeFeedbackPayload, session: NativeFeedbackSession): String {
        if (!NativeFeedbackContent.validPayload(payload) || !NativeFeedbackContent.validLog(payload.crashLog) ||
            NativeFeedbackContent.logBytes(payload.crashLog) > NativeFeedbackContent.MAX_LOG_BYTES ||
            !UUID.matches(payload.submissionId) ||
            !UUID.matches(session.sessionId) ||
            !SECRET.matches(session.secret)
        ) {
            throw NativeFeedbackException(NativeFeedbackFailure.INVALID)
        }
        val result = exchange(
            "/api/feedback",
            buildJsonObject {
                put("submissionId", payload.submissionId)
                put("title", payload.title)
                put("description", payload.description)
                put("summary", payload.summary)
                put("consent", true)
                put("nativeSessionId", session.sessionId)
                put("nativeSessionSecret", session.secret)
                if (payload.crashLog.isNotEmpty()) {
                    put("crashLog", NativeFeedbackContent.normalizeLog(payload.crashLog))
                    put("crashLogConsent", true)
                }
            },
            expectedStatus = 202,
        )
        return guarded {
            require(result.keys == setOf("status", "receipt") && result.string("status") == "received_pending")
            result.string("receipt").also { require(SECRET.matches(it)) }
        }
    }

    private suspend fun exchange(
        path: String,
        body: JsonObject? = null,
        secret: String? = null,
        expectedStatus: Int = 200,
    ): JsonObject = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(requireNotNull(baseUrl.resolve(path)))
                .cacheControl(CacheControl.Builder().noCache().noStore().build())
                .apply {
                    if (body !=
                        null
                    ) {
                        post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    }
                    if (secret != null) header("Authorization", "Bearer $secret")
                }.build()
            client.newCall(request).await().use { response ->
                if (response.code != expectedStatus) {
                    throw NativeFeedbackException(
                        when (response.code) {
                            400 -> NativeFeedbackFailure.INVALID
                            401, 403 -> NativeFeedbackFailure.EXPIRED
                            409 -> NativeFeedbackFailure.CONFLICT
                            429 -> NativeFeedbackFailure.RATE_LIMIT
                            else -> NativeFeedbackFailure.NETWORK
                        },
                    )
                }
                val buffer = Buffer()
                val source = response.body.source()
                while (buffer.size <= MAX_RESPONSE) {
                    if (source.read(buffer, MAX_RESPONSE + 1 - buffer.size) < 0) break
                }
                require(buffer.size <= MAX_RESPONSE)
                requireNotNull(Json.parseToJsonElement(buffer.readUtf8()) as? JsonObject)
            }
        } catch (
            cancelled: CancellationException,
        ) {
            throw cancelled
        } catch (
            error: NativeFeedbackException,
        ) {
            throw error
        } catch (error: Exception) {
            throw NativeFeedbackException(NativeFeedbackFailure.NETWORK)
        }
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        throw NativeFeedbackException(NativeFeedbackFailure.NETWORK)
    }
    private fun JsonObject.string(
        key: String,
    ): String = requireNotNull((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content)

    companion object {
        const val ORIGIN = "https://mihon-feedback.mihon-altaircardinal.workers.dev"
        private const val MAX_RESPONSE = 16L * 1024
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val SECRET = Regex("[A-Za-z0-9_-]{43}")
    }
}
