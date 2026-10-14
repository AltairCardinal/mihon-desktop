package mihon.desktop.easteregg

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** One input of the easter egg code, independent of the keyboard or gamepad that produced it. */
internal enum class KonamiStep {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    A,
    B,
}

/** 上上下下左左右右ABAB */
internal val KonamiCode: List<KonamiStep> = listOf(
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
)

/**
 * Accepts steps one by one and reports the moment the whole code was entered.
 * A wrong step restarts the code, but keeps a step that already begins it so a slightly
 * mistyped run still completes instead of stalling.
 */
internal class KonamiCodeDetector(private val code: List<KonamiStep> = KonamiCode) {
    private var progress = 0

    init {
        require(code.isNotEmpty()) { "An empty code can never be entered" }
    }

    val matchedSteps: Int get() = progress

    fun accept(step: KonamiStep): Boolean {
        if (step != code[progress]) {
            progress = if (step == code.first()) 1 else 0
            return false
        }
        progress++
        if (progress < code.size) return false
        progress = 0
        return true
    }

    fun reset() {
        progress = 0
    }
}

internal fun KonamiStep.key(): Key = when (this) {
    KonamiStep.UP -> Key.DirectionUp
    KonamiStep.DOWN -> Key.DirectionDown
    KonamiStep.LEFT -> Key.DirectionLeft
    KonamiStep.RIGHT -> Key.DirectionRight
    KonamiStep.A -> Key.A
    KonamiStep.B -> Key.B
}

internal fun konamiStepOf(key: Key): KonamiStep? = when (key) {
    Key.DirectionUp -> KonamiStep.UP
    Key.DirectionDown -> KonamiStep.DOWN
    Key.DirectionLeft -> KonamiStep.LEFT
    Key.DirectionRight -> KonamiStep.RIGHT
    Key.A -> KonamiStep.A
    Key.B -> KonamiStep.B
    else -> null
}

/** Held keys repeat on the desktop, so only the press itself may advance the code. */
internal fun konamiStepOf(event: KeyEvent): KonamiStep? =
    if (event.type == KeyEventType.KeyDown) konamiStepOf(event.key) else null
