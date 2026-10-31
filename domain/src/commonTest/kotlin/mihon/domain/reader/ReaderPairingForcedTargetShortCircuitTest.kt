package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A forced single page is a hard boundary of the real scan: `allowed()` rejects
 * every pair that touches it, so the target can only ever become a single page
 * unit and its reason is one of the real reason priorities.
 *
 * The certifier nevertheless entered the chapter at `target - 2` and asked the
 * real scan about the left neighbour. That read cannot change the unit of a
 * forced target, but it could hold the target pending for an original analysis
 * of a page the user never asked about. The short circuit enters the scan at
 * the target itself, so the certificate depends on the target's own facts only.
 */
class ReaderPairingForcedTargetShortCircuitTest {
    /** The real pixel prototypes of the research family; the side hint is derived, never declared. */
    private enum class Pixel { GRAY, LEFT, RIGHT, WIDE }

    private data class Sampled(val fact: ReaderPageFact, val original: ReaderOriginalPageSamples)

    private fun sample(pixel: Pixel): Sampled {
        val width = if (pixel == Pixel.WIDE) 512 else 256
        val height = 384
        fun argb(x: Int, y: Int): Int = when {
            pixel == Pixel.LEFT && x >= width * 19 / 20 -> -1
            pixel == Pixel.RIGHT && x < width / 20 -> -1
            else -> 0xFF8C8C8C.toInt()
        }
        val plan = requireNotNull(ReaderOriginalPageSampling.plan(width, height))
        val original = plan.samples(
            plan.left.map { (x, y) -> argb(x, y) },
            plan.right.map { (x, y) -> argb(x, y) },
        )
        val expected = when (pixel) {
            Pixel.LEFT -> ReaderPageSideHint.LEFT
            Pixel.RIGHT -> ReaderPageSideHint.RIGHT
            else -> ReaderPageSideHint.UNKNOWN
        }
        check(original.sideHint == expected) { "The real sampler must derive $expected from the pixels" }
        return Sampled(ReaderPageFact.dimensions(width, height).copy(sideHint = original.sideHint), original)
    }

    private fun pages(count: Int) = List(count) { ReaderPageId(ReaderChapterId(9311), it) }

    private fun identityOf(pages: List<ReaderPageId>) = ReaderDualPageIdentity(
        1L,
        1L,
        pages.map { ReaderDualPageContentIdentity(it, "content-$it") },
    )

    private fun settings(
        direction: ReaderDirection,
        automatic: Boolean,
        forced: Set<ReaderPageId>,
        opening: ReaderChapterOpeningMode = ReaderChapterOpeningMode.AUTO,
    ) = ReaderPairingSettings(
        direction = direction,
        automatic = automatic,
        forcedSinglePages = forced,
        manualPairStarts = emptySet(),
        openingMode = opening,
    )

    /** The owner that holds the real analysis of every page; it is the oracle of the complete plan. */
    private fun completeOwner(
        identity: ReaderDualPageIdentity,
        sampled: Map<ReaderPageId, Sampled>,
    ): ReaderSpreadSamplesOwner = ReaderSpreadSamplesOwner(identity).also { owner ->
        for ((page, value) in sampled) {
            check(owner.acceptOriginal(owner.request(page), value.fact, value.original.edges)) { "accept $page" }
        }
    }

    /** The owner that holds the real analysis of the target only. */
    private fun targetOwner(
        identity: ReaderDualPageIdentity,
        sampled: Map<ReaderPageId, Sampled>,
        target: ReaderPageId,
    ): ReaderSpreadSamplesOwner = ReaderSpreadSamplesOwner(identity).also { owner ->
        val value = sampled.getValue(target)
        check(owner.acceptOriginal(owner.request(target), value.fact, value.original.edges)) { "accept $target" }
    }

