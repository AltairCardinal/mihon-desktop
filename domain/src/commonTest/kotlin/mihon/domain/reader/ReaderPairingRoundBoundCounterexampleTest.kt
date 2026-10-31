package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M0 engineering mapping of the round-bound counterexample.
 *
 * The mathematical answer claims that a legal cached state - a gray-spine page whose side
 * hint stays UNKNOWN, and an edge that is computed without a matching score - lets the
 * publisher complete one dependency at a time, so the counting rounds are not bounded by
 * ceil(D/k)+2. This test pins the three engineering facts the memo interface, the
 * invalidation table and the threshold ruling depend on:
 *
 *  1. those states are reachable through the real session acceptance path;
 *  2. the existing positive certificate validator accepts an unchanged dependency set and
 *     rejects every single dependency change;
 *  3. a pending (NeedFacts) result carries no value-level dependency snapshot, so a reuse
 *     entry for a pending result must record the complete read set itself.
 *
 * No owner substitution, no cache clearing and no pairing algorithm copy is used: every
 * mutation goes through the production session acceptance path.
 */
class ReaderPairingRoundBoundCounterexampleTest {
    private val portrait = ReaderPageFact.dimensions(980, 1403)

    /** Both edges are white: the samples exist but carry no match, and the score stays null. */
    private val whiteEdges = ReaderSpreadEdgeSamples(List(50) { -1 }, List(50) { -1 })

    /** Matching textured edges: the same samples on every page produce a real distance. */
    private val texturedEdges = ReaderSpreadEdgeSamples(
        List(50) { if (it % 2 == 0) 0xFF102030.toInt() else 0xFFE0D0C0.toInt() },
        List(50) { if (it % 2 == 0) 0xFF102030.toInt() else 0xFFE0D0C0.toInt() },
    )

    private fun pages(count: Int) = List(count) { ReaderPageId(ReaderChapterId(9131), it) }

    private fun identityOf(pages: List<ReaderPageId>) = ReaderDualPageIdentity(
        1L,
        1L,
        pages.map { ReaderDualPageContentIdentity(it, "content-$it") },
    )

    /** Reads the real owner solely to observe evidence; every mutation uses the session API. */
    private fun readOwner(session: SharedDualPageSession): ReaderSpreadSamplesOwner =
        SharedDualPageSession::class.java.getDeclaredField("spreadSamples")
            .apply { isAccessible = true }.get(session) as ReaderSpreadSamplesOwner

    private fun openSession(
        pages: List<ReaderPageId>,
        identity: ReaderDualPageIdentity = identityOf(pages),
    ): SharedDualPageSession = SharedDualPageSession(identity, ReaderDirection.RTL, pages[0]).also { session ->
        session.configure(ReaderDirection.RTL, automaticMatching = true)
    }

    private fun acceptHeaders(session: SharedDualPageSession, pages: List<ReaderPageId>) {
        pages.forEach { page ->
            assertTrue(session.acceptFact(session.requestFact(page), portrait), "Header fact for $page")
        }
    }

    private fun acceptOriginal(
        session: SharedDualPageSession,
        page: ReaderPageId,
        side: ReaderPageSideHint,
        edges: ReaderSpreadEdgeSamples,
    ) {
        assertTrue(
            session.acceptOriginalAnalysis(
                session.requestOriginalAnalysis(page)!!,
                portrait,
                ReaderOriginalPageSamples(side, edges),
            ),
            "Original analysis for $page",
        )
    }

    @Test
    fun `gray spine and computed without a matching edge are reachable through the real path`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            acceptHeaders(session, pages)
            pages.forEach { acceptOriginal(session, it, ReaderPageSideHint.UNKNOWN, whiteEdges) }

            pages.forEach { page ->
                val evidence = session.originalAnalysis(page)
                assertTrue(evidence is ReaderPageAnalysisEvidence.Computed, "Computed analysis for $page")
                assertEquals(
                    ReaderPageSideHint.UNKNOWN,
                    (evidence as ReaderPageAnalysisEvidence.Computed).fact.sideHint,
                    "A gray spine keeps the UNKNOWN side hint: $page",
                )
            }

