package mihon.desktop.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput

internal data class LibraryClickModifiers(
    val shiftPressed: Boolean = false,
    val ctrlPressed: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
internal fun Modifier.shiftAwareCombinedClickable(
    onClick: (LibraryClickModifiers) -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    var modifiers by mutableStateOf(LibraryClickModifiers())
    var primaryAllowed by mutableStateOf(true)
    combinedClickable(
        onClick = { if (primaryAllowed) onClick(modifiers) },
        onLongClick = { if (primaryAllowed) onLongClick() },
    ).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    primaryAllowed = (event.button == PointerButton.Primary || event.buttons.isPrimaryPressed) &&
                        !event.keyboardModifiers.isAltPressed
                    modifiers = LibraryClickModifiers(
                        shiftPressed = event.keyboardModifiers.isShiftPressed,
                        ctrlPressed = event.keyboardModifiers.isCtrlPressed,
                    )
                } else if (event.type == PointerEventType.Release) {
                    awaitPointerEvent(PointerEventPass.Final)
                    modifiers = LibraryClickModifiers()
                    primaryAllowed = true
                }
            }
        }
    }
}
