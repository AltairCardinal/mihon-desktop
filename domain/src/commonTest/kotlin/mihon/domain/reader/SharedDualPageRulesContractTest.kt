package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real-source dimensions and synthetic counterexamples are deliberately separate contracts. */
class SharedDualPageRulesContractTest {
    private val chapter = ReaderChapterId(42)

    @Test
    fun `unknown prefix does not turn normal forward pairing into an entire chapter of singles`() {
        val pages = pages(9)
        val facts = mapOf(pages[1] to ReaderPageFact.dimensions(100, 150))
        val units = SharedDualPageRules.build(pages, facts, ReaderDirection.RTL)
        assertEquals(listOf(pages[1], pages[2]), units[1].pageIds)
        assertEquals(ReaderPortraitSingleSlot.RIGHT, units[1].slots.first().position)
        assertFalse(units[1].certain, "Ordinary fallback must not fabricate portrait evidence")
    }
    private fun pages(count: Int) = List(count) { ReaderPageId(chapter, it) }
    private fun facts(pages: List<ReaderPageId>, spreads: Set<Int> = emptySet()) = pages.associateWith {
        if (it.sourcePageIndex in spreads) {
            ReaderPageFact.dimensions(2000, 1000)
        } else {
            ReaderPageFact.dimensions(1000, 1500)
        }
    }
    private fun groups(units: List<ReaderDualPageUnit>) =
        units.map { unit -> unit.pageIds.map { it.sourcePageIndex + 1 } }
    private fun realRows(name: String): List<List<Int>> =
        requireNotNull(javaClass.getResourceAsStream("/reader/shared-dual-page-real-$name.csv"))
            .bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { line -> line.split(',').map(String::toInt) }.toList()
            }
    private fun build(
        count: Int,
        spreads: Set<Int> = emptySet(),
        direction: ReaderDirection = ReaderDirection.RTL,
        singles: Set<Int> = emptySet(),
        matches: Set<PagePair> = emptySet(),
    ): List<ReaderDualPageUnit> {
        val pages = pages(count)
        return SharedDualPageRules.build(
            pages,
            facts(pages, spreads),
            direction,
            singles.mapTo(mutableSetOf()) { pages[it] },
            matches.mapTo(mutableSetOf()) { pages[it.first] to pages[it.second] },
            automaticMatching = matches.isNotEmpty(),
        )
    }

    @Test
    fun `real B1 dimensions have only width ten and preserve fifteen sixteen with independent seventeen`() {
        val rows = realRows("b1")
        assertEquals((1..17).toList(), rows.map { it[0] })
        val pages = pages(rows.size)
        val facts = rows.associate { row -> pages[row[0] - 1] to ReaderPageFact.dimensions(row[1], row[2]) }
        assertEquals(listOf(pages[9]), facts.filterValues { it.state == ReaderPageFactState.SPREAD }.keys.toList())
        for (matching in listOf(false, true)) {
            val units = SharedDualPageRules.build(
                pages,
                facts,
                ReaderDirection.RTL,
                matchedPairs = setOf(pages[14] to pages[15]),
                automaticMatching = matching,
            )
            assertEquals(
                listOf(listOf(10), listOf(11, 12), listOf(13, 14), listOf(15, 16), listOf(17)),
                groups(units).takeLast(5),
            )
            assertEquals(ReaderPortraitSingleSlot.RIGHT, units.last().slots.single().position)
        }
    }

    @Test
    fun `real B2 preserves opening two left and ambiguous twenty centered without skipping pages`() {
        val rows = realRows("b2")
        assertEquals((1..22).toList(), rows.map { it[0] })
        val pages = pages(rows.size)
        val facts = rows.associate { row -> pages[row[0] - 1] to ReaderPageFact.dimensions(row[1], row[2]) }
        assertEquals(
            listOf(3, 10, 19, 21),
            facts.filterValues { it.state == ReaderPageFactState.SPREAD }
                .keys.map { it.sourcePageIndex + 1 },
        )
        val units = SharedDualPageRules.build(pages, facts, ReaderDirection.RTL, automaticMatching = true)
        assertEquals(pages, units.flatMap { it.pageIds })
        assertEquals(ReaderPortraitSingleSlot.LEFT, units.single { it.anchor == pages[1] }.slots.single().position)
        val twenty = units.single { it.anchor == pages[19] }
        assertEquals(ReaderPortraitSingleSlot.FULL, twenty.slots.single().position)
        assertEquals(ReaderDualPageSingleReason.AMBIGUOUS, twenty.singleReason)
        assertEquals(
            listOf(
                listOf(1), listOf(2), listOf(3), listOf(4, 5), listOf(6, 7), listOf(8, 9), listOf(10),
                listOf(11, 12), listOf(13, 14), listOf(15, 16), listOf(17, 18),
                listOf(19), listOf(20), listOf(21), listOf(22),
            ),
            groups(units),
        )
    }

    @Test
    fun `synthetic seventeen pages width fourteen preserves matched fifteen sixteen`() {
        val units = build(17, setOf(13), matches = setOf(PagePair(14, 15)))
        assertEquals(listOf(listOf(14), listOf(15, 16), listOf(17)), groups(units).takeLast(3))
    }

    @Test
    fun `later width fact changes only its dependent pair`() {
        val before = groups(build(10, setOf(3)))
        val after = groups(build(10, setOf(3, 7)))
        assertTrue(listOf(5, 6) in before)
        assertTrue(listOf(5, 6) in after)
        assertEquals(listOf(listOf(7), listOf(8), listOf(9, 10)), after.takeLast(3))
    }

    @Test
    fun `one two four five portrait chapters have symmetric first tail and intact last pair`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val opening = if (direction == ReaderDirection.RTL) {
                ReaderPortraitSingleSlot.LEFT
            } else {
                ReaderPortraitSingleSlot.RIGHT
            }
            val ending = if (direction == ReaderDirection.RTL) {
                ReaderPortraitSingleSlot.RIGHT
            } else {
                ReaderPortraitSingleSlot.LEFT
            }
            assertEquals(
                ReaderPortraitSingleSlot.FULL,
                build(1, direction = direction).single().slots.single().position,
            )
            assertEquals(listOf(opening, ending), build(2, direction = direction).map { it.slots.single().position })
            assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4)), groups(build(4, direction = direction)))
            assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5)), groups(build(5, direction = direction)))
            assertEquals(ending, build(4, direction = direction).last().slots.single().position)
        }
    }

    @Test
    fun `wide and square cover are entire centered logical page and rotation changes classification`() {
        val pages = pages(5)
        val facts = facts(pages).toMutableMap()
        facts[pages[0]] = ReaderPageFact.dimensions(1200, 800)
        facts[pages[2]] = ReaderPageFact.dimensions(900, 900)
        facts[pages[3]] = ReaderPageFact.dimensions(600, 1000, PageRotation.CLOCKWISE_90)
        val units = SharedDualPageRules.build(pages, facts, ReaderDirection.RTL)
        for (index in listOf(0, 2, 3)) {
            val unit = units.single { pages[index] in it.pageIds }
            assertEquals(listOf(pages[index]), unit.pageIds)
            assertEquals(ReaderPortraitSingleSlot.FULL, unit.slots.single().position)
            assertEquals(ReaderDualPageSingleReason.SPREAD, unit.singleReason)
        }
    }

    @Test
    fun `opening single preserved and remainder before width faces trailing side`() {
        val units = build(8, setOf(0, 2, 6, 7))
        assertEquals(
            listOf(listOf(1), listOf(2), listOf(3), listOf(4, 5), listOf(6), listOf(7), listOf(8)),
            groups(units),
        )
        assertEquals(ReaderPortraitSingleSlot.LEFT, units[1].slots.single().position)
        assertEquals(ReaderDualPageSingleReason.OPENING, units[1].singleReason)
        assertEquals(ReaderPortraitSingleSlot.RIGHT, units[4].slots.single().position)
        assertEquals(ReaderDualPageSingleReason.REMAINDER_BEFORE_SPREAD, units[4].singleReason)
    }

    @Test
    fun `segment leading portrait reserved for match or manual boundary uses stable reading side`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val side = if (direction == ReaderDirection.RTL) {
                ReaderPortraitSingleSlot.RIGHT
            } else {
                ReaderPortraitSingleSlot.LEFT
            }
            val matched = build(8, setOf(3), direction, matches = setOf(PagePair(5, 6)))
            val leading = matched.single { it.anchor.sourcePageIndex == 4 }
            assertEquals(
                listOf(listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6, 7), listOf(8)),
                groups(matched),
            )
            assertEquals(side, leading.slots.single().position)
            assertEquals(ReaderDualPageSingleReason.MATCH_BOUNDARY, leading.singleReason)
            val manual = build(8, setOf(3), direction, singles = setOf(5))
            val beforeManual = manual.single { it.anchor.sourcePageIndex == 4 }
            assertEquals(side, beforeManual.slots.single().position)
            assertEquals(ReaderDualPageSingleReason.MANUAL_BOUNDARY, beforeManual.singleReason)
            assertEquals(
                ReaderPortraitSingleSlot.FULL,
                build(9, setOf(3, 5), direction)
                    .single { it.anchor.sourcePageIndex == 4 }.slots.single().position,
            )
        }
    }

    @Test
    fun `interior portrait between widths is ambiguous and remains centered`() {
        val units = build(9, setOf(3, 5))
        val orphan = units.single { it.anchor.sourcePageIndex == 4 }
        assertEquals(ReaderPortraitSingleSlot.FULL, orphan.slots.single().position)
        assertEquals(ReaderDualPageSingleReason.AMBIGUOUS, orphan.singleReason)
    }

    @Test
    fun `unknown and failed facts never masquerade as portraits or permanent boundaries`() {
        val pages = pages(7)
        val facts = facts(pages).toMutableMap()
        facts.remove(pages[3])
        facts[pages[6]] = ReaderPageFact(ReaderPageFactState.FAILED)
        val units = SharedDualPageRules.build(pages, facts, ReaderDirection.RTL)
        assertEquals(listOf(listOf(1), listOf(2, 3)), groups(units).take(2))
        assertEquals(listOf(listOf(4, 5), listOf(6, 7)), groups(units).drop(2))
        for (unit in units.drop(2)) {
            assertFalse(unit.certain)
            assertEquals(ReaderPortraitSingleSlot.RIGHT, unit.slots.first().position)
        }
        facts[pages[5]] = ReaderPageFact.dimensions(1800, 1000)
        facts[pages[6]] = ReaderPageFact.dimensions(1000, 1500)
        val anchored = SharedDualPageRules.build(pages, facts, ReaderDirection.RTL)
        assertTrue(anchored.last().certain)
        assertEquals(ReaderPortraitSingleSlot.RIGHT, anchored.last().slots.single().position)
    }

    @Test
    fun `manual boundaries suppress only conflicting matches and matched pair anchors unknown prefix`() {
        val pages = pages(8)
        val facts = facts(pages).toMutableMap().apply { remove(pages[2]) }
        val units = SharedDualPageRules.build(
            pages,
            facts,
            ReaderDirection.RTL,
            setOf(pages[3]),
            setOf(pages[2] to pages[3], pages[4] to pages[5]),
            true,
        )
        assertTrue(listOf(5, 6) in groups(units))
        assertTrue(units.single { pages[4] in it.pageIds }.certain)
        assertTrue(listOf(7, 8) in groups(units))
        assertEquals(listOf(4), groups(units).single { 4 in it })
    }

    @Test
    fun `strong match shifts default pairing locally without splitting final double`() {
        assertEquals(
            listOf(listOf(1), listOf(2), listOf(3, 4), listOf(5, 6)),
            groups(build(6, matches = setOf(PagePair(2, 3)))),
        )
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5)), groups(build(5)))
    }

    @Test
    fun `every permutation of facts has identical final coverage slots and stable identities`() {
        val pages = pages(17)
        val facts = facts(pages, setOf(13))
        val expected = SharedDualPageRules.build(pages, facts, ReaderDirection.RTL)
        val evenThenOdd = pages.filterIndexed { i, _ -> i % 2 == 0 } + pages.filterIndexed { i, _ -> i % 2 != 0 }
        for (order in listOf(pages, pages.reversed(), evenThenOdd)) {
            val arriving = mutableMapOf<ReaderPageId, ReaderPageFact>()
            for (page in order) {
                arriving[page] = facts.getValue(page)
                val units = SharedDualPageRules.build(pages, arriving, ReaderDirection.RTL)
                assertEquals(pages, units.flatMap { it.pageIds })
                assertEquals(units.size, units.map { it.id }.distinct().size)
            }
            assertEquals(expected, SharedDualPageRules.build(pages, arriving, ReaderDirection.RTL))
        }
    }
}
