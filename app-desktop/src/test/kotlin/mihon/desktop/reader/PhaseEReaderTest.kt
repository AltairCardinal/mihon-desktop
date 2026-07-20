package mihon.desktop.reader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * Phase E Reader Enhancement — Red-Green TDD tests.
 *
 * These tests verify:
 * 1. ReadingMode enum correctness
 * 2. DualPageState grouping logic (pairs of pages)
 * 3. DesktopReaderScreen can be instantiated with new isDualPage parameter
 * 4. ReadingMode derivation from isRtl/isWebtoon flags
 */
class PhaseEReaderTest {

    // ── 1. ReadingMode enum ────────────────────────────────────────────────

    @Test
    fun `ReadingMode has LTR RTL WEBTOON values`() {
        assertEquals(3, ReadingMode.entries.size)
        assertNotNull(ReadingMode.LTR)
        assertNotNull(ReadingMode.RTL)
        assertNotNull(ReadingMode.WEBTOON)
    }

    @Test
    fun `ReadingMode display names are human-readable`() {
        assertTrue(ReadingMode.LTR.displayName.isNotBlank())
        assertTrue(ReadingMode.RTL.displayName.isNotBlank())
        assertTrue(ReadingMode.WEBTOON.displayName.isNotBlank())
    }

    // ── 2. DualPageState grouping logic ────────────────────────────────────

    @Test
    fun `DualPageState groups 6 pages into cover plus 2 pairs`() {
        // Pages: [0], [1,2], [3,4], [5]
        val state = DualPageState(totalPages = 6)
        assertEquals(4, state.groupCount) // cover + 2 full pairs + 1 remainder
    }

    @Test
    fun `DualPageState groups 5 pages into cover plus 2 pairs`() {
        // [0], [1,2], [3,4] = 3 groups
        val state = DualPageState(totalPages = 5)
        assertEquals(3, state.groupCount)
    }

    @Test
    fun `DualPageState single page has 1 group`() {
        val state = DualPageState(totalPages = 1)
        assertEquals(1, state.groupCount)
    }

    @Test
    fun `DualPageState group 0 is cover (single page)`() {
        val state = DualPageState(totalPages = 6)
        val group = state.getGroup(0)
        assertEquals(1, group.size)
        assertEquals(0, group[0])
    }

    @Test
    fun `DualPageState group 1 is pages 1 and 2`() {
        val state = DualPageState(totalPages = 6)
        val group = state.getGroup(1)
        assertEquals(2, group.size)
        assertEquals(1, group[0])
        assertEquals(2, group[1])
    }

    @Test
    fun `DualPageState maps logical page index to group index`() {
        val state = DualPageState(totalPages = 6)
        assertEquals(0, state.groupIndexForPage(0)) // cover
        assertEquals(1, state.groupIndexForPage(1)) // group 1: [1,2]
        assertEquals(1, state.groupIndexForPage(2)) // group 1: [1,2]
        assertEquals(2, state.groupIndexForPage(3)) // group 2: [3,4]
        assertEquals(2, state.groupIndexForPage(4)) // group 2: [3,4]
        assertEquals(3, state.groupIndexForPage(5)) // group 3: [5]
    }

    @Test
    fun `DualPageState maps group index to first logical page`() {
        val state = DualPageState(totalPages = 6)
        assertEquals(0, state.firstPageInGroup(0))
        assertEquals(1, state.firstPageInGroup(1))
        assertEquals(3, state.firstPageInGroup(2))
        assertEquals(5, state.firstPageInGroup(3))
    }

    // ── 2b. DualPageState — forcedSinglePages ───────────────────────────────

