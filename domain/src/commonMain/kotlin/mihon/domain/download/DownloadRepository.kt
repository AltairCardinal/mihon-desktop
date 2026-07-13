package mihon.domain.download

import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    val queueEntries: Flow<List<DownloadQueueEntry>>
    fun enqueue(entry: DownloadQueueEntry)
    fun isDownloaded(sourceId: Long, mangaTitle: String, chapterName: String): Boolean
    fun cancel(chapterId: Long): Boolean
    fun retry(chapterId: Long): Boolean
    fun transition(chapterId: Long, target: DownloadQueueStatus): Boolean
    fun recover(): List<DownloadQueueEntry>
}

class EnqueueDownload(private val repository: DownloadRepository) {
    operator fun invoke(entry: DownloadQueueEntry) = repository.enqueue(entry)
}

class IsChapterDownloaded(private val repository: DownloadRepository) {
    operator fun invoke(sourceId: Long, mangaTitle: String, chapterName: String): Boolean =
        repository.isDownloaded(sourceId, mangaTitle, chapterName)
}

class ObserveDownloadQueue(private val repository: DownloadRepository) {
    operator fun invoke(): Flow<List<DownloadQueueEntry>> = repository.queueEntries
}

class CancelDownload(private val repository: DownloadRepository) {
    operator fun invoke(chapterId: Long): Boolean = repository.cancel(chapterId)
}

class RetryDownload(private val repository: DownloadRepository) {
    operator fun invoke(chapterId: Long): Boolean = repository.retry(chapterId)
}

class TransitionDownload(private val repository: DownloadRepository) {
    operator fun invoke(
        chapterId: Long,
        target: DownloadQueueStatus,
    ): Boolean = repository.transition(chapterId, target)
}

class RecoverDownloads(private val repository: DownloadRepository) {
    operator fun invoke(): List<DownloadQueueEntry> = repository.recover()
}
