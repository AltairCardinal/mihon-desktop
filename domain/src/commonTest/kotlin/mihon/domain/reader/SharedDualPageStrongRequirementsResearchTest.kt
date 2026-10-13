package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Intentionally red feasibility gate: a canonical metadata plan alone cannot satisfy both demands. */
class SharedDualPageStrongRequirementsResearchTest {
    @Test
    fun `facts arriving after real drawing must not lose the pending reading settlement`() {
        val ids = List(5) { ReaderPageId(ReaderChapterId(8), it) }
        val identity = ReaderDualPageIdentity(1, 1, ids.map { ReaderDualPageContentIdentity(it, "same-content-$it") })
        val owner = SharedDualPageSession(identity, ReaderDirection.RTL, ids[1])
        try {
            ids.forEach { owner.acceptFact(owner.requestFact(it), ReaderPageFact.dimensions(100, 150)) }
            val ticket = owner.captureViewport()!!
            assertTrue(owner.reportDrawn(ticket, setOf(ids[1])))
            owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact.dimensions(200, 150))
            val settlement = ReaderDualPageSettlement(
                ticket.identity, ticket.layoutRevision, ticket.locationSequence, ticket.unitId,
                setOf(ids[1]), true, ReaderDualPageSettlementReason.USER_NAVIGATION, 1, ticket,
            )
            assertNotNull(
                owner.settle(settlement, false, "research"),
                "A new logical plan must not discard a real previously drawn page's unsettled reading event",
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun `cold ready page and warm complete content must have identical first physical slot`() {
        val ids = List(5) { ReaderPageId(ReaderChapterId(8), it) }
        val identity = ReaderDualPageIdentity(1, 1, ids.map { ReaderDualPageContentIdentity(it, "same-content-$it") })
        val portrait = ReaderPageFact.dimensions(100, 150)
        val wide = ReaderPageFact.dimensions(200, 150)
        val warm = SharedDualPageSession(identity, ReaderDirection.RTL, ids[2])
        val cold = SharedDualPageSession(identity, ReaderDirection.RTL, ids[2])
        try {
            warm.acceptFact(warm.requestFact(ids[1]), wide)
            warm.acceptFact(warm.requestFact(ids[2]), portrait)
            cold.acceptFact(cold.requestFact(ids[2]), portrait)
            val warmTicket = warm.captureViewport(preparingPageIds = emptySet())!!
            val coldTicket = cold.captureViewport(preparingPageIds = emptySet())!!
            assertTrue(warm.preparePage(warmTicket, ids[2]), "Ready images must render immediately")
            assertTrue(
                cold.preparePage(coldTicket, ids[2]),
                "Ready images must render immediately without prefix facts",
            )
            assertTrue(warm.reportDrawn(warmTicket, setOf(ids[2])))
            assertTrue(cold.reportDrawn(coldTicket, setOf(ids[2])))
            val firstColdSlot = cold.frame(coldTicket)!!.unit.slots.single { it.pageId == ids[2] }
            cold.acceptFact(cold.requestFact(ids[1]), wide)
            assertEquals(
                firstColdSlot,
                cold.frame(coldTicket)!!.unit.slots.single { it.pageId == ids[2] },
                "Background evidence must not move existing pixels",
            )
            assertEquals(
                warm.frame(warmTicket)!!.unit.slots.single { it.pageId == ids[2] },
                firstColdSlot,
                "The same content must choose the same slot even without a prior content index",
            )
        } finally {
            warm.close()
            cold.close()
        }
    }
}
