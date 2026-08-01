package mihon.desktop.ui.library

import tachiyomi.i18n.MR
import java.util.Locale

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.launch
import mihon.desktop.domain.LibrarySearchFilter
import mihon.desktop.ui.library.pickRandomMangaId
import mihon.desktop.domain.SortMode
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.ui.reader.DesktopReaderScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.core.common.preference.TriState

@Composable
internal fun LibraryToolbar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortMode: SortMode,
    sortAscending: Boolean,
    onSortChange: (SortMode, Boolean) -> Unit,
    filter: LibraryFilter,
    availableTrackerIds: Set<Long>,
    onToggleFilter: (LibraryFilterField) -> Unit,
    onToggleTracking: (Long) -> Unit,
    onToggleGlobalDownloadedOnly: () -> Unit,
    onToggleSkipOutsideReleasePeriod: () -> Unit,
    isUpdating: Boolean,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    onManageCategories: () -> Unit,
    onRandomManga: () -> Unit,
    onRefresh: () -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showDisplayMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text(MR.strings.desktop_ui_search_library.localized()) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = MR.strings.action_search.localized()) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            // Display mode
            Box {
                IconButton(onClick = { showDisplayMenu = true }) {
                    val icon = when (displayMode) {
                        LibraryDisplayMode.COMPACT_GRID -> Icons.Default.GridOn
                        LibraryDisplayMode.COMFORTABLE_GRID -> Icons.Default.GridView
                        LibraryDisplayMode.LIST -> Icons.AutoMirrored.Filled.List
                    }
                    Icon(icon, contentDescription = MR.strings.action_display_mode.localized())
                }
                DropdownMenu(expanded = showDisplayMenu, onDismissRequest = { showDisplayMenu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${if (displayMode == LibraryDisplayMode.COMPACT_GRID) "✓ " else "  "}" +
                                    MR.strings.desktop_ui_compact_grid.localized(),
                            )
                        },
                        onClick = { onDisplayModeChange(LibraryDisplayMode.COMPACT_GRID); showDisplayMenu = false },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${if (displayMode == LibraryDisplayMode.COMFORTABLE_GRID) "✓ " else "  "}" +
                                    MR.strings.desktop_ui_comfortable_grid.localized(),
                            )
                        },
                        onClick = { onDisplayModeChange(LibraryDisplayMode.COMFORTABLE_GRID); showDisplayMenu = false },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${if (displayMode == LibraryDisplayMode.LIST) "✓ " else "  "}" +
                                    MR.strings.desktop_ui_list.localized(),
                            )
                        },
                        onClick = { onDisplayModeChange(LibraryDisplayMode.LIST); showDisplayMenu = false },
                    )
                }
            }

            // Sort
            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.Default.SortByAlpha, contentDescription = MR.strings.action_sort.localized())
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                val label = when (mode) {
                                    SortMode.TITLE -> MR.strings.title.localized()
                                    SortMode.UNREAD_COUNT -> MR.strings.action_sort_unread_count.localized()
                                    SortMode.DATE_ADDED -> MR.strings.action_sort_date_added.localized()
                                    SortMode.LAST_READ -> MR.strings.action_sort_last_read.localized()
                                }
                                val arrow = if (mode == sortMode) (if (sortAscending) " ↑" else " ↓") else ""
                                Text("$label$arrow")
                            },
                            onClick = {
                                if (mode == sortMode) onSortChange(mode, !sortAscending) else onSortChange(mode, true)
                                showSortMenu = false
                            },
                        )
                    }
                }
            }

            // Filter
            Box {
                IconButton(onClick = { showFilterMenu = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = MR.strings.action_filter.localized())
                }
                DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                    filterRows(filter).forEach { (label, value) ->
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
                    DropdownMenuItem(
                        text = {
                            Text(
                                MR.strings.desktop_ui_global_downloaded_only.localized(
                                    Locale.getDefault(),
                                    filter.globalDownloadedOnly.onOff(),
                                ),
                            )
                        },
                        onClick = onToggleGlobalDownloadedOnly,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                MR.strings.desktop_ui_custom_interval_filter.localized(
                                    Locale.getDefault(),
                                    filter.skipOutsideReleasePeriod.onOff(),
                                ),
                            )
                        },
                        onClick = onToggleSkipOutsideReleasePeriod,
                    )
                }
            }

            IconButton(onClick = onRandomManga) {
                Icon(Icons.Default.Shuffle, contentDescription = MR.strings.desktop_ui_random_manga.localized())
            }

            IconButton(onClick = onManageCategories) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = MR.strings.desktop_ui_manage_categories_eceede45.localized())
            }

            if (isUpdating) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = MR.strings.check_for_updates.localized())
                }
            }
        }

        val activeFilters = filterRows(filter).filter { it.second.second != TriState.DISABLED }
        if (activeFilters.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                activeFilters.forEach { (label, value) ->
                    FilterChip(
                        selected = true,
                        onClick = {
                            onToggleFilter(value.first)
                        },
                        label = {
                            Text(
                                MR.strings.desktop_ui_filter_value.localized(
                                    Locale.getDefault(),
                                    label,
                                    value.second.label(),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

// ── Selection action bar ──────────────────────────────────────────────────────

@Composable
internal fun SelectionActionBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    actions: LibrarySelectionActions,
    canMigrate: Boolean = true,
    onSetCategories: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
) {
    var downloadExpanded by remember { mutableStateOf(false) }
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = MR.strings.desktop_ui_clear_selection.localized())
        }
        Text(
            text = MR.strings.desktop_ui_selected_count.localized(Locale.getDefault(), selectedCount),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Default.SelectAll, contentDescription = MR.strings.action_select_all.localized())
        }
        IconButton(onClick = onInvertSelection) {
            Icon(Icons.Default.SelectAll, contentDescription = MR.strings.desktop_ui_invert_selection.localized())
        }
        Box {
            TextButton(onClick = { downloadExpanded = true }) { Text(MR.strings.action_download.localized()) }
            DropdownMenu(expanded = downloadExpanded, onDismissRequest = { downloadExpanded = false }) {
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
                ).forEach { (action, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            downloadExpanded = false
                            actions.download(action)
                        },
                    )
                }
            }
        }
        TextButton(onClick = actions.migrate, enabled = canMigrate) { Text(MR.strings.action_migrate.localized()) }
        TextButton(onClick = onSetCategories) { Text(MR.strings.categories.localized()) }
        TextButton(onClick = onMarkRead) { Text(MR.strings.desktop_ui_mark_read.localized()) }
        TextButton(onClick = onMarkUnread) { Text(MR.strings.desktop_ui_mark_unread.localized()) }
        TextButton(onClick = onRemoveFromLibrary) {
            Text(MR.strings.action_remove.localized(), color = MaterialTheme.colorScheme.error)
        }
    }
}

