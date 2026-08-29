package mihon.desktop.ui.reader

import mihon.desktop.ui.reader.presentation.DisplayUnit
import mihon.desktop.ui.reader.presentation.ReaderPresentationRequest
import mihon.desktop.ui.reader.presentation.WebtoonInitialViewportBootstrapGate
import mihon.desktop.ui.reader.presentation.WebtoonPresentation
import mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor
import mihon.desktop.ui.reader.presentation.resolveWebtoonViewport
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

class WebtoonInitialViewportBootstrapContractTest {

    @Test
    fun `empty restore identity bootstraps the current display unit exactly once`() {
        val presentation = presentation()
        val gate = WebtoonInitialViewportBootstrapGate()

        val first = gate.take(
            presentation = presentation,
            currentPageId = pageId(1),
            currentDisplayUnitId = null,
            initialAnchor = null,
        )
        val repeated = gate.take(
            presentation = presentation,
            currentPageId = pageId(1),
            currentDisplayUnitId = null,
            initialAnchor = null,
        )

        val expectedUnit = presentation.displayUnits[1]
        assertEquals(expectedUnit.id, first?.visiblePages?.displayUnitId)
        assertEquals(setOf(pageId(1)), first?.visiblePages?.pageIds)
        assertEquals(pageId(1), first?.visiblePages?.activePageId)
        assertEquals(expectedUnit.id, first?.anchor?.displayUnitId)
        assertEquals(0, first?.anchor?.scrollOffset)
        assertNull(repeated, "The queued-page bootstrap must not submit the same synthetic viewport twice")
    }

    @Test
    fun `either restored display identity or restored anchor suppresses bootstrap`() {
        val presentation = presentation()
        val restoredUnit = presentation.displayUnits[1]
        val restoredAnchor = WebtoonScrollAnchor(
            displayUnitId = restoredUnit.id,
            scrollOffset = 37,
            itemSize = 400,
        )

        val displayIdentityGate = WebtoonInitialViewportBootstrapGate()
        assertNull(
            displayIdentityGate.take(
                presentation = presentation,
                currentPageId = pageId(1),
                currentDisplayUnitId = restoredUnit.id,
                initialAnchor = null,
            ),
            "An exact restored display identity must remain authoritative",
        )

        val anchorGate = WebtoonInitialViewportBootstrapGate()
        assertNull(
            anchorGate.take(
                presentation = presentation,
                currentPageId = pageId(1),
                currentDisplayUnitId = null,
                initialAnchor = restoredAnchor,
            ),
            "A persisted scroll anchor must not be replaced by a synthetic zero-offset anchor",
        )
    }

    @Test
    fun `a real settled viewport permanently closes the bootstrap window`() {
        val presentation = presentation()
        val gate = WebtoonInitialViewportBootstrapGate()
        val settled = checkNotNull(
            presentation.resolveWebtoonViewport(
                visibleItems = listOf(
                    mihon.desktop.ui.reader.presentation.WebtoonVisibleItem(
                        index = 1,
                        offset = -40,
                        size = 400,
                    ),
                ),
                viewportStartOffset = 0,
                viewportEndOffset = 360,
            ),
        )

        gate.onSettledViewport(settled)

        assertNull(
            gate.take(
                presentation = presentation,
                currentPageId = pageId(1),
                currentDisplayUnitId = null,
                initialAnchor = null,
            ),
            "A late recomposition must not bootstrap after the real LazyColumn viewport has settled",
        )
    }

    private fun presentation() = WebtoonPresentation.present(
        ReaderPresentationRequest(
            chapter = ReaderChapterSession(
                id = CHAPTER_ID,
                generation = 3L,
                loadState = ReaderChapterLoadState.Loaded,
                pages = List(3) { index ->
                    ReaderPageSession(
                        id = pageId(index),
                        url = "/page/$index",
                        imageUrl = null,
                        encodedPageRef = null,
                        loadState = ReaderPageLoadState.Queued,
                    )
                },
            ),
            direction = ReaderDirection.RTL,
        ),
    ).also { snapshot ->
        check(snapshot.displayUnits.map(DisplayUnit::id).size == 3)
    }

    private fun pageId(index: Int) = ReaderPageId(CHAPTER_ID, index)

    private companion object {
        val CHAPTER_ID = ReaderChapterId(91L)
    }
}
