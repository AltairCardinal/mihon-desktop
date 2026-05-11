package mihon.desktop.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Result returned by [FlareSolverrClient.solve].
 */
data class FlareSolverrResult(
    val userAgent: String,
    val cookies: List<FlareSolverrCookie>,
)

/**
 * A single cookie from the FlareSolverr response.
 */
data class FlareSolverrCookie(
    val name: String,
    val value: String,
    val domain: String,
)

/**
 * HTTP client for the FlareSolverr proxy service.
 *
 * Submits a `request.get` command to the FlareSolverr `/v1` endpoint and returns
 * the solved cookies and User-Agent, or null if the request cannot be completed.
 *
 * @param flareSolverrUrl Base URL of the FlareSolverr instance (e.g. "http://localhost:8191").
 * @param client OkHttpClient to use for requests.
 */
class FlareSolverrClient(
    private val flareSolverrUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Asks FlareSolverr to solve a Cloudflare challenge for [url].
     *
     * @return [FlareSolverrResult] on success, null if FlareSolverr is unreachable,
     *         returns an error status, or the response body is malformed.
     */
    suspend fun solve(url: String): FlareSolverrResult? = withContext(Dispatchers.IO) {
        try {
            val bodyJson = """{"cmd":"request.get","url":"$url","maxTimeout":60000}"""
            val requestBody = bodyJson.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$flareSolverrUrl/v1")
                .post(requestBody)
                .build()

            val responseBody = client.newCall(request).execute().use { resp ->
                resp.body?.string()
            } ?: return@withContext null

            val parsed = json.decodeFromString<FlareSolverrResponse>(responseBody)
            if (parsed.status != "ok") return@withContext null

            val solution = parsed.solution ?: return@withContext null
            FlareSolverrResult(
                userAgent = solution.userAgent,
                cookies = solution.cookies.map { c ->
                    FlareSolverrCookie(name = c.name, value = c.value, domain = c.domain)
                },
            )
        } catch (_: Throwable) {
            null
        }
    }
}

// ── Internal serialization models ─────────────────────────────────────────────

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
)
