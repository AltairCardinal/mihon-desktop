package mihon.desktop.ui.library

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

@Composable
internal fun LibraryToolbar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortMode: SortMode,
    sortAscending: Boolean,
    onSortChange: (SortMode, Boolean) -> Unit,
    filterUnread: Boolean,
    filterStarted: Boolean,
    filterCompleted: Boolean,
    filterDownloaded: Boolean,
    onFilterChange: (unread: Boolean, started: Boolean, completed: Boolean, downloaded: Boolean) -> Unit,
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
                placeholder = { Text("Search library...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
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
                    Icon(icon, contentDescription = "Display mode")
                }
                DropdownMenu(expanded = showDisplayMenu, onDismissRequest = { showDisplayMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (displayMode == LibraryDisplayMode.COMPACT_GRID) "✓ Compact Grid" else "  Compact Grid") },
                        onClick = { onDisplayModeChange(LibraryDisplayMode.COMPACT_GRID); showDisplayMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (displayMode == LibraryDisplayMode.COMFORTABLE_GRID) "✓ Comfortable Grid" else "  Comfortable Grid") },
                        onClick = { onDisplayModeChange(LibraryDisplayMode.COMFORTABLE_GRID); showDisplayMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (displayMode == LibraryDisplayMode.LIST) "✓ List" else "  List") },
                        onClick = { onDisplayModeChange(LibraryDisplayMode.LIST); showDisplayMenu = false },
                    )
                }
            }

            // Sort
            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.Default.SortByAlpha, contentDescription = "Sort")
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                val label = when (mode) {
                                    SortMode.TITLE -> "Title"
                                    SortMode.UNREAD_COUNT -> "Unread count"
                                    SortMode.DATE_ADDED -> "Date added"
                                    SortMode.LAST_READ -> "Last read"
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
                    Icon(Icons.Default.FilterList, contentDescription = "Filter")
                }
                DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (filterUnread) "✓ Unread" else "  Unread") },
                        onClick = { onFilterChange(!filterUnread, filterStarted, filterCompleted, filterDownloaded) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (filterStarted) "✓ Started" else "  Started") },
                        onClick = { onFilterChange(filterUnread, !filterStarted, filterCompleted, filterDownloaded) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (filterCompleted) "✓ Completed" else "  Completed") },
                        onClick = { onFilterChange(filterUnread, filterStarted, !filterCompleted, filterDownloaded) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (filterDownloaded) "✓ Downloaded" else "  Downloaded") },
                        onClick = { onFilterChange(filterUnread, filterStarted, filterCompleted, !filterDownloaded) },
                    )
                }
            }

            IconButton(onClick = onRandomManga) {
                Icon(Icons.Default.Shuffle, contentDescription = "Random manga")
            }

            IconButton(onClick = onManageCategories) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Manage categories")
            }

            if (isUpdating) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                }
            }
        }

        val activeFilters = buildList {
            if (filterUnread) add("Unread")
            if (filterStarted) add("Started")
            if (filterCompleted) add("Completed")
            if (filterDownloaded) add("Downloaded")
        }
        if (activeFilters.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                activeFilters.forEach { label ->
                    FilterChip(
                        selected = true,
                        onClick = {
                            when (label) {
                                "Unread" -> onFilterChange(false, filterStarted, filterCompleted, filterDownloaded)
                                "Started" -> onFilterChange(filterUnread, false, filterCompleted, filterDownloaded)
                                "Completed" -> onFilterChange(filterUnread, filterStarted, false, filterDownloaded)
                                "Downloaded" -> onFilterChange(filterUnread, filterStarted, filterCompleted, false)
                            }
                        },
                        label = { Text(label) },
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
    onSetCategories: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
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
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
        }
        TextButton(onClick = onSetCategories) { Text("Categories") }
        TextButton(onClick = onMarkRead) { Text("Mark read") }
        TextButton(onClick = onMarkUnread) { Text("Mark unread") }
        TextButton(onClick = onRemoveFromLibrary) {
            Text("Remove", color = MaterialTheme.colorScheme.error)
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
    onItemClick: (LibraryManga) -> Unit,
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
                onClick = { onItemClick(item) },
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
    onItemClick: (LibraryManga) -> Unit,
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
                            model = item.manga.thumbnailUrl,
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
                    { Text("${item.unreadCount} unread") }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onItemClick(item) },
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onContinueReading: () -> Unit,
    onContextMenu: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
                    model = item.manga.thumbnailUrl,
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
                            contentDescription = "Downloaded",
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
                        contentDescription = "Continue reading",
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
                            contentDescription = "Selected",
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
                text = "Your library is empty",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Add manga from Browse to get started",
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
            text = { Text("Mark all read") },
            onClick = onMarkAllRead,
        )
        DropdownMenuItem(
            text = { Text("Mark all unread") },
            onClick = onMarkAllUnread,
        )
        DropdownMenuItem(
            text = { Text("Download next unread") },
            onClick = onDownload,
        )
        DropdownMenuItem(
            text = { Text("Remove from library", color = MaterialTheme.colorScheme.error) },
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
        title = { Text("Set categories") },
        text = {
            if (categories.isEmpty()) {
                Text("No categories. Create categories first.")
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
            TextButton(onClick = { onConfirm(checkedIds.toList()) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
