package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The finite entry list of the certifier may only contain states a real chapter
 * scan can reach.
 *
 * A reserved entry means "the left neighbour already emitted a single page and
 * reserved this index for the pair that follows". The reservation can only be
 * created by the step at `first - 1`, and that step can only pair a page away
 * when the page is not a forced single page. A forced single page is a hard
 * boundary, so the reserved entry is unreachable there: keeping it let an entry
 * that no real scan needs request an edge and hold the target pending for a fact
 * that cannot change it.
 */
class ReaderPairingReachableEntryPruneTest {
    private val portrait = ReaderPageFact.dimensions(256, 384)

    private fun kindsOf(count: Int) = List(count) { index ->
        when (index) {
            3 -> ReaderPairingTestPixels.TEXTURE
            4, 5 -> ReaderPairingTestPixels.SHIFTED
            else -> ReaderPairingTestPixels.GRAY
        }
    }

    /**
     * The D witness of the research report: 20 pages, the default sample
     * capacity, page 2 forced, and the target at page 5.
     *
     * Pages 3, 4 and 5 arrive first, so the edges 3-4 and 4-5 are scored while
     * both endpoints are still resident. Their raw samples are then evicted, but
     * the scores stay. The edge 5-6 is never computed.
     */
    private fun buildWitness(
        capacity: Int,
    ): Triple<List<ReaderPageId>, ReaderDualPageIdentity, ReaderSpreadSamplesOwner> {
        val pages = List(20) { ReaderPageId(ReaderChapterId(20261029), it) }
        val identity = ReaderDualPageIdentity(
            1L,
            1L,
            pages.map { ReaderDualPageContentIdentity(it, "content-$it") },
        )
        val originals = kindsOf(pages.size).map { readerPairingTestOriginal(it) }
        val owner = ReaderSpreadSamplesOwner(identity, capacity)
        fun accept(index: Int) {
            val (fact, samples) = originals[index]
            assertTrue(
                owner.acceptOriginal(owner.request(pages[index]), fact, samples.edges),
                "accept ${pages[index]}",
            )
        }
        listOf(3, 4, 5).forEach(::accept)
        pages.indices.filter { it !in 3..6 }.forEach(::accept)
        accept(6)
        return Triple(pages, identity, owner)
    }

    private fun settings(forced: Set<ReaderPageId>) = ReaderPairingSettings(
        direction = ReaderDirection.RTL,
        automatic = true,
        forcedSinglePages = forced,
        manualPairStarts = emptySet(),
        openingMode = ReaderChapterOpeningMode.FIRST_PAIR,
        manualRevision = 0L,
    )

    /** The complete plan of the same facts, with every sample retained. */
    private fun completePlan(
        pages: List<ReaderPageId>,
        identity: ReaderDualPageIdentity,
        forced: Set<ReaderPageId>,
    ): List<ReaderDualPageUnit> {
        val owner = ReaderSpreadSamplesOwner(identity, 16)
        try {
            val originals = kindsOf(pages.size).map { readerPairingTestOriginal(it) }
            pages.indices.forEach { index ->
                val (fact, samples) = originals[index]
                assertTrue(
                    owner.acceptOriginal(owner.request(pages[index]), fact, samples.edges),
                    "accept ${pages[index]}",
                )
            }
            val facts = pages.associateWith { page ->
                (owner.pageAnalysis(page) as ReaderPageAnalysisEvidence.Computed).fact
            }
            return SharedDualPageRules.canonicalPlan(
                pages = pages,
                facts = facts,
                direction = ReaderDirection.RTL,
                forcedSinglePages = forced,
                manualPairStarts = emptySet(),
                openingMode = ReaderChapterOpeningMode.FIRST_PAIR,
                automatic = true,
                distance = { first, second ->
                    (
                        owner.edgeAnalysis(
                            first,
                            second,
                            ReaderDirection.RTL,
                        ) as? ReaderEdgeAnalysisEvidence.Computed
                        )?.score
                },
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun `a forced left neighbour makes the reserved entry unreachable`() {
        val (pages, identity, owner) = buildWitness(capacity = 16)
        try {
            // The fixture itself: the two decisive edges are known, the third is not.
            val near = owner.edgeAnalysis(pages[3], pages[4], ReaderDirection.RTL)
            val later = owner.edgeAnalysis(pages[4], pages[5], ReaderDirection.RTL)
            assertEquals(15.0, (near as ReaderEdgeAnalysisEvidence.Computed).score)
            assertEquals(0.0, (later as ReaderEdgeAnalysisEvidence.Computed).score)
            assertTrue(
                owner.edgeAnalysis(pages[5], pages[6], ReaderDirection.RTL) is ReaderEdgeAnalysisEvidence.NotAnalyzed,
                "The edge 5-6 must stay unknown in this fixture",
            )

            // A forced single page is a legal persisted record.
            val record = ChapterPairingRecord(2, 20, setOf(2), openingMode = ReaderChapterOpeningMode.FIRST_PAIR)
            assertTrue(record.isValidFor(20), "The witness must be a legal persisted record")

            val forced = setOf(pages[2])
            val headers = pages.associateWith { portrait }
            val result = ReaderPairingCertifier.certifyWithDependencies(
                identity,
                headers,
                owner,
                settings(forced),
                pages[5],
            )
            assertTrue(
                result.certification is ReaderPairingCertification.Certified,
                "The forced boundary must certify without an unreachable reserved entry: ${result.certification}",
            )
            val certified = result.certification as ReaderPairingCertification.Certified
            val oracle = completePlan(pages, identity, forced).single { unit -> pages[5] in unit.pageIds }
            assertEquals(oracle, certified.segment.unit, "The certified unit must equal the complete plan")
            assertEquals(listOf(pages[4], pages[5]), certified.segment.unit.pageIds)
            assertEquals(ReaderPortraitSingleSlot.RIGHT, certified.segment.unit.slots[0].position)
            assertEquals(ReaderPortraitSingleSlot.LEFT, certified.segment.unit.slots[1].position)
            assertTrue(certified.segment.unit.certain, "A complete pair is certain")
            assertNull(certified.segment.unit.singleReason, "A pair has no single page reason")
            assertTrue(
                certified.segment.isCurrent(identity, settings(forced), owner, headers),
                "The reused certificate must still be current for the same input",
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun `without a forced boundary the reserved entry stays reachable`() {
        val (pages, identity, owner) = buildWitness(capacity = 16)
        try {
            val headers = pages.associateWith { portrait }
            val result = ReaderPairingCertifier.certifyWithDependencies(
                identity,
                headers,
                owner,
                settings(emptySet()),
                pages[5],
            )
            val needs = (result.certification as ReaderPairingCertification.NeedFacts).facts
            assertTrue(
                needs.any { need ->
                    need.kind == ReaderPairingFactKind.EDGE_SAMPLES &&
                        need.pageId == pages[5] && need.secondPageId == pages[6]
                },
                "The reachable entry must still request the edge it can need: $needs",
            )
        } finally {
            owner.close()
        }
    }
}
