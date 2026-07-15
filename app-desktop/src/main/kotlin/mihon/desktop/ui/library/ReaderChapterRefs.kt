package mihon.desktop.ui.library

import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.withDuplicateChapterFlags
import mihon.domain.reader.isReaderChapterFiltered
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

internal fun List<Chapter>.toReaderChapterRefs(
    currentChapterId: Long,
    manga: Manga,
    isChapterDownloaded: (Chapter) -> Boolean,
): List<ReaderChapterRef> = map { chapter ->
    ReaderChapterRef(
        id = chapter.id,
        url = chapter.url,
        name = chapter.name,
        isRead = chapter.read,
        chapterNumber = chapter.chapterNumber,
        scanlator = chapter.scanlator,
        isFiltered = isReaderChapterFiltered(
            unreadFilterRaw = manga.unreadFilterRaw,
            downloadedFilterRaw = manga.downloadedFilterRaw,
            bookmarkedFilterRaw = manga.bookmarkedFilterRaw,
            chapterIsRead = chapter.read,
            chapterIsBookmarked = chapter.bookmark,
            chapterIsDownloaded = isChapterDownloaded(chapter),
        ),
    )
}.withDuplicateChapterFlags(currentChapterId)
