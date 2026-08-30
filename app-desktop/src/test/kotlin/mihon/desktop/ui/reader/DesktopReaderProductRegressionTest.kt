package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.buildVirtualPageList
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.ui.reader.presentation.DesktopReaderPresentationRegistry
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.desktopReaderPresentationRequest
import mihon.domain.reader.ReaderDirection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderProductRegressionTest {

    @Test
    fun `adjust spread shifts subsequent pairing and can restore the previous boundary`() {
        val baseline = ReaderScreenModel(
            initialSessionState = desktopReaderSessionState(pageCount = 6),
        ).state.value.copy(
            currentPage = 2,
            dualPageMode = true,
        )

        val shiftedSingles = adjustedForcedSinglePages(baseline)

        assertEquals(setOf(1), shiftedSingles)
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5)),
            dualGroups(pageCount = 6, forcedSinglePages = shiftedSingles),
        )

        val restoredSingles = adjustedForcedSinglePages(
            baseline.copy(currentPage = 3, forcedSinglePages = shiftedSingles),
        )
        assertEquals(emptySet<Int>(), restoredSingles)
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)),
            dualGroups(pageCount = 6, forcedSinglePages = restoredSingles),
        )
    }

    @Test
    fun `manual spread adjustment takes precedence over automatic matched pairs`() {
        val automaticallyMatched = ReaderScreenModel(
            initialSessionState = desktopReaderSessionState(pageCount = 6),
        ).state.value.copy(
            currentPage = 3,
            dualPageMode = true,
            readingMode = ReadingMode.LTR,
            matchedPairs = setOf(2 to 3),
        )

        val adjustedPages = adjustedForcedSinglePages(automaticallyMatched)
        val manuallyAdjusted = automaticallyMatched.copy(
            currentPage = 4,
            forcedSinglePages = adjustedPages,
        )

        assertEquals(setOf(2), adjustedPages)
        assertEquals(emptySet<Pair<Int, Int>>(), manuallyAdjusted.effectiveMatchedPairs())
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5)),
            manuallyAdjusted.dualPresentationSnapshot().displayUnits.map { unit ->
                unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }
            },
        )

        val restoredPages = adjustedForcedSinglePages(manuallyAdjusted)

        assertEquals(emptySet<Int>(), restoredPages)
        assertEquals(
            setOf(2 to 3),
            manuallyAdjusted.copy(forcedSinglePages = restoredPages).effectiveMatchedPairs(),
        )
    }

    @Test
    fun `adjust spread preserves unrelated manual boundaries`() {
        val state = ReaderScreenModel(
            initialSessionState = desktopReaderSessionState(pageCount = 8),
        ).state.value.copy(
            currentPage = 3,
            dualPageMode = true,
            forcedSinglePages = setOf(1, 4),
            matchedPairs = setOf(2 to 3),
        )

        assertEquals(setOf(4), adjustedForcedSinglePages(state))
    }

    @Test
    fun `shared pairing keeps cover edge matching adjust and landscape parity enhancements`() {
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3)),
            dualGroups(pageCount = 4),
        )
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5)),
            dualGroups(pageCount = 6, matchedPairs = setOf(2 to 3)),
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6)),
            dualGroups(pageCount = 7, spreadPages = setOf(3)),
        )
        assertEquals(listOf(1), dualGroups(pageCount = 4, forcedSinglePages = setOf(1))[1])
    }

    @Test
    fun `edge matching adapter only invokes production matcher for enabled dual page reading`() = runBlocking {
        var invocations = 0
        val matcher: suspend (Int, (Int) -> androidx.compose.ui.graphics.ImageBitmap?) -> Set<Pair<Int, Int>> = { _, _ ->
            invocations++
            setOf(2 to 3)
        }
        val cachedPage: (Int) -> androidx.compose.ui.graphics.ImageBitmap? = { null }

        assertEquals(
            setOf(2 to 3),
            resolveDesktopMatchedPairs(true, true, pageCount = 4, pageAt = cachedPage, findMatchedPairs = matcher),
        )
        assertEquals(
            emptySet<Pair<Int, Int>>(),
            resolveDesktopMatchedPairs(false, true, pageCount = 2, pageAt = cachedPage, findMatchedPairs = matcher),
        )
        assertEquals(
            emptySet<Pair<Int, Int>>(),
            resolveDesktopMatchedPairs(true, false, pageCount = 2, pageAt = cachedPage, findMatchedPairs = matcher),
        )
        assertEquals(1, invocations)
    }

    @Test
    fun `webtoon auto scroll adapter distinguishes disabled scrolling and chapter boundary`() {
        assertEquals(
            WebtoonAutoScrollAction.Idle,
            webtoonAutoScrollAction(false, lastVisibleIndex = 2, totalItemsCount = 3, lastVisibleBottom = 100, viewportEnd = 100),
        )
        assertEquals(
            WebtoonAutoScrollAction.Scroll,
            webtoonAutoScrollAction(true, lastVisibleIndex = 1, totalItemsCount = 3, lastVisibleBottom = 100, viewportEnd = 100),
        )
        assertEquals(
            WebtoonAutoScrollAction.NextChapter,
            webtoonAutoScrollAction(true, lastVisibleIndex = 2, totalItemsCount = 3, lastVisibleBottom = 100, viewportEnd = 100),
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
            forcedSinglePageIndices = forcedSinglePages,
            matchedPagePairs = matchedPairs,
            splitWidePages = false,
        )
        return DesktopReaderPresentationRegistry
            .require(ReaderPresentationMode.DUAL_PAGED)
            .present(request)
            .displayUnits
            .map { unit -> unit.slots.mapNotNull { it.page?.id?.sourcePageIndex }.distinct().sorted() }
    }
}
