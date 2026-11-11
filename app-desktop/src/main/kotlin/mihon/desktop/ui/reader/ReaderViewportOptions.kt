package mihon.desktop.ui.reader

import androidx.compose.runtime.staticCompositionLocalOf
import mihon.desktop.reader.ReaderDisplayOptions

internal data class ReaderViewportOptions(
    val display: ReaderDisplayOptions = ReaderDisplayOptions(),
    val singlePage: Boolean = false,
    val webtoon: Boolean = false,
    val rtl: Boolean = false,
    val previousChapter: mihon.desktop.reader.ReaderChapterRef? = null,
    val nextChapter: mihon.desktop.reader.ReaderChapterRef? = null,
)
internal val LocalReaderViewportOptions = staticCompositionLocalOf { ReaderViewportOptions() }
