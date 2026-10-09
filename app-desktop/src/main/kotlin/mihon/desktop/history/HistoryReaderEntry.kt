package mihon.desktop.history

import mihon.desktop.reader.desktopReaderScreen
import mihon.desktop.ui.reader.DesktopReaderScreen

/** One production factory for the real button and history_select Test Mode action. */
fun HistoryReaderRequest.toReaderScreen(onClosed: () -> Unit = {}): DesktopReaderScreen = desktopReaderScreen(this, onClosed)
