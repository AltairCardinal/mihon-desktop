package mihon.desktop.ui.library

import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.withDuplicateChapterFlags
import tachiyomi.domain.chapter.model.Chapter

internal fun List<Chapter>.toReaderChapterRefs(
    currentChapterId: Long,
    visibleChapterIds: Set<Long>,
): List<ReaderChapterRef> = map { chapter ->
    ReaderChapterRef(
        id = chapter.id,
        url = chapter.url,
        name = chapter.name,
        isRead = chapter.read,
        chapterNumber = chapter.chapterNumber,
        scanlator = chapter.scanlator,
        isFiltered = chapter.id !in visibleChapterIds,
    )
}.withDuplicateChapterFlags(currentChapterId)
