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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.ui.library.MangaDetailScreen
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryReducer
import tachiyomi.domain.source.service.SourceQueryState

/** Result group from one source. */
data class SourceSearchResult(
    val source: CatalogueSource,
    val results: List<SManga>,
    val error: String? = null,
)

/** Searches all installed sources simultaneously, grouped by source. */
class GlobalSearchScreen(private val initialQuery: String = "") : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val sourceManager = LocalDesktopUiDependencies.current.sourceManager
        val sourceMangaSearchService = LocalDesktopUiDependencies.current.sourceMangaSearchService
        val saveSourceMangaForDetails = LocalDesktopUiDependencies.current.saveSourceMangaForDetails
        val scope = rememberCoroutineScope()

        var query by remember { mutableStateOf(initialQuery) }
        var isSearching by remember { mutableStateOf(false) }
        var openingMangaUrl by remember { mutableStateOf<String?>(null) }
        val results = remember { mutableStateListOf<SourceSearchResult>() }
        val queryStates = remember { mutableMapOf<Long, SourceQueryState>() }
        val queryReducer = remember { SourceQueryReducer() }
        var generation by remember { mutableStateOf(0L) }

        fun launchSearch(q: String) {
            if (q.isBlank()) return
            val requestGeneration = ++generation
            scope.launch {
                results.clear()
                isSearching = true
                val sources = sourceManager.getCatalogueSources()
                val requests = sources.associateWith { source ->
                    SourcePageRequest(
                        sourceId = source.id,
                        page = 1,
                        generation = requestGeneration,
                        query = SourceQuery.Search(q, source.getFilterList()),
                    )
                }
                queryStates.clear()
                queryStates.putAll(requests.values.associate { request -> request.sourceId to queryReducer.start(request) })
                coroutineScope {
                    sources.map { source ->
                        async {
                            val request = requests.getValue(source)
                            val pageResult = sourceMangaSearchService.loadPageResult(source, request)
                            val current = queryStates[source.id] ?: return@async
                            val reduced = queryReducer.reduce(current, pageResult)
                            queryStates[source.id] = reduced
                            if (reduced.request.generation != requestGeneration) return@async
                            val res = when (reduced) {
                                is SourceQueryState.Content -> SourceSearchResult(source, reduced.items)
                                is SourceQueryState.Empty -> SourceSearchResult(source, emptyList())
                                is SourceQueryState.Failure -> SourceSearchResult(
                                    source,
                                    emptyList(),
                                    reduced.error::class.simpleName,
                                )
                                is SourceQueryState.Loading -> return@async
                            }
                            results.removeAll { it.source.id == source.id }
                            results.add(res)
                        }
                    }.awaitAll()
                }
                if (generation == requestGeneration) isSearching = false
            }
        }

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

                if (isSearching) {
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

                // Results grouped by source, sorted by result count descending
                val sortedResults = results.filter { it.results.isNotEmpty() }
                    .sortedByDescending { it.results.size }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sortedResults, key = { it.source.id }) { sourceResult ->
                        Text(
                            text = "${sourceResult.source.name} (${sourceResult.results.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
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
