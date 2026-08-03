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

    @Test
    fun `production auto scroll gate requests next chapter once per stay at bottom`() {
        val gate = WebtoonAutoScrollGate()

        assertEquals(
            WebtoonAutoScrollAction.NextChapter,
            gate.action(enabled = true, lastVisibleIndex = 1, totalItemsCount = 2, lastVisibleBottom = 100, viewportEnd = 100),
        )
        assertEquals(
            WebtoonAutoScrollAction.Idle,
            gate.action(enabled = true, lastVisibleIndex = 1, totalItemsCount = 2, lastVisibleBottom = 100, viewportEnd = 100),
        )
        assertEquals(
            WebtoonAutoScrollAction.Scroll,
            gate.action(enabled = true, lastVisibleIndex = 0, totalItemsCount = 2, lastVisibleBottom = 100, viewportEnd = 100),
        )
        assertEquals(
            WebtoonAutoScrollAction.NextChapter,
            gate.action(enabled = true, lastVisibleIndex = 1, totalItemsCount = 2, lastVisibleBottom = 100, viewportEnd = 100),
        )
    }

    @Test
    fun `auto scroll pauses through user drag and fling then resumes after settlement`() {
        val pauseState = WebtoonAutoScrollPauseState()

        assertTrue(pauseState.loopEnabled(enabled = true, isUserDragging = false, isScrollInProgress = false))
        assertEquals(false, pauseState.loopEnabled(enabled = true, isUserDragging = true, isScrollInProgress = true))
        assertEquals(false, pauseState.loopEnabled(enabled = true, isUserDragging = false, isScrollInProgress = true))
        assertTrue(pauseState.loopEnabled(enabled = true, isUserDragging = false, isScrollInProgress = false))
        assertEquals(false, pauseState.loopEnabled(enabled = false, isUserDragging = false, isScrollInProgress = false))
    }
}
