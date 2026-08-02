package mihon.desktop.ui.library

import tachiyomi.i18n.MR
import java.util.Locale

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
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import mihon.desktop.domain.SourceMangaRefreshKey
import mihon.desktop.domain.SourceMangaRefreshState
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.externalChapterUrlOrNull
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.reader.readingModeLabel
import mihon.desktop.ui.source.desktopSourceErrorMessage
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.manga.model.Manga
import mihon.desktop.platform.toDesktopNotification
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

        val screenModelFactory = LocalMangaDetailScreenModelFactory.current
        val model = rememberScreenModel { screenModelFactory(mangaId) }
        val state by model.state.collectAsState()
        val downloadQueue by model.downloadQueueFlow().collectAsState()
        val dependencies = LocalDesktopUiDependencies.current
        val appPreferences = dependencies.appPreferences
        val hideMissingChapterIndicators by appPreferences.hideMissingChapterIndicators.changes().collectAsState(
            initial = appPreferences.hideMissingChapterIndicators.get(),
        )
        val selectionState = remember { ChapterSelectionState() }

        // Read aliases — immutable vals at all read sites, writes go through model
        val manga = state.manga
        val chapters = state.chapters
        val sourceRefreshStates by dependencies.saveSourceMangaForDetails.refreshStates.collectAsState()
        val sourceRefreshState = manga?.let {
            sourceRefreshStates[SourceMangaRefreshKey(sourceId = it.source, mangaUrl = it.url)]
        }
        val sourceRefreshFailure = sourceRefreshState as? SourceMangaRefreshState.Failure
        val chapterContentState = mangaDetailChapterContentState(sourceRefreshState, chapters.size)
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
        var categoryDialogMode by remember { mutableStateOf<MangaCategoryDialogMode?>(null) }
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
        val refreshFromSource = refresh@{
            val currentManga = manga ?: return@refresh
            val currentSource = source as? CatalogueSource ?: return@refresh
            dependencies.saveSourceMangaForDetails.refreshFromSource(
                source = currentSource,
                listedManga = currentManga.toSourceMangaForRefresh(),
            )
        }
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
        val linkActions = mangaUrl?.let {
            mangaLinkActions(it)
        }
        val nextUnread = remember(chapters, manga?.chapterFlags) {
            manga?.let { nextUnreadChapter(chapters, it) }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(manga?.title ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                    actions = {
                        if (manga != null) {
                            Box {
                                IconButton(onClick = { downloadMenuExpanded = true }) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = MR.strings.desktop_ui_download_chapters.localized())
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
                                Icon(Icons.Default.OpenInBrowser, contentDescription = MR.strings.action_open_in_browser.localized())
                            }
                            IconButton(onClick = linkActions!!.copyLink) {
                                Icon(Icons.Default.Link, contentDescription = MR.strings.action_copy_link.localized())
                            }
                            IconButton(onClick = linkActions!!.share) {
                                Icon(Icons.Default.Share, contentDescription = MR.strings.desktop_ui_share_link.localized())
                            }
                        }

                        // Mark all as read
                        TextButton(onClick = { model.setMarkAllReadConfirm(true) }) { Text(MR.strings.desktop_ui_mark_all_read.localized()) }

                        // Select all chapters / close selection mode
                        if (selectionState.isActive) {
                            IconButton(onClick = { selectionState.selectAll(displayedChapters.map { it.id }) }) {
                                Icon(Icons.Default.SelectAll, contentDescription = MR.strings.action_select_all.localized())
                            }
                            IconButton(onClick = { selectionState.clear() }) {
                                Icon(Icons.Default.Close, contentDescription = MR.strings.desktop_ui_clear_selection.localized())
                            }
                        }

                        // Chapter filter/sort
                        Box {
                            IconButton(onClick = { model.toggleFilterMenu() }) {
                                Icon(Icons.Default.FilterList, contentDescription = MR.strings.desktop_ui_filter_chapters.localized())
                            }
                            DropdownMenu(expanded = showFilterMenu, onDismissRequest = { model.toggleFilterMenu() }) {
                                DropdownMenuItem(
                                    text = { Text(MR.strings.desktop_ui_filter.localized()) },
                                    onClick = {},
                                    enabled = false,
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("${if (filterShowRead) "✓" else " "} ${MR.strings.desktop_ui_show_read.localized()}")
                                    },
                                    onClick = { model.setFilterShowRead(!filterShowRead) },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("${if (filterShowUnread) "✓" else " "} ${MR.strings.desktop_ui_show_unread.localized()}")
                                    },
                                    onClick = { model.setFilterShowUnread(!filterShowUnread) },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${if (filterShowBookmarked) "✓" else " "} " +
                                                MR.strings.desktop_ui_bookmarked_only.localized(),
                                        )
                                    },
                                    onClick = { model.setFilterShowBookmarked(!filterShowBookmarked) },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${if (filterShowDownloaded) "✓" else " "} " +
                                                MR.strings.desktop_ui_downloaded_only.localized(),
                                        )
                                    },
                                    onClick = { model.setFilterShowDownloaded(!filterShowDownloaded) },
                                )
                                if (availableScanlators.isNotEmpty()) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(MR.strings.desktop_ui_scanlators.localized()) },
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
                                    text = { Text(MR.strings.desktop_ui_sort.localized()) },
                                    onClick = {},
                                    enabled = false,
                                )
                                ChapterSortMode.entries.forEach { mode ->
                                    val label = when (mode) {
                                        ChapterSortMode.BY_SOURCE_ORDER -> MR.strings.desktop_ui_source_order.localized()
                                        ChapterSortMode.BY_CHAPTER_NUMBER -> MR.strings.show_chapter_number.localized()
                                        ChapterSortMode.BY_DATE_UPLOAD -> MR.strings.desktop_ui_upload_date.localized()
                                        ChapterSortMode.BY_ALPHABET -> MR.strings.desktop_ui_alphabet.localized()
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
                                    text = { Text(MR.strings.desktop_ui_display.localized()) },
                                    onClick = {},
                                    enabled = false,
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${if (manga?.displayMode == Manga.CHAPTER_DISPLAY_NAME) "✓" else " "} " +
                                                MR.strings.desktop_ui_title.localized(),
                                        )
                                    },
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
                                        Text(
                                            "${if (manga?.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) "✓" else " "} " +
                                                MR.strings.show_chapter_number.localized(),
                                        )
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

                        if (isUpdating || sourceRefreshState is SourceMangaRefreshState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = {
                                val m = manga ?: return@IconButton
                                if (source is CatalogueSource) {
                                    refreshFromSource()
                                } else {
                                    scope.launch {
                                        model.setIsUpdating(true)
                                        model.refreshManga(m)
                                        model.setIsUpdating(false)
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = MR.strings.check_for_updates.localized())
                            }
                        }

                        // Migrate to another source
                        IconButton(onClick = { model.setShowMigrateSourcePicker(true) }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = MR.strings.desktop_ui_migrate_source.localized())
                        }

                        // Notes
                        IconButton(onClick = { model.setShowNotesDialog(true) }) {
                            Icon(Icons.Default.Note, contentDescription = MR.strings.action_notes.localized())
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
                        text = {
                            Text(
                                if (chapters.any { it.read }) {
                                    MR.strings.action_resume.localized()
                                } else {
                                    MR.strings.action_start.localized()
                                },
                            )
                        },
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
                    title = { Text(MR.strings.desktop_ui_delete_download_bba9a9de.localized()) },
                    text = {
                        Text(
                            MR.strings.desktop_ui_delete_chapter_files.localized(
                                Locale.getDefault(),
                                ch.name,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            manga?.let { model.deleteChapterDownload(it, ch) }
                            model.setDeleteConfirmChapter(null)
                        }) { Text(MR.strings.action_delete.localized(), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { model.setDeleteConfirmChapter(null) }) { Text(MR.strings.action_cancel.localized()) }
                    },
                )
            }

            // Mark all read confirmation
            if (markAllReadConfirm) {
                AlertDialog(
                    onDismissRequest = { model.setMarkAllReadConfirm(false) },
                    title = { Text(MR.strings.desktop_ui_mark_all_as_read_b69f52ab.localized()) },
                    text = {
                        Text(
                            MR.strings.desktop_ui_mark_chapters_read.localized(
                                Locale.getDefault(),
                                chapters.size,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                model.markAllRead(chapters)
                            }
                            model.setMarkAllReadConfirm(false)
                        }) { Text(MR.strings.desktop_ui_mark_all_read.localized()) }
                    },
                    dismissButton = {
                        TextButton(onClick = { model.setMarkAllReadConfirm(false) }) { Text(MR.strings.action_cancel.localized()) }
                    },
                )
            }

            val categoryManga = manga
            val activeCategoryDialogMode = categoryDialogMode
            if (activeCategoryDialogMode != null && categoryManga != null) {
                MangaDetailLibraryCategoryDialog(
                    manga = categoryManga,
                    mode = activeCategoryDialogMode,
                    model = model,
                    onDismiss = { categoryDialogMode = null },
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
                    title = { Text(MR.strings.desktop_ui_migrate_to_source.localized()) },
                    text = {
                        if (availableSources.isEmpty()) {
                            Text(MR.strings.desktop_ui_no_other_sources_installed.localized())
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
                        TextButton(onClick = { model.setShowMigrateSourcePicker(false) }) { Text(MR.strings.action_cancel.localized()) }
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
                            Text(MR.strings.desktop_ui_searching.localized())
                        } else {
                            Text(
                                MR.strings.desktop_ui_select_match_count.localized(
                                    Locale.getDefault(),
                                    searchResults.size,
                                ),
                            )
                        }
                    },
                    text = {
                        if (migrateSearching) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (searchResults.isEmpty()) {
                            Text(MR.strings.desktop_ui_no_results_found_try_migrating_manually.localized())
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
                        }) { Text(MR.strings.action_cancel.localized()) }
                    },
                )
            }

            // ── Migration: confirm ────────────────────────────────────────
            val confirmItem = migrateConfirmItem
            if (confirmItem != null) {
                AlertDialog(
                    onDismissRequest = { model.setMigrateConfirmItem(null) },
                    title = { Text(MR.strings.desktop_ui_confirm_migration.localized()) },
                    text = {
                        Text(
                            MR.strings.desktop_ui_confirm_migrate_to.localized(
                                Locale.getDefault(),
                                confirmItem.title,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val targetSourceId = migrateTargetSourceId ?: return@TextButton
                            scope.launch {
                                model.migrateTo(targetSourceId, confirmItem, manga?.title)
                            }
                            model.setMigrateConfirmItem(null)
                            model.setMigrateSearchResults(null)
                            model.setMigrateTargetSourceId(null)
                        }) { Text(MR.strings.action_migrate.localized()) }
                    },
                    dismissButton = {
                        TextButton(onClick = { model.setMigrateConfirmItem(null) }) { Text(MR.strings.action_cancel.localized()) }
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
                        onTagCopy = { text ->
                            dependencies.notificationService.post(
                                dependencies.shareService.copyText(text).toDesktopNotification(),
                            )
                        },
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
                        mangaUrl = mangaUrl,
                        hasUnreadChapters = nextUnread != null,
                        onToggleLibrary = {
                            val current = manga ?: return@MangaDetailActionRow
                            if (current.favorite) {
                                scope.launch {
                                    model.toggleLibrary(current)
                                }
                            } else {
                                categoryDialogMode = MangaCategoryDialogMode.ADD_TO_LIBRARY
                            }
                        },
                        onEditCategories = {
                            categoryDialogMode = MangaCategoryDialogMode.EDIT_CATEGORIES
                        },
                        onEditFetchInterval = { showFetchIntervalDialog = true },
                        onTracking = {
                            onTracking(navigator, manga!!.title, chapters.size.toLong())
                        },
                        onOpenInBrowser = { mangaUrl?.let(::openExternalLink) },
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
                        Text(MR.strings.desktop_ui_reading_mode_e073e5df.localized(), style = MaterialTheme.typography.bodyMedium)
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(currentOverride?.let(::readingModeLabel) ?: MR.strings.label_default.localized())
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(MR.strings.label_default.localized()) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            model.setReadingMode(mangaId, currentFlags, null)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(readingModeLabel(ReadingMode.LTR)) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            model.setReadingMode(mangaId, currentFlags, ReadingMode.LTR)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(readingModeLabel(ReadingMode.RTL)) },
                                    onClick = {
                                        expanded = false
                                        scope.launch {
                                            model.setReadingMode(mangaId, currentFlags, ReadingMode.RTL)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(readingModeLabel(ReadingMode.WEBTOON)) },
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

                sourceRefreshFailure?.let { failure ->
                    item(key = "source-refresh-failure") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = desktopSourceErrorMessage(failure.error),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = refreshFromSource) {
                                Text(MR.strings.action_retry.localized())
                            }
                        }
                    }
                }

                when (chapterContentState) {
                    MangaDetailChapterContentState.LOADING -> item(key = "source-refresh-loading") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.layoutSize(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(MR.strings.loading.localized())
                        }
                    }
                    MangaDetailChapterContentState.FAILURE -> Unit
                    MangaDetailChapterContentState.CONTENT -> mangaDetailChapterListItems(
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
}

internal enum class MangaDetailChapterContentState {
    LOADING,
    FAILURE,
    CONTENT,
}

internal fun mangaDetailChapterContentState(
    refreshState: SourceMangaRefreshState?,
    chapterCount: Int,
): MangaDetailChapterContentState = when {
    chapterCount > 0 -> MangaDetailChapterContentState.CONTENT
    refreshState is SourceMangaRefreshState.Loading -> MangaDetailChapterContentState.LOADING
    refreshState is SourceMangaRefreshState.Failure -> MangaDetailChapterContentState.FAILURE
    else -> MangaDetailChapterContentState.CONTENT
}

internal fun Manga.toSourceMangaForRefresh(): SManga = SManga.create().apply {
    url = this@toSourceMangaForRefresh.url
    title = this@toSourceMangaForRefresh.title
    thumbnail_url = this@toSourceMangaForRefresh.thumbnailUrl
    author = this@toSourceMangaForRefresh.author
    artist = this@toSourceMangaForRefresh.artist
    description = this@toSourceMangaForRefresh.description
    genre = this@toSourceMangaForRefresh.genre?.joinToString(", ")
    status = this@toSourceMangaForRefresh.status.toInt()
    initialized = this@toSourceMangaForRefresh.initialized
}
