package mihon.domain.download

import mihon.domain.error.AppError

data class DownloadQueueEntry(
    val chapterId: Long,
    val mangaId: Long,
    val sourceId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val chapterUrl: String,
    val pageUrls: List<String>,
    val status: DownloadQueueStatus = DownloadQueueStatus.QUEUED,
    val progress: Int = 0,
    val position: Long,
    val retryCount: Int = 0,
    val failure: AppError? = null,
)

enum class DownloadQueueStatus { QUEUED, DOWNLOADING, COMPLETED, ERROR, CANCELLED }

class DownloadQueueStateMachine {
    private val transitions = mapOf(
        DownloadQueueStatus.QUEUED to setOf(DownloadQueueStatus.DOWNLOADING, DownloadQueueStatus.CANCELLED),
        DownloadQueueStatus.DOWNLOADING to setOf(
            DownloadQueueStatus.COMPLETED,
            DownloadQueueStatus.ERROR,
            DownloadQueueStatus.CANCELLED,
        ),
        DownloadQueueStatus.ERROR to setOf(DownloadQueueStatus.QUEUED, DownloadQueueStatus.CANCELLED),
        DownloadQueueStatus.COMPLETED to emptySet(),
        DownloadQueueStatus.CANCELLED to emptySet(),
    )

    fun transition(entry: DownloadQueueEntry, target: DownloadQueueStatus): DownloadQueueEntry? =
        target.takeIf { it in transitions.getValue(entry.status) }?.let { entry.copy(status = it) }

    fun recover(entries: List<DownloadQueueEntry>): List<DownloadQueueEntry> = entries.map {
        if (it.status == DownloadQueueStatus.DOWNLOADING) it.copy(status = DownloadQueueStatus.QUEUED) else it
    }

    fun schedule(entries: List<DownloadQueueEntry>, limit: Int): List<DownloadQueueEntry> {
        val availableSlots = (limit - entries.count { it.status == DownloadQueueStatus.DOWNLOADING }).coerceAtLeast(0)
        if (availableSlots == 0) return emptyList()
        val bySource = entries.filter { it.status == DownloadQueueStatus.QUEUED }
            .sortedBy { it.position }
            .groupBy { it.sourceId }
            .mapValues { (_, value) -> ArrayDeque(value) }
            .toMutableMap()
        val result = mutableListOf<DownloadQueueEntry>()
        while (result.size < availableSlots && bySource.isNotEmpty()) {
            bySource.keys.toList().forEach { sourceId ->
                if (result.size < availableSlots) bySource[sourceId]?.removeFirstOrNull()?.let(result::add)
                if (bySource[sourceId].isNullOrEmpty()) bySource.remove(sourceId)
            }
        }
        return result
    }

    /** Shared retry policy: three retries after 2, 4 and 8 seconds. */
    fun retryDelayMillis(attempt: Int): Long? = if (attempt in 0..2) (2L shl attempt) * 1_000 else null
}