    @Test
    fun `forcedSinglePages makes specified page display alone`() {
        // 6 pages, force page 3 single: [0], [1,2], [3], [4,5]
        val state = DualPageState(totalPages = 6, forcedSinglePages = setOf(3))
        assertEquals(4, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))
        assertEquals(listOf(1, 2), state.getGroup(1))
        assertEquals(listOf(3), state.getGroup(2))
        assertEquals(listOf(4, 5), state.getGroup(3))
    }

    @Test
    fun `forcedSinglePages shifts subsequent pairings`() {
        // 10 pages, force page 1 single: [0], [1], [2,3], [4,5], [6,7], [8,9]
        val state = DualPageState(totalPages = 10, forcedSinglePages = setOf(1))
        assertEquals(6, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))
        assertEquals(listOf(1), state.getGroup(1))
        assertEquals(listOf(2, 3), state.getGroup(2))
        assertEquals(listOf(4, 5), state.getGroup(3))
        assertEquals(listOf(6, 7), state.getGroup(4))
        assertEquals(listOf(8, 9), state.getGroup(5))
    }

    @Test
    fun `spreadPages and forcedSinglePages merge correctly`() {
        // 8 pages, spread={2}, forced={5}:
        // After spread page 2, run ahead = pages 3,4 (until forced-single 5) = 2 = even → no reset.
        // [0], [1], [2], [3,4], [5], [6,7]
        val state = DualPageState(
            totalPages = 8,
            spreadPages = setOf(2),
            forcedSinglePages = setOf(5),
        )
        assertEquals(6, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))
        assertEquals(listOf(1), state.getGroup(1))     // alone because next is spread
        assertEquals(listOf(2), state.getGroup(2))      // spread
        assertEquals(listOf(3, 4), state.getGroup(3))   // even run → no reset, pair normally
        assertEquals(listOf(5), state.getGroup(4))       // forced single
        assertEquals(listOf(6, 7), state.getGroup(5))
    }

    // ── 2c. DualPageState — matchedPairs ────────────────────────────────────

    @Test
    fun `matchedPairs forces specific pages to pair together`() {
        // 7 pages, matched pair (2,3): [0], [1], [2,3], [4,5], [6]
        // Without matchedPairs default would be: [0], [1,2], [3,4], [5,6]
        // With matchedPairs=(2,3) we need to force 2+3 together.
        // Since matched only changes pairing when it conflicts with default,
        // and default already pairs [1,2], we need a case where it matters.
        //
        // Better example: 8 pages, forced page 1 single + matched (4,5):
        // [0], [1], [2,3], [4,5], [6,7]
        val state = DualPageState(
            totalPages = 8,
            forcedSinglePages = setOf(1),
            matchedPairs = setOf(4 to 5),
        )
        assertEquals(5, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))
        assertEquals(listOf(1), state.getGroup(1))    // forced single
        assertEquals(listOf(2, 3), state.getGroup(2))
        assertEquals(listOf(4, 5), state.getGroup(3)) // matched pair
        assertEquals(listOf(6, 7), state.getGroup(4))
    }

    @Test
    fun `forcedSinglePages takes priority over matchedPairs`() {
        // Page 3 is in both forcedSingle and matchedPair (3,4).
        // forcedSingle wins → page 3 shown alone.
        // 8 pages: [0], [1,2], [3], [4,5], [6,7]
        val state = DualPageState(
            totalPages = 8,
            forcedSinglePages = setOf(3),
            matchedPairs = setOf(3 to 4),
        )
        assertEquals(5, state.groupCount)
        assertEquals(listOf(3), state.getGroup(2))    // forced single, not paired with 4
        assertEquals(listOf(4, 5), state.getGroup(3)) // 4 pairs with 5 instead
    }

    @Test
    fun `matchedPairs second page in singles prevents pairing`() {
        // matchedPair (3,4) but page 4 is a spread → pair broken.
        // After spread page 4, run ahead = pages 5,6,7 = 3 = odd → parity reset.
        // 8 pages: [0], [1,2], [3], [4], [5], [6,7]
        val state = DualPageState(
            totalPages = 8,
            spreadPages = setOf(4),
            matchedPairs = setOf(3 to 4),
        )
        assertEquals(6, state.groupCount)
        assertEquals(listOf(3), state.getGroup(2))    // alone (next page is single)
        assertEquals(listOf(4), state.getGroup(3))    // spread, shown alone
        assertEquals(listOf(5), state.getGroup(4))    // parity reset (odd run=3)
        assertEquals(listOf(6, 7), state.getGroup(5)) // normal pair
    }

    @Test
    fun `matchedPair is not stolen by default sequential pairing of preceding page`() {
        // Regression: 7 pages, spreadPages={3}, matchedPairs={(5,6)}.
        // Without the guard, default pairing consumes page 5 into [4,5],
        // leaving page 6 stranded alone.
        // With the guard, page 4 is shown alone so that (5,6) can pair correctly.
        // Expected: [0], [1,2], [3], [4], [5,6]
        val state = DualPageState(
            totalPages = 7,
            spreadPages = setOf(3),
            matchedPairs = setOf(5 to 6),
        )
        assertEquals(5, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))
        assertEquals(listOf(1, 2), state.getGroup(1))
        assertEquals(listOf(3), state.getGroup(2))   // spread, shown alone
        assertEquals(listOf(4), state.getGroup(3))   // alone (page 5 reserved for matched pair)
        assertEquals(listOf(5, 6), state.getGroup(4)) // matched pair preserved
    }

    @Test
    fun `matchedPair guard does not affect default pairing when no reservation needed`() {
        // 7 pages, spreadPages={3}, no matchedPairs:
        // After spread 3, run = pages 4,5,6 = 3 = odd → parity reset page 4.
        // [0],[1,2],[3],[4],[5,6]
        val state = DualPageState(
            totalPages = 7,
            spreadPages = setOf(3),
        )
        assertEquals(5, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))
        assertEquals(listOf(1, 2), state.getGroup(1))
        assertEquals(listOf(3), state.getGroup(2))     // spread
        assertEquals(listOf(4), state.getGroup(3))      // parity reset (odd run=3)
        assertEquals(listOf(5, 6), state.getGroup(4))
    }

    @Test
    fun `smart parity reset - Chainsaw Man scenario`() {
        // 10 pages, spreads={1,3}:
        // After spread 1, run=1 (page 2, until spread 3) = odd → reset page 2.
        // After spread 3, run=6 (pages 4..9) = even → NO reset, pair normally.
        // [0],[1],[2],[3],[4,5],[6,7],[8,9]
        val state = DualPageState(
            totalPages = 10,
            spreadPages = setOf(1, 3),
        )
        assertEquals(7, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))    // cover
        assertEquals(listOf(1), state.getGroup(1))    // spread
        assertEquals(listOf(2), state.getGroup(2))    // parity reset (odd run=1)
        assertEquals(listOf(3), state.getGroup(3))    // spread
        assertEquals(listOf(4, 5), state.getGroup(4)) // even run=6 → no reset, pair
        assertEquals(listOf(6, 7), state.getGroup(5))
        assertEquals(listOf(8, 9), state.getGroup(6))
    }

    @Test
    fun `smart parity reset - even run between two spreads`() {
        // 9 pages, spreads={3,6}: pages between spreads = {4,5} = 2 = even → no reset
        // [0],[1,2],[3],[4,5],[6],[7,8]
        val state = DualPageState(
            totalPages = 9,
            spreadPages = setOf(3, 6),
        )
        assertEquals(6, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))
        assertEquals(listOf(1, 2), state.getGroup(1))
        assertEquals(listOf(3), state.getGroup(2))    // spread
        assertEquals(listOf(4, 5), state.getGroup(3)) // even run → pair
        assertEquals(listOf(6), state.getGroup(4))    // spread
        assertEquals(listOf(7, 8), state.getGroup(5)) // even run → pair
    }

    @Test
    fun `smart parity reset - odd run after spread`() {
        // 8 pages, spreads={3}: pages after spread = {4,5,6,7} = 4 = even → no reset
        // [0],[1,2],[3],[4,5],[6,7]
        val state = DualPageState(
            totalPages = 8,
            spreadPages = setOf(3),
        )
        assertEquals(5, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))
        assertEquals(listOf(1, 2), state.getGroup(1))
        assertEquals(listOf(3), state.getGroup(2))    // spread
        assertEquals(listOf(4, 5), state.getGroup(3)) // even run=4 → no reset
        assertEquals(listOf(6, 7), state.getGroup(4))
    }

    @Test
    fun `parity reset does not apply to forcedSinglePages`() {
        // forcedSinglePages do not represent physical double-page spreads,
        // so the next page should NOT be forced alone.
        // 7 pages, forced={2}: [0],[1],[2],[3,4],[5,6]
        // Page 1 alone because next (2) is forced single.
        // Page 2 forced single — no parity reset.
        // Pages 3,4 pair; 5,6 pair.
        val state = DualPageState(
            totalPages = 7,
            forcedSinglePages = setOf(2),
        )
        assertEquals(5, state.groupCount)
        assertEquals(listOf(0), state.getGroup(0))
        assertEquals(listOf(1), state.getGroup(1))     // alone because next is forced single
        assertEquals(listOf(2), state.getGroup(2))      // forced single, NO parity reset
        assertEquals(listOf(3, 4), state.getGroup(3))
        assertEquals(listOf(5, 6), state.getGroup(4))
    }

    // ── 2d. DualPageState — singlePageSide ──────────────────────────────────

    @Test
    fun `singlePageSide - cover page is TRAILING (connects forward)`() {
        val state = DualPageState(totalPages = 6)
        assertEquals(SinglePageSide.TRAILING, state.singlePageSide(0))
    }

    @Test
    fun `singlePageSide - landscape spread is CENTER`() {
        val state = DualPageState(totalPages = 6, spreadPages = setOf(3))
        val spreadGroupIndex = state.groupIndexForPage(3)
        assertEquals(SinglePageSide.CENTER, state.singlePageSide(spreadGroupIndex))
    }

    @Test
    fun `singlePageSide - parity reset page after spread is TRAILING`() {
        // 7 pages, spread={3}, run=3 (odd) → parity reset page 4
        // [0],[1,2],[3],[4],[5,6]  — page 4 has a next group → TRAILING
        val state = DualPageState(totalPages = 7, spreadPages = setOf(3))
        val resetGroupIndex = state.groupIndexForPage(4)
        assertEquals(SinglePageSide.TRAILING, state.singlePageSide(resetGroupIndex))
    }

    @Test
    fun `singlePageSide - last single page is LEADING (connects backward)`() {
        // 6 pages: [0],[1,2],[3,4],[5] — page 5 is last group → LEADING
        val state = DualPageState(totalPages = 6)
        assertEquals(SinglePageSide.LEADING, state.singlePageSide(state.groupCount - 1))
    }

    @Test
    fun `singlePageSide - dual group returns CENTER`() {
        // Group with 2 pages → CENTER (not applicable, but safe fallback)
        val state = DualPageState(totalPages = 6)
        assertEquals(SinglePageSide.CENTER, state.singlePageSide(1)) // group [1,2]
    }

    // ── 3. DesktopReaderScreen instantiation with isDualPage ───────────────

    @Test
    fun `DesktopReaderScreen with isDualPage true can be instantiated`() {
        val screen = mihon.desktop.ui.reader.DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            isDualPage = true,
        )
        assertNotNull(screen)
        assertEquals(true, screen.isDualPage)
    }

    @Test
    fun `DesktopReaderScreen isDualPage defaults to no explicit override`() {
        val screen = mihon.desktop.ui.reader.DesktopReaderScreen(chapterTitle = "Chapter 1")
        assertNull(screen.isDualPage)
    }

    // ── 4. ReadingMode derivation ──────────────────────────────────────────

    @Test
    fun `initialReadingMode returns WEBTOON when isWebtoon is true`() {
        assertEquals(ReadingMode.WEBTOON, ReadingMode.from(isWebtoon = true, isRtl = false))
    }

    @Test
    fun `initialReadingMode returns RTL when isRtl true and not webtoon`() {
        assertEquals(ReadingMode.RTL, ReadingMode.from(isWebtoon = false, isRtl = true))
    }

    @Test
    fun `initialReadingMode returns LTR by default`() {
        assertEquals(ReadingMode.LTR, ReadingMode.from(isWebtoon = false, isRtl = false))
    }

    @Test
    fun `WEBTOON takes priority over RTL`() {
        assertEquals(ReadingMode.WEBTOON, ReadingMode.from(isWebtoon = true, isRtl = true))
    }
}
