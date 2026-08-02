package mihon.desktop.ui.reader.presentation

import mihon.domain.error.AppError
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderPageSize
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SinglePagedPresentationTest {

    @Test
    fun `late content and load-state changes preserve display-unit identities`() {
        val queued = request(page(loadState = ReaderPageLoadState.Queued))
        val ready = request(
            page(
                imageUrl = "https://example.test/page-0.jpg",
                loadState = ReaderPageLoadState.Ready,
            ),
        )
        val failed = request(page(loadState = ReaderPageLoadState.Error(AppError.Network())))

        val queuedSnapshot = SinglePagedPresentation.present(queued)
        val readySnapshot = SinglePagedPresentation.present(ready)
        val failedSnapshot = SinglePagedPresentation.present(failed)

        assertEquals(queuedSnapshot.displayUnits.map(DisplayUnit::id), readySnapshot.displayUnits.map(DisplayUnit::id))
        assertEquals(queuedSnapshot.displayUnits.map(DisplayUnit::id), failedSnapshot.displayUnits.map(DisplayUnit::id))
        assertEquals(ReaderPageLoadState.Queued, queuedSnapshot.displayUnits.single().slots.single().page?.loadState)
        assertEquals(ReaderPageLoadState.Ready, readySnapshot.displayUnits.single().slots.single().page?.loadState)
        assertEquals(
            ReaderPageLoadState.Error(AppError.Network()),
            failedSnapshot.displayUnits.single().slots.single().page?.loadState,
        )
    }

    @Test
    fun `LTR and RTL split a wide page in reading order without changing source identity`() {
        val first = page(sourcePageIndex = 0, loadState = ReaderPageLoadState.Ready)
        val second = page(sourcePageIndex = 1, loadState = ReaderPageLoadState.Ready)
        val splitPageIds = setOf(first.id)
        val sizes = mapOf(first.id to ReaderPageSize(width = 101, height = 200))

        val ltr = SinglePagedPresentation.present(
            request(first, second, direction = ReaderDirection.LTR, splitPageIds = splitPageIds, pageSizes = sizes),
        )
        val rtl = SinglePagedPresentation.present(
            request(first, second, direction = ReaderDirection.RTL, splitPageIds = splitPageIds, pageSizes = sizes),
        )

        assertEquals(
            listOf(PageSplitHalf.LEFT, PageSplitHalf.RIGHT, null),
            ltr.displayUnits.map { it.slots.single().splitHalf },
        )
        assertEquals(
            listOf(PageSplitHalf.RIGHT, PageSplitHalf.LEFT, null),
            rtl.displayUnits.map { it.slots.single().splitHalf },
        )
        assertEquals(listOf(first.id, first.id, second.id), ltr.displayUnits.map { it.slots.single().page?.id })
        assertEquals(50, ltr.displayUnits.first().slots.single().sourceBounds?.width)
        assertEquals(51, ltr.displayUnits[1].slots.single().sourceBounds?.width)
    }

    @Test
    fun `settled unit reports logical visible page and supports stable return lookup`() {
        val first = page(sourcePageIndex = 0, loadState = ReaderPageLoadState.Ready)
        val second = page(sourcePageIndex = 1, loadState = ReaderPageLoadState.Ready)
        val snapshot = SinglePagedPresentation.present(
            request(first, second, splitPageIds = setOf(first.id)),
        )

        assertEquals(0, snapshot.firstDisplayUnitIndex(first.id))
        assertEquals(2, snapshot.firstDisplayUnitIndex(second.id))
        assertEquals(
            VisiblePageSet(
                displayUnitId = snapshot.displayUnits[1].id,
                pageIds = setOf(first.id),
            ),
            snapshot.visiblePages(snapshot.displayUnits[1].id),
        )
        assertEquals(
            1,
            snapshot.restoreDisplayUnitIndex(
                pageId = first.id,
                displayUnitId = snapshot.displayUnits[1].id,
            ),
            "Returning to a split page must restore its exact second slice, not the first slice for the same PageId",
        )
    }

    @Test
    fun `registry selects the single strategy and rejects duplicate mode ownership`() {
        val registry = ReaderPresentationRegistry(listOf(SinglePagedPresentation))

        assertEquals(SinglePagedPresentation, registry.require(ReaderPresentationMode.SINGLE_PAGED))
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPresentationRegistry(listOf(SinglePagedPresentation, SinglePagedPresentation))
        }
    }

    private fun request(
        vararg pages: ReaderPageSession,
        direction: ReaderDirection = ReaderDirection.LTR,
        splitPageIds: Set<ReaderPageId> = emptySet(),
        pageSizes: Map<ReaderPageId, ReaderPageSize> = emptyMap(),
    ): ReaderPresentationRequest = ReaderPresentationRequest(
        chapter = ReaderChapterSession(
            id = chapterId,
            generation = 7,
            loadState = ReaderChapterLoadState.Loaded,
            pages = pages.toList(),
        ),
        direction = direction,
        splitPageIds = splitPageIds,
        pageSizes = pageSizes,
    )

    private fun page(
        sourcePageIndex: Int = 0,
        imageUrl: String? = null,
        loadState: ReaderPageLoadState,
    ): ReaderPageSession = ReaderPageSession(
        id = ReaderPageId(chapterId, sourcePageIndex),
        url = "/page/$sourcePageIndex",
        imageUrl = imageUrl,
        encodedPageRef = null,
        loadState = loadState,
    )

    private companion object {
        val chapterId = ReaderChapterId(41L)
    }
}
