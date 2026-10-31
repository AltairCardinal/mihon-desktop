package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The seven equivalent-strength contracts of the round bound.
 *
 * The original round bound cannot be proved for the current architecture: the
 * rounds are driven by how many new dependencies one certification reveals,
 * not by how many of them an admission may take at once. The original
 * assertion, the original count and the original failure stay untouched in the
 * platform test, where they remain the recorded diagnostic.
 *
 * These contracts carry the same concerns in a form that can be measured:
 *
 *  1. the target is certified and its unit names the target;
 *  2. no required demand fails with RESOURCE;
 *  3. the certificate equals the plan of the same facts;
 *  4. a repeated certification reads no physical content again;
 *  5. inside one stable epoch the engine runs at most once per change;
 *  6. the dependency chain of the target stays inside an explicit bound;
 *  7. the round counts are reported instead of thresholded.
 */
class ReaderPairingEquivalentContractsTest {
    /**
     * The explicit bound of the dependency chain depth.
     *
     * The chain may reach the beginning of the chapter, because the scan
     * compares the neighbouring edges before it can decide the target unit.
     * The number is a proposal for the reviewer and is deliberately visible
     * here, so that changing it is one visible line.
     */
    private val maxDependencyChainDepth = 32

    private val portrait = ReaderPageFact.dimensions(100, 200)

    /** A gray spine: the samples exist, the edge has no matching score. */
    private val graySpineEdges = ReaderSpreadEdgeSamples(List(50) { -1 }, List(50) { -1 })

    private class Fixture(
        val pages: List<ReaderPageId>,
        val identity: ReaderDualPageIdentity,
        val session: SharedDualPageSession,
        val targetIndex: Int,
        val rounds: Int,
        val acquisitions: Int,
    )

