package tachiyomi.domain.chapter.service

import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter

fun Manga.effectiveDownloadedChapterFilter(downloadedOnly: Boolean = false): TriState = when {
    downloadedOnly -> TriState.ENABLED_IS
    downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED -> TriState.ENABLED_IS
    downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED -> TriState.ENABLED_NOT
    else -> TriState.DISABLED
}

fun Manga.hasActiveChapterFilters(downloadedOnly: Boolean = false): Boolean =
    unreadFilter != TriState.DISABLED || bookmarkedFilter != TriState.DISABLED ||
        effectiveDownloadedChapterFilter(downloadedOnly) != TriState.DISABLED

/** Platforms supply download availability; chapter flags and comparison remain shared. */
fun Chapter.matchesChapterFilters(
    manga: Manga,
    downloadedFilter: TriState = manga.effectiveDownloadedChapterFilter(),
    isLocal: Boolean = false,
    isDownloaded: () -> Boolean,
): Boolean = applyFilter(manga.unreadFilter) { !read } &&
    applyFilter(manga.bookmarkedFilter) { bookmark } &&
    applyFilter(downloadedFilter) { isLocal || isDownloaded() }

fun List<Chapter>.filterAndSortChapters(
    manga: Manga,
    downloadedOnly: Boolean = false,
    isLocal: Boolean = false,
    isDownloaded: (Chapter) -> Boolean,
): List<Chapter> = filter { chapter ->
    chapter.matchesChapterFilters(manga, manga.effectiveDownloadedChapterFilter(downloadedOnly), isLocal) {
        isDownloaded(chapter)
    }
}.sortedWith(getChapterSort(manga))
