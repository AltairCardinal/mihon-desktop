package mihon.desktop.domain

import tachiyomi.domain.library.interactor.EvaluateLibrary
import tachiyomi.domain.library.interactor.LibraryEvaluationItem
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.matchesLibraryQuery
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort

enum class SortMode {
    TITLE,
    LAST_READ,
    LAST_UPDATE,
    UNREAD_COUNT,
    TOTAL_CHAPTERS,
    LATEST_CHAPTER,
    CHAPTER_FETCH_DATE,
    DATE_ADDED,
    TRACKER_MEAN,
    RANDOM,
}

object LibrarySearchFilter {
    private val evaluator = EvaluateLibrary()

    fun applySearch(
        items: List<LibraryManga>,
        query: String?,
        sourceNames: Map<Long, String> = emptyMap(),
        localSourceId: Long = LOCAL_SOURCE_ID,
    ): List<LibraryManga> {
        if (query.isNullOrBlank()) return items
        val normalized = query.trim()
        return items.filter { item ->
            matchesLibraryQuery(item, normalized, sourceNames[item.manga.source].orEmpty(), localSourceId)
        }
    }

    fun apply(
        items: List<LibraryManga>,
        categoryId: Long? = null,
        searchQuery: String? = null,
        filter: LibraryFilter = LibraryFilter(),
        downloadedMangaIds: Set<Long> = emptySet(),
        downloadCountsByManga: Map<Long, Long> = emptyMap(),
        localMangaIds: Set<Long> = emptySet(),
        trackerIds: Map<Long, Set<Long>> = emptyMap(),
        trackerMeans: Map<Long, Double> = emptyMap(),
        sort: LibrarySort = LibrarySort.default,
        randomSeed: Int = 0,
        sourceNames: Map<Long, String> = emptyMap(),
        localSourceId: Long = LOCAL_SOURCE_ID,
    ): List<LibraryManga> {
        val evaluated = items.map {
            LibraryEvaluationItem(
                manga = it,
                downloadCount = downloadCountsByManga[it.id]
                    ?: if (it.id in downloadedMangaIds) 1 else 0,
                isLocal = it.id in localMangaIds,
                trackerIds = trackerIds[it.id].orEmpty(),
                trackerMean = trackerMeans[it.id],
            )
        }
        val normalizedQuery = searchQuery?.trim().orEmpty()
        val filtered = evaluator.filter(evaluated, categoryId, filter).filter { item ->
            normalizedQuery.isEmpty() || matchesLibraryQuery(
                item.manga,
                normalizedQuery,
                sourceNames[item.manga.manga.source].orEmpty(),
                localSourceId,
            )
        }
        return evaluator.sort(filtered, sort, randomSeed).map { it.manga }
    }

    fun toSharedSort(mode: SortMode, ascending: Boolean) = LibrarySort(
        type = when (mode) {
            SortMode.TITLE -> LibrarySort.Type.Alphabetical
            SortMode.LAST_READ -> LibrarySort.Type.LastRead
            SortMode.LAST_UPDATE -> LibrarySort.Type.LastUpdate
            SortMode.UNREAD_COUNT -> LibrarySort.Type.UnreadCount
            SortMode.TOTAL_CHAPTERS -> LibrarySort.Type.TotalChapters
            SortMode.LATEST_CHAPTER -> LibrarySort.Type.LatestChapter
            SortMode.CHAPTER_FETCH_DATE -> LibrarySort.Type.ChapterFetchDate
            SortMode.DATE_ADDED -> LibrarySort.Type.DateAdded
            SortMode.TRACKER_MEAN -> LibrarySort.Type.TrackerMean
            SortMode.RANDOM -> LibrarySort.Type.Random
        },
        direction = if (ascending) LibrarySort.Direction.Ascending else LibrarySort.Direction.Descending,
    )

}

private const val LOCAL_SOURCE_ID = 0L
