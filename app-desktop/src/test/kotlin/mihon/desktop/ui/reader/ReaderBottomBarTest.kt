package mihon.desktop.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for chapter-navigation button layout logic in ReaderBottomBar.
 *
 * Physical layout:
 *  - LTR: [SkipPrevious] ... [SkipNext]   (prev=left, next=right)
 *  - RTL: [SkipNext]     ... [SkipPrevious] (next=left, prev=right)
 */
class ReaderBottomBarTest {

    // ── LTR layout ───────────────────────────────────────────────────────────

    @Test
    fun `LTR left button is prev chapter`() {
        val layout = chapterNavLayout(isRtl = false, hasPrevChapter = true, hasNextChapter = true)
        assertTrue(layout.leftIsPrev, "In LTR, left button must be SkipPrevious (prev chapter)")
    }

    @Test
    fun `LTR right button is next chapter`() {
        val layout = chapterNavLayout(isRtl = false, hasPrevChapter = true, hasNextChapter = true)
        // left=prev means right=next
        assertTrue(layout.leftIsPrev, "In LTR, left=prev implies right=next chapter")
    }

    @Test
    fun `LTR prev button disabled when no prev chapter`() {
        val layout = chapterNavLayout(isRtl = false, hasPrevChapter = false, hasNextChapter = true)
        assertFalse(layout.prevEnabled, "Prev button must be disabled when no prev chapter")
    }

    @Test
    fun `LTR next button disabled when no next chapter`() {
        val layout = chapterNavLayout(isRtl = false, hasPrevChapter = true, hasNextChapter = false)
        assertFalse(layout.nextEnabled, "Next button must be disabled when no next chapter")
    }

    // ── RTL layout ───────────────────────────────────────────────────────────

    @Test
    fun `RTL left button is next chapter`() {
        val layout = chapterNavLayout(isRtl = true, hasPrevChapter = true, hasNextChapter = true)
        assertFalse(layout.leftIsPrev, "In RTL, left button must be SkipNext (next chapter)")
    }

    @Test
    fun `RTL left enabled when next chapter exists`() {
        val layout = chapterNavLayout(isRtl = true, hasPrevChapter = false, hasNextChapter = true)
        assertTrue(layout.nextEnabled, "In RTL, left (=next) button must be enabled when next chapter exists")
        assertFalse(layout.prevEnabled, "In RTL, right (=prev) button must be disabled when no prev chapter")
    }

    @Test
    fun `RTL left disabled when no next chapter`() {
        val layout = chapterNavLayout(isRtl = true, hasPrevChapter = true, hasNextChapter = false)
        assertFalse(layout.nextEnabled, "In RTL, left (=next) must be disabled when no next chapter")
        assertTrue(layout.prevEnabled, "In RTL, right (=prev) must be enabled when prev chapter exists")
    }

    // ── Direction symmetry ───────────────────────────────────────────────────

    @Test
    fun `LTR and RTL have opposite leftIsPrev`() {
        val ltr = chapterNavLayout(isRtl = false, hasPrevChapter = true, hasNextChapter = true)
        val rtl = chapterNavLayout(isRtl = true, hasPrevChapter = true, hasNextChapter = true)
        assertTrue(ltr.leftIsPrev != rtl.leftIsPrev, "LTR and RTL must have mirrored button order")
    }
}
