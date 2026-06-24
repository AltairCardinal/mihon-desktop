package mihon.desktop.ui.library

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
import mihon.desktop.reader.viewerFlagsWithReadingMode
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
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
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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
        val categoryRepository = remember { Injekt.get<CategoryRepository>() }
        val setMangaCategories = remember { Injekt.get<SetMangaCategories>() }
        val getExcludedScanlators = remember { Injekt.get<GetExcludedScanlators>() }
        val setExcludedScanlators = remember { Injekt.get<SetExcludedScanlators>() }
        val getAvailableScanlators = remember { Injekt.get<GetAvailableScanlators>() }
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
        var showCategoryDialog by remember { mutableStateOf(false) }
        var showFetchIntervalDialog by remember { mutableStateOf(false) }
        var showTrackingInfoDialog by remember { mutableStateOf(false) }
        var downloadMenuExpanded by remember { mutableStateOf(false) }

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
            sortMangaDetailChapters(filtered, chapterSortMode, chapterSortAscending)
        }
        val source = remember(manga?.source) {
            manga?.let { m -> sourceManager.getCatalogueSources().find { it.id == m.source } }
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
                                                    .filterNot { ch -> downloadManager.isDownloaded(m.source, m.title, ch.name) }
                                                    .forEach { ch ->
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
                                                val (nextMode, nextAscending) = nextChapterSort(
                                                    currentMode = chapterSortMode,
                                                    currentAscending = chapterSortAscending,
                                                    requestedMode = mode,
                                                )
                                                scope.launch {
                                                    mangaRepository.update(
                                                        MangaUpdate(
                                                            id = m.id,
                                                            chapterFlags = chapterSortFlags(
                                                                mode = nextMode,
                                                                ascending = nextAscending,
                                                                currentFlags = m.chapterFlags,
                                                            ),
                                                        ),
                                                    )
                                                }
                                            }
                                            model.toggleSort(mode)
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
                                                mangaRepository.update(
                                                    MangaUpdate(
                                                        id = m.id,
                                                        chapterFlags = chapterDisplayFlags(
                                                            displayMode = Manga.CHAPTER_DISPLAY_NAME,
                                                            currentFlags = m.chapterFlags,
                                                        ),
                                                    ),
                                                )
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
                                                mangaRepository.update(
                                                    MangaUpdate(
                                                        id = m.id,
                                                        chapterFlags = chapterDisplayFlags(
                                                            displayMode = Manga.CHAPTER_DISPLAY_NUMBER,
                                                            currentFlags = m.chapterFlags,
                                                        ),
                                                    ),
                                                )
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
                    val selectedChapters = chapters.filter { it.id in selectionState.selectedIds }
                    val selectedDownloadAction = chapterSelectionDownloadAction(selectedChapters) { ch ->
                        val m = manga
                        m != null && downloadManager.isDownloaded(m.source, m.title, ch.name)
                    }
                    ChapterSelectionBar(
                        selectedCount = selectionState.selectedIds.size,
                        downloadAction = selectedDownloadAction,
                        onBookmark = {
                            scope.launch {
                                val shouldBookmark = selectedChapters.any { !it.bookmark }
                                selectedChapters.forEach { ch ->
                                    chapterRepository.update(ChapterUpdate(id = ch.id, bookmark = shouldBookmark))
                                }
                                selectionState.clear()
                            }
                        },
                        onMarkRead = {
                            scope.launch {
                                selectedChapters.forEach { ch ->
                                    chapterRepository.update(ChapterUpdate(id = ch.id, read = true))
                                }
                                selectionState.clear()
                            }
                        },
                        onMarkUnread = {
                            scope.launch {
                                selectedChapters.forEach { ch ->
                                    chapterRepository.update(ChapterUpdate(id = ch.id, read = false))
                                }
                                selectionState.clear()
                            }
                        },
                        onMarkBelowRead = {
                            scope.launch {
                                chaptersAtOrBelowSelection(displayedChapters, selectionState.selectedIds).forEach { ch ->
                                    chapterRepository.update(ChapterUpdate(id = ch.id, read = true))
                                }
                                selectionState.clear()
                            }
                        },
                        onDownloadOrDelete = {
                            val m = manga ?: return@ChapterSelectionBar
                            when (selectedDownloadAction) {
                                ChapterSelectionDownloadAction.DOWNLOAD -> {
                                    selectedChapters
                                        .filterNot { ch -> downloadManager.isDownloaded(m.source, m.title, ch.name) }
                                        .forEach { ch ->
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
                                }
                                ChapterSelectionDownloadAction.DELETE_DOWNLOAD -> {
                                    selectedChapters.forEach { ch ->
                                        downloadManager.deleteDownload(m.source, m.title, ch.name)
                                    }
                                }
                            }
                            selectionState.clear()
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
                            val chapterRefs = chapters.sortedBy { it.sourceOrder }
                                .map { ReaderChapterRef(id = it.id, url = it.url, name = it.name, isRead = it.read) }
                            val idx = ReaderNavigator.indexForId(chapterRefs, ch.id)
                            navigator.push(
                                DesktopReaderScreen(
                                    chapterTitle = ch.name,
                                    mangaId = mangaId,
                                    mangaTitle = manga?.title ?: "",
                                    pageUrls = emptyList(),
                                    isWebtoon = false,
                                    sourceId = manga?.source ?: 0L,
                                    chapterUrl = ch.url,
                                    chapterId = ch.id,
                                    chapters = chapterRefs,
                                    currentChapterIndex = idx,
                                    initialPage = ch.lastPageRead.toInt().coerceAtLeast(0),
                                    mangaViewerFlags = manga?.viewerFlags ?: 0L,
                                    progressTracker = progressTracker,
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

            val categoryManga = manga
            if (showCategoryDialog && categoryManga != null) {
                MangaCategoryDialog(
                    mangaId = categoryManga.id,
                    categoryRepository = categoryRepository,
                    setMangaCategories = setMangaCategories,
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
                            mangaRepository.update(
                                MangaUpdate(
                                    id = intervalManga.id,
                                    fetchInterval = if (interval == 0) 0 else -interval,
                                ),
                            )
                        }
                        showFetchIntervalDialog = false
                    },
                )
            }

            if (showTrackingInfoDialog) {
                AlertDialog(
                    onDismissRequest = { showTrackingInfoDialog = false },
                    title = { Text("Tracking") },
                    text = { Text("Tracking services are not available in Mihon Desktop yet.") },
                    confirmButton = {
                        TextButton(onClick = { showTrackingInfoDialog = false }) { Text("OK") }
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
                item {
                    MangaHeader(
                        manga = manga!!,
                        sourceName = source?.name,
                        onTagSearch = { tag -> navigator.push(GlobalSearchScreen(initialQuery = tag)) },
                        onTagCopy = ::copyText,
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
                                mangaRepository.update(
                                    MangaUpdate(
                                        id = current.id,
                                        favorite = !current.favorite,
                                        dateAdded = if (!current.favorite) System.currentTimeMillis() else current.dateAdded,
                                    ),
                                )
                            }
                        },
                        onEditCategories = { showCategoryDialog = true },
                        onEditFetchInterval = { showFetchIntervalDialog = true },
                        onTracking = { showTrackingInfoDialog = true },
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
                                            mangaRepository.update(
                                                MangaUpdate(
                                                    id = mangaId,
                                                    viewerFlags = viewerFlagsWithReadingMode(currentFlags, null),
                                                ),
                                            )
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ReadingMode.LTR.displayName) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            mangaRepository.update(
                                                MangaUpdate(
                                                    id = mangaId,
                                                    viewerFlags = viewerFlagsWithReadingMode(currentFlags, ReadingMode.LTR),
                                                ),
                                            )
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ReadingMode.RTL.displayName) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            mangaRepository.update(
                                                MangaUpdate(
                                                    id = mangaId,
                                                    viewerFlags = viewerFlagsWithReadingMode(currentFlags, ReadingMode.RTL),
                                                ),
                                            )
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ReadingMode.WEBTOON.displayName) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            mangaRepository.update(
                                                MangaUpdate(
                                                    id = mangaId,
                                                    viewerFlags = viewerFlagsWithReadingMode(currentFlags, ReadingMode.WEBTOON),
                                                ),
                                            )
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
                        title = chapterDisplayTitle(chapter, manga?.displayMode ?: Manga.CHAPTER_DISPLAY_NAME),
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
                                        mangaId = mangaId,
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
private fun MangaHeader(
    manga: Manga,
    sourceName: String?,
    onTagSearch: (String) -> Unit,
    onTagCopy: (String) -> Unit,
) {
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
                    text = "Author: $author",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            manga.artist?.takeIf { it != manga.author }?.let { artist ->
                Text(
                    text = "Artist: $artist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = listOfNotNull(
                    mangaStatusLabel(manga.status),
                    sourceName?.let { "Source: $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
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
            val tags = manga.genre.orEmpty().filter { it.isNotBlank() }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = { onTagSearch(tag) },
                            label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                IconButton(onClick = { onTagCopy(tag) }) {
                                    Icon(Icons.Default.Link, contentDescription = "Copy tag")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaCategoryDialog(
    mangaId: Long,
    categoryRepository: CategoryRepository,
    setMangaCategories: SetMangaCategories,
    onDismiss: () -> Unit,
) {
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var checkedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mangaId) {
        categories = categoryRepository.getAll().sortedBy { it.order }
        checkedIds = categoryRepository.getCategoriesByMangaId(mangaId).map { it.id }.toSet()
        loaded = true
    }

    if (!loaded) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit categories") },
        text = {
            if (categories.isEmpty()) {
                Text("No categories. Create categories from Library first.")
            } else {
                Column {
                    categories.forEach { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    checkedIds = if (category.id in checkedIds) {
                                        checkedIds - category.id
                                    } else {
                                        checkedIds + category.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = category.id in checkedIds,
                                onCheckedChange = { checked ->
                                    checkedIds = if (checked) checkedIds + category.id else checkedIds - category.id
                                },
                            )
                            Text(category.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        setMangaCategories.await(mangaId, checkedIds.toList())
                    }
                    onDismiss()
                },
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FetchIntervalDialog(
    manga: Manga,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selectedInterval by remember(manga.fetchInterval) {
        mutableStateOf(manga.fetchInterval.coerceAtMost(0).let { -it })
    }
    val options = listOf(0, 1, 2, 7, 14, 30)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update interval") },
        text = {
            Column {
                options.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedInterval = interval }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedInterval == interval,
                            onCheckedChange = { selectedInterval = interval },
                        )
                        Text(
                            text = if (interval == 0) "Default" else "$interval day${if (interval == 1) "" else "s"}",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedInterval) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun mangaStatusLabel(status: Long): String? =
    when (status) {
        SManga.ONGOING.toLong() -> "Ongoing"
        SManga.COMPLETED.toLong() -> "Completed"
        SManga.LICENSED.toLong() -> "Licensed"
        SManga.PUBLISHING_FINISHED.toLong() -> "Publishing finished"
        SManga.CANCELLED.toLong() -> "Cancelled"
        else -> null
    }

@Composable
private fun MangaDetailActionRow(
    manga: Manga,
    isHttpSource: Boolean,
    hasUnreadChapters: Boolean,
    onToggleLibrary: () -> Unit,
    onEditCategories: () -> Unit,
    onEditFetchInterval: () -> Unit,
    onTracking: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
) {
    val actions = mangaDetailPrimaryActionTypes(
        isFavorite = manga.favorite,
        isHttpSource = isHttpSource,
        hasUnreadChapters = hasUnreadChapters,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            when (action) {
                MangaDetailPrimaryActionType.TOGGLE_LIBRARY ->
                    TextButton(onClick = onToggleLibrary) {
                        Icon(
                            if (manga.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (manga.favorite) "In library" else "Add to library")
                    }
                MangaDetailPrimaryActionType.EDIT_CATEGORIES ->
                    IconButton(onClick = onEditCategories) {
                        Icon(Icons.Default.Category, contentDescription = "Edit categories")
                    }
                MangaDetailPrimaryActionType.EDIT_FETCH_INTERVAL ->
                    IconButton(onClick = onEditFetchInterval) {
                        Icon(Icons.Default.HourglassEmpty, contentDescription = "Edit update interval")
                    }
                MangaDetailPrimaryActionType.TRACKING ->
                    IconButton(onClick = onTracking) {
                        Icon(Icons.Default.Sync, contentDescription = "Tracking")
                    }
                MangaDetailPrimaryActionType.OPEN_IN_BROWSER ->
                    IconButton(onClick = onOpenInBrowser) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser")
                    }
                MangaDetailPrimaryActionType.COPY_LINK ->
                    IconButton(onClick = onCopyLink) {
                        Icon(Icons.Default.Link, contentDescription = "Copy link")
                    }
                MangaDetailPrimaryActionType.SHARE ->
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Share link")
                    }
                MangaDetailPrimaryActionType.CONTINUE_READING -> Unit
            }
        }
    }
}

private enum class ChapterDownloadStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED }

@Composable
private fun ChapterRow(
    chapter: Chapter,
    title: String,
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
                text = title,
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

private val MangaDetailDownloadAction.label: String
    get() = when (this) {
        MangaDetailDownloadAction.NEXT_1_CHAPTER -> "Next 1 chapter"
        MangaDetailDownloadAction.NEXT_5_CHAPTERS -> "Next 5 chapters"
        MangaDetailDownloadAction.NEXT_10_CHAPTERS -> "Next 10 chapters"
        MangaDetailDownloadAction.NEXT_25_CHAPTERS -> "Next 25 chapters"
        MangaDetailDownloadAction.UNREAD_CHAPTERS -> "Unread chapters"
        MangaDetailDownloadAction.BOOKMARKED_CHAPTERS -> "Bookmarked chapters"
    }

private fun copyText(text: String) {
    runCatching {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }
}

private fun openExternalLink(url: String) {
    runCatching {
        Desktop.getDesktop().browse(URI(url))
    }
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
    downloadAction: ChapterSelectionDownloadAction,
    onBookmark: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onMarkBelowRead: () -> Unit,
    onDownloadOrDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val actionTypes = chapterSelectionActionTypes(downloadAction)
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
        actionTypes.forEach { action ->
            when (action) {
                ChapterSelectionActionType.BOOKMARK ->
                    IconButton(onClick = onBookmark) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Bookmark selected")
                    }
                ChapterSelectionActionType.MARK_READ ->
                    IconButton(onClick = onMarkRead) {
                        Icon(Icons.Default.DoneAll, contentDescription = "Mark selected as read")
                    }
                ChapterSelectionActionType.MARK_UNREAD ->
                    IconButton(onClick = onMarkUnread) {
                        Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "Mark selected as unread")
                    }
                ChapterSelectionActionType.MARK_BELOW_READ ->
                    IconButton(onClick = onMarkBelowRead) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Mark below as read")
                    }
                ChapterSelectionActionType.DOWNLOAD ->
                    IconButton(onClick = onDownloadOrDelete) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Download selected")
                    }
                ChapterSelectionActionType.DELETE_DOWNLOAD ->
                    IconButton(onClick = onDownloadOrDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete downloaded",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
            }
        }
    }
}
