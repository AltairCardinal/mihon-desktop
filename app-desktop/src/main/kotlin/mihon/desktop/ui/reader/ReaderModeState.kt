package mihon.desktop.ui.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Global state to track whether the app is currently in reader mode.
 * Used by HomeScreen to hide the bottom navigation bar.
 */
object ReaderModeState {
    private val _isInReaderMode = mutableStateOf(false)
    var isInReaderMode: Boolean
        get() = _isInReaderMode.value
        set(value) { _isInReaderMode.value = value }
}
