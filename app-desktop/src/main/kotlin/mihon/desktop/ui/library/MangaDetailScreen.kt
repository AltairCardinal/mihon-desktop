package mihon.desktop.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
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
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.SourceMangaRefreshKey
import mihon.desktop.domain.SourceMangaRefreshState
import mihon.desktop.platform.toDesktopNotification
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.externalChapterUrlOrNull
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.reader.readingModeLabel
import mihon.desktop.ui.source.desktopSourceErrorMessage
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.filterAndSortChapters
import tachiyomi.domain.chapter.service.hasActiveChapterFilters
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.toSourceManga
import tachiyomi.i18n.MR
import java.util.Locale
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

        val screenModelFactory = LocalMangaDetailScreenModelFactory.current
        val model = rememberScreenModel { screenModelFactory(mangaId) }
        val state by model.state.collectAsState()
        val downloadQueue by model.downloadQueueFlow().collectAsState()
        val downloadedOnly by model.downloadedOnlyFlow().collectAsState(false)
        val downloadAvailability by model.downloadAvailabilityFlow().collectAsState(0L)
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
        val chapterSortMode = state.chapterSortMode
        val chapterSortAscending = state.chapterSortAscending
        val availableScanlators = state.availableScanlators
        val excludedScanlators = state.excludedScanlators
        var categoryDialogMode by remember { mutableStateOf<MangaCategoryDialogMode?>(null) }
        var categoryMenuExpanded by remember { mutableStateOf(false) }
        var restoreCategoryMenuFocus by rememberSaveable { mutableStateOf(false) }
        val categoryMenuFocus = remember { FocusRequester() }
        val categorySnackbar = remember { SnackbarHostState() }
        LaunchedEffect(restoreCategoryMenuFocus) {
            if (restoreCategoryMenuFocus && navigator.lastItem == this@MangaDetailScreen) {
                androidx.compose.runtime.withFrameNanos { }
                categoryMenuFocus.requestFocus()
                restoreCategoryMenuFocus = false
            }
        }
        val chapterOptionsFocus = remember { FocusRequester() }
        var returnChapterOptionsFocus by remember { mutableStateOf(false) }
        LaunchedEffect(returnChapterOptionsFocus) {
            if (returnChapterOptionsFocus) {
                androidx.compose.runtime.withFrameNanos { }
                chapterOptionsFocus.requestFocus()
                returnChapterOptionsFocus = false
            }
        }
        val libraryFocus = remember { FocusRequester() }
        val intervalFocus = remember { FocusRequester() }
        var returnLibraryFocus by remember { mutableStateOf(false) }
        var returnIntervalFocus by remember { mutableStateOf(false) }
        LaunchedEffect(returnLibraryFocus, returnIntervalFocus, categoryDialogMode) {
            if (categoryDialogMode == null && (returnLibraryFocus || returnIntervalFocus)) {
                androidx.compose.runtime.withFrameNanos { }
                val trigger = if (returnLibraryFocus) libraryFocus else intervalFocus
                trigger.requestFocus()
                returnLibraryFocus = false
                returnIntervalFocus = false
            }
        }
        var showFetchIntervalDialog by remember { mutableStateOf(false) }
        var showCoverViewer by remember { mutableStateOf(false) }
        val coverFocus = remember { FocusRequester() }
        val backFocus = remember { FocusRequester() }
        var returnCoverFocus by remember { mutableStateOf(false) }
        var notesFromSummary by remember { mutableStateOf(false) }
        LaunchedEffect(returnCoverFocus) {
            if (returnCoverFocus && navigator.lastItem == this@MangaDetailScreen) {
                androidx.compose.runtime.withFrameNanos { }
                val restored = try {
                    coverFocus.requestFocus()
                } catch (_: IllegalStateException) {
                    false
                }
                if (!restored) backFocus.requestFocus()
                returnCoverFocus = false
            }
        }
        var duplicateEntries by remember {
            mutableStateOf<List<tachiyomi.domain.manga.model.MangaWithChapterCount>?>(null)
        }
        var removalSnapshot by remember { mutableStateOf<Pair<Manga, List<Chapter>>?>(null) }
        var downloadMenuExpanded by remember { mutableStateOf(false) }
        var creatorIdentityLoading by remember { mutableStateOf(false) }
        var creatorIdentityError by remember { mutableStateOf<String?>(null) }

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

        // Manga flags and the shared projection are the authoritative chapter settings.
        val displayedChapters = remember(chapters, manga, downloadQueue, downloadedOnly, downloadAvailability) {
            manga?.let { current ->
                chapters.filterAndSortChapters(current, downloadedOnly, current.source == 0L) {
                    model.isChapterDownloaded(current, it)
                }
            } ?: emptyList()
        }
        val chapterRows = remember(displayedChapters, chapterSortAscending, hideMissingChapterIndicators) {
            mangaDetailChapterRows(
                chapters = displayedChapters,
                ascending = chapterSortAscending,
                hideMissingChapters = hideMissingChapterIndicators,
            )
        }
        LaunchedEffect(displayedChapters) {
            selectionState.retainVisibleIds(displayedChapters.map { it.id })
        }
        val source = remember(manga?.source) {
            manga?.let { m -> model.sourceFor(m) }
        }
        val refreshFromSource = refresh@{
            val currentManga = manga ?: return@refresh
            val currentSource = source ?: return@refresh
            dependencies.saveSourceMangaForDetails.refreshFromSource(
                source = currentSource,
                listedManga = currentManga.toSourceMangaForRefresh(),
            )
        }
        LaunchedEffect(manga?.id) {
            val currentManga = manga ?: return@LaunchedEffect
            if (model.state.value.chapters.isNotEmpty()) return@LaunchedEffect
            if (sourceRefreshState is SourceMangaRefreshState.Loading) return@LaunchedEffect
            val currentSource = model.sourceFor(currentManga) ?: return@LaunchedEffect
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

        if (showFilterMenu) {
            MangaChapterOptionsPanel(model, downloadedOnly) {
                model.toggleFilterMenu()
                returnChapterOptionsFocus = true
            }
        }

        val canRefresh = !isUpdating && sourceRefreshState !is SourceMangaRefreshState.Loading
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val layout = mihon.desktop.ui.home.LocalDesktopWindowLayout.current
                ?: mihon.desktop.ui.home.DesktopWindowLayout(
                    maxWidth,
                    maxHeight,
                    dependencies.layoutSnapshot.tabletUiMode,
                )
            Scaffold(
                snackbarHost = { SnackbarHost(categorySnackbar) },
                topBar = {
                    TopAppBar(
                        title = { Text(manga?.title ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(modifier = Modifier.focusRequester(backFocus), onClick = { navigator.pop() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = MR.strings.action_bar_up_description.localized(),
                                )
                            }
                        },
                        actions = {
                            if (selectionState.isActive) {
                                IconButton(onClick = { selectionState.selectAll(displayedChapters.map { it.id }) }) {
                                    Icon(Icons.Default.SelectAll, MR.strings.action_select_all.localized())
                                }
                                IconButton(onClick = { selectionState.clear() }) {
                                    Icon(Icons.Default.Close, MR.strings.desktop_ui_clear_selection.localized())
                                }
                            }
                            if (manga != null) {
                                Box {
                                    IconButton(onClick = { downloadMenuExpanded = true }) {
                                        Icon(
                                            Icons.Default.CloudDownload,
                                            contentDescription = MR.strings.desktop_ui_download_chapters.localized(),
                                        )
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
                            IconButton(
                                onClick = { model.toggleFilterMenu() },
                                modifier = Modifier.focusRequester(chapterOptionsFocus),
                            ) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = MR.strings.desktop_ui_filter_chapters.localized(),
                                    tint = if (
                                        manga?.hasActiveChapterFilters(downloadedOnly) == true ||
                                        excludedScanlators.isNotEmpty()
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }

                            Box {
                                IconButton(
                                    modifier = Modifier.focusRequester(
                                        categoryMenuFocus,
                                    ).testTag("manga-category-menu"),
                                    onClick = { categoryMenuExpanded = true },
                                ) { Icon(Icons.Default.MoreVert, MR.strings.label_more.localized()) }
                                DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = {
                                    categoryMenuExpanded =
                                        false
                                }) {
                                    DropdownMenuItem(
                                        text = { Text(MR.strings.check_for_updates.localized()) },
                                        enabled = canRefresh,
                                        onClick = {
                                            categoryMenuExpanded = false
                                            val current = manga ?: return@DropdownMenuItem
                                            if (source != null) {
                                                refreshFromSource()
                                            } else {
                                                scope.launch {
                                                    model.setIsUpdating(true)
                                                    try {
                                                        model.refreshManga(current)
                                                    } finally {
                                                        model.setIsUpdating(false)
                                                    }
                                                }
                                            }
                                        },
                                    )
                                    if (manga?.favorite == true) {
                                        DropdownMenuItem(
                                            text = { Text(MR.strings.action_edit_categories.localized()) },
                                            onClick = {
                                                categoryMenuExpanded = false
                                                categoryDialogMode =
                                                    MangaCategoryDialogMode.EDIT_CATEGORIES
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(MR.strings.desktop_ui_migrate_source.localized()) },
                                        onClick = {
                                            categoryMenuExpanded = false
                                            val current = manga ?: return@DropdownMenuItem
                                            navigator.push(
                                                mihon.desktop.ui.migration.MigrationSearchScreen(
                                                    current.id,
                                                    current.title,
                                                ),
                                            )
                                        },
                                    )
                                    if (mangaUrl != null) {
                                        DropdownMenuItem(
                                            text = { Text(MR.strings.desktop_ui_share_link.localized()) },
                                            onClick = {
                                                categoryMenuExpanded = false
                                                linkActions!!.share()
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(MR.strings.action_notes.localized()) },
                                        onClick = {
                                            notesFromSummary = false
                                            categoryMenuExpanded = false
                                            model.setShowNotesDialog(true)
                                        },
                                    )
                                }
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
                                    if (continueActionResumes(chapters, ch, state.syncedResumeChapterId)) {
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
                                scope.launch {
                                    val request = model.continueReadingRequest(manga!!, chapters) ?: return@launch
                                    navigator.push(
                                        DesktopReaderScreen(
                                            resumeSnapshot = request.resumeSnapshot,
                                            chapterTitle = request.chapterTitle,
                                            mangaId = request.mangaId,
                                            mangaTitle = request.mangaTitle,
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
                            TextButton(onClick = {
                                model.setDeleteConfirmChapter(null)
                            }) { Text(MR.strings.action_cancel.localized()) }
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
                            TextButton(onClick = {
                                model.setMarkAllReadConfirm(false)
                            }) { Text(MR.strings.action_cancel.localized()) }
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
                        onDismiss = {
                            categoryDialogMode = null
                            if (categoryManga.favorite) restoreCategoryMenuFocus = true
                        },
                        onEditCategories = {
                            categoryDialogMode = null
                            restoreCategoryMenuFocus = categoryManga.favorite
                            navigator.push(CategoryManagementScreen())
                        },
                        onSaved = {
                            scope.launch {
                                categorySnackbar.showSnackbar(MR.strings.desktop_categories_saved.localized())
                            }
                        },
                    )
                }

                val intervalManga = manga
                if (showFetchIntervalDialog && intervalManga != null) {
                    FetchIntervalDialog(
                        manga = intervalManga,
                        onDismiss = {
                            showFetchIntervalDialog = false
                            returnIntervalFocus = true
                        },
                        onConfirm = { interval ->
                            model.setFetchInterval(intervalManga.id, interval)
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
                            TextButton(onClick = {
                                model.setShowMigrateSourcePicker(false)
                            }) { Text(MR.strings.action_cancel.localized()) }
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
                            TextButton(onClick = {
                                model.setMigrateConfirmItem(null)
                            }) { Text(MR.strings.action_cancel.localized()) }
                        },
                    )
                }

                // Notes dialog
                if (showCoverViewer && manga != null) {
                    MangaCoverViewerDialog(
                        manga = manga,
                        coverModel = state.coverModel,
                        coverVersion = state.coverLastModified,
                        hasCustomCover = state.hasCustomCover,
                        busy = state.coverTask is mihon.domain.task.TaskState.Running,
                        feedback = state.coverFeedback,
                        onDismiss = {
                            showCoverViewer = false
                            returnCoverFocus = true
                        },
                        onReplace = { scope.launch { model.chooseCustomCover() } },
                        onDelete = { scope.launch { model.deleteCustomCover() } },
                    )
                }
                duplicateEntries?.let { entries ->
                    DuplicateMangaDialog(
                        entries = entries,
                        onDismiss = {
                            duplicateEntries = null
                            returnLibraryFocus = true
                        },
                        onView = { existing ->
                            duplicateEntries = null
                            navigator.push(MangaDetailScreen(existing.id))
                        },
                        onMigrate = { existing ->
                            duplicateEntries = null
                            navigator.push(
                                mihon.desktop.ui.migration.MigrationSearchScreen(existing.id, existing.title),
                            )
                        },
                        onAdd = {
                            val current = manga ?: return@DuplicateMangaDialog false
                            when (model.addToLibraryUsingDefault(current)) {
                                MangaDetailAddToLibraryResult.ADDED -> true
                                MangaDetailAddToLibraryResult.CHOOSE_CATEGORY -> {
                                    categoryDialogMode = MangaCategoryDialogMode.ADD_TO_LIBRARY
                                    true
                                }
                                MangaDetailAddToLibraryResult.FAILED -> false
                            }
                        },
                    )
                }
                removalSnapshot?.let { (fixedManga, fixedChapters) ->
                    RemoveFavoriteDialog(
                        manga = fixedManga,
                        onDismiss = {
                            removalSnapshot = null
                            returnLibraryFocus = true
                        },
                        onConfirm = { deleteFiles, membershipCompleted ->
                            model.removeFavorite(fixedManga, fixedChapters, deleteFiles, membershipCompleted)
                        },
                    )
                }
                val notedManga = manga
                if (showNotesDialog && notedManga != null) {
                    MangaNotesDialog(
                        manga = notedManga,
                        onDismiss = {
                            model.setShowNotesDialog(false)
                            if (!notesFromSummary) {
                                restoreCategoryMenuFocus =
                                    true
                            }
                        },
                        onSaved = {
                            scope.launch { categorySnackbar.showSnackbar(MR.strings.desktop_notes_saved.localized()) }
                        },
                    )
                }

                creatorIdentityError?.let { error ->
                    AlertDialog(
                        onDismissRequest = { creatorIdentityError = null },
                        title = { Text(MR.strings.desktop_ui_identity_action_failed.localized()) },
                        text = { Text(error) },
                        confirmButton = {
                            TextButton(onClick = { creatorIdentityError = null }) {
                                Text(MR.strings.action_ok.localized())
                            }
                        },
                    )
                }

                if (manga == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@Scaffold
                }

                val informationState = rememberLazyListState()
                val chapterState = rememberMangaDetailChapterState(
                    model,
                    chapterRows,
                    (if (layout.expanded) 0 else 2) + 1 + (if (sourceRefreshFailure != null) 1 else 0),
                )
                val informationItems: LazyListScope.() -> Unit = {
                    item {
                        MangaHeader(
                            manga = manga!!,
                            expanded = layout.expanded,
                            coverModel = state.coverModel,
                            coverLastModified = state.coverLastModified,
                            coverFeedback = state.coverFeedback,
                            coverFailed = state.coverTask is mihon.domain.task.TaskState.Failure,
                            onViewCover = { showCoverViewer = true },
                            coverFocus = coverFocus,
                            sourceName = source?.name,
                            sourceLanguage = (source as? eu.kanade.tachiyomi.source.CatalogueSource)?.lang,
                            onSourceSearch = {
                                navigator.push(mihon.desktop.ui.browse.SourceBrowseScreen(manga!!.source))
                            },
                            onTagSearch = { tag ->
                                navigator.push(
                                    mihon.desktop.ui.browse.SourceBrowseScreen(manga!!.source, initialQuery = tag),
                                )
                            },
                            onTagGlobalSearch = { tag -> navigator.push(GlobalSearchScreen(initialQuery = tag)) },
                            onTitleSearch = { navigator.push(GlobalSearchScreen(initialQuery = manga!!.title)) },
                            onNotes = {
                                notesFromSummary = true
                                model.setShowNotesDialog(true)
                            },
                            onTagCopy = { text ->
                                dependencies.notificationService.post(
                                    dependencies.shareService.copyText(text).toDesktopNotification(),
                                )
                            },
                            creatorMentions = model.creatorMentions(manga!!),
                            creatorIdentityLoading = creatorIdentityLoading,
                            onCreatorSearch = { mention ->
                                navigator.push(GlobalSearchScreen(initialQuery = mention.displayName))
                            },
                            onCreatorClick = { mention ->
                                if (creatorIdentityLoading) return@MangaHeader
                                scope.launch {
                                    creatorIdentityLoading = true
                                    runCatching {
                                        model.resolveCreatorMention(manga!!, mention)
                                    }.onSuccess { resolution ->
                                        when (resolution) {
                                            is CreatorMentionResolution.Resolved ->
                                                authorDetailScreenOrNull(mention.displayName, resolution.creatorId)
                                                    ?.let { navigator.push(it) }
                                            is CreatorMentionResolution.Ambiguous ->
                                                creatorIdentityError =
                                                    "作者资料已变化，请重试"
                                        }
                                    }.onFailure { creatorIdentityError = it.message ?: it::class.simpleName.orEmpty() }
                                    creatorIdentityLoading = false
                                }
                            },
                        )
                    }

                    item {
                        MangaDetailActionRow(
                            manga = manga!!,
                            mangaUrl = mangaUrl,
                            hasUnreadChapters = nextUnread != null,
                            libraryFocus = libraryFocus,
                            intervalFocus = intervalFocus,
                            onToggleLibrary = {
                                val current = manga ?: return@MangaDetailActionRow
                                if (current.favorite) {
                                    removalSnapshot =
                                        current to chapters.filter { model.isChapterDownloaded(current, it) }
                                } else {
                                    scope.launch {
                                        try {
                                            val entries = model.duplicates(current)
                                            if (entries.isNotEmpty()) {
                                                duplicateEntries = entries
                                            } else if (
                                                model.addToLibraryUsingDefault(current) ==
                                                MangaDetailAddToLibraryResult.CHOOSE_CATEGORY
                                            ) {
                                                categoryDialogMode = MangaCategoryDialogMode.ADD_TO_LIBRARY
                                            }
                                        } catch (canceled: kotlinx.coroutines.CancellationException) {
                                            throw canceled
                                        } catch (_: Exception) {
                                            categorySnackbar.showSnackbar(
                                                MR.strings.desktop_detail_save_failed.localized(),
                                            )
                                        }
                                    }
                                }
                            },
                            onEditFetchInterval = { showFetchIntervalDialog = true },
                            onTracking = {
                                onTracking(navigator, manga!!.title, chapters.size.toLong())
                            },
                            onOpenInBrowser = { mangaUrl?.let(::openExternalLink) },
                        )
                    }
                }
                val chapterItems: LazyListScope.() -> Unit = {
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
                            missingChapterCount = if (hideMissingChapterIndicators) {
                                0
                            } else {
                                displayedChapters.detailMissingChaptersCount()
                            },
                            chapterRows = chapterRows,
                            downloadQueue = downloadQueue,
                            manga = manga,
                            isChapterDownloaded = model::isChapterDownloaded,
                            isChapterSelected = { chapterId -> chapterId in selectionState.selectedIds },
                            isSelectionMode = selectionState.isActive,
                            hasActiveFilters =
                            manga?.hasActiveChapterFilters(downloadedOnly) == true || excludedScanlators.isNotEmpty(),
                            onOpenSettings = model::toggleFilterMenu,
                            onSelectChapter = selectionState::toggle,
                            onDownloadChapter = { chapter ->
                                manga?.let { model.enqueueDownloads(it, listOf(chapter)) }
                            },
                            onDeleteDownload = model::setDeleteConfirmChapter,
                            onCancelDownload = model::cancelChapterDownload,
                            onRetryDownload = model::retryChapterDownload,
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
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                    if (layout.expanded) {
                        val informationWidth = minOf(maxWidth / 2, 450.dp)
                        Row(Modifier.fillMaxSize()) {
                            LazyColumn(
                                modifier = Modifier.width(
                                    informationWidth,
                                ).fillMaxHeight().testTag("manga-detail-information"),
                                state = informationState,
                                contentPadding = PaddingValues(bottom = 16.dp),
                                content = informationItems,
                            )
                            Box(Modifier.weight(1f).fillMaxHeight().testTag("manga-detail-chapters")) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                                    state = chapterState,
                                    contentPadding = PaddingValues(bottom = 16.dp),
                                    content = chapterItems,
                                )
                                androidx.compose.foundation.VerticalScrollbar(
                                    adapter = androidx.compose.foundation.rememberScrollbarAdapter(chapterState),
                                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("manga-detail-content"),
                            state = chapterState,
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            informationItems()
                            chapterItems()
                        }
                    }
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

internal fun Manga.toSourceMangaForRefresh(): SManga = toSourceManga()
