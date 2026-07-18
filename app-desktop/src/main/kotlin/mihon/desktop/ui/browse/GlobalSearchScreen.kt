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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.getEnabledCatalogueSourceCandidates
import mihon.desktop.ui.library.MangaDetailScreen
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceRecoveryAction
import eu.kanade.tachiyomi.source.online.HttpSource

/** Result group from one source. */
data class SourceSearchResult(
    val source: CatalogueSource,
    val results: List<SManga>,
    val error: SourcePageError? = null,
    val recoveryIntent: DesktopSourceRecoveryIntent = DesktopSourceRecoveryIntent.None,
)

internal data class GlobalSearchUiState(val loading: Boolean = false, val empty: Boolean = false, val results: List<SourceSearchResult> = emptyList())

internal object GlobalSearchStateProjector {
    fun project(sources: List<CatalogueSource>, state: DesktopGlobalSearchState): GlobalSearchUiState {
        val sourcesById = sources.associateBy(CatalogueSource::id)
        val results = state.queryStates.mapNotNull { (sourceId, queryState) ->
            val source = sourcesById[sourceId] ?: return@mapNotNull null
            when (queryState) {
                is SourceQueryState.Loading -> SourceSearchResult(source, queryState.items)
                is SourceQueryState.Empty -> null
                is SourceQueryState.Content -> SourceSearchResult(
                    source,
                    queryState.items,
                    queryState.pageError,
                    recoveryIntent(source, queryState.request, queryState.pageError?.recoveryAction),
                )
                is SourceQueryState.Failure -> SourceSearchResult(
                    source,
                    emptyList(),
                    SourcePageError(queryState.error, queryState.recoveryAction),
                    recoveryIntent(source, queryState.request, queryState.recoveryAction),
                )
            }
        }.filter { it.results.isNotEmpty() || it.error != null }
            .sortedByDescending { it.results.size }
        val loading = state.isSearching || state.queryStates.values.any(SourceQueryState::isLoading)
        return GlobalSearchUiState(loading, state.generation > 0 && !loading && results.isEmpty(), results)
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

/** Searches all installed sources simultaneously, grouped by source. */
class GlobalSearchScreen(private val initialQuery: String = "") : Screen {

    internal suspend fun search(
        sourceManager: SourceManager,
        appPreferences: DesktopAppPreferences,
        coordinator: DesktopGlobalSearchCoordinator,
        query: String,
        onStarted: (Long, List<CatalogueSource>) -> Unit,
    ) {
        val sources = sourceManager.getEnabledCatalogueSourceCandidates(appPreferences)
        onStarted(coordinator.state.generation + 1, sources)
        coordinator.search(sources, query)
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
        val sourceLoginSessionFactory = LocalDesktopUiDependencies.current.sourceLoginSessionFactory
        val scope = rememberCoroutineScope()

        var query by remember { mutableStateOf(initialQuery) }
        var openingMangaUrl by remember { mutableStateOf<String?>(null) }
        val coordinatorFactory = LocalGlobalSearchCoordinatorFactory.current
        val queryCoordinator = remember(sourceMangaSearchService, coordinatorFactory) { coordinatorFactory(sourceMangaSearchService) }
        val searchState by queryCoordinator.states.collectAsState()
        var sourcesByGeneration by remember { mutableStateOf(emptyMap<Long, List<CatalogueSource>>()) }
        val searchUiState = GlobalSearchStateProjector.project(sourcesByGeneration[searchState.generation].orEmpty(), searchState)
        var activeRecoveryController by remember { mutableStateOf<SourceBrowseRecoveryController?>(null) }
        var sourceLoginUiState by remember { mutableStateOf<DesktopSourceLoginUiState?>(null) }
        val loginUiActions = remember {
            DesktopSourceLoginUiActions(
                { attempt, header -> activeRecoveryController?.submitCookies(attempt, header) ?: false },
                { attempt -> activeRecoveryController?.cancel(attempt) ?: false },
            )
        }
        val loginCopy = remember { desktopSourceLoginCopy { it.localized() } }

        DisposableEffect(queryCoordinator) {
            onDispose { queryCoordinator.close() }
        }

        fun launchSearch(q: String) {
            if (q.isBlank()) return
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                search(sourceManager, appPreferences, queryCoordinator, q) { generation, sources ->
                    sourcesByGeneration = mapOf(generation to sources)
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
                    title = { Text("Global Search") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        placeholder = { Text("Search all sources...") },
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
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }

                if (searchUiState.loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            "Searching…",
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (searchUiState.empty) {
                    Text("No results found", modifier = Modifier.padding(16.dp))
                }

                // Results grouped by source, sorted by result count descending
                val sortedResults = searchUiState.results
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sortedResults, key = { it.source.id }) { sourceResult ->
                        Text(
                            text = "${sourceResult.source.name} (${sourceResult.results.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                        sourceResult.error?.let { error ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    desktopSourceErrorMessage(error.error),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                desktopSourceRecoveryActionLabel(error.recoveryAction)
                                    ?.let { label ->
                                    androidx.compose.material3.TextButton(onClick = { recover(sourceResult) }) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(sourceResult.results.take(10), key = { it.url }) { manga ->
                                GlobalSearchMangaCard(
                                    manga = manga,
                                    onClick = {
                                        if (openingMangaUrl != null) return@GlobalSearchMangaCard
                                        openingMangaUrl = "${sourceResult.source.id}:${manga.url}"
                                        scope.launch {
                                            val details = saveSourceMangaForDetails.awaitListedForDetails(manga, sourceResult.source.id)
                                            val saved = details.manga
                                            navigator.push(MangaDetailScreen(saved.id))
                                            if (details.needsRefresh) {
                                                saveSourceMangaForDetails.refreshFromSource(sourceResult.source, manga)
                                            }
                                            openingMangaUrl = null
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
    manga: SManga,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .size(width = 100.dp, height = 150.dp)
            .clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = manga.thumbnail_url,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
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
        }
    }
}
