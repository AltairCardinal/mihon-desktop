package eu.kanade.tachiyomi.ui.reader.loader

import androidx.annotation.CallSuper
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import mihon.domain.reader.ReaderDualPageFactReadPermit

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Whether this loader has been already recycled.
     */
    var isRecycled = false
        private set

    /** Actual normal work; optional consumers observe it without submitting loading requests. */
    open val hasForegroundWork: Boolean get() = false
    open val foregroundWork: Flow<Boolean> get() = flowOf(false)

    abstract var isLocal: Boolean

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    /** Optional metadata consumer uses the existing loader without becoming a visible-page consumer. */
    open suspend fun loadFactPage(
        page: ReaderPage,
        permit: ReaderDualPageFactReadPermit,
    ) = withContext(Dispatchers.IO) {
        if (!isRecycled && page.stream != null) page.publishHeaderFact()
    }

    /**
     * Notifies the loader that [page] became the viewer's logical current page.
     * Holder binding must not be used as a substitute for this position event.
     */
    open fun onPageSelected(page: ReaderPage) {}

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    @CallSuper
    open fun retryPage(page: ReaderPage) {
        page.retryHeaderFact()
    }

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
    }
}
