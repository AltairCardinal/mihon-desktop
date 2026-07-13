package tachiyomi.domain.library.interactor

import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort

data class LibraryFilter(
    val unread: Boolean = false,
    val started: Boolean = false,
    val completed: Boolean = false,
    val downloaded: Boolean = false,
    val downloadedMangaIds: Set<Long> = emptySet(),
)

class EvaluateLibrary {
    operator fun invoke(
        items: List<LibraryManga>,
        categoryId: Long?,
        filter: LibraryFilter,
        sort: LibrarySort,
    ): List<LibraryManga> {
        val filtered = items.asSequence()
            .filter { categoryId == null || categoryId in it.categories }
            .filter { !filter.unread || it.unreadCount > 0 }
            .filter { !filter.started || it.hasStarted }
            .filter { !filter.completed || it.manga.status == 2L }
            .filter { !filter.downloaded || it.id in filter.downloadedMangaIds }
            .toList()
        val comparator = when (sort.type) {
            LibrarySort.Type.Alphabetical -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.manga.title }
            LibrarySort.Type.LastRead -> compareBy<LibraryManga> { it.lastRead }
            LibrarySort.Type.LastUpdate -> compareBy { it.manga.lastUpdate }
            LibrarySort.Type.UnreadCount -> compareBy { it.unreadCount }
            LibrarySort.Type.TotalChapters -> compareBy { it.totalChapters }
            LibrarySort.Type.LatestChapter -> compareBy { it.latestUpload }
            LibrarySort.Type.ChapterFetchDate -> compareBy { it.chapterFetchedAt }
            LibrarySort.Type.DateAdded -> compareBy { it.manga.dateAdded }
            LibrarySort.Type.TrackerMean, LibrarySort.Type.Random -> compareBy { it.id }
        }
        return filtered.sortedWith(if (sort.isAscending) comparator else comparator.reversed())
    }
}
