package mihon.domain.download

import kotlinx.coroutines.flow.Flow
import mihon.domain.reader.content.DownloadChapterIdentity

interface DownloadRepository {
    val queueEntries: Flow<List<DownloadQueueEntry>>
    fun enqueue(entry: DownloadQueueEntry)
    fun isDownloaded(sourceId: Long, mangaTitle: String, chapterName: String): Boolean
    fun isDownloaded(sourceId: Long, identity: DownloadChapterIdentity): Boolean =
        isDownloaded(sourceId, identity.mangaTitle, identity.chapterName)
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

    operator fun invoke(sourceId: Long, identity: DownloadChapterIdentity): Boolean =
        repository.isDownloaded(sourceId, identity)
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
