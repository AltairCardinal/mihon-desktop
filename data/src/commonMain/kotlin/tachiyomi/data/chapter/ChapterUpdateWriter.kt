package tachiyomi.data.chapter

import mihon.data.sync.journal.appendChapterReadOperation
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.ChapterUpdate

internal fun Database.applyChapterUpdate(chapterUpdate: ChapterUpdate) {
    chaptersQueries.update(
        mangaId = chapterUpdate.mangaId,
        url = chapterUpdate.url,
        name = chapterUpdate.name,
        scanlator = chapterUpdate.scanlator,
        read = chapterUpdate.read,
        bookmark = chapterUpdate.bookmark,
        lastPageRead = chapterUpdate.lastPageRead,
        chapterNumber = chapterUpdate.chapterNumber,
        sourceOrder = chapterUpdate.sourceOrder,
        dateFetch = chapterUpdate.dateFetch,
        dateUpload = chapterUpdate.dateUpload,
        chapterId = chapterUpdate.id,
        version = chapterUpdate.version,
        isSyncing = 0,
        memo = chapterUpdate.memo?.let(tachiyomi.data.MemoColumnAdapter::encode),
    )
    chapterUpdate.read?.let { appendChapterReadOperation(chapterUpdate.id, it, chapterUpdate.syncContext) }
}
