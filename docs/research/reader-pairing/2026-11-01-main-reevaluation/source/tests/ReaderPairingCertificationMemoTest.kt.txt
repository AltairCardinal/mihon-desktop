package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the session-local certification reuse path.
 *
 * The reuse entry is allowed only while the complete observed input still
 * reads back exactly as it did. These tests use the real session acceptance
 * path; no owner substitution, no cache clearing and no pairing algorithm copy.
 */
class ReaderPairingCertificationMemoTest {
    private val portrait = ReaderPageFact.dimensions(980, 1403)
    private val whiteEdges = ReaderSpreadEdgeSamples(List(50) { -1 }, List(50) { -1 })
    private val texturedEdges = ReaderSpreadEdgeSamples(
        List(50) { if (it % 2 == 0) 0xFF102030.toInt() else 0xFFE0D0C0.toInt() },
        List(50) { if (it % 2 == 0) 0xFF102030.toInt() else 0xFFE0D0C0.toInt() },
    )

    private fun pages(count: Int) = List(count) { ReaderPageId(ReaderChapterId(4471), it) }

    private fun identityOf(pages: List<ReaderPageId>) = ReaderDualPageIdentity(
        1L,
        1L,
        pages.map { ReaderDualPageContentIdentity(it, "content-$it") },
    )

