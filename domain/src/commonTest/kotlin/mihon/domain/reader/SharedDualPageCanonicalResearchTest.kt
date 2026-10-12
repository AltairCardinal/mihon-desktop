package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Synthetic facts replay the production session failures without distributing source images. */
class SharedDualPageCanonicalResearchTest {
    @Test
    fun `portrait cover uses trailing half while real spread uses full width in both directions`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            session(1, 8).use { owner ->
                owner.configure(direction, true)
                assertEquals(
                    if (direction ==
                        ReaderDirection.RTL
                    ) {
                        ReaderPortraitSingleSlot.LEFT
                    } else {
                        ReaderPortraitSingleSlot.RIGHT
                    },
                    owner.targetUnit!!.slots.single().position,
                )
                owner.acceptFact(owner.requestFact(owner.id(1)), ReaderPageFact.dimensions(1400, 980))
                assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
            }
        }
    }

    @Test
    fun `stronger later edge reserves its adjacent pair against a still later competitor`() {
        session(1, 8).use { owner ->
            val ids = owner.identity.pageIds
            val plan = SharedDualPageRules.canonicalPlan(
                ids,
                ids.associateWith { portrait },
                ReaderDirection.RTL,
                automatic = true,
                distance = { a, b ->
                    if (b.sourcePageIndex == a.sourcePageIndex + 1) {
                        mapOf(1 to 20.0, 2 to 10.0, 3 to 0.0)[a.sourcePageIndex]
                    } else {
                        null
                    }
                },
            )
            assertEquals(listOf(ids[2], ids[3]), plan.single { ids[2] in it.pageIds }.pageIds)
        }
    }

    @Test
    fun `released frames do not retain unbounded metadata after repeated source navigation`() {
        session(2, 8).use { owner ->
            repeat(1000) {
                owner.goToPage(owner.id(2), true)
                val ticket = owner.captureViewport()!!
                owner.reportDrawn(ticket, owner.frame(ticket)!!.unit.pageIds.toSet())
                owner.releaseViewport(ticket)
            }
            assertTrue(owner.displayedHistory.size <= 64, "History is diagnostics, never an unbounded ownership store")
        }
    }

    @Test
    fun `original volume eight side facts recover 113 114 and 115 116 after neighbour exposure`() {
        session(112, 176).use { owner ->
            val previous = owner.captureViewport()!!
            val neighbour = owner.captureViewport(owner.id(114))!!
            assertTrue(owner.reportDrawn(neighbour, owner.frame(neighbour)!!.unit.pageIds.toSet()))
            owner.releaseViewport(neighbour)
            assertTrue(owner.reportDrawn(previous, owner.frame(previous)!!.unit.pageIds.toSet()))
            owner.releaseViewport(previous)
            // Recorded original-image classifications. All unlisted pages are UNKNOWN; no edge samples exist.
            for ((page, side) in mapOf(
                112 to ReaderPageSideHint.LEFT,
                113 to ReaderPageSideHint.RIGHT,
                117 to ReaderPageSideHint.RIGHT,
                118 to ReaderPageSideHint.LEFT,
            )) {
                owner.acceptFact(owner.requestFact(owner.id(page)), portrait.copy(sideHint = side))
            }
            owner.goToPage(owner.id(113), true)
            assertEquals(listOf(113, 114), owner.group())
            assertTrue(owner.navigate(true))
            assertEquals(listOf(115, 116), owner.group())
            assertPartition(owner)
        }
    }

    @Test
    fun `fact arrival permutations and distant late wide evidence converge for all navigation paths`() {
        val finalFacts = (1..24).associateWith { page ->
            when (page) {
                4, 11 -> ReaderPageFact.dimensions(1400, 980)
                18 -> portrait.copy(sideHint = ReaderPageSideHint.LEFT)
                19 -> portrait.copy(sideHint = ReaderPageSideHint.RIGHT)
                else -> portrait
            }
        }
        session(1, 24).use { reference ->
            finalFacts.forEach { (page, fact) -> reference.acceptFact(reference.requestFact(reference.id(page)), fact) }
            val expected = reference.partition()
            repeat(24) { seed ->
                session(seed + 1, 24).use { owner ->
                    for ((page, fact) in finalFacts.entries.shuffled(Random(seed))) {
                        owner.acceptFact(owner.requestFact(owner.id(page)), fact)
                        owner.goToPage(owner.id((page + seed) % 24 + 1), seed % 2 == 0)
                        owner.captureViewport()?.let { ticket ->
                            owner.reportDrawn(ticket, owner.frame(ticket)!!.lockedPageIds)
                            owner.releaseViewport(ticket)
                        }
                    }
                    assertEquals(expected, owner.partition(), "Fact permutation and navigation seed $seed")
                    assertPartition(owner)
                }
            }
        }
    }

    @Test
    fun `same facts produce the same complete partition for every initial target`() {
        val reference = session(1, 18).use { it.partition() }
        for (page in 1..18) {
            session(page, 18).use { owner ->
                assertEquals(reference, owner.partition(), "Initial source page $page cannot choose pairing phase")
                assertPartition(owner)
            }
        }
    }

    @Test
    fun `partial neighbour preview release cannot permanently split page 93 and 94`() {
        session(91, 188).use { owner ->
            val preview = owner.captureViewport(owner.id(93), preparingPageIds = emptySet())!!
            assertTrue(owner.acceptDecodedFact(preview, owner.id(93), 980, 1393, ReaderPageSideHint.RIGHT))
            val current = owner.frame(preview)?.let { preview }
                ?: owner.captureViewport(owner.id(93), preparingPageIds = emptySet())!!
            assertTrue(owner.preparePage(current, owner.id(93)))
            assertTrue(owner.reportDrawn(current, setOf(owner.id(93))))
            assertTrue(owner.releaseViewport(current))
            assertTrue(owner.goToPage(owner.id(93), true))
            assertEquals(listOf(93, 94), owner.group())
            val returned = owner.captureViewport(preparingPageIds = emptySet())!!
            assertFalse(owner.frame(returned)!!.drawnPageIds.contains(owner.id(94)))
            assertNull(owner.frame(current), "A released preview has no remaining pixel authority")
            assertPartition(owner)
        }
    }

    @Test
    fun `late left evidence recovers page 125 and 126 despite older displayed 124 and 125`() {
        session(120, 176).use { owner ->
            for (page in listOf(120, 122, 124)) {
                owner.goToPage(owner.id(page), true)
                val ticket = owner.captureViewport()!!
                assertTrue(owner.reportDrawn(ticket, owner.frame(ticket)!!.unit.pageIds.toSet()))
                owner.releaseViewport(ticket)
            }
            val incoming = owner.captureViewport(owner.id(126), preparingPageIds = emptySet())!!
            assertTrue(owner.acceptDecodedFact(incoming, owner.id(126), 980, 1403, ReaderPageSideHint.LEFT))
            owner.acceptFact(owner.requestFact(owner.id(127)), portrait.copy(sideHint = ReaderPageSideHint.RIGHT))
            assertTrue(owner.goToPage(owner.id(126), true))
            assertEquals(listOf(125, 126), owner.group())
            assertFalse(
                owner.preparePage(incoming, owner.id(127)),
                "Obsolete proposal cannot paint a displaced partner",
            )
            session(126, 176).use { fresh ->
                copyFacts(owner, fresh)
                assertEquals(fresh.partition(), owner.partition())
            }
            assertPartition(owner)
        }
    }

    @Test
    fun `neighbour first exposure cannot keep incorrect 114 and 115 across late 113 through 116 sides`() {
        session(112, 176).use { owner ->
            val oldNeighbour = owner.captureViewport(owner.id(114))!!
            assertTrue(owner.reportDrawn(oldNeighbour, owner.frame(oldNeighbour)!!.unit.pageIds.toSet()))
            owner.releaseViewport(oldNeighbour)
            for ((page, side) in listOf(
                113 to ReaderPageSideHint.RIGHT,
                114 to ReaderPageSideHint.LEFT,
                115 to ReaderPageSideHint.RIGHT,
                116 to ReaderPageSideHint.LEFT,
            )) {
                assertTrue(owner.acceptFact(owner.requestFact(owner.id(page)), portrait.copy(sideHint = side)))
            }
            owner.goToPage(owner.id(113), true)
            assertEquals(listOf(113, 114), owner.group())
            assertTrue(owner.navigate(true))
            assertEquals(listOf(115, 116), owner.group())
            assertPartition(owner)
        }
    }

    private fun session(page: Int, count: Int): SharedDualPageSession {
        val ids = List(count) { ReaderPageId(ReaderChapterId(1), it) }
        return SharedDualPageSession(
            ReaderDualPageIdentity(
                1,
                1,
                ids.map {
                    ReaderDualPageContentIdentity(it, "synthetic-${it.sourcePageIndex}")
                },
            ),
            ReaderDirection.RTL,
            ids[page - 1],
        ).also { owner ->
            owner.configure(ReaderDirection.RTL, true)
            ids.forEach { assertTrue(owner.acceptFact(owner.requestFact(it), portrait)) }
        }
    }

    private fun copyFacts(from: SharedDualPageSession, to: SharedDualPageSession) {
        from.identity.pageIds.forEach { id ->
            from.pageFact(id)?.let { assertTrue(to.acceptFact(to.requestFact(id), it)) }
        }
    }

    private fun assertPartition(owner: SharedDualPageSession) {
        val flattened = owner.units.flatMap { it.pageIds }
        assertEquals(owner.identity.pageIds, flattened, "Every source occurs exactly once in chapter order")
        owner.units.forEach { unit ->
            assertTrue(unit.pageIds.size in 1..2)
            assertTrue(unit.pageIds.zipWithNext().all { (a, b) -> b.sourcePageIndex == a.sourcePageIndex + 1 })
        }
    }

    private fun SharedDualPageSession.id(page: Int) = identity.pageIds[page - 1]
    private fun SharedDualPageSession.group() = targetUnit!!.pageIds.map { it.sourcePageIndex + 1 }
    private fun SharedDualPageSession.partition() = units.map { it.pageIds.map { id -> id.sourcePageIndex + 1 } }
    private inline fun <T> SharedDualPageSession.use(block: (SharedDualPageSession) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private val portrait = ReaderPageFact.dimensions(980, 1403)
}
