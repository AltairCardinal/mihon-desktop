package mihon.data.sync.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mihon.data.sync.http.SyncHttpClient
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpFailureClass
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.http.SyncHttpResponse
import mihon.data.sync.http.requireSyncSuccess
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.auth.GitHubAuthFailure
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.auth.GitHubAuthPort
import mihon.domain.sync.auth.GitHubAuthWaiter
import mihon.domain.sync.auth.GitHubCredentialStore
import mihon.domain.sync.auth.GitHubDeviceAuthResult
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.auth.GitHubStoredCredential
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

private val authJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

class GitHubAuthClient(
    productionClient: OkHttpClient,
    private val endpoints: GitHubAuthEndpoints = GitHubAuthEndpoints(),
    private val waiter: GitHubAuthWaiter = GitHubAuthWaiter { delay(it) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val deviceCodeRequestTimeoutMillis: Long = 30_000,
    requestGate: SyncHttpRequestGate? = null,
) : GitHubAuthPort {
    private val http = SyncHttpClient(
        productionClient,
        setOfNotNull(
            endpoints.deviceCodeUrl.hostOrNull(),
            endpoints.accessTokenUrl.hostOrNull(),
            endpoints.apiBaseUrl.hostOrNull(),
        ),
        requestGate = requestGate,
    )

    override suspend fun authorize(
        clientId: String,
        onDeviceCode: suspend (GitHubDeviceCode) -> Unit,
    ): GitHubDeviceAuthResult {
        require(clientId.isNotBlank() && clientId.length <= 200) { "client id is invalid" }
        require(deviceCodeRequestTimeoutMillis > 0) { "device code request timeout is invalid" }
        val device = try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(deviceCodeRequestTimeoutMillis) { requestDeviceCode(clientId) }
            }
                ?: return GitHubDeviceAuthResult.Failed(
                    GitHubAuthFailure(GitHubAuthFailureReason.HTTP, "device code request timed out", true),
                )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncHttpException) {
            return GitHubDeviceAuthResult.Failed(httpFailure(error))
        } catch (error: Exception) {
            return malformed()
        }
        val deadline = expiresAt(nowMillis(), device.expiresInSeconds) ?: return malformed()
        onDeviceCode(device)
        var interval = device.intervalSeconds * 1_000
        var attempts = 0
        while (nowMillis() < deadline && attempts < MAX_POLL_ATTEMPTS) {
            val remaining = deadline - nowMillis()
            if (remaining <= 0) break
            waiter.waitMillis(minOf(interval, remaining))
            if (interval >= remaining || nowMillis() >= deadline) break
            attempts++
            val response = try {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(deadline - nowMillis()) {
                        http.execute(
                            http.request(
                                endpoints.accessTokenUrl,
                                "POST",
                                headers = mapOf("Accept" to "application/json"),
                                body = FormBody.Builder()
                                    .add("client_id", clientId)
                                    .add("device_code", device.deviceCode)
                                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                                    .build(),
                            ),
                        )
                    }
                } ?: break
            } catch (error: CancellationException) {
                throw error
            } catch (error: SyncHttpException) {
                return GitHubDeviceAuthResult.Failed(httpFailure(error))
            } catch (error: Exception) {
                return GitHubDeviceAuthResult.Failed(
                    GitHubAuthFailure(GitHubAuthFailureReason.HTTP, "authorization request failed", true),
                )
            }
            if (response.code !in 200..299) {
                return GitHubDeviceAuthResult.Failed(httpFailure(response))
            }
            val payload = response.parseObject() ?: return malformed()
            val errorElement = payload["error"]
            if (errorElement == null) {
                val token = payload.toAccessToken(nowMillis()) ?: return malformed()
                return GitHubDeviceAuthResult.Authorized(token)
            }
            val errorCode = payload.string("error") ?: return malformed()
            when (errorCode) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    val reported = if (payload["interval"] == null) {
                        0
                    } else {
                        payload.long("interval")?.takeIf { it in 1..MAX_POLL_INTERVAL_SECONDS }
                            ?: return malformed()
                    }
                    interval = maxOf(interval + 5_000, reported * 1_000)
                    if (interval > MAX_POLL_INTERVAL_MILLIS) break
                }
                "access_denied" ->
                    return GitHubDeviceAuthResult.Failed(
                        GitHubAuthFailure(GitHubAuthFailureReason.ACCESS_DENIED, "authorization denied", false),
                    )
                "expired_token" ->
                    return GitHubDeviceAuthResult.Failed(
                        GitHubAuthFailure(GitHubAuthFailureReason.EXPIRED, "device code expired", false),
                    )
                else ->
                    return GitHubDeviceAuthResult.Failed(
                        GitHubAuthFailure(GitHubAuthFailureReason.HTTP, "authorization failed", false),
                    )
            }
        }
        return GitHubDeviceAuthResult.Failed(
            GitHubAuthFailure(GitHubAuthFailureReason.EXPIRED, "device authorization timed out", false),
        )
    }

    override suspend fun refresh(
        clientId: String,
        current: GitHubStoredCredential,
    ): Result<GitHubStoredCredential> = try {
        require(clientId.isNotBlank() && clientId.length <= 200) { "client id is invalid" }
        val refreshToken = current.credential.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw GitHubAuthException(revokedFailure())
        if (current.credential.refreshTokenExpiresAtMillis?.let { it <= nowMillis() } == true) {
            throw GitHubAuthException(revokedFailure())
        }
        val response = http.execute(
            http.request(
                endpoints.accessTokenUrl,
                "POST",
                headers = mapOf("Accept" to "application/json"),
                body = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .build(),
            ),
        )
        if (response.code !in 200..299) throw GitHubAuthException(httpFailure(response))
        val payload = response.parseObject()
        if (payload?.get("error") != null) {
            val failure = when (payload.string("error")) {
                "bad_refresh_token", "invalid_grant", "expired_token" -> revokedFailure()
                else -> GitHubAuthFailure(GitHubAuthFailureReason.HTTP, "token refresh rejected", false)
            }
            throw GitHubAuthException(failure)
        }
        val token = payload?.toAccessToken(nowMillis())
            ?: throw GitHubAuthException(malformed().failure)
        if (token.refreshToken == null || token.refreshTokenExpiresAtMillis == null ||
            current.revision == Long.MAX_VALUE
        ) {
            throw GitHubAuthException(malformed().failure)
        }
        Result.success(
            GitHubStoredCredential(
                token,
                current.revision + 1,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: GitHubAuthException) {
        Result.failure(error)
    } catch (error: SyncHttpException) {
        Result.failure(GitHubAuthException(httpFailure(error)))
    } catch (error: Exception) {
        Result.failure(IllegalStateException("token refresh failed"))
    }

    private suspend fun requestDeviceCode(clientId: String): GitHubDeviceCode {
        val response = http.execute(
            http.request(
                endpoints.deviceCodeUrl,
                "POST",
                headers = mapOf("Accept" to "application/json"),
                body = FormBody.Builder().add("client_id", clientId)
                    .build(),
            ),
        )
        response.requireSyncSuccess()
        val payload = response.parseObject() ?: throw IllegalStateException("device authorization response malformed")
        require(payload["error"] == null) { "device authorization failed" }
        val verificationUri = payload.string("verification_uri")
            ?: payload.string("verification_uri_complete")
            ?: throw IllegalStateException("authorization response missing field")
        require(isGitHubVerificationUri(verificationUri)) { "verification URI is not allowed" }
        val expiresIn = payload.requiredLong("expires_in")
        require(expiresIn in 1..MAX_DEVICE_LIFETIME_SECONDS) { "device lifetime is invalid" }
        val interval = if (payload["interval"] == null) 5L else payload.requiredLong("interval")
        require(interval in 1..MAX_POLL_INTERVAL_SECONDS) { "poll interval is invalid" }
        return GitHubDeviceCode(
            deviceCode = payload.requiredString("device_code"),
            userCode = payload.requiredString("user_code"),
            verificationUri = verificationUri,
            expiresInSeconds = expiresIn,
            intervalSeconds = interval,
        )
    }

    private fun malformed() = GitHubDeviceAuthResult.Failed(
        GitHubAuthFailure(GitHubAuthFailureReason.MALFORMED_RESPONSE, "authorization response malformed", false),
    )

    private fun httpFailure(response: SyncHttpResponse): GitHubAuthFailure = try {
        response.requireSyncSuccess()
        GitHubAuthFailure(GitHubAuthFailureReason.HTTP, "authorization HTTP request failed", false)
    } catch (error: SyncHttpException) {
        httpFailure(error)
    }

    private fun httpFailure(error: SyncHttpException): GitHubAuthFailure =
        GitHubAuthFailure(
            when {
                error.code == 401 -> GitHubAuthFailureReason.REVOKED
                error.failureClass == SyncHttpFailureClass.RATE_LIMITED || error.code == 429 ->
                    GitHubAuthFailureReason.RATE_LIMITED
                error.code == 403 && error.failureClass == SyncHttpFailureClass.AUTHORIZATION ->
                    GitHubAuthFailureReason.PERMISSION_DENIED
                else -> GitHubAuthFailureReason.HTTP
            },
            "authorization HTTP request failed",
            error.retryable,
            error.retryAfterMillis,
        )

    private fun revokedFailure() = GitHubAuthFailure(GitHubAuthFailureReason.REVOKED, "authorization revoked", false)
}

class InMemoryGitHubCredentialStore(initial: GitHubStoredCredential? = null) : GitHubCredentialStore {
    private val mutex = Mutex()
    private var value = initial

    override suspend fun read(): GitHubStoredCredential? = mutex.withLock { value }

    override suspend fun replace(
        expectedRevision: Long?,
        value: GitHubAccessToken,
    ): GitHubStoredCredential = mutex.withLock {
        if (expectedRevision == null && this.value != null) {
            throw IllegalStateException("credential already exists")
        }
        if (expectedRevision != null && this.value?.revision != expectedRevision) {
            throw IllegalStateException("credential revision changed")
        }
        val revision = this.value?.revision ?: 0
        check(revision < Long.MAX_VALUE) { "credential revision exhausted" }
        GitHubStoredCredential(value, revision + 1).also { this.value = it }
    }
}

class GitHubTokenRefresher(
    private val auth: GitHubAuthPort,
    private val store: GitHubCredentialStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    suspend fun refreshIfNeeded(clientId: String): Result<GitHubStoredCredential> = mutex.withLock {
        val current = try {
            store.read() ?: return@withLock Result.failure(IllegalStateException("authorization required"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return@withLock Result.failure(IllegalStateException("credential storage failed"))
        }
        val expiresAt = current.credential.accessTokenExpiresAtMillis
        val now = nowMillis()
        if (expiresAt == null || (expiresAt > now && expiresAt - now > 60_000)) return@withLock Result.success(current)
        val result = auth.refresh(clientId, current)
        if (result.isFailure) return@withLock result
        try {
            Result.success(store.replace(current.revision, result.getOrThrow().credential))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(IllegalStateException("credential storage failed"))
        }
    }
}

private fun String.hostOrNull(): String? = runCatching { toHttpUrl().host.lowercase() }.getOrNull()

private fun SyncHttpResponse.parseObject(): JsonObject? =
    runCatching { authJson.parseToJsonElement(body.decodeToString()) as? JsonObject }.getOrNull()

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

private fun JsonObject.requiredString(name: String): String = string(name)?.takeIf { it.isNotBlank() }
    ?: throw IllegalStateException("authorization response missing field")

private fun JsonObject.requiredLong(name: String): Long = long(name)
    ?: throw IllegalStateException("authorization response missing field")

private fun JsonObject.toAccessToken(nowMillis: Long): GitHubAccessToken? {
    val access = string("access_token")?.takeIf { it.isNotBlank() } ?: return null
    val tokenType = string("token_type")?.takeIf { it.equals("bearer", ignoreCase = true) } ?: return null
    val accessLifetime = if (this["expires_in"] == null) null else optionalLifetime("expires_in") ?: return null
    val refreshLifetime = if (this["refresh_token_expires_in"] == null) {
        null
    } else {
        optionalLifetime("refresh_token_expires_in") ?: return null
    }
    val refreshToken = if (this["refresh_token"] == null) {
        null
    } else {
        string("refresh_token")?.takeIf { it.isNotBlank() } ?: return null
    }
    if ((refreshToken == null) != (refreshLifetime == null)) return null
    if (refreshToken != null && accessLifetime == null) return null
    val scope = if (this["scope"] == null) "" else string("scope") ?: return null
    val accessExpiry = accessLifetime?.let { expiresAt(nowMillis, it) ?: return null }
    val refreshExpiry = refreshLifetime?.let { expiresAt(nowMillis, it) ?: return null }
    return GitHubAccessToken(
        accessToken = access,
        refreshToken = refreshToken,
        tokenType = tokenType,
        scope = scope.split(' ').filter(String::isNotBlank).toSet(),
        accessTokenExpiresAtMillis = accessExpiry,
        refreshTokenExpiresAtMillis = refreshExpiry,
    )
}

private fun JsonObject.optionalLifetime(name: String): Long? {
    if (this[name] == null) return null
    val value = long(name) ?: return null
    return value.takeIf { it in 1..MAX_TOKEN_LIFETIME_SECONDS }
}

private fun isGitHubVerificationUri(value: String): Boolean =
    runCatching {
        val url = value.toHttpUrl()
        url.isHttps && url.host.equals("github.com", ignoreCase = true) &&
            url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/login/device"
    }.getOrDefault(false)

private fun expiresAt(nowMillis: Long, seconds: Long): Long? {
    val duration = seconds * 1_000
    return if (nowMillis > Long.MAX_VALUE - duration) null else nowMillis + duration
}

private const val MAX_DEVICE_LIFETIME_SECONDS = 86_400L
private const val MAX_POLL_INTERVAL_SECONDS = 3_600L
private const val MAX_POLL_INTERVAL_MILLIS = MAX_POLL_INTERVAL_SECONDS * 1_000
private const val MAX_POLL_ATTEMPTS = 3_600
private const val MAX_TOKEN_LIFETIME_SECONDS = 86_400L * 365 * 10
