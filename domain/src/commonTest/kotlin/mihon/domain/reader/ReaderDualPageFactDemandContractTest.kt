package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDualPageFactDemandContractTest {
    private val pages = List(40) { ReaderPageId(ReaderChapterId(7), it) }

    @Test
    fun `direct target dependencies prioritize neighbor and boundary within the same chapter`() {
        val identity = ReaderDualPageIdentity(1, 1, pages.map { ReaderDualPageContentIdentity(it, "original") })
        val owner = SharedDualPageSession(identity, ReaderDirection.RTL, pages[16])
        owner.acceptFact(owner.requestFact(pages[16]), ReaderPageFact.dimensions(100, 200))
        owner.acceptFact(owner.requestFact(pages[9]), ReaderPageFact.dimensions(200, 100))
        assertEquals(listOf(17, 15, 14, 13, 12, 11, 10), owner.factDependencies().map { it.sourcePageIndex })
        (10..17).forEach { owner.acceptFact(owner.requestFact(pages[it]), ReaderPageFact.dimensions(100, 200)) }
        assertTrue(owner.factDependencies().isEmpty(), "A certain target unit must not request unrelated facts")
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
