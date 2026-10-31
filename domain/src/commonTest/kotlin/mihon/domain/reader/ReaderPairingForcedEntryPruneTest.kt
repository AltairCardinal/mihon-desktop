package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A forced single page is a hard boundary: the real chapter scan can never let
 * the left neighbour pair that page away. The finite entry list of the
 * certifier still contained the "(first + 1)" entry, which asks for an edge
 * that no real scan can need, and that request could make the target stay
 * pending for a fact that does not change the target.
 */
class ReaderPairingForcedEntryPruneTest {
    private val gray = 0xFF808080.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun pages(count: Int) = List(count) { ReaderPageId(ReaderChapterId(9131), it) }

    private fun identityOf(pages: List<ReaderPageId>) = ReaderDualPageIdentity(
        1L,
        1L,
        pages.map { ReaderDualPageContentIdentity(it, "content-$it") },
    )

    private val portrait = ReaderPageFact.dimensions(256, 384)

    /** The real sampler turns the pixels of this page into a LEFT side hint. */
    private fun samplesFor(leftColor: Int, rightColor: Int): ReaderOriginalPageSamples {
        val plan = ReaderOriginalPageSampling.plan(256, 384)!!
        return plan.samples(
            List(plan.left.size) { leftColor },
            List(plan.right.size) { rightColor },
        )
    }

    private fun buildOwner(
        identity: ReaderDualPageIdentity,
        pages: List<ReaderPageId>,
        capacity: Int,
    ): ReaderSpreadSamplesOwner {
        val owner = ReaderSpreadSamplesOwner(identity, capacity)
        // Page 2 is white on its right side, so its real side hint is LEFT.
        val order = listOf(0, 3, 4, 2, 1, 5)
        order.forEach { index ->
            val page = pages[index]
            val samples = if (index == 2) {
                samplesFor(gray, white)
            } else {
                samplesFor(gray, gray)
            }
            val request = owner.request(page)
            val fact = portrait.copy(sideHint = samples.sideHint)
            check(owner.acceptOriginal(request, fact, samples.edges)) { "accept $page" }
        }
        return owner
    }

    private fun settings(forced: Set<ReaderPageId>) = ReaderPairingSettings(
        ReaderDirection.LTR,
        automatic = true,
        forcedSinglePages = forced,
        manualPairStarts = emptySet(),
        openingMode = ReaderChapterOpeningMode.AUTO,
        manualRevision = 0L,
    )

    /** The complete plan of the same facts, with every sample retained. */
    private fun completePlan(
        pages: List<ReaderPageId>,
        identity: ReaderDualPageIdentity,
        forced: Set<ReaderPageId>,
    ): List<ReaderDualPageUnit> {
        val owner = buildOwner(identity, pages, capacity = 16)
        val facts = pages.associateWith { page ->
            val analysis = owner.pageAnalysis(page) as ReaderPageAnalysisEvidence.Computed
            analysis.fact
        }
        return SharedDualPageRules.canonicalPlan(
            pages = pages,
            facts = facts,
            direction = ReaderDirection.LTR,
            forcedSinglePages = forced,
            manualPairStarts = emptySet(),
            openingMode = ReaderChapterOpeningMode.AUTO,
            automatic = true,
            distance = { first, second ->
                (owner.edgeAnalysis(first, second, ReaderDirection.LTR) as? ReaderEdgeAnalysisEvidence.Computed)?.score
            },
        )
    }

    @Test
    fun `a forced left neighbour makes the first plus one entry unreachable`() {
        val pages = pages(6)
        val identity = identityOf(pages)
        val forced = setOf(pages[1])
        val owner = buildOwner(identity, pages, capacity = 2)
        val headers = pages.associateWith { portrait }

        // The real scan cannot let page 1 pair page 2 away, so the edge between
        // pages 4 and 5 is never needed for the target.
        val result = ReaderPairingCertifier.certifyWithDependencies(
            identity,
            headers,
            owner,
            settings(forced),
            pages[4],
        )
        val certified = result.certification as ReaderPairingCertification.Certified
        val oracle = completePlan(pages, identity, forced).single { unit -> pages[4] in unit.pageIds }
        assertEquals(oracle, certified.segment.unit, "The certified unit must equal the complete plan")
        assertTrue(
            certified.segment.isCurrent(
                identity,
                settings(forced),
                owner,
                headers,
            ),
            "The reused certificate must still be current for the same input",
        )
    }

    @Test
    fun `without a forced boundary the same state still asks for the missing edge`() {
        val pages = pages(6)
        val identity = identityOf(pages)
        val owner = buildOwner(identity, pages, capacity = 2)
        val headers = pages.associateWith { portrait }

        // Nothing is forced here, so the left neighbour may still pair page 2
        // away and the entry that compares both edges stays reachable.
        val result = ReaderPairingCertifier.certifyWithDependencies(
            identity,
            headers,
            owner,
            settings(emptySet()),
            pages[4],
        )
        val needs = (result.certification as ReaderPairingCertification.NeedFacts).facts
        assertTrue(
            needs.any { need ->
                need.kind == ReaderPairingFactKind.EDGE_SAMPLES &&
                    need.pageId == pages[4] && need.secondPageId == pages[5]
            },
            "The reachable entry must still request the edge it can need: $needs",
        )
    }
}
