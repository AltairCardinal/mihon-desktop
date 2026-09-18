package mihon.desktop.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope

/** Reports the content viewport, independently of transient reader overlays. */
internal fun Modifier.adaptiveReaderViewport(model: ReaderScreenModel, scope: CoroutineScope): Modifier =
    onSizeChanged { model.updateViewportSize(it.width, it.height, scope) }
