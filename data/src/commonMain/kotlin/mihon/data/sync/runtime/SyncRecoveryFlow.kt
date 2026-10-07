package mihon.data.sync.runtime

import kotlinx.serialization.Serializable
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.runtime.SyncRunProblem

@Serializable
enum class SyncRecoveryOutcome {
    ORIGINAL_VERIFIED,
    NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING,
    REMAINING,
    WAITING_EXTERNAL,
}

@Serializable
enum class SyncRecoveryFlowStep { CHECK_CONDITIONS, REPAIR_DATA, EXTERNAL_ACTION, VERIFY_SYNCHRONIZATION, COMPLETE }

@Serializable
enum class SyncRecoveryPlatformAction { NETWORK, EXTENSIONS, MIGRATION, BACKUP, READER, STORAGE, UPDATE }

@Serializable
data class SyncRecoveryPlatformRequest(
    val requestId: String,
    val action: SyncRecoveryPlatformAction,
    val sourceIds: Set<Long> = emptySet(),
    val objects: List<SyncObjectKey> = emptyList(),
    val originSpaceId: String? = null,
    val originGeneration: Long? = null,
    val failed: Boolean = false,
    val restartRequired: Boolean = false,
    val runtimeInstanceId: String? = null,
)

@Serializable
data class SyncRecoveryFailure(
    val problem: SyncRunProblem? = null,
    val discovery: SyncDiscoveryProblem? = null,
    val initialization: SyncInitializationFailure? = null,
    val networkPhase: mihon.domain.sync.runtime.SyncNetworkFailurePhase? = null,
    val httpStatus: Int? = null,
)

data class SyncRecoveryScopeSummary(
    val spaceId: String?,
    val generation: Long?,
    val label: String,
    val remaining: Long,
    val queuedUnpublished: Long = 0,
    val remoteUnverified: Boolean = false,
)

/** A display/continuation record is never admission permission or a replacement for sealed bindings. */
@Serializable
internal data class StoredSyncRecoveryFlow(
    val version: Int = 1,
    val bindingRevision: String,
    val credentialRevision: Long?,
    val step: SyncRecoveryFlowStep = SyncRecoveryFlowStep.CHECK_CONDITIONS,
    val outcome: SyncRecoveryOutcome? = null,
    val failure: SyncRecoveryFailure? = null,
    val request: SyncRecoveryPlatformRequest? = null,
    val result: mihon.domain.sync.runtime.SyncRunResult? = null,
    val verifiedRunId: String? = null,
    val updatedAtMillis: Long = 0,
) {
    override fun toString(): String = "StoredSyncRecoveryFlow(<redacted>)"
}

internal data class SyncRecoveryBinding(val stored: StoredSyncConnection, val credentialRevision: Long?) {
    val descriptor get() = stored.material.material().descriptor
    fun matches(other: SyncRecoveryBinding): Boolean = credentialRevision == other.credentialRevision &&
        stored.accountId == other.stored.accountId && stored.repositoryId == other.stored.repositoryId &&
        stored.material == other.stored.material && stored.actorId == other.stored.actorId &&
        stored.epoch == other.stored.epoch
}

internal fun StoredSyncConnection.recoveryRevision(): String =
    mihon.data.sync.transport.SyncSnapshotManifestCrypto.sha256(
        (snapshotManifestBinding().connectionRevision + "\u0000" + actorId + "\u0000" + epoch).encodeToByteArray(),
    )
