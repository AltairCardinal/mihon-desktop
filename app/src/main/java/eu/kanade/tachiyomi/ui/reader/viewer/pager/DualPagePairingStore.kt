package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.PageLayout
import java.util.IdentityHashMap

/** Retained by ReaderViewModel across viewport replacement and Activity recreation. */
class DualPagePairingStore(val requiresRestoration: Boolean = false) {
    internal data class ChapterPairing(
        val pages: List<ReaderPage>,
        val state: PairingState,
        var snapshot: ChapterPairingSnapshot = ChapterPairingSnapshot(null, 0),
        var canSave: Boolean = true,
    )
    internal val chapters = IdentityHashMap<ReaderChapter, ChapterPairing>()

    fun install(
        chapter: ReaderChapter,
        pages: List<ReaderPage>,
        snapshot: ChapterPairingSnapshot,
        canSave: Boolean = true,
    ) {
        val forced = snapshot.record?.takeIf { it.isValidFor(pages.size) }?.forcedSinglePages.orEmpty()
        val existing = chapters[chapter]?.takeIf { it.pages === pages }
        if (existing == null) {
            chapters[chapter] = ChapterPairing(
                pages = pages,
                state = PairingState(
                    pageCount = pages.size,
                    isR2L = false,
                    initialLayout = PageLayout.PORTRAIT,
                    forceFirstPageSingle = true,
                    forcedSinglePages = forced,
                ),
                snapshot = snapshot,
                canSave = canSave,
            )
        } else {
            existing.state.applyForcedSinglePages(forced)
            existing.snapshot = snapshot
            existing.canSave = canSave
        }
    }

    fun snapshot(chapter: ReaderChapter, pages: List<ReaderPage>): ChapterPairingSnapshot? =
        chapters[chapter]?.takeIf { it.pages === pages && it.canSave }?.snapshot

    fun isRestored(chapter: ReaderChapter, pages: List<ReaderPage>): Boolean =
        chapters[chapter]?.pages === pages

    fun applySaved(chapter: ReaderChapter, pages: List<ReaderPage>, snapshot: ChapterPairingSnapshot): Boolean {
        val entry = chapters[chapter]?.takeIf { it.pages === pages && it.canSave } ?: return false
        entry.state.applyForcedSinglePages(snapshot.record?.forcedSinglePages.orEmpty())
        entry.snapshot = snapshot
        return true
    }
}
