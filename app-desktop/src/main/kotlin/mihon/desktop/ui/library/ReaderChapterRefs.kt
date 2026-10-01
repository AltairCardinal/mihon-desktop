package mihon.desktop.ui.library

import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.withDuplicateChapterFlags
import mihon.domain.reader.isReaderChapterFiltered
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

internal fun List<Chapter>.toReaderChapterRefs(
    currentChapterId: Long,
    manga: Manga,
    downloadedOnly: Boolean = false,
    isChapterDownloaded: (Chapter) -> Boolean,
): List<ReaderChapterRef> = map { chapter ->
    ReaderChapterRef(
        id = chapter.id,
        url = chapter.url,
        name = chapter.name,
        isRead = chapter.read,
        chapterNumber = chapter.chapterNumber,
        scanlator = chapter.scanlator,
        isDownloaded = manga.source == 0L || isChapterDownloaded(chapter),
        isFiltered = isReaderChapterFiltered(
            unreadFilterRaw = manga.unreadFilterRaw,
            downloadedFilterRaw = if (downloadedOnly) Manga.CHAPTER_SHOW_DOWNLOADED else manga.downloadedFilterRaw,
            bookmarkedFilterRaw = manga.bookmarkedFilterRaw,
            chapterIsRead = chapter.read,
            chapterIsBookmarked = chapter.bookmark,
            chapterIsDownloaded = manga.source == 0L || isChapterDownloaded(chapter),
        ),
    )
}.withDuplicateChapterFlags(currentChapterId)
