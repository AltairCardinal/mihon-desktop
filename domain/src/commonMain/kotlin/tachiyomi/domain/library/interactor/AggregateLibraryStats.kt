package tachiyomi.domain.library.interactor

import tachiyomi.domain.library.model.LibraryManga

data class LibraryStats(
    val mangaCount: Int = 0,
    val startedCount: Int = 0,
    val totalChapters: Long = 0,
    val readChapters: Long = 0,
    val byCategory: Map<Long, Int> = emptyMap(),
    val bySource: Map<Long, Int> = emptyMap(),
    val byStatus: Map<Long, Int> = emptyMap(),
)

class AggregateLibraryStats {
    operator fun invoke(items: List<LibraryManga>): LibraryStats {
        val distinct = items.groupBy { it.id }.values.map { rows ->
            rows.first().copy(categories = rows.flatMap { it.categories }.distinct())
        }
        return LibraryStats(
            mangaCount = distinct.size,
            startedCount = distinct.count { it.hasStarted },
            totalChapters = distinct.sumOf { it.totalChapters },
            readChapters = distinct.sumOf { it.readCount },
            byCategory = distinct.flatMap { it.categories }.groupingBy { it }.eachCount(),
            bySource = distinct.groupingBy { it.manga.source }.eachCount(),
            byStatus = distinct.groupingBy { it.manga.status }.eachCount(),
        )
    }
}
