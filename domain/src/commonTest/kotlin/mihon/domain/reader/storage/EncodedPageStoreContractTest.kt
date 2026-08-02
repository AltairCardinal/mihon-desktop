package mihon.domain.reader.storage

import mihon.domain.reader.session.EncodedPageRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EncodedPageStoreContractTest {

    @Test
    fun `session reopen distinguishes retained physical entries from missing references`() {
        val first = entry("first", 4)
        val second = entry("second", 4)
        val missing = ref("missing")
        val index = ByteBudgetEncodedPageStoreIndex(maxBytes = 10)

        val result = index.beginSession(
            availableEntries = listOf(first, second),
            missingRefs = setOf(missing),
        )

        assertEquals(setOf(first.ref, second.ref), result.availableRefs)
        assertEquals(setOf(missing), result.missingRefs)
        assertTrue(result.evictedRefs.isEmpty())
        assertEquals(8, index.diagnostics().usedBytes)
        assertTrue(index.diagnostics().isSessionOpen)
    }

    @Test
    fun `quota commit evicts least recently used entry and reports the exact eviction`() {
        val first = entry("first", 4)
        val second = entry("second", 4)
        val third = entry("third", 6)
        val index = ByteBudgetEncodedPageStoreIndex(maxBytes = 10)
        index.beginSession(listOf(first, second), emptySet())
        assertTrue(index.recordLookup(first.ref, exists = true))

        val result = index.commit(third)

        assertEquals(
            EncodedPageStoreWriteResult.Stored(third, evictedRefs = setOf(second.ref)),
            result,
        )
        assertEquals(setOf(first.ref, third.ref), index.diagnostics().refs)
        assertEquals(10, index.diagnostics().usedBytes)
        assertEquals(1, index.diagnostics().evictionCount)
    }

    @Test
    fun `commit planning reports victims without advancing the logical index`() {
        val first = entry("first", 4)
        val second = entry("second", 4)
        val incoming = entry("incoming", 6)
        val index = ByteBudgetEncodedPageStoreIndex(maxBytes = 10)
        index.beginSession(listOf(first, second), emptySet())

        val plan = index.planCommit(incoming)

        assertEquals(
            EncodedPageStoreWriteResult.Stored(incoming, evictedRefs = setOf(first.ref)),
            plan,
        )
        assertEquals(setOf(first.ref, second.ref), index.diagnostics().refs)
        assertEquals(8, index.diagnostics().usedBytes)
        assertEquals(0, index.diagnostics().writeCount)
        assertEquals(0, index.diagnostics().evictionCount)
    }

    @Test
    fun `oversized write is rejected without changing entries or counters`() {
        val retained = entry("retained", 4)
        val oversized = entry("oversized", 11)
        val index = ByteBudgetEncodedPageStoreIndex(maxBytes = 10)
        index.beginSession(listOf(retained), emptySet())
        val before = index.diagnostics()

        val result = index.commit(oversized)

        assertEquals(EncodedPageStoreWriteResult.RejectedQuota(oversized, maxBytes = 10), result)
        assertEquals(before, index.diagnostics())
    }

    @Test
    fun `missing lookup and explicit eviction remain observable after session close`() {
        val retained = entry("retained", 4)
        val missing = ref("missing")
        val index = ByteBudgetEncodedPageStoreIndex(maxBytes = 10)
        index.beginSession(listOf(retained), emptySet())

        assertFalse(index.recordLookup(missing, exists = false))
        assertEquals(EncodedPageEvictionResult.Evicted(retained), index.evict(retained.ref))
        assertEquals(EncodedPageEvictionResult.Missing, index.evict(retained.ref))
        val closed = index.endSession()

        assertFalse(closed.isSessionOpen)
        assertEquals(1, closed.hitCount)
        assertEquals(1, closed.missCount)
        assertEquals(1, closed.evictionCount)
        assertTrue(closed.refs.isEmpty())
    }

    private fun ref(value: String) = EncodedPageRef(value)

    private fun entry(value: String, bytes: Long) = EncodedPageStoreEntry(ref(value), bytes)
}
