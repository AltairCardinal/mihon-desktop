package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.util.IdentityHashMap

/** Retained by ReaderViewModel across viewport replacement and Activity recreation. */
class DualPagePairingStore {
    internal data class ChapterPairing(val pages: List<ReaderPage>, val state: PairingState)
    internal val chapters = IdentityHashMap<ReaderChapter, ChapterPairing>()
}
