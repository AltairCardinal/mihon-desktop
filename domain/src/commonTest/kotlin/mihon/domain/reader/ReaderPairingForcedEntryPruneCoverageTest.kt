package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Coverage of the scenarios the pairing research document listed as not covered:
 * the default-capacity owner state, a wide-page target and an explicit manual
 * pair start that overlaps the forced boundary.
 *
 * Every mutation goes through the production owner and the production
 * certifier, and the complete plan of the same facts is the oracle. The
 * nineteen-page case uses the real owner capacity, so the eviction that the
 * research document only derived from the source is exercised for real.
 */
class ReaderPairingForcedEntryPruneCoverageTest {
    private val gray = 0xFF808080.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun pages(count: Int) = List(count) { ReaderPageId(ReaderChapterId(9136), it) }

    private fun identityOf(pages: List<ReaderPageId>) = ReaderDualPageIdentity(
        1L,
        1L,
        pages.map { ReaderDualPageContentIdentity(it, "content-$it") },
    )

    /** The real sampler turns the pixels of this page into its side hint. */
    private fun analysis(
        width: Int = 256,
        height: Int = 384,
        leftColor: Int = gray,
        rightColor: Int = gray,
    ): Pair<ReaderPageFact, ReaderOriginalPageSamples> {
        val plan = requireNotNull(ReaderOriginalPageSampling.plan(width, height))
        val samples = plan.samples(
            List(plan.left.size) { leftColor },
            List(plan.right.size) { rightColor },
        )
        return ReaderPageFact.dimensions(width, height).copy(sideHint = samples.sideHint) to samples
    }

    private fun settings(
        forced: Set<ReaderPageId> = emptySet(),
        manualPairStarts: Set<Int> = emptySet(),
        direction: ReaderDirection = ReaderDirection.LTR,
    ) = ReaderPairingSettings(
        direction,
        automatic = true,
        forcedSinglePages = forced,
        manualPairStarts = manualPairStarts,
        openingMode = ReaderChapterOpeningMode.AUTO,
    )

    /**
     * The distance of one adjacent pair, measured on its own owner.
     *
     * The owner keeps at most sixteen sample sets, so a nineteen-page chapter
     * cannot keep every page resident. Each pair is therefore measured with
     * the two pages it needs, which is the evidence the plan rule consumes.
     */
    private fun pairDistance(
        identity: ReaderDualPageIdentity,
        originals: Map<ReaderPageId, Pair<ReaderPageFact, ReaderOriginalPageSamples>>,
        direction: ReaderDirection = ReaderDirection.LTR,
    ): (ReaderPageId, ReaderPageId) -> Double? = { first, second ->
        val owner = ReaderSpreadSamplesOwner(identity)
        try {
            listOf(first, second).forEach { page ->
                val (fact, samples) = originals.getValue(page)
                check(owner.acceptOriginal(owner.request(page), fact, samples.edges))
            }
            (owner.edgeAnalysis(first, second, direction) as? ReaderEdgeAnalysisEvidence.Computed)?.score
        } finally {
            owner.close()
        }
    }

    private fun completePlan(
        pages: List<ReaderPageId>,
        facts: Map<ReaderPageId, ReaderPageFact>,
        forced: Set<ReaderPageId>,
        manualPairStarts: Set<Int>,
        distance: (ReaderPageId, ReaderPageId) -> Double?,
    ): List<ReaderDualPageUnit> = SharedDualPageRules.canonicalPlan(
        pages = pages,
        facts = facts,
        direction = ReaderDirection.LTR,
        forcedSinglePages = forced,
        manualPairStarts = manualPairStarts,
        openingMode = ReaderChapterOpeningMode.AUTO,
        automatic = true,
        distance = distance,
    )

    /**
     * The real owner capacity, a chapter longer than that capacity.
     *
     * The two pages before the target are accepted first, so their edge
     * exists. Sixteen further pages then evict them, and the last page is
     * accepted alone: the side scalars and the stored edge survive the
     * eviction, but the edge of the final pair was never computed.
     */
    @Test
    fun `the default capacity reaches the target without the unreachable edge`() {
        val pages = pages(19)
        val identity = identityOf(pages)
        val originals = pages.associateWith { page ->
            if (page == pages[15]) analysis(rightColor = white) else analysis()
        }
        val facts = originals.mapValues { it.value.first }
        val forced = setOf(pages[14])
        val owner = ReaderSpreadSamplesOwner(identity)
        try {
            val order = listOf(16, 17) + (0..15).toList() + listOf(18)
            order.forEach { index ->
                val (fact, samples) = originals.getValue(pages[index])
                check(owner.acceptOriginal(owner.request(pages[index]), fact, samples.edges))
            }
            val result = ReaderPairingCertifier.certify(
                identity,
                facts,
                owner,
                settings(forced = forced),
                pages[17],
            )
            val certified = result as ReaderPairingCertification.Certified
            val oracle = completePlan(
                pages,
                facts,
                forced,
                emptySet(),
                pairDistance(identity, originals),
            ).single { unit -> pages[17] in unit.pageIds }
            assertEquals(oracle, certified.segment.unit, "The certified unit must equal the complete plan")
            assertTrue(
                certified.segment.isCurrent(identity, settings(forced = forced), owner, facts),
                "The reused certificate must still be current for the same input",
            )
        } finally {
            owner.close()
        }
    }

