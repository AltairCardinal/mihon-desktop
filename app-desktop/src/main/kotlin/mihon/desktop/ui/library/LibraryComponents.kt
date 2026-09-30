package mihon.desktop.ui.library

import tachiyomi.i18n.MR
import java.util.Locale

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwapCalls
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Surface
import mihon.desktop.ui.theme.LocalDesktopDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.desktop.domain.LibrarySearchFilter
import mihon.desktop.ui.library.pickRandomMangaId
import mihon.desktop.domain.SortMode
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.browse.GlobalSearchScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.projectLibraryBadges
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState

@Composable
internal fun LibraryToolbar(
    searchQuery: String?,
    onSearchChange: (String?) -> Unit,
    sortMode: SortMode,
    sortAscending: Boolean,
    onSortChange: (SortMode, Boolean) -> Unit,
    filter: LibraryFilter,
    availableTrackerIds: Set<Long>,
    trackerNamesById: Map<Long, String> = emptyMap(),
    onToggleFilter: (LibraryFilterField) -> Unit,
    onToggleTracking: (Long) -> Unit,
    isUpdating: Boolean,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    categories: List<Category> = emptyList(),
    selectedCategoryIndex: Int = 0,
    showCategoryTabs: Boolean = true,
    showCategoryItemCounts: Boolean = false,
    onCategoryChange: (Int) -> Unit = {},
    showIntervalCustomFilter: Boolean = false,
    showFilterMenu: Boolean = false,
    onShowFilterMenuChange: (Boolean) -> Unit = {},
    optionsFocusRequester: FocusRequester? = null,
    onSearchFocusChange: (Boolean) -> Unit = {},
    onPopupVisibilityChange: (Boolean) -> Unit = {},
    toolbarTitle: String = MR.strings.label_library.localized(),
    onRandomManga: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshAll: () -> Unit,
) {
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchQuery != null) {
        if (searchQuery != null) searchFocusRequester.requestFocus()
    }
    val localOptionsFocus = remember { FocusRequester() }
    val optionsFocus = optionsFocusRequester ?: localOptionsFocus
    val moreFocus = remember { FocusRequester() }
    val moreFirstFocus = remember { FocusRequester() }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    LaunchedEffect(showCategoryMenu, showMoreMenu) {
        onPopupVisibilityChange(showCategoryMenu || showMoreMenu)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            onSearchFocusChange(false)
            onPopupVisibilityChange(false)
        }
    }
    LaunchedEffect(showMoreMenu) {
        if (showMoreMenu) {
            androidx.compose.runtime.withFrameNanos { }
            moreFirstFocus.requestFocus()
        }
    }
    var restoreMoreFocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(restoreMoreFocus) {
        if (restoreMoreFocus > 0) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { moreFocus.requestFocus() }
        }
    }
    val dismissMore: () -> Unit = {
        showMoreMenu = false
        restoreMoreFocus++
    }
    val displayedFilter = if (filter.globalDownloadedOnly) filter.copy(downloaded = TriState.ENABLED_IS) else filter
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (searchQuery == null) {
                Box(Modifier.weight(1f)) {
                    Text(
                        toolbarTitle,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (!showCategoryTabs && categories.size > 1) {
                            Modifier.clickable { showCategoryMenu = true }
                        } else Modifier,
                    )
                    DropdownMenu(expanded = showCategoryMenu, onDismissRequest = { showCategoryMenu = false }) {
                        categories.forEachIndexed { index, category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = { showCategoryMenu = false; onCategoryChange(index) },
                            )
                        }
                    }
                }
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Default.Search, MR.strings.action_search.localized())
                }
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    placeholder = { Text(MR.strings.desktop_ui_search_library.localized()) },
                    modifier = Modifier.weight(1f).focusRequester(searchFocusRequester)
                        .onFocusChanged { onSearchFocusChange(it.isFocused) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(Icons.Default.Close, MR.strings.action_reset.localized())
                            }
                        }
                    } else {
                        null
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = onOpenGlobalSearch) {
                        Icon(Icons.Default.Search, MR.strings.action_global_search.localized())
                    }
                }
            }
            IconButton(
                onClick = { onShowFilterMenuChange(true) },
                modifier = Modifier.focusRequester(optionsFocus),
            ) { Icon(Icons.Default.FilterList, MR.strings.action_filter.localized()) }
            mihon.desktop.sync.DesktopLibrarySyncAction()
            Box {
                IconButton(
                    onClick = { showMoreMenu = true },
                    modifier = Modifier.focusRequester(moreFocus),
                ) { Icon(Icons.Default.MoreVert, MR.strings.action_menu.localized()) }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = dismissMore,
                    modifier = Modifier.onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                            dismissMore()
                            true
                        } else false
                    },
                ) {
                    DropdownMenuItem(
                        text = { Text(MR.strings.action_update_library.localized()) },
                        modifier = Modifier.focusRequester(moreFirstFocus),
                        onClick = {
                            dismissMore()
                            onRefreshAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(MR.strings.action_update_category.localized()) },
                        onClick = {
                            dismissMore()
                            onRefresh()
                        },
                        enabled = categories.isNotEmpty(),
                    )
                    DropdownMenuItem(
                        text = { Text(MR.strings.desktop_ui_random_manga.localized()) },
                        onClick = {
                            dismissMore()
                            onRandomManga()
                        },
                    )
                }
            }
        }
        val activeFilters = filterRows(displayedFilter).filter {
            it.second.second != TriState.DISABLED &&
                (it.second.first != LibraryFilterField.INTERVAL_CUSTOM || filter.skipOutsideReleasePeriod)
        }
        val tracking = availableTrackerIds.sorted().filter { filter.tracking[it].orDisabledForUi() != TriState.DISABLED }
        if (activeFilters.isNotEmpty() || tracking.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                activeFilters.forEach { (label, value) ->
                    FilterChip(
                        selected = true,
                        onClick = { onShowFilterMenuChange(true) },
                        label = { Text("$label: ${value.second.label()}") },
                    )
                }
                tracking.forEach { id ->
                    val title = if (availableTrackerIds.size == 1) MR.strings.action_filter_tracked.localized() else trackerNamesById[id].orEmpty()
                    FilterChip(
                        selected = true,
                        onClick = { onShowFilterMenuChange(true) },
                        label = { Text("$title: ${filter.tracking[id].orDisabledForUi().label()}") },
                    )
                }

            }
        }
    }
}

