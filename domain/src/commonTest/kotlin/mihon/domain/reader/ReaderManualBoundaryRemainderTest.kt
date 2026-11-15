package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderManualBoundaryRemainderTest {
    private fun identity() = ReaderDualPageIdentity(
        1,
        1,
        List(6) {
            ReaderDualPageContentIdentity(ReaderPageId(ReaderChapterId(44929), it), "page-$it")
        },
    )

    private fun original(
        hint: ReaderPageSideHint = ReaderPageSideHint.UNKNOWN,
    ): Pair<ReaderPageFact, ReaderOriginalPageSamples> {
        val plan = requireNotNull(ReaderOriginalPageSampling.plan(256, 384))
        fun pixel(x: Int) = if ((hint == ReaderPageSideHint.LEFT && x >= 243) ||
            (hint == ReaderPageSideHint.RIGHT && x < 13)
        ) {
            -1
        } else {
            0xff8c8c8c.toInt()
        }
        val samples = plan.samples(plan.left.map { (x, _) -> pixel(x) }, plan.right.map { (x, _) -> pixel(x) })
        assertEquals(hint, samples.sideHint)
        return ReaderPageFact.dimensions(256, 384).copy(sideHint = hint) to samples
    }

    @Test
    fun `portrait remainder before manual boundary keeps a certified leading half in both directions`() {
        val identity = identity()
        val ids = identity.pageIds
        val (fact, samples) = original()
        val facts = ids.associateWith { fact }
        val owner = ReaderSpreadSamplesOwner(identity)
        try {
            ids.forEach { assertTrue(owner.acceptOriginal(owner.request(it), fact, samples.edges)) }
            for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
                for (automatic in listOf(false, true)) {
                    val forced = setOf(ids[4])
                    fun plan(boundaries: Set<ReaderPageId>) = SharedDualPageRules.canonicalPlan(
                        ids,
                        facts,
                        direction,
                        forcedSinglePages = boundaries,
                        automatic = automatic,
                        distance = { a, b ->
                            (owner.edgeAnalysis(a, b, direction) as ReaderEdgeAnalysisEvidence.Computed).score
                        },
                    )
                    fun certify(boundaries: Set<ReaderPageId>) = (
                        ReaderPairingCertifier.certify(
                            identity,
                            facts,
                            owner,
                            ReaderPairingSettings(direction, automatic, boundaries),
                            ids[3],
                        ) as ReaderPairingCertification.Certified
                        ).segment.unit
                    val units = plan(forced)
                    assertEquals(listOf(ids[1], ids[2]), units.single { ids[2] in it.pageIds }.pageIds)
                    val remainder = units.single { ids[3] in it.pageIds }
                    assertEquals(listOf(ids[3]), remainder.pageIds)
                    assertEquals(ReaderDualPageSingleReason.MANUAL_BOUNDARY, remainder.singleReason)
                    assertEquals(
                        if (direction ==
                            ReaderDirection.RTL
                        ) {
                            ReaderPortraitSingleSlot.RIGHT
                        } else {
                            ReaderPortraitSingleSlot.LEFT
                        },
                        remainder.slots.single().position,
                    )
                    assertEquals(remainder, certify(forced))
                    assertEquals(ids, units.flatMap { it.pageIds })
                    val restored = plan(emptySet()).single { ids[3] in it.pageIds }
                    assertEquals(listOf(ids[3], ids[4]), restored.pageIds)
                    assertEquals(restored, certify(emptySet()))
                }
            }
        } finally {
            owner.close()
        }
    }

    private fun session(direction: ReaderDirection, forced: Set<Int>) = identity().let { identity ->
        SharedDualPageSession(identity, direction, identity.pageIds[3], mangaId = 88).also {
            assertTrue(
                it.acceptPairingRestore(
                    requireNotNull(it.beginPairingRestore()),
                    ChapterPairingSnapshot(ChapterPairingRecord(1, 6, forced), 1),
                ),
            )
            it.configure(direction, automaticMatching = true)
        }
    }

    @Test
    fun `pending default trailing remainder locks the correct slot then gains paper from the same certified frame`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val owner = session(direction, setOf(2, 4))
            try {
                val ids = owner.identity.pageIds
                val trailingHint = if (direction ==
                    ReaderDirection.RTL
                ) {
                    ReaderPageSideHint.LEFT
                } else {
                    ReaderPageSideHint.RIGHT
                }
                val trailing = if (direction ==
                    ReaderDirection.RTL
                ) {
                    ReaderPortraitSingleSlot.LEFT
                } else {
                    ReaderPortraitSingleSlot.RIGHT
                }
                ids.forEachIndexed { index, id ->
                    if (index != 4) {
                        val (fact, samples) = original(if (index == 3) trailingHint else ReaderPageSideHint.UNKNOWN)
                        assertTrue(
                            owner.acceptOriginalAnalysis(
                                requireNotNull(owner.requestOriginalAnalysis(id)),
                                fact,
                                samples,
                            ),
                        )
                    }
                }
                assertTrue(owner.certifyPairing(ids[3]) is ReaderPairingCertification.NeedFacts)
                val provisional = owner.presentationFrames.single { ids[3] in it.unit.pageIds }
                assertEquals(listOf(ids[3]), provisional.unit.pageIds)
                assertEquals(ReaderDualPageSingleReason.MANUAL_BOUNDARY, provisional.unit.singleReason)
                assertEquals(trailing, provisional.unit.slots.single().position)
                assertNull(provisional.blankPaperSlot, "A neighbour boundary is not the current page's forced single")
                val ticket = requireNotNull(owner.capturePresentableViewport(ids[3]))
                assertEquals(ReaderDualPagePresentationSource.DEFAULT, ticket.source)
                assertTrue(owner.preparePage(ticket, ids[3]))
                assertTrue(owner.reportDrawn(ticket, setOf(ids[3])))
                val (fact, samples) = original()
                assertTrue(
                    owner.acceptOriginalAnalysis(requireNotNull(owner.requestOriginalAnalysis(ids[4])), fact, samples),
                )
                val certified = owner.certifyPairing(ids[3]) as ReaderPairingCertification.Certified
                assertEquals(provisional.unit, certified.segment.unit)
                val frame = requireNotNull(owner.frame(ticket))
                assertEquals(ticket.frameId, frame.id)
                assertEquals(trailing, frame.unit.slots.single().position)
                assertEquals(setOf(ids[3]), frame.lockedPageIds)
                assertNotNull(frame.blankPaperSlot)
                val runs = owner.certificationMetrics.engineRuns
                repeat(3) { assertNotNull(owner.frame(ticket)!!.blankPaperSlot) }
                assertEquals(runs, owner.certificationMetrics.engineRuns, "Decoration reads do not certify")
            } finally {
                owner.close()
            }
        }
    }

    @Test
    fun `default side correction cannot pull a remainder back into an already paired neighbour`() {
        val owner = session(ReaderDirection.RTL, setOf(4))
        try {
            val ids = owner.identity.pageIds
            ids.forEach { assertTrue(owner.acceptFact(owner.requestFact(it), original().first)) }
            val (fact, samples) = original(ReaderPageSideHint.LEFT)
            assertTrue(
                owner.acceptOriginalAnalysis(requireNotNull(owner.requestOriginalAnalysis(ids[3])), fact, samples),
            )
            assertTrue(owner.certifyPairing(ids[3]) is ReaderPairingCertification.NeedFacts)
            val frames = owner.presentationFrames
            assertEquals(listOf(ids[1], ids[2]), frames.single { ids[1] in it.unit.pageIds }.unit.pageIds)
            val remainder = frames.single { ids[3] in it.unit.pageIds }
            assertEquals(listOf(ids[3]), remainder.unit.pageIds)
            assertEquals(ReaderDualPageSingleReason.MANUAL_BOUNDARY, remainder.unit.singleReason)
            assertEquals(ReaderPortraitSingleSlot.LEFT, remainder.unit.slots.single().position)
            assertEquals(ids, frames.flatMap { it.unit.pageIds })
            assertNull(remainder.blankPaperSlot)
        } finally {
            owner.close()
        }
    }

    @Test
    fun `manual revision and next header invalidate memo without erasing admitted geometry`() {
        val owner = session(ReaderDirection.RTL, setOf(4))
        try {
            val ids = owner.identity.pageIds
            owner.configure(ReaderDirection.RTL, automaticMatching = false)
            ids.forEach { assertTrue(owner.acceptFact(owner.requestFact(it), original().first)) }
            fun certified() = (owner.certifyPairing(ids[3]) as ReaderPairingCertification.Certified).segment.unit
            assertEquals(ReaderDualPageSingleReason.MANUAL_BOUNDARY, certified().singleReason)
            val runs = owner.certificationMetrics.engineRuns
            assertTrue(
                owner.acceptPairingRestore(
                    requireNotNull(owner.beginPairingRestore(explicit = true)),
                    ChapterPairingSnapshot(null, 2),
                ),
            )
            assertEquals(listOf(ids[3], ids[4]), certified().pageIds)
            assertTrue(owner.certificationMetrics.engineRuns > runs)
            assertTrue(
                owner.acceptPairingRestore(
                    requireNotNull(owner.beginPairingRestore(explicit = true)),
                    ChapterPairingSnapshot(ChapterPairingRecord(1, 6, setOf(4)), 3),
                ),
            )
            assertEquals(ReaderDualPageSingleReason.MANUAL_BOUNDARY, certified().singleReason)
            assertTrue(owner.acceptFact(owner.requestFact(ids[4]), ReaderPageFact.dimensions(512, 384)))
            assertEquals(ReaderDualPageSingleReason.REMAINDER_BEFORE_SPREAD, certified().singleReason)
            assertTrue(owner.acceptFact(owner.requestFact(ids[4]), ReaderPageFact(ReaderPageFactState.FAILED)))
            assertEquals(
                ReaderDualPageSingleReason.REMAINDER_BEFORE_SPREAD,
                certified().singleReason,
                "A failed scalar notification cannot erase an already admitted original header",
            )
            assertTrue(owner.acceptFact(owner.requestFact(ids[4]), original().first))
            assertEquals(ReaderDualPageSingleReason.MANUAL_BOUNDARY, certified().singleReason)
        } finally {
            owner.close()
        }
    }

    @Test
    fun `manual remainder classification preserves existing geometry priorities and manual pairs`() {
        val ids = identity().pageIds
        for (opening in listOf(ReaderChapterOpeningMode.AUTO)) {
            val portrait = original().first
            val wide = ReaderPageFact.dimensions(512, 384)
            fun plan(
                spreads: Set<Int>,
                forced: Set<Int>,
                pairs: Set<Int> = emptySet(),
            ) = SharedDualPageRules.canonicalPlan(
                ids,
                ids.associateWith { if (it.sourcePageIndex in spreads) wide else portrait },
                ReaderDirection.RTL,
                forced.mapTo(mutableSetOf()) { ids[it] },
                pairs,
                opening,
            )
            val beforeWide = plan(setOf(4), setOf(4)).single { ids[3] in it.pageIds }
            assertTrue(
                beforeWide.singleReason in
                    setOf(ReaderDualPageSingleReason.OPENING, ReaderDualPageSingleReason.REMAINDER_BEFORE_SPREAD),
            )
            val ambiguous = plan(setOf(2, 4), setOf(4)).single { ids[3] in it.pageIds }
            assertTrue(
                ambiguous.singleReason in
                    setOf(ReaderDualPageSingleReason.OPENING, ReaderDualPageSingleReason.AMBIGUOUS),
            )
            val ownWide = plan(setOf(3), setOf(4)).single { ids[3] in it.pageIds }
            assertEquals(ReaderDualPageSingleReason.SPREAD, ownWide.singleReason)
            assertEquals(ReaderPortraitSingleSlot.FULL, ownWide.slots.single().position)
            val forced = plan(emptySet(), setOf(3, 4)).single { ids[3] in it.pageIds }
            assertEquals(ReaderDualPageSingleReason.MANUAL_BOUNDARY, forced.singleReason)
            val manual = plan(emptySet(), setOf(4), setOf(2)).single { ids[3] in it.pageIds }
            assertEquals(listOf(ids[2], ids[3]), manual.pageIds)
        }
    }
}
