package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPairingAdjustmentContractTest {
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
        val options = PagePairingOptions(pairAdjacentPortraitPages = true, forceFirstPageSingle = true)
        val state = ReaderPairingState(8, false, options = options, defaultLayout = PageLayout.PORTRAIT)
        state.updateDimensions(4, 200, 100)
        state.updateOptions(options.copy(forcedSinglePages = setOf(1, 6)))
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6), listOf(7)),
            state.pairings.map(IntArray::toList),
        )
    }
}
