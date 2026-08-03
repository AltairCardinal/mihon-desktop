package mihon.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import mihon.desktop.reader.DesktopReaderRuntime

@Composable
internal fun ReaderLifecycleEffect(runtime: DesktopReaderRuntime) {
    DisposableEffect(runtime) {
        ReaderModeState.isInReaderMode = true
        onDispose {
            ReaderModeState.isInReaderMode = false
        }
    }
}
