package mihon.desktop.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

internal fun Modifier.observeReaderPageDraw(
    acknowledgeDraw: (() -> Boolean)?,
): Modifier {
    return if (acknowledgeDraw != null) {
        drawWithContent {
            drawContent()
            acknowledgeDraw()
        }
    } else {
        this
    }
}
