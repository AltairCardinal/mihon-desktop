package tachiyomi.domain.library

import tachiyomi.domain.chapter.model.Chapter

/**
 * Returns queueable chapters after excluding active queue entries and completed downloads.
 * The limit is deliberately applied after both exclusions, matching the library action contract.
 */
fun selectLibraryDownloadChapters(
    candidates: List<Chapter>,
    limit: Int? = null,
    isQueued: (Chapter) -> Boolean,
    isDownloaded: (Chapter) -> Boolean,
): List<Chapter> {
    val available = candidates.filterNot { chapter ->
        isQueued(chapter) || isDownloaded(chapter)
    }
    return limit?.let { available.take(it.coerceAtLeast(0)) } ?: available
}

/** Manual actions use the original chapter order, with platform availability applied before the limit. */
fun selectManualDownloadChapters(
    candidates: List<Chapter>,
    manga: tachiyomi.domain.manga.model.Manga,
    bookmarkedOnly: Boolean = false,
    limit: Int? = null,
    isQueued: (Chapter) -> Boolean,
    isDownloaded: (Chapter) -> Boolean,
    isDownloadable: (Chapter) -> Boolean = { true },
): List<Chapter> = selectLibraryDownloadChapters(
    candidates = candidates.filter { (if (bookmarkedOnly) it.bookmark else !it.read) && isDownloadable(it) }
        .sortedWith(tachiyomi.domain.chapter.service.getChapterSort(manga, sortDescending = false)),
    limit = limit,
    isQueued = isQueued,
    isDownloaded = isDownloaded,
)
