package tachiyomi.domain.reader.model

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
) {
    val isRead: Boolean get() = wasRead || (totalPages > 0 && lastPageRead >= totalPages - 1)
}