@Composable
internal fun LibraryFilterDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    filter: LibraryFilter,
    availableTrackerIds: Set<Long>,
    showIntervalCustomFilter: Boolean,
    onToggleFilter: (LibraryFilterField) -> Unit,
    onToggleTracking: (Long) -> Unit,
) {
    val displayedFilter = if (filter.globalDownloadedOnly) {
        filter.copy(downloaded = TriState.ENABLED_IS)
    } else {
        filter
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        filterRows(displayedFilter)
            .filter { it.second.first != LibraryFilterField.INTERVAL_CUSTOM || showIntervalCustomFilter }
            .forEach { (label, value) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            MR.strings.desktop_ui_filter_value.localized(
                                Locale.getDefault(),
                                label,
                                value.second.label(),
                            ),
                        )
                    },
                    onClick = { onToggleFilter(value.first) },
                    enabled = isFilterFieldEnabled(filter, value.first),
                )
            }
        availableTrackerIds.forEach { trackerId ->
            DropdownMenuItem(
                text = {
                    Text(
                        MR.strings.desktop_ui_tracker_filter.localized(
                            Locale.getDefault(),
                            trackerId,
                            filter.tracking[trackerId].orDisabledForUi().label(),
                        ),
                    )
                },
                onClick = { onToggleTracking(trackerId) },
            )
        }
    }
}