    /** The complete plan of the same facts; every page keeps its own real analysis. */
    private fun oracle(
        pages: List<ReaderPageId>,
        facts: Map<ReaderPageId, ReaderPageFact>,
        owner: ReaderSpreadSamplesOwner,
        direction: ReaderDirection,
        forced: Set<ReaderPageId>,
        opening: ReaderChapterOpeningMode,
        automatic: Boolean,
        target: ReaderPageId,
    ): ReaderDualPageUnit = SharedDualPageRules.canonicalPlan(
        pages = pages,
        facts = facts,
        direction = direction,
        forcedSinglePages = forced,
        manualPairStarts = emptySet(),
        openingMode = opening,
        automatic = automatic,
        distance = { first, second ->
            val evidence = owner.edgeAnalysis(first, second, direction)
            check(evidence is ReaderEdgeAnalysisEvidence.Computed) { "The complete owner must know the edge" }
            evidence.score
        },
    ).single { target in it.pageIds }

    @Test
    fun `a forced target certifies from its own page without the left neighbour analysis`() {
        val pages = pages(4)
        val identity = identityOf(pages)
        val target = pages[2]
        val sampled = pages.associateWith { sample(Pixel.GRAY) }
        val facts = sampled.mapValues { it.value.fact }
        val headers = facts.mapValues { it.value.copy(sideHint = ReaderPageSideHint.UNKNOWN) }
        val complete = completeOwner(identity, sampled)
        val partial = targetOwner(identity, sampled, target)
        try {
            val trace = ReaderPairingCertifier.certifyWithDependencies(
                identity,
                headers,
                partial,
                settings(ReaderDirection.LTR, automatic = true, forced = setOf(target)),
                target,
            )
            val certified = trace.certification as ReaderPairingCertification.Certified
            assertEquals(
                listOf(target),
                certified.segment.unit.pageIds,
                "A forced target can never become part of a pair",
            )
            assertEquals(
                setOf(target),
                trace.dependentPageIds,
                "The certificate of a forced target may depend on the target only",
            )
            assertEquals(
                oracle(
                    pages,
                    facts,
                    complete,
                    ReaderDirection.LTR,
                    setOf(target),
                    ReaderChapterOpeningMode.AUTO,
                    true,
                    target,
                ),
                certified.segment.unit,
                "The certified unit must equal the complete plan",
            )
        } finally {
            complete.close()
            partial.close()
        }
    }

    @Test
    fun `a forced target without its own analysis asks for the target and not for its neighbour`() {
        val pages = pages(4)
        val identity = identityOf(pages)
        val target = pages[2]
        val neighbour = pages[1]
        val sampled = pages.associateWith { sample(Pixel.GRAY) }
        val headers = sampled.mapValues { it.value.fact.copy(sideHint = ReaderPageSideHint.UNKNOWN) }
        val partial = ReaderSpreadSamplesOwner(identity)
        try {
            val pending = ReaderPairingCertifier.certify(
                identity,
                headers,
                partial,
                settings(ReaderDirection.LTR, automatic = true, forced = setOf(target)),
                target,
            ) as ReaderPairingCertification.NeedFacts
            assertTrue(
                pending.facts.any { it.pageId == target && it.kind == ReaderPairingFactKind.ORIGINAL_ANALYSIS },
                "The automatic side of the target still needs the target's own analysis: ${pending.facts}",
            )
            assertFalse(
                pending.facts.any { it.pageId == neighbour },
                "The left neighbour cannot change a forced target: ${pending.facts}",
            )
        } finally {
            partial.close()
        }
    }

    @Test
    fun `a forced target with automatic off certifies from the header alone`() {
        val pages = pages(4)
        val identity = identityOf(pages)
        val target = pages[2]
        val sampled = pages.associateWith { sample(Pixel.GRAY) }
        val facts = sampled.mapValues { it.value.fact }
        val headers = facts.mapValues { it.value.copy(sideHint = ReaderPageSideHint.UNKNOWN) }
        val complete = completeOwner(identity, sampled)
        val partial = ReaderSpreadSamplesOwner(identity)
        try {
            val certified = ReaderPairingCertifier.certify(
                identity,
                headers,
                partial,
                settings(ReaderDirection.LTR, automatic = false, forced = setOf(target)),
                target,
            ) as ReaderPairingCertification.Certified
            assertEquals(
                oracle(
                    pages,
                    facts,
                    complete,
                    ReaderDirection.LTR,
                    setOf(target),
                    ReaderChapterOpeningMode.AUTO,
                    false,
                    target,
                ),
                certified.segment.unit,
                "Without the automatic side the complete header is enough",
            )
        } finally {
            complete.close()
            partial.close()
        }
    }

