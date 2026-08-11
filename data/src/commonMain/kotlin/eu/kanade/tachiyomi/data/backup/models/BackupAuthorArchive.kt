package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Optional, versioned author archive carried by field 107 of [Backup].
 *
 * This wire model intentionally contains only portable and natural keys. Local database IDs must
 * never be added because Android and Desktop assign them independently.
 */
@Serializable
data class BackupAuthorArchiveSection(
    @ProtoNumber(1) val version: Int = CURRENT_VERSION,
    @ProtoNumber(2) val creators: List<BackupCreatorIdentity> = emptyList(),
    @ProtoNumber(3) val sourceWorks: List<BackupAuthorSourceWork> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class BackupCreatorIdentity(
    @ProtoNumber(1) val portableKey: String,
    @ProtoNumber(2) val displayName: String,
    @ProtoNumber(3) val normalizedName: String,
    @ProtoNumber(4) val sortName: String? = null,
    @ProtoNumber(5) val status: String = "ACTIVE",
    @ProtoNumber(6) val mergedIntoPortableKey: String? = null,
    @ProtoNumber(7) val needsReview: Boolean = false,
    @ProtoNumber(8) val aliases: List<BackupCreatorAlias> = emptyList(),
)

@Serializable
data class BackupCreatorAlias(
    @ProtoNumber(1) val rawAlias: String,
    @ProtoNumber(2) val normalizedAlias: String,
    @ProtoNumber(3) val source: String,
    @ProtoNumber(4) val evidence: String,
    @ProtoNumber(5) val confidence: Double,
    @ProtoNumber(6) val isManual: Boolean,
)

@Serializable
data class BackupAuthorSourceWork(
    @ProtoNumber(1) val sourceId: Long,
    @ProtoNumber(2) val stableSourceUrl: String,
    @ProtoNumber(3) val title: String,
    @ProtoNumber(4) val authorText: String? = null,
    @ProtoNumber(5) val artistText: String? = null,
    @ProtoNumber(6) val thumbnailUrl: String? = null,
    @ProtoNumber(7) val bindings: List<BackupAuthorBinding> = emptyList(),
)

@Serializable
data class BackupAuthorBinding(
    @ProtoNumber(1) val creatorPortableKey: String,
    @ProtoNumber(2) val role: String,
    @ProtoNumber(3) val order: Long,
    @ProtoNumber(4) val origin: String,
    @ProtoNumber(5) val verification: String,
    @ProtoNumber(6) val sourceText: String? = null,
    @ProtoNumber(7) val confidence: Double,
    @ProtoNumber(8) val evidence: String,
)
