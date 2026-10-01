package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPortraitSingleSlotTest {
    @Test
    fun `one page fills the viewport in both reading directions`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            assertEquals(
                ReaderPortraitSingleSlot.FULL,
                portraitSinglePageSlot(direction, ReaderDualPageSingleReason.ONLY_PAGE),
            )
        }
    }

    @Test
    fun `four page portrait grouping places first and trailing singles on opposite sides`() {
        val groups = portraitGroups(4)
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3)),
            groups.map {
                it.pageIds.map { id -> id.sourcePageIndex }
            },
        )
        assertEquals(
            ReaderPortraitSingleSlot.LEFT,
            portraitSinglePageSlot(ReaderDirection.RTL, requireNotNull(groups.first().singleReason)),
        )
        assertEquals(
            ReaderPortraitSingleSlot.RIGHT,
            portraitSinglePageSlot(ReaderDirection.RTL, requireNotNull(groups.last().singleReason)),
        )
        assertEquals(ReaderPortraitSingleSlot.RIGHT, groups.first().slots.single().position)
        assertEquals(ReaderPortraitSingleSlot.LEFT, groups.last().slots.single().position)
    }

    @Test
    fun `five page final pair does not create another single slot`() {
        val groups = portraitGroups(5)
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3, 4)),
            groups.map {
                it.pageIds.map { id -> id.sourcePageIndex }
            },
        )
        assertEquals(
            ReaderPortraitSingleSlot.LEFT,
            portraitSinglePageSlot(ReaderDirection.RTL, requireNotNull(groups.first().singleReason)),
        )
        assertEquals(ReaderPortraitSingleSlot.RIGHT, groups.first().slots.single().position)
    }

    private fun portraitGroups(count: Int): List<ReaderDualPageUnit> {
        val ids =
            List(count) { mihon.domain.reader.session.ReaderPageId(mihon.domain.reader.session.ReaderChapterId(7), it) }
        return SharedDualPageRules.build(
            ids,
            ids.associateWith {
                ReaderPageFact.dimensions(40, 60)
            },
            ReaderDirection.LTR,
        )
    }
}