            val owner = readOwner(session)
            val edge = owner.edgeAnalysis(pages[4], pages[5], ReaderDirection.RTL)
            assertTrue(edge is ReaderEdgeAnalysisEvidence.Computed, "The edge evidence itself exists")
            assertNull(
                (edge as ReaderEdgeAnalysisEvidence.Computed).score,
                "A gray spine produces a computed edge without a matching score",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `the existing certificate validator rejects every single dependency change`() {
        val pages = pages(12)
        val identity = identityOf(pages)
        val session = openSession(pages, identity)
        val headers = mapOf(pages[0] to portrait, pages[1] to portrait, pages[2] to portrait, pages[3] to portrait)
        try {
            acceptHeaders(session, pages)
            // pages 1 and 2 form a leading/trailing pair; pages 3..5 stay gray spines so the
            // scan must compare edges before it can decide the target unit.
            acceptOriginal(session, pages[1], ReaderPageSideHint.RIGHT, texturedEdges)
            acceptOriginal(session, pages[2], ReaderPageSideHint.LEFT, texturedEdges)
            listOf(3, 4, 5).forEach { acceptOriginal(session, pages[it], ReaderPageSideHint.UNKNOWN, whiteEdges) }

            val owner = readOwner(session)
            // The session derives its settings from the pairing restore state. The certificate
            // snapshots exactly that value, so the validator must be given the same derivation
            // instead of a hand-built default: `manualRevision` is `revision ?: -1`, not 0.
            val pairingState = session.pairingState
            val settings = ReaderPairingSettings(
                direction = ReaderDirection.RTL,
                automatic = true,
                forcedSinglePages = emptySet(),
                manualPairStarts = pairingState.manualPairStarts,
                openingMode = pairingState.openingMode,
                manualRevision = pairingState.revision ?: -1,
            )
            val certified = session.certifyPairing(pages[3]) as ReaderPairingCertification.Certified
            val segment = certified.segment
            assertTrue(
                segment.isCurrent(identity, settings, owner, headers),
                "An unchanged dependency set stays current",
            )

            val changedContent = identity.copy(
                content = identity.content.map {
                    if (it.pageId == pages[3]) it.copy(contentVersion = "replaced") else it
                },
            )
            assertTrue(
                !segment.isCurrent(changedContent, settings, owner, headers),
                "A replaced content version must reject the certificate",
            )
            val changedRotation = identity.copy(
                content = identity.content.map {
                    if (it.pageId == pages[3]) it.copy(rotation = PageRotation.CLOCKWISE_90) else it
                },
            )
            assertTrue(
                !segment.isCurrent(changedRotation, settings, owner, headers),
                "A changed rotation must reject the certificate",
            )
            assertTrue(
                !segment.isCurrent(
                    identity,
                    settings.copy(direction = ReaderDirection.LTR),
                    owner,
                    headers,
                ),
                "A different reading direction must reject the certificate",
            )

            val closedOwner = ReaderSpreadSamplesOwner(identity)
            closedOwner.close()
            assertTrue(
                !segment.isCurrent(identity, settings, closedOwner, headers),
                "A closed owner must reject the certificate",
            )

            val emptyOwner = ReaderSpreadSamplesOwner(identity)
            try {
                assertTrue(
                    !segment.isCurrent(identity, settings, emptyOwner, headers),
                    "A lost analysis must reject the certificate",
                )
            } finally {
                emptyOwner.close()
            }

            val nextGeneration = identity.copy(generation = identity.generation + 1)
            assertTrue(
                !segment.isCurrent(nextGeneration, settings, owner, headers),
                "A new generation must reject the certificate",
            )

            // A certified segment only ever reads the header for a page whose analysis is not
            // computed, and every scanned page of a certified result has a computed analysis.
            // The header map is therefore not a dependency of this certificate at all.
            assertTrue(
                segment.isCurrent(
                    identity,
                    settings,
                    owner,
                    headers + (pages[3] to portrait.copy(width = 800, height = 1200)),
                ),
                "A certified result never reads the header, so the header is not its dependency",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `a pending result carries no value level dependency snapshot`() {
        val pages = pages(12)

        fun pendingState(
            edges: ReaderSpreadEdgeSamples,
        ): Pair<ReaderPairingCertificationTrace, ReaderSpreadSamplesOwner> {
            val session = openSession(pages)
            acceptHeaders(session, pages)
            // Pages 1 and 2 are gray spines with equal strength, so the scan must compare the
            // edge between them. The target page 3 has a header but no analysis yet.
            acceptOriginal(session, pages[1], ReaderPageSideHint.UNKNOWN, edges)
            acceptOriginal(session, pages[2], ReaderPageSideHint.UNKNOWN, edges)
            acceptOriginal(session, pages[4], ReaderPageSideHint.UNKNOWN, edges)
            acceptOriginal(session, pages[5], ReaderPageSideHint.UNKNOWN, edges)
            val trace = session.certifyPairingWithDependencies(pages[3])
            val owner = readOwner(session)
            return trace to owner
        }

        val (whiteTrace, whiteOwner) = pendingState(whiteEdges)
        val (texturedTrace, texturedOwner) = pendingState(texturedEdges)
        try {
            assertTrue(
                whiteTrace.certification is ReaderPairingCertification.NeedFacts,
                "A missing target analysis stays pending",
            )
            assertEquals(
                whiteTrace.certification,
                texturedTrace.certification,
                "Both pending states report the same facts and the same continuation",
            )
            assertEquals(
                whiteTrace.dependentPageIds,
                texturedTrace.dependentPageIds,
                "Both pending states report the same dependency pages",
            )

            val whiteEdge = whiteOwner.edgeAnalysis(pages[1], pages[2], ReaderDirection.RTL)
            val texturedEdge = texturedOwner.edgeAnalysis(pages[1], pages[2], ReaderDirection.RTL)
            assertTrue(whiteEdge is ReaderEdgeAnalysisEvidence.Computed, "The white edge is computed")
            assertTrue(texturedEdge is ReaderEdgeAnalysisEvidence.Computed, "The textured edge is computed")
            assertNull((whiteEdge as ReaderEdgeAnalysisEvidence.Computed).score, "The white edge has no score")
            assertNotEquals(
                (texturedEdge as ReaderEdgeAnalysisEvidence.Computed).score,
                (whiteEdge as ReaderEdgeAnalysisEvidence.Computed).score,
                "The underlying edge evidence really differs between the two pending states",
            )
        } finally {
            whiteOwner.close()
            texturedOwner.close()
        }
    }
}
