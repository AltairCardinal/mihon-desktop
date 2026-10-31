package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPairingPrefetchPlanTest {
    private val chapterId = ReaderChapterId(1L)
    private val pages = List(25) { ReaderPageId(chapterId, it) }
    private val portrait = ReaderPageFact.dimensions(980, 1403)

    private fun computed(pageId: ReaderPageId) = ReaderPageAnalysisEvidence.Computed(
        ReaderDualPageContentIdentity(pageId, "v1"),
        portrait,
    )

    @Test
    fun `classification keeps true missing facts separate from cancellable lookahead including page zero`() {
        val certification = ReaderPairingCertification.NeedFacts(
            setOf(ReaderPairingFactNeed(pages[2], ReaderPairingFactKind.ORIGINAL_ANALYSIS)),
            continuationFirstIndex = 2,
            scanSteps = 1,
        )
        val classified = ReaderPairingPrefetchPlan.classify(
            certification,
            pages,
            { ReaderPageAnalysisEvidence.NotAnalyzed },
            4,
        )
        assertEquals(listOf(pages[2]), classified.realMissing)
        assertEquals(listOf(pages[1], pages[0]), classified.speculative)
        assertEquals(
            classified.realMissing + classified.speculative,
            ReaderPairingPrefetchPlan.plan(certification, pages, { ReaderPageAnalysisEvidence.NotAnalyzed }, 4),
        )
        val opening = ReaderPairingPrefetchPlan.classify(
            certification.copy(facts = setOf(ReaderPairingFactNeed(pages[0], ReaderPairingFactKind.ORIGINAL_ANALYSIS))),
            pages,
            { ReaderPageAnalysisEvidence.NotAnalyzed },
            4,
        )
        assertEquals(listOf(pages[0]), opening.realMissing)
        assertTrue(pages[0] !in opening.speculative)
    }

    @Test
    fun `edge sample demand admits only uncomputed endpoints when a peer is computed`() {
        val certification = ReaderPairingCertification.NeedFacts(
            setOf(ReaderPairingFactNeed(pages[24], ReaderPairingFactKind.EDGE_SAMPLES, pages[23])),
            continuationFirstIndex = 24,
            scanSteps = 1,
        )

        val classified = ReaderPairingPrefetchPlan.classify(
            certification,
            pages,
            { id -> if (id == pages[23]) computed(id) else ReaderPageAnalysisEvidence.NotAnalyzed },
            0,
        )

        assertEquals(listOf(pages[24]), classified.realMissing)
        assertTrue(classified.speculative.isEmpty())
    }

    @Test
    fun `edge sample demand retains both computed endpoints when recovery is missing`() {
        val certification = ReaderPairingCertification.NeedFacts(
            setOf(ReaderPairingFactNeed(pages[1], ReaderPairingFactKind.EDGE_SAMPLES, pages[2])),
            continuationFirstIndex = 2,
            scanSteps = 1,
        )

        val classified = ReaderPairingPrefetchPlan.classify(
            certification,
            pages,
            ::computed,
            0,
        )

        assertEquals(listOf(pages[1], pages[2]), classified.realMissing)
    }

    @Test
    fun `non edge fact needs stay authoritative for a computed endpoint`() {
        val certification = ReaderPairingCertification.NeedFacts(
            setOf(ReaderPairingFactNeed(pages[23], ReaderPairingFactKind.DIMENSIONS)),
            continuationFirstIndex = 24,
            scanSteps = 1,
        )

        val classified = ReaderPairingPrefetchPlan.classify(
            certification,
            pages,
            ::computed,
            0,
        )

        assertEquals(listOf(pages[23]), classified.realMissing)
    }

    @Test
    fun `plan puts real missing needs first followed by k unanalyzed prefix pages`() {
        val need = ReaderPairingFactNeed(pages[10], ReaderPairingFactKind.ORIGINAL_ANALYSIS)
        val needFacts = ReaderPairingCertification.NeedFacts(
            setOf(need),
            continuationFirstIndex = 10,
            scanSteps = 1,
        )
        val plan = ReaderPairingPrefetchPlan.plan(
            certification = needFacts,
            pageIds = pages,
            analysisStatus = { ReaderPageAnalysisEvidence.NotAnalyzed },
            lookaheadK = 3,
        )
        val expected = listOf(pages[10], pages[9], pages[8], pages[7])
        assertEquals(expected, plan)
    }

    @Test
    fun `plan bounds at index zero without negative indices`() {
        val need = ReaderPairingFactNeed(pages[1], ReaderPairingFactKind.ORIGINAL_ANALYSIS)
        val needFacts = ReaderPairingCertification.NeedFacts(
            setOf(need),
            continuationFirstIndex = 1,
            scanSteps = 1,
        )
        val plan = ReaderPairingPrefetchPlan.plan(
            certification = needFacts,
            pageIds = pages,
            analysisStatus = { ReaderPageAnalysisEvidence.NotAnalyzed },
            lookaheadK = 4,
        )
        val expected = listOf(pages[1], pages[0])
        assertEquals(expected, plan)
    }

    @Test
    fun `plan skips pages already computed or with failures`() {
        val need = ReaderPairingFactNeed(pages[10], ReaderPairingFactKind.ORIGINAL_ANALYSIS)
        val needFacts = ReaderPairingCertification.NeedFacts(
            setOf(need),
            continuationFirstIndex = 10,
            scanSteps = 1,
        )
        val content = ReaderDualPageContentIdentity(pages[9], "v1")
        val plan = ReaderPairingPrefetchPlan.plan(
            certification = needFacts,
            pageIds = pages,
            analysisStatus = { id ->
                when (id.sourcePageIndex) {
                    9 -> ReaderPageAnalysisEvidence.Computed(content, portrait)
                    8 -> ReaderPageAnalysisEvidence.RetryableFailure(content, ReaderAnalysisFailure.RESOURCE)
                    else -> ReaderPageAnalysisEvidence.NotAnalyzed
                }
            },
            lookaheadK = 4,
            failedPages = setOf(pages[7]),
        )
        // 9 is Computed, 8 is RetryableFailure, 7 is in failedPages -> only 6 is accepted
        val expected = listOf(pages[10], pages[6])
        assertEquals(expected, plan)
    }

    @Test
    fun `plan deduplicates when speculative page is already present in real needs`() {
        val need1 = ReaderPairingFactNeed(pages[10], ReaderPairingFactKind.ORIGINAL_ANALYSIS)
        val need2 = ReaderPairingFactNeed(pages[9], ReaderPairingFactKind.ORIGINAL_ANALYSIS)
        val needFacts = ReaderPairingCertification.NeedFacts(
            setOf(need1, need2),
            continuationFirstIndex = 10,
            scanSteps = 1,
        )
        val plan = ReaderPairingPrefetchPlan.plan(
            certification = needFacts,
            pageIds = pages,
            analysisStatus = { ReaderPageAnalysisEvidence.NotAnalyzed },
            lookaheadK = 2,
        )
        val expected = listOf(pages[10], pages[9], pages[8])
        assertEquals(expected, plan)
    }

    @Test
    fun `speculative facts admission preserves oracle equivalence and does not alter converged certification`() {
        val chapterId = ReaderChapterId(100L)
        val chapterPages = List(15) { ReaderPageId(chapterId, it) }
        val identity = ReaderDualPageIdentity(
            1,
            1,
            chapterPages.map { ReaderDualPageContentIdentity(it, "v1") },
        )
        val target = chapterPages[8]
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, target)
        session.configure(ReaderDirection.RTL, automaticMatching = true)
        chapterPages.forEach { session.acceptFact(session.requestFact(it), portrait) }

        val white = ReaderSpreadEdgeSamples(List(50) { -1 }, List(50) { -1 })
        val samples = ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, white)

        val initialCert = session.certifyPairing(target)
        assertTrue(initialCert is ReaderPairingCertification.NeedFacts)

        val plan = ReaderPairingPrefetchPlan.plan(
            certification = initialCert,
            pageIds = chapterPages,
            analysisStatus = session::originalAnalysis,
            lookaheadK = 4,
        )
        // Admitting speculative facts
        plan.forEach { id ->
            session.requestOriginalAnalysis(id)?.let { req ->
                session.acceptOriginalAnalysis(req, portrait, samples)
            }
        }

        // Complete remaining needs until certified
        var cert = session.certifyPairing(target)
        var rounds = 0
        while (cert !is ReaderPairingCertification.Certified && rounds++ < 20) {
            when (cert) {
                is ReaderPairingCertification.NeedFacts -> {
                    cert.facts.forEach { need ->
                        val id = need.pageId
                        session.requestOriginalAnalysis(id)?.let { req ->
                            session.acceptOriginalAnalysis(req, portrait, samples)
                        }
                    }
                }
                else -> error("Unexpected certification state: $cert")
            }
            cert = session.certifyPairing(target)
        }
        assertTrue(cert is ReaderPairingCertification.Certified, "Must converge within 20 rounds, but got: $cert")
        val certifiedUnit = (cert as ReaderPairingCertification.Certified).segment.unit

        val oracle = SharedDualPageRules.canonicalPlan(
            chapterPages,
            chapterPages.associateWith { portrait },
            ReaderDirection.RTL,
            automatic = true,
        ).single { target in it.pageIds }
        assertEquals(oracle, certifiedUnit, "Converged unit must equal canonical oracle")

        // Extra speculative facts after convergence must not alter certified unit
        val extra = chapterPages[1]
        session.requestOriginalAnalysis(extra)?.let { req ->
            session.acceptOriginalAnalysis(req, portrait, samples)
        }
        val afterExtra = session.certifyPairing(target)
        assertTrue(afterExtra is ReaderPairingCertification.Certified)
        assertEquals(certifiedUnit, (afterExtra as ReaderPairingCertification.Certified).segment.unit)
    }
}
