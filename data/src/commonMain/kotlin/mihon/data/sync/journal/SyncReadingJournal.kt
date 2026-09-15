package mihon.data.sync.journal

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import tachiyomi.data.Database
import tachiyomi.domain.reader.model.ReadingProgressEvent

internal fun Database.appendReadingOperation(event: ReadingProgressEvent): SyncEventEnvelope? {
    if (!event.syncContext.uploadAllowed || event.syncContext.origin != SyncOrigin.USER) return null
    val (chapterKey, mangaKey) = readingObjectKeys(event.chapterId)
    val effects = buildList {
        if (event.totalPages > 0 && event.lastPageRead >= event.totalPages - 1) {
            add(SyncEffect("read", chapterKey, SyncField.READ_STATUS, SyncEffectKind.MARK_READ))
        }
        add(
            SyncEffect(
                "position",
                mangaKey,
                SyncField.RESUME_POSITION,
                SyncEffectKind.RESUME_POSITION,
                payload = buildJsonObject {
                    put("chapterKey", chapterKey.stableKey)
                    put("pageIndex", event.lastPageRead)
                    put("totalPages", event.totalPages)
                },
            ),
        )
        add(
            SyncEffect(
                "summary",
                mangaKey,
                SyncField.READING_SUMMARY,
                SyncEffectKind.READING_SUMMARY,
                payload = buildJsonObject {
                    put("chapterKey", chapterKey.stableKey)
                    put("readAt", event.readAt.time)
                },
            ),
        )
    }
    return appendSyncOperation(event.syncContext, SyncCategory.READING, effects, event.readAt.time)
}

internal fun Database.appendChapterReadOperation(chapterId: Long, read: Boolean, context: SyncMutationContext) {
    if (!context.uploadAllowed || context.origin != SyncOrigin.USER) return
    val (chapterKey, _) = readingObjectKeys(chapterId)
    appendSyncOperation(
        context,
        SyncCategory.READING,
        listOf(
            SyncEffect(
                "read",
                chapterKey,
                SyncField.READ_STATUS,
                if (read) SyncEffectKind.MARK_READ else SyncEffectKind.MARK_UNREAD,
            ),
        ),
    )
}

internal fun Database.readingObjectKeys(chapterId: Long): Pair<SyncObjectKey, SyncObjectKey> {
    val chapter = chaptersQueries.getChapterById(chapterId).executeAsOne()
    val manga = mangasQueries.getMangaById(chapter.manga_id).executeAsOne()
    return SyncObjectKey(
        SyncObjectType.CHAPTER,
        sourceId = manga.source.toString(),
        originalUrl = chapter.url,
        parentUrl = manga.url,
    ) to SyncObjectKey(SyncObjectType.MANGA, sourceId = manga.source.toString(), originalUrl = manga.url)
}
