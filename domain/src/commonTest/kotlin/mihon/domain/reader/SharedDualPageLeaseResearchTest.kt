package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedDualPageLeaseResearchTest {
    @Test
    fun `prepared slot may report its first real draw after facts change but not after navigation or release`() {
        session().use { owner ->
            val ids = owner.identity.pageIds
            val ticket = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
            owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact.dimensions(200, 100))
            assertTrue(owner.reportDrawn(ticket, setOf(ids[1])))
            assertNotNull(owner.settle(event(ticket, setOf(ids[1])), false, "first-draw"))
            assertFalse(owner.reportDrawn(ticket, setOf(ids[2])))
        }
        session().use { owner ->
            val ids = owner.identity.pageIds
            val ticket = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
            owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact.dimensions(200, 100))
            owner.goToPage(ids[3])
            assertFalse(owner.reportDrawn(ticket, setOf(ids[1])))
            assertNull(owner.settle(event(ticket, setOf(ids[1])), false, "old-navigation"))
        }
        session().use { owner ->
            val ids = owner.identity.pageIds
            val ticket = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
            owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact.dimensions(200, 100))
            owner.releaseViewport(ticket)
            assertFalse(owner.reportDrawn(ticket, setOf(ids[1])))
        }
    }

    @Test
    fun `two visible leases preserve unique coverage and leave an unpainted wide page reachable`() {
        session().use { owner ->
            val ids = owner.identity.pageIds
            val current = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
            val preview = owner.captureViewport(pageId = ids[3], preparingPageIds = setOf(ids[3]))!!
            assertTrue(owner.reportDrawn(current, setOf(ids[1])))
            assertTrue(owner.reportDrawn(preview, setOf(ids[3])))
            val currentSlot = owner.frame(current)!!.unit.slots.single { it.pageId == ids[1] }
            val previewSlot = owner.frame(preview)!!.unit.slots.single { it.pageId == ids[3] }
            owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact.dimensions(200, 100))
            assertEquals(ids, owner.canonicalUnits.flatMap { it.pageIds })
            assertEquals(ids, owner.presentationFrameProjection.flatMap { it.pageIds })
            assertEquals(currentSlot, owner.frame(current)!!.unit.slots.single { it.pageId == ids[1] })
            assertEquals(previewSlot, owner.frame(preview)!!.unit.slots.single { it.pageId == ids[3] })
            assertNull(owner.settle(event(preview, setOf(ids[3])), false, "preview"))
            assertTrue(owner.navigate(true))
            assertEquals(ids[2], owner.location!!.pageId)
            assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
            assertTrue(owner.navigate(true))
            assertEquals(ids[3], owner.location!!.pageId)
            assertTrue(owner.releaseViewport(current))
            assertEquals(ids, owner.presentationFrameProjection.flatMap { it.pageIds })
            assertTrue(owner.releaseViewport(preview))
            assertEquals(owner.canonicalUnits, owner.presentationFrameProjection)
        }
    }

    @Test
    fun `late canonical changes retain the visible projection and turn past its actual frame`() {
        session().use { owner ->
            val ids = owner.identity.pageIds
            val ticket = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
            assertTrue(owner.reportDrawn(ticket, setOf(ids[1])))
            val shown = owner.frame(ticket)!!.unit
            owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact.dimensions(200, 100))
            val retained = shown.copy(slots = shown.slots.filter { it.pageId == ids[1] })
            assertEquals(retained, owner.units.single { ids[1] in it.pageIds })
            assertEquals(retained, owner.targetUnit)
            assertEquals(
                ReaderPortraitSingleSlot.FULL,
                owner.units.single {
                    ids[2] in it.pageIds
                }.slots.single().position,
            )
            assertEquals(ids, owner.units.flatMap { it.pageIds })
            assertEquals(ids, owner.canonicalUnits.flatMap { it.pageIds })
            assertEquals(listOf(ids[1]), owner.canonicalTargetUnit!!.pageIds)
            assertSame(ticket, owner.captureViewport(preparingPageIds = emptySet()))
            assertFalse(owner.preparePage(ticket, ids[2]))
            assertFalse(owner.reportDrawn(ticket, setOf(ids[2])))
            assertTrue(owner.navigate(true))
            assertEquals(ids[2], owner.location!!.pageId)
            assertTrue(owner.releaseViewport(ticket))
            owner.goToPage(ids[1])
            assertEquals(listOf(ids[1]), owner.targetUnit!!.pageIds)
            assertEquals(ReaderDualPageSingleReason.SPREAD, owner.units.single { ids[2] in it.pageIds }.singleReason)
            assertEquals(owner.canonicalUnits, owner.presentationFrameProjection)
        }
    }

    @Test
    fun `late facts preserve real pending settlement but reject repeats navigation and release`() {
        session().use { owner ->
            val ids = owner.identity.pageIds
            val ticket = owner.captureViewport()!!
            assertTrue(owner.reportDrawn(ticket, setOf(ids[1])))
            owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact.dimensions(200, 100))
            val actual = event(ticket, setOf(ids[1]))
            assertNotNull(owner.settle(actual, false, "lease"))
            assertNull(owner.settle(actual.copy(settlementSequence = 2), false, "lease"))
            assertNull(owner.settle(event(ticket, setOf(ids[2])), false, "lease"))
            owner.goToPage(ids[3])
            assertNull(owner.settle(actual.copy(settlementSequence = 3), false, "lease"))
            owner.releaseViewport(ticket)
            assertNull(owner.settle(actual.copy(settlementSequence = 4), false, "lease"))
        }
    }

    private fun event(ticket: ReaderDualPageViewportTicket, pages: Set<ReaderPageId>) = ReaderDualPageSettlement(
        ticket.identity, ticket.layoutRevision, ticket.locationSequence, ticket.unitId,
        pages, true, ReaderDualPageSettlementReason.USER_NAVIGATION, 1, ticket,
    )

    private fun session(): SharedDualPageSession {
        val ids = List(8) { ReaderPageId(ReaderChapterId(40), it) }
        return SharedDualPageSession(
            ReaderDualPageIdentity(1, 1, ids.map { ReaderDualPageContentIdentity(it, "source-$it") }),
            ReaderDirection.RTL,
            ids[1],
        ).also { owner ->
            ids.forEach { owner.acceptFact(owner.requestFact(it), ReaderPageFact.dimensions(100, 150)) }
        }
    }

    private inline fun SharedDualPageSession.use(block: (SharedDualPageSession) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
