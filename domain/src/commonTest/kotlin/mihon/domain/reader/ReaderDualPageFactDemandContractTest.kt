package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDualPageFactDemandContractTest {
    private val pages = List(40) { ReaderPageId(ReaderChapterId(7), it) }

    @Test
    fun `reverse navigation shifts existing fact and sample demand to its source window`() {
        val identity = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "original") })
        val owner = SharedDualPageSession(identity, ReaderDirection.RTL, pages[16])
        (13..16).forEach {
            assertTrue(owner.acceptFact(owner.requestFact(pages[it]), ReaderPageFact.dimensions(100, 200)))
        }
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        // Cover 0 is single; the canonical neighboring groups are 13/14 and 15/16.
        // The requested source remains 16, rather than becoming a new local pair start.
        assertEquals(pages[16], owner.location!!.pageId)
        assertEquals(listOf(pages[15], pages[16]), owner.targetUnit!!.pageIds)
        assertTrue(owner.navigate(false))
        assertEquals(pages[14], owner.location!!.pageId)
        assertEquals(listOf(pages[13], pages[14]), owner.targetUnit!!.pageIds)
        assertEquals(listOf(pages[10], pages[11], pages[12]), owner.factDependencies())
        assertEquals(listOf(pages[13], pages[14]), owner.spreadSampleDependencies())
        assertTrue(owner.displayedHistory.isEmpty(), "Demand and navigation do not manufacture a physical draw")
    }

    @Test
    fun `direct target dependencies stay within five forward pages without unknown prefix`() {
        val identity = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "original") })
        val owner = SharedDualPageSession(identity, ReaderDirection.RTL, pages[16])
        owner.acceptFact(owner.requestFact(pages[16]), ReaderPageFact.dimensions(100, 200))
        owner.acceptFact(owner.requestFact(pages[9]), ReaderPageFact.dimensions(200, 100))
        assertEquals(listOf(18, 19, 20), owner.factDependencies().map { it.sourcePageIndex })
        (16..20).forEach { owner.acceptFact(owner.requestFact(pages[it]), ReaderPageFact.dimensions(100, 200)) }
        assertTrue(owner.factDependencies().isEmpty(), "A certain target unit must not request unrelated facts")
    }

    @Test
    fun `normal nearby facts can prepare a future edge before first pixels without prefix requests`() {
        val identity = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "original") })
        val owner = SharedDualPageSession(identity, ReaderDirection.RTL, pages[16])
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        (19..20).forEach { owner.acceptFact(owner.requestFact(pages[it]), ReaderPageFact.dimensions(100, 200)) }
        assertEquals(listOf(pages[19], pages[20]), owner.spreadSampleDependencies())
        assertTrue(owner.displayedHistory.isEmpty())
        assertTrue(owner.factDependencies().all { it.sourcePageIndex in 16..20 })
    }

    @Test
    fun `navigation cancels outstanding samples but retains confirmed same content edge samples`() {
        val identity = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "original") })
        val owner = SharedDualPageSession(identity, ReaderDirection.RTL, pages[16])
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        (16..20).forEach { owner.acceptFact(owner.requestFact(pages[it]), ReaderPageFact.dimensions(100, 200)) }
        val artwork = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
        val samples = ReaderSpreadEdgeSamples(artwork, artwork)
        assertTrue(owner.acceptSpreadSample(requireNotNull(owner.requestSpreadSample(pages[19])), samples))
        val pending = requireNotNull(owner.requestSpreadSample(pages[20]))
        assertTrue(owner.goToPage(pages[30]))
        assertFalse(owner.ownsSpreadSampleRequest(pending))
        assertFalse(owner.acceptSpreadSample(pending, samples))
        assertTrue(owner.goToPage(pages[16]))
        assertEquals(listOf(pages[16], pages[17]), owner.spreadSampleDependencies())
        (16..18).forEach { owner.acceptSpreadSample(requireNotNull(owner.requestSpreadSample(pages[it])), samples) }
        assertEquals(listOf(pages[20]), owner.spreadSampleDependencies())
    }

    @Test
    fun `direction change cancels old optional capability without waiting for its decoder`() {
        val identity = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "original") })
        val owner = SharedDualPageSession(identity, ReaderDirection.RTL, pages[16])
        owner.configure(ReaderDirection.RTL, automaticMatching = true)
        owner.acceptFact(owner.requestFact(pages[17]), ReaderPageFact.dimensions(100, 200))
        val pending = requireNotNull(owner.requestSpreadSample(pages[17]))
        owner.configure(ReaderDirection.LTR, automaticMatching = true)
        assertFalse(owner.ownsSpreadSampleRequest(pending))
        assertEquals(pages[16], owner.location!!.pageId)
    }

    @Test
    fun `supplementation admits at most two in flight and sixteen additional pages per location`() {
        val budget = ReaderDualPageFactBudget()
        assertEquals(pages.take(2), budget.reserve(pages, 0))
        assertTrue(budget.reserve(pages, 0).isEmpty())
        val admitted = pages.take(2).toMutableList()
        while (admitted.size < 16) {
            admitted.takeLast(2).forEach { budget.complete(it, 1024, cacheHit = false) }
            admitted += budget.reserve(pages, 100)
        }
        admitted.takeLast(2).forEach { budget.complete(it, 0, cacheHit = true) }
        assertEquals(16, admitted.size)
        assertTrue(budget.reserve(pages, 101).isEmpty())
    }

    @Test
    fun `new bytes or fifteen seconds stop extensions while cache hits do not consume byte budget`() {
        val bytes = ReaderDualPageFactBudget()
        val first = bytes.reserve(pages, 100)
        first.forEach { bytes.complete(it, 64L * 1024 * 1024, cacheHit = true) }
        val second = bytes.reserve(pages, 101)
        assertEquals(2, second.size)
        bytes.complete(second.first(), 32L * 1024 * 1024, cacheHit = false)
        assertTrue(bytes.reserve(pages, 102).isEmpty())
        val deadline = ReaderDualPageFactBudget()
        val admitted = deadline.reserve(pages, 100)
        admitted.forEach { deadline.complete(it, 0, cacheHit = true) }
        assertTrue(deadline.reserve(pages, 15_100).isEmpty())
        deadline.close()
        assertTrue(deadline.reserve(pages, 100).isEmpty())
    }

    @Test
    fun `streaming grants cannot read beyond remaining shared bytes or the current deadline`() {
        val budget = ReaderDualPageFactBudget()
        val admitted = budget.reserve(pages, 100)
        assertEquals(32L * 1024 * 1024, budget.reserveRead(admitted[0], Long.MAX_VALUE, 101))
        assertEquals(0L, budget.reserveRead(admitted[1], 8192, 101))
        budget.returnUnreadBytes(admitted[0], 8192)
        assertEquals(8192L, budget.reserveRead(admitted[1], 16384, 102))
        assertEquals(14_998L, budget.remainingWaitMillis(102))
        assertEquals(0L, budget.reserveRead(admitted[1], 8192, 15_100))
        budget.close()
        assertEquals(0L, budget.reserveRead(admitted[0], 8192, 103))
    }
}
