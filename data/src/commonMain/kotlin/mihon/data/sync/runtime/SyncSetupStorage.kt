package mihon.data.sync.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.data.sync.auth.SyncCreationAttempt
import mihon.data.sync.auth.SyncGitHubAccount
import mihon.data.sync.transport.SyncSnapshotManifestBinding
import mihon.data.sync.transport.SyncSnapshotManifestCrypto
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.security.SyncSecureStoreException
import mihon.domain.sync.transport.SyncRepository
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest

internal class UnsupportedSyncSpace : IllegalStateException("sync space format is unsupported")

@Serializable
internal data class StoredSyncMaterial(val descriptor: String, val keyHex: String?) {
    fun material(): SyncSpaceMaterial {
        require(keyHex == null || keyHex.matches(Regex("[0-9a-f]{64}")))
        return SyncSpaceMaterial(
            SyncSpaceDescriptorCodec.decode(descriptor.encodeToByteArray()).getOrThrow(),
            keyHex?.let { SyncSecret.fromBytes(it.decodeHex().toByteArray()) },
        )
    }

    override fun toString(): String = "StoredSyncMaterial(<redacted>)"

    companion object {
        fun from(material: SyncSpaceMaterial) = StoredSyncMaterial(
            SyncSpaceDescriptorCodec.encode(material.descriptor).decodeToString(),
            material.secret?.bytes?.toByteString()?.hex(),
        )
    }
}

@Serializable
internal data class StoredSyncConnection(
    val version: Int = 2,
    val accountId: Long,
    val accountLogin: String,
    val repositoryId: Long,
    val owner: String,
    val repository: String,
    val branch: String,
    val material: StoredSyncMaterial,
    val actorId: String,
    val epoch: Long,
) {
    fun repository() = SyncRepository(owner, repository, branch)
    override fun toString(): String = "StoredSyncConnection(<redacted>)"
}

internal fun StoredSyncConnection.snapshotManifestBinding(): SyncSnapshotManifestBinding {
    val descriptorContext = SyncSnapshotManifestCrypto.sha256(material.descriptor.encodeToByteArray())
    val keyId = material.keyHex?.let { value ->
        SyncSnapshotManifestCrypto.sha256(value.decodeHex().toByteArray())
    } ?: "unprotected"
    val revision = listOf(
        "mihon-sync-connection-v1",
        accountId.toString(),
        repositoryId.toString(),
        owner,
        repository,
        branch,
        descriptorContext,
        keyId,
    ).joinToString("\u0000")
    return SyncSnapshotManifestBinding(
        accountId = accountId,
        repositoryId = repositoryId,
        connectionRevision = SyncSnapshotManifestCrypto.sha256(revision.encodeToByteArray()),
    )
}

@Serializable
internal data class StoredSyncSetup(
    val version: Int = 2,
    val accountId: Long,
    val accountLogin: String,
    val attemptId: String,
    val newSpace: Boolean,
    val material: StoredSyncMaterial,
    val submitted: Boolean = false,
    val repositoryId: Long? = null,
    val owner: String,
    val repository: String,
    val branch: String,
    val connected: Boolean = false,
) {
    fun account() = SyncGitHubAccount(accountId, accountLogin)
    fun repository() = SyncRepository(owner, repository, branch)
    fun attempt() = SyncCreationAttempt(account(), attemptId, submitted, repositoryId)
    override fun toString(): String = "StoredSyncSetup(<redacted>)"
}

/** Versioned records contain only verified data keys, never the user's password or a password-derived KEK. */
internal class SyncSetupStorage(private val secure: SyncSecureStore) {
    private val json = Json { encodeDefaults = true }

    suspend fun pending(accountId: Long): StoredSyncSetup? = secure.read(setupKey(accountId))?.let {
        decode<StoredSyncSetup>(it).also { value ->
            require(value.accountId == accountId && accountId > 0)
            require(value.attemptId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
            require(value.repositoryId == null || value.repositoryId > 0)
            require(!value.connected || value.repositoryId != null)
            value.repository()
            value.material.material()
        }
    }

    suspend fun save(value: StoredSyncSetup, expected: StoredSyncSetup?) {
        val key = setupKey(value.accountId)
        val before = secure.read(key)
        require(before?.let { decode<StoredSyncSetup>(it) } == expected) { "sync setup changed" }
        if (!secure.compareAndSet(key, before, json.encodeToString(value))) throw SyncSecureStoreException()
    }

    suspend fun clear(value: StoredSyncSetup) {
        val key = setupKey(value.accountId)
        val before = secure.read(key) ?: return
        if (decode<StoredSyncSetup>(before).attemptId != value.attemptId) return
        if (!secure.compareAndSet(key, before, null)) throw SyncSecureStoreException()
    }

    suspend fun connection(spaceId: String, generation: Long): StoredSyncConnection? =
        secure.read(connectionKey(spaceId, generation))?.let {
            decode<StoredSyncConnection>(it).also { value ->
                require(value.accountId > 0 && value.repositoryId > 0)
                require(value.actorId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && value.epoch > 0)
                val descriptor = value.material.material().descriptor
                require(descriptor.spaceId == spaceId && descriptor.generation == generation)
                value.repository()
            }
        }

    suspend fun bind(value: StoredSyncConnection, previous: StoredSyncConnection?) {
        val descriptor = value.material.material().descriptor
        val key = connectionKey(descriptor.spaceId, descriptor.generation)
        val before = secure.read(key)
        require(before?.let { decode<StoredSyncConnection>(it) } == previous) { "sync binding changed" }
        if (!secure.compareAndSet(key, before, json.encodeToString(value))) throw SyncSecureStoreException()
    }

    private inline fun <reified T> decode(value: String): T {
        require(value.length <= 64 * 1024) { "sync secure record exceeds limit" }
        val version = runCatching {
            Json.parseToJsonElement(value).jsonObject["version"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
        if (version != 2) throw UnsupportedSyncSpace()
        return try {
            json.decodeFromString<T>(value)
        } catch (_: Exception) {
            throw SyncSecureStoreException()
        }
    }

    private fun setupKey(accountId: Long) = "sync-setup-v2-$accountId"

    private fun connectionKey(spaceId: String, generation: Long): String =
        "space-" + MessageDigest.getInstance("SHA-256")
            .digest("$generation:$spaceId".toByteArray(Charsets.UTF_8)).toByteString().hex()
}
