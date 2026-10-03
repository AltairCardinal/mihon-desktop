package mihon.data.sync.runtime

import kotlinx.serialization.Serializable
import mihon.domain.sync.runtime.SyncRunProblem

@Serializable
internal data class StoredSyncSpaceRecovery(
    val version: Int = 1,
    val bindingRevision: String,
    val reason: SyncSpaceRecoveryReason,
)

data class SyncSpaceRecoveryCheck(
    val recovery: SyncSpaceRecovery? = null,
    val problem: SyncRunProblem? = null,
    val spaceAddressUpdated: Boolean = false,
)
