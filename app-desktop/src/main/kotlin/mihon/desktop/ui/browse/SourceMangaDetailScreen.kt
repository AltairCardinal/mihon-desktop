package mihon.desktop.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.launch
import mihon.desktop.domain.AddMangaToLibrary
import mihon.desktop.domain.ReaderProgressTracker
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Shows manga details and chapter list from a live source (no DB required).
 */
data class SourceMangaDetailScreen(
    val sourceId: Long,
    val mangaUrl: String,
    val mangaTitle: String,
    val thumbnailUrl: String?,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val addToLibrary = remember { Injekt.get<AddMangaToLibrary>() }
        val getDuplicateLibraryManga = remember { Injekt.get<GetDuplicateLibraryManga>() }
        val progressTracker = remember { Injekt.get<ReaderProgressTracker>() }
        val source = remember {
            sourceManager.getCatalogueSources().find { it.id == sourceId }
        }
        val scope = rememberCoroutineScope()

        var manga by remember {
            mutableStateOf(
                SManga.create().apply {
                    url = mangaUrl
                    title = mangaTitle
                    thumbnail_url = thumbnailUrl
                },
            )
        }
        val chapters = remember { mutableStateListOf<SChapter>() }
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        // null = unknown, false = not in library, true = in library
        var isInLibrary by remember { mutableStateOf<Boolean?>(null) }
        var savedMangaId by remember { mutableStateOf(0L) }
        var duplicatesForConfirm by remember { mutableStateOf<List<MangaWithChapterCount>>(emptyList()) }

        LaunchedEffect(mangaUrl) {
            if (source == null) {
                isLoading = false
                return@LaunchedEffect
            }
            val detailsResult = safeSourceCall { source.getMangaDetails(manga) }
            when (detailsResult) {
                is SourceCallResult.Success -> manga = applyMangaDetails(manga, detailsResult.value)
                is SourceCallResult.Timeout -> { errorMessage = "Source timed out"; isLoading = false; return@LaunchedEffect }
                is SourceCallResult.Error -> { errorMessage = detailsResult.message; isLoading = false; return@LaunchedEffect }
            }
            val chaptersResult = safeSourceCall { source.getChapterList(manga) }
            when (chaptersResult) {
                is SourceCallResult.Success -> { chapters.clear(); chapters.addAll(chaptersResult.value) }
                is SourceCallResult.Timeout -> errorMessage = "Source timed out loading chapters"
                is SourceCallResult.Error -> errorMessage = chaptersResult.message
            }
            isLoading = false
        }

        // Duplicate confirmation dialog
        if (duplicatesForConfirm.isNotEmpty()) {
            val dupeTitle = duplicatesForConfirm.first().manga.title
            AlertDialog(
                onDismissRequest = { duplicatesForConfirm = emptyList() },
                title = { Text("Already in library") },
                text = {
                    Text(
                        "\"$dupeTitle\" is already in your library. Add this entry as well?",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        duplicatesForConfirm = emptyList()
                        scope.launch {
                            val saved = addToLibrary.await(manga, sourceId, chapters)
                            savedMangaId = saved.id
                            isInLibrary = true
                        }
                    }) { Text("Add anyway") }
                },
                dismissButton = {
                    TextButton(onClick = { duplicatesForConfirm = emptyList() }) { Text("Cancel") }
                },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(manga.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (!isLoading) {
                            IconButton(
                                onClick = {
                                    if (isInLibrary != true) {
                                        scope.launch {
                                            val tempManga = Manga.create().copy(title = manga.title)
                                            val dupes = getDuplicateLibraryManga(tempManga)
                                            if (shouldShowDuplicateWarning(dupes)) {
                                                duplicatesForConfirm = dupes
                                            } else {
                                                val saved = addToLibrary.await(manga, sourceId, chapters)
                                                savedMangaId = saved.id
                                                isInLibrary = true
                                            }
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (isInLibrary == true) {
                                        Icons.Default.Favorite
                                    } else {
                                        Icons.Default.FavoriteBorder
                                    },
                                    contentDescription = if (isInLibrary == true) "In library" else "Add to library",
                                    tint = if (isInLibrary == true) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            if (errorMessage != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        Button(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    if (source != null) {
                                        val dr = safeSourceCall { source.getMangaDetails(manga) }
                                        when (dr) {
                                            is SourceCallResult.Success -> manga = applyMangaDetails(manga, dr.value)
                                            is SourceCallResult.Timeout -> { errorMessage = "Source timed out"; isLoading = false; return@launch }
                                            is SourceCallResult.Error -> { errorMessage = dr.message; isLoading = false; return@launch }
                                        }
                                        val cr = safeSourceCall { source.getChapterList(manga) }
                                        when (cr) {
                                            is SourceCallResult.Success -> { chapters.clear(); chapters.addAll(cr.value) }
                                            is SourceCallResult.Timeout -> errorMessage = "Source timed out loading chapters"
                                            is SourceCallResult.Error -> errorMessage = cr.message
                                        }
                                    }
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Retry") }
                    }
                }
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item { SourceMangaHeader(manga = manga) }

                item {
                    Text(
                        text = "Chapters (${chapters.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Divider()
                }

                items(chapters, key = { it.url }) { chapter ->
                    ListItem(
                        headlineContent = {
                            Text(chapter.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = chapter.scanlator?.let {
                            { Text(it, style = MaterialTheme.typography.bodySmall) }
                        },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val chapterRefs = chapters.map {
                                            ReaderChapterRef(
                                                id = it.hashCode().toLong(),
                                                url = it.url,
                                                name = it.name,
                                                isRead = false,
                                            )
                                        }
                                        val idx = chapterRefs.indexOfFirst { it.url == chapter.url }
                                            .takeIf { it >= 0 } ?: 0
                                        navigator.push(
                                            DesktopReaderScreen(
                                                chapterTitle = chapter.name,
                                                mangaTitle = manga.title,
                                                pageUrls = emptyList(),
                                                isWebtoon = false,
                                                sourceId = sourceId,
                                                chapterUrl = chapter.url,
                                                chapterId = savedMangaId,
                                                mangaId = savedMangaId,
                                                chapters = chapterRefs,
                                                currentChapterIndex = idx,
                                                initialPage = 0,
                                                progressTracker = progressTracker,
                                            ),
                                        )
                                    }
                                },
                            ) { Text("Read") }
                        },
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/**
 * Merges getMangaDetails() result with the original manga.
 *
 * Extensions commonly return getMangaDetails() without setting url or title —
 * those are already known from the catalogue listing. This function preserves
 * them from [original] so that downstream code (e.g. AddMangaToLibrary) does
 * not crash with "lateinit property url has not been initialized".
 */
internal fun applyMangaDetails(original: SManga, details: SManga): SManga = details.also { d ->
    runCatching { d.url }.onFailure { d.url = original.url }
    runCatching { d.title }.onFailure { d.title = original.title }
}

@Composable
private fun SourceMangaHeader(manga: SManga) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = manga.thumbnail_url,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(120.dp).aspectRatio(0.7f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(manga.title, style = MaterialTheme.typography.titleLarge)
            manga.author?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            manga.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            manga.genre?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
