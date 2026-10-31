package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPairingOpeningEntryContractTest {
    @Test
    fun `page one retains the opening flag after the real chapter entry emits page zero singly`() {
        val pages = List(6) { ReaderPageId(ReaderChapterId(20261027), it) }
        val identity = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "page-$it") })
        val originals = pages.indices.map { index ->
            readerPairingTestOriginal(ReaderPairingTestPixels.GRAY, width = if (index in setOf(2, 4)) 512 else 256)
        }
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val complete = ReaderSpreadSamplesOwner(identity)
            val partial = ReaderSpreadSamplesOwner(identity)
            try {
                pages.forEachIndexed { index, page ->
                    val (fact, samples) = originals[index]
                    assertTrue(complete.acceptOriginal(complete.request(page), fact, samples.edges))
                    if (index > 0) assertTrue(partial.acceptOriginal(partial.request(page), fact, samples.edges))
                }
                val facts = pages.associateWith {
                    (complete.pageAnalysis(it) as ReaderPageAnalysisEvidence.Computed).fact
                }
                val record = ChapterPairingRecord(2, pages.size, setOf(1))
                assertTrue(record.isValidFor(pages.size))
                val forced = setOf(pages[1])
                val settings = ReaderPairingSettings(direction, automatic = true, forcedSinglePages = forced)
                val scan = ReaderCanonicalPairingScan(
                    pages,
                    { facts[pages[it]] },
                    { facts.getValue(pages[it]).sideHint },
                    direction,
                    forced,
                    emptySet(),
                    ReaderChapterOpeningMode.AUTO,
                    true,
                    { facts[pages[it]]?.sideHint },
                ) { _, _ -> null }
                val entry = scan.step(ReaderPairingScanCursor(0, true))
                assertEquals(listOf(pages[0]), entry.unit!!.pageIds)
                assertEquals(ReaderPairingScanCursor(1, true), entry.next)
                val oracle = SharedDualPageRules.canonicalPlan(
                    pages,
                    facts,
                    direction,
                    forcedSinglePages = forced,
                    automatic = true,
                ).single { pages[3] in it.pageIds }
                val headers = facts.filterKeys { it != pages[0] }
                val result = ReaderPairingCertifier.certify(identity, headers, partial, settings, pages[3])
                assertTrue(
                    result is ReaderPairingCertification.Certified,
                    "An unreachable opening=false entry must not require missing page zero: $direction $result",
                )
                val segment = (result as ReaderPairingCertification.Certified).segment
                assertEquals(1, segment.firstIndex)
                assertEquals(oracle, segment.unit)
                assertEquals(ReaderDualPageSingleReason.OPENING, segment.unit.singleReason)
                assertTrue(segment.isCurrent(identity, settings, partial, headers))
            } finally {
                complete.close()
                partial.close()
            }
        }
    }
}
