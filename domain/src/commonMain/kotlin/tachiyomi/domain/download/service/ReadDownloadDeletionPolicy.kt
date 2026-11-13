package tachiyomi.domain.download.service

import mihon.domain.reader.ChapterSkipPolicy
import mihon.domain.reader.ReaderChapterEntry
import mihon.domain.reader.filterChaptersForReader
import mihon.domain.reader.markDuplicateChapters
import tachiyomi.domain.chapter.model.Chapter

fun readDownloadDeletionCatalog(
    entries: List<ReaderChapterEntry>,
    currentChapterId: Long,
    skipPolicy: ChapterSkipPolicy,
): List<ReaderChapterEntry> {
    val candidates = filterChaptersForReader(entries, currentChapterId, skipPolicy.copy(duplicate = false))
    val marked = if (skipPolicy.duplicate) markDuplicateChapters(candidates, currentChapterId) else candidates
    return filterChaptersForReader(marked, currentChapterId, skipPolicy)
}

/** The catalog is in reading order (oldest first), after the reader's existing filters. */
fun <T> readDownloadDeletionCandidate(
    chapters: List<T>,
    completedIndex: Int,
    slots: Int,
    isRead: (T) -> Boolean,
): T? {
    if (slots !in 0..4 || completedIndex !in chapters.indices) return null
    return chapters.getOrNull(completedIndex - slots)?.takeIf { slots == 0 || isRead(it) }
}

/** Matches Android's default-category and any-category exclusion semantics. */
fun filterReadDownloadDeletion(
    chapters: List<Chapter>,
    categoryIds: List<Long>,
    excludedCategoryIds: Set<String>,
    removeBookmarks: Boolean,
): List<Chapter> {
    val excluded = categoryIds.ifEmpty { listOf(0L) }.any { it.toString() in excludedCategoryIds }
    return chapters.filter { (!excluded || !it.read) && (removeBookmarks || !it.bookmark) }
}
