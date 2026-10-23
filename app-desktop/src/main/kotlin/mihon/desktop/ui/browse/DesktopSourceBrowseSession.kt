package mihon.desktop.ui.browse

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.CatalogResultFilter
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState

/**
 * Browse state of a single source that outlives the composition of [SourceBrowseScreen].
 *
 * Android keeps this state inside a retained screen model, so opening a manga and going back
 * restores the previous results instead of querying the source again. The desktop keeps the
 * same state in this session, which is owned by the screen instance and is therefore dropped
 * together with it.
 */
internal class DesktopSourceBrowseSession(
    val sourceId: Long,
    private val initialQuery: String? = null,
) {
    private val lock = Any()
    private var listing: MutableState<SourceQuery>? = null
    private var coordinatorRef: SourceBrowseQueryCoordinator? = null
    private var materializerRef: SourceResultMaterializer? = null

    /**
     * Scope that keeps source requests and result materialisation running while the screen is
     * not composed, so the cached rows are complete when the user comes back.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Text of the search field. */
    val searchQuery: MutableState<String> = mutableStateOf(initialQuery.orEmpty())

    /** Whether the search field is expanded. */
    val searchActive: MutableState<Boolean> = mutableStateOf(initialQuery != null)

    /** Result filter applied to the listing. */
    val resultFilter: MutableState<CatalogResultFilter> = mutableStateOf(CatalogResultFilter())

    /**
     * Listing shown by the screen. The state object is created once so that it keeps the
     * identity of the query that produced the current results, even after the screen was
     * recreated by the navigator.
     */
    fun listingQueryState(filters: FilterList): MutableState<SourceQuery> = synchronized(lock) {
        listing ?: mutableStateOf(
            initialQuery?.let { SourceQuery.Search(it, filters) } ?: SourceQuery.Popular,
        ).also { listing = it }
    }

    /** Coordinator holding the authoritative query state of this session. */
    fun coordinator(service: SourceMangaSearchService): SourceBrowseQueryCoordinator = synchronized(lock) {
        coordinatorRef ?: SourceBrowseQueryCoordinator(service).also { coordinatorRef = it }
    }

    /** Materializer turning the listed results into the canonical local rows. */
    fun materializer(persist: suspend (List<SManga>, Long) -> List<Manga>): SourceResultMaterializer =
        synchronized(lock) {
            materializerRef ?: SourceResultMaterializer(scope, persist).also { materializerRef = it }
        }

    /**
     * True when [state] already holds the outcome of the listing that is currently shown, so
     * the screen can restore it instead of querying the source again.
     */
    fun holdsResultsOf(state: SourceQueryState?): Boolean {
        val current = state ?: return false
        val listing = listing?.value ?: return false
        return current.request.query === listing
    }
}