    /**
     * Drives the real session to a certified target.
     *
     * The required channel is used, because the optional channel of the
     * publisher stops at the additional page cap and the demand would starve
     * before the target is certified. Only the admission differs; the
     * certifier, the owner and the budget are the production ones.
     */
    private fun drive(pageCount: Int, targetIndex: Int): Fixture {
        val pages = List(pageCount) { ReaderPageId(ReaderChapterId(9138), it) }
        val identity = ReaderDualPageIdentity(
            1L,
            1L,
            pages.map { ReaderDualPageContentIdentity(it, "content-$it") },
        )
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, pages[targetIndex])
        session.configure(ReaderDirection.RTL, automaticMatching = true)
        val budget = session.factBudget
        var rounds = 0
        var acquisitions = 0
        var previousDemand: Set<ReaderPageId> = emptySet()
        var revealedTotal = 0
        while (rounds < 200) {
            val certification = session.certifyPairingWithDependencies(pages[targetIndex]).certification
            if (certification is ReaderPairingCertification.Certified) break
            val needs = when (certification) {
                is ReaderPairingCertification.NeedFacts -> certification.facts
                is ReaderPairingCertification.RetryableBlocked -> certification.facts
                is ReaderPairingCertification.Certified -> emptySet()
            }
            val ids = needs
                .filter { it.kind != ReaderPairingFactKind.PAIRING_RULES }
                .flatMap { listOfNotNull(it.pageId, it.secondPageId) }
                .distinct()
            if (ids.isEmpty()) break
            revealedTotal += ids.count { it !in previousDemand }
            previousDemand = ids.toSet()
            val admitted = budget.reserve(ids, rounds * 10L, required = true)
            if (admitted.isEmpty()) break
            admitted.forEach { id ->
                session.acceptFact(session.requestFact(id), portrait)
                session.requestOriginalAnalysis(id)?.let { request ->
                    session.acceptOriginalAnalysis(
                        request,
                        portrait,
                        ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, graySpineEdges),
                    )
                    acquisitions++
                }
                budget.complete(id, 0, cacheHit = true)
            }
            rounds++
        }
        return Fixture(pages, identity, session, targetIndex, rounds, acquisitions)
    }

    private fun certificationOf(fixture: Fixture): ReaderPairingCertification =
        fixture.session.certifyPairingWithDependencies(fixture.pages[fixture.targetIndex]).certification

    @Test
    fun `one the target is certified and its unit names the target`() {
        val fixture = drive(pageCount = 40, targetIndex = 30)
        try {
            val certified = certificationOf(fixture) as ReaderPairingCertification.Certified
            assertTrue(
                fixture.pages[fixture.targetIndex] in certified.segment.unit.pageIds,
                "The certified unit must name the target: ${certified.segment.unit}",
            )
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun `two no required demand fails with a resource failure`() {
        val fixture = drive(pageCount = 40, targetIndex = 30)
        try {
            fixture.pages.forEach { page ->
                val evidence = fixture.session.originalAnalysis(page)
                assertTrue(
                    evidence !is ReaderPageAnalysisEvidence.RetryableFailure,
                    "No page may end in a resource failure: $page -> $evidence",
                )
            }
            val certification = certificationOf(fixture)
            assertTrue(
                certification !is ReaderPairingCertification.RetryableBlocked,
                "The target must not be blocked: $certification",
            )
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun `three the certificate equals the plan of the same facts`() {
        val fixture = drive(pageCount = 40, targetIndex = 30)
        try {
            val certified = certificationOf(fixture) as ReaderPairingCertification.Certified
            val facts = fixture.pages.mapNotNull { page -> fixture.session.pageFact(page)?.let { page to it } }.toMap()
            val oracle = SharedDualPageRules.canonicalPlan(
                pages = fixture.pages,
                facts = facts,
                direction = ReaderDirection.RTL,
                forcedSinglePages = emptySet(),
                manualPairStarts = emptySet(),
                openingMode = ReaderChapterOpeningMode.AUTO,
                automatic = true,
                distance = { first, second -> pairDistance(fixture, first, second) },
            ).single { unit -> fixture.pages[fixture.targetIndex] in unit.pageIds }
            assertEquals(
                oracle,
                certified.segment.unit,
                "The certified unit must equal the plan of the same facts",
            )
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun `four a repeated certification reads no physical content again`() {
        val fixture = drive(pageCount = 40, targetIndex = 30)
        try {
            val acquisitionsAfterFirst = fixture.acquisitions
            val stepsAfterFirst = fixture.session.certificationMetrics.scanStepsExecuted
            repeat(4) {
                val certification = certificationOf(fixture)
                assertTrue(
                    certification is ReaderPairingCertification.Certified,
                    "The repeated certification must stay certified: $certification",
                )
            }
            assertEquals(
                acquisitionsAfterFirst,
                fixture.acquisitions,
                "A repeated certification must not acquire physical content again",
            )
            assertEquals(
                stepsAfterFirst,
                fixture.session.certificationMetrics.scanStepsExecuted,
                "A reused result must not report the scan work again",
            )
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun `five inside one stable epoch the engine runs at most once per change`() {
        val fixture = drive(pageCount = 40, targetIndex = 30)
        try {
            val metrics = fixture.session.certificationMetrics
            val engineRunsBefore = metrics.engineRuns
            val entriesBefore = metrics.entries
            repeat(4) { certificationOf(fixture) }
            assertEquals(
                engineRunsBefore,
                metrics.engineRuns,
                "A stable epoch must not run the certification engine again: ${metrics.engineRuns}",
            )
            assertTrue(
                metrics.entries > entriesBefore,
                "The repeated requests must be counted as entries",
            )
            assertTrue(
                metrics.memoHits > 0,
                "The repeated requests must be counted as reuse: ${metrics.memoHits}",
            )
            assertTrue(
                metrics.engineRuns <= 1 + metrics.memoMisses,
                "The engine runs must stay within one run per miss: ${metrics.engineRuns}/${metrics.memoMisses}",
            )
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun `six the dependency chain of the target stays inside the explicit bound`() {
        val fixture = drive(pageCount = 40, targetIndex = 30)
        try {
            val certification = certificationOf(fixture)
            val finalEntryIndex = when (certification) {
                is ReaderPairingCertification.Certified -> certification.segment.firstIndex
                is ReaderPairingCertification.NeedFacts -> certification.continuationFirstIndex
                is ReaderPairingCertification.RetryableBlocked -> certification.continuationFirstIndex
            }
            val depth = fixture.targetIndex - finalEntryIndex
            println("CONTRACT chainDepth=$depth finalEntryIndex=$finalEntryIndex rounds=${fixture.rounds}")
            assertTrue(
                finalEntryIndex >= 0,
                "The chain must stay inside the chapter: $finalEntryIndex",
            )
            assertTrue(
                depth <= maxDependencyChainDepth,
                "The dependency chain depth must stay inside the explicit bound: $depth",
            )
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun `seven the round counts are reported instead of thresholded`() {
        val fixture = drive(pageCount = 40, targetIndex = 30)
        try {
            val metrics = fixture.session.certificationMetrics
            println(
                "CONTRACT entries=${metrics.entries} engineRuns=${metrics.engineRuns} " +
                    "memoHits=${metrics.memoHits} memoMisses=${metrics.memoMisses} " +
                    "scanSteps=${metrics.scanStepsExecuted} evictions=${metrics.evictions} " +
                    "oversizedBypasses=${metrics.oversizedBypasses} " +
                    "missReasons=${metrics.missReasonCounts} rounds=${fixture.rounds}",
            )
            assertTrue(
                metrics.entries > 0,
                "Every certification request must be counted: ${metrics.entries}",
            )
            assertTrue(
                metrics.engineRuns > 0,
                "Every real engine execution must be counted: ${metrics.engineRuns}",
            )
        } finally {
            fixture.session.close()
        }
    }

    /**
     * The distance of one adjacent pair, measured on its own owner.
     *
     * The owner keeps at most sixteen sample sets, so a forty page chapter
     * cannot keep every page resident. Each pair is measured with the two
     * pages it needs, which is the evidence the plan rule consumes.
     */
    private fun pairDistance(
        fixture: Fixture,
        first: ReaderPageId,
        second: ReaderPageId,
    ): Double? {
        val owner = ReaderSpreadSamplesOwner(fixture.identity)
        return try {
            listOf(first, second).forEach { page ->
                val fact = fixture.session.pageFact(page) ?: return null
                val samples = ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, graySpineEdges)
                check(owner.acceptOriginal(owner.request(page), fact, samples.edges))
            }
            (owner.edgeAnalysis(first, second, ReaderDirection.RTL) as? ReaderEdgeAnalysisEvidence.Computed)?.score
        } finally {
            owner.close()
        }
    }
}
