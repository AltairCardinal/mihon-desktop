package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Real certification precedes first exposure; drawn leases and pending reading survive later facts.
 * These contracts do not establish the latency of acquiring previously missing evidence.
 */
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
        val ids = List(6) { ReaderPageId(ReaderChapterId(8), it) }
        val identity = ReaderDualPageIdentity(1, 1, ids.map { ReaderDualPageContentIdentity(it, "same-content-$it") })
        val portrait = ReaderPageFact.dimensions(100, 150)
        val wide = ReaderPageFact.dimensions(200, 150)
        val originalFacts = ids.associateWith { if (it in setOf(ids[1], ids[5])) wide else portrait }
        val warm = SharedDualPageSession(identity, ReaderDirection.RTL, ids[2])
        val cold = SharedDualPageSession(identity, ReaderDirection.RTL, ids[2])
        try {
            originalFacts.forEach { (page, fact) ->
                assertTrue(warm.acceptFact(warm.requestFact(page), fact))
            }
            assertTrue(cold.acceptFact(cold.requestFact(ids[2]), originalFacts.getValue(ids[2])))
            assertTrue(cold.certifyPairing() is ReaderPairingCertification.NeedFacts)
            assertNull(
                cold.captureCertifiedViewport(ids[2]),
                "Ready geometry alone cannot prove the first physical slot",
            )
            assertTrue(cold.displayedHistory.isEmpty(), "No uncertified page has been exposed")

            // Consume only actual production requests, using the same frozen original metadata as warm.
            // Automatic matching is off, so this certificate requires original dimensions, not pixels.
            var rounds = 0
            while (true) {
                val required = cold.certifyPairing()
                if (required is ReaderPairingCertification.Certified) break
                assertTrue(
                    required is ReaderPairingCertification.NeedFacts,
                    "Unexpected certification state: $required",
                )
                required as ReaderPairingCertification.NeedFacts
                assertTrue(++rounds <= ids.size, "Finite metadata acquisition must make progress")
                assertTrue(required.facts.isNotEmpty())
                required.facts.forEach { need ->
                    assertEquals(ReaderPairingFactKind.DIMENSIONS, need.kind)
                    assertTrue(cold.acceptFact(cold.requestFact(need.pageId), originalFacts.getValue(need.pageId)))
                }
            }
            assertNull(
                cold.pageFact(ids[5]),
                "The later background fact must not be consumed during certification",
            )
            val warmTicket = warm.captureCertifiedViewport(ids[2])!!
            val coldTicket = cold.captureCertifiedViewport(ids[2])!!
            assertEquals(listOf(ids[2], ids[3]), warm.frame(warmTicket)!!.unit.pageIds)
            assertEquals(listOf(ids[2], ids[3]), cold.frame(coldTicket)!!.unit.pageIds)
            assertTrue(warm.preparePage(warmTicket, ids[2]))
            assertTrue(cold.preparePage(coldTicket, ids[2]))
            assertTrue(warm.reportDrawn(warmTicket, setOf(ids[2])))
            assertTrue(cold.reportDrawn(coldTicket, setOf(ids[2])))
            val firstColdSlot = cold.frame(coldTicket)!!.unit.slots.single { it.pageId == ids[2] }
            assertEquals(ReaderPortraitSingleSlot.RIGHT, firstColdSlot.position, "A real wide page 1 resets group 2/3")
            assertEquals(warm.frame(warmTicket)!!.unit.slots.single { it.pageId == ids[2] }, firstColdSlot)
            val drawn = cold.frame(coldTicket)!!
            assertTrue(cold.acceptFact(cold.requestFact(ids[5]), originalFacts.getValue(ids[5])))
            assertEquals(drawn, cold.frame(coldTicket), "Unrelated original facts cannot move already drawn pixels")
            val settlement = ReaderDualPageSettlement(
                coldTicket.identity, coldTicket.layoutRevision, coldTicket.locationSequence, coldTicket.unitId,
                setOf(ids[2]), true, ReaderDualPageSettlementReason.USER_NAVIGATION, 1, coldTicket,
            )
            assertEquals(2, cold.settle(settlement, false, "certified")!!.lastPageRead)
            assertNull(cold.settle(settlement.copy(settlementSequence = 2), false, "certified"))
        } finally {
            warm.close()
            cold.close()
        }
    }
}