    private fun openSession(
        pages: List<ReaderPageId>,
        identity: ReaderDualPageIdentity = identityOf(pages),
        pageOrderLimit: Int = ReaderPairingMemoLimits.MAX_PAGE_ORDER,
        readKeyLimit: Int = ReaderPairingMemoLimits.MAX_READ_KEYS,
        memoCapacity: Int = ReaderPairingMemoLimits.DEFAULT_ENTRIES,
    ): SharedDualPageSession = SharedDualPageSession(
        identity,
        ReaderDirection.RTL,
        pages[0],
        null,
        pageOrderLimit,
        readKeyLimit,
        memoCapacity,
    ).also { session ->
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
        side: ReaderPageSideHint = ReaderPageSideHint.UNKNOWN,
        edges: ReaderSpreadEdgeSamples = whiteEdges,
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

    private fun readOwner(session: SharedDualPageSession): ReaderSpreadSamplesOwner =
        SharedDualPageSession::class.java.getDeclaredField("spreadSamples")
            .apply { isAccessible = true }.get(session) as ReaderSpreadSamplesOwner

    private fun readHeaders(session: SharedDualPageSession): Map<ReaderPageId, ReaderPageFact> =
        SharedDualPageSession::class.java.getDeclaredField("originalHeaders")
            .apply { isAccessible = true }.get(session) as Map<ReaderPageId, ReaderPageFact>

    /** The settings the session derives from its own pairing state. */
    private fun settingsOf(session: SharedDualPageSession) = ReaderPairingSettings(
        direction = ReaderDirection.RTL,
        automatic = true,
        forcedSinglePages = emptySet(),
        manualPairStarts = session.pairingState.manualPairStarts,
        openingMode = session.pairingState.openingMode,
        manualRevision = session.pairingState.revision ?: -1,
    )

    /** The direct, uncached certification used as the independent control. */
    private fun control(
        session: SharedDualPageSession,
        target: ReaderPageId,
        settings: ReaderPairingSettings = settingsOf(session),
    ): ReaderPairingCertificationTrace = ReaderPairingCertifier.certifyWithDependencies(
        session.identity,
        readHeaders(session),
        readOwner(session),
        settings,
        target,
    )

    @Test
    fun `A01 the same input reuses the engine result through both public session entries`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            acceptHeaders(session, pages)
            val target = pages[3]

            val first = session.certifyPairingWithDependencies(target)
            val engineRuns = session.certificationMetrics.engineRuns
            val scanSteps = session.certificationMetrics.scanStepsExecuted
            assertEquals(1L, engineRuns, "The first request must run the real engine")
            assertEquals(0L, session.certificationMetrics.memoHits)

            val second = session.certifyPairingWithDependencies(target)
            assertEquals(
                engineRuns,
                session.certificationMetrics.engineRuns,
                "A reused result must not run the engine again",
            )
            assertEquals(
                scanSteps,
                session.certificationMetrics.scanStepsExecuted,
                "A reused result must not add scanning work",
            )
            assertEquals(1L, session.certificationMetrics.memoHits)
            assertEquals(first.certification, second.certification)
            assertEquals(first.dependentPageIds, second.dependentPageIds)

            val third = session.certifyPairing(target)
            assertEquals(engineRuns, session.certificationMetrics.engineRuns)
            assertEquals(2L, session.certificationMetrics.memoHits)
            assertEquals(first.certification, third)

            val direct = control(session, target)
            assertEquals(direct.certification, first.certification, "The reused result must equal the uncached one")
            assertEquals(direct.dependentPageIds, first.dependentPageIds)
        } finally {
            session.close()
        }
    }

    @Test
    fun `A02 an analyzed page forces a miss and agrees with the uncached engine`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            acceptHeaders(session, pages)
            val target = pages[3]
            val before = session.certifyPairingWithDependencies(target)
            val engineRuns = session.certificationMetrics.engineRuns
            val misses = session.certificationMetrics.memoMisses

            acceptOriginal(session, target)

            val after = session.certifyPairingWithDependencies(target)
            assertEquals(
                engineRuns + 1,
                session.certificationMetrics.engineRuns,
                "An analyzed page must force a miss",
            )
            assertEquals(
                misses + 1,
                session.certificationMetrics.memoMisses,
                "The change must be recorded as exactly one miss",
            )
            assertNotEquals(before.certification, after.certification, "The result must reflect the new analysis")
            val direct = control(session, target)
            assertEquals(direct.certification, after.certification)
            assertEquals(direct.dependentPageIds, after.dependentPageIds)
        } finally {
            session.close()
        }
    }

    @Test
    fun `A02 a computed edge without a matching score is not merged with a missing edge`() {
        val pages = pages(12)
        val identity = identityOf(pages)

        fun edgeValueOf(acceptEndpoints: Boolean): ReaderEdgeAnalysisEvidence {
            val owner = ReaderSpreadSamplesOwner(identity)
            try {
                if (acceptEndpoints) {
                    listOf(pages[1], pages[2]).forEach { page ->
                        val request = owner.request(page)
                        assertTrue(owner.acceptOriginal(request, portrait, whiteEdges))
                    }
                }
                return owner.edgeAnalysis(pages[1], pages[2], ReaderDirection.RTL)
            } finally {
                owner.close()
            }
        }

        val missing = edgeValueOf(acceptEndpoints = false)
        val computed = edgeValueOf(acceptEndpoints = true)
        assertTrue(missing is ReaderEdgeAnalysisEvidence.NotAnalyzed, "The edge is missing without both endpoints")
        assertTrue(computed is ReaderEdgeAnalysisEvidence.Computed, "The edge is computed once both endpoints exist")
        assertNull(
            (computed as ReaderEdgeAnalysisEvidence.Computed).score,
            "A gray spine produces a computed edge without a matching score",
        )
        assertNotEquals(
            missing,
            computed,
            "A missing edge and a computed edge without a matching score must stay different",
        )

        val session = openSession(pages, identity)
        try {
            acceptHeaders(session, pages)
            val target = pages[3]
            val before = session.certifyPairingWithDependencies(target)
            var engineRuns = session.certificationMetrics.engineRuns

            acceptOriginal(session, pages[2], ReaderPageSideHint.UNKNOWN, whiteEdges)
            val middle = session.certifyPairingWithDependencies(target)
            assertEquals(
                engineRuns + 1,
                session.certificationMetrics.engineRuns,
                "A newly analyzed endpoint must force a miss",
            )
            engineRuns = session.certificationMetrics.engineRuns

            // The second endpoint turns the missing edge into a computed edge
            // without a matching score.
            acceptOriginal(session, pages[1], ReaderPageSideHint.UNKNOWN, whiteEdges)
            val after = session.certifyPairingWithDependencies(target)
            assertEquals(
                engineRuns + 1,
                session.certificationMetrics.engineRuns,
                "A changed edge evidence must force a miss",
            )
            val direct = control(session, target)
            assertEquals(direct.certification, after.certification)
            assertEquals(direct.dependentPageIds, after.dependentPageIds)
            // The dependency page set does not change, only the observed values do.
            // A page-set comparison would therefore have reused a stale result.
            assertEquals(
                before.dependentPageIds,
                middle.dependentPageIds,
                "The same dependency pages are read with different evidence",
            )
            assertNotEquals(
                before.certification,
                middle.certification,
                "Different evidence must produce a different result",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `A02 a cleared failure forces a miss and the retry recovers`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            acceptHeaders(session, pages)
            val target = pages[3]
            session.certifyPairingWithDependencies(target)
            val engineRuns = session.certificationMetrics.engineRuns

            val request = session.requestOriginalAnalysis(target)!!
            assertTrue(session.failOriginalAnalysis(request, ReaderAnalysisFailure.READ))
            val blocked = session.certifyPairingWithDependencies(target)
            assertTrue(
                blocked.certification is ReaderPairingCertification.RetryableBlocked,
                "A failed analysis must block the target",
            )
            assertEquals(
                engineRuns + 1,
                session.certificationMetrics.engineRuns,
                "A failed analysis must force a miss",
            )

            assertTrue(session.retryFacts())
            val recovered = session.certifyPairingWithDependencies(target)
            assertEquals(
                engineRuns + 2,
                session.certificationMetrics.engineRuns,
                "A cleared failure must force another miss",
            )
            val direct = control(session, target)
            assertEquals(direct.certification, recovered.certification)
        } finally {
            session.close()
        }
    }

    @Test
    fun `A03 a changed matching mode and a changed direction reject the stored result`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            acceptHeaders(session, pages)
            val target = pages[3]
            session.certifyPairingWithDependencies(target)
            val engineRuns = session.certificationMetrics.engineRuns

            session.configure(ReaderDirection.RTL, automaticMatching = false)
            session.certifyPairingWithDependencies(target)
            assertEquals(
                engineRuns + 1,
                session.certificationMetrics.engineRuns,
                "A changed matching mode must force a miss",
            )

            val beforeDirection = session.certificationMetrics.engineRuns
            session.configure(ReaderDirection.LTR)
            session.certifyPairingWithDependencies(target)
            assertEquals(
                beforeDirection + 1,
                session.certificationMetrics.engineRuns,
                "A changed direction must force a miss",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `A03 a changed content orientation rejects the stored result`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            acceptHeaders(session, pages)
            val target = pages[3]
            session.certifyPairingWithDependencies(target)
            val engineRuns = session.certificationMetrics.engineRuns

            val request = session.requestFact(target)
            val mirrored = ReaderPageFact.dimensions(
                980,
                1403,
                sourceOrientation = ReaderImageOrientation.MIRROR_HORIZONTAL,
            )
            assertTrue(
                session.acceptOrientedFact(request, mirrored) != null,
                "The oriented fact must be accepted",
            )
            assertEquals(
                ReaderImageOrientation.MIRROR_HORIZONTAL,
                session.identity.content.first { it.pageId == target }.sourceOrientation,
                "The content orientation really changed",
            )
            session.certifyPairingWithDependencies(target)
            assertEquals(
                engineRuns + 1,
                session.certificationMetrics.engineRuns,
                "A changed content orientation must force a miss",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `A03 a pending pairing restore never returns the stored result`() {
        val pages = pages(12)
        val identity = identityOf(pages)
        // A session with a persisted chapter waits for its pairing rules; the
        // reuse path must not answer while that gate is open.
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, pages[0], 7L)
        try {
            session.configure(ReaderDirection.RTL, automaticMatching = true)
            acceptHeaders(session, pages)
            val target = pages[3]
            val waiting = session.certifyPairingWithDependencies(target)
            assertEquals(
                setOf(ReaderPairingFactNeed(target, ReaderPairingFactKind.PAIRING_RULES)),
                (waiting.certification as ReaderPairingCertification.NeedFacts).facts,
                "A pending restore reports exactly the pairing rule need",
            )
            assertEquals(0L, session.certificationMetrics.entries, "The reuse path is not entered while restoring")

            val request = session.beginPairingRestore(explicit = true)
            assertTrue(request != null, "An explicit restore must be requestable")
            val restoring = session.certifyPairingWithDependencies(target)
            assertEquals(
                setOf(ReaderPairingFactNeed(target, ReaderPairingFactKind.PAIRING_RULES)),
                (restoring.certification as ReaderPairingCertification.NeedFacts).facts,
                "A running restore still reports exactly the pairing rule need",
            )
            assertEquals(0L, session.certificationMetrics.entries, "The reuse path stays closed while restoring")

            assertTrue(session.failPairingRestore(request!!))
            val failed = session.certifyPairingWithDependencies(target)
            assertTrue(
                failed.certification is ReaderPairingCertification.RetryableBlocked,
                "A failed restore blocks the target",
            )
            assertEquals(
                mapOf(target to ReaderAnalysisFailure.PAIRING_RESTORE),
                (failed.certification as ReaderPairingCertification.RetryableBlocked).failures,
            )
            assertEquals(0L, session.certificationMetrics.entries, "The reuse path stays closed after a failed restore")
        } finally {
            session.close()
        }
    }

    @Test
    fun `A04 an unread page change does not force a new engine run`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            acceptHeaders(session, pages)
            val target = pages[3]
            session.certifyPairingWithDependencies(target)
            val engineRuns = session.certificationMetrics.engineRuns
            val scanSteps = session.certificationMetrics.scanStepsExecuted

            // Page 9 is not part of the target's read set.
            acceptOriginal(session, pages[9], ReaderPageSideHint.UNKNOWN, texturedEdges)

            val after = session.certifyPairingWithDependencies(target)
            assertEquals(
                engineRuns,
                session.certificationMetrics.engineRuns,
                "An unrelated page must not force a new engine run",
            )
            assertEquals(scanSteps, session.certificationMetrics.scanStepsExecuted)
            assertEquals(1L, session.certificationMetrics.memoHits)
            val direct = control(session, target)
            assertEquals(direct.certification, after.certification)
        } finally {
            session.close()
        }
    }

    @Test
    fun `A05 a closed session does not return the stored result`() {
        val pages = pages(12)
        val session = openSession(pages)
        acceptHeaders(session, pages)
        val target = pages[3]
        session.certifyPairingWithDependencies(target)
        session.close()
        assertThrows(IllegalStateException::class.java) { session.certifyPairing(target) }
    }

    @Test
    fun `A05 a new location does not reuse the previous location's result`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            acceptHeaders(session, pages)
            session.certifyPairingWithDependencies(pages[3])
            val engineRuns = session.certificationMetrics.engineRuns

            assertTrue(session.goToPage(pages[7]))
            session.certifyPairingWithDependencies(pages[3])
            assertEquals(
                engineRuns + 1,
                session.certificationMetrics.engineRuns,
                "A new location must not reuse the previous location's result",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `A05 an oversized read set bypasses the reuse path and keeps the complete evidence`() {
        val pages = pages(12)
        val session = openSession(pages, readKeyLimit = 1)
        try {
            acceptHeaders(session, pages)
            val target = pages[3]
            val result = session.certifyPairingWithDependencies(target)
            assertEquals(1L, session.certificationMetrics.oversizedBypasses)
            assertEquals(0L, session.certificationMetrics.memoHits)
            assertEquals(1L, session.certificationMetrics.engineRuns)

            session.certifyPairingWithDependencies(target)
            assertEquals(2L, session.certificationMetrics.engineRuns, "An oversized entry is never reused")
            assertEquals(2L, session.certificationMetrics.oversizedBypasses)

            val direct = control(session, target)
            assertEquals(direct.certification, result.certification, "The bypass keeps the complete evidence")
            assertEquals(direct.dependentPageIds, result.dependentPageIds)
        } finally {
            session.close()
        }
    }

    @Test
    fun `A09 the reused result keeps the certified unit of the production plan`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            acceptHeaders(session, pages)
            val target = pages[3]
            acceptOriginal(session, pages[1], ReaderPageSideHint.RIGHT, texturedEdges)
            acceptOriginal(session, pages[2], ReaderPageSideHint.LEFT, texturedEdges)
            listOf(3, 4, 5).forEach { acceptOriginal(session, pages[it], ReaderPageSideHint.UNKNOWN, whiteEdges) }

            val first = session.certifyPairingWithDependencies(target)
            val second = session.certifyPairingWithDependencies(target)
            assertEquals(1L, session.certificationMetrics.engineRuns)
            assertEquals(1L, session.certificationMetrics.memoHits)
            assertEquals(first.certification, second.certification)

            val certified = first.certification as ReaderPairingCertification.Certified
            val direct = control(session, target).certification as ReaderPairingCertification.Certified
            assertEquals(direct.segment.unit, certified.segment.unit, "The unit must match the uncached plan")
            assertEquals(direct.segment.firstIndex, certified.segment.firstIndex)
            assertTrue(
                certified.segment.isCurrent(
                    session.identity,
                    settingsOf(session),
                    readOwner(session),
                    readHeaders(session),
                ),
                "A reused certificate must still be current for the same input",
            )
        } finally {
            session.close()
        }
    }

    private fun assertEquivalent(
        expected: ReaderPairingCertificationTrace,
        actual: ReaderPairingCertificationTrace,
    ) {
        assertEquals(expected.dependentPageIds, actual.dependentPageIds)
        val expectedResult = expected.certification
        val actualResult = actual.certification
        if (expectedResult is ReaderPairingCertification.Certified &&
            actualResult is ReaderPairingCertification.Certified
        ) {
            // Unit equality includes page coverage, slots, reason and certainty.
            assertEquals(expectedResult.segment.unit, actualResult.segment.unit)
            assertEquals(expectedResult.segment.firstIndex, actualResult.segment.firstIndex)
            assertEquals(expectedResult.segment.scanSteps, actualResult.segment.scanSteps)
        } else {
            assertEquals(expectedResult, actualResult)
        }
    }

    @Test
    fun `S3 a recorded missing header invalidates resident certification when supplied`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            val target = pages[3]
            val missing = session.certifyPairingWithDependencies(target)
            val memo = SharedDualPageSession::class.java.getDeclaredField("certificationMemo")
                .apply { isAccessible = true }.get(session) as ReaderPairingCertificationMemo
            val reads = memo.entryFor(target)!!.reads
            assertEquals(ReaderPairingReadValue.Header(null), reads[ReaderPairingReadKey.Header(target)])
            assertEquals(ReaderPairingReadValue.Dimension(null), reads[ReaderPairingReadKey.Dimension(target)])
            assertTrue(
                ReaderPairingFactNeed(target, ReaderPairingFactKind.DIMENSIONS) in
                    (missing.certification as ReaderPairingCertification.NeedFacts).facts,
            )
            session.certifyPairing(target)
            assertEquals(1L, session.certificationMetrics.engineRuns)
            assertEquals(1L, session.certificationMetrics.memoHits)

            assertTrue(session.acceptFact(session.requestFact(target), portrait))
            val supplied = session.certifyPairingWithDependencies(target)
            assertEquals(2L, session.certificationMetrics.engineRuns)
            assertNotEquals(missing.certification, supplied.certification)
            assertEquivalent(control(session, target), supplied)
            assertEquals(
                1L,
                session.certificationMetrics.missReasonCounts[ReaderPairingMemoMissReason.DEPENDENCY_CHANGED],
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `S3 default four resident targets evict the oldest and rerun only the evicted request`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            pages.take(5).forEach { target ->
                assertEquivalent(control(session, target), session.certifyPairingWithDependencies(target))
            }
            val metrics = session.certificationMetrics
            assertEquals(5L, metrics.engineRuns)
            assertEquals(1L, metrics.evictions)
            assertEquals(0L, metrics.memoHits)
            val resident = session.certifyPairingWithDependencies(pages[4])
            assertEquivalent(control(session, pages[4]), resident)
            assertEquals(5L, metrics.engineRuns)
            assertEquals(1L, metrics.memoHits)
            assertEquivalent(control(session, pages[0]), session.certifyPairingWithDependencies(pages[0]))
            assertEquals(6L, metrics.engineRuns)
            assertEquals(2L, metrics.evictions)
            assertEquals(6L, metrics.memoMisses)
            assertEquals(7L, metrics.entries)
            assertEquals(1L, metrics.missReasonCounts[ReaderPairingMemoMissReason.TARGET_CHANGED])
            assertEquals(0L, metrics.oversizedBypasses)
        } finally {
            session.close()
        }
    }

    @Test
    fun `S3 production page order boundary bypasses storage without losing the certified unit`() {
        for (count in listOf(4096, 4097)) {
            val pages = pages(count)
            val session = openSession(pages)
            try {
                session.configure(ReaderDirection.RTL, automaticMatching = false)
                val target = pages[0]
                assertTrue(session.acceptFact(session.requestFact(target), portrait))
                // The real cover rule checks whether its neighbour is wide before rejecting the opening pair.
                assertTrue(session.acceptFact(session.requestFact(pages[1]), portrait))
                val settings = settingsOf(session).copy(automatic = false)
                val first = session.certifyPairingWithDependencies(target)
                val second = session.certifyPairingWithDependencies(target)
                assertEquivalent(control(session, target, settings), first)
                assertEquivalent(first, second)
                val certified = first.certification as ReaderPairingCertification.Certified
                val oracle = SharedDualPageRules.canonicalPlan(pages, readHeaders(session), ReaderDirection.RTL)
                assertEquals(oracle.single { target in it.pageIds }, certified.segment.unit)
                assertTrue(
                    certified.segment.isCurrent(session.identity, settings, readOwner(session), readHeaders(session)),
                )
                val oversized = count > 4096
                assertEquals(if (oversized) 2L else 1L, session.certificationMetrics.engineRuns)
                assertEquals(if (oversized) 0L else 1L, session.certificationMetrics.memoHits)
                assertEquals(if (oversized) 2L else 0L, session.certificationMetrics.oversizedBypasses)
                assertEquals(0L, session.certificationMetrics.evictions)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `S3 real content rebind closes the old memo and rejects order and identity capabilities`() {
        val pages = pages(12)
        val session = openSession(pages)
        var replacement: SharedDualPageSession? = null
        try {
            acceptHeaders(session, pages)
            pages.forEach { acceptOriginal(session, it) }
            val target = pages[3]
            val before = session.certifyPairingWithDependencies(target)
            session.certifyPairing(target)
            assertEquals(1L, session.certificationMetrics.engineRuns)
            assertEquals(1L, session.certificationMetrics.memoHits)
            assertThrows(IllegalArgumentException::class.java) {
                session.rebindContent(session.identity.copy(content = session.identity.content.reversed()))
            }
            val oldRequest = session.requestOriginalAnalysis(target)!!
            val nextIdentity = session.identity.copy(
                pageListRevision = session.identity.pageListRevision + 1,
                content = session.identity.content.map {
                    if (it.pageId == target) it.copy(contentVersion = "replacement-$target") else it
                },
            )
            val next = session.rebindContent(nextIdentity)
            replacement = next
            assertTrue(session.isClosed)
            assertThrows(IllegalStateException::class.java) { session.certifyPairing(target) }
            assertEquals(0L, next.certificationMetrics.entries)
            assertTrue(
                !next.acceptOriginalAnalysis(
                    oldRequest,
                    portrait,
                    ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, whiteEdges),
                ),
            )
            val missing = next.certifyPairingWithDependencies(target)
            assertEquivalent(control(next, target), missing)
            assertEquals(1L, next.certificationMetrics.engineRuns)
            assertEquals(0L, next.certificationMetrics.memoHits)
            assertTrue(
                !(before.certification as ReaderPairingCertification.Certified).segment.isCurrent(
                    next.identity,
                    settingsOf(next),
                    readOwner(next),
                    readHeaders(next),
                ),
            )
            assertTrue(next.acceptFact(next.requestFact(target), portrait))
            acceptOriginal(next, target)
            val restored = next.certifyPairingWithDependencies(target)
            assertEquivalent(control(next, target), restored)
            val oracle = SharedDualPageRules.canonicalPlan(
                pages,
                readHeaders(next),
                ReaderDirection.RTL,
                automatic = true,
            )
            val restoredSegment = (restored.certification as ReaderPairingCertification.Certified).segment
            assertEquals(oracle.single { target in it.pageIds }, restoredSegment.unit)
            assertTrue(
                restoredSegment.isCurrent(next.identity, settingsOf(next), readOwner(next), readHeaders(next)),
            )
            assertEquals(2L, next.certificationMetrics.engineRuns)
            next.certifyPairing(target)
            assertEquals(2L, next.certificationMetrics.engineRuns)
            assertEquals(1L, next.certificationMetrics.memoHits)
        } finally {
            replacement?.close()
            session.close()
        }
    }

    @Test
    fun `S3 validation metrics count resident reads and stop at the first changed value`() {
        val pages = pages(12)
        val session = openSession(pages)
        try {
            val target = pages[3]
            session.certifyPairing(target)
            val metrics = session.certificationMetrics
            assertEquals(0L, metrics.validationCalls, "No resident entry exists on the first request")
            assertEquals(0L, metrics.readValuesChecked)
            val memo = SharedDualPageSession::class.java.getDeclaredField("certificationMemo")
                .apply { isAccessible = true }.get(session) as ReaderPairingCertificationMemo
            val recordedKeys = memo.entryFor(target)!!.reads.keys.toList()
            val changedHeaderIndex = recordedKeys.indexOf(ReaderPairingReadKey.Header(target))
            assertTrue(changedHeaderIndex >= 0, "The initially absent target header must really be recorded")

            session.certifyPairing(target)
            assertEquals(1L, metrics.validationCalls)
            assertEquals(recordedKeys.size.toLong(), metrics.readValuesChecked, "A hit compares every recorded value")
            assertEquals(1L, metrics.engineRuns)
            assertEquals(1L, metrics.memoHits)

            assertTrue(session.acceptFact(session.requestFact(target), portrait))
            session.certifyPairing(target)
            assertEquals(2L, metrics.validationCalls)
            assertEquals(
                recordedKeys.size.toLong() + changedHeaderIndex + 1,
                metrics.readValuesChecked,
                "The changed header ends validation after exactly the prefix including its first mismatch",
            )
            assertEquals(2L, metrics.engineRuns)
            val checkedBeforeClear = metrics.readValuesChecked
            session.configure(ReaderDirection.LTR, automaticMatching = true)
            session.certifyPairing(target)
            assertEquals(2L, metrics.validationCalls, "Configuration clearing must not pretend to validate an entry")
            assertEquals(checkedBeforeClear, metrics.readValuesChecked)
            assertTrue(session.goToPage(pages[7]))
            session.certifyPairing(target)
            assertEquals(2L, metrics.validationCalls, "Navigation clearing must not pretend to validate an entry")
            assertEquals(checkedBeforeClear, metrics.readValuesChecked)
        } finally {
            session.close()
        }
    }
}
