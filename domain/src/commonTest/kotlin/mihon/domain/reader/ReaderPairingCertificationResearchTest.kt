package mihon.domain.reader

import mihon.domain.reader.ReaderPairingCertification.Certified
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import mihon.domain.reader.ReaderPageAnalysisEvidence.Computed as ComputedPageAnalysis

class ReaderPairingCertificationResearchTest {
    private val ids = List(12) { ReaderPageId(ReaderChapterId(3), it) }
    private val identity = ReaderDualPageIdentity(1, 1, ids.map { ReaderDualPageContentIdentity(it, "bytes-$it") })
    private val portrait = ReaderPageFact.dimensions(980, 1403)
    private val white = ReaderSpreadEdgeSamples(List(50) { -1 }, List(50) { -1 })

    @Test
    fun `failure before original dimensions is blocked with its actual recoverable cause`() {
        for (reason in listOf(ReaderAnalysisFailure.READ, ReaderAnalysisFailure.RESOURCE)) {
            for (failedHeader in listOf(false, true)) {
                val session = SharedDualPageSession(identity, ReaderDirection.RTL, ids[7])
                try {
                    session.configure(ReaderDirection.RTL, true)
                    if (failedHeader) {
                        assertTrue(
                            session.acceptFact(session.requestFact(ids[7]), ReaderPageFact(ReaderPageFactState.FAILED)),
                        )
                    }
                    assertTrue(session.failOriginalAnalysis(session.requestOriginalAnalysis(ids[7])!!, reason))
                    val result = session.certifyPairing(ids[7])
                    assertTrue(
                        result is ReaderPairingCertification.RetryableBlocked,
                        "Absent dimensions must retain the real failure: $result",
                    )
                    assertEquals(reason, (result as ReaderPairingCertification.RetryableBlocked).failures[ids[7]])
                    session.retryFacts()
                    assertTrue(session.certifyPairing(ids[7]) is ReaderPairingCertification.NeedFacts)
                } finally {
                    session.close()
                }
            }
        }
    }

