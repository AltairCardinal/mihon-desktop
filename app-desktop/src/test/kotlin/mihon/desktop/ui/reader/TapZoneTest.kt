package mihon.desktop.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TapZoneTest {

    @Test
    fun `tap in left third returns LEFT`() {
        assertEquals(TapZone.LEFT, tapZoneFor(x = 50f, width = 300f))
    }

    @Test
    fun `tap in right third returns RIGHT`() {
        assertEquals(TapZone.RIGHT, tapZoneFor(x = 250f, width = 300f))
    }

    @Test
    fun `tap in center third returns CENTER`() {
        assertEquals(TapZone.CENTER, tapZoneFor(x = 150f, width = 300f))
    }

    @Test
    fun `boundary at exactly one third returns CENTER`() {
        // x = width/3 is the boundary; not strictly less than, so CENTER
        assertEquals(TapZone.CENTER, tapZoneFor(x = 100f, width = 300f))
    }

    @Test
    fun `boundary at exactly two thirds returns CENTER`() {
        assertEquals(TapZone.CENTER, tapZoneFor(x = 200f, width = 300f))
    }

    @Test
    fun `zero width returns CENTER safely`() {
        assertEquals(TapZone.CENTER, tapZoneFor(x = 0f, width = 0f))
    }

    @Test
    fun `tap at far left edge returns LEFT`() {
        assertEquals(TapZone.LEFT, tapZoneFor(x = 0f, width = 300f))
    }

    @Test
    fun `tap at far right edge returns RIGHT`() {
        assertEquals(TapZone.RIGHT, tapZoneFor(x = 299f, width = 300f))
    }

    @Test
    fun `dual page spread uses full spread width for left page center tap`() {
        // In a 600px-wide spread, the visual center of the left page is x = 150.
        // This must still resolve to the left navigation zone for the whole spread,
        // not the center zone of the 300px child page box.
        assertEquals(TapZone.LEFT, tapZoneFor(x = 150f, width = 600f))
    }

    @Test
    fun `dual page spread uses full spread width for right page center tap`() {
        // Symmetric to the left-page case above.
        assertEquals(TapZone.RIGHT, tapZoneFor(x = 450f, width = 600f))
    }
}

class TapPageNavTest {

    // ── tapLeftAction ───────────────────────────────────────────────────────

    @Test
    fun `tap left on first pager page returns PrevChapter`() {
        assertEquals(PageNavAction.PrevChapter, tapLeftAction(currentPagerIndex = 0))
    }

    @Test
    fun `tap left on page 1 returns ScrollTo 0`() {
        assertEquals(PageNavAction.ScrollTo(0), tapLeftAction(currentPagerIndex = 1))
    }

    @Test
    fun `tap left on page 5 returns ScrollTo 4`() {
        assertEquals(PageNavAction.ScrollTo(4), tapLeftAction(currentPagerIndex = 5))
    }

    // ── tapRightAction ──────────────────────────────────────────────────────

    @Test
    fun `tap right on last pager page returns NextChapter`() {
        // groupCount = 5, last valid index = 4
        assertEquals(PageNavAction.NextChapter, tapRightAction(currentPagerIndex = 4, groupCount = 5))
    }

    @Test
    fun `tap right on page before last returns ScrollTo next`() {
        assertEquals(PageNavAction.ScrollTo(3), tapRightAction(currentPagerIndex = 2, groupCount = 5))
    }

    @Test
    fun `single page chapter - tap right from index 0 returns NextChapter`() {
        assertEquals(PageNavAction.NextChapter, tapRightAction(currentPagerIndex = 0, groupCount = 1))
    }

    @Test
    fun `single page chapter - tap left from index 0 returns PrevChapter`() {
        assertEquals(PageNavAction.PrevChapter, tapLeftAction(currentPagerIndex = 0))
    }
}

class ChapterNavDirectionTest {

    // ── LTR mode ────────────────────────────────────────────────────────────

    @Test
    fun `LTR - tap left at pager 0 returns PrevChapter`() {
        assertEquals(PageNavAction.PrevChapter, chapterNavForTapLeft(0, isRtl = false))
    }

    @Test
    fun `LTR - tap right at last pager slot returns NextChapter`() {
        assertEquals(PageNavAction.NextChapter, chapterNavForTapRight(4, groupCount = 5, isRtl = false))
    }

    @Test
    fun `LTR - tap left in middle returns null (no chapter switch)`() {
        assertNull(chapterNavForTapLeft(3, isRtl = false))
    }

    @Test
    fun `LTR - tap right in middle returns null`() {
        assertNull(chapterNavForTapRight(2, groupCount = 5, isRtl = false))
    }

    // ── RTL mode: physical direction is opposite to reading direction ────────

    @Test
    fun `RTL - tap left at pager 0 (end of chapter) returns NextChapter`() {
        // Pager index 0 in RTL = last logical page = end of chapter.
        // Tapping left (physically) = forward in reading = next/newer chapter.
        assertEquals(PageNavAction.NextChapter, chapterNavForTapLeft(0, isRtl = true))
    }

    @Test
    fun `RTL - tap right at last pager slot (start of chapter) returns PrevChapter`() {
        // Pager index max in RTL = first logical page = start of chapter.
        // Tapping right (physically) = backward in reading = prev/older chapter.
        assertEquals(PageNavAction.PrevChapter, chapterNavForTapRight(4, groupCount = 5, isRtl = true))
    }

    @Test
    fun `RTL - tap left in middle returns null`() {
        assertNull(chapterNavForTapLeft(3, isRtl = true))
    }

    @Test
    fun `RTL - tap right in middle returns null`() {
        assertNull(chapterNavForTapRight(2, groupCount = 5, isRtl = true))
    }
}
