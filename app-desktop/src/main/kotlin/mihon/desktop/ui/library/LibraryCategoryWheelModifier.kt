package mihon.desktop.ui.library

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import mihon.desktop.platform.OperatingSystem

/** Owned by the root screen, outside category-specific lazy content. */
internal class LibraryCategoryWheelSegment {
    private var lastDirection: Int? = null
    private var lastEventTime: Long? = null

    fun reset() {
        lastDirection = null
        lastEventTime = null
    }

    fun nextDirection(direction: Int, timeMillis: Long): Int? {
        val previousTime = lastEventTime
        val sameBurst = lastDirection == direction && previousTime != null &&
            timeMillis >= previousTime && timeMillis - previousTime < 250
        lastDirection = direction
        lastEventTime = timeMillis
        return direction.takeUnless { sameBurst }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.libraryCategoryWheel(
    segment: LibraryCategoryWheelSegment,
    enabled: Boolean,
    onAdjacentCategory: (Int) -> Unit,
): Modifier = onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
    if (!event.keyboardModifiers.isCtrlPressed) {
        segment.reset()
    } else if (OperatingSystem.detect() == OperatingSystem.WINDOWS) {
        val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
        if (!enabled || event.keyboardModifiers.isAltPressed || event.keyboardModifiers.isShiftPressed ||
            delta.x != 0f
        ) {
            segment.reset()
            return@onPointerEvent
        }
        if (delta.y != 0f) {
            val direction = if (delta.y < 0f) -1 else 1
            segment.nextDirection(direction, event.changes.first().uptimeMillis)?.let(onAdjacentCategory)
            event.changes.forEach { it.consume() }
        }
    }
}
