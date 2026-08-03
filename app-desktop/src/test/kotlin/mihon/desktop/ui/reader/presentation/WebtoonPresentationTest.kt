package mihon.desktop.ui.reader.presentation

import mihon.domain.error.AppError
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebtoonPresentationTest {

    @Test
    fun `late page state and content preserve webtoon display identities`() {
        val queued = WebtoonPresentation.present(request(page(0, ReaderPageLoadState.Queued), page(1, ReaderPageLoadState.Queued)))
        val ready = WebtoonPresentation.present(
            request(
                page(0, ReaderPageLoadState.Ready, "https://example.test/0.jpg"),
                page(1, ReaderPageLoadState.Error(AppError.Network())),
            ),
        )

        assertEquals(queued.displayUnits.map(DisplayUnit::id), ready.displayUnits.map(DisplayUnit::id))
        assertEquals(listOf(ReaderPageLoadState.Queued, ReaderPageLoadState.Queued), queued.loadStates())
        assertEquals(
            listOf(ReaderPageLoadState.Ready, ReaderPageLoadState.Error(AppError.Network())),
            ready.loadStates(),
        )
    }

    @Test
    fun `wide webtoon page becomes consecutive halves in requested merge order`() {
        val wide = page(0, ReaderPageLoadState.Ready)
        val trailing = page(1, ReaderPageLoadState.Ready)

        val rtl = WebtoonPresentation.present(
            request(wide, trailing, direction = ReaderDirection.RTL, splitPageIds = setOf(wide.id)),
        )
        val ltr = WebtoonPresentation.present(
            request(wide, trailing, direction = ReaderDirection.LTR, splitPageIds = setOf(wide.id)),
        )

        assertEquals(listOf(PageSplitHalf.RIGHT, PageSplitHalf.LEFT, null), rtl.splitHalves())
        assertEquals(listOf(PageSplitHalf.LEFT, PageSplitHalf.RIGHT, null), ltr.splitHalves())
        assertEquals(listOf(wide.id, wide.id, trailing.id), rtl.pageIds())
    }

    @Test
    fun `viewport reports every visible page and uses fixed-main last end-visible active rule`() {
        val snapshot = WebtoonPresentation.present(
            request(
                page(0, ReaderPageLoadState.Ready),
                page(1, ReaderPageLoadState.Ready),
                page(2, ReaderPageLoadState.Ready),
            ),
        )

        val update = snapshot.resolveWebtoonViewport(
            visibleItems = listOf(
                WebtoonVisibleItem(index = 0, offset = -200, size = 400),
                WebtoonVisibleItem(index = 1, offset = 200, size = 600),
                WebtoonVisibleItem(index = 2, offset = 800, size = 600),
            ),
            viewportStartOffset = 0,
            viewportEndOffset = 1_000,
        )

        assertEquals(setOf(pageId(0), pageId(1), pageId(2)), update?.visiblePages?.pageIds)
        assertEquals(pageId(1), update?.visiblePages?.activePageId)
        assertEquals(snapshot.displayUnits[0].id, update?.anchor?.displayUnitId)
        assertEquals(200, update?.anchor?.scrollOffset)
    }

    @Test
    fun `last page becomes active at the settled chapter bottom`() {
        val snapshot = WebtoonPresentation.present(
            request(page(0, ReaderPageLoadState.Ready), page(1, ReaderPageLoadState.Ready), page(2, ReaderPageLoadState.Ready)),
        )

        val update = snapshot.resolveWebtoonViewport(
            visibleItems = listOf(
                WebtoonVisibleItem(index = 1, offset = -100, size = 300),
                WebtoonVisibleItem(index = 2, offset = 200, size = 800),
            ),
            viewportStartOffset = 0,
            viewportEndOffset = 1_000,
        )

        assertEquals(pageId(2), update?.visiblePages?.activePageId)
        assertEquals(snapshot.displayUnits[2].id, update?.visiblePages?.displayUnitId)
    }

    @Test
    fun `fixed-main active resolver returns no position when no visible child reaches either edge`() {
        val snapshot = WebtoonPresentation.present(request(page(0, ReaderPageLoadState.Ready)))

        val update = snapshot.resolveWebtoonViewport(
            visibleItems = listOf(WebtoonVisibleItem(index = 0, offset = 100, size = 1_200)),
            viewportStartOffset = 0,
            viewportEndOffset = 1_000,
        )

        assertNull(update)
    }

    @Test
    fun `anchor restores exact split item and falls back to its logical page`() {
        val wide = page(0, ReaderPageLoadState.Ready)
        val split = WebtoonPresentation.present(
            request(wide, page(1, ReaderPageLoadState.Ready), splitPageIds = setOf(wide.id)),
        )
        val secondHalfAnchor = WebtoonScrollAnchor(split.displayUnits[1].id, scrollOffset = 900, itemSize = 1_200)

        assertEquals(1, split.restoreWebtoonAnchorIndex(secondHalfAnchor))
        assertEquals(225, secondHalfAnchor.restoreOffsetFor(itemSize = 300))

        val merged = WebtoonPresentation.present(request(wide, page(1, ReaderPageLoadState.Ready)))
        assertEquals(0, merged.restoreWebtoonAnchorIndex(secondHalfAnchor))
    }

    @Test
    fun `registry exposes webtoon beside single-page presentation`() {
        assertEquals(
            WebtoonPresentation,
            DesktopReaderPresentationRegistry.require(ReaderPresentationMode.WEBTOON),
        )
        assertEquals(
            SinglePagedPresentation,
            DesktopReaderPresentationRegistry.require(ReaderPresentationMode.SINGLE_PAGED),
        )
    }

    private fun request(
        vararg pages: ReaderPageSession,
        direction: ReaderDirection = ReaderDirection.RTL,
        splitPageIds: Set<ReaderPageId> = emptySet(),
    ) = ReaderPresentationRequest(
        chapter = ReaderChapterSession(
            id = chapterId,
            generation = 5,
            loadState = ReaderChapterLoadState.Loaded,
            pages = pages.toList(),
        ),
        direction = direction,
        splitPageIds = splitPageIds,
    )

    private fun page(
        index: Int,
        state: ReaderPageLoadState,
        imageUrl: String? = null,
    ) = ReaderPageSession(
        id = pageId(index),
        url = "/page/$index",
        imageUrl = imageUrl,
        encodedPageRef = null,
        loadState = state,
    )

    private fun pageId(index: Int) = ReaderPageId(chapterId, index)

    private fun ReaderPresentationSnapshot.loadStates() =
        displayUnits.map { it.slots.single().page?.loadState }

    private fun ReaderPresentationSnapshot.splitHalves() =
        displayUnits.map { it.slots.single().splitHalf }

    private fun ReaderPresentationSnapshot.pageIds() =
        displayUnits.map { it.slots.single().page?.id }

    private companion object {
        val chapterId = ReaderChapterId(72L)
    }
}