    @Test
    fun `a forced target keeps the real reason priority at the chapter edges`() {
        val cases = listOf(
            Triple(1, 0, ReaderDualPageSingleReason.ONLY_PAGE),
            Triple(4, 0, ReaderDualPageSingleReason.FIRST_PAGE),
            Triple(4, 3, ReaderDualPageSingleReason.MANUAL_BOUNDARY),
        )
        for ((count, targetIndex, reason) in cases) {
            val pages = pages(count)
            val identity = identityOf(pages)
            val target = pages[targetIndex]
            val sampled = pages.associateWith { sample(Pixel.GRAY) }
            val facts = sampled.mapValues { it.value.fact }
            val headers = facts.mapValues { it.value.copy(sideHint = ReaderPageSideHint.UNKNOWN) }
            val complete = completeOwner(identity, sampled)
            val partial = targetOwner(identity, sampled, target)
            try {
                val certified = ReaderPairingCertifier.certify(
                    identity,
                    headers,
                    partial,
                    settings(ReaderDirection.LTR, automatic = true, forced = setOf(target)),
                    target,
                ) as ReaderPairingCertification.Certified
                assertEquals(
                    reason,
                    certified.segment.unit.singleReason,
                    "The real reason priority must survive the short circuit",
                )
                assertEquals(
                    oracle(
                        pages,
                        facts,
                        complete,
                        ReaderDirection.LTR,
                        setOf(target),
                        ReaderChapterOpeningMode.AUTO,
                        true,
                        target,
                    ),
                    certified.segment.unit,
                    "The certified edge unit must equal the complete plan",
                )
            } finally {
                complete.close()
                partial.close()
            }
        }
    }

    @Test
    fun `a failed target analysis stays a retryable block instead of a false single page`() {
        val pages = pages(4)
        val identity = identityOf(pages)
        val target = pages[2]
        val sampled = pages.associateWith { sample(Pixel.GRAY) }
        val headers = sampled.mapValues { it.value.fact.copy(sideHint = ReaderPageSideHint.UNKNOWN) }
        val partial = ReaderSpreadSamplesOwner(identity)
        try {
            assertTrue(partial.failOriginal(partial.request(target), ReaderAnalysisFailure.RESOURCE))
            val blocked = ReaderPairingCertifier.certify(
                identity,
                headers,
                partial,
                settings(ReaderDirection.LTR, automatic = true, forced = setOf(target)),
                target,
            ) as ReaderPairingCertification.RetryableBlocked
            assertEquals(
                ReaderAnalysisFailure.RESOURCE,
                blocked.failures[target],
                "A recoverable failure must stay visible instead of becoming a final answer",
            )
        } finally {
            partial.close()
        }
    }

