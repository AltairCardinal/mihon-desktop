package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPortraitSingleSlotTest {
    @Test
    fun `one page fills the viewport in both reading directions`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            assertEquals(ReaderPortraitSingleSlot.FULL, portraitSinglePageSlot(direction, 0, 1))
        }
    }

    @Test
    fun `four page portrait grouping places first and trailing singles on opposite sides`() {
        val groups = ReaderPagePairing.build(
            pageCount = 4,
            layoutAt = { PageLayout.PORTRAIT },
            options = PagePairingOptions(pairAdjacentPortraitPages = true, forceFirstPageSingle = true),
        )
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), groups.map(IntArray::toList))
        assertEquals(ReaderPortraitSingleSlot.LEFT, portraitSinglePageSlot(ReaderDirection.RTL, 0, groups.size))
        assertEquals(ReaderPortraitSingleSlot.RIGHT, portraitSinglePageSlot(ReaderDirection.RTL, 2, groups.size))
        assertEquals(ReaderPortraitSingleSlot.RIGHT, portraitSinglePageSlot(ReaderDirection.LTR, 0, groups.size))
        assertEquals(ReaderPortraitSingleSlot.LEFT, portraitSinglePageSlot(ReaderDirection.LTR, 2, groups.size))
    }

    @Test
    fun `five page final pair does not create another single slot`() {
        val groups = ReaderPagePairing.build(
            pageCount = 5,
            layoutAt = { PageLayout.PORTRAIT },
            options = PagePairingOptions(pairAdjacentPortraitPages = true, forceFirstPageSingle = true),
        )
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), groups.map(IntArray::toList))
        assertEquals(ReaderPortraitSingleSlot.LEFT, portraitSinglePageSlot(ReaderDirection.RTL, 0, groups.size))
        assertEquals(ReaderPortraitSingleSlot.RIGHT, portraitSinglePageSlot(ReaderDirection.LTR, 0, groups.size))
    }
}
