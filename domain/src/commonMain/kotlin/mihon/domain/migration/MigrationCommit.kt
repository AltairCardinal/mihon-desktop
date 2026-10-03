package mihon.domain.migration

import mihon.domain.migration.models.MigrationFlag

/** A single accepted migration. Identities are rechecked against current storage in the commit. */
@kotlinx.serialization.Serializable
data class MigrationCommit(
    val sourceMangaId: Long,
    val sourceId: Long,
    val sourceUrl: String,
    val targetMangaId: Long,
    val targetSourceId: Long,
    val targetUrl: String,
    val flags: Set<MigrationFlag>,
    val replace: Boolean,
    val now: Long,
    val operationId: String? = null,
    val previousCoverVersion: Long? = null,
    val coverVersion: Long? = null,
    val checkpointOwner: String? = null,
)

/** Finite, device-local confirmation receipt. Not a backup or sync identity. */
@kotlinx.serialization.Serializable
data class MigrationReceipt(
    val request: MigrationCommit,
    val committed: Boolean = false,
    val filesReady: Boolean = false,
    val filesComplete: Boolean = false,
    val targetDateAdded: Long? = null,
)
