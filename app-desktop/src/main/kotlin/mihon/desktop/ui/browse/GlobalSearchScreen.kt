package mihon.desktop.ui.browse

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.platform.DesktopExternalActionTarget
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.getEnabledCatalogueSourceCandidates
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.domain.error.AppError
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.GlobalSearchSourceFilter
import tachiyomi.domain.source.service.GlobalSearchSourcePolicy
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.i18n.MR
import eu.kanade.tachiyomi.source.online.HttpSource

/** Result group from one source. */
data class SourceSearchResult(
    val source: CatalogueSource,
    val results: List<Manga>,
    val kind: GlobalSearchRowKind,
    val error: SourcePageError? = null,
    val recoveryIntent: DesktopSourceRecoveryIntent = DesktopSourceRecoveryIntent.None,
    val listedByUrl: Map<String, SManga> = emptyMap(),
    val retryMaterialization: Boolean = false,
)

enum class GlobalSearchRowKind { Loading, Empty, Content, Error }

internal sealed interface CanonicalSearchResult {
    val generation: Long
    data class Content(override val generation: Long, val items: List<Manga>, val listedByUrl: Map<String, SManga>) : CanonicalSearchResult
    data class Failure(override val generation: Long, val cause: Throwable) : CanonicalSearchResult
}

internal class SourceResultMaterializer(
    private val scope: CoroutineScope,
    private val persist: suspend (List<SManga>, Long) -> List<Manga>,
) {
    val results = mutableStateMapOf<Long, CanonicalSearchResult>()
    private val requests = mutableMapOf<Long, List<SManga>>()
    private val jobs = mutableMapOf<Long, Job>()
    private val activeAttempts = mutableMapOf<Long, Long>()
    private var activeGeneration = 0L
    private var nextAttempt = 0L

    fun sync(state: DesktopGlobalSearchState) {
        sync(state.generation, state.queryStates)
    }

    fun sync(generation: Long, queryStates: Map<Long, SourceQueryState?>) {
        if (activeGeneration != generation) {
            jobs.values.forEach(Job::cancel)
            jobs.clear()
            requests.clear()
            activeAttempts.clear()
            results.clear()
            activeGeneration = generation
        }
        queryStates.forEach { (sourceId, queryState) ->
            if (queryState is SourceQueryState.Content) {
                val listed = queryState.items.distinctBy(SManga::url)
                if (requests[sourceId] != listed) {
                    requests[sourceId] = listed
                    start(sourceId, listed)
                }
            }
        }
    }

    fun retry(sourceId: Long) {
        results.remove(sourceId)
        requests[sourceId]?.let { start(sourceId, it) }
    }

    fun close() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        activeAttempts.clear()
    }

    private fun start(sourceId: Long, listed: List<SManga>) {
        val expectedGeneration = activeGeneration
        val expectedAttempt = ++nextAttempt
        activeAttempts[sourceId] = expectedAttempt
        jobs[sourceId]?.cancel()
        jobs[sourceId] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val canonical = persist(listed, sourceId)
                if (activeGeneration == expectedGeneration && activeAttempts[sourceId] == expectedAttempt) {
                    results[sourceId] = CanonicalSearchResult.Content(
                        expectedGeneration,
                        canonical,
                        listed.distinctBy(SManga::url).associateBy(SManga::url),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeGeneration == expectedGeneration && activeAttempts[sourceId] == expectedAttempt) {
                    results[sourceId] = CanonicalSearchResult.Failure(expectedGeneration, error)
                }
            }
        }
    }
}

internal data class GlobalSearchUiState(
    val loading: Boolean = false,
    val empty: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val results: List<SourceSearchResult> = emptyList(),
)

