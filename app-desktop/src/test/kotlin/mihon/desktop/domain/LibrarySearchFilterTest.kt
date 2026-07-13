package mihon.desktop.domain

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga

class LibrarySearchFilterTest {
    @Test
    fun `desktop pipeline maps UI data into shared tri-state evaluator`() {
        val items = listOf(item(1, "Local", unread = 1), item(2, "Downloaded", unread = 1), item(3, "Read"))
        val result = LibrarySearchFilter.apply(
            items = items,
            filter = LibraryFilter(downloaded = TriState.ENABLED_IS, unread = TriState.ENABLED_IS),
            downloadedMangaIds = setOf(2),
            localMangaIds = setOf(1),
            sort = LibrarySort.default,
        )
        result.map { it.id }.shouldContainExactly(2L, 1L)
    }

    @Test
    fun `desktop search is applied without replacing shared ordering rules`() {
        val result = LibrarySearchFilter.apply(
            items = listOf(item(1, "Zulu", 1), item(2, "Alpha match", 1), item(3, "Beta match", 1)),
            searchQuery = "match",
            sort = LibrarySearchFilter.toSharedSort(SortMode.TITLE, ascending = false),
        )
        result.map { it.id }.shouldContainExactly(3L, 2L)
    }

    private fun item(id: Long, title: String, unread: Long = 0) = LibraryManga(
        Manga.create().copy(id = id, title = title), listOf(7), unread, 0, 0, 0, 0, 0,
    )
}
