package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPairingDependencyRecoveryAcceptanceTest {
    private val pages = List(12) { ReaderPageId(ReaderChapterId(73L), it) }
    private val identity = ReaderDualPageIdentity(
        1L,
        1L,
        pages.map { ReaderDualPageContentIdentity(it, "original-${it.sourcePageIndex}") },
    )

    @Test
    fun `pending proof retains already read evidence without requiring the whole chapter`() {
        val owner = ReaderSpreadSamplesOwner(identity)
        val headers = mapOf(pages[1] to ReaderPageFact.dimensions(980, 1403))
        val settings = ReaderPairingSettings(ReaderDirection.RTL, automatic = false)
        try {
            val trace = ReaderPairingCertifier.certifyWithDependencies(identity, headers, owner, settings, pages[1])
            assertTrue(trace.certification is ReaderPairingCertification.NeedFacts)
            assertTrue(
                pages[1] in trace.dependentPageIds,
                "The known A remains a dependency while another fact is missing",
            )
            val missing = (trace.certification as ReaderPairingCertification.NeedFacts).facts
                .flatMap { listOfNotNull(it.pageId, it.secondPageId) }.toSet()
            assertTrue(trace.dependentPageIds.containsAll(missing), "The trace also retains the actual missing reads")
            assertFalse(pages.last() in trace.dependentPageIds, "Untouched chapter pages cannot become a gate")
            assertEquals(
                ReaderPairingCertifier.certify(identity, headers, owner, settings, pages[1]),
                trace.certification,
                "The existing certification result must keep its original semantics",
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun `an isolated spread trace excludes unrelated unknown prefix and tail`() {
        val owner = ReaderSpreadSamplesOwner(identity)
        val headers = mapOf(pages[8] to ReaderPageFact.dimensions(1800, 1000))
        val settings = ReaderPairingSettings(ReaderDirection.RTL, automatic = true)
        try {
            val trace = ReaderPairingCertifier.certifyWithDependencies(identity, headers, owner, settings, pages[8])
            assertTrue(trace.certification is ReaderPairingCertification.Certified)
            val certified = trace.certification as ReaderPairingCertification.Certified
            assertEquals(setOf(pages[8]), trace.dependentPageIds)
            assertEquals(certified.segment.dependentPageIds, trace.dependentPageIds)
            val original = ReaderPairingCertifier.certify(identity, headers, owner, settings, pages[8])
                as ReaderPairingCertification.Certified
            assertEquals(original.segment.unit, certified.segment.unit)
        } finally {
            owner.close()
        }
    }
}
