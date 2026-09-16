package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Platform-independent contract, ready for the Android viewport adapter. */
class AdaptiveReaderLayoutContractTest {
    private data class Case(val width: Int, val initial: Boolean, val fromSingle: Boolean, val fromDual: Boolean)

    @Test
    fun `initial layout and hysteresis use inclusive switching boundaries`() {
        val cases = listOf(
            Case(900, false, false, false),
            Case(1250, false, false, false),
            Case(1251, false, false, true),
            Case(1349, false, false, true),
            Case(1350, true, true, true),
            Case(1600, true, true, true),
        )
        for ((width, initial, fromSingle, fromDual) in cases) {
            assertEquals(initial, AdaptiveReaderLayout.dualPage(width, 1000))
            assertEquals(fromSingle, AdaptiveReaderLayout.dualPage(width, 1000, false))
            assertEquals(fromDual, AdaptiveReaderLayout.dualPage(width, 1000, true))
        }
        assertEquals(150L, AdaptiveReaderLayout.STABILITY_MILLIS)
    }

    @Test
    fun `unavailable or invalid viewport cannot select a new layout`() {
        assertNull(AdaptiveReaderLayout.dualPage(0, 1000))
        assertNull(AdaptiveReaderLayout.dualPage(1000, 0, true))
        assertNull(AdaptiveReaderLayout.dualPage(-1, 1000, false))
    }
}
