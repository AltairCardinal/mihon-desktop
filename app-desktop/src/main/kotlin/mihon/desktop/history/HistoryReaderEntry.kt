package mihon.desktop.history

import mihon.desktop.ui.reader.DesktopReaderScreen

/** One entry mapper for the real history button and history_select Test Mode action. */
fun HistoryReaderRequest.toReaderScreen(onClosed: () -> Unit = {}): DesktopReaderScreen = DesktopReaderScreen(
    chapterTitle = chapterTitle,
    mangaTitle = mangaTitle,
    sourceId = sourceId,
    chapterUrl = chapterUrl,
    chapterId = chapterId,
    mangaId = mangaId,
    chapterNumber = chapterNumber,
    chapters = chapters,
    currentChapterIndex = currentChapterIndex,
    initialPage = initialPage,
    mangaViewerFlags = mangaViewerFlags,
    resumeSnapshot = resumeSnapshot,
    onProductionClosed = onClosed,
)
