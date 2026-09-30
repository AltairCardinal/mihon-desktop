package mihon.data.sync.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubCredentialStore
import mihon.domain.sync.auth.GitHubStoredCredential
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.security.SyncSecureStoreException

class PersistentGitHubCredentialStore(private val store: SyncSecureStore) : GitHubCredentialStore {
    private val mutableAuthorizationEpoch = MutableStateFlow(0L)

    /** Process-local authorization identity, independent of token-refresh revisions. */
    val authorizationEpoch: StateFlow<Long> = mutableAuthorizationEpoch
    override suspend fun read(): GitHubStoredCredential? = safe {
        store.read(KEY)?.let(::decode)?.let { record ->
            record.token?.let { GitHubStoredCredential(it.value(), record.revision) }
        }
    }

    override suspend fun replace(expectedRevision: Long?, value: GitHubAccessToken): GitHubStoredCredential =
        replace(expectedRevision, value, explicitAuthorization = true)

    /** Only the runtime's refresher adapter uses this; the persistent revision/CAS remain identical. */
    internal suspend fun replaceRefreshed(expectedRevision: Long?, value: GitHubAccessToken): GitHubStoredCredential =
        replace(expectedRevision, value, explicitAuthorization = false)

    private suspend fun replace(
        expectedRevision: Long?,
        value: GitHubAccessToken,
        explicitAuthorization: Boolean,
    ): GitHubStoredCredential = safe {
        val encoded = store.read(KEY)
        val current = encoded?.let(::decode)
        check(
            if (expectedRevision ==
                null
            ) {
                current?.token == null
            } else {
                current?.token != null && current.revision == expectedRevision
            },
        )
        val revision = nextRevision(current)
        val replacement = Record(revision = revision, token = Token(value))
        validate(replacement)
        check(store.compareAndSet(KEY, encoded, json.encodeToString(replacement)))
        if (explicitAuthorization) mutableAuthorizationEpoch.update { it + 1 }
        GitHubStoredCredential(value, revision)
    }

    /** Retains a revision tombstone so a stale refresh cannot replace a subsequently authorized account. */
    suspend fun clear(): Unit = safe {
        repeat(8) {
            val encoded = store.read(KEY) ?: return@safe
            val current = decode(encoded)
            if (current.token == null) return@safe
            val tombstone = Record(revision = nextRevision(current), token = null)
            if (store.compareAndSet(KEY, encoded, json.encodeToString(tombstone))) {
                mutableAuthorizationEpoch.update { it + 1 }
                return@safe
            }
        }
        throw SyncSecureStoreException()
    }

    private fun nextRevision(current: Record?): Long {
        val revision = current?.revision ?: 0
        check(revision < Long.MAX_VALUE)
        return revision + 1
    }

    private fun decode(value: String): Record = json.decodeFromString<Record>(value).also(::validate)

    private fun validate(record: Record) {
        check(record.version == 1 && record.revision > 0)
        record.token?.let {
            check(it.accessToken.isNotBlank() && it.tokenType.equals("bearer", ignoreCase = true))
            check(it.refreshToken == null || it.refreshToken.isNotBlank())
            check(it.scope.all(String::isNotBlank))
            check(it.accessExpiry == null || it.accessExpiry >= 0)
            check(it.refreshExpiry == null || it.refreshExpiry >= 0)
        }
    }

    private suspend fun <T> safe(operation: suspend () -> T): T = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw SyncSecureStoreException()
    }

    override fun toString(): String = "PersistentGitHubCredentialStore(<redacted>)"

    @Serializable
    private data class Record(val version: Int = 1, val revision: Long, val token: Token?)

    @Serializable
    private data class Token(
        val accessToken: String,
        val refreshToken: String?,
        val tokenType: String,
        val scope: Set<String>,
        val accessExpiry: Long?,
        val refreshExpiry: Long?,
    ) {
        constructor(value: GitHubAccessToken) : this(
            value.accessToken,
            value.refreshToken,
            value.tokenType,
            value.scope,
            value.accessTokenExpiresAtMillis,
            value.refreshTokenExpiresAtMillis,
        )
        fun value() = GitHubAccessToken(accessToken, refreshToken, tokenType, scope, accessExpiry, refreshExpiry)
        override fun toString(): String = "Token(<redacted>)"
    }

    companion object {
        private const val KEY = "github-auth"
        private val json = Json { encodeDefaults = true }
    }
}
