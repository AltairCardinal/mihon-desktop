package mihon.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import mihon.desktop.reader.DesktopReaderRuntime

@Composable
internal fun ReaderLifecycleEffect(model: ReaderScreenModel) {
    val runtime: DesktopReaderRuntime = checkNotNull(model.runtime)
    DisposableEffect(runtime, model) {
        val runtimeCompositionLease = model.retainProductionRuntimeForComposition()
        ReaderModeState.isInReaderMode = true
        onDispose {
            ReaderModeState.isInReaderMode = false
            runtimeCompositionLease.close()
        }
    }
}
