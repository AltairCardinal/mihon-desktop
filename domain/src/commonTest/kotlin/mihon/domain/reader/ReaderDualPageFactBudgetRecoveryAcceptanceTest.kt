package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDualPageFactBudgetRecoveryAcceptanceTest {
    private val page = ReaderPageId(ReaderChapterId(7), 1)
    private val nextPage = ReaderPageId(ReaderChapterId(7), 2)

    @Test
    fun `classified and legacy speculative exits retain exactly one real recovery`() {
        for (classified in listOf(false, true)) {
            val budget = ReaderDualPageFactBudget()
            fun reserve(id: ReaderPageId, speculative: Boolean, nowMillis: Long): List<ReaderPageId> = if (classified) {
                budget.reserveClassified(
                    real = if (speculative) emptyList() else listOf(id),
                    speculative = if (speculative) listOf(id) else emptyList(),
                    nowMillis = nowMillis,
                ).map { it.pageId }
            } else {
                budget.reserve(listOf(id), nowMillis, required = true, speculative = speculative)
            }

            assertEquals(listOf(page), reserve(page, speculative = true, nowMillis = 100), "classified=$classified")
            assertEquals(1024L, budget.reserveRead(page, 1024, 101))
            budget.complete(page, 0, cacheHit = true)
            assertEquals(
                listOf(page),
                reserve(page, speculative = false, nowMillis = 102),
                "Exited speculation must not spend the real recovery: classified=$classified",
            )
            assertEquals(14_998L, budget.remainingWaitMillis(102), "Recovery cannot renew the deadline")
            budget.complete(page, 0, cacheHit = true)
            assertTrue(
                reserve(page, speculative = false, nowMillis = 103).isEmpty(),
                "Only one real attempt is admitted",
            )
            assertEquals(listOf(nextPage), reserve(nextPage, speculative = false, nowMillis = 104))
            assertEquals(
                ReaderDualPageFactLimits.MAX_NEW_BYTES - 1024,
                budget.reserveRead(nextPage, Long.MAX_VALUE, 105),
                "Recovery cannot refund the bytes consumed by speculation",
            )
        }
    }

    @Test
    fun `a deferred optional attempt can be admitted again`() {
        val budget = ReaderDualPageFactBudget()
        assertEquals(
            listOf(page),
            budget.reserve(listOf(page), 100, required = false),
            "The optional attempt is admitted",
        )
        assertTrue(budget.defer(page), "A deferred attempt releases its slot")
        assertEquals(
            listOf(page),
            budget.reserve(listOf(page), 101, required = false),
            "A deferred page is not a failed page: it may be admitted again",
        )
    }

    @Test
    fun `a deferred optional attempt does not spend the additional page budget`() {
        val budget = ReaderDualPageFactBudget()
        repeat(ReaderDualPageFactLimits.MAX_ADDITIONAL_PAGES) { index ->
            val id = ReaderPageId(ReaderChapterId(7), index)
            assertTrue(
                budget.reserve(listOf(id), 100, required = false).isNotEmpty(),
                "Every optional attempt is admitted while the budget is free",
            )
            assertTrue(budget.defer(id))
        }
        val fresh = ReaderPageId(ReaderChapterId(7), 9_999)
        assertEquals(
            listOf(fresh),
            budget.reserve(listOf(fresh), 101, required = false),
            "A deferred attempt does not spend the additional page budget",
        )
    }

    @Test
    fun `classified speculative readmission cannot steal a pending real upgrade`() {
        val budget = ReaderDualPageFactBudget()
        assertEquals(
            listOf(page),
            budget.reserveClassified(emptyList(), listOf(page), 100).map { it.pageId },
        )
        budget.complete(page, 0, cacheHit = true)
        assertTrue(
            budget.reserveClassified(emptyList(), listOf(page), 101).isEmpty(),
            "A finished speculative attempt cannot repeatedly re-enter as speculation",
        )
        assertEquals(
            listOf(page),
            budget.reserveClassified(listOf(page), emptyList(), 102).map { it.pageId },
            "Preventing repeated speculation must preserve the real upgrade",
        )
    }
}