// ── Selection action bar ──────────────────────────────────────────────────────

@Composable
internal fun SelectionActionBar(
    actions: LibrarySelectionActions,
    canDownload: Boolean = true,
    canMigrate: Boolean = true,
    onSetCategories: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onPopupVisibilityChange: (Boolean) -> Unit = {},
    onExitSelection: () -> Unit = {},
    categoryFocusRequester: FocusRequester? = null,
    removalFocusRequester: FocusRequester? = null,
) {
    var downloadExpanded by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    val downloadFocus = remember { FocusRequester() }
    val localMoreFocus = remember { FocusRequester() }
    val moreFocus = removalFocusRequester ?: localMoreFocus
    val downloadFirstFocus = remember { FocusRequester() }
    val moreFirstFocus = remember { FocusRequester() }
    var restoreDownloadFocus by remember { mutableIntStateOf(0) }
    var restoreMoreFocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(downloadExpanded, moreExpanded) {
        onPopupVisibilityChange(downloadExpanded || moreExpanded)
        if (downloadExpanded || moreExpanded) {
            androidx.compose.runtime.withFrameNanos { }
            if (downloadExpanded) downloadFirstFocus.requestFocus() else moreFirstFocus.requestFocus()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onPopupVisibilityChange(false) }
    }
    LaunchedEffect(restoreDownloadFocus) {
        if (restoreDownloadFocus > 0) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { downloadFocus.requestFocus() }
        }
    }
    LaunchedEffect(restoreMoreFocus) {
        if (restoreMoreFocus > 0) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { moreFocus.requestFocus() }
        }
    }
    val dismissDownload: () -> Unit = {
        downloadExpanded = false
        restoreDownloadFocus++
    }
    val dismissMore: () -> Unit = {
        moreExpanded = false
        restoreMoreFocus++
    }
    Surface(
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && !downloadExpanded && !moreExpanded) {
                onExitSelection()
                true
            } else false
        },
        shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
            IconButton(
                onClick = onSetCategories,
                modifier = Modifier
                    .weight(1f)
                    .then(categoryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Label, MR.strings.action_move_category.localized())
            }
            IconButton(onClick = onMarkRead, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.DoneAll, MR.strings.action_mark_as_read.localized())
            }
            IconButton(onClick = onMarkUnread, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.RemoveDone, MR.strings.action_mark_as_unread.localized())
            }
            if (canDownload) {
                Box(Modifier.weight(1f)) {
                    IconButton(
                        onClick = { downloadExpanded = true },
                        modifier = Modifier.fillMaxWidth().focusRequester(downloadFocus),
                    ) {
                        Icon(Icons.Outlined.Download, MR.strings.action_download.localized())
                    }
                    DropdownMenu(
                        expanded = downloadExpanded,
                        onDismissRequest = dismissDownload,
                        modifier = Modifier.onPreviewKeyEvent {
                            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                                dismissDownload()
                                true
                            } else false
                        },
                    ) {
                        listOf(
                            MangaDetailDownloadAction.NEXT_1_CHAPTER to
                                MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 1),
                            MangaDetailDownloadAction.NEXT_5_CHAPTERS to
                                MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 5),
                            MangaDetailDownloadAction.NEXT_10_CHAPTERS to
                                MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 10),
                            MangaDetailDownloadAction.NEXT_25_CHAPTERS to
                                MR.strings.desktop_ui_next_chapters.localized(Locale.getDefault(), 25),
                            MangaDetailDownloadAction.UNREAD_CHAPTERS to MR.strings.desktop_ui_all_unread_chapters.localized(),
                            MangaDetailDownloadAction.BOOKMARKED_CHAPTERS to MR.strings.desktop_ui_bookmarked_chapters.localized(),
                        ).forEachIndexed { index, (action, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                modifier = if (index == 0) Modifier.focusRequester(downloadFirstFocus) else Modifier,
                                onClick = {
                                    dismissDownload()
                                    actions.download(action)
                                },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    IconButton(
                        onClick = { moreExpanded = true },
                        modifier = Modifier.fillMaxWidth().focusRequester(moreFocus),
                    ) {
                        Icon(Icons.Outlined.MoreVert, MR.strings.action_menu.localized())
                    }
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = dismissMore,
                        modifier = Modifier.onPreviewKeyEvent {
                            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                                dismissMore()
                                true
                            } else false
                        },
                    ) {
                        DropdownMenuItem(
                            text = { Text(MR.strings.migrate.localized()) },
                            enabled = canMigrate,
                            modifier = Modifier.focusRequester(moreFirstFocus),
                            onClick = {
                                dismissMore()
                                actions.migrate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(MR.strings.action_delete.localized()) },
                            onClick = {
                                dismissMore()
                                onRemoveFromLibrary()
                            },
                        )
                    }
                }
            } else {
                IconButton(onClick = actions.migrate, enabled = canMigrate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.SwapCalls, MR.strings.migrate.localized())
                }
                IconButton(
                    onClick = onRemoveFromLibrary,
                    modifier = Modifier
                    .weight(1f)
                    .then(removalFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
                ) {
                    Icon(Icons.Outlined.Delete, MR.strings.action_delete.localized())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
) {
    TopAppBar(
        modifier = Modifier.testTag("library-selection-top-bar"),
        title = { Text(selectedCount.toString()) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = MR.strings.desktop_ui_clear_selection.localized())
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = MR.strings.action_select_all.localized())
            }
            IconButton(onClick = onInvertSelection) {
                Icon(Icons.Outlined.FlipToBack, contentDescription = MR.strings.desktop_ui_invert_selection.localized())
            }
        },
    )
}

