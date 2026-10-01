package mihon.data.sync.inbox

import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import tachiyomi.data.Database
import tachiyomi.data.chapter.readChapterUrlIdentity

/** A local clear suppresses known history, without pretending to date a never-observed remote actor. */
internal fun Database.allowSyncHistory(event: SyncEventEnvelope, chapterKey: SyncObjectKey): Boolean {
    val targets = historyTargets(chapterKey)
    if (sync_historyQueries.hasClear(targets).executeAsOne() == 0L) return true
    if (event.origin != SyncOrigin.USER) return false
    val watermark = sync_historyQueries.getWatermark(
        targets,
        event.spaceId,
        event.generation,
        event.actorId,
        event.epoch,
    ).executeAsOne().max
    return watermark == null || event.seq > watermark
}

internal fun Database.isSyncHistoryBaselineSuppressed(chapterKey: SyncObjectKey): Boolean =
    sync_historyQueries.hasClear(historyTargets(chapterKey)).executeAsOne() != 0L

/** Called in the same transaction as the existing history mutation. */
internal fun Database.suppressSyncChapterHistory(historyId: Long) {
    val row = sync_historyQueries.getHistoryTarget(historyId).executeAsOneOrNull() ?: return
    val chapter = SyncObjectKey(
        SyncObjectType.CHAPTER,
        sourceId = row.source.toString(),
        originalUrl = row.chapter_url,
        parentUrl = row.manga_url,
    )
    recordHistoryClear(chapter.stableKey)
    sync_historyQueries.clearPublicChapterHistory(row.chapter_id)
}

internal fun Database.suppressSyncMangaHistory(mangaId: Long) {
    val manga = mangasQueries.getMangaById(mangaId).executeAsOneOrNull() ?: return
    recordHistoryClear(
        SyncObjectKey(SyncObjectType.MANGA, sourceId = manga.source.toString(), originalUrl = manga.url).stableKey,
    )
    sync_historyQueries.clearPublicMangaHistory(mangaId)
}

internal fun Database.suppressAllSyncHistory() {
    recordHistoryClear("*")
    sync_historyQueries.clearAllPublicHistory()
}

private fun Database.recordHistoryClear(target: String) {
    sync_historyQueries.recordClear(target)
    // Includes received-but-unprojected events and every previously observed space, using SQL aggregation.
    sync_historyQueries.captureWatermarks(target)
}

private fun Database.historyTargets(chapter: SyncObjectKey): List<String> {
    require(chapter.type == SyncObjectType.CHAPTER)
    val manga = SyncObjectKey(
        SyncObjectType.MANGA,
        sourceId = chapter.sourceId,
        originalUrl = chapter.parentUrl,
    )
    val row = chapter.sourceId?.toLongOrNull()?.let { source ->
        sync_projectionQueries.getChapterByIdentity(
            chapterUrl = requireNotNull(chapter.originalUrl),
            mangaUrl = requireNotNull(chapter.parentUrl),
            sourceId = source,
        ).executeAsOneOrNull()
    }
    val chapterTargets = if (row == null) {
        listOf(chapter.stableKey)
    } else {
        val identity = readChapterUrlIdentity(row._id)
        (identity.aliases + identity.canonicalUrl + row.url + requireNotNull(chapter.originalUrl))
            .distinct().map { chapter.copy(originalUrl = it).stableKey }
    }
    return listOf("*", manga.stableKey) + chapterTargets
}
