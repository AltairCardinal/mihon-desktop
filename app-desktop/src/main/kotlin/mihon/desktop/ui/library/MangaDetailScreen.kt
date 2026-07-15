package mihon.desktop.ui.library

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.externalChapterUrlOrNull
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.manga.model.Manga
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.Desktop
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import androidx.compose.foundation.layout.size as layoutSize

data class MangaDetailScreen(val mangaId: Long) : Screen {

    internal fun onTracking(navigator: Navigator, mangaTitle: String, totalChapters: Long) {
        mihon.desktop.ui.tracking.pushMangaTracking(navigator, mangaId, mangaTitle, totalChapters)
    }

    override val key: String get() = "MangaDetailScreen-$mangaId"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        // Observe test navigation for reader screen
        LaunchedEffect(Unit) {
            mihon.desktop.test.navigation.TestNavigationController.pendingReaderScreen.collect { readerScreen ->
                if (readerScreen != null) {
                    navigator.push(readerScreen)
                    mihon.desktop.test.navigation.TestNavigationController.clearPendingReaderScreen()
                }
            }
        }

        val model = rememberScreenModel { MangaDetailScreenModelFactory.create(mangaId) }
        val state by model.state.collectAsState()
        val downloadQueue by model.downloadQueueFlow().collectAsState()
        val appPreferences = LocalDesktopUiDependencies.current.appPreferences
        val hideMissingChapterIndicators by appPreferences.hideMissingChapterIndicators.changes().collectAsState(
            initial = appPreferences.hideMissingChapterIndicators.get(),
        )
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
        var showCategoryDialog by remember { mutableStateOf(false) }
        var showFetchIntervalDialog by remember { mutableStateOf(false) }
        var downloadMenuExpanded by remember { mutableStateOf(false) }

        LaunchedEffect(mangaId) {
            model.mangaWithChaptersFlow().collect { (m, ch) ->
                model.setManga(m)
                model.setChapters(ch)
            }
        }

        LaunchedEffect(mangaId) {
            model.availableScanlatorsFlow().collect { model.setAvailableScanlators(it) }
        }

