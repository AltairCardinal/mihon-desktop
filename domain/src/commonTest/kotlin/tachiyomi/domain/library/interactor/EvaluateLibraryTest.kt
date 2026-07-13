package tachiyomi.domain.library.interactor

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga

class EvaluateLibraryTest {
    private val evaluator = EvaluateLibrary()

    @Test
    fun `all tri-state filters preserve Android IS NOT and disabled semantics`() {
        val match = item(1, unread = 2, read = 1, bookmarks = 1, status = SManga.COMPLETED.toLong(), interval = -1)
        val opposite = item(2)
        val inputs = listOf(match, opposite).map { LibraryEvaluationItem(it) }
        listOf(
            LibraryFilter(unread = TriState.ENABLED_IS) to listOf(1L),
            LibraryFilter(unread = TriState.ENABLED_NOT) to listOf(2L),
            LibraryFilter(started = TriState.ENABLED_IS) to listOf(1L),
            LibraryFilter(started = TriState.ENABLED_NOT) to listOf(2L),
            LibraryFilter(bookmarked = TriState.ENABLED_IS) to listOf(1L),
            LibraryFilter(bookmarked = TriState.ENABLED_NOT) to listOf(2L),
            LibraryFilter(completed = TriState.ENABLED_IS) to listOf(1L),
            LibraryFilter(completed = TriState.ENABLED_NOT) to listOf(2L),
            LibraryFilter(intervalCustom = TriState.ENABLED_IS, skipOutsideReleasePeriod = true) to listOf(1L),
            LibraryFilter(intervalCustom = TriState.ENABLED_NOT, skipOutsideReleasePeriod = true) to listOf(2L),
            LibraryFilter(unread = TriState.DISABLED) to listOf(1L, 2L),
        ).forEach { (filter, expected) ->
            evaluator(inputs, null, filter, LibrarySort.default).map { it.manga.id } shouldContainExactly expected
        }
    }

    @Test
    fun `download global local tracking and multiple flags match Android boundaries`() {
        val items = listOf(
            LibraryEvaluationItem(
                item(1, unread = 1, read = 1),
                downloadCount = 0,
                isLocal = true,
                trackerIds = setOf(10),
            ),
            LibraryEvaluationItem(item(2, unread = 1, read = 1), downloadCount = 1, trackerIds = setOf(20)),
            LibraryEvaluationItem(item(3, unread = 1), downloadCount = 1, trackerIds = setOf(10, 20)),
            LibraryEvaluationItem(item(4, unread = 1, read = 1), trackerIds = emptySet()),
        )
        val filter = LibraryFilter(
            downloaded = TriState.ENABLED_NOT,
            unread = TriState.ENABLED_IS,
            started = TriState.ENABLED_IS,
            globalDownloadedOnly = true,
            tracking = mapOf(10L to TriState.ENABLED_IS, 20L to TriState.ENABLED_NOT),
        )
        evaluator(items, null, filter, LibrarySort.default).map { it.manga.id } shouldContainExactly listOf(1L)
    }

    @Test
    fun `tracking disabled and logged-out maps are ignored`() {
        val items = listOf(LibraryEvaluationItem(item(1), trackerIds = emptySet()))
        evaluator(
            items,
            null,
            LibraryFilter(tracking = mapOf(10L to TriState.DISABLED)),
            LibrarySort.default,
        ).map { it.manga.id } shouldContainExactly listOf(1L)
        evaluator(items, null, LibraryFilter(tracking = emptyMap()), LibrarySort.default)
            .map { it.manga.id } shouldContainExactly listOf(1L)
    }

    @Test
    fun `every sort type supports both directions and final title collator tie-break`() {
        val a = LibraryEvaluationItem(item(1, title = "éclair", unread = 1), trackerMean = 5.0)
        val b = LibraryEvaluationItem(item(2, title = "Zulu", unread = 2), trackerMean = 8.0)
        val tied = LibraryEvaluationItem(item(3, title = "Alpha", unread = 1), trackerMean = 5.0)
        val items = listOf(a, b, tied)
        sortTypes.filterNot { it == LibrarySort.Type.Random }.forEach { type ->
            val asc = evaluator(items, null, LibraryFilter(), LibrarySort(type, LibrarySort.Direction.Ascending))
                .map { it.manga.id }
            val desc = evaluator(items, null, LibraryFilter(), LibrarySort(type, LibrarySort.Direction.Descending))
                .map { it.manga.id }
            if (type == LibrarySort.Type.Alphabetical) {
                asc.shouldContainExactly(3L, 1L, 2L)
                desc.shouldContainExactly(2L, 1L, 3L)
            } else if (type == LibrarySort.Type.UnreadCount) {
                asc.shouldContainExactly(3L, 1L, 2L)
                desc.shouldContainExactly(2L, 3L, 1L)
            } else if (type == LibrarySort.Type.TrackerMean) {
                asc.shouldContainExactly(3L, 1L, 2L)
                desc.shouldContainExactly(2L, 3L, 1L)
            } else if (type == LibrarySort.Type.TotalChapters) {
                asc.shouldContainExactly(3L, 1L, 2L)
                desc.shouldContainExactly(2L, 3L, 1L)
            } else {
                asc.shouldContainExactly(1L, 2L, 3L)
                desc.shouldContainExactly(3L, 2L, 1L)
            }
        }
    }

    @Test
    fun `zero unread is always last and random uses stable Android seed behavior`() {
        val items = listOf(
            LibraryEvaluationItem(item(1, unread = 2)),
            LibraryEvaluationItem(item(2)),
            LibraryEvaluationItem(item(3, unread = 1)),
        )
        listOf(LibrarySort.Direction.Ascending, LibrarySort.Direction.Descending).forEach { direction ->
            val result = evaluator(items, null, LibraryFilter(), LibrarySort(LibrarySort.Type.UnreadCount, direction))
            result.last().manga.id shouldBe 2L
        }
        val sort = LibrarySort(LibrarySort.Type.Random, LibrarySort.Direction.Descending)
        evaluator(items, null, LibraryFilter(), sort, randomSeed = 123).map { it.manga.id } shouldContainExactly
            evaluator(items, null, LibraryFilter(), sort, randomSeed = 123).map { it.manga.id }
    }

    private fun item(
        id: Long,
        title: String = "M$id",
        unread: Long = 0,
        read: Long = 0,
        bookmarks: Long = 0,
        status: Long = 0,
        interval: Int = 0,
    ) =
        LibraryManga(
            Manga.create().copy(
                id = id,
                title = title,
                status = status,
                fetchInterval = interval,
                lastUpdate = id,
                dateAdded = id,
            ),
            listOf(7),
            unread + read,
            read,
            bookmarks,
            id,
            id,
            id,
        )

    private val sortTypes = listOf(
        LibrarySort.Type.Alphabetical,
        LibrarySort.Type.LastRead,
        LibrarySort.Type.LastUpdate,
        LibrarySort.Type.UnreadCount,
        LibrarySort.Type.TotalChapters,
        LibrarySort.Type.LatestChapter,
        LibrarySort.Type.ChapterFetchDate,
        LibrarySort.Type.DateAdded,
        LibrarySort.Type.TrackerMean,
        LibrarySort.Type.Random,
    )
}
