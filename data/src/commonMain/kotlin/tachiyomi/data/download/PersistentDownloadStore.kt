package tachiyomi.data.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.download.DownloadQueueStateMachine
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.partial.PartialPageTable
import mihon.domain.reader.partial.PartialPageTablePolicy
import tachiyomi.data.Database

private const val PAGE_METADATA_TYPE = "mihon-partial-page-table"
private const val PAGE_METADATA_STORAGE_VERSION = 1

@Serializable
private data class StoredDownloadPageMetadata(
    val type: String = PAGE_METADATA_TYPE,
    val version: Int = PAGE_METADATA_STORAGE_VERSION,
    val pageTable: PartialPageTable,
    val downloadIdentity: DownloadChapterIdentity? = null,
)

private data class DecodedDownloadPageMetadata(
    val pageUrls: List<String>,
    val pageTable: PartialPageTable,
    val downloadIdentity: DownloadChapterIdentity?,
)

private val downloadPageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

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
        val pageTable = PartialPageTablePolicy.normalizeLegacyPageUrls(entry.pageTable, entry.pageUrls)
        database.download_queueQueries.upsert(
            entry.chapterId,
            entry.mangaId,
            entry.sourceId,
            entry.mangaTitle,
            entry.chapterName,
            entry.chapterUrl,
            downloadPageJson.encodeToString(
                StoredDownloadPageMetadata(
                    pageTable = pageTable,
                    downloadIdentity = entry.downloadIdentity,
                ),
            ),
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
    ): DownloadQueueEntry {
        val pageMetadata = decodePageMetadata(pageUrls)
        return DownloadQueueEntry(
            chapterId = chapterId,
            mangaId = mangaId,
            sourceId = sourceId,
            mangaTitle = mangaTitle,
            chapterName = chapterName,
            chapterUrl = chapterUrl,
            pageUrls = pageMetadata.pageUrls,
            status = DownloadQueueStatus.valueOf(status),
            progress = progress.toInt(),
            position = position,
            retryCount = retryCount.toInt(),
            failure = failure?.let { Json.decodeFromString<StoredAppError>(it).toAppError() },
            pageTable = pageMetadata.pageTable,
            downloadIdentity = pageMetadata.downloadIdentity,
        )
    }

    private fun decodePageMetadata(raw: String): DecodedDownloadPageMetadata = runCatching {
        val element = downloadPageJson.parseToJsonElement(raw)
        if (element is JsonArray) {
            val legacyUrls = downloadPageJson.decodeFromJsonElement<List<String>>(element)
            DecodedDownloadPageMetadata(
                pageUrls = legacyUrls,
                pageTable = PartialPageTable.legacy(legacyUrls),
                downloadIdentity = null,
            )
        } else {
            val stored = downloadPageJson.decodeFromJsonElement<StoredDownloadPageMetadata>(element)
            check(stored.type == PAGE_METADATA_TYPE && stored.version == PAGE_METADATA_STORAGE_VERSION)
            DecodedDownloadPageMetadata(
                pageUrls = stored.pageTable.entries
                    .sortedBy { it.readerOrdinal }
                    .map { entry -> entry.imageUrl?.takeIf(String::isNotBlank) ?: entry.pageUrl },
                pageTable = stored.pageTable,
                downloadIdentity = stored.downloadIdentity,
            )
        }
    }.getOrElse {
        DecodedDownloadPageMetadata(
            pageUrls = emptyList(),
            pageTable = PartialPageTable.legacy(emptyList()),
            downloadIdentity = null,
        )
    }
}