    @Test
    fun `a changed forced setting or target content invalidates the forced target certificate`() {
        val pages = pages(4)
        val identity = identityOf(pages)
        val target = pages[2]
        val sampled = pages.associateWith { sample(Pixel.GRAY) }
        val headers = sampled.mapValues { it.value.fact.copy(sideHint = ReaderPageSideHint.UNKNOWN) }
        val partial = targetOwner(identity, sampled, target)
        try {
            val forced = setOf(target)
            val forcedSettings = settings(ReaderDirection.LTR, automatic = true, forced = forced)
            val unforcedSettings = settings(ReaderDirection.LTR, automatic = true, forced = emptySet())
            val segment = (
                ReaderPairingCertifier.certify(
                    identity,
                    headers,
                    partial,
                    forcedSettings,
                    target,
                ) as ReaderPairingCertification.Certified
                ).segment

            assertTrue(
                segment.isCurrent(identity, forcedSettings, partial, headers),
                "The certificate must stay current for the same input",
            )
            assertFalse(
                segment.isCurrent(identity, unforcedSettings, partial, headers),
                "The forced boundary is part of the certificate",
            )
            val changed = identity.copy(
                content = identity.content.map {
                    if (it.pageId == target) it.copy(contentVersion = "new-pixels/v1") else it
                },
            )
            assertFalse(
                segment.isCurrent(changed, forcedSettings, partial, headers),
                "New target pixels must invalidate the certificate",
            )
        } finally {
            partial.close()
        }
    }

    @Test
    fun `the forced target family certifies every state that does not need the target analysis`() {
        val pages = pages(4)
        val identity = identityOf(pages)
        val target = pages[2]
        val neighbour = pages[1]
        var certified = 0
        var pending = 0
        val pendingKeys = mutableListOf<String>()
        for (pixel in Pixel.entries) {
            for (direction in listOf(ReaderDirection.LTR, ReaderDirection.RTL)) {
                for (opening in ReaderChapterOpeningMode.entries) {
                    for (automatic in listOf(false, true)) {
                        for (available in listOf(false, true)) {
                            val key = "$pixel-$direction-$opening-$automatic-$available"
                            val sampled = pages.associateWith {
                                sample(if (it == target) pixel else Pixel.GRAY)
                            }
                            val facts = sampled.mapValues { it.value.fact }
                            val headers = facts.mapValues { it.value.copy(sideHint = ReaderPageSideHint.UNKNOWN) }
                            val complete = completeOwner(identity, sampled)
                            val partial = ReaderSpreadSamplesOwner(identity)
                            try {
                                if (available) {
                                    val value = sampled.getValue(target)
                                    check(
                                        partial.acceptOriginal(
                                            partial.request(target),
                                            value.fact,
                                            value.original.edges,
                                        ),
                                    )
                                }
                                val forced = setOf(target)
                                val plan = oracle(
                                    pages,
                                    facts,
                                    complete,
                                    direction,
                                    forced,
                                    opening,
                                    automatic,
                                    target,
                                )
                                val result = ReaderPairingCertifier.certify(
                                    identity,
                                    headers,
                                    partial,
                                    settings(direction, automatic, forced, opening),
                                    target,
                                )
                                when (result) {
                                    is ReaderPairingCertification.Certified -> {
                                        certified++
                                        assertEquals(
                                            plan,
                                            result.segment.unit,
                                            "A certified forced target must equal the complete plan: $key",
                                        )
                                    }
                                    is ReaderPairingCertification.NeedFacts -> {
                                        pending++
                                        pendingKeys += key
                                        assertTrue(
                                            result.facts.any { it.pageId == target },
                                            "A pending forced target must ask for its own fact: $key",
                                        )
                                        assertFalse(
                                            result.facts.any { it.pageId == neighbour },
                                            "The left neighbour cannot change a forced target: $key",
                                        )
                                    }
                                    else -> error("Unexpected certification for $key: $result")
                                }
                            } finally {
                                complete.close()
                                partial.close()
                            }
                        }
                    }
                }
            }
        }
        assertEquals(96, certified + pending, "The research family must stay complete")
        assertEquals(
            78,
            certified,
            "Every state that does not need the target analysis must certify",
        )
        assertEquals(
            18,
            pending,
            "Only the portrait target without its own analysis may stay pending",
        )
        assertTrue(
            pendingKeys.all { it.contains("-true-false") },
            "The pending states are the portrait target without its own analysis: $pendingKeys",
        )
        assertTrue(
            pendingKeys.none { it.startsWith("${Pixel.WIDE}-") },
            "A wide target keeps its own isolated spread entry: $pendingKeys",
        )
    }
}
