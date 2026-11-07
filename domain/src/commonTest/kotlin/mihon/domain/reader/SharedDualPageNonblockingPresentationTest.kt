package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedDualPageNonblockingPresentationTest {
    @Test
    fun `cold 126 can draw with only its geometry while analysis and restore remain pending`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val owner = owner(direction = direction, mangaId = 3)
            val ids = owner.identity.pageIds
            owner.configure(direction, automaticMatching = true)
            assertNotNull(owner.beginPairingRestore())
            val ticket = owner.capturePresentableViewport(ids[125])!!
            assertEquals(ReaderDualPagePresentationSource.DEFAULT, ticket.source)
            assertEquals(listOf(ids[125], ids[126]), owner.frame(ticket)!!.unit.pageIds)
            assertFalse(owner.preparePage(ticket, ids[125]))
            assertTrue(owner.acceptDecodedFact(ticket, ids[125], 100, 150))
            assertTrue(owner.preparePage(ticket, ids[125]))
            assertTrue(owner.reportDrawn(ticket, setOf(ids[125])))
            assertTrue(owner.certifyPairing() is ReaderPairingCertification.NeedFacts)
            assertNull(owner.captureCertifiedViewport(ids[125]))
            assertFalse(owner.preparePage(ticket, ids[126]))
            assertEquals(ids[125], owner.location!!.pageId)
            assertEquals(setOf(ids[125]), owner.frame(ticket)!!.drawnPageIds)
        }
    }

    @Test
    fun `late reliable regrouping preserves pixels then overlaps boundary on both turn directions`() {
        for (forward in listOf(true, false)) {
            for (drawn in listOf(setOf(125), setOf(126), setOf(125, 126))) {
                val owner = owner()
                val ids = owner.identity.pageIds
                val old = owner.capturePresentableViewport(ids[125])!!
                drawn.forEach { paint(owner, old, it) }
                val before = owner.frame(old)!!
                // A real earlier wide image changes the phase without supplying any fake analysis.
                ids.forEachIndexed { index, id ->
                    assertTrue(
                        owner.acceptFact(
                            owner.requestFact(id),
                            ReaderPageFact.dimensions(
                                if (index ==
                                    1
                                ) {
                                    200
                                } else {
                                    100
                                },
                                150,
                            ),
                        ),
                    )
                }
                assertTrue(owner.certifyPairing() is ReaderPairingCertification.Certified)
                assertEquals(before.unit.slots, owner.frame(old)!!.unit.slots)
                assertEquals(before.drawnPageIds, owner.frame(old)!!.drawnPageIds)
                assertTrue(owner.navigate(forward))
                val expectedAnchor = if (forward) {
                    if (126 in drawn) 127 else 126
                } else {
                    124
                }
                assertEquals(ids[expectedAnchor], owner.location!!.pageId)
                val projectedTarget = owner.targetUnit!!
                assertTrue(owner.units.any { it.id == projectedTarget.id })
                val next = owner.capturePresentableViewport(owner.location!!.pageId)!!
                assertEquals(
                    projectedTarget.id,
                    owner.frame(next)!!.unit.id,
                    "Projection and before-draw must choose the same target",
                )
                assertNotEquals(old.frameId, next.frameId)
                assertEquals(next.frameId, owner.targetFrameId)
                assertEquals(
                    if (forward) listOf(ids[126], ids[127]) else listOf(ids[124], ids[125]),
                    owner.frame(next)!!.unit.pageIds,
                )
                val repeat = if (forward) 126 else 125
                paint(owner, next, repeat)
                if (repeat in drawn) assertTrue(owner.reportDrawn(old, setOf(ids[repeat])))
                assertTrue(owner.units.count { ids[repeat] in it.pageIds } >= 2)
                assertTrue(owner.releaseViewport(old))
                assertNotNull(owner.frame(next))
                assertEquals(1, owner.units.count { ids[repeat] in it.pageIds })
            }
        }
    }

    @Test
    fun `cancelled neighbour and rapid turns retain only live bounded frame leases`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        var current = owner.capturePresentableViewport(ids[125])!!
        paint(owner, current, 125)
        val anchor = owner.location
        val neighbour = owner.capturePresentableViewport(ids[127])!!
        assertTrue(owner.preparePage(neighbour, ids[127]).not())
        assertTrue(owner.releaseViewport(neighbour))
        assertEquals(anchor, owner.location, "Cancelling a prepared neighbour cannot commit navigation")
        assertEquals(current.frameId, owner.targetFrameId)
        repeat(20) { turn ->
            val outgoing = current
            assertTrue(owner.navigate(turn % 2 == 0))
            current = owner.capturePresentableViewport(owner.location!!.pageId)!!
            paint(owner, current, owner.location!!.pageId.sourcePageIndex)
            assertTrue(owner.releaseViewport(outgoing))
            assertTrue(owner.presentationFrames.count { it.frameId != null } <= 1)
            assertTrue(owner.units.flatMap { it.pageIds }.groupingBy { it }.eachCount().values.all { it == 1 })
        }
    }

    @Test
    fun `unpainted partner is activated once then can be deliberately left in default and certified frames`() {
        for (certified in listOf(false, true)) {
            val owner = owner()
            val ids = owner.identity.pageIds
            if (certified) {
                ids.forEach {
                    assertTrue(owner.acceptFact(owner.requestFact(it), ReaderPageFact.dimensions(100, 150)))
                }
            }
            val ticket = owner.capturePresentableViewport(ids[125])!!
            paint(owner, ticket, 125)
            assertTrue(owner.navigate(true))
            assertEquals(ids[126], owner.location!!.pageId)
            val partner = owner.capturePresentableViewport(ids[126])!!
            assertEquals(ticket.frameId, partner.frameId)
            assertEquals(setOf(ids[125]), owner.frame(partner)!!.drawnPageIds)
            assertTrue(owner.navigate(true))
            assertEquals(ids[127], owner.location!!.pageId)
            assertEquals(listOf(ids[127], ids[128]), owner.targetUnit!!.pageIds)
            assertFalse(owner.reportDrawn(ticket, setOf(ids[126])))
        }
    }

    @Test
    fun `empty frames and exact trailing jump advance by the source anchor`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        owner.capturePresentableViewport(ids[125])!!
        assertTrue(owner.navigate(true))
        assertEquals(ids[126], owner.location!!.pageId)
        assertTrue(owner.navigate(true))
        assertEquals(ids[127], owner.location!!.pageId)
        assertTrue(owner.goToPage(ids[126]))
        owner.capturePresentableViewport(ids[126])!!
        assertTrue(owner.navigate(true))
        assertEquals(ids[127], owner.location!!.pageId)
        assertTrue(owner.goToPage(ids[126]))
        owner.capturePresentableViewport(ids[126])!!
        assertTrue(owner.navigate(false))
        assertEquals(ids[125], owner.location!!.pageId)
        assertTrue(owner.navigate(false))
        assertEquals(ids[124], owner.location!!.pageId)
    }

    @Test
    fun `late wide partner remains a navigation responsibility after its slot is withdrawn`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        val ticket = owner.capturePresentableViewport(ids[125])!!
        paint(owner, ticket, 125)
        assertTrue(owner.acceptFact(owner.requestFact(ids[126]), ReaderPageFact.dimensions(200, 150)))
        assertEquals(listOf(ids[125]), owner.frame(ticket)!!.unit.pageIds)
        assertTrue(owner.navigate(true))
        assertEquals(ids[126], owner.location!!.pageId)
        assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
    }

    @Test
    fun `withdrawn wide logical anchor keeps the actually drawn partner frame until an explicit source jump`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        val ticket = owner.capturePresentableViewport(ids[125])!!
        paint(owner, ticket, 126)
        assertTrue(owner.acceptDecodedFact(ticket, ids[125], 200, 150))
        val stable = owner.frame(ticket)!!
        assertEquals(listOf(ids[126]), stable.unit.pageIds)
        assertEquals(stable.unit, owner.targetUnit)
        assertEquals(stable.id, owner.preparePresentationFrame())
        val fresh = owner.capturePresentableViewport(ids[125], frameId = stable.id, leaseOwner = Any())!!
        assertFalse(owner.preparePage(fresh, ids[125]))
        assertFalse(owner.reportDrawn(fresh, setOf(ids[125])))
        assertTrue(owner.reportDrawn(fresh, setOf(ids[126])))
        assertEquals(ids[125], owner.location!!.pageId)
        assertNull(owner.adjustPairing(), "A withdrawn logical anchor cannot adjust its retained physical partner")
        assertEquals(ids[125], owner.location!!.pageId)
        assertTrue(owner.pairingState.manualPairStarts.isEmpty())
        assertTrue(owner.goToPage(ids[125]))
        assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
    }

    @Test
    fun `only actual draw settles default progress and closed or rebound capabilities are rejected`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        val ticket = owner.capturePresentableViewport(ids[125])!!
        val event =
            ReaderDualPageSettlement(
                ticket.identity, ticket.layoutRevision, ticket.locationSequence, ticket.unitId,
                setOf(ids[125]), true, ReaderDualPageSettlementReason.USER_NAVIGATION, 1, ticket,
            )
        assertNull(owner.settle(event, false, "nonblocking"))
        paint(owner, ticket, 125)
        assertEquals(125, owner.settle(event, false, "nonblocking")!!.lastPageRead)
        assertNull(owner.settle(event.copy(settlementSequence = 2), false, "nonblocking"))
        val next = owner.rebindContent(owner.identity.copy(pageListRevision = 2))
        assertFalse(next.reportDrawn(ticket, setOf(ids[125])))
        assertFalse(next.preparePage(ticket, ids[125]))
        assertTrue(next.releaseViewport(ticket))
        val fresh = next.capturePresentableViewport(ids[125])!!
        next.close()
        assertFalse(next.reportDrawn(fresh, setOf(ids[125])))
        assertNull(next.capturePresentableViewport(ids[125]))
    }

    @Test
    fun `overlapping neighbour requires its exact unit and frame without activating or reporting reading`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        val current = owner.capturePresentableViewport(ids[125])!!
        paint(owner, current, 125)
        ids.forEachIndexed { index, page ->
            assertTrue(
                owner.acceptFact(
                    owner.requestFact(page),
                    ReaderPageFact.dimensions(
                        if (index ==
                            1
                        ) {
                            200
                        } else {
                            100
                        },
                        150,
                    ),
                ),
            )
        }
        val proof = owner.certifyPairing(ids[126]) as ReaderPairingCertification.Certified
        val anchor = owner.location
        val prepared = owner.preparePresentationFrame(ids[126], unitId = proof.segment.unit.id, activate = false)!!
        assertTrue(owner.presentationFrames.any { it.frameId == prepared && it.unit.id == proof.segment.unit.id })
        val neighbour = owner.capturePresentableViewport(
            ids[126],
            unitId = proof.segment.unit.id,
            frameId = prepared,
            activate = false,
        )!!
        assertEquals(proof.segment.unit.id, owner.frame(neighbour)!!.unit.id)
        assertNotEquals(current.frameId, neighbour.frameId)
        assertEquals(current.frameId, owner.targetFrameId)
        assertEquals(anchor, owner.location)
        paint(owner, neighbour, 126)
        assertFalse(neighbour.readingEligible)
        val event =
            ReaderDualPageSettlement(
                neighbour.identity, neighbour.layoutRevision, neighbour.locationSequence, neighbour.unitId,
                setOf(ids[126]), true, ReaderDualPageSettlementReason.USER_NAVIGATION, 1, neighbour,
            )
        assertNull(owner.settle(event, false, "neighbour"))
        assertTrue(owner.releaseViewport(neighbour))
        assertNull(
            owner.capturePresentableViewport(
                ids[126],
                unitId = proof.segment.unit.id,
                frameId = neighbour.frameId,
                activate = false,
            ),
        )
        assertEquals(current.frameId, owner.targetFrameId)
        assertEquals(anchor, owner.location)
        assertNotNull(owner.frame(current))
        assertTrue(owner.navigate(true))
        assertEquals(ids[126], owner.location!!.pageId)
        assertEquals(proof.segment.unit.id, owner.targetUnit!!.id)
        assertNull(
            owner.capturePresentableViewport(
                ids[126],
                unitId = current.unitId,
                frameId = current.frameId,
                activate = true,
            ),
        )
        assertNull(
            owner.capturePresentableViewport(
                ids[126],
                unitId = current.unitId,
                frameId = current.frameId,
                activate = false,
            ),
        )
        assertTrue(owner.reportDrawn(current, setOf(ids[125])), "The outgoing frame still owns repaint of prior pixels")
        assertNotEquals(neighbour.frameId, owner.capturePresentableViewport(ids[126])!!.frameId)
    }

    @Test
    fun `a new pairing proof cannot certify an incompatible locked default frame`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        val current = owner.capturePresentableViewport(ids[125])!!
        paint(owner, current, 125)
        ids.forEachIndexed { index, page ->
            assertTrue(
                owner.acceptFact(
                    owner.requestFact(page),
                    ReaderPageFact.dimensions(
                        if (index ==
                            1
                        ) {
                            200
                        } else {
                            100
                        },
                        150,
                    ),
                ),
            )
        }
        assertTrue(owner.certifyPairing(ids[125]) is ReaderPairingCertification.Certified)
        assertEquals(ReaderDualPagePresentationSource.DEFAULT, current.source)
        assertNull(owner.captureCertifiedViewport(ids[125]))
        assertEquals(listOf(ids[125], ids[126]), owner.frame(current)!!.unit.pageIds)
    }

    @Test
    fun `certified trailing target keeps its requested frame when default phase differs from the proof`() {
        val owner = owner(count = 8, target = 3)
        val ids = owner.identity.pageIds
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        val plan = ReaderOriginalPageSampling.plan(100, 150)!!
        ids.forEachIndexed { index, page ->
            val samples = plan.samples(
                plan.left.map { if (index == 2) -1 else 0xff334455.toInt() },
                plan.right.map { 0xff334455.toInt() },
            )
            assertTrue(
                owner.acceptOriginalAnalysis(
                    owner.requestOriginalAnalysis(page)!!,
                    ReaderPageFact.dimensions(100, 150),
                    samples,
                ),
            )
        }
        val proof = owner.certifyPairing(ids[3]) as ReaderPairingCertification.Certified
        assertEquals(listOf(ids[2], ids[3]), proof.segment.unit.pageIds)
        val ticket = owner.capturePresentableViewport(ids[3], unitId = proof.segment.unit.id)!!
        assertEquals(ReaderDualPagePresentationSource.CERTIFIED, ticket.source)
        assertNotNull(owner.frame(ticket), "Refresh must use the requested target, not the group's earlier anchor")
        assertTrue(owner.preparePage(ticket, ids[3]))
        assertTrue(owner.reportDrawn(ticket, setOf(ids[3])))
        assertEquals(proof.segment.unit.id, owner.frame(ticket)!!.unit.id)
    }

    @Test
    fun `separate native mounts hold independent tickets for the same frame in either release order`() {
        for (oldFirst in listOf(true, false)) {
            val owner = owner()
            val page = owner.identity.pageIds[125]
            val firstMount = Any()
            val secondMount = Any()
            val first = owner.capturePresentableViewport(page, leaseOwner = firstMount)!!
            val second = owner.capturePresentableViewport(page, leaseOwner = secondMount)!!
            assertEquals(first.frameId, second.frameId)
            assertNotSame(first, second)
            assertSame(first, owner.capturePresentableViewport(page, leaseOwner = firstMount))
            paint(owner, first, 125)
            assertTrue(owner.reportDrawn(second, setOf(page)))
            fun settlement(ticket: ReaderDualPageViewportTicket, sequence: Long) = ReaderDualPageSettlement(
                ticket.identity, ticket.layoutRevision, ticket.locationSequence, ticket.unitId,
                setOf(page), true, ReaderDualPageSettlementReason.USER_NAVIGATION, sequence, ticket,
            )
            assertEquals(125, owner.settle(settlement(first, 1), false, "mounts")!!.lastPageRead)
            assertNull(owner.settle(settlement(second, 2), false, "mounts"))
            val released = if (oldFirst) first else second
            val remaining = if (oldFirst) second else first
            assertTrue(owner.releaseViewport(released))
            assertNotNull(owner.frame(remaining))
            assertTrue(owner.reportDrawn(remaining, setOf(page)))
            val rebound = owner.rebindContent(owner.identity.copy(pageListRevision = 2))
            val fresh = rebound.capturePresentableViewport(page, leaseOwner = Any())!!
            assertFalse(rebound.reportDrawn(remaining, setOf(page)))
            assertTrue(rebound.releaseViewport(remaining))
            assertNotNull(rebound.frame(fresh))
            rebound.close()
            assertNull(rebound.frame(fresh))
            assertFalse(rebound.releaseViewport(fresh))
        }
    }

    @Test
    fun `replacing only an unlocked partner preserves the mounted frame and binds a fresh content ticket`() {
        for (certified in listOf(false, true)) {
            val owner = owner()
            val ids = owner.identity.pageIds
            if (certified) {
                ids.forEach {
                    assertTrue(owner.acceptFact(owner.requestFact(it), ReaderPageFact.dimensions(100, 150)))
                }
            }
            val old = owner.capturePresentableViewport(ids[125], leaseOwner = Any())!!
            paint(owner, old, 125)
            assertFalse(
                owner.reloadContent(ids[126]),
                "Retrying an unlocked partner does not replace visible geometry",
            )
            assertNotNull(owner.frame(old))
            val identity = owner.identity.copy(
                pageListRevision = 2,
                content = owner.identity.content.map {
                    if (it.pageId == ids[126]) it.copy(contentVersion = "retry") else it
                },
            )
            assertTrue(owner.canRebindContent(identity))
            val rebound = owner.rebindContent(identity)
            assertNull(rebound.pageFact(ids[126]))
            assertTrue(rebound.certifyPairing() is ReaderPairingCertification.NeedFacts)
            assertEquals(old.frameId, rebound.preparePresentationFrame())
            val fresh = rebound.capturePresentableViewport(ids[125], frameId = old.frameId, leaseOwner = Any())!!
            assertEquals(
                ReaderDualPagePresentationSource.DEFAULT,
                fresh.source,
                "Old proof cannot certify replacement bytes",
            )
            assertEquals(old.frameId, fresh.frameId)
            assertFalse(rebound.reportDrawn(old, setOf(ids[125])))
            assertFalse(rebound.acceptDecodedFact(old, ids[126], 100, 150))
            assertTrue(rebound.releaseViewport(old))
            assertNotNull(rebound.frame(fresh))
            paint(rebound, fresh, 126)
            assertEquals(setOf(ids[125], ids[126]), rebound.frame(fresh)!!.drawnPageIds)
            assertTrue(
                rebound.reloadContent(ids[125]),
                "An explicit reload of a locked page still revokes that context",
            )
            assertNull(rebound.frame(fresh))
        }
    }

    @Test
    fun `target identity is prepared before publication without drawing and unmounted candidates stay bounded`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        owner.preparePresentationFrame()
        val firstFrame = owner.targetFrameId!!
        assertNull(owner.preparePresentationFrame(ids[129], activate = true))
        assertNull(owner.capturePresentableViewport(ids[129], activate = true))
        assertEquals(firstFrame, owner.targetFrameId, "Offscreen preparation cannot replace the logical target")
        assertTrue(owner.displayedHistory.isEmpty())
        assertTrue(owner.presentationFrames.any { it.frameId == firstFrame })
        val first = owner.capturePresentableViewport(ids[125], frameId = firstFrame)!!
        assertEquals(firstFrame, first.frameId)
        assertFalse(owner.preparePage(first, ids[125]))
        assertTrue(owner.frame(first)!!.drawnPageIds.isEmpty())
        assertTrue(owner.releaseViewport(first))
        owner.preparePresentationFrame()
        val remounted = owner.targetFrameId!!
        assertNotEquals(firstFrame, remounted)
        assertNull(owner.capturePresentableViewport(ids[125], frameId = firstFrame))
        assertNotNull(owner.capturePresentableViewport(ids[125], frameId = remounted))
        repeat(20) { offset ->
            assertTrue(owner.goToPage(ids[100 + offset]))
            owner.preparePresentationFrame()
            assertTrue(owner.presentationFrames.count { it.frameId != null } <= 2)
            assertTrue(owner.displayedHistory.isEmpty())
        }
        owner.close()
        assertNull(owner.targetFrameId)
    }

    @Test
    fun `memo bypass retains current certified proposal and prepared neighbours without reflow`() {
        for ((pageLimit, readLimit) in listOf(1 to 4096, 4096 to 1)) {
            val owner = owner(count = 8, target = 5, memoPageOrderLimit = pageLimit, memoReadKeyLimit = readLimit)
            val ids = owner.identity.pageIds
            owner.configure(ReaderDirection.RTL, automaticMatching = true)
            val plan = ReaderOriginalPageSampling.plan(100, 150)!!
            ids.forEachIndexed { index, id ->
                val samples = plan.samples(
                    plan.left.map { if (index == 2) -1 else 0xff334455.toInt() },
                    plan.right.map { 0xff334455.toInt() },
                )
                assertTrue(
                    owner.acceptOriginalAnalysis(
                        owner.requestOriginalAnalysis(id)!!,
                        ReaderPageFact.dimensions(100, 150),
                        samples,
                    ),
                )
            }
            assertEquals(listOf(ids[5], ids[6]), owner.targetUnit!!.pageIds)
            assertTrue(owner.goToPage(ids[5]))
            val proof = owner.certifyPairing() as ReaderPairingCertification.Certified
            assertEquals(listOf(ids[4], ids[5]), proof.segment.unit.pageIds)
            assertEquals(
                proof.segment.unit.id,
                owner.targetUnit!!.id,
                "Memo capacity cannot change a certified proposal",
            )
            val target = owner.preparePresentationFrame()!!
            val neighbours = listOf(ids[3], ids[7]).associateWith { page ->
                val neighbourProof = owner.certifyPairing(page) as ReaderPairingCertification.Certified
                owner.preparePresentationFrame(page, neighbourProof.segment.unit.id, activate = false)!!
            }
            repeat(4) {
                neighbours.forEach { (page, frame) ->
                    owner.certifyPairing(page)
                    assertEquals(frame, owner.preparePresentationFrame(page, activate = false))
                    assertTrue(owner.presentationFrames.any { it.frameId == frame })
                }
                assertEquals(target, owner.targetFrameId)
                assertEquals(proof.segment.unit.id, owner.targetUnit!!.id)
            }
            val ticket = owner.capturePresentableViewport(ids[5], frameId = target)!!
            assertEquals(ReaderDualPagePresentationSource.CERTIFIED, ticket.source)
            assertEquals(proof.segment.unit.id, owner.frame(ticket)!!.unit.id)
            assertTrue(owner.goToPage(ids[3]))
            assertEquals(listOf(ids[2], ids[3]), owner.targetUnit!!.pageIds)
            assertNotNull(owner.capturePresentableViewport(ids[3]))
            owner.close()
        }
    }

    @Test
    fun `nearby projection uses the exact certified candidate without activating or drawing it`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val owner = owner(direction = direction, count = 16, target = 6)
            val ids = owner.identity.pageIds
            owner.configure(direction, automaticMatching = true)
            val plan = ReaderOriginalPageSampling.plan(100, 150)!!
            ids.forEachIndexed { index, id ->
                assertTrue(
                    owner.acceptOriginalAnalysis(
                        owner.requestOriginalAnalysis(id)!!,
                        ReaderPageFact.dimensions(100, 150),
                        plan.samples(
                            plan.left.map { if (index == 2) -1 else 0xff334455.toInt() },
                            plan.right.map { 0xff334455.toInt() },
                        ),
                    ),
                )
            }
            assertTrue(owner.goToPage(ids[6]))
            owner.preparePresentationFrame()
            val anchor = owner.location
            val frame = owner.targetFrameId
            for (index in listOf(8, 9, 5, 4)) {
                val proof = owner.certifyPairing(ids[index]) as ReaderPairingCertification.Certified
                assertTrue(
                    owner.presentationFrames.any { it.unit.id == proof.segment.unit.id },
                    "Nearby native projection must match the actual navigation candidate at $index",
                )
            }
            assertEquals(anchor, owner.location)
            assertEquals(frame, owner.targetFrameId)
            assertTrue(owner.displayedHistory.isEmpty())
            owner.close()
        }
    }

    @Test
    fun `one resident neighbour proof survives an unproved partner and a later unrelated proposal`() {
        val owner = owner(count = 16, target = 6)
        val ids = owner.identity.pageIds
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        val plan = ReaderOriginalPageSampling.plan(100, 150)!!
        ids.forEachIndexed { index, id ->
            assertTrue(
                owner.acceptOriginalAnalysis(
                    owner.requestOriginalAnalysis(id)!!,
                    ReaderPageFact.dimensions(100, 150),
                    plan.samples(
                        plan.left.map { if (index == 2) -1 else 0xff334455.toInt() },
                        plan.right.map { 0xff334455.toInt() },
                    ),
                ),
            )
        }
        assertTrue(owner.goToPage(ids[6]))
        owner.preparePresentationFrame()
        val proof = owner.certifyPairing(ids[8]) as ReaderPairingCertification.Certified
        owner.certifyPairing(ids[12])
        owner.certifyPairing(ids[4])
        assertTrue(
            owner.presentationFrames.any { it.unit.id == proof.segment.unit.id },
            "A DEFAULT partner must not overwrite the surviving certified neighbour claim",
        )
        assertEquals(ids[6], owner.location!!.pageId)
        assertTrue(owner.displayedHistory.isEmpty())
        owner.close()
    }

    @Test
    fun `prepared outgoing frame renews its neighbour capability without duplicating physical identity`() {
        val owner = owner(count = 16, target = 5)
        val ids = owner.identity.pageIds
        ids.forEach { owner.acceptFact(owner.requestFact(it), ReaderPageFact.dimensions(100, 150)) }
        val outgoing = owner.capturePresentableViewport(ids[5])!!
        paint(owner, outgoing, 5)
        paint(owner, outgoing, 6)
        assertTrue(owner.navigate(true))
        assertEquals(ids[7], owner.location!!.pageId)
        val current = owner.capturePresentableViewport(ids[7])!!
        paint(owner, current, 7)
        paint(owner, current, 8)
        val renewedFrame = owner.preparePresentationFrame(ids[5], activate = false)
        assertEquals(
            outgoing.frameId,
            renewedFrame,
            "A live unchanged outgoing bitmap must become the prepared neighbour in the same physical frame",
        )
        val renewed = owner.capturePresentableViewport(ids[5], frameId = renewedFrame, activate = false)!!
        assertNotSame(outgoing, renewed)
        assertFalse(renewed.readingEligible)
        assertFalse(owner.preparePage(outgoing, ids[6]))
        assertTrue(owner.releaseViewport(outgoing))
        assertNotNull(owner.frame(renewed))
        owner.close()
    }

    private fun paint(owner: SharedDualPageSession, ticket: ReaderDualPageViewportTicket, index: Int) {
        val page = owner.identity.pageIds[index]
        assertTrue(owner.acceptDecodedFact(ticket, page, 100, 150))
        assertTrue(owner.preparePage(ticket, page))
        assertTrue(owner.reportDrawn(ticket, setOf(page)))
    }

    private fun owner(
        direction: ReaderDirection = ReaderDirection.RTL,
        mangaId: Long? = null,
        count: Int = 200,
        target: Int = 125,
        memoPageOrderLimit: Int = 4096,
        memoReadKeyLimit: Int = 4096,
    ): SharedDualPageSession {
        val identity =
            ReaderDualPageIdentity(
                1,
                1,
                List(count) {
                    ReaderDualPageContentIdentity(ReaderPageId(ReaderChapterId(42), it), "page-$it")
                },
            )
        return SharedDualPageSession(
            identity,
            direction,
            identity.pageIds[target],
            mangaId,
            memoPageOrderLimit,
            memoReadKeyLimit,
        )
    }
}
