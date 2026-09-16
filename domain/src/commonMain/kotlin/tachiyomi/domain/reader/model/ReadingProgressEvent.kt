package tachiyomi.domain.reader.model

import mihon.domain.sync.SyncMutationContext
import java.util.Date
import java.util.UUID

data class ReadingProgressEvent(
    val chapterId: Long,
    val lastPageRead: Int,
    val totalPages: Int,
    val readAt: Date,
    val sessionReadDuration: Long,
    val trackerEvent: String = "progress",
    val recordHistory: Boolean = true,
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val wasRead: Boolean = false,
    val syncContext: SyncMutationContext = SyncMutationContext.Metadata,
) {
    val isRead: Boolean get() = wasRead || (totalPages > 0 && lastPageRead >= totalPages - 1)
}
