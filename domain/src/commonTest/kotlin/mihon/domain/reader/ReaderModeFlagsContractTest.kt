package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderModeFlagsContractTest {
    @Test
    fun `wire modes preserve unrelated flags and consume only legacy auto marker`() {
        val other = 0x38L or (1L shl 32) or (1L shl 42)
        for (mode in 0L..7L) {
            val flags = ReaderModeFlags.write(other or (1L shl 34) or 2L, mode)
            assertEquals((if (mode == 0L) other and (1L shl 32).inv() else other) or mode, flags)
            assertEquals(mode, ReaderModeFlags.read(flags))
        }
        assertEquals(7L, ReaderModeFlags.read(other or (1L shl 34) or 2L))
        assertEquals(1L, ReaderModeFlags.read(other or (1L shl 34) or 1L))
        assertEquals(0L, ReaderModeFlags.read(other))
    }
}
