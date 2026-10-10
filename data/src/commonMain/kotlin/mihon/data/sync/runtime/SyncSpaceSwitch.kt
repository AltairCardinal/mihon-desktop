package mihon.data.sync.runtime

import kotlinx.serialization.Serializable

@Serializable
internal enum class SyncSpaceSwitchPurpose { CONNECT, CREATE }

@Serializable
internal enum class SyncSpaceSwitchStage { PREPARING, ACTIVATING, COMPLETE, CANCELLED }

/** Each intent and its sealed checkpoint are retained independently across successive switches. */
@Serializable
internal data class StoredSyncSpaceSwitch(
    val version: Int = 1,
    val intentId: String,
    val accountId: Long,
    val oldConnection: StoredSyncConnection,
    val oldBindingRevision: String,
    val oldPending: StoredSyncSetup?,
    val purpose: SyncSpaceSwitchPurpose,
    val previousIntentId: String? = null,
    val stage: SyncSpaceSwitchStage = SyncSpaceSwitchStage.PREPARING,
    val target: StoredSyncSetup? = null,
    val targetConnection: StoredSyncConnection? = null,
) {
    override fun toString(): String = "StoredSyncSpaceSwitch(<redacted>)"
}

@Serializable
internal data class StoredSyncSpaceSwitchPointer(val version: Int = 1, val intentId: String)

@Serializable
internal data class StoredSyncAddressUpdate(
    val version: Int = 1,
    val before: StoredSyncConnection,
    val after: StoredSyncConnection,
    val pendingBefore: StoredSyncSetup?,
    val complete: Boolean = false,
) {
    override fun toString(): String = "StoredSyncAddressUpdate(<redacted>)"
}
