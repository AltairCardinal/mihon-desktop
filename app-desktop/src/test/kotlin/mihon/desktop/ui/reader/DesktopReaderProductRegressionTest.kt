package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.buildVirtualPageList
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.ui.reader.presentation.DesktopReaderPresentationRegistry
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.desktopReaderPresentationRequest
import mihon.desktop.ui.reader.presentation.withSharedOwner
import mihon.domain.reader.ReaderDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderProductRegressionTest {

    @Test
    fun `adjust spread shifts subsequent pairing and restores previous boundary`() {
        val baseline = dualGroups(pageCount = 6)
        val shifted = mihon.domain.reader.adjustReaderPairing(2, baseline[1], emptySet()).forcedSinglePages
        assertEquals(setOf(1), shifted)
        val adjusted = dualGroups(pageCount = 6, forcedSinglePages = shifted)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5)), adjusted)
        val restored = mihon.domain.reader.adjustReaderPairing(3, adjusted[2], shifted).forcedSinglePages
        assertEquals(emptySet<Int>(), restored)
        assertEquals(baseline, dualGroups(pageCount = 6, forcedSinglePages = restored))
    }

    @Test
    fun `legacy manual boundary and its following pair override overlapping automatic edges`() {
        val groups = dualGroups(
            pageCount = 7,
            forcedSinglePages = setOf(2),
            matchedPairs = setOf(2 to 3, 4 to 5),
        )
        // A legacy boundary also promises the immediately following legal pair (3, 4).
        // Both automatic edges overlap that manual intent; (4, 5) is not unrelated.
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5, 6)), groups)
        assertFalse(listOf(2, 3) in groups)
        assertFalse(listOf(4, 5) in groups)
    }

    @Test
    fun `legacy manual boundary retains a distant automatic edge that is not a default pair`() {
        val overlapping = setOf(2 to 3, 4 to 5)
        val withoutDistantMatch = dualGroups(
            pageCount = 9,
            forcedSinglePages = setOf(2),
            matchedPairs = overlapping,
        )
        val withDistantMatch = dualGroups(
            pageCount = 9,
            forcedSinglePages = setOf(2),
            matchedPairs = overlapping + (6 to 7),
        )

        assertFalse(listOf(6, 7) in withoutDistantMatch, "Control must not pair by default: $withoutDistantMatch")
        assertTrue(listOf(6, 7) in withDistantMatch, "Distant automatic match must survive: $withDistantMatch")
        assertTrue(listOf(2) in withDistantMatch)
        assertTrue(listOf(3, 4) in withDistantMatch)
        assertFalse(listOf(2, 3) in withDistantMatch)
        assertFalse(listOf(4, 5) in withDistantMatch)
    }

    @Test
    fun `adjust spread preserves unrelated manual boundaries`() {
        val boundaries = setOf(1, 4)
        val groups = dualGroups(pageCount = 8, forcedSinglePages = boundaries)
        val current = groups.single { 3 in it }
        assertEquals(setOf(4), mihon.domain.reader.adjustReaderPairing(3, current, boundaries).forcedSinglePages)
    }

    @Test
    fun `shared pairing keeps cover edge matching adjust and spread phase`() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), dualGroups(pageCount = 4))
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5)),
            dualGroups(pageCount = 6, matchedPairs = setOf(2 to 3)),
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5), listOf(6)),
            dualGroups(pageCount = 7, spreadPages = setOf(3)),
        )
        assertEquals(listOf(1), dualGroups(pageCount = 4, forcedSinglePages = setOf(1))[1])
    }

    @Test
    fun `webtoon auto scroll adapter distinguishes disabled scrolling and chapter boundary`() {
        assertEquals(
            WebtoonAutoScrollAction.Idle,
            webtoonAutoScrollAction(
                false,
                lastVisibleIndex = 2,
                totalItemsCount = 3,
                lastVisibleBottom = 100,
                viewportEnd = 100,
            ),
        )
        assertEquals(
            WebtoonAutoScrollAction.Scroll,
            webtoonAutoScrollAction(
                true,
                lastVisibleIndex = 1,
                totalItemsCount = 3,
                lastVisibleBottom = 100,
                viewportEnd = 100,
            ),
        )
        assertEquals(
            WebtoonAutoScrollAction.NextChapter,
            webtoonAutoScrollAction(
                true,
                lastVisibleIndex = 2,
                totalItemsCount = 3,
                lastVisibleBottom = 100,
                viewportEnd = 100,
            ),
        )
    }

    @Test
    fun `delegated page navigation keeps transform pan and double tap gestures enabled`() {
        val delegated = zoomableGestureCapabilities(handlesTapNavigation = false, hasNavigationCallbacks = false)
        assertTrue(delegated.transformEnabled)
        assertTrue(delegated.doubleTapResetEnabled)
        assertFalse(delegated.tapNavigationEnabled)

        val independent = zoomableGestureCapabilities(handlesTapNavigation = true, hasNavigationCallbacks = true)
        assertTrue(independent.transformEnabled)
        assertTrue(independent.doubleTapResetEnabled)
        assertTrue(independent.tapNavigationEnabled)
    }

    @Test
    fun `virtual pages and keyboard use the same shared direction contract`() {
        val rtlPages = buildVirtualPageList(2, setOf(0), isRtl = true)

        assertEquals(0, rtlPages[0].realIndex)
        assertEquals(PageSplitHalf.RIGHT, rtlPages[0].splitHalf)
        assertEquals(ReaderPageAction.GoToPage(2), ReaderKeyboardAction.forLeft(true, 1, 3))
        assertEquals(ReaderPageAction.GoToPage(0), ReaderKeyboardAction.forRight(true, 1, 3))
    }

    @Test
    fun `grayscale and invert remain effective reader filters`() {
        val filter = ReaderColorFilter(grayscaleEnabled = true, invertEnabled = true)

        assertTrue(filter.isEffective)
        assertTrue(filter.grayscaleEnabled)
        assertTrue(filter.invertEnabled)
    }

    private fun dualGroups(
        pageCount: Int,
        spreadPages: Set<Int> = emptySet(),
        forcedSinglePages: Set<Int> = emptySet(),
        matchedPairs: Set<Pair<Int, Int>> = emptySet(),
    ): List<List<Int>> {
        val request = desktopReaderPresentationRequest(
            chapter = readerChapterSession(pageCount = pageCount),
            direction = ReaderDirection.LTR,
            spreadPageIndices = spreadPages,
            splitWidePages = false,
        ).let { request ->
            request.copy(
                pageSizes = request.chapter.pages.associate { page ->
                    page.id to if (page.id.sourcePageIndex in spreadPages) {
                        mihon.domain.reader.ReaderPageSize(1800, 1000)
                    } else {
                        mihon.domain.reader.ReaderPageSize(900, 1300)
                    }
                },
            )
        }
        val owned = request.withSharedOwner(forcedSinglePages, matchedPairs)
        return DesktopReaderPresentationRegistry
            .require(ReaderPresentationMode.DUAL_PAGED)
            .present(owned)
            .displayUnits
            .map { unit -> unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }.distinct().sorted() }
    }
}