// ── Grid view ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryGrid(
    items: List<LibraryManga>,
    minCardWidth: androidx.compose.ui.unit.Dp,
    comfortable: Boolean = false,
    selectionState: LibrarySelectionState,
    downloadedMangaIds: Set<Long> = emptySet(),
    onContextMenu: (LibraryManga) -> Unit,
    onItemClick: (LibraryManga, shiftPressed: Boolean) -> Unit,
    onItemLongClick: (LibraryManga) -> Unit,
    onContinueReading: (LibraryManga) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minCardWidth),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            MangaCoverCard(
                item = item,
                comfortable = comfortable,
                isSelected = selectionState.isSelected(item.manga.id),
                isDownloaded = item.id in downloadedMangaIds,
                onClick = { shiftPressed -> onItemClick(item, shiftPressed) },
                onLongClick = { onItemLongClick(item) },
                onContinueReading = { onContinueReading(item) },
                onContextMenu = { onContextMenu(item) },
            )
        }
    }
}

// ── List view ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun LibraryList(
    items: List<LibraryManga>,
    selectionState: LibrarySelectionState,
    onContextMenu: (LibraryManga) -> Unit,
    onItemClick: (LibraryManga, shiftPressed: Boolean) -> Unit,
    onItemLongClick: (LibraryManga) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            val isSelected = selectionState.isSelected(item.manga.id)
            ListItem(
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
                            model = mihon.desktop.image.desktopSourceImageModel(
                                item.manga.thumbnailUrl,
                                item.manga.source,
                            ),
                            contentDescription = item.manga.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp),
                        )
                        if (isSelected) {
                            Box(
                                Modifier.size(48.dp).background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                },
                supportingContent = if (item.unreadCount > 0L) {
                    {
                        Text(
                            MR.strings.desktop_ui_unread_count.localized(
                                Locale.getDefault(),
                                item.unreadCount,
                            ),
                        )
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
                    }
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else Color.Transparent,
                    ),
            )
        }
    }
}

