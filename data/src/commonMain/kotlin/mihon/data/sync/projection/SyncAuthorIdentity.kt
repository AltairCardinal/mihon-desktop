package mihon.data.sync.projection

import tachiyomi.data.Database

/** One local identity resolution for business projection and invalidating older receiver decisions. */
internal fun Database.resolveSyncAuthorIdentity(portableKey: String): Long? {
    fun unavailable(): Nothing = throw SyncProjectionUnavailable(SyncProjectionUnavailableReason.IDENTITY)
    var id = author_archiveQueries.getArchiveCreatorIdByPortableKey(portableKey).executeAsOneOrNull() ?: return null
    val visited = mutableSetOf<Long>()
    while (visited.add(id)) {
        val identity = author_archiveQueries.getArchiveCreatorIdentityRecord(id).executeAsOneOrNull() ?: unavailable()
        when (identity.status) {
            "ACTIVE" -> {
                if (identity.merged_into_creator_id != null) unavailable()
                return id
            }
            "MERGED" -> id = identity.merged_into_creator_id ?: unavailable()
            else -> unavailable()
        }
    }
    unavailable()
}
