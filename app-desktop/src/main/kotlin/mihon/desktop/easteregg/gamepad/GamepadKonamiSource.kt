package mihon.desktop.easteregg.gamepad

import com.sun.jna.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mihon.desktop.easteregg.KonamiStep

/** One polled controller state; [leftX] and [leftY] are raw XInput stick axes. */
internal data class GamepadSnapshot(val buttons: Int = 0, val leftX: Int = 0, val leftY: Int = 0)

private const val BIT_UP = 1
private const val BIT_DOWN = 1 shl 1
private const val BIT_LEFT = 1 shl 2
private const val BIT_RIGHT = 1 shl 3
private const val BIT_A = 1 shl 4
private const val BIT_B = 1 shl 5

private val STEP_PRIORITY = listOf(
    KonamiStep.A to BIT_A,
    KonamiStep.B to BIT_B,
    KonamiStep.UP to BIT_UP,
    KonamiStep.DOWN to BIT_DOWN,
    KonamiStep.LEFT to BIT_LEFT,
    KonamiStep.RIGHT to BIT_RIGHT,
)

/**
 * Reports one step per press. Polling repeats a held button forever, so only the rising edge of
 * each input counts, and simultaneous presses drain one per frame instead of losing one.
 */
internal class GamepadKonamiEdgeDetector(private val stickDeadzone: Float = DEFAULT_STICK_DEADZONE) {
    private var latched = 0
    private var pending = 0

    fun onSnapshot(snapshot: GamepadSnapshot?): KonamiStep? {
        val active = activeMask(snapshot)
        pending = pending or (active and latched.inv())
        latched = active
        val pressed = STEP_PRIORITY.firstOrNull { (step, bit) -> pending and bit != 0 } ?: return null
        pending = pending and pressed.second.inv()
        return pressed.first
    }

    fun reset() {
        latched = 0
        pending = 0
    }

    private fun activeMask(snapshot: GamepadSnapshot?): Int {
        if (snapshot == null) return 0
        var mask = 0
        if (snapshot.buttons and XInputGamepadButtons.DPAD_UP != 0) mask = mask or BIT_UP
        if (snapshot.buttons and XInputGamepadButtons.DPAD_DOWN != 0) mask = mask or BIT_DOWN
        if (snapshot.buttons and XInputGamepadButtons.DPAD_LEFT != 0) mask = mask or BIT_LEFT
        if (snapshot.buttons and XInputGamepadButtons.DPAD_RIGHT != 0) mask = mask or BIT_RIGHT
        if (snapshot.buttons and XInputGamepadButtons.A != 0) mask = mask or BIT_A
        if (snapshot.buttons and XInputGamepadButtons.B != 0) mask = mask or BIT_B
        val threshold = (stickDeadzone * Short.MAX_VALUE).toInt()
        if (snapshot.leftY <= -threshold) mask = mask or BIT_UP
        if (snapshot.leftY >= threshold) mask = mask or BIT_DOWN
        if (snapshot.leftX <= -threshold) mask = mask or BIT_LEFT
        if (snapshot.leftX >= threshold) mask = mask or BIT_RIGHT
        return mask
    }

    private companion object {
        const val DEFAULT_STICK_DEADZONE = 0.6f
    }
}

/** Feeds Konami steps from a controller. Implementations must tolerate repeated start and stop. */
internal interface GamepadKonamiSource {
    fun start(onStep: (KonamiStep) -> Unit)

    fun stop()
}

/** Used where no controller can be read, so the keyboard path still works on its own. */
internal object UnavailableGamepadKonamiSource : GamepadKonamiSource {
    override fun start(onStep: (KonamiStep) -> Unit) = Unit

    override fun stop() = Unit
}

internal fun gamepadKonamiSource(
    windows: Boolean = Platform.isWindows(),
    native: () -> WindowsGamepadCalls = { WindowsNativeGamepadCalls() },
    pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
): GamepadKonamiSource {
    if (!windows) return UnavailableGamepadKonamiSource
    val calls = try {
        native()
    } catch (_: Throwable) {
        return UnavailableGamepadKonamiSource
    }
    return PollingGamepadKonamiSource(calls, pollIntervalMillis = pollIntervalMillis)
}

/**
 * Polls the controller on a background dispatcher. Compose desktop has no gamepad input source,
 * so the only way to read a pad is to ask the platform while the about page is visible.
 */
internal class PollingGamepadKonamiSource(
    private val calls: WindowsGamepadCalls,
    private val edges: GamepadKonamiEdgeDetector = GamepadKonamiEdgeDetector(),
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val userIndex: Int = 0,
) : GamepadKonamiSource {
    private var job: Job? = null

    override fun start(onStep: (KonamiStep) -> Unit) {
        if (job != null) return
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (isActive) {
                val snapshot = try {
                    calls.snapshot(userIndex)
                } catch (_: Throwable) {
                    null
                }
                snapshot?.let { edges.onSnapshot(it)?.let(onStep) }
                delay(pollIntervalMillis)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}

private const val DEFAULT_POLL_INTERVAL_MILLIS = 50L
