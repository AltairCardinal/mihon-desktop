package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the physical-side contract for single-page groups in dual-page mode.
 *
 * The invariant (RTL-aware):
 *  - LTR: TRAILING → physical RIGHT,  LEADING → physical LEFT
 *  - RTL: TRAILING → physical LEFT,   LEADING → physical RIGHT
 *
 * In RTL manga reading direction the cover (TRAILING) should appear on the
 * LEFT half (the side the reader's eye enters first), mirroring the physical
 * layout of a right-to-left bound book.
 */
class DualPageViewerAlignmentTest {

    // ── LTR: TRAILING on RIGHT, LEADING on LEFT ──────────────────────────────

    @Test
    fun `TRAILING single page is on physical right in LTR`() {
        assertTrue(
            singlePageBoxOnRight(SinglePageSide.TRAILING, isRtl = false),
            "Cover page must be on physical RIGHT in LTR dual-page mode",
        )
    }

    @Test
    fun `LEADING single page is on physical left in LTR`() {
        assertFalse(
            singlePageBoxOnRight(SinglePageSide.LEADING, isRtl = false),
            "Last standalone page must be on physical LEFT in LTR dual-page mode",
        )
    }

    // ── RTL: TRAILING on LEFT, LEADING on RIGHT (mirrored) ───────────────────

    @Test
    fun `TRAILING single page is on physical left in RTL`() {
        assertFalse(
            singlePageBoxOnRight(SinglePageSide.TRAILING, isRtl = true),
            "Cover page must be on physical LEFT in RTL dual-page mode",
        )
    }

    @Test
    fun `LEADING single page is on physical right in RTL`() {
        assertTrue(
            singlePageBoxOnRight(SinglePageSide.LEADING, isRtl = true),
            "Last standalone page must be on physical RIGHT in RTL dual-page mode",
        )
    }

    // ── LTR and RTL produce opposite physical positions ───────────────────────

    @Test
    fun `TRAILING physical side is opposite in LTR vs RTL`() {
        val ltr = singlePageBoxOnRight(SinglePageSide.TRAILING, isRtl = false)
        val rtl = singlePageBoxOnRight(SinglePageSide.TRAILING, isRtl = true)
        assertTrue(ltr != rtl, "TRAILING physical side must be mirrored in RTL vs LTR")
    }

    @Test
    fun `LEADING physical side is opposite in LTR vs RTL`() {
        val ltr = singlePageBoxOnRight(SinglePageSide.LEADING, isRtl = false)
        val rtl = singlePageBoxOnRight(SinglePageSide.LEADING, isRtl = true)
        assertTrue(ltr != rtl, "LEADING physical side must be mirrored in RTL vs LTR")
    }
}
