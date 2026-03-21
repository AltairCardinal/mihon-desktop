package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the physical-side contract for single-page groups in dual-page mode.
 *
 * The invariant:
 *  - TRAILING (cover, or any page with a next group) → always physical RIGHT
 *  - LEADING  (last page, connects backward)         → always physical LEFT
 *
 * This must hold for both LTR and RTL reading directions.  RTL wraps the pager
 * in [CompositionLocalProvider(LocalLayoutDirection = RTL)], so direction-aware
 * alignments (CenterEnd/CenterStart) flip physical meaning.  The viewer must
 * compensate by swapping the alignment when isRtl = true.
 */
class DualPageViewerAlignmentTest {

    // ── TRAILING: always physical RIGHT ─────────────────────────────────────

    @Test
    fun `TRAILING single page is on physical right in LTR`() {
        assertTrue(
            singlePageBoxOnRight(SinglePageSide.TRAILING, isRtl = false),
            "Cover page must be on physical RIGHT in LTR dual-page mode",
        )
    }

    @Test
    fun `TRAILING single page is on physical right in RTL`() {
        assertTrue(
            singlePageBoxOnRight(SinglePageSide.TRAILING, isRtl = true),
            "Cover page must be on physical RIGHT in RTL dual-page mode (not left)",
        )
    }

    // ── LEADING: always physical LEFT ────────────────────────────────────────

    @Test
    fun `LEADING single page is on physical left in LTR`() {
        assertFalse(
            singlePageBoxOnRight(SinglePageSide.LEADING, isRtl = false),
            "Last standalone page must be on physical LEFT in LTR dual-page mode",
        )
    }

    @Test
    fun `LEADING single page is on physical left in RTL`() {
        assertFalse(
            singlePageBoxOnRight(SinglePageSide.LEADING, isRtl = true),
            "Last standalone page must be on physical LEFT in RTL dual-page mode",
        )
    }

    // ── LTR and RTL produce the same physical position ───────────────────────

    @Test
    fun `TRAILING physical side is the same in LTR and RTL`() {
        val ltr = singlePageBoxOnRight(SinglePageSide.TRAILING, isRtl = false)
        val rtl = singlePageBoxOnRight(SinglePageSide.TRAILING, isRtl = true)
        assertTrue(ltr == rtl, "TRAILING physical side must be identical in LTR and RTL")
    }

    @Test
    fun `LEADING physical side is the same in LTR and RTL`() {
        val ltr = singlePageBoxOnRight(SinglePageSide.LEADING, isRtl = false)
        val rtl = singlePageBoxOnRight(SinglePageSide.LEADING, isRtl = true)
        assertTrue(ltr == rtl, "LEADING physical side must be identical in LTR and RTL")
    }
}
