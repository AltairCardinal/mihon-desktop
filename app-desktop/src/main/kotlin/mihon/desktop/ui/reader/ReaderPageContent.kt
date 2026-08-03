package mihon.desktop.ui.reader

import mihon.domain.reader.session.ReaderPageSession

internal fun ReaderPageSession.encodedContentUri(): String = requireNotNull(encodedPageRef) {
    "Ready reader page has no encoded content: $id"
}.value
