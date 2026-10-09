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

/** v3 events run on the real shared owner; no copied pairing or progress reducer. */
class SharedDualPageStablePresentationContractTest {
    @Test
    fun `returning to an entered group honors an offscreen real spread without shifting prior C`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        fact(owner, 1)
        val old = owner.captureViewport()!!
        owner.reportDrawn(old, setOf(ids[1]))
        val cSlot = owner.frame(old)!!.unit.slots.first()
        owner.goToPage(ids[3])
        owner.releaseViewport(old)
        fact(owner, 2, wide = true)
        owner.goToPage(ids[1])
        val returned = owner.captureViewport(preparingPageIds = emptySet())!!
        assertEquals(listOf(cSlot), owner.frame(returned)!!.unit.slots)
        assertFalse(owner.preparePage(returned, ids[2]))
        assertTrue(owner.navigate(true))
        assertEquals(ids[2], owner.location!!.pageId)
        assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
    }

    @Test
    fun `group turns skip current unit regardless of drawable loading or failed partners`() {
        for (painted in listOf(false, true)) {
            for (failed in listOf(false, true)) {
                val owner = owner()
                val ids = owner.identity.pageIds
                if (painted) fact(owner, 1)
                val current = owner.captureViewport(preparingPageIds = if (painted) setOf(ids[1]) else emptySet())!!
                if (painted) assertTrue(owner.reportDrawn(current, setOf(ids[1])))
                if (failed) {
                    assertTrue(
                        owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact(ReaderPageFactState.FAILED)),
                    )
                }
                assertEquals(listOf(ids[1], ids[2]), owner.targetUnit!!.pageIds)
                assertTrue(owner.navigate(true))
                assertEquals(ids[3], owner.location!!.pageId, "Next screen must skip the complete C/D unit")
                assertEquals(listOf(ids[3], ids[4]), owner.targetUnit!!.pageIds)
                assertTrue(owner.navigate(false))
                assertEquals(ids[2], owner.location!!.pageId)
                assertEquals(listOf(ids[1], ids[2]), owner.targetUnit!!.pageIds)
                assertTrue(owner.goToPage(ids[1]))
                assertEquals(ids[1], owner.location!!.pageId, "Explicit source jump remains exact within its group")
            }
        }
    }

    @Test
    fun `leaving and releasing a partial unit preserves its undrawn partner on return`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        fact(owner, 1)
        val original = owner.captureViewport()!!
        assertTrue(owner.reportDrawn(original, setOf(ids[1])))
        val slots = owner.frame(original)!!.unit.slots
        assertTrue(owner.goToPage(ids[3]))
        assertTrue(owner.releaseViewport(original))
        assertTrue(owner.navigate(false))
        assertEquals(listOf(ids[1], ids[2]), owner.targetUnit!!.pageIds)
        val returned = owner.captureViewport(preparingPageIds = emptySet())!!
        val frame = owner.frame(returned)!!
        assertEquals(slots, frame.unit.slots)
        assertEquals(setOf(ids[1]), frame.drawnPageIds)
        assertFalse(owner.preparePage(returned, ids[2]))
        assertFalse(owner.reportDrawn(returned, setOf(ids[2])))
        val projection = owner.units.flatMap { it.pageIds }
        assertEquals(ids.toSet(), projection.toSet())
        assertEquals(ids.size, projection.size)
    }

    @Test
    fun `partner error and retry retain entered grouping while a real wide fact may change it`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val owner = owner()
            owner.configure(direction, automaticMatching = true)
            val ids = owner.identity.pageIds
            val pending = owner.captureViewport(preparingPageIds = emptySet())!!
            val leading = if (direction == ReaderDirection.RTL) ReaderPageSideHint.RIGHT else ReaderPageSideHint.LEFT
            assertTrue(owner.acceptDecodedFact(pending, ids[1], 100, 150, leading))
            val current = owner.captureViewport()!!
            assertTrue(owner.reportDrawn(current, setOf(ids[1])))
            val slots = owner.frame(current)!!.unit.slots
            assertTrue(owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact(ReaderPageFactState.FAILED)))
            assertEquals(slots, owner.targetUnit!!.slots, "Failure cannot remove the assigned D slot")
            assertTrue(owner.retryFacts())
            assertEquals(slots, owner.targetUnit!!.slots)
            assertEquals(setOf(ids[1]), owner.frame(current)!!.drawnPageIds)
            fact(owner, 2, wide = true)
            assertEquals(listOf(ids[1]), owner.targetUnit!!.pageIds, "Real structure retains the wide-page guard")
            assertTrue(owner.navigate(true))
            assertEquals(ids[2], owner.location!!.pageId)
            assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
        }
    }

    @Test
    fun `decoded margin evidence is bounded and ambiguous margins stay unknown`() {
        val width = 128
        val height = 256
        fun image(leftWhite: Boolean, rightWhite: Boolean): IntArray = IntArray(width * height) { index ->
            val x = index % width
            if ((leftWhite && x < 6) || (rightWhite && x >= width - 6)) -1 else 0xff303030.toInt()
        }
        val left = image(true, false)
        var reads = 0
        assertEquals(
            ReaderPageSideHint.RIGHT,
            ReaderPageSideHints.classify(width, height) { x, y ->
                reads++
                left[y * width + x]
            },
        )
        assertEquals(288, reads)
        val right = image(false, true)
        assertEquals(
            ReaderPageSideHint.LEFT,
            ReaderPageSideHints.classify(width, height) { x, y ->
                right[y * width + x]
            },
        )
        for (both in listOf(image(true, true), image(false, false))) {
            assertEquals(
                ReaderPageSideHint.UNKNOWN,
                ReaderPageSideHints.classify(width, height) { x, y ->
                    both[
                        y *
                            width +
                            x,
                    ]
                },
            )
        }
        assertEquals(
            ReaderPageSideHint.UNKNOWN,
            ReaderPageSideHints.classify(width, height) { x, _ ->
                if (x == 1) -1 else 0xff303030.toInt()
            },
        )
    }

    @Test
    fun `confident decoded side selects either direction without waiting for an unknown earlier partner`() {
        val cases = listOf(
            Triple(ReaderDirection.RTL, ReaderPageSideHint.LEFT, listOf(2, 3)),
            Triple(ReaderDirection.RTL, ReaderPageSideHint.RIGHT, listOf(3, 4)),
            Triple(ReaderDirection.LTR, ReaderPageSideHint.LEFT, listOf(3, 4)),
            Triple(ReaderDirection.LTR, ReaderPageSideHint.RIGHT, listOf(2, 3)),
        )
        for ((direction, side, expected) in cases) {
            val owner = owner(target = 3)
            owner.configure(direction, automaticMatching = true)
            val ids = owner.identity.pageIds
            val placeholder = owner.captureViewport(preparingPageIds = emptySet())!!
            assertTrue(owner.acceptDecodedFact(placeholder, ids[3], 100, 150, side))
            assertEquals(expected, owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
            val actual = owner.captureViewport(preparingPageIds = emptySet())!!
            assertTrue(owner.preparePage(actual, ids[3]))
            assertTrue(owner.reportDrawn(actual, setOf(ids[3])))
            val slot = owner.frame(actual)!!.unit.slots.single { it.pageId == ids[3] }
            assertEquals(
                if (side ==
                    ReaderPageSideHint.LEFT
                ) {
                    ReaderPortraitSingleSlot.LEFT
                } else {
                    ReaderPortraitSingleSlot.RIGHT
                },
                slot.position,
            )
            val partner = expected.single { it != 3 }
            assertTrue(owner.acceptDecodedFact(actual, ids[partner], 100, 150, side))
            assertEquals(listOf(ids[3]), owner.frame(actual)!!.unit.pageIds)
            assertEquals(slot, owner.frame(actual)!!.unit.slots.single())
            assertFalse(owner.preparePage(actual, ids[partner]))
            fact(owner, 3)
            assertEquals(side, owner.pageFact(ids[3])!!.sideHint, "A late ordinary header must retain the decoded hint")
        }
    }

    @Test
    fun `decoded side respects manual first page automatic switch and physically locked history`() {
        val earlyPartner = owner(target = 3)
        earlyPartner.configure(ReaderDirection.RTL, automaticMatching = true)
        fact(earlyPartner, 3)
        val earlyIds = earlyPartner.identity.pageIds
        val pending = earlyPartner.captureViewport(preparingPageIds = emptySet())!!
        assertTrue(earlyPartner.acceptDecodedFact(pending, earlyIds[4], 100, 150, ReaderPageSideHint.RIGHT))
        assertEquals(listOf(earlyIds[3]), earlyPartner.targetUnit!!.pageIds)
        assertFalse(
            earlyPartner.preparePage(pending, earlyIds[4]),
            "A leading page cannot first paint in the trailing slot",
        )
        val owner = owner(target = 3)
        val ids = owner.identity.pageIds
        val off = owner.captureViewport(preparingPageIds = emptySet())!!
        assertTrue(owner.acceptDecodedFact(off, ids[3], 100, 150, ReaderPageSideHint.LEFT))
        assertEquals(listOf(3, 4), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        val firstPages = owner.identity.pageIds
        assertTrue(owner.goToPage(firstPages[1]))
        val opening = owner.captureViewport(preparingPageIds = emptySet())!!
        assertTrue(owner.acceptDecodedFact(opening, firstPages[1], 100, 150, ReaderPageSideHint.LEFT))
        assertEquals(listOf(firstPages[1]), owner.targetUnit!!.pageIds)
        val manual = SharedDualPageRules.decide(
            firstPages,
            mapOf(
                ids[3] to ReaderPageFact.dimensions(100, 150)
                    .copy(sideHint = ReaderPageSideHint.LEFT),
            ),
            ReaderDirection.RTL,
            3,
            forcedSinglePages = setOf(ids[3]),
            usePageSideHints = true,
        )
        assertEquals(ReaderPortraitSingleSlot.LEFT, manual.unit.slots.single().position)
        val lockedOwner = owner(target = 3)
        lockedOwner.configure(ReaderDirection.RTL, automaticMatching = true)
        fact(lockedOwner, 3)
        val locked = lockedOwner.captureViewport(preparingPageIds = setOf(lockedOwner.identity.pageIds[3]))!!
        val before = lockedOwner.frame(locked)!!
        assertTrue(
            lockedOwner.acceptDecodedFact(locked, lockedOwner.identity.pageIds[3], 100, 150, ReaderPageSideHint.LEFT),
        )
        assertEquals(before.unit.slots.first(), lockedOwner.frame(locked)!!.unit.slots.first())
        assertEquals(before.lockedPageIds, lockedOwner.frame(locked)!!.lockedPageIds)
    }

    @Test
    fun `unpainted neighbour proposals cannot claim a new cold jump target`() {
        for (target in listOf(14, 16)) {
            val owner = owner(count = 17, target = 0)
            val ids = owner.identity.pageIds
            fact(owner, 0)
            val home = owner.captureViewport(preparingPageIds = setOf(ids[0]))!!
            assertTrue(owner.reportDrawn(home, setOf(ids[0])))
            assertTrue(owner.goToPage(ids[target]))
            val neighbour = owner.captureViewport(ids[target - 1], preparingPageIds = emptySet())!!
            assertTrue(owner.frame(neighbour)!!.lockedPageIds.isEmpty())
            assertTrue(owner.frame(neighbour)!!.drawnPageIds.isEmpty())
            assertEquals(
                if (target == 14) listOf(14, 15) else listOf(16),
                owner.targetUnit!!.pageIds.map { it.sourcePageIndex },
            )
            assertEquals(setOf(ids[0]), owner.frame(home)!!.drawnPageIds)
        }
    }

    @Test
    fun `rapid navigation leaves an unlocked earlier proposal before painting the later target`() {
        val owner = owner(target = 2)
        val ids = owner.identity.pageIds
        val earlier = owner.captureViewport(preparingPageIds = emptySet())!!
        assertEquals(listOf(2, 3), owner.frame(earlier)!!.unit.pageIds.map { it.sourcePageIndex })
        assertTrue(owner.navigate(true))
        assertEquals(ids[4], owner.location!!.pageId)
        fact(owner, 4)
        val target = owner.captureViewport(preparingPageIds = emptySet())!!
        assertEquals(
            ReaderPortraitSingleSlot.RIGHT,
            owner.frame(target)!!.unit.slots.single {
                it.pageId == ids[4]
            }.position,
        )
        assertTrue(owner.preparePage(target, ids[4]))
        assertTrue(owner.reportDrawn(target, setOf(ids[4])))
        assertFalse(owner.preparePage(earlier, ids[2]))
        assertFalse(owner.reportDrawn(earlier, setOf(ids[2])))
        assertEquals(setOf(ids[4]), owner.frame(target)!!.drawnPageIds)
        assertTrue(owner.displayedHistory.none { ids[2] in it.drawnPageIds })
    }

    @Test
    fun `rapid navigation preserves an earlier physically locked proposal without requiring its draw`() {
        val owner = owner(target = 2)
        val ids = owner.identity.pageIds
        fact(owner, 2)
        fact(owner, 3)
        val earlier = owner.captureViewport(preparingPageIds = setOf(ids[2]))!!
        val frame = owner.frame(earlier)!!
        assertTrue(owner.navigate(true))
        fact(owner, 4)
        val target = owner.captureViewport(preparingPageIds = emptySet())!!
        assertEquals(frame.id, owner.frame(earlier)!!.id)
        assertEquals(frame.unit, owner.frame(earlier)!!.unit)
        assertEquals(ids[4], owner.location!!.pageId)
        assertTrue(owner.preparePage(target, ids[4]))
        assertTrue(owner.reportDrawn(target, setOf(ids[4])))
        assertEquals(setOf(ids[4]), owner.frame(target)!!.drawnPageIds)
    }

    @Test
    fun `reverse group turn leaves both earlier sources while retaining the committed later slot`() {
        val owner = owner(target = 3)
        fact(owner, 1)
        fact(owner, 2)
        assertTrue(owner.navigate(false))
        assertEquals(listOf(1, 2), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        val ticket = owner.captureViewport(preparingPageIds = setOf(owner.identity.pageIds[2]))!!
        assertTrue(owner.reportDrawn(ticket, setOf(owner.identity.pageIds[2])))
        val slots = owner.targetUnit!!.slots
        assertTrue(owner.navigate(false))
        assertEquals(
            owner.identity.pageIds[0],
            owner.location!!.pageId,
            "The reverse turn skips the whole earlier group",
        )
        assertEquals(listOf(0), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertEquals(slots, owner.frame(ticket)!!.unit.slots)
    }

    @Test
    fun `reverse from a drawn earlier source does not navigate forward to its undrawn later partner`() {
        val owner = owner()
        fact(owner, 1)
        fact(owner, 2)
        val ticket = owner.captureViewport(preparingPageIds = setOf(owner.identity.pageIds[1]))!!
        assertTrue(owner.reportDrawn(ticket, setOf(owner.identity.pageIds[1])))
        assertTrue(owner.navigate(false))
        assertEquals(owner.identity.pageIds[0], owner.location!!.pageId)
    }
    private fun owner(count: Int = 8, target: Int = 1, mangaId: Long? = null): SharedDualPageSession {
        val identity = ReaderDualPageIdentity(
            1,
            1,
            List(count) {
                ReaderDualPageContentIdentity(ReaderPageId(ReaderChapterId(42), it), "fixture-$it")
            },
        )
        return SharedDualPageSession(identity, ReaderDirection.RTL, identity.pageIds[target], mangaId)
    }

    @Test
    fun `manual singles use reliable side evidence without dropping their boundary`() {
        val ids = owner().identity.pageIds
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            for (automatic in listOf(false, true)) {
                for (hint in ReaderPageSideHint.entries) {
                    val decision = SharedDualPageRules.decide(
                        ids,
                        mapOf(ids[1] to ReaderPageFact.dimensions(100, 150).copy(sideHint = hint)),
                        direction,
                        1,
                        forcedSinglePages = setOf(ids[1]),
                        usePageSideHints = automatic,
                    )
                    assertEquals(listOf(ids[1]), decision.unit.pageIds)
                    assertEquals(ReaderDualPageSingleReason.MANUAL_BOUNDARY, decision.unit.singleReason)
                    val expected = if (automatic && hint != ReaderPageSideHint.UNKNOWN) {
                        if (hint == ReaderPageSideHint.LEFT) {
                            ReaderPortraitSingleSlot.LEFT
                        } else {
                            ReaderPortraitSingleSlot.RIGHT
                        }
                    } else {
                        if (direction == ReaderDirection.RTL) {
                            ReaderPortraitSingleSlot.RIGHT
                        } else {
                            ReaderPortraitSingleSlot.LEFT
                        }
                    }
                    assertEquals(expected, decision.unit.slots.single().position)
                }
            }
        }
    }

    @Test
    fun `first pair requires compatible nearby portrait evidence in either direction`() {
        val ids = owner().identity.pageIds
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val trailing = if (direction == ReaderDirection.RTL) ReaderPageSideHint.LEFT else ReaderPageSideHint.RIGHT
            val leading = if (direction == ReaderDirection.RTL) ReaderPageSideHint.RIGHT else ReaderPageSideHint.LEFT
            val facts = mapOf(
                ids[0] to ReaderPageFact.dimensions(100, 150),
                ids[1] to ReaderPageFact.dimensions(100, 150).copy(sideHint = trailing),
            )
            for (target in 0..1) {
                for (forward in listOf(false, true)) {
                    fun decide(
                        observed: Map<ReaderPageId, ReaderPageFact> = facts,
                        forced: Set<ReaderPageId> = emptySet(),
                        occupied: Set<ReaderPageId> = emptySet(),
                        automatic: Boolean = true,
                    ) = SharedDualPageRules.decide(
                        ids,
                        observed,
                        direction,
                        target,
                        forward,
                        forced,
                        occupied,
                        usePageSideHints = automatic,
                    ).unit
                    assertEquals(listOf(ids[0], ids[1]), decide().pageIds)
                    val leadingFacts = facts + (ids[0] to facts.getValue(ids[0]).copy(sideHint = leading))
                    assertEquals(listOf(ids[0], ids[1]), decide(leadingFacts).pageIds)
                    val conflicting = facts + (ids[0] to facts.getValue(ids[0]).copy(sideHint = trailing))
                    assertFalse(decide(conflicting).pageIds.containsAll(listOf(ids[0], ids[1])))
                    if (target == 0 || forward) {
                        assertFalse(decide(automatic = false).pageIds.containsAll(listOf(ids[0], ids[1])))
                    }
                    for (page in listOf(ids[0], ids[1])) {
                        assertFalse(decide(forced = setOf(page)).pageIds.containsAll(listOf(ids[0], ids[1])))
                        assertFalse(decide(occupied = setOf(page)).pageIds.containsAll(listOf(ids[0], ids[1])))
                        val rejectedStates = listOf(
                            ReaderPageFactState.UNKNOWN,
                            ReaderPageFactState.SPREAD,
                            ReaderPageFactState.FAILED,
                        )
                        for (state in rejectedStates) {
                            val rejected = decide(facts + (page to ReaderPageFact(state)))
                            assertFalse(rejected.pageIds.containsAll(listOf(ids[0], ids[1])))
                        }
                    }
                    val unknownFact = facts.getValue(ids[1]).copy(sideHint = ReaderPageSideHint.UNKNOWN)
                    val unknownHint = facts + (ids[1] to unknownFact)
                    assertFalse(decide(unknownHint).pageIds.containsAll(listOf(ids[0], ids[1])))
                }
            }
        }
    }

    @Test
    fun `explicit opening adjustment clears first history and late save protects current pixels`() {
        for (late in listOf(false, true)) {
            val owner = owner(target = 0, mangaId = 88)
            owner.configure(ReaderDirection.RTL, automaticMatching = true)
            val ids = owner.identity.pageIds
            (0..7).forEach { fact(owner, it) }
            owner.acceptPairingRestore(
                owner.beginPairingRestore()!!,
                ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(1)), 1),
            )
            val first = owner.captureViewport()!!
            assertTrue(owner.reportDrawn(first, setOf(ids[0])))
            assertEquals(ReaderPortraitSingleSlot.LEFT, owner.frame(first)!!.unit.slots.single().position)
            owner.goToPage(ids[1])
            val proposal = owner.captureViewport(preparingPageIds = emptySet())!!
            assertTrue(owner.acceptDecodedFact(proposal, ids[1], 100, 150, ReaderPageSideHint.LEFT))
            val manual = owner.captureViewport()!!
            owner.reportDrawn(manual, setOf(ids[1]))
            val save = owner.adjustPairing()!!
            assertEquals(emptySet<Int>(), save.adjustment.forcedSinglePages)
            val protected = if (late) {
                owner.goToPage(ids[4])
                owner.captureViewport()!!.also { owner.reportDrawn(it, owner.targetUnit!!.pageIds.toSet()) }
            } else {
                null
            }
            val original = protected?.let(owner::frame)
            val location = owner.location
            assertTrue(owner.acceptPairingSave(save, ChapterPairingSnapshot(null, 2)))
            assertEquals(location, owner.location)
            if (late) {
                assertEquals(original, owner.frame(protected!!))
                assertNotNull(owner.frame(first), "A late save must not revoke still-visible first-page pixels")
                owner.releaseViewport(first)
                owner.releaseViewport(manual)
                owner.goToPage(ids[1])
            } else {
                assertNull(owner.frame(first), "Explicit adjustment must dispose the conflicting old first-page scope")
            }
            val pair = owner.targetUnit!!
            assertEquals(listOf(ids[0], ids[1]), pair.pageIds)
            assertEquals(
                listOf(ReaderPortraitSingleSlot.RIGHT, ReaderPortraitSingleSlot.LEFT),
                pair.slots.map { it.position },
            )
            assertEquals(ids[1], owner.location!!.pageId)
            val current = owner.captureViewport()!!
            owner.reportDrawn(current, setOf(ids[0], ids[1]))
            val split = owner.adjustPairing()!!
            assertEquals(setOf(1), split.adjustment.forcedSinglePages)
            assertTrue(owner.acceptPairingSave(split, ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(1)), 3)))
            assertEquals(ids[1], owner.location!!.pageId)
            assertEquals(listOf(ids[1]), owner.targetUnit!!.pageIds)
            assertEquals(ReaderPortraitSingleSlot.LEFT, owner.targetUnit!!.slots.single().position)
            val projection = owner.units.flatMap { it.pageIds }
            assertEquals(ids.toSet(), projection.toSet())
            assertEquals(ids.size, projection.size)
        }
    }

    @Test
    fun `late side evidence never relocates a locked first page or manual single`() {
        for (target in 0..1) {
            val owner = owner(target = target, mangaId = 88)
            owner.configure(ReaderDirection.RTL, automaticMatching = true)
            val ids = owner.identity.pageIds
            fact(owner, 0)
            fact(owner, 1)
            if (target == 1) {
                owner.acceptPairingRestore(
                    owner.beginPairingRestore()!!,
                    ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(1)), 1),
                )
            }
            val ticket = owner.captureViewport()!!
            owner.reportDrawn(ticket, setOf(ids[target]))
            val before = owner.frame(ticket)!!
            val lateSide = ReaderPageFact.dimensions(100, 150).copy(sideHint = ReaderPageSideHint.LEFT)
            assertTrue(owner.acceptFact(owner.requestFact(ids[1]), lateSide))
            assertEquals(before.unit.slots, owner.frame(ticket)!!.unit.slots)
            assertEquals(before.lockedPageIds, owner.frame(ticket)!!.lockedPageIds)
        }
    }

    private fun fact(owner: SharedDualPageSession, index: Int, wide: Boolean = false) {
        val id = owner.identity.pageIds[index]
        assertTrue(owner.acceptFact(owner.requestFact(id), ReaderPageFact.dimensions(if (wide) 200 else 100, 150)))
    }

    private fun settled(
        owner: SharedDualPageSession,
        ticket: ReaderDualPageViewportTicket,
        pages: Set<Int>,
        sequence: Long,
    ) = ReaderDualPageSettlement(
        ticket.identity, ticket.layoutRevision, ticket.locationSequence, ticket.unitId,
        pages.mapTo(mutableSetOf()) { owner.identity.pageIds[it] }, true,
        ReaderDualPageSettlementReason.USER_NAVIGATION, sequence, ticket,
    )

    @Test
    fun `three hundred page cold target reads without unknown prefix or matcher`() {
        for (target in listOf(0, 199)) {
            val owner = owner(300, target)
            fact(owner, target)
            val targetId = owner.identity.pageIds[target]
            assertEquals(targetId, owner.location!!.pageId)
            assertTrue(owner.factDependencies().all { it.sourcePageIndex in target..target + 4 })
            assertEquals(
                if (target == 0) ReaderPortraitSingleSlot.LEFT else ReaderPortraitSingleSlot.RIGHT,
                owner.targetUnit!!.slots.first { it.pageId == targetId }.position,
            )
            assertNotNull(owner.captureViewport())
        }
    }

    @Test
    fun `only target ready locks right slot before late wide partner arrives`() {
        val owner = owner()
        fact(owner, 1)
        val ticket = owner.captureViewport()!!
        val slot = owner.targetUnit!!.slots.first()
        assertEquals(ReaderPortraitSingleSlot.RIGHT, slot.position)
        fact(owner, 2, wide = true)
        assertEquals(slot, owner.targetUnit!!.slots.first())
        assertSame(ticket, owner.captureViewport())
        assertNotNull(owner.settle(settled(owner, ticket, setOf(1), 1), false, "stable"))
        assertTrue(owner.navigate(true))
        assertEquals(owner.identity.pageIds[2], owner.location!!.pageId)
        assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
    }

    @Test
    fun `one location can append actually drawn partner exactly once then loses qualification offscreen`() {
        val owner = owner()
        fact(owner, 1)
        fact(owner, 2)
        val ticket = owner.captureViewport()!!
        assertNotNull(owner.settle(settled(owner, ticket, setOf(1), 1), false, "partial"))
        assertNotNull(owner.settle(settled(owner, ticket, setOf(1, 2), 2), false, "partial"))
        assertNull(owner.settle(settled(owner, ticket, setOf(1, 2), 3), false, "partial"))
        owner.goToPage(owner.identity.pageIds[5])
        assertNull(owner.settle(settled(owner, ticket, setOf(2), 4), false, "partial"))
    }

    @Test
    fun `first drawn page turns past its unseen partner and retains the entered group`() {
        val owner = owner()
        fact(owner, 1)
        fact(owner, 2)
        val ticket = owner.captureViewport()!!
        owner.settle(settled(owner, ticket, setOf(1), 1), false, "partial")
        assertTrue(owner.navigate(true))
        assertEquals(owner.identity.pageIds[3], owner.location!!.pageId)
        assertEquals(
            listOf(owner.identity.pageIds[1], owner.identity.pageIds[2]),
            owner.presentationUnits.single {
                owner.identity.pageIds[1] in
                    it.pageIds
            }.pageIds,
        )
        assertNull(owner.settle(settled(owner, ticket, setOf(2), 2), false, "partial"))
    }

    @Test
    fun `later page drawn first keeps its left commitment while navigation turns past the group`() {
        val owner = owner()
        fact(owner, 1)
        fact(owner, 2)
        val ticket = owner.captureViewport()!!
        owner.settle(settled(owner, ticket, setOf(2), 1), false, "later-first")
        assertTrue(owner.navigate(true))
        assertEquals(owner.identity.pageIds[3], owner.location!!.pageId)
        assertEquals(listOf(3, 4), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        val history = owner.frame(ticket)!!
        assertEquals(listOf(1, 2), history.unit.pageIds.map { it.sourcePageIndex })
        assertEquals(ReaderPortraitSingleSlot.LEFT, history.unit.slots.last().position)
        assertEquals(setOf(owner.identity.pageIds[2]), history.drawnPageIds)
    }

    @Test
    fun `cold jump six uses local starting page and reverse unknown remains right single`() {
        val owner = owner(10, 5)
        fact(owner, 5)
        assertEquals(owner.identity.pageIds[5], owner.targetUnit!!.anchor)
        owner.captureViewport()
        assertTrue(owner.navigate(false))
        fact(owner, 4)
        assertEquals(listOf(owner.identity.pageIds[4]), owner.targetUnit!!.pageIds)
        assertEquals(ReaderPortraitSingleSlot.RIGHT, owner.targetUnit!!.slots.single().position)
        owner.captureViewport()
        fact(owner, 3)
        assertEquals(listOf(owner.identity.pageIds[4]), owner.targetUnit!!.pageIds)
    }

    @Test
    fun `late manual restore changes future boundaries but preserves locked current geometry`() {
        val owner = owner(mangaId = 88)
        fact(owner, 1)
        fact(owner, 2)
        val ticket = owner.captureViewport()!!
        val slots = owner.targetUnit!!.slots
        assertTrue(
            owner.acceptPairingRestore(
                owner.beginPairingRestore()!!,
                ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(1)), 2),
            ),
        )
        assertEquals(slots, owner.targetUnit!!.slots)
        assertSame(ticket, owner.captureViewport())
        assertEquals(setOf(1), owner.pairingState.committedForcedSinglePages)
    }

    @Test
    fun `unknown fallback double permits legal explicit adjustment without manufacturing read`() {
        val owner = owner()
        fact(owner, 1)
        assertFalse(owner.targetUnit!!.certain)
        assertEquals(listOf(1, 2), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        owner.captureViewport()
        owner.adjustPairing()
        assertEquals(setOf(1), owner.pairingState.committedForcedSinglePages)
        assertEquals(owner.identity.pageIds[2], owner.location!!.pageId)
        val ticket = owner.captureViewport()!!
        assertNull(owner.settle(settled(owner, ticket, setOf(2), 1), false, "adjustment"))
    }

    private fun sample(owner: SharedDualPageSession, index: Int, leftShift: Int = 0, rightShift: Int = 0) {
        fun edge(shift: Int) = List(50) {
            (if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt()) + shift * 0x010101
        }
        assertTrue(
            owner.acceptSpreadSample(
                owner.requestSpreadSample(owner.identity.pageIds[index])!!,
                ReaderSpreadEdgeSamples(edge(leftShift), edge(rightShift)),
            ),
        )
    }

    @Test
    fun `publishing earlier orphan atomically reserves stronger later pair and ignores future competition`() {
        val owner = owner()
        (1..4).forEach { fact(owner, it) }
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        sample(owner, 1)
        sample(owner, 2, rightShift = 5)
        sample(owner, 3, rightShift = 1)
        assertEquals(listOf(1), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        val ticket = owner.captureViewport()!!
        assertEquals(setOf(owner.identity.pageIds[2] to owner.identity.pageIds[3]), owner.reservedPairs)
        owner.reportDrawn(ticket, setOf(owner.identity.pageIds[1]))
        sample(owner, 4)
        assertEquals(setOf(owner.identity.pageIds[2] to owner.identity.pageIds[3]), owner.reservedPairs)
        assertTrue(owner.navigate(true))
        assertEquals(listOf(2, 3), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `equal scores keep earlier pair while unknown or rejected near score can reserve ready later pair`() {
        for (competition in listOf("equal", "unknown", "rejected")) {
            val owner = owner()
            (1..3).forEach { fact(owner, it) }
            owner.configure(ReaderDirection.RTL, automaticMatching = true)
            if (competition != "unknown") sample(owner, 1, leftShift = if (competition == "rejected") 20 else 0)
            sample(owner, 2)
            sample(owner, 3)
            owner.captureViewport()
            if (competition == "equal") {
                assertEquals(listOf(1, 2), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
                assertTrue(owner.reservedPairs.isEmpty())
            } else {
                assertEquals(listOf(1), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
                assertEquals(setOf(owner.identity.pageIds[2] to owner.identity.pageIds[3]), owner.reservedPairs)
            }
        }
    }

    @Test
    fun `late competition may remove unexposed partner but cannot take a prepared left slot`() {
        for (partnerPrepared in listOf(false, true)) {
            val owner = owner()
            (1..3).forEach { fact(owner, it) }
            owner.configure(ReaderDirection.RTL, automaticMatching = true)
            val ids = owner.identity.pageIds
            val ticket = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
            owner.reportDrawn(ticket, setOf(ids[1]))
            val original = owner.frame(ticket)!!
            if (partnerPrepared) assertTrue(owner.preparePage(ticket, ids[2]))
            sample(owner, 2)
            sample(owner, 3)
            assertEquals(original.id, owner.frame(ticket)!!.id)
            assertEquals(original.unit.slots.first(), owner.frame(ticket)!!.unit.slots.first())
            assertEquals(
                if (partnerPrepared) listOf(1, 2) else listOf(1),
                owner.targetUnit!!.pageIds.map {
                    it.sourcePageIndex
                },
            )
            assertEquals(!partnerPrepared, owner.reservedPairs.isNotEmpty())
        }
    }

    @Test
    fun `current and animation neighbor receive independent pre pixel locks without neighbor progress`() {
        val owner = owner()
        (1..5).forEach { fact(owner, it) }
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        val ids = owner.identity.pageIds
        val current = owner.captureViewport()!!
        owner.reportDrawn(current, setOf(ids[1], ids[2]))
        val neighbor = owner.captureViewport(ids[3], setOf(ids[3], ids[4]))!!
        assertFalse(neighbor.readingEligible)
        owner.reportDrawn(neighbor, setOf(ids[3]))
        val before = owner.frame(neighbor)!!
        sample(owner, 4)
        sample(owner, 5)
        assertEquals(before.id, owner.frame(neighbor)!!.id)
        assertEquals(before.unit.slots, owner.frame(neighbor)!!.unit.slots)
        assertNotNull(owner.frame(current))
        assertNull(owner.settle(settled(owner, neighbor, setOf(3), 1), false, "animation"))
        assertTrue(owner.navigate(true))
        assertEquals(listOf(3, 4), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        val active = owner.captureViewport()!!
        assertTrue(active.readingEligible)
        assertNotNull(owner.frame(current), "Outgoing frame stays locked until the native animation releases it")
        assertEquals(before.id, owner.frame(neighbor)!!.id)
        assertTrue(owner.releaseViewport(current))
        assertNull(owner.frame(current))
    }

    @Test
    fun `history fragments meet at boundary with unique ownership and jumping within old pair preserves target`() {
        val owner = owner(10, 5)
        (1..8).forEach { fact(owner, it) }
        var ticket = owner.captureViewport()!!
        owner.reportDrawn(ticket, setOf(owner.identity.pageIds[5], owner.identity.pageIds[6]))
        owner.goToPage(owner.identity.pageIds[4])
        assertEquals(listOf(4), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        ticket = owner.captureViewport()!!
        owner.reportDrawn(ticket, setOf(owner.identity.pageIds[4]))
        owner.goToPage(owner.identity.pageIds[6])
        assertEquals(owner.identity.pageIds[6], owner.location!!.pageId)
        assertEquals(listOf(5, 6), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        val pages = owner.displayedHistory.flatMap { it.unit.pageIds }
        assertEquals(pages.distinct().size, pages.size)
    }

    @Test
    fun `actual draws are separate from settlement and additional slot may use same user sequence`() {
        val owner = owner()
        fact(owner, 1)
        val ids = owner.identity.pageIds
        val ticket = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
        assertTrue(owner.reportDrawn(ticket, setOf(ids[1])))
        assertNull(owner.settle(settled(owner, ticket, setOf(1), 1).copy(settled = false), false, "actual"))
        assertNotNull(owner.settle(settled(owner, ticket, setOf(1), 1), false, "actual"))
        fact(owner, 2)
        assertFalse(owner.reportDrawn(ticket, setOf(ids[2])), "A drawable fact is not a pre pixel lock")
        assertTrue(owner.preparePage(ticket, ids[2]))
        assertTrue(owner.reportDrawn(ticket, setOf(ids[2])))
        assertNotNull(owner.settle(settled(owner, ticket, setOf(1, 2), 1), false, "actual"))
        assertNull(owner.settle(settled(owner, ticket, setOf(2), 1), false, "actual"))
        assertTrue(owner.releaseViewport(ticket))
        assertFalse(owner.preparePage(ticket, ids[2]))
        assertFalse(owner.reportDrawn(ticket, setOf(ids[2])))
    }

    @Test
    fun `conditional B2 slots stay fixed when wide structure arrives after preparation`() {
        for (target in listOf(1, 19)) {
            for (early in listOf(false, true)) {
                val owner = owner(22, target)
                fact(owner, target)
                if (early) {
                    fact(owner, target + 1, true)
                    if (target == 19) fact(owner, target - 1, true)
                }
                val ticket = owner.captureViewport()!!
                val slot = owner.frame(ticket)!!.unit.slots.first()
                assertEquals(
                    if (!early) {
                        ReaderPortraitSingleSlot.RIGHT
                    } else if (target ==
                        1
                    ) {
                        ReaderPortraitSingleSlot.LEFT
                    } else {
                        ReaderPortraitSingleSlot.FULL
                    },
                    slot.position,
                )
                if (!early) {
                    fact(owner, target + 1, true)
                    if (target == 19) fact(owner, target - 1, true)
                }
                assertEquals(slot, owner.frame(ticket)!!.unit.slots.first())
            }
        }
    }

    @Test
    fun `explicit context changes reject old tickets and content replacement cannot reuse old history`() {
        for (change in listOf("viewport", "direction", "mode", "content")) {
            val owner = owner()
            fact(owner, 1)
            fact(owner, 2)
            val ticket = owner.captureViewport()!!
            owner.reportDrawn(ticket, owner.targetUnit!!.pageIds.toSet())
            when (change) {
                "viewport" -> owner.invalidateViewport()
                "direction" -> owner.configure(ReaderDirection.LTR)
                "mode" -> owner.configurePresentationMode(ReaderPresentationMode.SINGLE)
                "content" -> {
                    val changed = owner.identity.copy(
                        content = owner.identity.content.map {
                            it.copy(contentVersion = "new:${it.contentVersion}")
                        },
                    )
                    assertFalse(
                        owner.canRebindContent(changed),
                        "Background replacement must retain visible old content",
                    )
                    val next = owner.rebindContent(changed, explicitReload = true)
                    assertTrue(next.displayedHistory.isEmpty())
                    assertFalse(next.reportDrawn(ticket, setOf(next.identity.pageIds[1])))
                }
            }
            assertFalse(owner.reportDrawn(ticket, setOf(owner.identity.pageIds[1])))
            assertNull(owner.settle(settled(owner, ticket, setOf(1), 1), false, "old-context"))
            if (change == "viewport" || change == "direction") {
                assertFalse(
                    ticket.frameId == owner.captureViewport()!!.frameId,
                    "Explicit geometry has a new mount context",
                )
            }
        }
    }

    @Test
    fun `later page progress followed by earlier actual draw never regresses maximum presented source page`() {
        val owner = owner()
        fact(owner, 1)
        fact(owner, 2)
        val ticket = owner.captureViewport()!!
        assertEquals(2, owner.settle(settled(owner, ticket, setOf(2), 1), false, "later-first")!!.lastPageRead)
        assertEquals(2, owner.settle(settled(owner, ticket, setOf(1, 2), 2), false, "later-first")!!.lastPageRead)
    }

    @Test
    fun `navigation withdraws old append permission but retains outgoing pixel geometry until release`() {
        val owner = owner()
        fact(owner, 1)
        val ids = owner.identity.pageIds
        val outgoing = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
        owner.reportDrawn(outgoing, setOf(ids[1]))
        val fixed = owner.frame(outgoing)!!
        owner.navigate(true)
        fact(owner, 2)
        assertEquals(fixed.id, owner.frame(outgoing)!!.id)
        assertEquals(fixed.unit.slots.first(), owner.frame(outgoing)!!.unit.slots.first())
        assertFalse(owner.preparePage(outgoing, ids[2]))
        assertFalse(owner.reportDrawn(outgoing, setOf(ids[2])))
        assertNull(owner.settle(settled(owner, outgoing, setOf(1), 1), false, "outgoing"))
        assertTrue(owner.releaseViewport(outgoing))
        assertNull(owner.frame(outgoing))
    }

    @Test
    fun `forward and reverse local target match native pager projection without page overlap`() {
        for (forward in listOf(false, true)) {
            val owner = owner(10, 5)
            (1..8).forEach { fact(owner, it) }
            val ticket = owner.captureViewport()!!
            owner.reportDrawn(ticket, owner.targetUnit!!.pageIds.toSet())
            assertTrue(owner.navigate(forward))
            val target = owner.targetUnit!!
            assertEquals(target, owner.presentationUnits.single { owner.location!!.pageId in it.pageIds })
            val projected = owner.presentationUnits.flatMap { it.pageIds }
            assertEquals(owner.identity.pageIds, projected)
            val prepared = owner.captureViewport()!!
            assertEquals(
                owner.frame(prepared)!!.unit,
                owner.presentationUnits.single {
                    owner.location!!.pageId in
                        it.pageIds
                },
            )
        }
    }

    @Test
    fun `distant actual type patches its candidate while retaining shown and unrelated frames`() {
        val owner = owner(300)
        fact(owner, 1)
        val ticket = owner.captureViewport(preparingPageIds = setOf(owner.identity.pageIds[1]))!!
        owner.reportDrawn(ticket, setOf(owner.identity.pageIds[1]))
        val shown = owner.frame(ticket)
        val projection = owner.presentationUnits
        val unrelated = projection.single { owner.identity.pageIds[99] in it.pageIds }
        fact(owner, 199, wide = true)
        val far = owner.presentationUnits.single { owner.identity.pageIds[199] in it.pageIds }
        assertEquals(listOf(owner.identity.pageIds[199]), far.pageIds)
        assertEquals(ReaderPortraitSingleSlot.FULL, far.slots.single().position)
        assertEquals(shown, owner.frame(ticket), "A distant type must not reframe pixels already exposed")
        assertSame(unrelated, owner.presentationUnits.single { owner.identity.pageIds[99] in it.pageIds })
        val updated = owner.presentationUnits
        fact(owner, 199, wide = true)
        assertSame(updated, owner.presentationUnits, "Repeated classification must reuse the cached projection")
        fact(owner, 2, wide = true)
        assertSame(far, owner.presentationUnits.single { owner.identity.pageIds[199] in it.pageIds })
        assertSame(unrelated, owner.presentationUnits.single { owner.identity.pageIds[99] in it.pageIds })
        assertEquals(owner.identity.pageIds, owner.presentationUnits.flatMap { it.pageIds })
        owner.goToPage(owner.identity.pageIds[199])
        assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
    }

    @Test
    fun `unpainted loading record yields to target actual wide type before its first pixel`() {
        val owner = owner()
        val placeholder = owner.captureViewport(preparingPageIds = emptySet())!!
        assertTrue(owner.frame(placeholder)!!.lockedPageIds.isEmpty())
        fact(owner, 1, wide = true)
        val ready = owner.captureViewport()!!
        assertEquals(ReaderPortraitSingleSlot.FULL, owner.frame(ready)!!.unit.slots.single().position)
        assertNull(owner.frame(placeholder), "An unpainted proposal carries no old half-slot authority")
    }

    @Test
    fun `rapid source requests can continue past a loading target without counting it drawn`() {
        val owner = owner()
        fact(owner, 1)
        val first = owner.captureViewport()!!
        owner.reportDrawn(first, setOf(owner.identity.pageIds[1]))
        owner.navigate(true)
        val loading = owner.captureViewport(preparingPageIds = emptySet())!!
        assertEquals(3, owner.location!!.pageId.sourcePageIndex)
        assertTrue(owner.navigate(true))
        assertEquals(5, owner.location!!.pageId.sourcePageIndex)
        assertFalse(owner.reportDrawn(loading, setOf(owner.identity.pageIds[3])))
    }

    @Test
    fun `unrelated content replacement preserves unchanged frame and history but rejects old capabilities`() {
        val owner = owner()
        fact(owner, 1)
        fact(owner, 2)
        val old = owner.captureViewport()!!
        owner.reportDrawn(old, owner.targetUnit!!.pageIds.toSet())
        val before = owner.frame(old)!!
        val next = owner.rebindContent(
            owner.identity.copy(
                content = owner.identity.content.map {
                    if (it.pageId.sourcePageIndex == 6) it.copy(contentVersion = "new-six") else it
                },
            ),
        )
        assertEquals(before.unit.slots, next.targetUnit!!.slots)
        assertEquals(before.id, next.captureViewport()!!.frameId)
        assertEquals(before.drawnPageIds, next.displayedHistory.single().drawnPageIds)
        assertFalse(next.reportDrawn(old, setOf(next.identity.pageIds[1])))
        next.goToPage(next.identity.pageIds[2])
        assertEquals(listOf(1, 2), next.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `committed reservation does not yield to stronger later score after its leading page starts drawing`() {
        val owner = owner()
        (1..4).forEach { fact(owner, it) }
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        sample(owner, 2)
        sample(owner, 3, rightShift = 1)
        val first = owner.captureViewport()!!
        owner.reportDrawn(first, setOf(owner.identity.pageIds[1]))
        owner.navigate(true)
        val reserved = owner.captureViewport(preparingPageIds = setOf(owner.identity.pageIds[2]))!!
        owner.reportDrawn(reserved, setOf(owner.identity.pageIds[2]))
        sample(owner, 4)
        assertEquals(listOf(2, 3), owner.frame(reserved)!!.unit.pageIds.map { it.sourcePageIndex })
        assertTrue(owner.reservedPairs.isEmpty(), "Prepared reservation is now a frame commitment")
    }

    @Test
    fun `real B1 tail target and reverse grouping honor timely facts without prefix reconstruction`() {
        val rows = requireNotNull(javaClass.getResourceAsStream("/reader/shared-dual-page-real-b1.csv"))
            .bufferedReader(Charsets.UTF_8).use {
                it.lineSequence().filter { line ->
                    line.isNotBlank() &&
                        !line.startsWith("#")
                }.map { line -> line.split(',').map(String::toInt) }.toList()
            }
        for (timely in listOf(false, true)) {
            val owner = owner(rows.size, 16)
            val ids = owner.identity.pageIds
            fun real(index: Int) {
                val row = rows[index]
                assertTrue(owner.acceptFact(owner.requestFact(ids[index]), ReaderPageFact.dimensions(row[1], row[2])))
            }
            if (timely) rows.indices.forEach(::real) else real(16)
            val tail = owner.captureViewport()!!
            assertEquals(ids[16], owner.location!!.pageId)
            assertEquals(listOf(ids[16]), owner.frame(tail)!!.unit.pageIds)
            owner.reportDrawn(tail, setOf(ids[16]))
            owner.navigate(false)
            if (!timely) real(15)
            val reverse = owner.captureViewport()!!
            assertEquals(if (timely) listOf(ids[14], ids[15]) else listOf(ids[15]), owner.frame(reverse)!!.unit.pageIds)
            if (!timely) real(14)
            assertEquals(if (timely) listOf(ids[14], ids[15]) else listOf(ids[15]), owner.frame(reverse)!!.unit.pageIds)
        }
    }

    @Test
    fun `actual EXIF types and LTR stable slots mirror without guessing portrait`() {
        for (orientation in ReaderImageOrientation.entries) {
            for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
                val owner = owner()
                owner.configure(direction)
                val page = owner.identity.pageIds[1]
                val size = orientation.orientedSize(900, 1300)
                val fact = ReaderPageFact.dimensions(size.width, size.height, sourceOrientation = orientation)
                assertNotNull(owner.acceptOrientedFact(owner.requestFact(page), fact))
                val ticket = owner.captureViewport(preparingPageIds = setOf(page))!!
                assertEquals(
                    if (fact.state ==
                        ReaderPageFactState.SPREAD
                    ) {
                        ReaderPortraitSingleSlot.FULL
                    } else if (direction ==
                        ReaderDirection.RTL
                    ) {
                        ReaderPortraitSingleSlot.RIGHT
                    } else {
                        ReaderPortraitSingleSlot.LEFT
                    },
                    owner.frame(ticket)!!.unit.slots.first().position,
                )
            }
        }
    }

    @Test
    fun `explicit viewport event preserves partially shown source combination without inventing partner reading`() {
        val owner = owner()
        fact(owner, 1)
        fact(owner, 2)
        val ids = owner.identity.pageIds
        val old = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
        owner.reportDrawn(old, setOf(ids[1]))
        owner.invalidateViewport()
        assertEquals(listOf(ids[1], ids[2]), owner.targetUnit!!.pageIds)
        val next = owner.captureViewport(preparingPageIds = setOf(ids[1]))!!
        assertEquals(listOf(ids[1], ids[2]), owner.frame(next)!!.unit.pageIds)
        assertEquals(setOf(ids[1]), owner.frame(next)!!.drawnPageIds)
        assertNull(
            owner.settle(
                settled(owner, next, setOf(1), 1).copy(reason = ReaderDualPageSettlementReason.LAYOUT_ONLY),
                false,
                "viewport",
            ),
        )
        owner.navigate(true)
        assertEquals(ids[2], owner.location!!.pageId)
        assertEquals(ids[2], owner.targetUnit!!.anchor)
    }

    @Test
    fun `walked opening summary preserves left isolated page without reading unknown prefix on cold jump`() {
        fun prepare(): SharedDualPageSession = owner(8, 0).also { session ->
            (0..7).forEach { fact(session, it, wide = it in setOf(0, 2, 3, 5)) }
        }
        val sequential = prepare()
        repeat(4) {
            val ticket = sequential.captureViewport()!!
            sequential.reportDrawn(ticket, sequential.targetUnit!!.pageIds.toSet())
            assertTrue(sequential.navigate(true))
        }
        assertEquals(4, sequential.location!!.pageId.sourcePageIndex)
        assertEquals(ReaderPortraitSingleSlot.LEFT, sequential.targetUnit!!.slots.single().position)
        val cold = prepare()
        cold.goToPage(cold.identity.pageIds[4])
        assertEquals(ReaderPortraitSingleSlot.FULL, cold.targetUnit!!.slots.single().position)
    }

    @Test
    fun `reverse pair earlier slot may draw first without losing original logical target or later slot`() {
        val owner = owner(10, 5)
        (2..6).forEach { fact(owner, it) }
        val first = owner.captureViewport()!!
        owner.reportDrawn(first, owner.targetUnit!!.pageIds.toSet())
        owner.navigate(false)
        val ids = owner.identity.pageIds
        assertEquals(ids[4], owner.location!!.pageId)
        val reverse = owner.captureViewport(preparingPageIds = setOf(ids[3]))!!
        assertTrue(owner.reportDrawn(reverse, setOf(ids[3])))
        val right = owner.frame(reverse)!!.unit.slots.first()
        fact(owner, 8)
        assertEquals(ids[4], owner.location!!.pageId)
        assertEquals(listOf(ids[3], ids[4]), owner.frame(reverse)!!.unit.pageIds)
        assertEquals(right, owner.frame(reverse)!!.unit.slots.first())
        assertTrue(owner.preparePage(reverse, ids[4]))
        assertTrue(owner.reportDrawn(reverse, setOf(ids[4])))
    }

    @Test
    fun `late successful manual save invalidates original conflict history without changing current frame`() {
        for (releaseBeforeSave in listOf(false, true)) {
            val owner = owner(mangaId = 88)
            (1..6).forEach { fact(owner, it) }
            assertTrue(owner.acceptPairingRestore(owner.beginPairingRestore()!!, ChapterPairingSnapshot(null, 1)))
            val ids = owner.identity.pageIds
            val original = owner.captureViewport()!!
            owner.reportDrawn(original, setOf(ids[1], ids[2]))
            val save = owner.adjustPairing()!!
            owner.navigate(true)
            val current = owner.captureViewport()!!
            owner.reportDrawn(current, owner.targetUnit!!.pageIds.toSet())
            val frame = owner.frame(current)!!
            val location = owner.location
            if (releaseBeforeSave) owner.releaseViewport(original)
            assertTrue(owner.acceptPairingSave(save, ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(1)), 2)))
            assertEquals(location, owner.location)
            assertEquals(frame.unit.slots, owner.frame(current)!!.unit.slots)
            if (!releaseBeforeSave) owner.releaseViewport(original)
            owner.goToPage(ids[1])
            assertEquals(listOf(ids[1]), owner.targetUnit!!.pageIds)
            assertEquals(ReaderPortraitSingleSlot.RIGHT, owner.targetUnit!!.slots.single().position)
        }
    }
}
