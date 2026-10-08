package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPairingAdjustmentContractTest {
    @Test
    fun `splitting the evidenced first pair persists page one instead of forbidden page zero`() {
        assertEquals(ReaderPairingAdjustment(setOf(1), 1), adjustReaderPairing(1, listOf(0, 1), emptySet()))
        assertEquals(ReaderPairingAdjustment(setOf(1), 1), adjustReaderPairing(0, listOf(0, 1), emptySet()))
    }

    @Test
    fun `shared pairing adjustment alternates normal pairs and leaves unique cover unchanged`() {
        assertEquals(ReaderPairingAdjustment(emptySet(), 0), adjustReaderPairing(0, listOf(0), emptySet()))
        val shifted = adjustReaderPairing(1, listOf(2, 1), emptySet())
        assertEquals(ReaderPairingAdjustment(setOf(1), 2), shifted)
        assertEquals(
            ReaderPairingAdjustment(emptySet(), 1),
            adjustReaderPairing(2, listOf(3, 2), shifted.forcedSinglePages),
        )
        assertEquals(shifted, adjustReaderPairing(1, listOf(1, 2), emptySet()))
    }

    @Test
    fun `updating pairing options retains decoded wide pages and unrelated manual boundaries`() {
        val ids =
            List(8) { mihon.domain.reader.session.ReaderPageId(mihon.domain.reader.session.ReaderChapterId(7), it) }
        val state =
            SharedDualPageSession(
                ReaderDualPageIdentity(
                    1,
                    1,
                    ids.map {
                        ReaderDualPageContentIdentity(it, "fixture")
                    },
                ),
                ReaderDirection.LTR,
                ids.first(),
                1,
            )
        ids.forEach { id ->
            val request = state.requestFact(id)
            state.acceptFact(
                request,
                if (id.sourcePageIndex ==
                    4
                ) {
                    ReaderPageFact.dimensions(200, 100)
                } else {
                    ReaderPageFact.dimensions(100, 200)
                },
            )
        }
        state.acceptPairingRestore(
            requireNotNull(state.beginPairingRestore()),
            ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(1, 6)), 1),
        )
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6), listOf(7)),
            state.units.map { it.pageIds.map { id -> id.sourcePageIndex } },
        )
        state.close()
    }
}