// ── Grid view ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryGrid(
    items: List<LibraryManga>,
    minCardWidth: androidx.compose.ui.unit.Dp,
    comfortable: Boolean = false,
    coverOnly: Boolean = false,
    portraitColumns: Int = 0,
    landscapeColumns: Int = 0,
    selectionState: LibrarySelectionState,
    downloadedMangaIds: Set<Long> = emptySet(),
    downloadCountsByManga: Map<Long, Long> = emptyMap(),
    localMangaIds: Set<Long> = emptySet(),
    sourceLanguagesByManga: Map<Long, String> = emptyMap(),
    trackerMeansByManga: Map<Long, Double> = emptyMap(),
    showTrackerScore: Boolean = false,
    showDownloadBadge: Boolean = true,
    showUnreadBadge: Boolean = true,
    showLocalBadge: Boolean = true,
    showLanguageBadge: Boolean = false,
    showContinueReadingButton: Boolean = true,
    syncedResumeMangaIds: Set<Long> = emptySet(),
    continueReadingMangaIds: Set<Long>? = null,
    scrollState: LazyGridState = rememberLazyGridState(),
    resolveCoverModel: (Long, String?) -> String? = { _, url -> url },
    onContextMenu: (LibraryManga) -> Unit,
    onItemClick: (LibraryManga, LibraryClickModifiers) -> Unit,
    onItemLongClick: (LibraryManga) -> Unit,
    onContinueReading: (LibraryManga) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth < maxHeight) portraitColumns else landscapeColumns
        LazyVerticalGrid(
            state = scrollState,
            columns = columns.takeIf { it > 0 }?.let(GridCells::Fixed)
                ?: GridCells.Adaptive(minSize = minCardWidth),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { item ->
                MangaCoverCard(
                    item = item,
                    comfortable = comfortable,
                    coverOnly = coverOnly,
                    isSelected = selectionState.isSelected(item.manga.id),
                    isDownloaded = item.id in downloadedMangaIds,
                    downloadCount = downloadCountsByManga[item.id] ?: if (item.id in downloadedMangaIds) 1L else 0L,
                    isLocal = item.id in localMangaIds,
                    sourceLanguage = sourceLanguagesByManga[item.id].orEmpty(),
                    trackerScore = trackerMeansByManga[item.id],
                    showTrackerScore = showTrackerScore,
                    showDownloadBadge = showDownloadBadge,
                    showUnreadBadge = showUnreadBadge,
                    showLocalBadge = showLocalBadge,
                    showLanguageBadge = showLanguageBadge,
                    showContinueReadingButton = showContinueReadingButton,
                    syncedResumeMangaIds = syncedResumeMangaIds,
                    canContinueReading = continueReadingMangaIds?.contains(item.id) ?: (item.unreadCount > 0),
                    coverModel = resolveCoverModel(item.id, item.manga.thumbnailUrl),
                    onClick = { shiftPressed -> onItemClick(item, shiftPressed) },
                    onLongClick = { onItemLongClick(item) },
                    onContinueReading = { onContinueReading(item) },
                    onContextMenu = { onContextMenu(item) },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

// ── List view ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun LibraryList(
    items: List<LibraryManga>,
    selectionState: LibrarySelectionState,
    downloadedMangaIds: Set<Long> = emptySet(),
    downloadCountsByManga: Map<Long, Long> = emptyMap(),
    localMangaIds: Set<Long> = emptySet(),
    sourceLanguagesByManga: Map<Long, String> = emptyMap(),
    trackerMeansByManga: Map<Long, Double> = emptyMap(),
    showTrackerScore: Boolean = false,
    showDownloadBadge: Boolean = true,
    showUnreadBadge: Boolean = true,
    showLocalBadge: Boolean = true,
    showLanguageBadge: Boolean = false,
    showContinueReadingButton: Boolean = true,
    syncedResumeMangaIds: Set<Long> = emptySet(),
    continueReadingMangaIds: Set<Long>? = null,
    scrollState: LazyListState = rememberLazyListState(),
    resolveCoverModel: (Long, String?) -> String? = { _, url -> url },
    onContextMenu: (LibraryManga) -> Unit,
    onItemClick: (LibraryManga, LibraryClickModifiers) -> Unit,
    onItemLongClick: (LibraryManga) -> Unit,
    onContinueReading: (LibraryManga) -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { item ->
                val isSelected = selectionState.isSelected(item.manga.id)
                val downloadCount = downloadCountsByManga[item.id] ?: if (item.id in downloadedMangaIds) 1L else 0L
                val badges = projectLibraryBadges(
                    { downloadCount }, { item.unreadCount }, { item.id in localMangaIds },
                    { sourceLanguagesByManga[item.id].orEmpty() }, showDownloadBadge, showUnreadBadge,
                    showLocalBadge, showLanguageBadge,
                )
                val showLanguageIndicator = badges.sourceLanguage.isNotBlank()
                val showTrailingIndicators =
                    badges.unreadCount > 0L || badges.downloadCount > 0L || badges.isLocal ||
                        showLanguageIndicator
                val showContinueReading = showContinueReadingButton &&
                    (continueReadingMangaIds?.contains(item.id) ?: (item.unreadCount > 0))
                val selectionAlpha = if (LocalDesktopDarkTheme.current) 0.16f else 0.22f
                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.secondary.copy(alpha = selectionAlpha)
                        } else Color.Transparent,
                    ),
                    headlineContent = {
                        Text(
                            item.manga.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Box {
                            AsyncImage(
                                model = rememberMangaCoverRequestState(
                                    item.id, item.manga.source, resolveCoverModel(item.id, item.manga.thumbnailUrl),
                                    item.manga.coverLastModified,
                                ).request,
                                contentDescription = item.manga.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp),
                            )

                        }
                    },
                    supportingContent = if (badges.unreadCount > 0L || showTrackerScore) {
                        {
                            Column {
                                if (badges.unreadCount > 0L) {
                                    Text(MR.strings.desktop_ui_unread_count.localized(Locale.getDefault(), badges.unreadCount))
                                }
                                if (showTrackerScore) Text(trackerScoreLabel(trackerMeansByManga[item.id]))
                            }
                        }
                    } else null,
                    trailingContent = if (showContinueReading || showTrailingIndicators) {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (badges.unreadCount > 0L) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = MaterialTheme.colorScheme.onSecondary,
                                    ) { Text(badges.unreadCount.toString()) }
                                }
                                if (badges.downloadCount > 0L) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                        contentColor = MaterialTheme.colorScheme.onTertiary,
                                        modifier = Modifier.semantics {
                                            contentDescription = MR.strings.label_downloaded.localized()
                                        },
                                    ) { Text(badges.downloadCount.toString()) }
                                }
                                if (badges.isLocal || showLanguageIndicator) {
                                    LibraryLanguageBadge(
                                        isLocal = badges.isLocal,
                                        sourceLanguage = badges.sourceLanguage,
                                    )
                                }
                                if (showContinueReading) {
                                    IconButton(onClick = { onContinueReading(item) }) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = MR.strings.desktop_ui_continue_reading.localized(),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .shiftAwareCombinedClickable(
                            onClick = { onItemClick(item, it) },
                            onLongClick = { onItemLongClick(item) },
                        )
                        .semantics { selected = isSelected }
                        .pointerInput(item.manga.id) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press &&
                                        event.button == PointerButton.Secondary
                                    ) {
                                        onContextMenu(item)
                                    }
                                }
                            }
                        },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

