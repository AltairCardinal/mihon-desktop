package eu.kanade.tachiyomi.ui.reader

internal fun emptyReaderProgressCoordinator() = AndroidReaderProgressCoordinator(
    onCommitted = {},
    onClosed = {},
)
