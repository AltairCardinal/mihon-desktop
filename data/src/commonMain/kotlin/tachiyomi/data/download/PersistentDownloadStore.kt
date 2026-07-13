package tachiyomi.data.download

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.download.DownloadQueueStateMachine
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import tachiyomi.data.Database

class PersistentDownloadStore(
    private val database: Database,
    private val stateMachine: DownloadQueueStateMachine = DownloadQueueStateMachine(),
) {
    fun entries(): List<DownloadQueueEntry> = database.download_queueQueries.selectAll(::map).executeAsList()

    fun recover(): List<DownloadQueueEntry> {
        val persisted = entries()
        val recovered = stateMachine.recover(persisted)
        if (recovered != persisted) replaceAll(recovered)
        return recovered
    }

    fun replaceAll(entries: List<DownloadQueueEntry>) {
        database.transaction {
            database.download_queueQueries.deleteAll()
            entries.forEach(::upsert)
        }
    }

    fun upsert(entry: DownloadQueueEntry) {
        database.download_queueQueries.upsert(
            entry.chapterId,
            entry.mangaId,
            entry.sourceId,
            entry.mangaTitle,
            entry.chapterName,
            entry.chapterUrl,
            Json.encodeToString(entry.pageUrls),
            entry.status.name,
            entry.progress.toLong(),
            entry.position,
            entry.retryCount.toLong(),
            entry.failure?.toStoredAppError()?.let { Json.encodeToString(it) },
        )
    }

    fun delete(chapterId: Long) = database.download_queueQueries.deleteByChapterId(chapterId)

    private fun map(
        chapterId: Long,
        mangaId: Long,
        sourceId: Long,
        mangaTitle: String,
        chapterName: String,
        chapterUrl: String,
        pageUrls: String,
        status: String,
        progress: Long,
        position: Long,
        retryCount: Long,
        failure: String?,
    ) = DownloadQueueEntry(
        chapterId,
        mangaId,
        sourceId,
        mangaTitle,
        chapterName,
        chapterUrl,
        Json.decodeFromString(pageUrls),
        DownloadQueueStatus.valueOf(status),
        progress.toInt(),
        position,
        retryCount.toInt(),
        failure?.let { Json.decodeFromString<StoredAppError>(it).toAppError() },
    )
}
