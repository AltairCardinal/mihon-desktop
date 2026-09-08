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
