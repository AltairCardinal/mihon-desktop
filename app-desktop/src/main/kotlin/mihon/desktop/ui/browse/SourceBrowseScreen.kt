package mihon.desktop.ui.browse

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.domain.error.AppError
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceLoginState
import tachiyomi.i18n.MR

data class SourceBrowseUiState(
    val items: List<SManga> = emptyList(),
    val loading: Boolean = false,
    val empty: Boolean = false,
    val request: tachiyomi.domain.source.service.SourcePageRequest? = null,
    val hasNextPage: Boolean = false,
    val pageError: SourcePageError? = null,
)

object SourceBrowseStateProjector {
    fun project(state: SourceQueryState?): SourceBrowseUiState = when (state) {
        null -> SourceBrowseUiState()
        is SourceQueryState.Loading -> SourceBrowseUiState(state.items, true, request = state.request)
        is SourceQueryState.Empty -> SourceBrowseUiState(empty = true, request = state.request)
        is SourceQueryState.Failure -> SourceBrowseUiState(
            request = state.request,
            pageError = SourcePageError(state.error, state.recoveryAction),
        )
        is SourceQueryState.Content -> SourceBrowseUiState(
            items = state.items,
            loading = state.isLoading,
            request = state.request,
            hasNextPage = state.hasNextPage,
            pageError = state.pageError,
        )
    }
}

class SourceBrowseRecoveryController(
    private val coordinator: SourceBrowseQueryCoordinator,
    private val loginController: DesktopSourceLoginController,
) {
    suspend fun recover(
        source: CatalogueSource,
        intent: DesktopSourceRecoveryIntent,
        onLoginStarted: (DesktopSourceLoginAttempt) -> Unit = {},
    ): SourceLoginState? = when (intent) {
        is DesktopSourceRecoveryIntent.Retry -> {
            coordinator.retry(source, intent.request)
            null
        }
        is DesktopSourceRecoveryIntent.OpenLogin -> {
            val attempt = loginController.newAttempt()
            loginController.login(source, intent, attempt, onLoginStarted)
        }
        DesktopSourceRecoveryIntent.None -> null
    }

    fun submitCookies(attempt: DesktopSourceLoginAttempt, header: String): Boolean =
        loginController.submitCookies(attempt, header)

    fun cancel(attempt: DesktopSourceLoginAttempt): Boolean = loginController.cancel(attempt)
}

data class DesktopSourceLoginDialogModel(
    val state: DesktopSourceLoginUiState,
    val copy: DesktopSourceLoginCopy,
) {
    val feedback: String? = state.feedback?.let(copy::feedback)
}

data class DesktopSourceLoginDialogEvents(
    val edit: (String) -> Unit,
    val submit: () -> Unit,
    val dismiss: () -> Unit,
)

internal val LocalSourceLoginDialogInitialState = staticCompositionLocalOf<DesktopSourceLoginUiState?> { null }

internal fun sourceLoginDialogEvents(
    rendered: DesktopSourceLoginUiState,
    currentState: () -> DesktopSourceLoginUiState?,
    actions: DesktopSourceLoginUiActions,
    onStateChange: (DesktopSourceLoginUiState?) -> Unit,
) = DesktopSourceLoginDialogEvents(
    edit = { value -> currentState()?.takeIf { it.attempt === rendered.attempt }?.let { onStateChange(actions.editHeader(it, value)) } },
    submit = { currentState()?.takeIf { it.attempt === rendered.attempt }?.let { onStateChange(actions.submit(it)) } },
    dismiss = { currentState()?.takeIf { it.attempt === rendered.attempt }?.let { onStateChange(if (it.terminal) null else actions.cancel(it)) } },
)

@Composable
internal fun SourceLoginDialogHost(
    state: DesktopSourceLoginUiState?,
    currentState: () -> DesktopSourceLoginUiState?,
    copy: DesktopSourceLoginCopy,
    actions: DesktopSourceLoginUiActions,
    onStateChange: (DesktopSourceLoginUiState?) -> Unit,
    render: @Composable (DesktopSourceLoginDialogModel, DesktopSourceLoginDialogEvents) -> Unit = ::DesktopSourceLoginDialog,
) {
    state ?: return
    render(
        DesktopSourceLoginDialogModel(state, copy),
        sourceLoginDialogEvents(state, currentState, actions, onStateChange),
    )
}