        LaunchedEffect(mangaId) {
            model.excludedScanlatorsFlow().collect { model.setExcludedScanlators(it) }
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
                    m != null && model.isChapterDownloaded(m, ch)
                } else {
                    true
                }
                readOk && bookmarkOk && downloadedOk
            }
            sortMangaDetailChapters(filtered, chapterSortMode, chapterSortAscending)
        }
        val chapterRows = remember(displayedChapters, chapterSortAscending, hideMissingChapterIndicators) {
            mangaDetailChapterRows(
                chapters = displayedChapters,
                ascending = chapterSortAscending,
                hideMissingChapters = hideMissingChapterIndicators,
            )
        }
        val source = remember(manga?.source) {
            manga?.let { m -> model.sourceFor(m) }
        }
        val isHttpSource = source is eu.kanade.tachiyomi.source.online.HttpSource
        val mangaUrl = remember(manga?.url, source) {
            val m = manga
            val httpSource = source as? eu.kanade.tachiyomi.source.online.HttpSource
            if (m != null && httpSource != null) {
                runCatching {
                    httpSource.getMangaUrl(
                        eu.kanade.tachiyomi.source.model.SManga.create().apply { url = m.url },
                    )
                }.getOrNull()
            } else {
                null
            }
        }
        val nextUnread = remember(chapters) { nextUnreadChapter(chapters) }

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
                        if (manga != null) {
                            Box {
                                IconButton(onClick = { downloadMenuExpanded = true }) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = "Download chapters")
                                }
                                DropdownMenu(
                                    expanded = downloadMenuExpanded,
                                    onDismissRequest = { downloadMenuExpanded = false },
                                ) {
                                    mangaDetailDownloadActions().forEach { action ->
                                        DropdownMenuItem(
                                            text = { Text(action.label) },
                                            onClick = {
                                                val m = manga ?: return@DropdownMenuItem
                                                chaptersForDownloadAction(chapters, action)
                                                    .let { model.enqueueDownloads(m, it) }
                                                downloadMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        if (mangaUrl != null) {
                            IconButton(onClick = { openExternalLink(mangaUrl) }) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser")
                            }
                            IconButton(onClick = { copyText(mangaUrl) }) {
                                Icon(Icons.Default.Link, contentDescription = "Copy link")
                            }
                            IconButton(onClick = { copyText(mangaUrl) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share link")
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
                                                    model.updateExcludedScanlators(newExcluded)
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
                                        ChapterSortMode.BY_ALPHABET -> "Alphabet"
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
                                            manga?.let { m ->
                                                scope.launch {
                                                    model.setChapterSort(m, mode)
                                                }
                                            }
                                            model.toggleFilterMenu()
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("── Display ──") },
                                    onClick = {},
                                    enabled = false,
                                )
                                DropdownMenuItem(
                                    text = { Text(if (manga?.displayMode == Manga.CHAPTER_DISPLAY_NAME) "✓ Title" else "  Title") },
                                    onClick = {
                                        manga?.let { m ->
                                            scope.launch {
                                                model.setChapterDisplayMode(m, Manga.CHAPTER_DISPLAY_NAME)
                                            }
                                        }
                                        model.toggleFilterMenu()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (manga?.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) "✓ Chapter number" else "  Chapter number")
                                    },
                                    onClick = {
                                        manga?.let { m ->
                                            scope.launch {
                                                model.setChapterDisplayMode(m, Manga.CHAPTER_DISPLAY_NUMBER)
                                            }
                                        }
                                        model.toggleFilterMenu()
                                    },
                                )
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
                                    model.refreshManga(m)
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
                    val selectedChapters = chapters.filter { it.id in selectionState.selectedIds }
                    val selectedDownloadAction = chapterSelectionDownloadAction(selectedChapters) { ch ->
                        val m = manga
                        m != null && model.isChapterDownloaded(m, ch)
                    }
                    ChapterSelectionBar(
                        selectedCount = selectionState.selectedIds.size,
                        downloadAction = selectedDownloadAction,
                        onBookmark = {
                            scope.launch {
                                model.markSelectedBookmark(selectedChapters)
                                selectionState.clear()
                            }
                        },
                        onMarkRead = {
                            scope.launch {
                                model.markSelectedRead(selectedChapters, read = true)
                                selectionState.clear()
                            }
                        },
                        onMarkUnread = {
                            scope.launch {
                                model.markSelectedRead(selectedChapters, read = false)
                                selectionState.clear()
                            }
                        },
                        onMarkBelowRead = {
                            scope.launch {
                                model.markAtOrBelowRead(displayedChapters, selectionState.selectedIds)
                                selectionState.clear()
                            }
                        },
                        onDownloadOrDelete = {
                            val m = manga ?: return@ChapterSelectionBar
                            scope.launch {
                                when (selectedDownloadAction) {
                                    ChapterSelectionDownloadAction.DOWNLOAD -> {
                                        model.enqueueDownloadBatch(m, selectedChapters)
                                    }
                                    ChapterSelectionDownloadAction.DELETE_DOWNLOAD -> {
                                        model.deleteDownloadBatch(m, selectedChapters)
                                    }
                                }
                                selectionState.clear()
                            }
                        },
                        onClose = { selectionState.clear() },
                    )
                }
            },
            floatingActionButton = {
                val ch = nextUnread
                if (ch != null && manga != null && !selectionState.isActive) {
                    ExtendedFloatingActionButton(
                        text = { Text(if (chapters.any { it.read }) "Resume" else "Start") },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        onClick = {
                            val externalUrl = ch.url.externalChapterUrlOrNull()
                            if (externalUrl != null) {
                                openExternalLink(externalUrl)
                                return@ExtendedFloatingActionButton
                            }
                            val request = model.readerRequest(
                                manga = manga!!,
                                chapters = chapters,
                                chapter = ch,
                            ) ?: return@ExtendedFloatingActionButton
                            navigator.push(
                                DesktopReaderScreen(
                                    chapterTitle = request.chapterTitle,
                                    mangaId = request.mangaId,
                                    mangaTitle = request.mangaTitle,
                                    pageUrls = emptyList(),
                                    isWebtoon = false,
                                    sourceId = request.sourceId,
                                    chapterUrl = request.chapterUrl,
                                    chapterId = request.chapterId,
                                    chapters = request.chapters,
                                    currentChapterIndex = request.currentChapterIndex,
                                    initialPage = request.initialPage,
                                    mangaViewerFlags = request.mangaViewerFlags,
                                ),
                            )
                        },
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
                            manga?.let { model.deleteChapterDownload(it, ch) }
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
                                model.markAllRead(chapters)
                            }
                            model.setMarkAllReadConfirm(false)
                        }) { Text("Mark all read") }
                    },
                    dismissButton = {
                        TextButton(onClick = { model.setMarkAllReadConfirm(false) }) { Text("Cancel") }
                    },
                )
            }

            val categoryManga = manga
            if (showCategoryDialog && categoryManga != null) {
                MangaCategoryDialog(
                    mangaId = categoryManga.id,
                    loadCategories = model::categories,
                    loadCategoryIds = model::categoryIdsForManga,
                    setCategories = model::setCategoriesForManga,
                    onDismiss = { showCategoryDialog = false },
                )
            }

            val intervalManga = manga
            if (showFetchIntervalDialog && intervalManga != null) {
                FetchIntervalDialog(
                    manga = intervalManga,
                    onDismiss = { showFetchIntervalDialog = false },
                    onConfirm = { interval ->
                        scope.launch {
                            model.setFetchInterval(intervalManga.id, interval)
                        }
                        showFetchIntervalDialog = false
                    },
                )
            }


            // ── Migration: source picker ──────────────────────────────────
            if (showMigrateSourcePicker) {
                val availableSources = remember {
                    model.migrationSources(manga?.source)
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
                                                    model.setMigrateSearchResults(model.searchMigration(src, query))
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
                                model.migrateTo(targetSourceId, confirmItem, manga?.title)
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
                item {
                    MangaHeader(
                        manga = manga!!,
                        coverModel = state.coverModel,
                        coverLastModified = state.coverLastModified,
                        coverFeedback = state.coverFeedback,
                        onEditCover = { scope.launch { model.chooseCustomCover() } },
                        onDeleteCover = { scope.launch { model.deleteCustomCover() } },
                        sourceName = source?.name,
                        onTagSearch = { tag -> navigator.push(GlobalSearchScreen(initialQuery = tag)) },
                        onTagCopy = ::copyText,
                        onAuthorClick = { author ->
                            scope.launch {
                                val creatorId = model.linkCreator(author, CreatorRole.AUTHOR)
                                authorDetailScreenOrNull(author, creatorId)?.let(navigator::push)
                            }
                        },
                        onArtistClick = { artist ->
                            scope.launch {
                                val creatorId = model.linkCreator(artist, CreatorRole.ARTIST)
                                authorDetailScreenOrNull(artist, creatorId)?.let(navigator::push)
                            }
                        },
                    )
                }

                item {
                    MangaDetailActionRow(
                        manga = manga!!,
                        isHttpSource = isHttpSource,
                        hasUnreadChapters = nextUnread != null,
                        onToggleLibrary = {
                            val current = manga ?: return@MangaDetailActionRow
                            scope.launch {
                                model.toggleLibrary(current)
                            }
                        },
                        onEditCategories = { showCategoryDialog = true },
                        onEditFetchInterval = { showFetchIntervalDialog = true },
                        onTracking = {
                            onTracking(navigator, manga!!.title, chapters.size.toLong())
                        },
                        onOpenInBrowser = { mangaUrl?.let(::openExternalLink) },
                        onCopyLink = { mangaUrl?.let(::copyText) },
                        onShare = { mangaUrl?.let(::copyText) },
                    )
                }

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
                                            model.setReadingMode(mangaId, currentFlags, null)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ReadingMode.LTR.displayName) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            model.setReadingMode(mangaId, currentFlags, ReadingMode.LTR)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ReadingMode.RTL.displayName) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            model.setReadingMode(mangaId, currentFlags, ReadingMode.RTL)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ReadingMode.WEBTOON.displayName) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            model.setReadingMode(mangaId, currentFlags, ReadingMode.WEBTOON)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }

                mangaDetailChapterListItems(
                    displayedChapterCount = displayedChapters.size,
                    totalChapterCount = chapters.size,
                    chapterRows = chapterRows,
                    downloadQueue = downloadQueue,
                    manga = manga,
                    isChapterDownloaded = model::isChapterDownloaded,
                    isChapterSelected = { chapterId -> chapterId in selectionState.selectedIds },
                    onSelectChapter = selectionState::toggle,
                    onDownloadChapter = { chapter ->
                        manga?.let { model.enqueueDownloads(it, listOf(chapter)) }
                    },
                    onDeleteDownload = model::setDeleteConfirmChapter,
                    onCancelDownload = model::cancelChapterDownload,
                    onToggleBookmark = { chapter ->
                        scope.launch {
                            model.toggleChapterBookmark(chapter)
                        }
                    },
                    onReadChapter = { chapter ->
                        scope.launch {
                            val externalUrl = chapter.url.externalChapterUrlOrNull()
                            if (externalUrl != null) {
                                openExternalLink(externalUrl)
                                return@launch
                            }
                            val request = model.readerRequest(
                                manga = manga!!,
                                chapters = chapters,
                                chapter = chapter,
                            ) ?: return@launch
                            navigator.push(
                                DesktopReaderScreen(
                                    chapterTitle = request.chapterTitle,
                                    mangaId = request.mangaId,
                                    mangaTitle = request.mangaTitle,
                                    pageUrls = emptyList(),
                                    isWebtoon = false,
                                    sourceId = request.sourceId,
                                    chapterUrl = request.chapterUrl,
                                    chapterId = request.chapterId,
                                    chapters = request.chapters,
                                    currentChapterIndex = request.currentChapterIndex,
                                    initialPage = request.initialPage,
                                    mangaViewerFlags = request.mangaViewerFlags,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}
