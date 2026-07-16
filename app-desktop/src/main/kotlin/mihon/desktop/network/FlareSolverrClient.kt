package mihon.desktop.network

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class FlareSolverrResult(
    val userAgent: String,
    val cookies: List<FlareSolverrCookie>,
) {
    override fun toString(): String =
        "FlareSolverrResult(userAgentPresent=${userAgent.isNotBlank()}, cookieNames=${cookies.map { it.name }}, cookieCount=${cookies.size})"
}

data class FlareSolverrCookie(
    val name: String,
    val value: String,
    val domain: String,
    val hostOnly: Boolean = !domain.startsWith('.'),
    val path: String = "/",
    val expiresAt: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
) {
    override fun toString(): String =
        "FlareSolverrCookie(name=$name, domain=$domain, hostOnly=$hostOnly, path=$path, value=<redacted>)"
}

/** Explicit FlareSolverr fallback. It is never called by the network interceptor. */
class FlareSolverrClient(
    flareSolverrUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val endpoint = "${flareSolverrUrl.trimEnd('/')}/v1"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun solve(url: String): FlareSolverrResult? = try {
        val requestBody = json.encodeToString(
            FlareSolverrRequest(url = url),
        ).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(endpoint).post(requestBody).build()
        val responseBody = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        val parsed = json.decodeFromString<FlareSolverrResponse>(responseBody)
        if (parsed.status != "ok") return null
        val solution = parsed.solution ?: return null
        FlareSolverrResult(
            userAgent = solution.userAgent,
            cookies = solution.cookies.map { cookie ->
                FlareSolverrCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path.ifBlank { "/" },
                    expiresAt = cookie.expires?.takeIf { it > 0 }?.times(1_000)?.toLong(),
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                )
            },
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
private data class FlareSolverrRequest(
    val cmd: String = "request.get",
    val url: String,
    val maxTimeout: Long = 60_000,
)

@Serializable
private data class FlareSolverrResponse(
    val status: String,
    val solution: FlareSolverrSolution? = null,
)

@Serializable
private data class FlareSolverrSolution(
    @SerialName("userAgent") val userAgent: String = "",
    val cookies: List<FlareSolverrCookieJson> = emptyList(),
)

@Serializable
private data class FlareSolverrCookieJson(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expires: Double? = null,
    val secure: Boolean = false,
    @SerialName("httpOnly") val httpOnly: Boolean = false,
)