    /** A wide page as the target: the certifier never enumerates the shifted entry there. */
    @Test
    fun `a wide page target never reaches the shifted entry`() {
        val pages = pages(6)
        val identity = identityOf(pages)
        val originals = pages.associateWith { page ->
            when (page) {
                pages[2] -> analysis(rightColor = white)
                pages[4] -> analysis(width = 512, height = 384)
                else -> analysis()
            }
        }
        val facts = originals.mapValues { it.value.first }
        val forced = setOf(pages[1])
        val owner = ReaderSpreadSamplesOwner(identity, sampleCapacity = 2)
        val oracleOwner = ReaderSpreadSamplesOwner(identity)
        try {
            listOf(0, 3, 4, 2, 1, 5).forEach { index ->
                val (fact, samples) = originals.getValue(pages[index])
                check(owner.acceptOriginal(owner.request(pages[index]), fact, samples.edges))
                check(oracleOwner.acceptOriginal(oracleOwner.request(pages[index]), fact, samples.edges))
            }
            val result = ReaderPairingCertifier.certify(
                identity,
                facts,
                owner,
                settings(forced = forced),
                pages[4],
            )
            val certified = result as ReaderPairingCertification.Certified
            val oracle = completePlan(
                pages,
                facts,
                forced,
                emptySet(),
                { first, second ->
                    (
                        oracleOwner.edgeAnalysis(first, second, ReaderDirection.LTR)
                            as? ReaderEdgeAnalysisEvidence.Computed
                        )?.score
                },
            ).single { unit -> pages[4] in unit.pageIds }
            assertEquals(oracle, certified.segment.unit, "The certified unit must equal the complete plan")
            assertEquals(
                listOf(pages[4]),
                certified.segment.unit.pageIds,
                "A wide page is certified on its own",
            )
        } finally {
            owner.close()
            oracleOwner.close()
        }
    }

    /**
     * An explicit manual pair start that overlaps the forced boundary.
     *
     * A forced page is a hard boundary for the scan, so the manual request for
     * the pair that touches it is void and the shifted entry stays unreachable.
     */
    @Test
    fun `an explicit manual request cannot revive the pruned entry`() {
        val pages = pages(6)
        val identity = identityOf(pages)
        val originals = pages.associateWith { page ->
            if (page == pages[2]) analysis(rightColor = white) else analysis()
        }
        val facts = originals.mapValues { it.value.first }
        val forced = setOf(pages[1])
        val owner = ReaderSpreadSamplesOwner(identity, sampleCapacity = 2)
        val oracleOwner = ReaderSpreadSamplesOwner(identity)
        try {
            listOf(0, 3, 4, 2, 1, 5).forEach { index ->
                val (fact, samples) = originals.getValue(pages[index])
                check(owner.acceptOriginal(owner.request(pages[index]), fact, samples.edges))
                check(oracleOwner.acceptOriginal(oracleOwner.request(pages[index]), fact, samples.edges))
            }
            val manual = setOf(0)
            val result = ReaderPairingCertifier.certify(
                identity,
                facts,
                owner,
                settings(forced = forced, manualPairStarts = manual),
                pages[4],
            )
            val certified = result as ReaderPairingCertification.Certified
            val oracle = completePlan(
                pages,
                facts,
                forced,
                manual,
                { first, second ->
                    (
                        oracleOwner.edgeAnalysis(first, second, ReaderDirection.LTR)
                            as? ReaderEdgeAnalysisEvidence.Computed
                        )?.score
                },
            ).single { unit -> pages[4] in unit.pageIds }
            assertEquals(oracle, certified.segment.unit, "The certified unit must equal the complete plan")
        } finally {
            owner.close()
            oracleOwner.close()
        }
    }

    /** Without a forced boundary the same manual request keeps the entry reachable. */
    @Test
    fun `without a forced boundary a manual request keeps the entry reachable`() {
        val pages = pages(6)
        val identity = identityOf(pages)
        val originals = pages.associateWith { page ->
            if (page == pages[2]) analysis(rightColor = white) else analysis()
        }
        val facts = originals.mapValues { it.value.first }
        val owner = ReaderSpreadSamplesOwner(identity, sampleCapacity = 2)
        try {
            listOf(0, 3, 4, 2, 1, 5).forEach { index ->
                val (fact, samples) = originals.getValue(pages[index])
                check(owner.acceptOriginal(owner.request(pages[index]), fact, samples.edges))
            }
            // The user asks for the pair that consumes the page before the target.
            val result = ReaderPairingCertifier.certify(
                identity,
                facts,
                owner,
                settings(manualPairStarts = setOf(1)),
                pages[4],
            )
            val needs = (result as ReaderPairingCertification.NeedFacts).facts
            assertTrue(
                needs.any { need ->
                    need.kind == ReaderPairingFactKind.EDGE_SAMPLES &&
                        need.pageId == pages[4] && need.secondPageId == pages[5]
                },
                "The reachable entry must still request the edge it can need: $needs",
            )
        } finally {
            owner.close()
        }
    }
}
