package mihon.desktop.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebtoonAutoScrollTest {

    @Test
    fun `WebtoonAutoScrollSpeed has 5 entries`() {
        assertEquals(5, WebtoonAutoScrollSpeed.entries.size)
    }

    @Test
    fun `speeds increase from Slowest to Fastest`() {
        val speeds = WebtoonAutoScrollSpeed.entries.map { it.pixelsPerSecond }
        for (i in 0 until speeds.size - 1) {
            assertTrue(speeds[i] < speeds[i + 1], "Speed at index $i should be less than ${i + 1}")
        }
    }

    @Test
    fun `all speeds are positive`() {
        WebtoonAutoScrollSpeed.entries.forEach { speed ->
            assertTrue(speed.pixelsPerSecond > 0f, "${speed.name} must have positive speed")
        }
    }
}
