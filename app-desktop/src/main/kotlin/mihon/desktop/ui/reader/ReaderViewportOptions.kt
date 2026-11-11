package mihon.desktop.ui.reader

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import mihon.desktop.reader.ReaderDisplayOptions
import mihon.desktop.reader.ReaderZoomStart
import mihon.desktop.reader.ScaleType

internal data class ReaderViewportOptions(
    val display: ReaderDisplayOptions = ReaderDisplayOptions(),
    val singlePage: Boolean = false,
    val webtoon: Boolean = false,
    val rtl: Boolean = false,
    val configured: Boolean = false,
    val verticalPaged: Boolean = false,
    val previousChapter: mihon.desktop.reader.ReaderChapterRef? = null,
    val nextChapter: mihon.desktop.reader.ReaderChapterRef? = null,
)
internal val LocalReaderViewportOptions = staticCompositionLocalOf { ReaderViewportOptions() }

internal fun readerZoomStartAlignment(viewport: ReaderViewportOptions, fallback: Alignment): Alignment {
    if (viewport.webtoon || (!viewport.configured && !viewport.singlePage)) return fallback
    return when (viewport.display.zoomStart) {
        ReaderZoomStart.LEFT -> Alignment.CenterStart
        ReaderZoomStart.RIGHT -> Alignment.CenterEnd
        ReaderZoomStart.CENTER -> Alignment.Center
        ReaderZoomStart.AUTO -> when {
            viewport.verticalPaged -> Alignment.Center
            viewport.rtl -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    }
}

internal fun readerImageScaleType(
    selected: ScaleType,
    viewport: ReaderViewportOptions,
    source: IntSize,
    area: IntSize,
): ScaleType = if (viewport.singlePage && viewport.display.landscapeZoom && selected == ScaleType.FIT_SCREEN &&
    source.width > source.height && area.width > 0 && area.height > 0
) {
    ScaleType.FIT_HEIGHT
} else {
    selected
}
