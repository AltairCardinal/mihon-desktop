package mihon.desktop.domain

import tachiyomi.domain.library.interactor.EvaluateLibrary
import tachiyomi.domain.library.interactor.LibraryEvaluationItem
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort

enum class SortMode { TITLE, UNREAD_COUNT, DATE_ADDED, LAST_READ }

object LibrarySearchFilter {
    private val evaluator = EvaluateLibrary()

    fun applySearch(items: List<LibraryManga>, query: String?): List<LibraryManga> {
        if (query.isNullOrBlank()) return items
        val normalized = query.trim().lowercase()
        return items.filter { normalized in it.manga.title.lowercase() }
    }

    fun apply(
        items: List<LibraryManga>,
        categoryId: Long? = null,
        searchQuery: String? = null,
        filter: LibraryFilter = LibraryFilter(),
        downloadedMangaIds: Set<Long> = emptySet(),
        localMangaIds: Set<Long> = emptySet(),
        trackerIds: Map<Long, Set<Long>> = emptyMap(),
        trackerMeans: Map<Long, Double> = emptyMap(),
        sort: LibrarySort = LibrarySort.default,
        randomSeed: Int = 0,
    ): List<LibraryManga> {
        val evaluated = items.map {
            LibraryEvaluationItem(
                manga = it,
                downloadCount = if (it.id in downloadedMangaIds) 1 else 0,
                isLocal = it.id in localMangaIds,
                trackerIds = trackerIds[it.id].orEmpty(),
                trackerMean = trackerMeans[it.id],
            )
        }
        return evaluator(evaluated, categoryId, filter, sort, randomSeed)
            .map { it.manga }
            .let { applySearch(it, searchQuery) }
    }

    fun toSharedSort(mode: SortMode, ascending: Boolean) = LibrarySort(
        type = when (mode) {
            SortMode.TITLE -> LibrarySort.Type.Alphabetical
            SortMode.UNREAD_COUNT -> LibrarySort.Type.UnreadCount
            SortMode.DATE_ADDED -> LibrarySort.Type.DateAdded
            SortMode.LAST_READ -> LibrarySort.Type.LastRead
        },
        direction = if (ascending) LibrarySort.Direction.Ascending else LibrarySort.Direction.Descending,
    )
}
