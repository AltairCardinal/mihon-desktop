package mihon.desktop.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.shiftAwareCombinedClickable(
    onClick: (shiftPressed: Boolean) -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    var shiftPressed by mutableStateOf(false)
    combinedClickable(
        onClick = { onClick(shiftPressed) },
        onLongClick = onLongClick,
    ).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press) {
                    shiftPressed = event.keyboardModifiers.isShiftPressed
                }
            }
        }
    }
}
