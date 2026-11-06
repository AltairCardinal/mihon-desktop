package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceFailureIdentity
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.Source
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SOURCE_COMPATIBILITY_LOG_TAG
import tachiyomi.domain.source.service.SourceFilterResult
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceMetadataSnapshot
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourcePageResult
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryReducer
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.domain.source.service.prepareSourceFilters
import tachiyomi.domain.source.service.prepareSourceMetadata
import tachiyomi.domain.source.service.recordSourceFailureDiagnostics
import tachiyomi.domain.source.service.sourceRecoveryAction
import tachiyomi.domain.source.service.toSourceAppError
import tachiyomi.domain.source.service.withSourceFailureIdentity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors

abstract class SearchScreenModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val sourceMangaSearchService: SourceMangaSearchService = Injekt.get(),
    workerScope: CoroutineScope? = null,
    private val coroutineDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher(),
    private val sourceFailureLog: (String) -> Unit = { Log.e(SOURCE_COMPATIBILITY_LOG_TAG, it) },
) : StateScreenModel<SearchScreenModel.State>(initialState) {

    private val workerScope = workerScope ?: ioCoroutineScope
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private val sourceQueryReducer = SourceQueryReducer()

    private val enabledLanguages = sourcePreferences.enabledLanguages().get()
    private val disabledSources = sourcePreferences.disabledSources().get()
    protected val pinnedSources = sourcePreferences.pinnedSources().get()

    private var lastQuery: String? = null
    private var lastSourceFilter: SourceFilter? = null
    private var lastOwners = emptyList<Pair<Source, SourceFailureIdentity>>()

    protected var extensionFilter: String? = null

    protected open fun sortComparator(
        map: Map<Source, SearchItemResult>,
        metadata: List<SourceMetadataSnapshot>,
    ): Comparator<Source> = compareBy<Source>(
        { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
        { metadata.firstOrNull { item -> item.source === it }?.sourceId?.toString() !in pinnedSources },
        {
            metadata.firstOrNull { item -> item.source === it }
                ?.let { item -> "${item.name.orEmpty().lowercase()} (${item.language.orEmpty()})" }.orEmpty()
        },
    )
        .thenBy { source -> metadata.firstOrNull { it.source === source }?.sourceId }
        .thenBy { source -> metadata.indexOfFirst { it.source === source } }

    init {
        screenModelScope.launch {
            preferences.globalSearchFilterState().changes().collectLatest { state ->
                mutableState.update { it.copy(onlyShowHasResults = state) }
            }
        }
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return key(initialManga.source, initialManga.url) {
            produceState(initialValue = initialManga) {
                getManga.subscribe(initialManga.url, initialManga.source)
                    .filterNotNull()
                    .collectLatest { manga ->
                        value = manga
                    }
            }
        }
    }

    protected open fun sourceCandidates(): List<Source> = sourceManager.getQuerySources()

    protected open fun isSourceIdEnabled(sourceId: Long?): Boolean = sourceId?.toString() !in disabledSources

    protected open fun isLanguageEnabled(language: String): Boolean = language in enabledLanguages

    protected open fun isSourceEnabled(metadata: SourceMetadataSnapshot): Boolean {
        val language = metadata.language
        return (language == null || isLanguageEnabled(language)) && isSourceIdEnabled(metadata.sourceId)
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: SourceFilter) {
        mutableState.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState().toggle()
    }

    fun retry() {
        lastQuery = null
        search()
    }

    fun search() {
        val query = state.value.searchQuery
        val sourceFilter = state.value.sourceFilter

        if (query.isNullOrBlank()) return

        val sameQuery = this.lastQuery == query
        val installed = extensionManager.installedExtensionsFlow.value
        val candidates = sourceCandidates()
        val identities = candidates.map { source ->
            val owner = installed.firstOrNull { extension -> extension.sources.any { it === source } }
            source to SourceFailureIdentity(
                sourceId = sourceManager.getRegisteredSourceId(source),
                extensionPackage = owner?.pkgName,
                extensionName = owner?.name,
                versionName = owner?.versionName,
                versionCode = owner?.versionCode,
            )
        }
        val sameOwners = identities.size == lastOwners.size && identities.zip(lastOwners).all { (next, old) ->
            next.first === old.first && next.second == old.second
        }
        if (sameQuery && this.lastSourceFilter == sourceFilter && sameOwners) return

        this.lastQuery = query
        this.lastSourceFilter = sourceFilter
        this.lastOwners = identities

        searchJob?.cancel()
        val generation = ++searchGeneration

        val metadata = identities.filter { isSourceIdEnabled(it.second.sourceId) }
            .map { (source, identity) -> prepareSourceMetadata(source, identity.sourceId, ::isLanguageEnabled) }
            .filter(::isSourceEnabled)
            .filter { item ->
                extensionFilter.isNullOrEmpty() ||
                    identities.first { it.first === item.source }.second.extensionPackage == extensionFilter
            }
        val sources = metadata.map { it.source }

        // Reuse previous results if possible
        if (sameQuery) {
            val previousState = state.value
            val existingResults = previousState.items
            val previousOwners = previousState.sourceIdentities
            updateItems(
                sources
                    .associateWith { source ->
                        val owner = identities.first { it.first === source }.second
                        val unchanged = previousOwners.any { it.first === source && it.second == owner }
                        val prepared = metadata.first { it.source === source }
                        val previous = previousState.metadata.firstOrNull { it.source === source }
                        existingResults[source].takeIf {
                            unchanged && previous != null && previous.error == null && prepared.error == null
                        }
                            ?: SearchItemResult.Loading
                    }
                    .toPersistentMap(),
                metadata,
                identities,
                generation,
            )
        } else {
            updateItems(
                sources
                    .associateWith { SearchItemResult.Loading }
                    .toPersistentMap(),
                metadata,
                identities,
                generation,
            )
        }

        searchJob = workerScope.launch {
            metadata.map { sourceMetadata ->
                async {
                    val source = sourceMetadata.source
                    if (state.value.items[source] !is SearchItemResult.Loading) {
                        return@async
                    }

                    val sourceId = sourceMetadata.sourceId
                    val metadataError = sourceMetadata.error ?: if (sourceId == null) AppError.Unknown() else null
                    if (metadataError != null) {
                        val error = metadataError.withSourceFailureIdentity(
                            identities.first { it.first === source }.second,
                        )
                        val failed = SearchItemResult.Error(
                            SourcePageError(
                                error,
                                if (sourceId == null) SourceRecoveryAction.None else error.sourceRecoveryAction(),
                            ),
                        )
                        if (updateItem(
                                source,
                                failed,
                                generation,
                            )
                        ) {
                            error.recordSourceFailureDiagnostics(sourceFailureLog)
                        }
                        return@async
                    }

                    val prepared = withContext(coroutineDispatcher) { prepareSourceFilters(source) }
                    val request = SourcePageRequest(
                        sourceId = requireNotNull(sourceId),
                        page = 1,
                        generation = generation,
                        query = SourceQuery.Search(
                            query,
                            (prepared as? SourceFilterResult.Content)?.filters
                                ?: eu.kanade.tachiyomi.source.model.FilterList(),
                        ),
                    )
                    val loading = sourceQueryReducer.start(request)
                    val result = withContext(coroutineDispatcher) {
                        when (prepared) {
                            is SourceFilterResult.Content -> sourceMangaSearchService.loadPageResult(source, request)
                            is SourceFilterResult.Failure -> SourcePageResult.Failure(
                                request,
                                prepared.error,
                                prepared.error.sourceRecoveryAction(),
                            )
                        }
                    }
                    val current = if (generation == searchGeneration) {
                        loading
                    } else {
                        sourceQueryReducer.start(request.copy(generation = searchGeneration))
                    }
                    when (val reduced = sourceQueryReducer.reduce(current, result)) {
                        is SourceQueryState.Content -> {
                            val mapped = try {
                                reduced.items.map { it.toDomainManga(sourceId, SourceOperationPhase.SEARCH) }
                            } catch (error: SourceCompatibilityException) {
                                val appError = error.toSourceAppError().withSourceFailureIdentity(
                                    identities.first { it.first === source }.second,
                                )
                                val pageError = SourcePageError(appError, SourceRecoveryAction.None)
                                val failed = SearchItemResult.Error(pageError)
                                if (isActive && updateItem(source, failed, generation)) {
                                    appError.recordSourceFailureDiagnostics(sourceFailureLog)
                                }
                                return@async
                            }
                            val titles = networkToLocalManga(mapped.distinctBy { it.url })
                            if (isActive && reduced.request.generation == generation) {
                                updateItem(source, SearchItemResult.Success(titles), generation)
                            }
                        }
                        is SourceQueryState.Empty -> if (isActive) {
                            updateItem(source, SearchItemResult.Success(emptyList()), generation)
                        }
                        is SourceQueryState.Failure -> if (isActive) {
                            val error = reduced.error.withSourceFailureIdentity(
                                identities.first { it.first === source }.second.copy(sourceId = request.sourceId),
                            )
                            val failed = SearchItemResult.Error(
                                SourcePageError(
                                    error,
                                    reduced.recoveryAction,
                                ),
                            )
                            if (updateItem(source, failed, generation)) {
                                error.recordSourceFailureDiagnostics(sourceFailureLog)
                            }
                        }
                        is SourceQueryState.Loading -> Unit
                    }
                }
            }
                .awaitAll()
        }
    }

    internal fun isCurrentSourceError(source: Source, error: SourcePageError, generation: Long): Boolean {
        val current = state.value
        val currentError = (current.items[source] as? SearchItemResult.Error)?.pageError
        return current.generation == generation && currentError === error
    }

    internal fun reportSourceRecoveryFailure(
        source: Source,
        expectedError: SourcePageError,
        generation: Long,
        error: AppError,
    ): Boolean {
        val current = state.value
        val previous = current.items[source] as? SearchItemResult.Error ?: return false
        if (current.generation != generation || previous.pageError !== expectedError) return false
        val identity = current.sourceIdentities.firstOrNull { it.first === source }?.second ?: return false
        val appError = error.withSourceFailureIdentity(identity)
        val recovery = if (identity.sourceId == null) SourceRecoveryAction.None else appError.sourceRecoveryAction()
        val failed = SearchItemResult.Error(SourcePageError(appError, recovery))
        val accepted = updateItem(source, failed, generation, previous)
        if (accepted) appError.recordSourceFailureDiagnostics(sourceFailureLog)
        return accepted
    }

    private fun updateItems(
        items: PersistentMap<Source, SearchItemResult>,
        metadata: List<SourceMetadataSnapshot>,
        identities: List<Pair<Source, SourceFailureIdentity>>,
        generation: Long,
    ) {
        mutableState.update {
            it.copy(
                generation = generation,
                metadata = metadata,
                sourceIdentities = identities,
                items = items
                    .toSortedMap(sortComparator(items, metadata))
                    .toPersistentMap(),
            )
        }
    }

    private fun updateItem(
        source: Source,
        result: SearchItemResult,
        generation: Long,
        expected: SearchItemResult? = null,
    ): Boolean {
        val accepted = mutableState.updateAndGet { current ->
            if (current.generation != generation || current.metadata.none { it.source === source } ||
                (expected != null && current.items[source] !== expected)
            ) {
                return@updateAndGet current
            }
            val items = current.items.mutate { it[source] = result }
            current.copy(items = items.toSortedMap(sortComparator(items, current.metadata)).toPersistentMap())
        }
        return accepted.generation == generation && accepted.items[source] === result
    }

    fun setMigrateDialog(currentId: Long, target: Manga) {
        screenModelScope.launchIO {
            val current = getManga.await(currentId) ?: return@launchIO
            mutableState.update { it.copy(dialog = Dialog.Migrate(target, current)) }
        }
    }

    fun clearDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    @Immutable
    data class State(
        val from: Manga? = null,
        val searchQuery: String? = null,
        val sourceFilter: SourceFilter = SourceFilter.PinnedOnly,
        val onlyShowHasResults: Boolean = false,
        val items: PersistentMap<Source, SearchItemResult> = persistentMapOf(),
        val dialog: Dialog? = null,
        val generation: Long = 0,
        val metadata: List<SourceMetadataSnapshot> = emptyList(),
        val sourceIdentities: List<Pair<Source, SourceFailureIdentity>> = emptyList(),
    ) {
        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }

    sealed interface Dialog {
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }
}

enum class SourceFilter {
    All,
    PinnedOnly,
}

sealed interface SearchItemResult {
    data object Loading : SearchItemResult

    data class Error(
        val pageError: SourcePageError,
    ) : SearchItemResult

    data class Success(
        val result: List<Manga>,
    ) : SearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
