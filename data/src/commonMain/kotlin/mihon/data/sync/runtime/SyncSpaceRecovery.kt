package mihon.data.sync.runtime

import kotlinx.serialization.Serializable
import mihon.domain.sync.runtime.SyncRunProblem

@Serializable
internal data class StoredSyncSpaceRecovery(
    val version: Int = 1,
    val bindingRevision: String,
    val reason: SyncSpaceRecoveryReason,
)

/** Display observations never grant permission to synchronize or replace a binding. */
@Serializable
internal data class StoredSyncRecoveryObservation(
    val version: Int = 1,
    val bindingRevision: String,
    val credentialRevision: Long? = null,
    val lastCheckedAtMillis: Long? = null,
    val lastCheckProblem: SyncRunProblem? = null,
    val lastCheckReason: SyncSpaceRecoveryReason? = null,
    val lastCheckSucceeded: Boolean? = null,
    val authorizationConfirmedAtMillis: Long? = null,
    val authorization: SyncRecoveryAuthorization = SyncRecoveryAuthorization.IDLE,
)

data class SyncSpaceRecoveryCheck(
    val recovery: SyncSpaceRecovery? = null,
    val problem: SyncRunProblem? = null,
    val spaceAddressUpdated: Boolean = false,
    val authorizationConfirmed: Boolean = false,
    val authorizationCredentialRevision: Long? = null,
)

internal data class SyncRecoveryAuthorizationCheck(
    val confirmed: Boolean = false,
    val required: Boolean = false,
    val problem: SyncRunProblem? = null,
)
