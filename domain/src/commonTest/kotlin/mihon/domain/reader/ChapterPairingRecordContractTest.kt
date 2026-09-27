package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPairingRecordContractTest {
    @Test
    fun `only a complete current version record for the same page count is applied`() {
        val valid = ChapterPairingRecord(ChapterPairingRecord.FORMAT_VERSION, 8, setOf(1, 4))
        assertTrue(valid.isValidFor(8))
        assertFalse(valid.isValidFor(9))
        assertFalse(valid.copy(formatVersion = 2).isValidFor(8))
        assertFalse(valid.copy(forcedSinglePages = setOf(1, 8)).isValidFor(8))
        assertFalse(valid.copy(forcedSinglePages = emptySet()).isValidFor(8))
        assertFalse(valid.copy(corrupt = true).isValidFor(8))
    }
}
