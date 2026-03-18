package mihon.desktop.ui.library

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DownloadItem
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.ui.reader.DesktopReaderScreen
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class MangaDetailScreen(val mangaId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val getMangaWithChapters = remember { Injekt.get<GetMangaWithChapters>() }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val progressTracker = remember { Injekt.get<ReaderProgressTracker>() }
        val downloadManager = remember { Injekt.get<DesktopDownloadManager>() }
        val updateChecker = remember { Injekt.get<LibraryUpdateChecker>() }
        var manga by remember { mutableStateOf<Manga?>(null) }
        var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
        val scope = rememberCoroutineScope()
        var isUpdating by remember { mutableStateOf(false) }
        var deleteConfirmChapter by remember { mutableStateOf<Chapter?>(null) }

        LaunchedEffect(mangaId) {
            getMangaWithChapters.subscribe(mangaId).collect { (m, ch) ->
                manga = m
                chapters = ch.sortedByDescending { it.sourceOrder }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(manga?.title ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = {
                                val m = manga ?: return@IconButton
                                scope.launch {
                                    isUpdating = true
                                    val source = sourceManager.getCatalogueSources()
                                        .find { it.id == m.source }
                                    if (source != null) updateChecker.checkForUpdates(m, source)
                                    isUpdating = false
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            // Delete downloaded chapter confirmation dialog
            deleteConfirmChapter?.let { ch ->
                AlertDialog(
                    onDismissRequest = { deleteConfirmChapter = null },
                    title = { Text("Delete download?") },
                    text = { Text("Delete local files for \"${ch.name}\"? You can re-download it later.") },
                    confirmButton = {
                        TextButton(onClick = {
                            downloadManager.deleteDownload(
                                sourceId = manga?.source ?: 0L,
                                mangaTitle = manga?.title ?: "",
                                chapterName = ch.name,
                            )
                            deleteConfirmChapter = null
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteConfirmChapter = null }) { Text("Cancel") }
                    },
                )
            }

            if (manga == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item { MangaHeader(manga = manga!!) }
                item {
                    Text(
                        text = "Chapters (${chapters.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                }
                items(chapters, key = { it.id }) { chapter ->
                    val chapterRefs = remember(chapters) {
                        chapters.map { ReaderChapterRef(id = it.id, url = it.url, name = it.name) }
                    }
                    // Observe queue reactively so button updates when status changes
                    val downloadQueue by downloadManager.queue.collectAsState()
                    val queuedItem = downloadQueue.find { it.chapterId == chapter.id }
                    val downloadStatus = when {
                        // Check queue FIRST — active downloads must take priority over disk state
                        // (the disk may already have some pages written while still downloading)
                        queuedItem != null -> when (queuedItem.status) {
                            mihon.desktop.download.DownloadStatus.DOWNLOADING -> ChapterDownloadStatus.DOWNLOADING
                            else -> ChapterDownloadStatus.QUEUED
                        }
                        downloadManager.isDownloaded(manga?.source ?: 0L, manga?.title ?: "", chapter.name) ->
                            ChapterDownloadStatus.DOWNLOADED
                        else -> ChapterDownloadStatus.NOT_DOWNLOADED
                    }
                    ChapterRow(
                        chapter = chapter,
                        downloadStatus = downloadStatus,
                        onDownload = {
                            downloadManager.enqueue(
                                DownloadItem(
                                    sourceId = manga?.source ?: 0L,
                                    mangaTitle = manga?.title ?: "",
                                    chapterName = chapter.name,
                                    chapterId = chapter.id,
                                    chapterUrl = chapter.url,
                                ),
                            )
                        },
                        onDeleteDownload = { deleteConfirmChapter = chapter },
                        onRead = {
                            scope.launch {
                                val idx = ReaderNavigator.indexForId(chapterRefs, chapter.id)
                                navigator.push(
                                    DesktopReaderScreen(
                                        chapterTitle = chapter.name,
                                        mangaTitle = manga?.title ?: "",
                                        pageUrls = emptyList(),
                                        isWebtoon = false,
                                        sourceId = manga?.source ?: 0L,
                                        chapterUrl = chapter.url,
                                        chapterId = chapter.id,
                                        chapters = chapterRefs,
                                        currentChapterIndex = idx,
                                        initialPage = chapter.lastPageRead.toInt().coerceAtLeast(0),
                                        progressTracker = progressTracker,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MangaHeader(manga: Manga) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = manga.thumbnailUrl,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(0.7f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.titleLarge,
            )
            manga.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            manga.description?.let { desc ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class ChapterDownloadStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED }

@Composable
private fun ChapterRow(
    chapter: Chapter,
    downloadStatus: ChapterDownloadStatus,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onRead: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = chapter.name,
                color = if (chapter.read) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (downloadStatus) {
                    ChapterDownloadStatus.DOWNLOADED ->
                        // Tap the downloaded icon to delete
                        IconButton(onClick = onDeleteDownload) {
                            Icon(
                                Icons.Default.DownloadDone,
                                contentDescription = "Delete download",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    // Android: both QUEUE and DOWNLOADING show CircularProgressIndicator
                    // QUEUE → indeterminate spinner (no progress value)
                    // DOWNLOADING → indeterminate spinner (progress tracked in download queue screen)
                    ChapterDownloadStatus.QUEUED ->
                        CircularProgressIndicator(
                            modifier = Modifier.padding(8.dp),
                            strokeWidth = 2.dp,
                        )
                    ChapterDownloadStatus.DOWNLOADING ->
                        CircularProgressIndicator(
                            modifier = Modifier.padding(8.dp),
                            strokeWidth = 2.dp,
                        )
                    ChapterDownloadStatus.NOT_DOWNLOADED ->
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Download")
                        }
                }
                TextButton(onClick = onRead) {
                    if (chapter.read) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Read",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text("Read")
                    }
                }
            }
        },
        supportingContent = chapter.scanlator?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}
