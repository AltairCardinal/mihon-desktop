package mihon.desktop.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
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
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class SourceBrowseScreen(val sourceId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val source = remember { sourceManager.getCatalogueSources().find { it.id == sourceId } }
        val scope = rememberCoroutineScope()

        val mangas = remember { mutableStateListOf<SManga>() }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var currentPage by remember { mutableStateOf(1) }
        var hasNextPage by remember { mutableStateOf(false) }

        // Search state
        var searchQuery by remember { mutableStateOf("") }
        var searchActive by remember { mutableStateOf(false) }
        var isSearchMode by remember { mutableStateOf(false) }

        fun loadPage(page: Int, query: String = "") {
            if (source == null || isLoading) return
            isLoading = true
            errorMessage = null
            scope.launch {
                try {
                    val result: MangasPage = if (query.isBlank()) {
                        source.getPopularManga(page)
                    } else {
                        source.getSearchManga(page, query, source.getFilterList())
                    }
                    if (page == 1) mangas.clear()
                    mangas.addAll(result.mangas)
                    hasNextPage = result.hasNextPage
                    currentPage = page
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Failed to load manga"
                } finally {
                    isLoading = false
                }
            }
        }

        // Initial load
        LaunchedEffect(Unit) { loadPage(1) }

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
                                    navigator.push(
                                        SourceMangaDetailScreen(
                                            sourceId = sourceId,
                                            mangaUrl = manga.url,
                                            mangaTitle = manga.title,
                                            thumbnailUrl = manga.thumbnail_url,
                                        ),
                                    )
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
