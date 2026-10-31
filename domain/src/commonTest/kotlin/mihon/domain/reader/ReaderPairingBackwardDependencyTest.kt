package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A backward pairing cannot be certified from the pages around the target alone.
 *
 * The certifier starts a backward scan two pages before the target, because the
 * scan compares the candidate pair against its right neighbour and a later pair
 * may win. That comparison reads the original analysis of the earlier page and
 * the edge samples of two adjacent pairs, and an edge sample can only exist when
 * both of its endpoints have their original analysis. When the finite entries
 * disagree, the scan moves one page further left and repeats, so the real
 * dependency has no fixed distance from the target.
 *
 * This is the minimal counterexample for the desktop contract "the pairing must
 * not request a page below target - 1": certifying the same backward pairing
 * correctly requires a page below target - 1, so that contract and the canonical
 * oracle cannot both hold. The test locks the canonical dependency down so the
 * desktop expectation can be corrected instead of the oracle being weakened.
 */
class ReaderPairingBackwardDependencyTest {
    private val pageCount = 24
    private val targetOrdinal = 15

    private val pages = List(pageCount) { ReaderPageId(ReaderChapterId(20261030), it) }

    private val identity = ReaderDualPageIdentity(
        1L,
        1L,
        pages.map { ReaderDualPageContentIdentity(it, "content-$it") },
    )

    private val headers = pages.associateWith { ReaderPageFact.dimensions(256, 384) }

    private val settings = ReaderPairingSettings(
        direction = ReaderDirection.RTL,
        automatic = true,
        forcedSinglePages = emptySet(),
        manualPairStarts = emptySet(),
        openingMode = ReaderChapterOpeningMode.FIRST_PAIR,
        manualRevision = 0L,
    )

    private fun neededOrdinals(certification: ReaderPairingCertification): Set<Int> {
        val facts = when (certification) {
            is ReaderPairingCertification.NeedFacts -> certification.facts
            is ReaderPairingCertification.RetryableBlocked -> certification.facts
            is ReaderPairingCertification.Certified -> emptySet()
        }
        return facts.flatMap { need -> listOfNotNull(need.pageId, need.secondPageId) }
            .map { it.sourcePageIndex }
            .toSet()
    }

    @Test
    fun `a backward pairing needs a page before the target minus one`() {
        val owner = ReaderSpreadSamplesOwner(identity, 16)
        try {
            val result = ReaderPairingCertifier.certifyWithDependencies(
                identity,
                headers,
                owner,
                settings,
                pages[targetOrdinal],
            )
            val ordinals = neededOrdinals(result.certification)
            assertTrue(
                ordinals.any { it < targetOrdinal - 1 },
                "the canonical certification needs a page below target-1: $ordinals",
            )
            assertTrue(
                result.dependentPageIds.any { it.sourcePageIndex < targetOrdinal - 1 },
                "the recorded read set contains a page below target-1: ${result.dependentPageIds}",
            )
        } finally {
            owner.close()
        }
    }
}
