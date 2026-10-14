package mihon.desktop.easteregg

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KonamiCodeDetectorTest {

    @Test
    fun `konami code is up up down down left right left right a b a b`() {
        assertEquals(
            listOf(
                KonamiStep.UP,
                KonamiStep.UP,
                KonamiStep.DOWN,
                KonamiStep.DOWN,
                KonamiStep.LEFT,
                KonamiStep.RIGHT,
                KonamiStep.LEFT,
                KonamiStep.RIGHT,
                KonamiStep.A,
                KonamiStep.B,
                KonamiStep.A,
                KonamiStep.B,
            ),
            KonamiCode,
        )
    }

    @Test
    fun `the complete code fires exactly once`() {
        val detector = KonamiCodeDetector()
        val results = KonamiCode.map { detector.accept(it) }

        assertEquals(KonamiCode.size - 1, results.count { !it })
        assertEquals(1, results.count { it })
        assertTrue(results.last())
    }

    @Test
    fun `the code can be entered again after it completed`() {
        val detector = KonamiCodeDetector()
        assertEquals(1, KonamiCode.count { detector.accept(it) })
        assertEquals(0, detector.matchedSteps)

        assertEquals(1, KonamiCode.count { detector.accept(it) })
        assertEquals(0, detector.matchedSteps)
    }

    @Test
    fun `a wrong step drops progress but keeps a fresh leading step`() {
        val detector = KonamiCodeDetector()

        KonamiCode.take(3).forEach { detector.accept(it) }
        assertEquals(3, detector.matchedSteps)

        assertFalse(detector.accept(KonamiStep.RIGHT))
        assertEquals(0, detector.matchedSteps)

        assertFalse(detector.accept(KonamiStep.UP))
        assertEquals(1, detector.matchedSteps)
        assertFalse(detector.accept(KonamiStep.UP))
        assertEquals(2, detector.matchedSteps)
        assertEquals(1, KonamiCode.drop(2).count { detector.accept(it) })
    }

    @Test
    fun `a late leading step restarts instead of stalling`() {
        val detector = KonamiCodeDetector()

        KonamiCode.take(5).forEach { detector.accept(it) }
        assertEquals(5, detector.matchedSteps)

        assertFalse(detector.accept(KonamiStep.UP))
        assertEquals(1, detector.matchedSteps)
        assertEquals(1, KonamiCode.drop(1).count { detector.accept(it) })
    }

    @Test
    fun `an incomplete code never fires`() {
        repeat(64) { attempt ->
            val detector = KonamiCodeDetector()
            val prefix = KonamiCode.dropLast(1 + (attempt % KonamiCode.size))
            assertFalse(prefix.any { detector.accept(it) })
        }
    }

    @Test
    fun `reset abandons partial progress`() {
        val detector = KonamiCodeDetector()
        KonamiCode.take(6).forEach { detector.accept(it) }
        assertEquals(6, detector.matchedSteps)

        detector.reset()
        assertEquals(0, detector.matchedSteps)
        assertEquals(1, KonamiCode.count { detector.accept(it) })
    }

    @Test
    fun `keyboard arrows and a b map to konami steps`() {
        assertEquals(KonamiStep.UP, konamiStepOf(Key.DirectionUp))
        assertEquals(KonamiStep.DOWN, konamiStepOf(Key.DirectionDown))
        assertEquals(KonamiStep.LEFT, konamiStepOf(Key.DirectionLeft))
        assertEquals(KonamiStep.RIGHT, konamiStepOf(Key.DirectionRight))
        assertEquals(KonamiStep.A, konamiStepOf(Key.A))
        assertEquals(KonamiStep.B, konamiStepOf(Key.B))
        assertNull(konamiStepOf(Key.Enter))
        assertNull(konamiStepOf(Key.Spacebar))
        assertNull(konamiStepOf(Key.Escape))
        assertNull(konamiStepOf(Key.C))
    }

    @Test
    fun `only key down events advance the code`() {
        assertEquals(KonamiStep.UP, konamiStepOf(desktopKeyEvent(Key.DirectionUp, KeyEventType.KeyDown)))
        assertNull(konamiStepOf(desktopKeyEvent(Key.DirectionUp, KeyEventType.KeyUp)))
    }

    private fun desktopKeyEvent(key: Key, type: KeyEventType): KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        return KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null))
    }
}
