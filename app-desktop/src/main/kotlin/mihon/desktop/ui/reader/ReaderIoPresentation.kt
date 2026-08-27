package mihon.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import java.util.concurrent.atomic.AtomicBoolean
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.domain.reader.session.ReaderPageId

@Composable
internal fun Modifier.observeReaderPageDraw(
    pageId: ReaderPageId,
    generation: Long,
    decoded: Boolean,
    observer: ReaderPageIoObserver?,
): Modifier {
    if (observer == null) return this
    val firstDrawReported = remember(pageId, generation) { AtomicBoolean(false) }
    return if (decoded) {
        drawWithContent {
            drawContent()
            if (firstDrawReported.compareAndSet(false, true)) {
                observer.pagePresented(pageId, generation)
            }
        }
    } else {
        this
    }
}
