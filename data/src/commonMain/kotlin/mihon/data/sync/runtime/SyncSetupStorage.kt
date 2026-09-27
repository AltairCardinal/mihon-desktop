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
import mihon.domain.sync.transport.SyncInitializationStage
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

/** Setup and connection use the same durable scope; actor and epoch are intentionally absent from the binding. */
internal fun StoredSyncSetup.snapshotManifestBinding(): SyncSnapshotManifestBinding = StoredSyncConnection(
    accountId = accountId,
    accountLogin = accountLogin,
    repositoryId = requireNotNull(repositoryId),
    owner = owner,
    repository = repository,
    branch = branch,
    material = material,
    actorId = "",
    epoch = 1,
).snapshotManifestBinding()

@Serializable
internal data class StoredSyncSetup(
    val version: Int = 3,
    val accountId: Long,
    val accountLogin: String,
    val attemptId: String,
    val attemptNonce: String,
    val newSpace: Boolean,
    val material: StoredSyncMaterial,
    val stage: SyncInitializationStage,
    val repositoryId: Long,
    val owner: String,
    val repository: String,
    val branch: String,
    val defaultBranch: String? = null,
    val confirmedBootstrapCommitSha: String? = null,
    val confirmedBootstrapTreeSha: String? = null,
) {
    fun account() = SyncGitHubAccount(accountId, accountLogin)
    fun repository() = SyncRepository(owner, repository, branch)
    override fun toString(): String = "StoredSyncSetup(<redacted>)"
}

/** Kept separate so v2's submitted flag can never be interpreted as a v3 initialization stage. */
@Serializable
internal data class StoredLegacySyncSetup(
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
    override fun toString(): String = "StoredLegacySyncSetup(<redacted>)"
}

/** Versioned records contain only verified data keys, never the user's password or a password-derived KEK. */
internal class SyncSetupStorage(private val secure: SyncSecureStore) {
    private val json = Json { encodeDefaults = true }

    suspend fun pending(accountId: Long): StoredSyncSetup? = secure.read(setupKey(accountId))?.let {
        decode<StoredSyncSetup>(it, 3).also { value ->
            require(value.accountId == accountId && accountId > 0)
            require(value.attemptId.matches(ATTEMPT_PATTERN) && value.attemptNonce.matches(ATTEMPT_PATTERN))
            require(value.repositoryId > 0)
            val material = value.material.material()
            value.repository()
            require(!value.newSpace || !value.defaultBranch.isNullOrBlank())
            require(
                value.stage == SyncInitializationStage.SPACE_CONFIRMED ||
                    value.stage == SyncInitializationStage.CONNECTED ||
                    value.newSpace,
            )
            val hasCommit = value.confirmedBootstrapCommitSha != null
            val hasTree = value.confirmedBootstrapTreeSha != null
            require(hasCommit == hasTree)
            if (value.stage.ordinal >= SyncInitializationStage.BOOTSTRAP_CONFIRMED.ordinal && value.newSpace) {
                require(hasCommit && hasTree)
            }
            if (hasCommit) {
                require(value.confirmedBootstrapCommitSha?.matches(GIT_SHA_PATTERN) == true)
                require(value.confirmedBootstrapTreeSha?.matches(GIT_SHA_PATTERN) == true)
            }
            require(material.descriptor.spaceId.isNotBlank())
        }
    }

    suspend fun legacyPending(accountId: Long): StoredLegacySyncSetup? =
        secure.read(legacySetupKey(accountId))?.let {
            decode<StoredLegacySyncSetup>(it, 2).also { value ->
                require(value.accountId == accountId && accountId > 0)
                require(value.attemptId.matches(ATTEMPT_PATTERN))
                require(value.repositoryId == null || value.repositoryId > 0)
                require(!value.connected || value.repositoryId != null)
                value.repository()
                value.material.material()
            }
        }

    suspend fun save(value: StoredSyncSetup, expected: StoredSyncSetup?) {
        require(value.version == 3)
        val key = setupKey(value.accountId)
        val before = secure.read(key)
        require(before?.let { decode<StoredSyncSetup>(it, 3) } == expected) { "sync setup changed" }
        if (!secure.compareAndSet(key, before, json.encodeToString(value))) throw SyncSecureStoreException()
    }

    suspend fun clear(value: StoredSyncSetup) {
        val key = setupKey(value.accountId)
        val before = secure.read(key) ?: return
        if (decode<StoredSyncSetup>(before, 3) != value) return
        if (!secure.compareAndSet(key, before, null)) throw SyncSecureStoreException()
    }

    /** Removes legacy material only after a separate explicit user decision and an exact-value CAS. */
    suspend fun abandonLegacy(value: StoredLegacySyncSetup) {
        val key = legacySetupKey(value.accountId)
        val before = secure.read(key) ?: return
        if (decode<StoredLegacySyncSetup>(before, 2) != value) return
        if (!secure.compareAndSet(key, before, null)) throw SyncSecureStoreException()
    }

    /** Clears a migrated v2 join only after its exact repository binding completed successfully. */
    suspend fun clearMigratedLegacyJoin(value: StoredLegacySyncSetup, completed: StoredSyncSetup) {
        require(!value.newSpace && completed.accountId == value.accountId)
        require(value.repositoryId == completed.repositoryId)
        require(value.repository() == completed.repository())
        require(value.material == completed.material)
        clearLegacy(value)
    }

    suspend fun connection(spaceId: String, generation: Long): StoredSyncConnection? =
        secure.read(connectionKey(spaceId, generation))?.let {
            decode<StoredSyncConnection>(it, 2).also { value ->
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
        require(before?.let { decode<StoredSyncConnection>(it, 2) } == previous) { "sync binding changed" }
        if (!secure.compareAndSet(key, before, json.encodeToString(value))) throw SyncSecureStoreException()
    }

    private inline fun <reified T> decode(value: String, expectedVersion: Int): T {
        require(value.length <= 64 * 1024) { "sync secure record exceeds limit" }
        val version = runCatching {
            Json.parseToJsonElement(value).jsonObject["version"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
        if (version != expectedVersion) throw UnsupportedSyncSpace()
        return try {
            json.decodeFromString<T>(value)
        } catch (_: Exception) {
            throw SyncSecureStoreException()
        }
    }

    private fun setupKey(accountId: Long) = "sync-setup-v3-$accountId"

    private fun legacySetupKey(accountId: Long) = "sync-setup-v2-$accountId"

    private suspend fun clearLegacy(value: StoredLegacySyncSetup) {
        val key = legacySetupKey(value.accountId)
        val before = secure.read(key) ?: return
        if (decode<StoredLegacySyncSetup>(before, 2) != value) return
        if (!secure.compareAndSet(key, before, null)) throw SyncSecureStoreException()
    }

    private fun connectionKey(spaceId: String, generation: Long): String =
        "space-" + MessageDigest.getInstance("SHA-256")
            .digest("$generation:$spaceId".toByteArray(Charsets.UTF_8)).toByteString().hex()

    private companion object {
        val ATTEMPT_PATTERN = Regex("[A-Za-z0-9_-]{16,128}")
        val GIT_SHA_PATTERN = Regex("[0-9a-f]{40,64}")
    }
}
