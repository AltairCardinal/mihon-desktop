package mihon.desktop.ui.browse

import androidx.compose.runtime.mutableStateOf
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mihon.desktop.ui.authors.LanguageArchiveFilter
import tachiyomi.domain.creator.service.CreatorDiscoverySourcePort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.GlobalSearchSourceFilter
import tachiyomi.domain.source.service.SourceMangaSearchService

/**
 * Global search state that outlives the composition of [GlobalSearchScreen].
 *
 * Android keeps this state inside a retained screen model, so opening a manga and going back
 * restores the previous results instead of searching every source again. The desktop keeps the
 * same state in this session, which is owned by the screen instance and is therefore dropped
 * together with it.
 */
internal class DesktopGlobalSearchSession(
    initialQuery: String,
    initialSearchMode: GlobalSearchMode,
) {
    private val lock = Any()
    private var coordinatorRef: DesktopGlobalSearchCoordinator? = null
    private var authorCoordinatorRef: AuthorGlobalSearchCoordinator? = null
    private var materializerRef: SourceResultMaterializer? = null

    /** Scope that keeps the result materialisation running while the screen is not composed. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Text of the search field. */
    val query = mutableStateOf(initialQuery)

    /** Whether the screen searches manga or authors. */
    val searchMode = mutableStateOf(initialSearchMode)

    /** Language filter of the manga results. */
    val mangaLanguageFilter = mutableStateOf(LanguageArchiveFilter.ALL)

    /** Identity filter of the author results. */
    val authorIdentityFilter = mutableStateOf(AuthorIdentityFilter.ALL)

    /** Source filter of the search. */
    val sourceFilter = mutableStateOf(GlobalSearchSourceFilter.PinnedOnly)

    /** Sources that produced the results of the current generation. */
    val sourcesByGeneration = mutableStateOf(emptyMap<Long, List<Source>>())

    /** True once a search was started, so returning to the screen does not search again. */
    var searched: Boolean = false
        private set

    /** Marks the session as searched. */
    fun markSearched() {
        searched = true
    }

    /** Coordinator holding the search state of this session. */
    fun coordinator(
        factory: (SourceMangaSearchService) -> DesktopGlobalSearchCoordinator,
        service: SourceMangaSearchService,
    ): DesktopGlobalSearchCoordinator = synchronized(lock) {
        coordinatorRef ?: factory(service).also { coordinatorRef = it }
    }

    /** Coordinator holding the author search state of this session. */
    fun authorCoordinator(port: CreatorDiscoverySourcePort?): AuthorGlobalSearchCoordinator? = synchronized(lock) {
        if (port == null) return@synchronized null
        authorCoordinatorRef ?: AuthorGlobalSearchCoordinator(port).also { authorCoordinatorRef = it }
    }

    /** Materializer turning the listed results into the canonical local rows. */
    fun materializer(
        factory: SourceResultMaterializerFactory,
        persist: suspend (List<SManga>, Long) -> List<Manga>,
    ): SourceResultMaterializer = synchronized(lock) {
        materializerRef ?: factory(scope, persist).also { materializerRef = it }
    }
}
