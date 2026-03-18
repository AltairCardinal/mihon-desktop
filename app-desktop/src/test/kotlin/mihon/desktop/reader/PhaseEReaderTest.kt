package mihon.desktop.reader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    // ── 3. DesktopReaderScreen instantiation with isDualPage ───────────────

    @Test
    fun `DesktopReaderScreen with isDualPage true can be instantiated`() {
        val screen = mihon.desktop.ui.reader.DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            isDualPage = true,
        )
        assertNotNull(screen)
        assertTrue(screen.isDualPage)
    }

    @Test
    fun `DesktopReaderScreen isDualPage defaults to false`() {
        val screen = mihon.desktop.ui.reader.DesktopReaderScreen(chapterTitle = "Chapter 1")
        assertFalse(screen.isDualPage)
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
