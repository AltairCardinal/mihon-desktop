package mihon.data.sync.journal

import mihon.domain.reader.ChapterPairingRecord
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMetadataPolicy
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import tachiyomi.data.Database

internal fun Database.categorySnapshotEffect(mangaId: Long): SyncEffect {
    val manga = mangasQueries.getMangaById(mangaId).executeAsOne()
    val names = categoriesQueries.getCategoriesByMangaId(mangaId).executeAsList()
        .filter { it.id > 0 }.map { it.name }
    return SyncEffect(
        "categories",
        SyncObjectKey(SyncObjectType.MANGA, sourceId = manga.source.toString(), originalUrl = manga.url),
        SyncField.MANGA_CATEGORIES,
        SyncEffectKind.SET_CATEGORIES,
        payload = SyncMetadataPolicy.categoryPayload(names),
    )
}

internal fun Database.appendCategoryOperation(mangaId: Long, context: SyncMutationContext) {
    if (!context.uploadAllowed || context.origin != SyncOrigin.USER) return
    if (sync_journalQueries.getActiveActor().executeAsOneOrNull() == null) return
    appendSyncOperation(context, SyncCategory.FAVORITE, listOf(categorySnapshotEffect(mangaId)))
}

internal fun Database.appendPairingOperation(chapterId: Long, record: ChapterPairingRecord) {
    if (sync_journalQueries.getActiveActor().executeAsOneOrNull() == null) return
    val (key, _) = readingObjectKeys(chapterId)
    appendSyncOperation(
        SyncMutationContext.User,
        SyncCategory.READING,
        listOf(
            SyncEffect(
                "pairing",
                key,
                SyncField.CHAPTER_PAIRING,
                SyncEffectKind.SET_PAIRING,
                payload = SyncMetadataPolicy.pairingPayload(record),
            ),
        ),
    )
}
