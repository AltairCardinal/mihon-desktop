package tachiyomi.domain.library.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga

class EvaluateLibraryTest {
    @Test
    fun `android alphabetical fixture is sorted inside category`() {
        val items = listOf(item(1, "Zulu", listOf(7)), item(2, "alpha", listOf(7)), item(3, "Beta", listOf(8)))
        EvaluateLibrary().invoke(items, 7, LibraryFilter(), LibrarySort.default).map { it.id } shouldBe listOf(2L, 1L)
    }

    @Test
    fun `android combined filter fixture requires every enabled condition`() {
        val items = listOf(
            item(1, "match", listOf(7), unread = 2, read = 1, completed = true),
            item(2, "unread only", listOf(7), unread = 2),
            item(3, "wrong category", listOf(8), unread = 2, read = 1, completed = true),
        )
        val filter = LibraryFilter(unread = true, started = true, completed = true)
        val result = EvaluateLibrary().invoke(items, 7, filter, LibrarySort.default)
        result.map { it.id } shouldBe listOf(1L)
    }

    private fun item(
        id: Long,
        title: String,
        categories: List<Long>,
        unread: Long = 0,
        read: Long = 0,
        completed: Boolean = false,
    ) =
        LibraryManga(
            Manga.create().copy(
                id = id,
                title = title,
                status = if (completed) 2 else 0,
            ),
            categories,
            unread + read,
            read,
            0,
            0,
            0,
            0,
        )
}
