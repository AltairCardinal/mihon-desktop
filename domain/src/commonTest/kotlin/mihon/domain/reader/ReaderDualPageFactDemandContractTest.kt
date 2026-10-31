package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDualPageFactDemandContractTest {

    @Test
    fun `withdrawn speculation can upgrade once without renewing the deadline or consumed bytes`() {
        val budget = ReaderDualPageFactBudget()
        val page = pages.first()
        assertEquals(listOf(page), budget.reserve(listOf(page), 100, required = true, speculative = true))
        assertEquals(1024L, budget.reserveRead(page, 1024, 101))
        budget.complete(page, 0, cacheHit = true)
        assertEquals(listOf(page), budget.reserve(listOf(page), 102, required = true))
        assertEquals(14_998L, budget.remainingWaitMillis(102))
        budget.complete(page, 0, cacheHit = true)
        assertTrue(budget.reserve(listOf(page), 103, required = true).isEmpty(), "Only one real upgrade is admitted")
        val next = pages[1]
        assertEquals(listOf(next), budget.reserve(listOf(next), 104, required = true))
        assertEquals(
            ReaderDualPageFactLimits.MAX_NEW_BYTES - 1024,
            budget.reserveRead(next, Long.MAX_VALUE, 105),
            "Speculative bytes remain charged after promotion",
        )

        val deadline = ReaderDualPageFactBudget()
        assertEquals(listOf(page), deadline.reserve(listOf(page), 100, required = true, speculative = true))
        deadline.complete(page, 0, cacheHit = true)
        assertTrue(deadline.reserve(listOf(page), 15_100, required = true).isEmpty())
    }
    private val pages = List(40) { ReaderPageId(ReaderChapterId(7), it) }

    @Test
    fun `real and speculative demand share one capacity allocation that two sequential reserves cannot express`() {
        val capacity = ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS

        // The allocation C4 must not rely on: sequential reserves share remainingConcurrency.
        val sequential = ReaderDualPageFactBudget()
        assertEquals(capacity, sequential.reserve(pages.take(capacity), 100, required = true).size)
        assertTrue(
            sequential.reserve(pages.drop(capacity).take(3), 100, required = true, speculative = true)
                .isEmpty(),
            "Two sequential reserves starve speculation once real demand fills the shared pool",
        )

        val shared = ReaderDualPageFactBudget()
        val admitted = shared.reserveClassified(pages.take(2), pages.drop(2).take(3), 100)
        assertEquals(capacity, admitted.size, "Speculation must use the capacity real demand left over")
        assertEquals(pages.take(2), admitted.filterNot { it.speculative }.map { it.pageId })
        assertEquals(pages.drop(2).take(2), admitted.filter { it.speculative }.map { it.pageId })
        admitted.forEach { shared.complete(it.pageId, 0, cacheHit = true) }

        val upgraded = ReaderDualPageFactBudget()
        val speculativeFirst = upgraded.reserveClassified(pages.take(1), pages.drop(1).take(2), 100)
        assertEquals(3, speculativeFirst.size)
        speculativeFirst.forEach { upgraded.complete(it.pageId, 0, cacheHit = true) }
        val speculativePage = pages[1]
        assertTrue(
            upgraded.reserveClassified(emptyList(), listOf(speculativePage), 101).isEmpty(),
            "A finished speculative attempt cannot re-enter as speculation",
        )
        val promoted = upgraded.reserveClassified(listOf(speculativePage), emptyList(), 102)
        assertEquals(
            listOf(ReaderDualPageFactBudget.ReservedCapability(speculativePage, speculative = false)),
            promoted,
            "A failed speculation leaves one real recovery opportunity",
        )
        promoted.forEach { upgraded.complete(it.pageId, 0, cacheHit = true) }
        assertTrue(
            upgraded.reserveClassified(listOf(speculativePage), emptyList(), 103).isEmpty(),
            "A real recovery is admitted only once",
        )

        val pressured = ReaderDualPageFactBudget()
        val underPressure = pressured.reserveClassified(pages.take(6), pages.drop(6).take(2), 100)
        assertEquals(capacity, underPressure.size)
        assertTrue(underPressure.none { it.speculative }, "Real demand alone may fill the whole allocation")

        val spent = ReaderDualPageFactBudget()
        spent.reserveClassified(pages.take(capacity), pages.drop(capacity).take(2), 100)
            .forEach { spent.complete(it.pageId, 0, cacheHit = true) }
        assertTrue(spent.reserveClassified(pages.take(1), pages.drop(8).take(2), 101).isNotEmpty())
        spent.close()
        assertTrue(spent.reserveClassified(pages.take(1), pages.drop(1).take(1), 102).isEmpty())
    }

    @Test
    fun `classified reservation still stops at the shared deadline and byte limits`() {
        val budget = ReaderDualPageFactBudget()
        budget.reserveClassified(pages.take(2), pages.drop(2).take(2), 100)
            .forEach { budget.complete(it.pageId, 16L * 1024 * 1024, cacheHit = false) }
        assertTrue(budget.reserveClassified(pages.take(1), pages.drop(1).take(1), 101).isEmpty())
        val fresh = ReaderDualPageFactBudget()
        fresh.reserveClassified(pages.take(2), pages.drop(2).take(1), 100)
            .forEach { fresh.complete(it.pageId, 0, cacheHit = true) }
        assertTrue(
            fresh.reserveClassified(pages.take(1), pages.drop(1).take(1), 15_101).isEmpty(),
            "Deadline stops classified reservation",
        )
    }

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

    @Test
    fun `required demand exceeds sixteen page cap while optional capped and sharing limits`() {
        val budget = ReaderDualPageFactBudget()
        val optionalAdmitted = mutableListOf<ReaderPageId>()
        while (optionalAdmitted.size < 16) {
            val next = budget.reserve(pages, 100, required = false)
            optionalAdmitted += next
            next.forEach { budget.complete(it, 1024, cacheHit = false) }
        }
        assertEquals(16, optionalAdmitted.size)
        assertTrue(budget.reserve(pages, 101, required = false).isEmpty(), "Optional must be capped at 16")

        val requiredAdmitted = budget.reserve(pages.drop(16), 102, required = true)
        assertEquals(
            pages.subList(16, 16 + ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS),
            requiredAdmitted,
            "Required must not be blocked by 16 optional page cap",
        )
        requiredAdmitted.forEach { budget.complete(it, 1024, cacheHit = false) }

        val requiredMore = budget.reserve(pages.drop(20), 103, required = true)
        assertEquals(
            pages.subList(20, 20 + ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS),
            requiredMore,
        )
        requiredMore.forEach { budget.complete(it, 1024, cacheHit = false) }

        assertTrue(budget.reserve(pages, 104, required = false).isEmpty(), "Optional is still capped")

        val concurrent = budget.reserve(pages.drop(24), 105, required = true)
        assertEquals(ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS, concurrent.size)
        assertTrue(budget.reserve(pages.drop(28), 105, required = true).isEmpty(), "Concurrency cap must apply")
        assertTrue(budget.reserve(pages.drop(28), 105, required = false).isEmpty())
        concurrent.forEach { budget.complete(it, 1024, cacheHit = false) }

        val byteTest = budget.reserve(pages.drop(28), 106, required = true)
        assertEquals(ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS, byteTest.size)
        budget.complete(byteTest[0], 32L * 1024 * 1024, cacheHit = false)
        byteTest.drop(1).forEach { budget.complete(it, 0, cacheHit = true) }
        assertTrue(budget.reserve(pages.drop(32), 107, required = true).isEmpty(), "Byte limit stops required")
        assertTrue(budget.reserve(pages.drop(32), 107, required = false).isEmpty(), "Byte limit stops optional")

        val deadlineBudget = ReaderDualPageFactBudget()
        val first = deadlineBudget.reserve(pages, 100, required = true)
        first.forEach { deadlineBudget.complete(it, 0, cacheHit = true) }
        assertTrue(
            deadlineBudget.reserve(pages.drop(4), 15_101, required = true).isEmpty(),
            "Deadline stops required",
        )
        assertTrue(
            deadlineBudget.reserve(pages.drop(4), 15_101, required = false).isEmpty(),
            "Deadline stops optional",
        )

        val closeBudget = ReaderDualPageFactBudget()
        closeBudget.close()
        assertTrue(closeBudget.reserve(pages, 100, required = true).isEmpty())
        assertTrue(closeBudget.reserve(pages, 100, required = false).isEmpty())
    }
}
