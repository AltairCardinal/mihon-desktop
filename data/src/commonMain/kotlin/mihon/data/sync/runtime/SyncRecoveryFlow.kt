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
sealed interface SyncRecoveryPlatformResult {
    @Serializable
    data object Cancelled : SyncRecoveryPlatformResult

    @Serializable
    data object NoChange : SyncRecoveryPlatformResult

    @Serializable
    data class Changed(val objects: List<SyncObjectKey> = emptyList()) : SyncRecoveryPlatformResult

    @Serializable
    data class Failed(val reason: String) : SyncRecoveryPlatformResult

    @Serializable
    data class PartialFailure(
        val succeededObjects: List<SyncObjectKey> = emptyList(),
        val failedObjects: List<SyncObjectKey> = emptyList(),
        val remaining: Long? = null,
        val objectsComplete: Boolean = true,
    ) : SyncRecoveryPlatformResult

    @Serializable
    data object RestartRequired : SyncRecoveryPlatformResult
}

@Serializable
data class SyncRecoveryExternalScope(
    val requestId: String,
    val action: SyncRecoveryPlatformAction,
    val failedObjects: List<SyncObjectKey> = emptyList(),
    val remaining: Long? = null,
    val objectsComplete: Boolean = true,
)

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
    val result: SyncRecoveryPlatformResult? = null,
)

/** Optional setup observations do not invent a connection, actor, encryption key or admission permission. */
@Serializable
internal data class StoredUnboundSyncRecoveryFlow(
    val version: Int = 1,
    val sourceStage: String? = null,
    val purpose: String? = null,
    val accountId: Long? = null,
    val accountLogin: String? = null,
    val credentialRevision: Long? = null,
    val creationAttemptId: String? = null,
    val repositoryId: Long? = null,
    val failure: SyncRecoveryFailure? = null,
    val request: SyncRecoveryPlatformRequest? = null,
    val officialAction: SyncRecoveryAction? = null,
    val externalScopes: List<SyncRecoveryExternalScope> = emptyList(),
    val repositoryDraftName: String? = null,
    val repositoryPreparedName: String? = null,
    val repositoryDraftSwitchId: String? = null,
    val archivedSetupIds: List<String> = emptyList(),
    val archivedSetupsTruncated: Boolean = false,
) {
    override fun toString(): String = "StoredUnboundSyncRecoveryFlow(<redacted>)"
}

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
    val remainingUnknown: Boolean = false,
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
    val secondaryFailure: SyncRecoveryFailure? = null,
    val conditionsVerified: Boolean = false,
    val repairMadeNoProgress: Boolean = false,
    val repairReportFingerprint: String? = null,
    val externalScopes: List<SyncRecoveryExternalScope> = emptyList(),
    val officialAction: SyncRecoveryAction? = null,
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

/** Only an explicit platform result covering known object identities can settle an earlier failure. */
internal fun List<SyncRecoveryExternalScope>.afterPlatformResult(
    request: SyncRecoveryPlatformRequest,
    result: SyncRecoveryPlatformResult,
    recordNewFailure: Boolean = true,
): List<SyncRecoveryExternalScope> {
    val covered = when (result) {
        is SyncRecoveryPlatformResult.Changed -> result.objects
        is SyncRecoveryPlatformResult.PartialFailure -> result.succeededObjects + result.failedObjects
        else -> emptyList()
    }.toSet()
    val retained = mapNotNull { scope ->
        val known = scope.failedObjects.distinct()
        if (scope.action != request.action || !scope.objectsComplete || scope.remaining == null ||
            scope.remaining != known.size.toLong()
        ) {
            scope
        } else {
            val stillFailed = known.filterNot { it in covered }
            if (stillFailed.isEmpty()) {
                null
            } else {
                scope.copy(
                    failedObjects = stillFailed,
                    remaining = stillFailed.size.toLong(),
                )
            }
        }
    }
    if (result !is SyncRecoveryPlatformResult.PartialFailure || !recordNewFailure) return retained
    require(result.remaining == null || result.remaining > 0)
    val sample = result.failedObjects.distinct().boundedIdentitySample(1024)
    val complete = result.objectsComplete && sample.size == result.failedObjects.distinct().size &&
        result.remaining == sample.size.toLong()
    return (retained + SyncRecoveryExternalScope(request.requestId, request.action, sample, result.remaining, complete))
        .distinct().boundedScopes()
}

internal fun SyncRecoveryPlatformResult.boundedMetadata(): SyncRecoveryPlatformResult = when (this) {
    is SyncRecoveryPlatformResult.PartialFailure -> {
        val succeeded = succeededObjects.boundedIdentitySample(4096)
        val failed = failedObjects.boundedIdentitySample(4096)
        copy(
            succeededObjects = succeeded,
            failedObjects = failed,
            objectsComplete =
            objectsComplete && succeeded.size == succeededObjects.size && failed.size == failedObjects.size,
        )
    }
    is SyncRecoveryPlatformResult.Changed -> copy(objects = objects.boundedIdentitySample(4096))
    else -> this
}

private fun List<SyncObjectKey>.boundedIdentitySample(byteLimit: Int): List<SyncObjectKey> {
    var bytes = 0
    return take(100).takeWhile {
        bytes += kotlinx.serialization.json.Json.encodeToString(SyncObjectKey.serializer(), it).encodeToByteArray().size
        bytes <= byteLimit
    }
}

private fun List<SyncRecoveryExternalScope>.boundedScopes(): List<SyncRecoveryExternalScope> {
    if (size <= 16) return this
    return listOf(first().copy(failedObjects = emptyList(), remaining = null, objectsComplete = false)) + takeLast(15)
}