// ── Card ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun MangaCoverCard(
    item: LibraryManga,
    comfortable: Boolean,
    coverOnly: Boolean = false,
    isSelected: Boolean,
    isDownloaded: Boolean = false,
    downloadCount: Long = if (isDownloaded) 1L else 0L,
    isLocal: Boolean = false,
    sourceLanguage: String = "",
    trackerScore: Double? = null,
    showTrackerScore: Boolean = false,
    showDownloadBadge: Boolean = true,
    showUnreadBadge: Boolean = true,
    showLocalBadge: Boolean = true,
    showLanguageBadge: Boolean = false,
    showContinueReadingButton: Boolean = true,
    syncedResumeMangaIds: Set<Long> = emptySet(),
    canContinueReading: Boolean = item.unreadCount > 0,
    coverModel: String? = item.manga.thumbnailUrl,
    onClick: (LibraryClickModifiers) -> Unit,
    onLongClick: () -> Unit,
    onContinueReading: () -> Unit,
    onContextMenu: () -> Unit,
) {
    val badges = projectLibraryBadges(
        { downloadCount }, { item.unreadCount }, { isLocal }, { sourceLanguage },
        showDownloadBadge, showUnreadBadge, showLocalBadge, showLanguageBadge,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shiftAwareCombinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { selected = isSelected }
            .pointerInput(item.manga.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press &&
                            event.button == PointerButton.Secondary
                        ) {
                            onContextMenu()
                        }
                    }
                }
            },
        shape = MaterialTheme.shapes.small,
        color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(4.dp)) {
            Box {
                AsyncImage(
                    model = rememberMangaCoverRequestState(
                        item.id, item.manga.source, coverModel, item.manga.coverLastModified,
                    ).request,
                    contentDescription = item.manga.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .alpha(if (isSelected) 0.76f else 1f),
                )
                if (showTrackerScore) {
                    Text(
                        trackerScoreLabel(trackerScore),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                // Gradient overlay (only for compact grid with title inside)
                if (!comfortable && !coverOnly) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                    startY = 0.4f,
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp),
                    ) {
                        Text(
                            text = item.manga.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Unread badge
                if (badges.unreadCount > 0L) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ) {
                        Text(
                            text = badges.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // Downloaded badge
                if (badges.downloadCount > 0L) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                        containerColor = MaterialTheme.colorScheme.tertiary,
                    ) {
                        Text(
                            badges.downloadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.semantics {
                                contentDescription = MR.strings.label_downloaded.localized()
                            },
                        )
                    }
                }

                // Continue reading FAB overlay (bottom-start, visible on hover via always-visible small icon)
                if (showContinueReadingButton && canContinueReading) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                shape = androidx.compose.foundation.shape.CircleShape,
                            )
                            .clickable { onContinueReading() }
                            .padding(4.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = MR.strings.desktop_ui_continue_reading.localized(),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                if (badges.isLocal || badges.sourceLanguage.isNotBlank()) {
                    LibraryLanguageBadge(
                        isLocal = badges.isLocal,
                        sourceLanguage = badges.sourceLanguage,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp),
                    )
                } else if (showLocalBadge && isLocal) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp),
                        containerColor = MaterialTheme.colorScheme.secondary,
                    ) {
                        Text(
                            MR.strings.action_display_local_badge.localized(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }


            }

            // Title below cover in comfortable mode
            if (comfortable && !coverOnly) {
                Text(
                    text = item.manga.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryLanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
    modifier: Modifier = Modifier,
) {
    if (isLocal) {
        Badge(modifier = modifier, containerColor = MaterialTheme.colorScheme.tertiary) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = MR.strings.action_display_local_badge.localized(),
                modifier = Modifier.size(12.dp),
            )
        }
    } else if (sourceLanguage.isNotBlank()) {
        Badge(modifier = modifier, containerColor = MaterialTheme.colorScheme.tertiary) {
            Text(sourceLanguage.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun EmptyLibrary(onGettingStarted: () -> Unit = {}) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = MR.strings.information_empty_library.localized(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = MR.strings.desktop_ui_add_manga_from_browse_to_get_started.localized(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onGettingStarted) {
                Text(MR.strings.getting_started_guide.localized())
            }
        }
    }
}

// ── Manga right-click context menu ────────────────────────────────────────────

@Composable
internal fun MangaContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onMarkAllRead: () -> Unit,
    onMarkAllUnread: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onDownload: () -> Unit,
    canDownload: Boolean = true,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(MR.strings.desktop_ui_mark_all_read.localized()) },
            onClick = onMarkAllRead,
        )
        DropdownMenuItem(
            text = { Text(MR.strings.desktop_ui_mark_all_unread.localized()) },
            onClick = onMarkAllUnread,
        )
        if (canDownload) {
            DropdownMenuItem(
                text = { Text(MR.strings.desktop_ui_download_next_unread.localized()) },
                onClick = onDownload,
            )
        }
        DropdownMenuItem(
            text = { Text(MR.strings.remove_from_library.localized(), color = MaterialTheme.colorScheme.error) },
            onClick = onRemoveFromLibrary,
        )
    }
}

