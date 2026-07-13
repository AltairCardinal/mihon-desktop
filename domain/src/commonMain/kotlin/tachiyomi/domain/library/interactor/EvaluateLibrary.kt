package tachiyomi.domain.library.interactor

import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.applyFilter
import kotlin.random.Random

data class LibraryEvaluationItem(
    val manga: LibraryManga,
    val downloadCount: Long = 0,
    val isLocal: Boolean = false,
    val trackerIds: Set<Long> = emptySet(),
    val trackerMean: Double? = null,
)

data class LibraryFilter(
    val downloaded: TriState = TriState.DISABLED,
    val unread: TriState = TriState.DISABLED,
    val started: TriState = TriState.DISABLED,
    val bookmarked: TriState = TriState.DISABLED,
    val completed: TriState = TriState.DISABLED,
    val intervalCustom: TriState = TriState.DISABLED,
    val globalDownloadedOnly: Boolean = false,
    val skipOutsideReleasePeriod: Boolean = false,
    val tracking: Map<Long, TriState> = emptyMap(),
)

class EvaluateLibrary {
    operator fun invoke(
        items: List<LibraryEvaluationItem>,
        categoryId: Long?,
        filter: LibraryFilter,
        sort: LibrarySort,
        randomSeed: Int = 0,
    ): List<LibraryEvaluationItem> = sort(
        items = filter(items, categoryId, filter),
        sort = sort,
        randomSeed = randomSeed,
    )

    fun filter(
        items: List<LibraryEvaluationItem>,
        categoryId: Long?,
        filter: LibraryFilter,
    ): List<LibraryEvaluationItem> {
        val downloadedFilter = if (filter.globalDownloadedOnly) TriState.ENABLED_IS else filter.downloaded
        val excludedTracks = filter.tracking.filterValues { it == TriState.ENABLED_NOT }.keys
        val includedTracks = filter.tracking.filterValues { it == TriState.ENABLED_IS }.keys
        return items.filter { item ->
            (categoryId == null || categoryId in item.manga.categories) &&
                applyFilter(downloadedFilter) { item.isLocal || item.downloadCount > 0 } &&
                applyFilter(filter.unread) { item.manga.unreadCount > 0 } &&
                applyFilter(filter.started) { item.manga.hasStarted } &&
                applyFilter(filter.bookmarked) { item.manga.hasBookmarks } &&
                applyFilter(filter.completed) { item.manga.manga.status.toInt() == SManga.COMPLETED } &&
                (
                    !filter.skipOutsideReleasePeriod ||
                        applyFilter(filter.intervalCustom) { item.manga.manga.fetchInterval < 0 }
                    ) &&
                (
                    (includedTracks.isEmpty() || item.trackerIds.any { it in includedTracks }) &&
                        item.trackerIds.none { it in excludedTracks }
                    )
        }
    }

    fun sort(
        items: List<LibraryEvaluationItem>,
        sort: LibrarySort,
        randomSeed: Int = 0,
    ): List<LibraryEvaluationItem> {
        if (sort.type == LibrarySort.Type.Random) return items.shuffled(Random(randomSeed))

        val titleComparator = Comparator<LibraryEvaluationItem> { left, right ->
            left.manga.manga.title.lowercase().compareToWithCollator(right.manga.manga.title.lowercase())
        }
        val comparator = Comparator<LibraryEvaluationItem> { left, right ->
            when (sort.type) {
                LibrarySort.Type.Alphabetical -> titleComparator.compare(left, right)
                LibrarySort.Type.LastRead -> left.manga.lastRead.compareTo(right.manga.lastRead)
                LibrarySort.Type.LastUpdate -> left.manga.manga.lastUpdate.compareTo(right.manga.manga.lastUpdate)
                LibrarySort.Type.UnreadCount -> when {
                    left.manga.unreadCount == right.manga.unreadCount -> 0
                    left.manga.unreadCount == 0L -> if (sort.isAscending) 1 else -1
                    right.manga.unreadCount == 0L -> if (sort.isAscending) -1 else 1
                    else -> left.manga.unreadCount.compareTo(right.manga.unreadCount)
                }
                LibrarySort.Type.TotalChapters -> left.manga.totalChapters.compareTo(right.manga.totalChapters)
                LibrarySort.Type.LatestChapter -> left.manga.latestUpload.compareTo(right.manga.latestUpload)
                LibrarySort.Type.ChapterFetchDate -> left.manga.chapterFetchedAt.compareTo(right.manga.chapterFetchedAt)
                LibrarySort.Type.DateAdded -> left.manga.manga.dateAdded.compareTo(right.manga.manga.dateAdded)
                LibrarySort.Type.TrackerMean -> (left.trackerMean ?: -1.0).compareTo(right.trackerMean ?: -1.0)
                LibrarySort.Type.Random -> error("Random is shuffled before comparator creation")
            }
        }.let { if (sort.isAscending) it else it.reversed() }.then(titleComparator)
        return items.sortedWith(comparator)
    }
}
