package mihon.desktop.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

internal fun Modifier.observeReaderPageDraw(
    acknowledgeDraw: (() -> Boolean)?,
    prepareDraw: (() -> Boolean)? = null,
): Modifier {
    return if (acknowledgeDraw != null) {
        drawWithContent {
            if (prepareDraw?.invoke() == false) return@drawWithContent
            drawContent()
            acknowledgeDraw()
        }
    } else {
        this
    }
}