internal object GlobalSearchStateProjector {
    fun project(
        sources: List<CatalogueSource>,
        state: DesktopGlobalSearchState,
        canonicalResults: Map<Long, CanonicalSearchResult> = emptyMap(),
        pinnedIds: Set<String> = emptySet(),
        onlyShowHasResults: Boolean = false,
    ): GlobalSearchUiState {
        val allRows = sources.map { source ->
            when (val queryState = state.queryStates[source.id]) {
                null, is SourceQueryState.Loading -> SourceSearchResult(source, emptyList(), GlobalSearchRowKind.Loading)
                is SourceQueryState.Empty -> SourceSearchResult(source, emptyList(), GlobalSearchRowKind.Empty)
                is SourceQueryState.Content -> when (val canonical = canonicalResults[source.id]?.takeIf { it.generation == state.generation }) {
                    null -> SourceSearchResult(source, emptyList(), GlobalSearchRowKind.Loading, queryState.pageError)
                    is CanonicalSearchResult.Content -> SourceSearchResult(
                        source,
                        canonical.items,
                        if (canonical.items.isEmpty()) GlobalSearchRowKind.Empty else GlobalSearchRowKind.Content,
                        queryState.pageError,
                        recoveryIntent(source, queryState.request, queryState.pageError?.recoveryAction),
                        canonical.listedByUrl,
                    )
                    is CanonicalSearchResult.Failure -> SourceSearchResult(
                        source,
                        emptyList(),
                        GlobalSearchRowKind.Error,
                        SourcePageError(AppError.Unknown(canonical.cause), SourceRecoveryAction.Retry),
                        retryMaterialization = true,
                    )
                }
                is SourceQueryState.Failure -> SourceSearchResult(
                    source,
                    emptyList(),
                    GlobalSearchRowKind.Error,
                    SourcePageError(queryState.error, queryState.recoveryAction),
                    recoveryIntent(source, queryState.request, queryState.recoveryAction),
                )
            }
        }.sortedWith(
            compareBy<SourceSearchResult>(
                { it.kind != GlobalSearchRowKind.Content },
                { it.source.id.toString() !in pinnedIds },
                { "${it.source.name.lowercase()} (${it.source.lang})" },
            ),
        )
        val completed = allRows.count { it.kind != GlobalSearchRowKind.Loading }
        val loading = state.isSearching || completed < sources.size
        val visibleRows = if (onlyShowHasResults) {
            allRows.filter { it.kind == GlobalSearchRowKind.Content }
        } else {
            allRows
        }
        return GlobalSearchUiState(
            loading = loading,
            empty = state.generation > 0 && allRows.isNotEmpty() &&
                completed == sources.size && allRows.all { it.kind == GlobalSearchRowKind.Empty },
            completed = completed,
            total = sources.size,
            results = visibleRows,
        )
    }

    private fun recoveryIntent(
        source: CatalogueSource,
        request: tachiyomi.domain.source.service.SourcePageRequest,
        action: SourceRecoveryAction?,
    ): DesktopSourceRecoveryIntent = when (action) {
        SourceRecoveryAction.Retry -> DesktopSourceRecoveryIntent.Retry(request)
        SourceRecoveryAction.OpenLogin -> (source as? HttpSource)?.baseUrl
            ?.let { DesktopSourceRecoveryIntent.OpenLogin(it, request) }
            ?: DesktopSourceRecoveryIntent.None
        SourceRecoveryAction.None, null -> DesktopSourceRecoveryIntent.None
    }
}

internal val LocalGlobalSearchCoordinatorFactory = staticCompositionLocalOf<(SourceMangaSearchService) -> DesktopGlobalSearchCoordinator> {
    { service -> DesktopGlobalSearchCoordinator(service) }
}

internal typealias SourceResultMaterializerFactory = (
    CoroutineScope,
    suspend (List<SManga>, Long) -> List<Manga>,
) -> SourceResultMaterializer

internal val LocalSourceResultMaterializerFactory = staticCompositionLocalOf<SourceResultMaterializerFactory> {
    { scope, persist -> SourceResultMaterializer(scope, persist) }
}

/** Searches all installed sources simultaneously, grouped by source. */
class GlobalSearchScreen(internal val initialQuery: String = "") : Screen {

    companion object {
        internal fun fromExternalActionTarget(target: DesktopExternalActionTarget.GlobalSearch) =
            GlobalSearchScreen(target.query)
    }

    internal suspend fun search(
        sourceManager: SourceManager,
        appPreferences: DesktopAppPreferences,
        coordinator: DesktopGlobalSearchCoordinator,
        query: String,
        onStarted: (Long, List<CatalogueSource>) -> Unit,
    ) = executeSearch(
        sourceManager.getEnabledCatalogueSourceCandidates(appPreferences),
        coordinator,
        query,
        GlobalSearchSourceFilter.All,
        onStarted,
    )

    private suspend fun executeSearch(
        sources: List<CatalogueSource>,
        coordinator: DesktopGlobalSearchCoordinator,
        query: String,
        sourceFilter: GlobalSearchSourceFilter,
        onStarted: (Long, List<CatalogueSource>) -> Unit,
    ) {
        var started = false
        coordinator.search(sources, query, sourceFilter) { state ->
            if (!started) {
                started = true
                onStarted(state.generation, sources)
            }
        }
    }

