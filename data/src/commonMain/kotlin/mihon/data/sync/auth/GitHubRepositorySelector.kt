package mihon.data.sync.auth

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.data.sync.http.SyncHttpClient
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.http.SyncHttpResponse
import mihon.domain.sync.transport.SyncRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody

data class GitHubPrivateRepository(
    val repository: SyncRepository,
    val installationId: Long,
)

class GitHubPrivateRepositorySelector(
    productionClient: OkHttpClient,
    private val accessToken: suspend () -> String,
    apiBaseUrl: String = "https://api.github.com",
    requestGate: SyncHttpRequestGate? = null,
) {
    private val baseUrl = apiBaseUrl.trimEnd('/')
    private val http = SyncHttpClient(
        productionClient,
        setOf(baseUrl.hostOrNull() ?: "api.github.com"),
        requestGate = requestGate,
    )

    internal suspend fun objects(path: String, field: String): List<JsonObject> =
        collectPages("$baseUrl$path") { response -> response.json().array(field).map { it.jsonObject } }.flatten()

    internal suspend fun requestPath(
        path: String,
        method: String = "GET",
        body: RequestBody? = null,
    ): SyncHttpResponse = request("$baseUrl$path", method, body)

    suspend fun select(
        branch: String,
        maxRepositories: Int = 200,
    ): List<GitHubPrivateRepository> {
        require(maxRepositories in 1..1_000) { "repository selection limit is invalid" }
        val installations = collectPages("$baseUrl/user/installations?per_page=100&page=1") { page ->
            page.json().array("installations").mapNotNull { item -> item.jsonObject.long("id") }
        }.flatten().distinct()
        val selected = linkedMapOf<String, GitHubPrivateRepository>()
        for (installation in installations) {
            val pages = collectPages(
                "$baseUrl/user/installations/$installation/repositories?per_page=100&page=1",
            ) { page ->
                page.json().array("repositories").mapNotNull { item ->
                    val repo = item.jsonObject
                    val private = repo.boolean("private")
                    val permissions = repo["permissions"]?.jsonObject
                    val writable = permissions?.boolean("push") == true ||
                        permissions?.boolean("admin") == true || permissions?.boolean("maintain") == true
                    val fullName = repo["full_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!private || !writable) return@mapNotNull null
                    val pieces = fullName.split('/', limit = 2)
                    if (pieces.size != 2) return@mapNotNull null
                    GitHubPrivateRepository(SyncRepository(pieces[0], pieces[1], branch), installation)
                }
            }.flatten()
            pages.forEach { selected.putIfAbsent(it.repository.fullName, it) }
            if (selected.size >= maxRepositories) break
        }
        return selected.values.take(maxRepositories)
    }

    private suspend fun <T> collectPages(
        firstUrl: String,
        read: (SyncHttpResponse) -> List<T>,
    ): List<List<T>> {
        val pages = mutableListOf<List<T>>()
        val visited = mutableSetOf<String>()
        var url: String? = firstUrl
        repeat(100) {
            val current = url ?: return pages
            require(visited.add(current)) { "GitHub pagination loop" }
            val response = request(current)
            if (response.code !in 200..299) {
                throw SyncHttpException(
                    response.code,
                    "GitHub repository request failed",
                    response.code == 429 || response.code >= 500 || response.headers["retry-after"] != null ||
                        response.headers["x-ratelimit-remaining"] == "0",
                )
            }
            pages += try {
                read(response)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IllegalStateException("GitHub repository response malformed")
            }
            url = nextUrl(response.headers["link"], current)
            if (url == null) return pages
        }
        throw IllegalStateException("GitHub pagination exceeds limit")
    }

    private suspend fun request(url: String, method: String = "GET", body: RequestBody? = null): SyncHttpResponse {
        val token = accessToken().takeIf { it.isNotBlank() } ?: throw IllegalStateException("authorization required")
        return http.execute(
            http.request(
                url,
                method,
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "Authorization" to "Bearer $token",
                    "X-GitHub-Api-Version" to "2026-03-10",
                ),
                body = body,
            ),
        )
    }

    private fun nextUrl(link: String?, current: String): String? {
        val next = link.orEmpty().split(',').filter { it.contains("rel=\"next\"") }
        if (next.isEmpty()) return null
        require(next.size == 1) { "GitHub pagination link is ambiguous" }
        val candidate = Regex("<([^>]+)>").find(next.single())?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("GitHub pagination link is invalid")
        val parsed = runCatching { candidate.toHttpUrl() }.getOrNull()
            ?: throw IllegalStateException("GitHub pagination link is invalid")
        val currentUrl = current.toHttpUrl()
        require(
            parsed.scheme == currentUrl.scheme && parsed.host == currentUrl.host &&
                parsed.port == currentUrl.port && parsed.username.isEmpty() && parsed.password.isEmpty(),
        ) {
            "GitHub pagination link host is invalid"
        }
        require(parsed.encodedPath == currentUrl.encodedPath && parsed.fragment == null) {
            "GitHub pagination path is invalid"
        }
        return parsed.toString()
    }
}

private val githubSelectionJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

private fun SyncHttpResponse.json(): JsonObject =
    runCatching { githubSelectionJson.parseToJsonElement(body.decodeToString()).jsonObject }
        .getOrElse { throw IllegalStateException("GitHub repository response malformed") }

private fun JsonObject.array(name: String) = this[name] as? JsonArray
    ?: throw IllegalStateException("GitHub repository response missing field")

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

private fun String.hostOrNull(): String? = runCatching { toHttpUrl().host.lowercase() }.getOrNull()
