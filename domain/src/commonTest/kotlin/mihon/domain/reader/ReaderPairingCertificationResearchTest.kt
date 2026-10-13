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
    fun `new locations renew only resource failures and retain original scalar authority`() {
        for (stepNavigation in listOf(false, true)) {
            for (missingVector in listOf(false, true)) {
                for (reason in listOf(
                    ReaderAnalysisFailure.RESOURCE,
                    ReaderAnalysisFailure.READ,
                    ReaderAnalysisFailure.UNSUPPORTED,
                )) {
                    val pages = List(if (missingVector) 40 else 12) { ReaderPageId(ReaderChapterId(73095), it) }
                    val chapter = ReaderDualPageIdentity(
                        1,
                        1,
                        pages.map { ReaderDualPageContentIdentity(it, "actual-content-$it") },
                    )
                    val session = SharedDualPageSession(chapter, ReaderDirection.RTL, pages[0])
                    try {
                        session.configure(ReaderDirection.RTL, true)
                        pages.forEach { session.acceptFact(session.requestFact(it), portrait) }
                        // Actual capacity16 eviction prevents the page2/page3 edge from ever being scored.
                        // No capacity override, owner substitution or cache clearing is used.
                        val order = if (missingVector) {
                            listOf(1) + (10..27).toList() + listOf(2, 0) + (3..9).toList() + (28..39).toList()
                        } else {
                            pages.indices.filter { it != 1 }
                        }
                        order.forEach { index ->
                            assertTrue(
                                session.acceptOriginalAnalysis(
                                    session.requestOriginalAnalysis(pages[index])!!,
                                    portrait,
                                    ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, white),
                                ),
                            )
                        }
                        // Read the real owner solely to observe scalar preservation; all mutations use Session APIs.
                        val samples = SharedDualPageSession::class.java.getDeclaredField("spreadSamples")
                            .apply { isAccessible = true }.get(session) as ReaderSpreadSamplesOwner
                        val preservedEdge = samples.edgeAnalysis(
                            pages[pages.lastIndex - 1],
                            pages.last(),
                            ReaderDirection.RTL,
                        )
                        assertTrue(preservedEdge is ReaderEdgeAnalysisEvidence.Computed)
                        val preservedPages = pages.associateWith(session::originalAnalysis)
                            .filterValues { it is ComputedPageAnalysis }
                        val budget = session.factBudget
                        assertEquals(listOf(pages[1]), budget.reserve(listOf(pages[1]), 0))
                        budget.complete(pages[1], 0, true)
                        assertTrue(budget.reserve(listOf(pages[1]), 1).isEmpty())
                        val old = session.requestOriginalAnalysis(pages[1])!!
                        assertTrue(session.failOriginalAnalysis(old, reason))
                        val blocked = session.certifyPairing(pages[1]) as ReaderPairingCertification.RetryableBlocked
                        assertEquals(reason, blocked.failures[pages[1]])
                        assertTrue(session.factBudget === budget, "A proof query cannot renew the same-location budget")
                        assertTrue(session.ownsOriginalAnalysisRequest(old))
                        val before = session.location!!
                        assertTrue(if (stepNavigation) session.navigate(true) else session.goToPage(pages[1]))
                        assertEquals(pages[1], session.location!!.pageId)
                        assertEquals(before.sequence + 1, session.location!!.sequence)
                        assertFalse(session.factBudget === budget)
                        assertFalse(session.ownsOriginalAnalysisRequest(old))
                        assertFalse(
                            session.acceptOriginalAnalysis(
                                old,
                                portrait,
                                ReaderOriginalPageSamples(ReaderPageSideHint.UNKNOWN, white),
                            ),
                        )
                        preservedPages.forEach { (page, evidence) ->
                            assertEquals(evidence, session.originalAnalysis(page))
                        }
                        assertEquals(
                            preservedEdge,
                            samples.edgeAnalysis(
                                pages[pages.lastIndex - 1],
                                pages.last(),
                                ReaderDirection.RTL,
                            ),
                        )
                        val result = session.certifyPairing(pages[1])
                        val label = "step=$stepNavigation missingVector=$missingVector reason=$reason result=$result"
                        if (reason == ReaderAnalysisFailure.RESOURCE) {
                            assertTrue(result is ReaderPairingCertification.NeedFacts, label)
                            assertTrue(
                                (result as ReaderPairingCertification.NeedFacts).facts.any {
                                    it.pageId == pages[1] && it.kind == if (missingVector) {
                                        ReaderPairingFactKind.EDGE_SAMPLES
                                    } else {
                                        ReaderPairingFactKind.ORIGINAL_ANALYSIS
                                    }
                                },
                                label,
                            )
                        } else {
                            assertTrue(result is ReaderPairingCertification.RetryableBlocked, label)
                            assertEquals(
                                reason,
                                (result as ReaderPairingCertification.RetryableBlocked).failures[pages[1]],
                            )
                        }
                    } finally {
                        session.close()
                    }
                }
            }
        }
    }

    @Test
    fun `sixteen observed control pages cannot certify an unseen prefix reset`() {
        // Actual ART target126 exhausted its shared location budget after reader pages115..130.
        // All original margin points there are gray: computed UNKNOWN and computed null edges.
        // The policy layer then refuses the next read; this checks the missing input just before that refusal.
        val pages = List(176) { ReaderPageId(ReaderChapterId(73090), it) }
        val observed = (114..129).map(pages::get)
        assertEquals(ReaderDualPageFactLimits.MAX_ADDITIONAL_PAGES, observed.size)
        val plan = requireNotNull(ReaderOriginalPageSampling.plan(portrait.width, portrait.height))
        fun input(rightPage: Boolean): ReaderOriginalPageSamples {
            fun color(point: Pair<Int, Int>) =
                if (rightPage && point.first < portrait.width / 20) -1 else 0xff8c8c8c.toInt()
            return plan.samples(plan.left.map(::color), plan.right.map(::color))
        }
        val gray = input(false)
        val right = input(true)
        assertEquals(ReaderPageSideHint.UNKNOWN, gray.sideHint)
        assertEquals(ReaderPageSideHint.RIGHT, right.sideHint)
        val settings = ReaderPairingSettings(ReaderDirection.RTL, true)
        val headers = pages.associateWith { portrait }
        val owners = mutableListOf<ReaderSpreadSamplesOwner>()
        try {
            val worlds = listOf(false, true).map { reset ->
                // Only the unobserved reader113 changes physical content; all observed identities stay equal.
                val identity = ReaderDualPageIdentity(
                    1,
                    1,
                    pages.map { page ->
                        ReaderDualPageContentIdentity(
                            page,
                            if (reset && page.sourcePageIndex == 112) {
                                "unseen-right-margin"
                            } else {
                                "gray-" + page.sourcePageIndex
                            },
                        )
                    },
                )
                val full = ReaderSpreadSamplesOwner(identity).also(owners::add)
                val partial = ReaderSpreadSamplesOwner(identity).also(owners::add)
                val facts = pages.associateWith { page ->
                    val samples = if (reset && page.sourcePageIndex == 112) right else gray
                    portrait.copy(sideHint = samples.sideHint).also { fact ->
                        assertTrue(full.acceptOriginal(full.request(page), fact, samples.edges))
                        if (page in observed) {
                            assertTrue(partial.acceptOriginal(partial.request(page), fact, samples.edges))
                        }
                    }
                }
                val unit = SharedDualPageRules.canonicalPlan(
                    pages,
                    facts,
                    settings.direction,
                    automatic = settings.automatic,
                    distance = { a, b ->
                        (full.edgeAnalysis(a, b, settings.direction) as ReaderEdgeAnalysisEvidence.Computed).score
                    },
                ).single { pages[125] in it.pageIds }
                val certificate =
                    ReaderPairingCertifier.certify(identity, headers, full, settings, pages[125]) as Certified
                assertEquals(unit, certificate.segment.unit)
                val pending = ReaderPairingCertifier.certify(identity, headers, partial, settings, pages[125])
                assertTrue(pending is ReaderPairingCertification.NeedFacts)
                Triple(full, unit, pending as ReaderPairingCertification.NeedFacts)
            }
            observed.forEach { page ->
                assertEquals(worlds[0].first.pageAnalysis(page), worlds[1].first.pageAnalysis(page))
            }
            observed.zipWithNext().forEach { (a, b) ->
                val before = worlds[0].first.edgeAnalysis(a, b, settings.direction)
                assertEquals(before, worlds[1].first.edgeAnalysis(a, b, settings.direction))
                assertNull((before as ReaderEdgeAnalysisEvidence.Computed).score)
            }
            assertEquals(listOf(126, 127), worlds[0].second.pageIds.map { it.sourcePageIndex + 1 })
            assertEquals(listOf(125, 126), worlds[1].second.pageIds.map { it.sourcePageIndex + 1 })
            assertEquals(worlds[0].third.facts, worlds[1].third.facts)
            val missing = worlds[0].third.facts
            assertEquals(
                setOf(114),
                missing.filter {
                    it.kind == ReaderPairingFactKind.ORIGINAL_ANALYSIS
                }.map { it.pageId.sourcePageIndex + 1 }.toSet(),
            )
            assertTrue(
                missing.any {
                    it.kind == ReaderPairingFactKind.EDGE_SAMPLES &&
                        it.pageId == pages[113] && it.secondPageId == pages[114]
                },
            )
            println(
                "R3_PREFIX_WITNESS observed=115..130 missingOriginal=114 changedUnobserved=113 " +
                    "oracleGray=126,127 oracleReset=125,126 scanSteps=" + worlds[0].third.scanSteps,
            )
        } finally {
            owners.forEach(ReaderSpreadSamplesOwner::close)
        }
    }

    @Test
    fun `production needs quantify original prefix closure from observed and cold samples`() {
        // This measures necessary fact inputs, without a decoder, byte reads, or wall-clock latency.
        // It deliberately continues past the policy's 16-page refusal to quantify the missing closure;
        // it does not create another location budget or authorize those reads in the actual reader.
        val pages = List(176) { ReaderPageId(ReaderChapterId(73091), it) }
        val observed = (114..129).map(pages::get)
        val target = pages[125]
        val settings = ReaderPairingSettings(ReaderDirection.RTL, true)
        val headers = pages.associateWith { portrait }
        val plan = requireNotNull(ReaderOriginalPageSampling.plan(portrait.width, portrait.height))
        fun input(rightPage: Boolean): ReaderOriginalPageSamples {
            fun color(point: Pair<Int, Int>) =
                if (rightPage && point.first < portrait.width / 20) -1 else 0xff8c8c8c.toInt()
            return plan.samples(plan.left.map(::color), plan.right.map(::color))
        }
        val gray = input(false)
        val right = input(true)
        assertEquals(ReaderPageSideHint.UNKNOWN, gray.sideHint)
        assertEquals(ReaderPageSideHint.RIGHT, right.sideHint)
        data class Closure(
            val initial: List<ReaderPageId>,
            val additional: List<ReaderPageId>,
            val queries: Int,
            val scanSteps: Int,
            val maxScanSteps: Int,
            val batches: Int,
            val unit: ReaderDualPageUnit,
        ) {
            val total get() = (initial + additional).toSet()
        }
        fun readerNumbers(values: Collection<ReaderPageId>) =
            values.map { it.sourcePageIndex + 1 }.sorted().joinToString(",", "[", "]")
        val cases = mutableListOf<String>()
        val comparisons = mutableListOf<String>()
        for (reset in listOf(false, true)) {
            val world = if (reset) "reader113_RIGHT" else "gray"
            val identity = ReaderDualPageIdentity(
                1,
                1,
                pages.map { page ->
                    ReaderDualPageContentIdentity(
                        page,
                        if (reset && page.sourcePageIndex == 112) "unseen-right-margin" else "gray-$page",
                    )
                },
            )
            val samples = pages.associateWith { page ->
                if (reset && page.sourcePageIndex == 112) right else gray
            }
            val facts = pages.associateWith { page -> portrait.copy(sideHint = samples.getValue(page).sideHint) }
            val full = ReaderSpreadSamplesOwner(identity)
            try {
                pages.forEach { page ->
                    assertTrue(
                        full.acceptOriginal(full.request(page), facts.getValue(page), samples.getValue(page).edges),
                    )
                }
                val oracle = SharedDualPageRules.canonicalPlan(
                    pages,
                    facts,
                    settings.direction,
                    automatic = settings.automatic,
                    distance = { a, b ->
                        (full.edgeAnalysis(a, b, settings.direction) as ReaderEdgeAnalysisEvidence.Computed).score
                    },
                ).single { target in it.pageIds }
                fun closure(initial: List<ReaderPageId>): Closure {
                    val partial = ReaderSpreadSamplesOwner(identity)
                    try {
                        val read = initial.toMutableSet()
                        val additional = mutableListOf<ReaderPageId>()
                        initial.forEach { page ->
                            assertTrue(
                                partial.acceptOriginal(
                                    partial.request(page),
                                    facts.getValue(page),
                                    samples.getValue(page).edges,
                                ),
                            )
                        }
                        var queries = 0
                        var scanSteps = 0
                        var maxScanSteps = 0
                        var batches = 0
                        // Every NeedFacts query must add at least one previously unread original page.
                        // Thus N+1 queries suffice here; 4N also catches an accidental non-progress loop.
                        repeat(4 * pages.size) {
                            queries++
                            val result = ReaderPairingCertifier.certify(identity, headers, partial, settings, target)
                            when (result) {
                                is Certified -> {
                                    scanSteps += result.segment.scanSteps
                                    maxScanSteps = maxOf(maxScanSteps, result.segment.scanSteps)
                                    assertEquals(oracle, result.segment.unit)
                                    assertTrue(result.segment.isCurrent(identity, settings, partial, headers))
                                    return Closure(
                                        initial,
                                        additional,
                                        queries,
                                        scanSteps,
                                        maxScanSteps,
                                        batches,
                                        result.segment.unit,
                                    )
                                }
                                is ReaderPairingCertification.NeedFacts -> {
                                    scanSteps += result.scanSteps
                                    maxScanSteps = maxOf(maxScanSteps, result.scanSteps)
                                    assertTrue(
                                        result.facts.all { need ->
                                            need.kind == ReaderPairingFactKind.ORIGINAL_ANALYSIS ||
                                                need.kind == ReaderPairingFactKind.EDGE_SAMPLES
                                        },
                                    )
                                    // Both edge endpoints come from the production request. Existing original
                                    // samples/scalars remain in the real 16-sample owner and are not fabricated.
                                    val batch = result.facts.flatMap { need ->
                                        listOfNotNull(need.pageId, need.secondPageId)
                                    }.distinct().filter { it !in read }
                                        .take(ReaderDualPageFactLimits.MAX_CONCURRENT_REQUESTS)
                                    assertTrue(batch.isNotEmpty(), "NeedFacts made no new-input progress: $result")
                                    assertTrue(batch.size <= 2)
                                    batches++
                                    batch.forEach { page ->
                                        assertTrue(
                                            partial.acceptOriginal(
                                                partial.request(page),
                                                facts.getValue(page),
                                                samples.getValue(page).edges,
                                            ),
                                        )
                                        assertTrue(read.add(page))
                                        additional += page
                                    }
                                }
                                is ReaderPairingCertification.RetryableBlocked ->
                                    throw AssertionError("Valid fixture input must not be blocked: $result")
                            }
                        }
                        throw AssertionError("Original closure did not terminate within 4N production queries")
                    } finally {
                        partial.close()
                    }
                }
                val warm = closure(observed)
                val cold = closure(emptyList())
                for ((start, result) in listOf("observed115..130" to warm, "coldOriginal" to cold)) {
                    if (start == "observed115..130") {
                        assertTrue(result.total.size > ReaderDualPageFactLimits.MAX_ADDITIONAL_PAGES)
                    }
                    assertEquals(result.batches + 1, result.queries)
                    val exceedsLimit = result.total.size > ReaderDualPageFactLimits.MAX_ADDITIONAL_PAGES
                    cases += "{\"world\":\"$world\",\"start\":\"$start\",\"initialCount\":${result.initial.size}," +
                        "\"newOriginalCount\":${result.additional.size},\"totalOriginalCount\":${result.total.size}," +
                        "\"newOriginalPages\":${readerNumbers(result.additional)}," +
                        "\"totalOriginalPages\":${readerNumbers(result.total)},\"queryCount\":${result.queries}," +
                        "\"scanStepsTotal\":${result.scanSteps},\"scanStepsMaxPerQuery\":${result.maxScanSteps}," +
                        "\"batchCount\":${result.batches},\"certifiedUnit\":${readerNumbers(result.unit.pageIds)}," +
                        "\"exceeds16OriginalInputs\":$exceedsLimit}"
                }
                comparisons += "{\"world\":\"$world\",\"observedInputsAbsentFromColdClosure\":" +
                    readerNumbers(observed.filter { it !in cold.total }) +
                    ",\"totalInputDifference\":${warm.total.size - cold.total.size}}"
            } finally {
                full.close()
            }
        }
        println(
            "R3_PREFIX_CLOSURE {\"targetReaderPage\":126,\"scope\":\"singleTarget\"," +
                "\"nativeNeighborTargets\":\"NOT_OBSERVED_FROM_CURRENT_ART_EVIDENCE\"," +
                "\"units\":{\"pages\":\"reader_1based\",\"originalCount\":\"distinct_fact_inputs\"," +
                "\"scanSteps\":\"production_reducer_steps\",\"queryCount\":\"certifier_calls\"," +
                "\"latency\":\"NOT_MEASURED\",\"bytes\":\"NOT_MEASURED\"}," +
                "\"maxBatchSize\":2,\"maxQueryCount\":${4 * pages.size},\"cases\":[" +
                cases.joinToString(",") + "],\"comparison\":[" + comparisons.joinToString(",") + "]}",
        )
    }

    @Test
    fun `known original side veto avoids unrelated lookbehind after manual priority`() {
        val pages = List(176) { ReaderPageId(ReaderChapterId(73092), it) }
        val observed = (112..127).map(pages::get)
        assertEquals(ReaderDualPageFactLimits.MAX_ADDITIONAL_PAGES, observed.size)
        val target = pages[125]
        val plan = requireNotNull(ReaderOriginalPageSampling.plan(portrait.width, portrait.height))
        fun input(hint: ReaderPageSideHint): ReaderOriginalPageSamples {
            fun color(point: Pair<Int, Int>) = when {
                hint == ReaderPageSideHint.RIGHT && point.first < portrait.width / 20 -> -1
                hint == ReaderPageSideHint.LEFT && point.first >= portrait.width * 19 / 20 -> -1
                else -> 0xff8c8c8c.toInt()
            }
            return plan.samples(plan.left.map(::color), plan.right.map(::color)).also {
                assertEquals(hint, it.sideHint)
            }
        }
        val gray = input(ReaderPageSideHint.UNKNOWN)
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val leading = if (direction == ReaderDirection.RTL) ReaderPageSideHint.RIGHT else ReaderPageSideHint.LEFT
            val trailing = if (direction == ReaderDirection.RTL) ReaderPageSideHint.LEFT else ReaderPageSideHint.RIGHT
            for (hint in listOf(leading, trailing)) {
                for (starts in listOf(emptySet(), setOf(111), setOf(110, 111))) {
                    val settings = ReaderPairingSettings(direction, true, manualPairStarts = starts)
                    val identity = ReaderDualPageIdentity(
                        1,
                        1,
                        pages.map { ReaderDualPageContentIdentity(it, "original-${it.sourcePageIndex}") },
                    )
                    val reset = input(hint)
                    val samples = pages.associateWith { if (it.sourcePageIndex == 112) reset else gray }
                    val facts = pages.associateWith { portrait.copy(sideHint = samples.getValue(it).sideHint) }
                    val headers = pages.associateWith { portrait }
                    val full = ReaderSpreadSamplesOwner(identity)
                    val partial = ReaderSpreadSamplesOwner(identity)
                    try {
                        pages.forEach { page ->
                            assertTrue(
                                full.acceptOriginal(
                                    full.request(page),
                                    facts.getValue(page),
                                    samples.getValue(page).edges,
                                ),
                            )
                            if (page in observed) {
                                assertTrue(
                                    partial.acceptOriginal(
                                        partial.request(page),
                                        facts.getValue(page),
                                        samples.getValue(page).edges,
                                    ),
                                )
                            }
                        }
                        val oracle = SharedDualPageRules.canonicalPlan(
                            pages,
                            facts,
                            direction,
                            automatic = true,
                            manualPairStarts = starts,
                            distance = { a, b ->
                                (full.edgeAnalysis(a, b, direction) as ReaderEdgeAnalysisEvidence.Computed).score
                            },
                        ).single { target in it.pageIds }
                        if (hint == leading && starts.isEmpty()) {
                            assertEquals(listOf(125, 126), oracle.pageIds.map { it.sourcePageIndex + 1 })
                        }
                        val result = ReaderPairingCertifier.certify(identity, headers, partial, settings, target)
                        assertTrue(
                            result is Certified,
                            "A computed $direction $hint reset after manual $starts needs no reader112 original: " +
                                result,
                        )
                        val segment = (result as Certified).segment
                        assertEquals(oracle, segment.unit)
                        assertEquals(ReaderPageAnalysisEvidence.NotAnalyzed, partial.pageAnalysis(pages[111]))
                        assertEquals(
                            ReaderEdgeAnalysisEvidence.NotAnalyzed,
                            partial.edgeAnalysis(pages[111], pages[112], direction),
                        )
                        assertTrue(segment.isCurrent(identity, settings, partial, headers))
                        assertFalse(segment.isCurrent(identity, settings.copy(manualRevision = 1), partial, headers))
                        if (hint == leading && starts.isEmpty()) {
                            assertTrue(pages[112] in segment.dependentPageIds)
                            val next = identity.copy(
                                content = identity.content.map {
                                    if (it.pageId == pages[112]) it.copy(contentVersion = "changed-reset") else it
                                },
                            )
                            val replacement = partial.rebind(next)
                            try {
                                assertFalse(segment.isCurrent(next, settings, replacement, headers))
                            } finally {
                                replacement.close()
                            }
                        }
                    } finally {
                        full.close()
                        partial.close()
                    }
                }
            }
            // An unanalyzed hint in a header is not the owner's Computed original evidence.
            val identity = ReaderDualPageIdentity(
                1,
                1,
                pages.map { ReaderDualPageContentIdentity(it, "unknown-${it.sourcePageIndex}") },
            )
            val uncomputed = ReaderSpreadSamplesOwner(identity)
            try {
                observed.drop(1).forEach {
                    assertTrue(uncomputed.acceptOriginal(uncomputed.request(it), portrait, gray.edges))
                }
                val headers = pages.associateWith { portrait }.toMutableMap()
                headers[pages[112]] = portrait.copy(sideHint = leading)
                val result = ReaderPairingCertifier.certify(
                    identity,
                    headers,
                    uncomputed,
                    ReaderPairingSettings(direction, true),
                    target,
                )
                assertTrue(result is ReaderPairingCertification.NeedFacts)
                assertTrue(
                    (result as ReaderPairingCertification.NeedFacts).facts.any {
                        it.pageId == pages[112] && it.kind == ReaderPairingFactKind.ORIGINAL_ANALYSIS
                    },
                )
            } finally {
                uncomputed.close()
            }
        }
    }

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
