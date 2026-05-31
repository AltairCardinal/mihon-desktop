package mihon.desktop.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import mihon.desktop.domain.DesktopMangaCoverManager
import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DownloadItem
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.ui.reader.DesktopReaderScreen
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.Desktop
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import androidx.compose.foundation.layout.size as layoutSize

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
        val chapterRepository = remember { Injekt.get<ChapterRepository>() }
        val mangaRepository = remember { Injekt.get<MangaRepository>() }
        val getExcludedScanlators = remember { Injekt.get<GetExcludedScanlators>() }
        val setExcludedScanlators = remember { Injekt.get<SetExcludedScanlators>() }
        val getAvailableScanlators = remember { Injekt.get<GetAvailableScanlators>() }
        val scope = rememberCoroutineScope()

        // Observe test navigation for reader screen
        LaunchedEffect(Unit) {
            mihon.desktop.test.navigation.TestNavigationController.pendingReaderScreen.collect { readerScreen ->
                if (readerScreen != null) {
                    navigator.push(readerScreen)
                    mihon.desktop.test.navigation.TestNavigationController.clearPendingNavigation()
                }
            }
        }

        val model = rememberScreenModel { MangaDetailScreenModel(mangaId) }
        val state by model.state.collectAsState()
        val selectionState = remember { ChapterSelectionState() }

        // Read aliases — immutable vals at all read sites, writes go through model
        val manga = state.manga
        val chapters = state.chapters
        val isUpdating = state.isUpdating
        val deleteConfirmChapter = state.deleteConfirmChapter
        val markAllReadConfirm = state.markAllReadConfirm
        val showMigrateSourcePicker = state.showMigrateSourcePicker
        val migrateSearchResults = state.migrateSearchResults
        val migrateTargetSourceId = state.migrateTargetSourceId
        val migrateSearching = state.migrateSearching
        val migrateConfirmItem = state.migrateConfirmItem
        val showNotesDialog = state.showNotesDialog
        val showFilterMenu = state.showFilterMenu
        val filterShowRead = state.filterShowRead
        val filterShowUnread = state.filterShowUnread
        val filterShowBookmarked = state.filterShowBookmarked
        val filterShowDownloaded = state.filterShowDownloaded
        val chapterSortMode = state.chapterSortMode
        val chapterSortAscending = state.chapterSortAscending
        val availableScanlators = state.availableScanlators
        val excludedScanlators = state.excludedScanlators

        LaunchedEffect(mangaId) {
            getMangaWithChapters.subscribe(mangaId, applyScanlatorFilter = true).collect { (m, ch) ->
                model.setManga(m)
                model.setChapters(ch)
            }
        }

        LaunchedEffect(mangaId) {
            getAvailableScanlators.subscribe(mangaId).collect { model.setAvailableScanlators(it) }
        }

        LaunchedEffect(mangaId) {
            getExcludedScanlators.subscribe(mangaId).collect { model.setExcludedScanlators(it) }
        }

        // Apply chapter filter + sort
        val displayedChapters = remember(
            chapters,
            filterShowRead,
            filterShowUnread,
            filterShowBookmarked,
            filterShowDownloaded,
            chapterSortMode,
            chapterSortAscending,
        ) {
            val filtered = chapters.filter { ch ->
                val readOk = (filterShowRead && ch.read) || (filterShowUnread && !ch.read)
                val bookmarkOk = if (filterShowBookmarked) ch.bookmark else true
                val downloadedOk = if (filterShowDownloaded) {
                    val m = manga
                    m != null && downloadManager.isDownloaded(m.source, m.title, ch.name)
                } else {
                    true
                }
                readOk && bookmarkOk && downloadedOk
            }
            val comparator: Comparator<Chapter> = when (chapterSortMode) {
                ChapterSortMode.BY_SOURCE_ORDER -> compareBy { it.sourceOrder }
                ChapterSortMode.BY_CHAPTER_NUMBER -> compareBy { it.chapterNumber }
                ChapterSortMode.BY_DATE_UPLOAD -> compareBy { it.dateUpload }
            }
            if (chapterSortAscending) {
                filtered.sortedWith(comparator)
            } else {
                filtered.sortedWith(comparator.reversed())
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
                        // Open in browser + Copy link
                        manga?.let { m ->
                            val source = sourceManager.getCatalogueSources().find { it.id == m.source }
                            if (source is eu.kanade.tachiyomi.source.online.HttpSource) {
                                val mangaUrl = source.getMangaUrl(
                                    eu.kanade.tachiyomi.source.model.SManga.create().apply { url = m.url },
                                )
                                IconButton(onClick = {
                                    try {
                                        Desktop.getDesktop().browse(URI(mangaUrl))
                                    } catch (_: Exception) { }
                                }) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser")
                                }
                                IconButton(onClick = {
                                    try {
                                        val sel = java.awt.datatransfer.StringSelection(mangaUrl)
                                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                    } catch (_: Exception) { }
                                }) {
                                    Icon(Icons.Default.Link, contentDescription = "Copy link")
                                }
                            }
                        }

                        // Mark all as read
                        TextButton(onClick = { model.setMarkAllReadConfirm(true) }) { Text("Mark all read") }

                        // Select all chapters / close selection mode
                        if (selectionState.isActive) {
                            IconButton(onClick = { selectionState.selectAll(displayedChapters.map { it.id }) }) {
                                Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                            }
                            IconButton(onClick = { selectionState.clear() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear selection")
                            }
                        }

                        // Chapter filter/sort
                        Box {
                            IconButton(onClick = { model.toggleFilterMenu() }) {
                                Icon(Icons.Default.FilterList, contentDescription = "Filter chapters")
                            }
                            DropdownMenu(expanded = showFilterMenu, onDismissRequest = { model.toggleFilterMenu() }) {
                                DropdownMenuItem(
                                    text = { Text("── Filter ──") },
                                    onClick = {},
                                    enabled = false,
                                )
                                DropdownMenuItem(
                                    text = { Text(if (filterShowRead) "✓ Show Read" else "  Show Read") },
                                    onClick = { model.setFilterShowRead(!filterShowRead) },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (filterShowUnread) "✓ Show Unread" else "  Show Unread") },
                                    onClick = { model.setFilterShowUnread(!filterShowUnread) },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (filterShowBookmarked) "✓ Bookmarked only" else "  Bookmarked only")
                                    },
                                    onClick = { model.setFilterShowBookmarked(!filterShowBookmarked) },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (filterShowDownloaded) "✓ Downloaded only" else "  Downloaded only")
                                    },
                                    onClick = { model.setFilterShowDownloaded(!filterShowDownloaded) },
                                )
                                if (availableScanlators.isNotEmpty()) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("── Scanlators ──") },
                                        onClick = {},
                                        enabled = false,
                                    )
                                    availableScanlators.sorted().forEach { scanlator ->
                                        val isExcluded = scanlator in excludedScanlators
                                        DropdownMenuItem(
                                            text = { Text(if (!isExcluded) "✓ $scanlator" else "  $scanlator") },
                                            onClick = {
                                                scope.launch {
                                                    val newExcluded = if (isExcluded) {
                                                        excludedScanlators - scanlator
                                                    } else {
                                                        excludedScanlators + scanlator
                                                    }
                                                    setExcludedScanlators.await(mangaId, newExcluded)
                                                }
                                            },
                                        )
                                    }
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("── Sort ──") },
                                    onClick = {},
                                    enabled = false,
                                )
                                ChapterSortMode.entries.forEach { mode ->
                                    val label = when (mode) {
                                        ChapterSortMode.BY_SOURCE_ORDER -> "Source order"
                                        ChapterSortMode.BY_CHAPTER_NUMBER -> "Chapter number"
                                        ChapterSortMode.BY_DATE_UPLOAD -> "Upload date"
                                    }
                                    val arrow = if (mode ==
                                        chapterSortMode
                                    ) {
                                        (if (chapterSortAscending) " ↑" else " ↓")
                                    } else {
                                        ""
                                    }
                                    DropdownMenuItem(
                                        text = { Text("$label$arrow") },
                                        onClick = {
                                            model.toggleSort(mode)
                                            model.toggleFilterMenu()
                                        },
                                    )
                                }
                            }
                        }

                        if (isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = {
                                val m = manga ?: return@IconButton
                                scope.launch {
                                    model.setIsUpdating(true)
                                    val source = sourceManager.getCatalogueSources()
                                        .find { it.id == m.source }
                                    if (source != null) updateChecker.checkForUpdates(m, source)
                                    model.setIsUpdating(false)
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                            }
                        }

                        // Migrate to another source
                        IconButton(onClick = { model.setShowMigrateSourcePicker(true) }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Migrate source")
                        }

                        // Notes
                        IconButton(onClick = { model.setShowNotesDialog(true) }) {
                            Icon(Icons.Default.Note, contentDescription = "Notes")
                        }
                    },
                )
            },
            bottomBar = {
                if (selectionState.isActive) {
                    ChapterSelectionBar(
                        selectedCount = selectionState.selectedIds.size,
                        onDownload = {
                            val m = manga ?: return@ChapterSelectionBar
                            chapters.filter { it.id in selectionState.selectedIds }.forEach { ch ->
                                downloadManager.enqueue(
                                    DownloadItem(
                                        sourceId = m.source,
                                        mangaTitle = m.title,
                                        chapterName = ch.name,
                                        chapterId = ch.id,
                                        chapterUrl = ch.url,
                                    ),
                                )
                            }
                            selectionState.clear()
                        },
                        onMarkRead = {
                            scope.launch {
                                chapters.filter { it.id in selectionState.selectedIds }.forEach { ch ->
                                    chapterRepository.update(ChapterUpdate(id = ch.id, read = true, lastPageRead = 0L))
                                }
                                selectionState.clear()
                            }
                        },
                        onMarkUnread = {
                            scope.launch {
                                chapters.filter { it.id in selectionState.selectedIds }.forEach { ch ->
                                    chapterRepository.update(ChapterUpdate(id = ch.id, read = false, lastPageRead = 0L))
                                }
                                selectionState.clear()
                            }
                        },
                        onDeleteDownload = {
                            val m = manga ?: return@ChapterSelectionBar
                            chapters.filter { it.id in selectionState.selectedIds }.forEach { ch ->
                                downloadManager.deleteDownload(m.source, m.title, ch.name)
                            }
                            selectionState.clear()
                        },
                        onClose = { selectionState.clear() },
                    )
                }
            },
        ) { padding ->
            // Delete confirmation dialog
            deleteConfirmChapter?.let { ch ->
                AlertDialog(
                    onDismissRequest = { model.setDeleteConfirmChapter(null) },
                    title = { Text("Delete download?") },
                    text = { Text("Delete local files for \"${ch.name}\"? You can re-download it later.") },
                    confirmButton = {
                        TextButton(onClick = {
                            downloadManager.deleteDownload(
                                sourceId = manga?.source ?: 0L,
                                mangaTitle = manga?.title ?: "",
                                chapterName = ch.name,
                            )
                            model.setDeleteConfirmChapter(null)
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { model.setDeleteConfirmChapter(null) }) { Text("Cancel") }
                    },
                )
            }

            // Mark all read confirmation
            if (markAllReadConfirm) {
                AlertDialog(
                    onDismissRequest = { model.setMarkAllReadConfirm(false) },
                    title = { Text("Mark all as read?") },
                    text = { Text("Mark all ${chapters.size} chapters as read?") },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                val updates = chapters.map { ch -> ChapterUpdate(id = ch.id, read = true) }
                                chapterRepository.updateAll(updates)
                            }
                            model.setMarkAllReadConfirm(false)
                        }) { Text("Mark all read") }
                    },
                    dismissButton = {
                        TextButton(onClick = { model.setMarkAllReadConfirm(false) }) { Text("Cancel") }
                    },
                )
            }

            // ── Migration: source picker ──────────────────────────────────
            if (showMigrateSourcePicker) {
                val availableSources = remember {
                    sourceManager.getCatalogueSources().filter { it.id != manga?.source }
                }
                AlertDialog(
                    onDismissRequest = { model.setShowMigrateSourcePicker(false) },
                    title = { Text("Migrate to source") },
                    text = {
                        if (availableSources.isEmpty()) {
                            Text("No other sources installed.")
                        } else {
                            LazyColumn {
                                items(availableSources) { src ->
                                    DropdownMenuItem(
                                        text = { Text("${src.name} (${src.lang})") },
                                        onClick = {
                                            model.setShowMigrateSourcePicker(false)
                                            model.setMigrateTargetSourceId(src.id)
                                            model.setMigrateSearchResults(null)
                                            scope.launch {
                                                model.setMigrateSearching(true)
                                                runCatching {
                                                    val query = manga?.title ?: return@runCatching
                                                    val results = src.getSearchManga(1, query, FilterList())
                                                    model.setMigrateSearchResults(results.mangas)
                                                }
                                                model.setMigrateSearching(false)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { model.setShowMigrateSourcePicker(false) }) { Text("Cancel") }
                    },
                )
            }

            // ── Migration: search results ─────────────────────────────────
            val searchResults = migrateSearchResults
            if (searchResults != null) {
                AlertDialog(
                    onDismissRequest = {
                        model.setMigrateSearchResults(null)
                        model.setMigrateTargetSourceId(null)
                    },
                    title = {
                        if (migrateSearching) {
                            Text("Searching\u2026")
                        } else {
                            Text("Select match (${searchResults.size} results)")
                        }
                    },
                    text = {
                        if (migrateSearching) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (searchResults.isEmpty()) {
                            Text("No results found. Try migrating manually.")
                        } else {
                            LazyColumn {
                                items(searchResults) { result: SManga ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(result.title, style = MaterialTheme.typography.bodyMedium)
                                                if (!result.author.isNullOrBlank()) {
                                                    Text(
                                                        result.author ?: "",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = { model.setMigrateConfirmItem(result) },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            model.setMigrateSearchResults(null)
                            model.setMigrateTargetSourceId(null)
                        }) { Text("Cancel") }
                    },
                )
            }

            // ── Migration: confirm ────────────────────────────────────────
            val confirmItem = migrateConfirmItem
            if (confirmItem != null) {
                AlertDialog(
                    onDismissRequest = { model.setMigrateConfirmItem(null) },
                    title = { Text("Confirm migration") },
                    text = { Text("Migrate to \"${confirmItem.title}\"? This will update the manga URL and source.") },
                    confirmButton = {
                        TextButton(onClick = {
                            val targetSourceId = migrateTargetSourceId ?: return@TextButton
                            scope.launch {
                                mangaRepository.update(
                                    MangaUpdate(
                                        id = mangaId,
                                        source = targetSourceId,
                                        url = confirmItem.url,
                                        title = confirmItem.title.takeIf { it.isNotBlank() } ?: manga?.title,
                                        thumbnailUrl = confirmItem.thumbnail_url,
                                    ),
                                )
                            }
                            model.setMigrateConfirmItem(null)
                            model.setMigrateSearchResults(null)
                            model.setMigrateTargetSourceId(null)
                        }) { Text("Migrate") }
                    },
                    dismissButton = {
                        TextButton(onClick = { model.setMigrateConfirmItem(null) }) { Text("Cancel") }
                    },
                )
            }

            // Notes dialog
            val notedManga = manga
            if (showNotesDialog && notedManga != null) {
                MangaNotesDialog(
                    manga = notedManga,
                    onDismiss = { model.setShowNotesDialog(false) },
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

                // Per-manga reading mode selector
                item {
                    val currentFlags = manga!!.viewerFlags
                    val currentOverride = readingModeFromViewerFlags(currentFlags)
                    var expanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Reading mode:", style = MaterialTheme.typography.bodyMedium)
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(currentOverride?.displayName ?: "Default")
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Default") },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            mangaRepository.update(MangaUpdate(id = mangaId, viewerFlags = 0L))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ReadingMode.LTR.displayName) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            mangaRepository.update(MangaUpdate(id = mangaId, viewerFlags = 1L))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ReadingMode.RTL.displayName) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            mangaRepository.update(MangaUpdate(id = mangaId, viewerFlags = 2L))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ReadingMode.WEBTOON.displayName) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            mangaRepository.update(MangaUpdate(id = mangaId, viewerFlags = 5L))
                                        }
                                    },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }

                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Chapters (${displayedChapters.size}${if (displayedChapters.size != chapters.size) "/${chapters.size}" else ""})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider()
                }
                items(displayedChapters, key = { it.id }) { chapter ->
                    val chapterRefs = remember(chapters) {
                        chapters.sortedBy { it.sourceOrder }
                            .map { ReaderChapterRef(id = it.id, url = it.url, name = it.name, isRead = it.read) }
                    }
                    val downloadQueue by downloadManager.queue.collectAsState()
                    val queuedItem = downloadQueue.find { it.chapterId == chapter.id }
                    val downloadStatus = when {
                        queuedItem != null -> when (queuedItem.status) {
                            mihon.desktop.download.DownloadStatus.DOWNLOADING -> ChapterDownloadStatus.DOWNLOADING
                            else -> ChapterDownloadStatus.QUEUED
                        }
                        downloadManager.isDownloaded(manga?.source ?: 0L, manga?.title ?: "", chapter.name) ->
                            ChapterDownloadStatus.DOWNLOADED
                        else -> ChapterDownloadStatus.NOT_DOWNLOADED
                    }
                    val downloadProgress = downloadProgressFraction(
                        progress = queuedItem?.progress ?: 0,
                        totalPages = queuedItem?.pageUrls?.size ?: 0,
                    )
                    val isSelected = chapter.id in selectionState.selectedIds
                    ChapterRow(
                        chapter = chapter,
                        downloadStatus = downloadStatus,
                        downloadProgress = downloadProgress,
                        isSelected = isSelected,
                        onSelect = { selectionState.toggle(chapter.id) },
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
                        onDeleteDownload = { model.setDeleteConfirmChapter(chapter) },
                        onCancelDownload = { downloadManager.cancel(chapter.id) },
                        onToggleBookmark = {
                            scope.launch {
                                chapterRepository.update(ChapterUpdate(id = chapter.id, bookmark = !chapter.bookmark))
                            }
                        },
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
                                        mangaViewerFlags = manga?.viewerFlags ?: 0L,
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
    val coverManager = remember { Injekt.get<DesktopMangaCoverManager>() }
    var coverVersion by remember { mutableStateOf(0) }
    val coverModel by produceState<String?>(initialValue = manga.thumbnailUrl, coverVersion) {
        value = coverManager.resolveModel(manga.id, manga.thumbnailUrl)
    }
    var showCoverMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box {
            AsyncImage(
                model = coverModel,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(0.7f),
            )
            // Edit cover button overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd),
            ) {
                IconButton(
                    onClick = { showCoverMenu = true },
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit cover",
                        tint = Color.White,
                    )
                }
                DropdownMenu(
                    expanded = showCoverMenu,
                    onDismissRequest = { showCoverMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit cover") },
                        onClick = {
                            showCoverMenu = false
                            SwingUtilities.invokeLater {
                                val chooser = JFileChooser()
                                chooser.fileFilter = FileNameExtensionFilter(
                                    "Image files",
                                    "jpg",
                                    "jpeg",
                                    "png",
                                    "webp",
                                    "gif",
                                )
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    coverManager.setCustomCover(manga.id, chooser.selectedFile)
                                    coverVersion++
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete cover") },
                        onClick = {
                            showCoverMenu = false
                            coverManager.deleteCustomCover(manga.id)
                            coverVersion++
                        },
                        enabled = coverManager.customCoverExists(manga.id),
                    )
                }
            }
        }
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
    downloadProgress: Float?,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRead: () -> Unit,
) {
    ListItem(
        modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
        leadingContent = {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelect() },
            )
        },
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
                // Bookmark toggle
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        if (chapter.bookmark) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = if (chapter.bookmark) "Remove bookmark" else "Add bookmark",
                        tint = if (chapter.bookmark) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                when (downloadStatus) {
                    ChapterDownloadStatus.DOWNLOADED ->
                        IconButton(onClick = onDeleteDownload) {
                            Icon(
                                Icons.Default.DownloadDone,
                                contentDescription = "Delete download",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    ChapterDownloadStatus.QUEUED, ChapterDownloadStatus.DOWNLOADING ->
                        ChapterDownloadingIndicator(
                            downloadProgress = downloadProgress,
                            onCancel = onCancelDownload,
                        )
                    ChapterDownloadStatus.NOT_DOWNLOADED ->
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Download")
                        }
                }
                if (!isSelected) {
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
            }
        },
        supportingContent = chapter.scanlator?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

/**
 * Returns the download progress as a fraction [0.0, 1.0], or null for indeterminate state.
 *
 * Returns null when:
 * - totalPages is 0 (page list not yet resolved)
 * - progress is 0 (download just started, no pages fetched yet)
 */
internal fun downloadProgressFraction(progress: Int, totalPages: Int): Float? {
    if (totalPages == 0 || progress == 0) return null
    return (progress.toFloat() / totalPages).coerceIn(0f, 1f)
}

/**
 * Matches Android's ChapterDownloadIndicator for QUEUE and DOWNLOADING states:
 * - null progress → indeterminate circular indicator
 * - non-null → determinate circular indicator with animation
 * - ArrowDownward icon in center
 * - Click shows context menu with Cancel option
 */
@Composable
private fun ChapterDownloadingIndicator(
    downloadProgress: Float?,
    onCancel: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val indicatorSize = 36.dp
    val strokeWidth = 3.dp
    val strokeColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clickable { showMenu = true }
            .layoutSize(indicatorSize),
        contentAlignment = Alignment.Center,
    ) {
        if (downloadProgress == null) {
            CircularProgressIndicator(
                modifier = Modifier.matchParentSize(),
                color = strokeColor,
                strokeWidth = strokeWidth,
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
                strokeCap = StrokeCap.Butt,
            )
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = downloadProgress,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "download_progress",
            )
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.matchParentSize(),
                color = strokeColor,
                strokeWidth = strokeWidth,
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
                strokeCap = StrokeCap.Butt,
            )
        }
        Icon(
            imageVector = Icons.Default.ArrowDownward,
            contentDescription = "Downloading",
            modifier = Modifier.layoutSize(16.dp),
            tint = strokeColor,
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Cancel") },
                onClick = {
                    onCancel()
                    showMenu = false
                },
            )
        }
    }
}

/**
 * Batch action bar that appears at the bottom when chapters are selected.
 * Mirrors the SelectionActionBar pattern from LibraryTab.
 */
@Composable
private fun ChapterSelectionBar(
    selectedCount: Int,
    onDownload: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDeleteDownload: () -> Unit,
    onClose: () -> Unit,
) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Clear selection")
        }
        Text(
            text = "$selectedCount selected",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDownload) {
            Icon(Icons.Default.CloudDownload, contentDescription = "Download selected")
        }
        IconButton(onClick = onMarkRead) {
            Icon(Icons.Default.DoneAll, contentDescription = "Mark selected as read")
        }
        IconButton(onClick = onMarkUnread) {
            Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "Mark selected as unread")
        }
        IconButton(onClick = onDeleteDownload) {
            Icon(Icons.Default.Delete, contentDescription = "Delete downloaded", tint = MaterialTheme.colorScheme.error)
        }
    }
}