@Composable
fun DesktopSourceLoginDialog(model: DesktopSourceLoginDialogModel, events: DesktopSourceLoginDialogEvents) {
    val state = model.state
    val copy = model.copy
    AlertDialog(
        onDismissRequest = events.dismiss,
        title = { Text(copy.title) },
        text = {
            Column {
                Text(state.host)
                Text(copy.description)
                if (!state.terminal) {
                    OutlinedTextField(
                        value = state.cookieHeader,
                        onValueChange = events.edit,
                        label = { Text(copy.cookieHeaderLabel) },
                        placeholder = { Text(copy.cookieHeaderPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                model.feedback?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = if (state.terminal) events.dismiss else events.submit) {
                Text(if (state.terminal) copy.close else copy.submit)
            }
        },
        dismissButton = {
            if (!state.terminal) TextButton(onClick = events.dismiss) { Text(copy.cancel) }
        },
    )
}

data class SourceBrowseScreen(val sourceId: Long, val initialQuery: String? = null) : Screen {

    internal fun projectState(state: SourceQueryState?): SourceBrowseUiState =
        SourceBrowseStateProjector.project(state)

    internal fun queryStates(coordinator: SourceBrowseQueryCoordinator): Flow<SourceQueryState?> =
        coordinator.states

    internal suspend fun recover(
        controller: SourceBrowseRecoveryController,
        source: CatalogueSource,
        intent: DesktopSourceRecoveryIntent,
        onLoginStarted: (DesktopSourceLoginAttempt) -> Unit = {},
    ) = controller.recover(source, intent, onLoginStarted)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val sourceManager = dependencies.sourceManager
        val sourceMangaSearchService = dependencies.sourceMangaSearchService
        val saveSourceMangaForDetails = dependencies.saveSourceMangaForDetails
        val getManga = dependencies.getManga
        val source = remember { sourceManager.getCatalogueSources().find { it.id == sourceId } }
        val scope = rememberCoroutineScope()

        val queryCoordinator = remember { SourceBrowseQueryCoordinator(sourceMangaSearchService) }
        val queryState by queryStates(queryCoordinator).collectAsState(initial = null)
        val queryUiState = projectState(queryState)
        val resultMaterializer = remember(saveSourceMangaForDetails, scope) {
            SourceResultMaterializer(scope, saveSourceMangaForDetails::awaitSearchResults)
        }
        LaunchedEffect(queryState) {
            resultMaterializer.sync(
                queryState?.request?.generation ?: 0,
                mapOf(sourceId to queryState),
            )
        }
        val canonicalResult = queryState?.let { state ->
            resultMaterializer.results[sourceId]?.takeIf { it.generation == state.request.generation }
        }
        val canonicalContent = canonicalResult as? CanonicalSearchResult.Content
        val canonicalFailure = canonicalResult as? CanonicalSearchResult.Failure
        var openingMangaUrl by remember { mutableStateOf<String?>(null) }
        val loginController = remember(queryCoordinator, dependencies.sourceLoginSessionFactory) {
            DesktopSourceLoginController(dependencies.sourceLoginSessionFactory, queryCoordinator)
        }
        val recoveryController = remember(queryCoordinator, loginController) {
            SourceBrowseRecoveryController(queryCoordinator, loginController)
        }
        val loginUiActions = remember(recoveryController) {
            DesktopSourceLoginUiActions(recoveryController::submitCookies, recoveryController::cancel)
        }
        val loginCopy = remember { desktopSourceLoginCopy { it.localized() } }
        val initialLoginState = LocalSourceLoginDialogInitialState.current
        var sourceLoginUiState by remember { mutableStateOf(initialLoginState) }
        val testModePort = remember(queryCoordinator, scope, loginUiActions) {
            SourceBrowseTestModeObservationPort(
                sourceId,
                queryCoordinator,
                scope,
                currentLogin = { sourceLoginUiState },
                setLogin = { sourceLoginUiState = it },
                loginActions = loginUiActions,
            )
        }
        DisposableEffect(testModePort) {
            SourceBrowseTestModeBridge.install(testModePort)
            onDispose {
                testModePort.close()
                SourceBrowseTestModeBridge.clear(testModePort)
            }
        }
        DisposableEffect(resultMaterializer) {
            onDispose(resultMaterializer::close)
        }

        // Search state
        var searchQuery by remember { mutableStateOf(initialQuery.orEmpty()) }
        var searchActive by remember { mutableStateOf(initialQuery != null) }
        var listingQuery by remember(source, initialQuery) {
            mutableStateOf<SourceQuery>(
                initialQuery?.let { SourceQuery.Search(it, source?.getFilterList() ?: FilterList()) }
                    ?: SourceQuery.Popular,
            )
        }

        val modes = remember(source) { availableBrowseModes(source?.supportsLatest == true) }

        // Filter state
        var showFilterDialog by remember { mutableStateOf(false) }
        val hasFilters = remember(source) { source?.getFilterList()?.isNotEmpty() == true }

        fun loadPage(page: Int, query: SourceQuery = listingQuery) {
            if (source == null || (page > 1 && queryUiState.loading)) return
            scope.launch {
                queryCoordinator.load(source, page, query)
            }
        }

        fun recover() {
            val catalogueSource = source ?: return
            val intent = queryCoordinator.recoveryIntent(catalogueSource)
            scope.launch {
                var acceptedAttempt: DesktopSourceLoginAttempt? = null
                val result = recover(
                    recoveryController,
                    catalogueSource,
                    intent,
                ) { attempt ->
                    acceptedAttempt = attempt
                    (intent as? DesktopSourceRecoveryIntent.OpenLogin)?.let { loginIntent ->
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

        // Initial load
        LaunchedEffect(Unit) { loadPage(1) }

        // Filter dialog
        if (showFilterDialog) {
            FilterDialog(
                filters = (listingQuery as? SourceQuery.Search)?.filters
                    ?: source?.getFilterList()
                    ?: FilterList(),
                resetFilters = { source?.getFilterList() ?: FilterList() },
                onApply = { updatedFilters ->
                    showFilterDialog = false
                    searchQuery = ""
                    SourceQuery.Search("", updatedFilters).also {
                        listingQuery = it
                        loadPage(1, it)
                    }
                },
                onDismiss = { showFilterDialog = false },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(source?.name ?: "Browse") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                    },
                )
            },
        ) { padding ->

            Column(modifier = Modifier.fillMaxSize().padding(padding)) {

                // Search bar (shown when active)
                if (searchActive) {
                    SearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onSearch = { q ->
                                    searchActive = false
                                    val filters = (listingQuery as? SourceQuery.Search)?.filters
                                        ?: source?.getFilterList()
                                        ?: FilterList()
                                    SourceQuery.Search(q, filters).also {
                                        listingQuery = it
                                        loadPage(1, it)
                                    }
                                },
                                expanded = false,
                                onExpandedChange = { searchActive = it },
                                placeholder = { Text("Search ${source?.name ?: ""}…") },
                            )
                        },
                        expanded = false,
                        onExpandedChange = { searchActive = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        content = {},
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    modes.forEach { mode ->
                        val query = when (mode) {
                            BrowseMode.POPULAR -> SourceQuery.Popular
                            BrowseMode.LATEST -> SourceQuery.Latest
                        }
                        FilterChip(
                            selected = listingQuery == query,
                            onClick = {
                                searchQuery = ""
                                listingQuery = query
                                loadPage(1, query)
                            },
                            label = {
                                Text(
                                    if (mode == BrowseMode.POPULAR) {
                                        MR.strings.popular.localized()
                                    } else {
                                        MR.strings.latest.localized()
                                    },
                                )
                            },
                        )
                    }
                    if (hasFilters) {
                        FilterChip(
                            selected = listingQuery is SourceQuery.Search,
                            onClick = { showFilterDialog = true },
                            leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                            label = { Text(MR.strings.action_filter.localized()) },
                        )
                    }
                }

                when {
                    queryUiState.loading && queryUiState.items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    queryUiState.empty -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(MR.strings.no_results_found.localized())
                        }
                    }

                    queryUiState.pageError != null && queryUiState.items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    desktopSourceErrorMessage(queryUiState.pageError.error),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                desktopSourceRecoveryActionLabel(queryUiState.pageError.recoveryAction)?.let { label ->
                                    androidx.compose.material3.Button(
                                        onClick = ::recover,
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }

                    queryState is SourceQueryState.Content && canonicalFailure != null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    desktopSourceErrorMessage(AppError.Unknown(canonicalFailure.cause)),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                androidx.compose.material3.Button(
                                    onClick = { resultMaterializer.retry(sourceId) },
                                    modifier = Modifier.padding(top = 8.dp),
                                ) {
                                    Text(MR.strings.action_retry.localized())
                                }
                            }
                        }
                    }

                    queryState is SourceQueryState.Content && canonicalContent == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(canonicalContent?.items.orEmpty(), key = { it.source to it.url }) { initialManga ->
                                val manga by produceState(initialManga, initialManga.source, initialManga.url) {
                                    getManga.subscribe(initialManga.url, initialManga.source)
                                        .filterNotNull()
                                        .collectLatest { value = it }
                                }
                                MangaCard(manga = manga) {
                                    val catalogueSource = source ?: return@MangaCard
                                    val listed = canonicalContent?.listedByUrl?.get(manga.url) ?: return@MangaCard
                                    if (openingMangaUrl != null) return@MangaCard
                                    openingMangaUrl = manga.url
                                    navigator.push(MangaDetailScreen(manga.id))
                                    scope.launch {
                                        try {
                                            val details = saveSourceMangaForDetails.awaitListedForDetails(listed, sourceId)
                                            if (details.needsRefresh) {
                                                saveSourceMangaForDetails.refreshFromSource(catalogueSource, listed)
                                            }
                                        } finally {
                                            openingMangaUrl = null
                                        }
                                    }
                                }
                            }

                            queryUiState.pageError?.let { error ->
                                item {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            desktopSourceErrorMessage(error.error),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        desktopSourceRecoveryActionLabel(error.recoveryAction)?.let { label ->
                                            androidx.compose.material3.Button(onClick = ::recover) {
                                                Text(label)
                                            }
                                        }
                                    }
                                }
                            }

                            // Load-more trigger
                            if (queryUiState.hasNextPage) {
                                item {
                                    LaunchedEffect(queryUiState.items.size) {
                                        loadPage(
                                            (queryUiState.request?.page ?: 0) + 1,
                                            queryUiState.request?.query ?: listingQuery,
                                        )
                                    }
                                    Box(
                                        Modifier.fillMaxWidth().padding(8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterDialog(
    filters: FilterList,
    resetFilters: () -> FilterList,
    onApply: (FilterList) -> Unit,
    onDismiss: () -> Unit,
) {
    fun freshSourceTree(): FilterList = resetFilters().also {
        require(it !== filters) { "Source must return a fresh FilterList for draft editing" }
    }
    var draft by remember(filters) {
        mutableStateOf(filters.copyStatesToFreshTree(freshSourceTree()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.action_filter.localized()) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                draft.forEach { FilterItem(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draft) }) { Text(MR.strings.action_apply.localized()) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { draft = freshSourceTree() }) {
                    Text(MR.strings.action_reset.localized(), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
            }
        },
    )
}

@Composable
private fun FilterItem(filter: Filter<*>) {
    when (filter) {
        is Filter.Header -> Text(
            filter.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        is Filter.Separator -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        is Filter.CheckBox -> {
            var checked by remember(filter) { mutableStateOf(filter.state) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    checked = !checked
                    filter.state = checked
                },
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(filter.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is Filter.TriState -> {
            var state by remember(filter) { mutableStateOf(filter.state) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    state = when (state) {
                        Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE
                        Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE
                        else -> Filter.TriState.STATE_IGNORE
                    }
                    filter.state = state
                },
            ) {
                TriStateCheckbox(
                    state = when (state) {
                        Filter.TriState.STATE_INCLUDE -> ToggleableState.On
                        Filter.TriState.STATE_EXCLUDE -> ToggleableState.Indeterminate
                        else -> ToggleableState.Off
                    },
                    onClick = null,
                )
                Text(filter.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is Filter.Text -> {
            var text by remember(filter) { mutableStateOf(filter.state) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; filter.state = it },
                label = { Text(filter.name) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                singleLine = true,
            )
        }
        is Filter.Select<*> -> {
            var selectedIndex by remember(filter) { mutableStateOf(filter.state) }
            FilterChoiceGroup(filter.name) {
                filter.values.forEachIndexed { index, value ->
                    FilterChoice(value.toString(), selectedIndex == index) {
                        selectedIndex = index
                        filter.state = index
                    }
                }
            }
        }
        is Filter.Sort -> {
            var selection by remember(filter) { mutableStateOf(filter.state) }
            FilterChoiceGroup(filter.name) {
                filter.values.forEachIndexed { index, value ->
                    val selected = selection?.index == index
                    val direction = selection?.takeIf { selected }
                        ?.let {
                            if (it.ascending) {
                                MR.strings.action_asc.localized()
                            } else {
                                MR.strings.action_desc.localized()
                            }
                        }
                    FilterChoice(listOfNotNull(value, direction?.let { "($it)" }).joinToString(" "), selected) {
                        selection = Filter.Sort.Selection(
                            index,
                            if (selection?.index == index) !selection!!.ascending else selection?.ascending ?: true,
                        )
                        filter.state = selection
                    }
                }
            }
        }
        is Filter.Group<*> -> FilterChoiceGroup(filter.name) {
            filter.state.filterIsInstance<Filter<*>>().forEach { nested ->
                Box(Modifier.padding(start = 12.dp)) { FilterItem(nested) }
            }
        }
    }
}

@Composable
private fun FilterChoiceGroup(name: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MangaCard(manga: Manga, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = manga.thumbnailUrl,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).testTag("source-browse-cover:${manga.thumbnailUrl}"),
            )
            // Gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                            startY = 0.5f,
                        ),
                    ),
            )
            Text(
                text = manga.title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            )
            if (manga.favorite) {
                Text(MR.strings.in_library.localized(), color = Color.White, modifier = Modifier.padding(6.dp))
            }
        }
    }
}