// ── Card ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun MangaCoverCard(
    item: LibraryManga,
    comfortable: Boolean,
    isSelected: Boolean,
    isDownloaded: Boolean = false,
    onClick: (shiftPressed: Boolean) -> Unit,
    onLongClick: () -> Unit,
    onContinueReading: () -> Unit,
    onContextMenu: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shiftAwareCombinedClickable(onClick = onClick, onLongClick = onLongClick)
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
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column {
            Box {
                AsyncImage(
                    model = mihon.desktop.image.desktopSourceImageModel(
                        item.manga.thumbnailUrl,
                        item.manga.source,
                    ),
                    contentDescription = item.manga.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f),
                )
                // Gradient overlay (only for compact grid with title inside)
                if (!comfortable) {
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
                if (item.unreadCount > 0L) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = item.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // Downloaded badge
                if (isDownloaded) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                        containerColor = MaterialTheme.colorScheme.tertiary,
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = MR.strings.label_downloaded.localized(),
                            tint = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }

                // Continue reading FAB overlay (bottom-start, visible on hover via always-visible small icon)
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

                // Selection indicator
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = MR.strings.selected.localized(),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }

            // Title below cover in comfortable mode
            if (comfortable) {
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
internal fun EmptyLibrary() {
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
        DropdownMenuItem(
            text = { Text(MR.strings.desktop_ui_download_next_unread.localized()) },
            onClick = onDownload,
        )
        DropdownMenuItem(
            text = { Text(MR.strings.remove_from_library.localized(), color = MaterialTheme.colorScheme.error) },
            onClick = onRemoveFromLibrary,
        )
    }
}

// ── Batch category assignment dialog ─────────────────────────────────────────

@Composable
internal fun BatchCategoryDialog(
    categories: List<Category>,
    selectedMangaIds: List<Long>,
    loadCategoryIds: suspend (Long) -> Set<Long>,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Load current categories for the first selected manga as initial state
    var checkedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedMangaIds) {
        if (selectedMangaIds.isNotEmpty()) {
            checkedIds = loadCategoryIds(selectedMangaIds.first())
        }
        loaded = true
    }

    if (!loaded) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.action_move_category.localized()) },
        text = {
            if (categories.isEmpty()) {
                Text(MR.strings.desktop_ui_no_categories_create_categories_first.localized())
            } else {
                Column {
                    categories.forEach { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { checkedIds = if (cat.id in checkedIds) checkedIds - cat.id else checkedIds + cat.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = cat.id in checkedIds,
                                onCheckedChange = { checked ->
                                    checkedIds = if (checked) checkedIds + cat.id else checkedIds - cat.id
                                },
                            )
                            Text(cat.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checkedIds.toList()) }) { Text(MR.strings.action_ok.localized()) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(MR.strings.action_cancel.localized()) }
        },
    )
}
