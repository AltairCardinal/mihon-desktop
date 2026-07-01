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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.CatalogueSource
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.ui.library.MangaDetailScreen
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchRequest
import tachiyomi.domain.source.service.SourceMangaSearchService

data class SourceBrowseScreen(val sourceId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val sourceManager = LocalDesktopUiDependencies.current.sourceManager
        val sourceMangaSearchService = LocalDesktopUiDependencies.current.sourceMangaSearchService
        val saveSourceMangaForDetails = LocalDesktopUiDependencies.current.saveSourceMangaForDetails
        val source = remember { sourceManager.getCatalogueSources().find { it.id == sourceId } }
        val scope = rememberCoroutineScope()

        val mangas = remember { mutableStateListOf<SManga>() }
        var isLoading by remember { mutableStateOf(false) }
        var openingMangaUrl by remember { mutableStateOf<String?>(null) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var currentPage by remember { mutableStateOf(1) }
        var hasNextPage by remember { mutableStateOf(false) }

        // Search state
        var searchQuery by remember { mutableStateOf("") }
        var searchActive by remember { mutableStateOf(false) }
        var isSearchMode by remember { mutableStateOf(false) }

        // Browse mode (Popular / Latest tabs)
        var browseMode by remember { mutableStateOf(BrowseMode.POPULAR) }
        val modes = remember(source) { availableBrowseModes(source?.supportsLatest == true) }

        // Filter state
        var showFilterDialog by remember { mutableStateOf(false) }
        var activeFilters by remember { mutableStateOf(source?.getFilterList() ?: FilterList()) }
        val hasFilters = remember(source) { source?.getFilterList()?.isNotEmpty() == true }

        fun loadPage(page: Int, query: String = "", mode: BrowseMode = browseMode) {
            if (source == null || isLoading) return
            isLoading = true
            errorMessage = null
            scope.launch {
                val callResult = safeSourceCall {
                    sourceMangaSearchService.loadPage(
                        source = source,
                        page = page,
                        request = when {
                            query.isNotBlank() -> SourceMangaSearchRequest.Search(query, activeFilters)
                            hasActiveFilters(activeFilters) -> SourceMangaSearchRequest.Search("", activeFilters)
                            mode == BrowseMode.LATEST -> SourceMangaSearchRequest.Latest
                            else -> SourceMangaSearchRequest.Popular
                        },
                    )
                }
                when (callResult) {
                    is SourceCallResult.Success -> {
                        val result = callResult.value
                        if (page == 1) mangas.clear()
                        mangas.addAll(result.mangas)
                        hasNextPage = result.hasNextPage
                        currentPage = page
                    }
                    is SourceCallResult.Timeout -> errorMessage = "Source timed out"
                    is SourceCallResult.Error -> errorMessage = callResult.message
                }
                isLoading = false
            }
        }

        // Initial load
        LaunchedEffect(Unit) { loadPage(1) }

        // Filter dialog
        if (showFilterDialog) {
            FilterDialog(
                filters = activeFilters,
                onApply = { updatedFilters ->
                    activeFilters = updatedFilters
                    showFilterDialog = false
                    isSearchMode = hasActiveFilters(updatedFilters)
                    loadPage(1, query = searchQuery.ifBlank { "" }, mode = browseMode)
                },
                onReset = {
                    activeFilters = source?.getFilterList() ?: FilterList()
                    showFilterDialog = false
                    loadPage(1)
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
                        // Filter button – only when source has filters and not in Latest mode
                        if (hasFilters && !isSearchMode && browseMode != BrowseMode.LATEST) {
                            val filtersActive = hasActiveFilters(activeFilters)
                            IconButton(onClick = { showFilterDialog = true }) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filters",
                                    tint = if (filtersActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                )
                            }
                        }
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
                                    isSearchMode = q.isNotBlank()
                                    loadPage(1, q)
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

                // Popular / Latest tabs (only when source supports latest)
                if (modes.size > 1 && !isSearchMode) {
                    PrimaryTabRow(selectedTabIndex = modes.indexOf(browseMode)) {
                        modes.forEach { mode ->
                            Tab(
                                selected = browseMode == mode,
                                onClick = {
                                    if (browseMode != mode) {
                                        browseMode = mode
                                        loadPage(1, mode = mode)
                                    }
                                },
                                text = {
                                    Text(
                                        when (mode) {
                                            BrowseMode.POPULAR -> "Popular"
                                            BrowseMode.LATEST -> "Latest"
                                        },
                                    )
                                },
                            )
                        }
                    }
                }

                when {
                    isLoading && mangas.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    errorMessage != null && mangas.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    errorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                androidx.compose.material3.Button(
                                    onClick = { loadPage(1, if (isSearchMode) searchQuery else "") },
                                    modifier = Modifier.padding(top = 8.dp),
                                ) { Text("Retry") }
                            }
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
                            items(mangas, key = { it.url }) { manga ->
                                MangaCard(manga = manga) {
                                    val catalogueSource = source ?: return@MangaCard
                                    if (openingMangaUrl != null) return@MangaCard
                                    openingMangaUrl = manga.url
                                    scope.launch {
                                        val saved = saveSourceMangaForDetails.awaitListed(manga, sourceId)
                                        navigator.push(MangaDetailScreen(saved.id))
                                        if (!saved.initialized) {
                                            saveSourceMangaForDetails.refreshFromSource(catalogueSource, manga)
                                        }
                                        openingMangaUrl = null
                                    }
                                }
                            }

                            // Load-more trigger
                            if (hasNextPage) {
                                item {
                                    LaunchedEffect(mangas.size) {
                                        loadPage(
                                            currentPage + 1,
                                            if (isSearchMode) searchQuery else "",
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
    onApply: (FilterList) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Create local mutable copies of filter state
    val localFilters = remember(filters) {
        filters.map { filter ->
            when (filter) {
                is Filter.CheckBox -> object : Filter.CheckBox(filter.name, filter.state) {}
                is Filter.Text -> object : Filter.Text(filter.name) {}.also { it.state = filter.state }
                is Filter.Select<*> -> filter  // use as-is; selects are immutable value types
                else -> filter
            }
        }.let { FilterList(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                localFilters.forEach { filter ->
                    when (filter) {
                        is Filter.CheckBox -> {
                            var checked by remember { mutableStateOf(filter.state) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    checked = !checked
                                    filter.state = checked
                                },
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { v ->
                                    checked = v
                                    filter.state = v
                                })
                                Text(filter.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        is Filter.Text -> {
                            var text by remember { mutableStateOf(filter.state) }
                            OutlinedTextField(
                                value = text,
                                onValueChange = { v -> text = v; filter.state = v },
                                label = { Text(filter.name) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                singleLine = true,
                            )
                        }
                        is Filter.Select<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val select = filter as Filter.Select<Any>
                            var selectedIndex by remember { mutableStateOf(select.state) }
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    select.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                select.values.forEachIndexed { index, value ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedIndex = index
                                            select.state = index
                                        },
                                    ) {
                                        RadioButton(
                                            selected = selectedIndex == index,
                                            onClick = { selectedIndex = index; select.state = index },
                                        )
                                        Text(value.toString(), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        is Filter.Header -> {
                            Text(
                                filter.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        is Filter.Separator -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        else -> { /* Sort and Group not yet supported */ }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(localFilters) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("Reset", color = MaterialTheme.colorScheme.error) }
        },
    )
}

@Composable
private fun MangaCard(manga: SManga, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = manga.thumbnail_url,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
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
        }
    }
}
