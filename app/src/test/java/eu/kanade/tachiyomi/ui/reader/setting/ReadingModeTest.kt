package eu.kanade.tachiyomi.ui.reader.setting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadingModeTest {

    @Test
    fun `DUAL_PAGE_R2L can be resolved from preference value 6`() {
        val mode = ReadingMode.fromPreference(6)
        assertEquals(ReadingMode.DUAL_PAGE_R2L, mode)
    }

    @Test
    fun `DUAL_PAGE_R2L flag value is 6`() {
        assertEquals(0x00000006, ReadingMode.DUAL_PAGE_R2L.flagValue)
    }

    @Test
    fun `DUAL_PAGE_R2L is of pager type`() {
        assertTrue(ReadingMode.isPagerType(6))
    }

    @Test
    fun `DUAL_PAGE_R2L has Horizontal direction`() {
        assertEquals(ReadingMode.Direction.Horizontal, ReadingMode.DUAL_PAGE_R2L.direction)
    }

    @Test
    fun `MASK still covers all existing mode values`() {
        ReadingMode.entries.forEach { mode ->
            if (mode != ReadingMode.DEFAULT) {
                assertTrue(
                    (mode.flagValue and ReadingMode.MASK) == mode.flagValue,
                    "Mode ${mode.name} with flagValue ${mode.flagValue} is not covered by MASK",
                )
            }
        }
    }

    @Test
    fun `fromPreference with unknown value returns DEFAULT`() {
        assertEquals(ReadingMode.DEFAULT, ReadingMode.fromPreference(99))
    }

    @Test
    fun `all existing modes still resolve correctly`() {
        assertEquals(ReadingMode.DEFAULT, ReadingMode.fromPreference(0))
        assertEquals(ReadingMode.LEFT_TO_RIGHT, ReadingMode.fromPreference(1))
        assertEquals(ReadingMode.RIGHT_TO_LEFT, ReadingMode.fromPreference(2))
        assertEquals(ReadingMode.VERTICAL, ReadingMode.fromPreference(3))
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromPreference(4))
        assertEquals(ReadingMode.CONTINUOUS_VERTICAL, ReadingMode.fromPreference(5))
        assertEquals(ReadingMode.DUAL_PAGE_R2L, ReadingMode.fromPreference(6))
    }

    @Test
    fun `DUAL_PAGE_R2L has a non-null string resource`() {
        assertNotNull(ReadingMode.DUAL_PAGE_R2L.stringRes)
    }

    @Test
    fun `DUAL_PAGE_R2L has a valid icon resource`() {
        assertTrue(ReadingMode.DUAL_PAGE_R2L.iconRes != 0)
    }
}