    @Test
    fun `trusted wide geometry does not consume an irrelevant failed side read`() {
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, ids[7])
        try {
            session.configure(ReaderDirection.RTL, true)
            assertTrue(session.acceptFact(session.requestFact(ids[7]), ReaderPageFact.dimensions(1403, 980)))
            assertTrue(
                session.failOriginalAnalysis(session.requestOriginalAnalysis(ids[7])!!, ReaderAnalysisFailure.RESOURCE),
            )
            val proof = session.certifyPairing(ids[7])
            assertTrue(
                proof is ReaderPairingCertification.Certified,
                "A known spread needs only trusted geometry: $proof",
            )
            assertEquals(
                ReaderPortraitSingleSlot.FULL,
                (proof as ReaderPairingCertification.Certified).segment.unit.slots.single().position,
            )
        } finally {
            session.close()
        }
    }

    private fun owner(hints: Map<Int, ReaderPageSideHint> = emptyMap()): ReaderSpreadSamplesOwner =
        ReaderSpreadSamplesOwner(identity, sampleCapacity = 2).also { owner ->
            ids.forEachIndexed { index, id ->
                assertTrue(
                    owner.acceptOriginal(
                        owner.request(id),
                        portrait.copy(sideHint = hints[index] ?: ReaderPageSideHint.UNKNOWN),
                        white,
                    ),
                )
            }
        }

    private fun certify(
        owner: ReaderSpreadSamplesOwner,
        headers: Map<ReaderPageId, ReaderPageFact> = ids.associateWith { portrait },
        target: Int = 7,
        automatic: Boolean = true,
        manual: Set<Int> = emptySet(),
    ): ReaderPairingCertification =
        ReaderPairingCertifier.certify(
            identity,
            headers,
            owner,
            ReaderPairingSettings(ReaderDirection.RTL, automatic, manualPairStarts = manual),
            ids[target],
        )

    @Test
    fun `headers alone cannot certify but computed unknown and null can`() {
        val headersOnly = ReaderSpreadSamplesOwner(identity)
        assertTrue(certify(headersOnly) is ReaderPairingCertification.NeedFacts)
        val analyzed = owner(mapOf(6 to ReaderPageSideHint.RIGHT, 7 to ReaderPageSideHint.LEFT))
        val certified = certify(analyzed) as Certified
        assertEquals(listOf(ids[6], ids[7]), certified.segment.unit.pageIds)
        assertTrue(analyzed.pageAnalysis(ids[5]) is ComputedPageAnalysis)
        val edge = analyzed.edgeAnalysis(ids[5], ids[6], ReaderDirection.RTL) as ReaderEdgeAnalysisEvidence.Computed
        assertNull(edge.score)
    }

    @Test
    fun `original unknown replaces display right and later display cannot override it`() {
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, ids[7])
        session.configure(ReaderDirection.RTL, true)
        assertTrue(session.acceptFact(session.requestFact(ids[7]), portrait.copy(sideHint = ReaderPageSideHint.RIGHT)))
        assertTrue(
            session.acceptOriginalAnalysis(
                session.requestOriginalAnalysis(ids[7])!!,
                portrait,
                ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, white),
            ),
        )
        assertEquals(ReaderPageSideHint.UNKNOWN, session.pageFact(ids[7])!!.sideHint)
        assertTrue(session.acceptFact(session.requestFact(ids[7]), portrait.copy(sideHint = ReaderPageSideHint.RIGHT)))
        assertEquals(ReaderPageSideHint.UNKNOWN, session.pageFact(ids[7])!!.sideHint)
    }

    @Test
    fun `scalar evidence survives sample eviction but replacement rejects old capabilities`() {
        val analyzed = owner()
        assertEquals(2, analyzed.cachedSampleCount)
        assertTrue(analyzed.edgeAnalysis(ids[1], ids[2], ReaderDirection.RTL) is ReaderEdgeAnalysisEvidence.Computed)
        val old = analyzed.request(ids[7])
        val replacementIdentity = identity.copy(
            content = identity.content.map {
                if (it.pageId == ids[7]) it.copy(contentVersion = "replacement") else it
            },
        )
        val replacement = analyzed.rebind(replacementIdentity)
        assertFalse(replacement.acceptOriginal(old, portrait, white))
        assertTrue(replacement.pageAnalysis(ids[7]) is ReaderPageAnalysisEvidence.NotAnalyzed)
        assertTrue(replacement.pageAnalysis(ids[1]) is ComputedPageAnalysis)
        assertTrue(
            replacement.edgeAnalysis(
                ids[6],
                ids[7],
                ReaderDirection.RTL,
            ) is ReaderEdgeAnalysisEvidence.NotAnalyzed,
        )
    }

    @Test
    fun `certified dependencies reject relevant changes but preserve unrelated arrival`() {
        val analyzed = owner(mapOf(6 to ReaderPageSideHint.RIGHT, 7 to ReaderPageSideHint.LEFT))
        val settings = ReaderPairingSettings(ReaderDirection.RTL, true)
        val segment = (certify(analyzed) as Certified).segment
        assertTrue(segment.isCurrent(identity, settings, analyzed, ids.associateWith { portrait }))
        assertTrue(
            analyzed.acceptOriginal(
                analyzed.request(ids[11]),
                portrait.copy(sideHint = ReaderPageSideHint.LEFT),
                white,
            ),
        )
        assertTrue(segment.isCurrent(identity, settings, analyzed, ids.associateWith { portrait }))
        assertTrue(
            analyzed.acceptOriginal(
                analyzed.request(ids[7]),
                portrait.copy(sideHint = ReaderPageSideHint.RIGHT),
                white,
            ),
        )
        assertFalse(segment.isCurrent(identity, settings, analyzed, ids.associateWith { portrait }))
        assertFalse(
            segment.isCurrent(
                identity,
                settings.copy(manualRevision = 1),
                analyzed,
                ids.associateWith { portrait },
            ),
        )
    }

    @Test
    fun `all targets equal full production oracle with manual overlap and both directions`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            for (manual in listOf(emptySet(), setOf(1, 2, 3, 4, 5, 6, 7))) {
                val analyzed = owner(mapOf(3 to ReaderPageSideHint.LEFT, 6 to ReaderPageSideHint.RIGHT))
                val headers = ids.associateWith { portrait }.toMutableMap()
                headers[ids[2]] = ReaderPageFact.dimensions(1403, 980)
                analyzed.acceptOriginal(analyzed.request(ids[2]), headers.getValue(ids[2]), null)
                val settings = ReaderPairingSettings(direction, true, manualPairStarts = manual)
                val facts = ids.associateWith {
                    (analyzed.pageAnalysis(it) as ComputedPageAnalysis).fact
                }
                val oracle = SharedDualPageRules.canonicalPlan(
                    ids,
                    facts,
                    direction,
                    manualPairStarts = manual,
                    automatic = true,
                    distance = { a, b ->
                        (
                            analyzed.edgeAnalysis(
                                a,
                                b,
                                direction,
                            ) as? ReaderEdgeAnalysisEvidence.Computed
                            )?.score
                    },
                )
                ids.forEach { target ->
                    val actual = ReaderPairingCertifier.certify(
                        identity,
                        headers,
                        analyzed,
                        settings,
                        target,
                    ) as Certified
                    assertEquals(
                        oracle.single { target in it.pageIds },
                        actual.segment.unit,
                        "$direction manual=$manual target=$target",
                    )
                }
            }
        }
    }

    @Test
    fun `four incident windows certify against 16384 real owner prefix permutations`() {
        data class Window(val start: Int, val hints: List<ReaderPageSideHint>, val target: Int, val expected: List<Int>)
        val u = ReaderPageSideHint.UNKNOWN
        val r = ReaderPageSideHint.RIGHT
        val l = ReaderPageSideHint.LEFT
        val windows = listOf(
            Window(113, listOf(r, u, u, u, r), 113, listOf(113, 114)),
            Window(113, listOf(r, u, u, u, r), 115, listOf(115, 116)),
            Window(123, listOf(u, u, u, l, r), 126, listOf(125, 126)),
            Window(93, listOf(r, u, u), 93, listOf(93, 94)),
        )
        val wave = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
        var executions = 0
        for (window in windows) {
            val windowPortrait = ReaderPageFact.dimensions(980, if (window.start == 93) 1393 else 1403)
            val pages = List(176) { ReaderPageId(ReaderChapterId(701), it) }
            val chapter = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "synthetic-$it") })
            val owner = ReaderSpreadSamplesOwner(chapter)
            val facts = pages.associateWith { windowPortrait }.toMutableMap()
            window.hints.forEachIndexed { index, hint ->
                facts[pages[window.start - 1 + index]] = windowPortrait.copy(sideHint = hint)
            }
            pages.forEach { assertTrue(owner.acceptOriginal(owner.request(it), facts.getValue(it), white)) }
            for (shape in 0 until 256) {
                for (pattern in 0 until 16) {
                    repeat(5) { offset ->
                        val index = window.start - 5 + offset
                        val fact = if (offset == 4) {
                            facts.getValue(pages[index])
                        } else {
                            when ((shape shr (offset * 2)) and 3) {
                                1 -> windowPortrait.copy(sideHint = l)
                                2 -> windowPortrait.copy(sideHint = r)
                                3 -> ReaderPageFact.dimensions(windowPortrait.height, windowPortrait.width)
                                else -> windowPortrait
                            }
                        }
                        facts[pages[index]] = fact
                        val left = if (offset < 4 && pattern and (1 shl offset) != 0) wave else white.left
                        val right = if (offset > 0 && pattern and (1 shl (offset - 1)) != 0) wave else white.right
                        assertTrue(
                            owner.acceptOriginal(
                                owner.request(pages[index]),
                                fact,
                                if (fact.state == ReaderPageFactState.SPREAD) {
                                    null
                                } else {
                                    ReaderSpreadEdgeSamples(left, right)
                                },
                            ),
                        )
                    }
                    val settings = ReaderPairingSettings(ReaderDirection.RTL, true)
                    val target = pages[window.target - 1]
                    val certified = ReaderPairingCertifier.certify(chapter, facts, owner, settings, target) as Certified
                    val oracle = SharedDualPageRules.canonicalPlan(
                        pages,
                        facts,
                        ReaderDirection.RTL,
                        automatic = true,
                        distance = { a, b ->
                            (
                                owner.edgeAnalysis(
                                    a,
                                    b,
                                    ReaderDirection.RTL,
                                ) as? ReaderEdgeAnalysisEvidence.Computed
                                )?.score
                        },
                    )
                    assertEquals(
                        oracle.single { target in it.pageIds },
                        certified.segment.unit,
                        "$window shape=$shape pattern=$pattern",
                    )
                    assertEquals(window.expected, certified.segment.unit.pageIds.map { it.sourcePageIndex + 1 })
                    executions++
                }
            }
            owner.close()
        }
        assertEquals(16384, executions)
    }

    @Test
    fun `strong preceding competition invalidates five page closure and expands left`() {
        val pages = List(176) { ReaderPageId(ReaderChapterId(8), it) }
        val chapter = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "synthetic-$it") })
        val owner = ReaderSpreadSamplesOwner(chapter)
        val facts = pages.associateWith { portrait }.toMutableMap()
        facts[pages[112]] = portrait.copy(sideHint = ReaderPageSideHint.RIGHT)
        facts[pages[116]] = portrait.copy(sideHint = ReaderPageSideHint.RIGHT)
        facts[pages[117]] = portrait.copy(sideHint = ReaderPageSideHint.LEFT)
        facts[pages[125]] = portrait.copy(sideHint = ReaderPageSideHint.LEFT)
        facts[pages[126]] = portrait.copy(sideHint = ReaderPageSideHint.RIGHT)
        pages.forEach { owner.acceptOriginal(owner.request(it), facts.getValue(it), white) }
        val settings = ReaderPairingSettings(ReaderDirection.RTL, true)
        val before = (ReaderPairingCertifier.certify(chapter, facts, owner, settings, pages[125]) as Certified).segment
        assertEquals(listOf(pages[124], pages[125]), before.unit.pageIds)
        val wave = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
        owner.acceptOriginal(
            owner.request(pages[123]),
            facts.getValue(pages[123]),
            ReaderSpreadEdgeSamples(wave, white.right),
        )
        owner.acceptOriginal(
            owner.request(pages[124]),
            facts.getValue(pages[124]),
            ReaderSpreadEdgeSamples(white.left, wave),
        )
        assertFalse(before.isCurrent(chapter, settings, owner, facts))
        val after = (ReaderPairingCertifier.certify(chapter, facts, owner, settings, pages[125]) as Certified).segment
        assertTrue(after.firstIndex < 121, "A fixed 122..126 window must not be reused: ${after.firstIndex}")
        val oracle = SharedDualPageRules.canonicalPlan(
            pages,
            facts,
            ReaderDirection.RTL,
            automatic = true,
            distance = { a, b ->
                (
                    owner.edgeAnalysis(
                        a,
                        b,
                        ReaderDirection.RTL,
                    ) as? ReaderEdgeAnalysisEvidence.Computed
                    )?.score
            },
        )
        assertEquals(oracle.single { pages[125] in it.pageIds }, after.unit)
    }

    @Test
    fun `retryable failure and cancellation never become a final negative fact`() {
        val analyzed = owner(mapOf(6 to ReaderPageSideHint.RIGHT, 7 to ReaderPageSideHint.LEFT))
        val old = analyzed.request(ids[7])
        analyzed.cancelRequests()
        assertFalse(analyzed.acceptOriginal(old, portrait, white))
        assertTrue(analyzed.pageAnalysis(ids[7]) is ComputedPageAnalysis)
        val empty = ReaderSpreadSamplesOwner(identity)
        assertTrue(empty.failOriginal(empty.request(ids[7]), ReaderAnalysisFailure.RESOURCE))
        assertTrue(certify(empty) is ReaderPairingCertification.RetryableBlocked)
        assertTrue(empty.acceptOriginal(empty.request(ids[7]), portrait, white))
        assertTrue(certify(empty) is ReaderPairingCertification.NeedFacts)
        assertFalse(
            empty.acceptOriginal(empty.request(ids[6]), portrait, null),
            "Resource absence cannot masquerade as completed edge analysis",
        )
    }

    @Test
    fun `long unresolved prefix uses bounded production step count and reaches actual chapter entry`() {
        val pages = List(1024) { ReaderPageId(ReaderChapterId(7), it) }
        val chapter = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "synthetic-$it") })
        val owner = ReaderSpreadSamplesOwner(chapter, sampleCapacity = 2)
        val facts = pages.associateWith { portrait }
        pages.forEach { owner.acceptOriginal(owner.request(it), portrait, white) }
        val result = ReaderPairingCertifier.certify(
            chapter,
            facts,
            owner,
            ReaderPairingSettings(ReaderDirection.RTL, true),
            pages[1000],
        ) as Certified
        assertTrue(result.segment.firstIndex <= 1, "The known cover prohibition permits entry at page 1")
        val oracle = SharedDualPageRules.canonicalPlan(pages, facts, ReaderDirection.RTL, automatic = true)
        assertEquals(oracle.single { pages[1000] in it.pageIds }, result.segment.unit)
        assertTrue(
            result.segment.scanSteps <= 4 * pages.size,
            "Actual reducer work must not be quadratic: ${result.segment.scanSteps}",
        )
        assertEquals(2, owner.cachedSampleCount)
    }

    @Test
    fun `overlapping manual cluster depends on unknown earlier dimensions`() {
        val analyzed = ReaderSpreadSamplesOwner(identity)
        for (index in 5..10) analyzed.acceptOriginal(analyzed.request(ids[index]), portrait, white)
        val headers = ids.associateWith { portrait }.toMutableMap()
        headers.remove(ids[1])
        val missing = certify(
            analyzed,
            headers,
            manual = setOf(1, 2, 3, 4, 5, 6, 7),
        ) as ReaderPairingCertification.NeedFacts
        assertTrue(ReaderPairingFactNeed(ids[1], ReaderPairingFactKind.DIMENSIONS) in missing.facts)
        headers[ids[1]] = ReaderPageFact.dimensions(1403, 980)
        val changed = certify(analyzed, headers, manual = setOf(1, 2, 3, 4, 5, 6, 7)) as Certified
        assertEquals(listOf(ids[6], ids[7]), changed.segment.unit.pageIds)
        headers[ids[1]] = portrait
        assertFalse(
            changed.segment.isCurrent(
                identity,
                ReaderPairingSettings(ReaderDirection.RTL, true, manualPairStarts = setOf(1, 2, 3, 4, 5, 6, 7)),
                analyzed,
                headers,
            ),
        )
        val normal = certify(analyzed, headers, manual = setOf(1, 2, 3, 4, 5, 6, 7)) as Certified
        assertEquals(listOf(ids[7], ids[8]), normal.segment.unit.pageIds)
    }

    @Test
    fun `automatic off needs dimensions only while cover tail forced and opening obey full oracle`() {
        val analyzed = ReaderSpreadSamplesOwner(identity)
        val headers = ids.associateWith { portrait }
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            for (opening in ReaderChapterOpeningMode.entries) {
                for (forced in listOf(emptySet(), setOf(ids[4]), setOf(ids[0], ids.last()))) {
                    val settings = ReaderPairingSettings(
                        direction,
                        false,
                        forcedSinglePages = forced,
                        openingMode = opening,
                    )
                    val oracle = SharedDualPageRules.canonicalPlan(
                        ids,
                        headers,
                        direction,
                        forcedSinglePages = forced,
                        openingMode = opening,
                    )
                    ids.forEach { target ->
                        val actual = ReaderPairingCertifier.certify(
                            identity,
                            headers,
                            analyzed,
                            settings,
                            target,
                        ) as Certified
                        assertEquals(oracle.single { target in it.pageIds }, actual.segment.unit)
                    }
                }
            }
        }
    }

    @Test
    fun `batch publication invalidates layout once and rejects stale batch atomically`() {
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, ids[7])
        session.configure(ReaderDirection.RTL, true)
        fun publications() = listOf(6, 7).map { index ->
            ReaderOriginalAnalysisPublication(
                session.requestOriginalAnalysis(ids[index])!!,
                portrait,
                ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, white),
            )
        }
        val stale = publications()
        session.requestOriginalAnalysis(ids[7])
        assertFalse(session.acceptOriginalAnalyses(stale))
        assertNull(session.pageFact(ids[6]), "The first half of a rejected batch must not publish")
        val revision = session.layoutRevision
        assertTrue(session.acceptOriginalAnalyses(publications()))
        assertEquals(revision + 1, session.layoutRevision)
        assertEquals(ReaderPageSideHint.UNKNOWN, session.pageFact(ids[6])!!.sideHint)
        assertEquals(ReaderPageSideHint.UNKNOWN, session.pageFact(ids[7])!!.sideHint)
    }

    @Test
    fun `legacy samples never acquire original authority or overwrite confirmed original edges`() {
        val mixed = ReaderSpreadSamplesOwner(identity)
        assertTrue(mixed.accept(mixed.request(ids[1]), white))
        assertTrue(mixed.acceptOriginal(mixed.request(ids[2]), portrait, white))
        assertTrue(mixed.edgeAnalysis(ids[1], ids[2], ReaderDirection.RTL) is ReaderEdgeAnalysisEvidence.NotAnalyzed)
        assertTrue(mixed.acceptOriginal(mixed.request(ids[1]), portrait, white))
        val confirmed = mixed.edgeAnalysis(ids[1], ids[2], ReaderDirection.RTL)
        assertTrue(confirmed is ReaderEdgeAnalysisEvidence.Computed)
        assertFalse(mixed.accept(mixed.request(ids[2]), white))
        assertEquals(confirmed, mixed.edgeAnalysis(ids[1], ids[2], ReaderDirection.RTL))
    }

    @Test
    fun `known manual reset does not request irrelevant unknown prefix dimensions`() {
        val empty = ReaderSpreadSamplesOwner(identity)
        val headers = (5..11).associate { ids[it] to portrait }
        val settings = ReaderPairingSettings(
            ReaderDirection.RTL,
            false,
            forcedSinglePages = setOf(ids[6]),
            manualPairStarts = (1..9).toSet(),
        )
        val actual = ReaderPairingCertifier.certify(identity, headers, empty, settings, ids[8]) as Certified
        assertEquals(listOf(ids[7], ids[8]), actual.segment.unit.pageIds)
        assertTrue(actual.segment.dependentPageIds.none { it in ids.take(5) })
    }

    @Test
    fun `unknown original with new edges invalidates an already cached canonical plan`() {
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, ids[2])
        session.configure(ReaderDirection.RTL, true)
        ids.forEach { session.acceptFact(session.requestFact(it), portrait) }
        assertEquals(listOf(ids[1], ids[2]), session.canonicalUnits.single { ids[2] in it.pageIds }.pageIds)
        val wave = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
        val publications = (0..4).map { index ->
            val edges = when (index) {
                2 -> ReaderSpreadEdgeSamples(wave, white.right)
                3 -> ReaderSpreadEdgeSamples(white.left, wave)
                else -> white
            }
            ReaderOriginalAnalysisPublication(
                session.requestOriginalAnalysis(ids[index])!!,
                portrait,
                ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, edges),
            )
        }
        val revision = session.layoutRevision
        assertTrue(session.acceptOriginalAnalyses(publications))
        assertEquals(revision + 1, session.layoutRevision)
        assertEquals(listOf(ids[2], ids[3]), session.canonicalUnits.single { ids[2] in it.pageIds }.pageIds)
        assertEquals(
            session.canonicalUnits.single { ids[2] in it.pageIds },
            (session.certifyPairing() as Certified).segment.unit,
        )
    }

    private fun populatedPersistentSession(): SharedDualPageSession {
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, ids[7], mangaId = 91)
        session.configure(ReaderDirection.RTL, true)
        ids.forEach {
            session.acceptOriginalAnalysis(
                session.requestOriginalAnalysis(it)!!,
                portrait,
                ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, white),
            )
        }
        return session
    }

    @Test
    fun `manual restoration is a required input and its failure has an explicit default recovery`() {
        val session = populatedPersistentSession()
        assertTrue(session.certifyPairing() is ReaderPairingCertification.NeedFacts)
        val request = session.beginPairingRestore()!!
        assertTrue(session.certifyPairing() is ReaderPairingCertification.NeedFacts)
        assertTrue(session.failPairingRestore(request))
        assertTrue(session.certifyPairing() is ReaderPairingCertification.RetryableBlocked)
        assertTrue(session.useDefaultPairingForSession())
        assertTrue(session.certifyPairing() is Certified)
        val restored = populatedPersistentSession()
        val record = ChapterPairingRecord(2, ids.size, emptySet(), manualPairStarts = setOf(6))
        assertTrue(restored.acceptPairingRestore(restored.beginPairingRestore()!!, ChapterPairingSnapshot(record, 8)))
        val certified = restored.certifyPairing() as Certified
        assertEquals(listOf(ids[6], ids[7]), certified.segment.unit.pageIds)
        assertEquals(restored.canonicalTargetUnit, certified.segment.unit)
    }

    @Test
    fun `disposed session cannot issue a pairing certificate`() {
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, ids[7])
        ids.forEach { session.acceptFact(session.requestFact(it), portrait) }
        session.close()
        assertThrows(IllegalStateException::class.java) { session.certifyPairing() }
    }

    @Test
    fun `converged entries do not demand an unrelated lookbehind analysis`() {
        val analyzed = ReaderSpreadSamplesOwner(identity)
        val hints = mapOf(3 to ReaderPageSideHint.LEFT, 4 to ReaderPageSideHint.RIGHT, 6 to ReaderPageSideHint.RIGHT)
        for (index in 2..6) {
            analyzed.acceptOriginal(
                analyzed.request(ids[index]),
                portrait.copy(sideHint = hints[index] ?: ReaderPageSideHint.UNKNOWN),
                white,
            )
        }
        val headers = (2..6).associate { ids[it] to portrait }
        val actual = certify(analyzed, headers, target = 4) as Certified
        assertEquals(listOf(ids[4], ids[5]), actual.segment.unit.pageIds)
        assertTrue(actual.segment.dependentPageIds.all { it in ids.slice(2..6) })
    }

    @Test
    fun `oriented header dimensions consume source rotation exactly once`() {
        val content = identity.copy(
            content = identity.content.map {
                it.copy(sourceOrientation = ReaderImageOrientation.CLOCKWISE)
            },
        )
        val owner = ReaderSpreadSamplesOwner(content)
        val spread = ReaderPageFact.dimensions(1403, 980, sourceOrientation = ReaderImageOrientation.CLOCKWISE)
        assertTrue(owner.acceptOriginal(owner.request(ids[7]), spread, null))
        val unknownOriginal = ReaderPageFact.dimensions(980, 1403, sourceOrientation = ReaderImageOrientation.CLOCKWISE)
        assertFalse(owner.acceptOriginal(owner.request(ids[6]), unknownOriginal, null))
    }

    @Test
    fun `required header failure blocks while irrelevant side failure does not`() {
        val analyzed = ReaderSpreadSamplesOwner(identity)
        val headers = ids.associateWith { portrait }.toMutableMap()
        headers[ids[7]] = ReaderPageFact(ReaderPageFactState.FAILED)
        assertTrue(certify(analyzed, headers, automatic = false) is ReaderPairingCertification.RetryableBlocked)
        headers[ids[7]] = portrait
        analyzed.failOriginal(analyzed.request(ids[7]), ReaderAnalysisFailure.READ)
        assertTrue(certify(analyzed, headers, automatic = false) is Certified)
    }

    @Test
    fun `manual pair needs no original side of an unrelated preceding single`() {
        val analyzed = ReaderSpreadSamplesOwner(identity)
        analyzed.failOriginal(analyzed.request(ids[5]), ReaderAnalysisFailure.UNSUPPORTED)
        val settings = ReaderPairingSettings(ReaderDirection.RTL, true, manualPairStarts = setOf(6))
        val result = ReaderPairingCertifier.certify(
            identity,
            ids.associateWith { portrait },
            analyzed,
            settings,
            ids[7],
        ) as Certified
        assertEquals(listOf(ids[6], ids[7]), result.segment.unit.pageIds)
    }

    @Test
    fun `evicted sample recovery failure blocks a missing edge while retaining confirmed side`() {
        val analyzed = ReaderSpreadSamplesOwner(identity, sampleCapacity = 2)
        val order = listOf(1, 4, 5, 2, 0, 3, 6, 7, 8, 9, 10, 11)
        order.forEach { index -> analyzed.acceptOriginal(analyzed.request(ids[index]), portrait, white) }
        assertTrue(certify(analyzed, target = 2) is ReaderPairingCertification.NeedFacts)
        val confirmed = analyzed.pageAnalysis(ids[1])
        assertTrue(analyzed.failOriginal(analyzed.request(ids[1]), ReaderAnalysisFailure.RESOURCE))
        assertEquals(confirmed, analyzed.pageAnalysis(ids[1]))
        val blocked = certify(analyzed, target = 2) as ReaderPairingCertification.RetryableBlocked
        assertEquals(ReaderAnalysisFailure.RESOURCE, blocked.failures[ids[1]])
        assertTrue(analyzed.acceptOriginal(analyzed.request(ids[1]), portrait, white))
        assertTrue(analyzed.acceptOriginal(analyzed.request(ids[2]), portrait, white))
        assertTrue(certify(analyzed, target = 2) is Certified)
        val completedEdge = analyzed.edgeAnalysis(ids[1], ids[2], ReaderDirection.RTL)
        assertTrue(analyzed.failOriginal(analyzed.request(ids[1]), ReaderAnalysisFailure.UNSUPPORTED))
        assertEquals(completedEdge, analyzed.edgeAnalysis(ids[1], ids[2], ReaderDirection.RTL))
        assertTrue(certify(analyzed, target = 2) is Certified)
    }

    @Test
    fun `display dimensions alone do not become original certification evidence`() {
        val single = identity.copy(content = identity.content.take(1))
        val session = SharedDualPageSession(single, ReaderDirection.RTL)
        val viewport = session.captureViewport(preparingPageIds = emptySet())!!
        assertTrue(session.acceptDecodedFact(viewport, ids[0], 100, 200))
        assertTrue(session.certifyPairing() is ReaderPairingCertification.NeedFacts)
    }

    @Test
    fun `trusted original header survives a cropped display callback with automatic off`() {
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, ids[0])
        ids.forEach { page ->
            val original = if (page == ids[0]) ReaderPageFact.dimensions(300, 100) else portrait
            session.acceptFact(session.requestFact(page), original)
        }
        val viewport = session.captureViewport(preparingPageIds = setOf(ids[0]))!!
        assertTrue(session.acceptDecodedFact(viewport, ids[0], 80, 120))
        assertEquals(ReaderPageFactState.SPREAD, session.pageFact(ids[0])!!.state)
        val certified = session.certifyPairing() as Certified
        assertEquals(ReaderPortraitSingleSlot.FULL, certified.segment.unit.slots.single().position)
    }

    @Test
    fun `a known spread target never waits for unknown original prefix sides`() {
        val analyzed = ReaderSpreadSamplesOwner(identity)
        val headers = mapOf(ids[6] to ReaderPageFact.dimensions(1403, 980))
        val actual = certify(analyzed, headers, target = 6) as Certified
        assertEquals(listOf(ids[6]), actual.segment.unit.pageIds)
        assertEquals(ReaderPortraitSingleSlot.FULL, actual.segment.unit.slots.single().position)
        assertEquals(setOf(ids[6]), actual.segment.dependentPageIds)
    }

    @Test
    fun `a transient repeated header failure cannot revoke an already trusted layout`() {
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, ids[0])
        ids.forEach { page ->
            session.acceptFact(
                session.requestFact(page),
                if (page ==
                    ids[0]
                ) {
                    ReaderPageFact.dimensions(300, 100)
                } else {
                    portrait
                },
            )
        }
        val certified = session.certifyPairing() as Certified
        session.acceptFact(session.requestFact(ids[0]), ReaderPageFact(ReaderPageFactState.FAILED))
        assertEquals(certified.segment.unit, (session.certifyPairing() as Certified).segment.unit)
        assertEquals(certified.segment.unit, session.canonicalTargetUnit)
    }

    @Test
    fun `original evidence rejects copied rotation without recomputed geometry`() {
        val rotated = identity.copy(content = identity.content.map { it.copy(rotation = PageRotation.CLOCKWISE_90) })
        val owner = ReaderSpreadSamplesOwner(rotated)
        val bad = portrait.copy(rotation = PageRotation.CLOCKWISE_90)
        assertFalse(owner.acceptOriginal(owner.request(ids[6]), bad, null))
        val session = SharedDualPageSession(rotated, ReaderDirection.RTL, ids[6])
        assertFalse(session.acceptFact(session.requestFact(ids[6]), bad))
        val headers = ids.associateWith { bad }
        assertTrue(
            ReaderPairingCertifier.certify(
                rotated,
                headers,
                owner,
                ReaderPairingSettings(ReaderDirection.RTL, false),
                ids[6],
            ) is ReaderPairingCertification.NeedFacts,
        )
        val correct = ReaderPageFact.dimensions(980, 1403, PageRotation.CLOCKWISE_90)
        assertTrue(owner.acceptOriginal(owner.request(ids[6]), correct, null))
    }

    @Test
    fun `closed analysis owner cannot retain or issue a header only certificate`() {
        val owner = ReaderSpreadSamplesOwner(identity)
        val headers = ids.associateWith { portrait }
        val settings = ReaderPairingSettings(ReaderDirection.RTL, false)
        val before = ReaderPairingCertifier.certify(identity, headers, owner, settings, ids[7]) as Certified
        owner.close()
        assertFalse(before.segment.isCurrent(identity, settings, owner, headers))
        assertThrows(IllegalStateException::class.java) {
            ReaderPairingCertifier.certify(identity, headers, owner, settings, ids[7])
        }
    }
}
