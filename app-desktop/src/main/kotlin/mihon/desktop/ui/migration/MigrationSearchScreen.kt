package mihon.desktop.ui.migration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import mihon.desktop.domain.DesktopMigrateMangaUseCase
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.desktop.domain.MigrationOptions
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Search for a replacement manga across all sources — step 3 of migration.
 * User picks the target manga, then confirms migration options.
 */
data class MigrationSearchScreen(
    val sourceMangaId: Long,
    val sourceMangaTitle: String,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val getManga = remember { Injekt.get<GetManga>() }
        val migrateManga = remember { Injekt.get<DesktopMigrateMangaUseCase>() }
        val scope = rememberCoroutineScope()

        var query by remember { mutableStateOf(sourceMangaTitle) }
        var searchActive by remember { mutableStateOf(false) }
        val results = remember { mutableStateListOf<Pair<CatalogueSource, SManga>>() }
        var isSearching by remember { mutableStateOf(false) }

        // Migration confirm dialog
        var confirmTarget by remember { mutableStateOf<Pair<CatalogueSource, SManga>?>(null) }

        fun doSearch(q: String) {
            if (q.isBlank()) return
            results.clear()
            isSearching = true
            scope.launch {
                val sources = sourceManager.getCatalogueSources()
                sources.forEach { source ->
                    try {
                        val page = source.getSearchManga(1, q, FilterList())
                        page.mangas.forEach { results.add(source to it) }
                    } catch (_: Exception) {
                        // Skip unreachable sources
                    }
                }
                isSearching = false
            }
        }

        // Auto-search on open with the original title
        LaunchedEffect(Unit) { doSearch(query) }

        // Confirm migration dialog
        confirmTarget?.let { (targetSource, targetSManga) ->
            MigrationConfirmDialog(
                sourceMangaTitle = sourceMangaTitle,
                targetMangaTitle = targetSManga.title,
                onDismiss = { confirmTarget = null },
                onCopy = { options ->
                    confirmTarget = null
                    scope.launch {
                        val sourceManga = getManga.await(sourceMangaId) ?: return@launch
                        val chaptersResult = safeSourceCall { targetSource.getChapterList(targetSManga) }
                        val chapters = when (chaptersResult) {
                            is SourceCallResult.Success -> chaptersResult.value
                            is SourceCallResult.Timeout -> return@launch
                            is SourceCallResult.Error -> return@launch
                        }
                        migrateManga.await(
                            sourceManga = sourceManga,
                            targetSManga = targetSManga,
                            targetSourceId = targetSource.id,
                            targetChapters = chapters,
                            options = options,
                            replace = false,
                        )
                        navigator.pop()
                    }
                },
                onMigrate = { options ->
                    confirmTarget = null
                    scope.launch {
                        val sourceManga = getManga.await(sourceMangaId) ?: return@launch
                        val chaptersResult = safeSourceCall { targetSource.getChapterList(targetSManga) }
                        val chapters = when (chaptersResult) {
                            is SourceCallResult.Success -> chaptersResult.value
                            is SourceCallResult.Timeout -> return@launch
                            is SourceCallResult.Error -> return@launch
                        }
                        migrateManga.await(
                            sourceManga = sourceManga,
                            targetSManga = targetSManga,
                            targetSourceId = targetSource.id,
                            targetChapters = chapters,
                            options = options,
                            replace = true,
                        )
                        // Pop twice: back to source list (past manga list too)
                        navigator.pop(); navigator.pop()
                    }
                },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Find replacement for \"$sourceMangaTitle\"") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { doSearch(it); searchActive = false },
                    active = false,
                    onActiveChange = { searchActive = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search manga title…") },
                ) {}

                if (isSearching) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (results.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No results",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(results, key = { "${it.first.id}_${it.second.url}" }) { (source, manga) ->
                            ListItem(
                                leadingContent = {
                                    AsyncImage(
                                        model = manga.thumbnail_url,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.width(40.dp).size(56.dp),
                                    )
                                },
                                headlineContent = { Text(manga.title, maxLines = 2) },
                                supportingContent = {
                                    Text(
                                        source.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    TextButton(onClick = { confirmTarget = source to manga }) {
                                        Text("Select")
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MigrationConfirmDialog(
    sourceMangaTitle: String,
    targetMangaTitle: String,
    onDismiss: () -> Unit,
    onCopy: (MigrationOptions) -> Unit,
    onMigrate: (MigrationOptions) -> Unit,
) {
    var copyChapters by remember { mutableStateOf(true) }
    var copyCategories by remember { mutableStateOf(true) }
    var copyNotes by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Migrate manga") },
        text = {
            Column {
                Text(
                    "\"$sourceMangaTitle\" → \"$targetMangaTitle\"",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                CheckRow("Copy chapter read status", copyChapters) { copyChapters = it }
                CheckRow("Copy categories", copyCategories) { copyCategories = it }
                CheckRow("Copy notes", copyNotes) { copyNotes = it }
            }
        },
        confirmButton = {
            val options = MigrationOptions(copyChapters, copyCategories, copyNotes)
            Button(onClick = { onMigrate(options) }) { Text("Migrate") }
        },
        dismissButton = {
            val options = MigrationOptions(copyChapters, copyCategories, copyNotes)
            OutlinedButton(onClick = { onCopy(options) }) { Text("Copy") }
        },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Checkbox(checked = checked, onCheckedChange = onToggle)
        },
        modifier = Modifier.padding(vertical = 0.dp),
    )
}
