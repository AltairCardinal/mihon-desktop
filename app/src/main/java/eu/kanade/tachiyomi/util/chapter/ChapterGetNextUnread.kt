package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import mihon.domain.reader.progress.ReaderChapterDisplayOrder
import mihon.domain.reader.progress.ReaderEntryCandidate
import mihon.domain.reader.progress.resolveReaderEntry
import mihon.domain.reader.session.ReaderChapterId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<Chapter>.getNextUnread(manga: Manga, downloadManager: DownloadManager): Chapter? {
    val chapters = applyFilters(manga, downloadManager)
    return resolveAndroidReaderEntry(chapters, manga.readerChapterDisplayOrder())
}

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<ChapterList.Item>.getNextUnread(manga: Manga): Chapter? {
    val chapters = applyFilters(manga).toList()
    val targetId = resolveReaderEntry(
        chapters.map { ReaderEntryCandidate(ReaderChapterId(it.chapter.id), it.chapter.read) },
        manga.readerChapterDisplayOrder(),
    )
    return chapters.firstOrNull { it.chapter.id == targetId?.value }?.chapter
}

internal fun resolveAndroidReaderEntry(
    chapters: List<Chapter>,
    displayOrder: ReaderChapterDisplayOrder,
): Chapter? {
    val targetId = resolveReaderEntry(
        chapters.map { ReaderEntryCandidate(ReaderChapterId(it.id), it.read) },
        displayOrder,
    )
    return chapters.firstOrNull { it.id == targetId?.value }
}

private fun Manga.readerChapterDisplayOrder(): ReaderChapterDisplayOrder =
    if (sortDescending()) {
        ReaderChapterDisplayOrder.STORY_DESCENDING
    } else {
        ReaderChapterDisplayOrder.STORY_ASCENDING
    }
