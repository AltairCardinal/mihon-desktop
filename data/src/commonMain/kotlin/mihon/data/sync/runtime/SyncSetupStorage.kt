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
    val switchIntentId: String? = null,
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
    val switchIntentId: String? = null,
    val settled: Boolean = false,
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

    suspend fun unboundRecoveryFlow(): StoredUnboundSyncRecoveryFlow? =
        secure.read("sync-recovery-unbound-v1")?.let { decode<StoredUnboundSyncRecoveryFlow>(it, 1) }

    suspend fun saveUnboundRecoveryFlow(
        value: StoredUnboundSyncRecoveryFlow,
        expected: StoredUnboundSyncRecoveryFlow?,
    ) {
        val key = "sync-recovery-unbound-v1"
        val before = secure.read(key)
        require(
            before?.let {
                decode<StoredUnboundSyncRecoveryFlow>(it, 1)
            } == expected,
        ) { "sync recovery context changed" }
        require(value.version == 1 && (value.accountId == null || value.accountId > 0))
        require(value.repositoryId == null || value.repositoryId > 0)
        val encoded = json.encodeToString(value)
        require(encoded.length <= 64 * 1024)
        if (!secure.compareAndSet(key, before, encoded)) throw SyncSecureStoreException()
    }

    suspend fun repositoryCreation(accountId: Long): mihon.data.sync.auth.SyncRepositoryCreationIntent? =
        secure.read("sync-repository-creation-v1-$accountId")?.let {
            decode<StoredSyncRepositoryCreation>(it, 1).intent.also { value ->
                require(value.account.id == accountId && value.attemptId.matches(ATTEMPT_PATTERN))
                require(value.repositoryId == null || value.repositoryId > 0)
                SyncRepository(
                    value.account.login,
                    value.repositoryName,
                    mihon.data.sync.auth.GitHubSyncSpaceClient.BRANCH,
                )
            }
        }

    suspend fun repositoryCreationArchive(attemptId: String): mihon.data.sync.auth.SyncRepositoryCreationIntent? {
        require(attemptId.matches(ATTEMPT_PATTERN))
        return secure.read("sync-repository-creation-archive-v1-$attemptId")?.let {
            decode<StoredSyncRepositoryCreation>(it, 1).intent
        }
    }

    suspend fun saveRepositoryCreation(
        value: mihon.data.sync.auth.SyncRepositoryCreationIntent,
        expected: mihon.data.sync.auth.SyncRepositoryCreationIntent?,
    ) {
        require(value.attemptId.matches(ATTEMPT_PATTERN))
        val key = "sync-repository-creation-v1-${value.account.id}"
        val before = secure.read(key)
        require(
            before?.let {
                decode<StoredSyncRepositoryCreation>(it, 1).intent
            } == expected,
        ) { "repository creation changed" }
        if (expected?.submitted == true) {
            require(value.attemptId == expected.attemptId && value.repositoryName == expected.repositoryName)
            require(expected.repositoryId == null || value.repositoryId == expected.repositoryId)
        }
        if (!secure.compareAndSet(
                key,
                before,
                json.encodeToString(StoredSyncRepositoryCreation(intent = value)),
            )
        ) {
            throw SyncSecureStoreException()
        }
    }

    suspend fun archiveRepositoryCreation(value: mihon.data.sync.auth.SyncRepositoryCreationIntent) {
        val key = "sync-repository-creation-archive-v1-${value.attemptId}"
        val encoded = json.encodeToString(StoredSyncRepositoryCreation(intent = value))
        val before = secure.read(key)
        require(before == null || before == encoded)
        if (before == null && !secure.compareAndSet(key, null, encoded)) throw SyncSecureStoreException()
        val currentKey = "sync-repository-creation-v1-${value.account.id}"
        val current = secure.read(currentKey)
        if (current?.let { decode<StoredSyncRepositoryCreation>(it, 1).intent } == value &&
            !secure.compareAndSet(currentKey, current, null)
        ) {
            throw SyncSecureStoreException()
        }
    }

    suspend fun recoveryFlow(connection: StoredSyncConnection): StoredSyncRecoveryFlow? {
        val descriptor = connection.material.material().descriptor
        val raw =
            secure.read(connectionKey(descriptor.spaceId, descriptor.generation) + "-recovery-flow-v1") ?: return null
        return decode<StoredSyncRecoveryFlow>(raw, 1).takeIf { it.bindingRevision == connection.recoveryRevision() }
    }

    suspend fun saveRecoveryFlow(
        connection: StoredSyncConnection,
        value: StoredSyncRecoveryFlow,
        expected: StoredSyncRecoveryFlow?,
    ) {
        val descriptor = connection.material.material().descriptor
        require(value.version == 1 && value.bindingRevision == connection.recoveryRevision())
        require(value.updatedAtMillis >= 0)
        val current = connection(descriptor.spaceId, descriptor.generation)
        require(current?.recoveryRevision() == value.bindingRevision) { "sync recovery target changed" }
        val key = connectionKey(descriptor.spaceId, descriptor.generation) + "-recovery-flow-v1"
        val before = secure.read(key)
        val decoded = before?.let { decode<StoredSyncRecoveryFlow>(it, 1) }
        require(
            decoded?.takeIf {
                it.bindingRevision == connection.recoveryRevision()
            } == expected,
        ) { "sync recovery flow changed" }
        val encoded = json.encodeToString(value)
        require(encoded.length <= 64 * 1024)
        if (!secure.compareAndSet(key, before, encoded)) throw SyncSecureStoreException()
    }

    suspend fun recoveryObservation(connection: StoredSyncConnection): StoredSyncRecoveryObservation? {
        val descriptor = connection.material.material().descriptor
        val value = secure.read(connectionKey(descriptor.spaceId, descriptor.generation) + "-recovery-observation")
            ?: return null
        return decodeObservation(value)?.takeIf {
            it.bindingRevision == connection.snapshotManifestBinding().connectionRevision
        }
    }

    suspend fun updateRecoveryObservation(
        connection: StoredSyncConnection,
        update: (StoredSyncRecoveryObservation) -> StoredSyncRecoveryObservation,
    ) {
        val descriptor = connection.material.material().descriptor
        val key = connectionKey(descriptor.spaceId, descriptor.generation) + "-recovery-observation"
        val before = secure.read(key)
        val decoded = before?.let(::decodeObservation)
        // Unknown display records are never permission to rewrite a future format.
        if (before != null && decoded == null) return
        val revision = connection.snapshotManifestBinding().connectionRevision
        val previous = decoded?.takeIf { it.bindingRevision == revision }
            ?: StoredSyncRecoveryObservation(bindingRevision = revision)
        val next = update(previous)
        require(next.version == 1 && next.bindingRevision == revision)
        val current = connection(descriptor.spaceId, descriptor.generation)
        require(current?.snapshotManifestBinding()?.connectionRevision == revision) { "sync binding changed" }
        if (!secure.compareAndSet(key, before, json.encodeToString(next))) throw SyncSecureStoreException()
    }

    private fun decodeObservation(value: String): StoredSyncRecoveryObservation? = try {
        decode<StoredSyncRecoveryObservation>(value, 1).also {
            require(it.lastCheckedAtMillis == null || it.lastCheckedAtMillis >= 0)
            require(it.authorizationConfirmedAtMillis == null || it.authorizationConfirmedAtMillis >= 0)
            require(it.credentialRevision == null || it.credentialRevision > 0)
        }
    } catch (_: Exception) {
        null
    }

    suspend fun recovery(connection: StoredSyncConnection): SyncSpaceRecovery? {
        val descriptor = connection.material.material().descriptor
        val value = secure.read(connectionKey(descriptor.spaceId, descriptor.generation) + "-recovery") ?: return null
        val recovery = decode<StoredSyncSpaceRecovery>(value, 1)
        return recovery.takeIf { it.bindingRevision == connection.snapshotManifestBinding().connectionRevision }
            ?.let { SyncSpaceRecovery(it.reason) }
    }

    suspend fun setRecovery(connection: StoredSyncConnection, reason: SyncSpaceRecoveryReason?) {
        val descriptor = connection.material.material().descriptor
        val key = connectionKey(descriptor.spaceId, descriptor.generation) + "-recovery"
        val before = secure.read(key)
        val revision = connection.snapshotManifestBinding().connectionRevision
        val previous = before?.let { decode<StoredSyncSpaceRecovery>(it, 1) }
        if (reason == null && previous?.bindingRevision != revision) return
        val after = reason?.let {
            json.encodeToString(StoredSyncSpaceRecovery(bindingRevision = revision, reason = it))
        }
        if (!secure.compareAndSet(key, before, after)) throw SyncSecureStoreException()
    }

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
        val key = value.switchIntentId?.let(::switchSetupKey) ?: setupKey(value.accountId)
        val before = secure.read(key)
        require(before?.let { decode<StoredSyncSetup>(it, 3) } == expected) { "sync setup changed" }
        if (!secure.compareAndSet(key, before, json.encodeToString(value))) throw SyncSecureStoreException()
    }

    suspend fun clear(value: StoredSyncSetup) {
        if (value.switchIntentId != null) {
            save(value.copy(settled = true), value)
            return
        }
        val key = value.switchIntentId?.let(::switchSetupKey) ?: setupKey(value.accountId)
        val before = secure.read(key) ?: return
        if (decode<StoredSyncSetup>(before, 3) != value) return
        if (!secure.compareAndSet(key, before, null)) throw SyncSecureStoreException()
    }

    suspend fun archivedSetup(attemptId: String): StoredSyncSetup? {
        require(attemptId.matches(ATTEMPT_PATTERN))
        return secure.read("sync-setup-archive-v3-$attemptId")?.let {
            decode<StoredSyncSetup>(it, 3).also { value ->
                require(value.attemptId == attemptId)
                value.material.material()
                value.repository()
            }
        }
    }

    /** Preserve the exact original before removing only its active setup pointer after explicit replacement. */
    suspend fun archivePending(value: StoredSyncSetup) {
        require(value.switchIntentId == null && value.attemptId.matches(ATTEMPT_PATTERN))
        val key = setupKey(value.accountId)
        val before = secure.read(key) ?: return
        require(decode<StoredSyncSetup>(before, 3) == value) { "sync setup changed" }
        val archiveKey = "sync-setup-archive-v3-${value.attemptId}"
        val previousArchive = secure.read(archiveKey)
        require(previousArchive == null || previousArchive == before)
        if (previousArchive == null && !secure.compareAndSet(archiveKey, null, before)) throw SyncSecureStoreException()
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

    suspend fun setupFor(value: StoredSyncSetup): StoredSyncSetup? =
        value.switchIntentId?.let { switchSetup(it) } ?: pending(value.accountId)

    suspend fun pendingForConnection(value: StoredSyncConnection): StoredSyncSetup? =
        (value.switchIntentId?.let { switchSetup(it) } ?: pending(value.accountId))?.takeIf { !it.settled }

    suspend fun switchSetup(intentId: String): StoredSyncSetup? =
        secure.read(switchSetupKey(intentId))?.let { decode<StoredSyncSetup>(it, 3) }

    suspend fun activeSwitch(accountId: Long): StoredSyncSpaceSwitch? {
        val pointer = secure.read("sync-switch-pointer-v1-$accountId") ?: return null
        val id = decode<StoredSyncSpaceSwitchPointer>(pointer, 1).intentId
        var intent = requireNotNull(switchIntent(id))
        require(intent.accountId == accountId)
        if (intent.stage == SyncSpaceSwitchStage.PREPARING && intent.target == null) {
            switchSetup(id)?.let { checkpoint ->
                require(checkpoint.accountId == accountId && checkpoint.switchIntentId == id)
                val repaired = intent.copy(target = checkpoint)
                saveSwitch(repaired, intent)
                intent = repaired
            }
        }
        return intent
    }

    suspend fun switchIntent(intentId: String): StoredSyncSpaceSwitch? {
        require(intentId.matches(ATTEMPT_PATTERN))
        return secure.read("sync-switch-intent-v1-$intentId")?.let {
            decode<StoredSyncSpaceSwitch>(it, 1).also { value ->
                require(value.intentId == intentId && value.accountId == value.oldConnection.accountId)
                require(value.oldBindingRevision == value.oldConnection.snapshotManifestBinding().connectionRevision)
                require(value.previousIntentId == null || value.previousIntentId.matches(ATTEMPT_PATTERN))
                require(value.previousIntentId != value.intentId)
                require(value.target == null || value.target.switchIntentId == intentId)
                require(value.targetConnection == null || value.targetConnection.switchIntentId == intentId)
            }
        }
    }

    suspend fun saveSwitch(value: StoredSyncSpaceSwitch, expected: StoredSyncSpaceSwitch?) {
        require(value.version == 1 && value.intentId.matches(ATTEMPT_PATTERN))
        val key = "sync-switch-intent-v1-${value.intentId}"
        val before = secure.read(key)
        require(before?.let { decode<StoredSyncSpaceSwitch>(it, 1) } == expected) { "sync switch changed" }
        if (!secure.compareAndSet(key, before, json.encodeToString(value))) throw SyncSecureStoreException()
    }

    suspend fun startSwitch(value: StoredSyncSpaceSwitch) {
        val key = "sync-switch-pointer-v1-${value.accountId}"
        val before = secure.read(key)
        before?.let {
            val previous = requireNotNull(switchIntent(decode<StoredSyncSpaceSwitchPointer>(it, 1).intentId))
            require(previous.stage in setOf(SyncSpaceSwitchStage.COMPLETE, SyncSpaceSwitchStage.CANCELLED))
        }
        saveSwitch(value, null)
        val next = json.encodeToString(StoredSyncSpaceSwitchPointer(intentId = value.intentId))
        if (!secure.compareAndSet(key, before, next)) throw SyncSecureStoreException()
    }

    suspend fun addressUpdate(spaceId: String, generation: Long): StoredSyncAddressUpdate? =
        secure.read(connectionKey(spaceId, generation) + "-address-v1")?.let {
            decode<StoredSyncAddressUpdate>(it, 1)
        }

    suspend fun saveAddressUpdate(value: StoredSyncAddressUpdate, expected: StoredSyncAddressUpdate?) {
        val descriptor = value.before.material.material().descriptor
        val key = connectionKey(descriptor.spaceId, descriptor.generation) + "-address-v1"
        val before = secure.read(key)
        require(before?.let { decode<StoredSyncAddressUpdate>(it, 1) } == expected)
        require(
            value.before.accountId == value.after.accountId &&
                value.before.repositoryId == value.after.repositoryId,
        )
        require(
            value.before.material == value.after.material && value.before.actorId == value.after.actorId &&
                value.before.epoch == value.after.epoch,
        )
        if (!secure.compareAndSet(key, before, json.encodeToString(value))) throw SyncSecureStoreException()
    }

    private fun switchSetupKey(intentId: String): String {
        require(intentId.matches(ATTEMPT_PATTERN))
        return "sync-switch-setup-v1-$intentId"
    }

    private inline fun <reified T> decode(value: String, expectedVersion: Int): T {
        require(value.length <= 64 * 1024) { "sync secure record exceeds limit" }
        val version = try {
            Json.parseToJsonElement(value).jsonObject["version"]?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            throw SyncSecureStoreException()
        }
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

@Serializable
internal data class StoredSyncRepositoryCreation(
    val version: Int = 1,
    val intent: mihon.data.sync.auth.SyncRepositoryCreationIntent,
) {
    override fun toString(): String = "StoredSyncRepositoryCreation(<redacted>)"
}