@Composable
internal fun LibraryRemovalDialog(
    items: List<LibraryManga>,
    onDismiss: () -> Unit,
    onConfirm: (removeFromLibrary: Boolean, deleteDownloads: Boolean) -> Unit,
) {
    var removeFromLibrary by remember(items) { mutableStateOf(false) }
    var deleteDownloads by remember(items) { mutableStateOf(false) }
    val policy = libraryRemovalPolicy(items)
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        cancelFocus.requestFocus()
    }

    AlertDialog(
        modifier = Modifier.categoryDialogEscape(true, onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.action_remove.localized()) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(MR.strings.manga_from_library.localized(), modifier = Modifier.weight(1f))
                    Checkbox(
                        checked = removeFromLibrary,
                        onCheckedChange = { removeFromLibrary = it },
                    )
                }
                if (policy.canDeleteDownloads) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(MR.strings.downloaded_chapters.localized(), modifier = Modifier.weight(1f))
                        Checkbox(
                            checked = deleteDownloads,
                            onCheckedChange = { deleteDownloads = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = policy.canConfirm(removeFromLibrary, deleteDownloads),
                onClick = { onConfirm(removeFromLibrary, deleteDownloads) },
            ) { Text(MR.strings.action_ok.localized()) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
}

// ── Batch category assignment dialog ─────────────────────────────────────────

@Composable
internal fun BatchCategoryDialog(
    categories: List<Category>,
    selectedMangaIds: List<Long>,
    loadCategoryIds: suspend (Long) -> Set<Long>,
    onConfirm: (LibraryCategoryDelta) -> Unit,
    onDismiss: () -> Unit,
    onEditCategories: () -> Unit = {},
) {
    val selectableCategories = remember { categories.filterNot(Category::isSystemCategory) }
    val targetMangaIds = remember { selectedMangaIds.toList() }
    var currentCategoryIdsByManga by remember { mutableStateOf<Map<Long, Set<Long>>?>(null) }
    var desiredStates by remember { mutableStateOf<List<CheckboxState<Category>>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        cancelFocus.requestFocus()
    }

    LaunchedEffect(Unit) {
        try {
            val current = targetMangaIds.associateWith { loadCategoryIds(it) }
            currentCategoryIdsByManga = current
            val initial = initialLibraryCategorySelections(selectableCategories, current)
            desiredStates = selectableCategories.map { category ->
                when (initial.getValue(category.id)) {
                    LibraryCategorySelection.NONE -> CheckboxState.State.None(category)
                    LibraryCategorySelection.ALL -> CheckboxState.State.Checked(category)
                    LibraryCategorySelection.MIXED -> CheckboxState.TriState.Exclude(category)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadFailed = true
        }
    }

    AlertDialog(
        modifier = Modifier.categoryDialogEscape(true, onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.action_move_category.localized()) },
        text = {
            when {
                loadFailed -> Text(MR.strings.internal_error.localized())
                currentCategoryIdsByManga == null -> CircularProgressIndicator()
                selectableCategories.isEmpty() -> {
                    Text(MR.strings.desktop_ui_no_categories_create_categories_first.localized())
                }
                else -> Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    desiredStates.forEach { checkbox ->
                        val checkboxState = when (checkbox) {
                            is CheckboxState.State.Checked,
                            is CheckboxState.TriState.Include,
                            -> ToggleableState.On
                            is CheckboxState.State.None,
                            is CheckboxState.TriState.None,
                            -> ToggleableState.Off
                            is CheckboxState.TriState.Exclude -> ToggleableState.Indeterminate
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .triStateToggleable(
                                    state = checkboxState,
                                    role = Role.Checkbox,
                                    onClick = {
                                        desiredStates = desiredStates.map { current ->
                                            if (current.value.id == checkbox.value.id) current.next() else current
                                        }
                                    },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TriStateCheckbox(state = checkboxState, onClick = null)
                            Text(checkbox.value.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = currentCategoryIdsByManga != null && !loadFailed && selectableCategories.isNotEmpty(),
                onClick = {
                    val add = desiredStates.filter {
                        it is CheckboxState.State.Checked || it is CheckboxState.TriState.Include
                    }.mapTo(mutableSetOf()) { it.value.id }
                    val remove = desiredStates.filter {
                        it is CheckboxState.State.None || it is CheckboxState.TriState.None
                    }.mapTo(mutableSetOf()) { it.value.id }
                    onConfirm(LibraryCategoryDelta(add, remove))
                },
            ) { Text(MR.strings.action_ok.localized()) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDismiss()
                    onEditCategories()
                }) { Text(MR.strings.action_edit_categories.localized()) }
                TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                    Text(MR.strings.action_cancel.localized())
                }
            }
        },
    )
}

private fun trackerScoreLabel(score: Double?): String =
    score?.let { String.format(Locale.getDefault(), "%.1f / 10", it) } ?: MR.strings.desktop_library_unrated.localized()