    internal suspend fun retry(
        coordinator: DesktopGlobalSearchCoordinator,
        source: CatalogueSource,
        intent: DesktopSourceRecoveryIntent,
        sessionFactory: DesktopSourceLoginSessionFactory,
    ) {
        if (intent !is DesktopSourceRecoveryIntent.Retry) return
        val child = coordinator.coordinatorFor(source.id) ?: return
        SourceBrowseRecoveryController(child, DesktopSourceLoginController(sessionFactory, child)).recover(source, intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val sourceManager = LocalDesktopUiDependencies.current.sourceManager
        val appPreferences = LocalDesktopUiDependencies.current.appPreferences
        val sourceMangaSearchService = LocalDesktopUiDependencies.current.sourceMangaSearchService
        val saveSourceMangaForDetails = LocalDesktopUiDependencies.current.saveSourceMangaForDetails
        val getManga = LocalDesktopUiDependencies.current.getManga
        val sourceLoginSessionFactory = LocalDesktopUiDependencies.current.sourceLoginSessionFactory
        val scope = rememberCoroutineScope()

        var query by remember { mutableStateOf(initialQuery) }
        var sourceFilter by remember { mutableStateOf(GlobalSearchSourceFilter.PinnedOnly) }
        var openingMangaUrl by remember { mutableStateOf<String?>(null) }
        val coordinatorFactory = LocalGlobalSearchCoordinatorFactory.current
        val materializerFactory = LocalSourceResultMaterializerFactory.current
        val queryCoordinator = remember(sourceMangaSearchService, coordinatorFactory) { coordinatorFactory(sourceMangaSearchService) }
        val resultMaterializer = remember(saveSourceMangaForDetails, scope, materializerFactory) {
            materializerFactory(scope, saveSourceMangaForDetails::awaitSearchResults)
        }
        val searchState by queryCoordinator.states.collectAsState()
        LaunchedEffect(searchState.generation, searchState.publicationOrdinal) {
            resultMaterializer.sync(searchState)
        }
        val onlyShowHasResults by appPreferences.globalSearchFilterState.changes().collectAsState(
            initial = appPreferences.globalSearchFilterState.get(),
        )
        var sourcesByGeneration by remember { mutableStateOf(emptyMap<Long, List<CatalogueSource>>()) }
        val searchUiState = GlobalSearchStateProjector.project(
            sourcesByGeneration[searchState.generation].orEmpty(),
            searchState,
            resultMaterializer.results,
            pinnedIds = appPreferences.pinnedSources.get(),
            onlyShowHasResults = onlyShowHasResults,
        )
        var activeRecoveryController by remember { mutableStateOf<SourceBrowseRecoveryController?>(null) }
        var sourceLoginUiState by remember { mutableStateOf<DesktopSourceLoginUiState?>(null) }
        val loginUiActions = remember {
            DesktopSourceLoginUiActions(
                { attempt, header -> activeRecoveryController?.submitCookies(attempt, header) ?: false },
                { attempt -> activeRecoveryController?.cancel(attempt) ?: false },
            )
        }
        val loginCopy = remember { desktopSourceLoginCopy { it.localized() } }

        DisposableEffect(queryCoordinator, resultMaterializer) {
            onDispose {
                queryCoordinator.close()
                resultMaterializer.close()
            }
        }

        fun launchSearch(q: String, filter: GlobalSearchSourceFilter = sourceFilter) {
            if (q.isBlank()) return
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val sources = GlobalSearchSourcePolicy.select(
                    sourceManager.getCatalogueSources(),
                    appPreferences.enabledLanguages.get(),
                    appPreferences.disabledSources.get(),
                    appPreferences.pinnedSources.get(),
                    filter,
                )
                executeSearch(sources, queryCoordinator, q, filter) { generation, selectedSources ->
                    sourcesByGeneration = mapOf(generation to selectedSources)
                }
            }
        }

        fun recover(sourceResult: SourceSearchResult) {
            scope.launch {
                val child = queryCoordinator.coordinatorFor(sourceResult.source.id) ?: return@launch
                val controller = SourceBrowseRecoveryController(
                    child,
                    DesktopSourceLoginController(sourceLoginSessionFactory, child),
                )
                var acceptedAttempt: DesktopSourceLoginAttempt? = null
                if (sourceResult.recoveryIntent is DesktopSourceRecoveryIntent.OpenLogin) {
                    activeRecoveryController = controller
                }
                val result = controller.recover(sourceResult.source, sourceResult.recoveryIntent) { attempt ->
                    acceptedAttempt = attempt
                    (sourceResult.recoveryIntent as? DesktopSourceRecoveryIntent.OpenLogin)?.let { loginIntent ->
                        sourceLoginUiState = loginUiActions.open(attempt, loginIntent.url)
                    }
                }
                acceptedAttempt?.let { completedAttempt ->
                    sourceLoginUiState = sourceLoginUiState?.let {
                        loginUiActions.complete(it, completedAttempt, result)
                    }
                }
            }
        }

        SourceLoginDialogHost(
            state = sourceLoginUiState,
            currentState = { sourceLoginUiState },
            copy = loginCopy,
            actions = loginUiActions,
            onStateChange = { sourceLoginUiState = it },
        )

        // Auto-search if an initial query is provided
        LaunchedEffect(initialQuery) {
            if (initialQuery.isNotBlank()) launchSearch(initialQuery)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.desktop_ui_global_search.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // Search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(MR.strings.desktop_ui_search_all_sources.localized()) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onKeyEvent { event ->
                                if (event.key == Key.Enter) {
                                    launchSearch(query)
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                    IconButton(onClick = { launchSearch(query) }) {
                        Icon(Icons.Default.Search, contentDescription = MR.strings.action_search.localized())
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        GlobalSearchSourceFilter.PinnedOnly to MR.strings.pinned_sources.localized(),
                        GlobalSearchSourceFilter.All to MR.strings.all.localized(),
                    ).forEach { (filter, label) ->
                        FilterChip(
                            selected = sourceFilter == filter,
                            onClick = {
                                if (sourceFilter != filter) {
                                    sourceFilter = filter
                                    launchSearch(query, filter)
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                    FilterChip(
                        selected = onlyShowHasResults,
                        onClick = {
                            synchronized(appPreferences.globalSearchFilterState) {
                                appPreferences.globalSearchFilterState.set(!appPreferences.globalSearchFilterState.get())
                            }
                        },
                        label = { Text(MR.strings.has_results.localized()) },
                    )
                }

                if (searchUiState.loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            MR.strings.desktop_ui_searching.localized(),
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (searchUiState.total > 0) {
                    Text(
                        "${searchUiState.completed} / ${searchUiState.total}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (searchUiState.empty) {
                    Text(MR.strings.no_results_found.localized(), modifier = Modifier.padding(16.dp))
                }

                // Fixed-main ordering: non-empty results, pinned sources, then name/language.
                val sortedResults = searchUiState.results
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sortedResults, key = { it.source.id }) { sourceResult ->
                        Text(
                            text = "${sourceResult.source.name} (${sourceResult.results.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { navigator.push(SourceBrowseScreen(sourceResult.source.id, query)) }
                                .padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                        sourceResult.error?.let { error ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(desktopSourceErrorMessage(error.error), color = MaterialTheme.colorScheme.error)
                                desktopSourceRecoveryActionLabel(error.recoveryAction)?.let { label ->
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            if (sourceResult.retryMaterialization) {
                                                resultMaterializer.retry(sourceResult.source.id)
                                            } else {
                                                recover(sourceResult)
                                            }
                                        },
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                        if (sourceResult.kind == GlobalSearchRowKind.Loading) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(MR.strings.loading.localized(), modifier = Modifier.padding(start = 8.dp))
                            }
                        } else if (sourceResult.kind == GlobalSearchRowKind.Empty) {
                            Text(MR.strings.no_results_found.localized(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(sourceResult.results, key = { it.source to it.url }) { initialManga ->
                                val manga by produceState(initialManga, initialManga.source, initialManga.url) {
                                    getManga.subscribe(initialManga.url, initialManga.source)
                                        .filterNotNull()
                                        .collectLatest { value = it }
                                }
                                GlobalSearchMangaCard(
                                    manga = manga,
                                    onClick = {
                                        if (openingMangaUrl != null) return@GlobalSearchMangaCard
                                        val listed = sourceResult.listedByUrl[manga.url] ?: return@GlobalSearchMangaCard
                                        openingMangaUrl = "${sourceResult.source.id}:${manga.url}"
                                        navigator.push(MangaDetailScreen(manga.id))
                                        scope.launch {
                                            try {
                                                val details = saveSourceMangaForDetails.awaitListedForDetails(listed, sourceResult.source.id)
                                                if (details.needsRefresh) {
                                                    saveSourceMangaForDetails.refreshFromSource(sourceResult.source, listed)
                                                }
                                            } finally {
                                                openingMangaUrl = null
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSearchMangaCard(
    manga: Manga,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .size(width = 100.dp, height = 150.dp)
            .clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = manga.thumbnailUrl,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag("global-search-cover:${manga.thumbnailUrl}"),
            )
            Text(
                text = manga.title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(4.dp),
            )
            if (manga.favorite) {
                Text(MR.strings.in_library.localized(), color = Color.White, modifier = Modifier.padding(4.dp))
            }
        }
    }
}
