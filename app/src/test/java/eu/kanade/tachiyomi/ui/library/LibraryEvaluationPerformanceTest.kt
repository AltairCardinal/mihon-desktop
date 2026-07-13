package eu.kanade.tachiyomi.ui.library

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.interactor.EvaluateLibrary
import tachiyomi.domain.library.interactor.LibraryEvaluationItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga

class LibraryEvaluationPerformanceTest {
    @Test
    fun `non tracker sort never requests tracker scores`() {
        var trackerScoreReads = 0
        val items = (1L..1_000L).map { LibraryEvaluationItem(item(it)) }

        EvaluateLibrary().sortForAndroid(
            items = items,
            sort = LibrarySort(LibrarySort.Type.DateAdded, LibrarySort.Direction.Descending),
            randomSeed = 0,
            trackerMeanProvider = {
                trackerScoreReads++
                error("Non-TrackerMean sort must not request tracker scores")
            },
        )

        trackerScoreReads shouldBe 0
    }

    @Test
    fun `tracker mean sort requests each score and preserves ordering semantics`() {
        var trackerScoreReads = 0
        val items = listOf(
            LibraryEvaluationItem(item(1, title = "Beta")),
            LibraryEvaluationItem(item(2, title = "Alpha")),
            LibraryEvaluationItem(item(3, title = "Gamma")),
        )
        val scores = mapOf(1L to 5.0, 2L to 5.0, 3L to 8.0)

        val result = EvaluateLibrary().sortForAndroid(
            items = items,
            sort = LibrarySort(LibrarySort.Type.TrackerMean, LibrarySort.Direction.Ascending),
            randomSeed = 0,
            trackerMeanProvider = {
                trackerScoreReads++
                scores[it.manga.id]
            },
        )

        trackerScoreReads shouldBe items.size
        result.map { it.manga.id }.shouldContainExactly(2L, 1L, 3L)
    }

    private fun item(id: Long, title: String = "M$id") = LibraryManga(
        manga = Manga.create().copy(id = id, title = title, dateAdded = id),
        categories = listOf(1),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )
}
