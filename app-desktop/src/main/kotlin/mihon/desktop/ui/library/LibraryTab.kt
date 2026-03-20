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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Badge
import androidx.compose.material3.BottomAppBar
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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DownloadItem
import mihon.desktop.domain.DesktopCategoryManager
import mihon.desktop.settings.LibraryCategoryPrefs
import mihon.desktop.domain.LibrarySearchFilter
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SortMode
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.ui.reader.DesktopReaderScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.CollectionsBookmark)
            return remember {
                TabOptions(
                    index = 0u,
                    title = "Library",
                    icon = icon,
                )
            }
        }

    @Composable
    override fun Content() {
        Navigator(LibraryRootScreen()) {
            CurrentScreen()
        }
    }
}

/** Root screen of the Library tab — shows the manga grid with filters/sort/categories. */
class LibraryRootScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val getLibraryManga = remember { Injekt.get<GetLibraryManga>() }
        val categoryManager = remember { Injekt.get<DesktopCategoryManager>() }
        val updateChecker = remember { Injekt.get<LibraryUpdateChecker>() }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val mangaRepository = remember { Injekt.get<MangaRepository>() }
        val chapterRepository = remember { Injekt.get<ChapterRepository>() }
        val progressTracker = remember { Injekt.get<mihon.desktop.domain.ReaderProgressTracker>() }
        val downloadManager = remember { runCatching { Injekt.get<DesktopDownloadManager>() }.getOrNull() }
        val downloadPrefs = remember { runCatching { Injekt.get<DesktopDownloadPreferences>() }.getOrNull() }
        val categoryPrefs = remember { runCatching { Injekt.get<LibraryCategoryPrefs>() }.getOrNull() }
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow

        var allItems by remember { mutableStateOf<List<LibraryManga>>(emptyList()) }
        var categories by remember { mutableStateOf<List<Category>>(emptyList()) }

        // UI state
        var searchQuery by remember { mutableStateOf("") }
        var sortMode by remember { mutableStateOf(SortMode.TITLE) }
        var sortAscending by remember { mutableStateOf(true) }
        var filterUnread by remember { mutableStateOf(false) }
        var filterStarted by remember { mutableStateOf(false) }
        var filterCompleted by remember { mutableStateOf(false) }
        var selectedCategoryIndex by remember { mutableIntStateOf(0) }
        var isUpdating by remember { mutableStateOf(false) }
        var updateStatusText by remember { mutableStateOf<String?>(null) }
        var showCategoryDialog by remember { mutableStateOf(false) }
        var displayMode by remember { mutableStateOf(LibraryDisplayMode.DEFAULT) }
        // Context menu for right-click on manga card
        var contextMenuManga by remember { mutableStateOf<LibraryManga?>(null) }

        // Batch selection
        val selectionState = remember { LibrarySelectionState() }

        LaunchedEffect(Unit) {
            launch { getLibraryManga.subscribe().collect { allItems = it } }
            launch { categories = categoryManager.getAll() }
        }

        val categoryTabs = remember(categories) { listOf(null) + categories }

        // Load per-category sort/display settings when the selected tab changes.
        LaunchedEffect(selectedCategoryIndex, categoryTabs) {
            val cat = categoryTabs.getOrNull(selectedCategoryIndex)
            categoryPrefs?.let { prefs ->
                sortMode = prefs.getSortMode(cat?.id)
                sortAscending = prefs.getSortAscending(cat?.id)
                displayMode = prefs.getDisplayMode(cat?.id)
            }
        }

        val displayedItems = remember(
            allItems, searchQuery, sortMode, sortAscending,
            filterUnread, filterStarted, filterCompleted, selectedCategoryIndex, categoryTabs,
        ) {
            val selectedCategory = categoryTabs.getOrNull(selectedCategoryIndex)
            LibrarySearchFilter.apply(
                items = allItems,
                categoryId = selectedCategory?.id,
                searchQuery = searchQuery.ifBlank { null },
                unread = filterUnread,
                started = filterStarted,
                completed = filterCompleted,
                sortMode = sortMode,
                ascending = sortAscending,
            )
        }

        if (showCategoryDialog) {
            CategoryManagementDialog(
                categoryManager = categoryManager,
                onDismiss = {
                    showCategoryDialog = false
                    scope.launch { categories = categoryManager.getAll() }
                },
            )
        }

        // Right-click context menu
        val ctxManga = contextMenuManga
        if (ctxManga != null) {
            MangaContextMenu(
                expanded = true,
                onDismiss = { contextMenuManga = null },
                onMarkAllRead = {
                    contextMenuManga = null
                    scope.launch {
                        val updates = chapterRepository.getChapterByMangaId(ctxManga.manga.id)
                            .map { tachiyomi.domain.chapter.model.ChapterUpdate(id = it.id, read = true) }
                        chapterRepository.updateAll(updates)
                    }
                },
                onMarkAllUnread = {
                    contextMenuManga = null
                    scope.launch {
                        val updates = chapterRepository.getChapterByMangaId(ctxManga.manga.id)
                            .map { tachiyomi.domain.chapter.model.ChapterUpdate(id = it.id, read = false) }
                        chapterRepository.updateAll(updates)
                    }
                },
                onRemoveFromLibrary = {
                    contextMenuManga = null
                    scope.launch {
                        mangaRepository.update(MangaUpdate(id = ctxManga.manga.id, favorite = false))
                    }
                },
                onDownload = {
                    contextMenuManga = null
                    if (downloadManager != null) {
                        scope.launch {
                            val chapters = chapterRepository.getChapterByMangaId(ctxManga.manga.id)
                            val firstUnread = chapters.sortedBy { it.sourceOrder }.firstOrNull { !it.read }
                                ?: return@launch
                            downloadManager.enqueue(
                                DownloadItem(
                                    sourceId = ctxManga.manga.source,
                                    mangaTitle = ctxManga.manga.title,
                                    chapterName = firstUnread.name,
                                    chapterId = firstUnread.id,
                                    chapterUrl = firstUnread.url,
                                ),
                            )
                        }
                    }
                },
            )
        }

        Scaffold(
            // ── Selection action bar ───────────────────────────────────────
            bottomBar = {
                if (selectionState.isInSelectionMode) {
                    SelectionActionBar(
                        selectedCount = selectionState.selectedIds.size,
                        onClose = { selectionState.clear() },
                        onSelectAll = { selectionState.selectAll(displayedItems.map { it.manga.id }) },
                        onMarkRead = {
                            scope.launch {
                                selectionState.selectedIds.forEach { mangaId ->
                                    val chapters = chapterRepository.getChapterByMangaId(mangaId)
                                    val updates = chapters.map { ch ->
                                        tachiyomi.domain.chapter.model.ChapterUpdate(id = ch.id, read = true)
                                    }
                                    chapterRepository.updateAll(updates)
                                }
                                selectionState.clear()
                            }
                        },
                        onMarkUnread = {
                            scope.launch {
                                selectionState.selectedIds.forEach { mangaId ->
                                    val chapters = chapterRepository.getChapterByMangaId(mangaId)
                                    val updates = chapters.map { ch ->
                                        tachiyomi.domain.chapter.model.ChapterUpdate(id = ch.id, read = false)
                                    }
                                    chapterRepository.updateAll(updates)
                                }
                                selectionState.clear()
                            }
                        },
                        onRemoveFromLibrary = {
                            scope.launch {
                                selectionState.selectedIds.forEach { mangaId ->
                                    mangaRepository.update(MangaUpdate(id = mangaId, favorite = false))
                                }
                                selectionState.clear()
                            }
                        },
                    )
                }
            },
        ) { scaffoldPadding ->
            Column(Modifier.fillMaxSize().padding(scaffoldPadding)) {
                LibraryToolbar(
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    sortMode = sortMode,
                    sortAscending = sortAscending,
                    onSortChange = { mode, asc ->
                        sortMode = mode; sortAscending = asc
                        val cat = categoryTabs.getOrNull(selectedCategoryIndex)
                        categoryPrefs?.setSortMode(cat?.id, mode)
                        categoryPrefs?.setSortAscending(cat?.id, asc)
                    },
                    filterUnread = filterUnread,
                    filterStarted = filterStarted,
                    filterCompleted = filterCompleted,
                    onFilterChange = { u, s, c -> filterUnread = u; filterStarted = s; filterCompleted = c },
                    isUpdating = isUpdating,
                    displayMode = displayMode,
                    onDisplayModeChange = {
                        displayMode = it
                        val cat = categoryTabs.getOrNull(selectedCategoryIndex)
                        categoryPrefs?.setDisplayMode(cat?.id, it)
                    },
                    onManageCategories = { showCategoryDialog = true },
                    onRefresh = {
                        scope.launch {
                            isUpdating = true
                            updateStatusText = "Checking for updates..."
                            var totalNew = 0
                            val autoDownload = downloadPrefs?.autoDownloadNewChapters?.get() == true
                            for (item in allItems) {
                                val source = sourceManager.getCatalogueSources()
                                    .find { it.id == item.manga.source } ?: continue
                                val result = updateChecker.checkForUpdates(item.manga, source)
                                totalNew += result.newChapterCount
                                if (autoDownload && downloadManager != null) {
                                    result.newChapters.forEach { chapter ->
                                        downloadManager.enqueue(
                                            DownloadItem(
                                                sourceId = item.manga.source,
                                                mangaTitle = item.manga.title,
                                                chapterName = chapter.name,
                                                chapterId = chapter.id,
                                                chapterUrl = chapter.url,
                                            ),
                                        )
                                    }
                                }
                            }
                            updateStatusText = if (totalNew > 0) {
                                "$totalNew new chapter(s) found"
                            } else {
                                "Library is up to date"
                            }
                            isUpdating = false
                        }
                    },
                )

                if (categories.isNotEmpty()) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedCategoryIndex,
                        modifier = Modifier.fillMaxWidth(),
                        edgePadding = 8.dp,
                    ) {
                        categoryTabs.forEachIndexed { index, cat ->
                            Tab(
                                selected = index == selectedCategoryIndex,
                                onClick = { selectedCategoryIndex = index },
                                text = { Text(cat?.name ?: "All") },
                            )
                        }
                    }
                }

                if (updateStatusText != null) {
                    Text(
                        text = updateStatusText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                if (allItems.isEmpty()) {
                    EmptyLibrary()
                } else if (displayedItems.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No manga match your filters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    when (displayMode) {
                        LibraryDisplayMode.COMPACT_GRID ->
                            LibraryGrid(
                                items = displayedItems,
                                minCardWidth = 120.dp,
                                selectionState = selectionState,
                                onContextMenu = { item -> contextMenuManga = item },
                                onItemClick = { item ->
                                    if (selectionState.isInSelectionMode) {
                                        selectionState.toggle(item.manga.id)
                                    } else {
                                        navigator.push(MangaDetailScreen(item.manga.id))
                                    }
                                },
                                onItemLongClick = { item -> selectionState.toggle(item.manga.id) },
                                onContinueReading = { item ->
                                    scope.launch {
                                        val chapters = chapterRepository.getChapterByMangaId(item.manga.id)
                                        val firstUnread = chapters
                                            .sortedBy { it.sourceOrder }
                                            .firstOrNull { !it.read }
                                            ?: chapters.maxByOrNull { it.sourceOrder }
                                            ?: return@launch
                                        val chapterRefs = chapters.sortedBy { it.sourceOrder }
                                            .map { ReaderChapterRef(id = it.id, url = it.url, name = it.name) }
                                        val idx = ReaderNavigator.indexForId(chapterRefs, firstUnread.id)
                                        navigator.push(
                                            DesktopReaderScreen(
                                                chapterTitle = firstUnread.name,
                                                mangaTitle = item.manga.title,
                                                pageUrls = emptyList(),
                                                isWebtoon = false,
                                                sourceId = item.manga.source,
                                                chapterUrl = firstUnread.url,
                                                chapterId = firstUnread.id,
                                                chapters = chapterRefs,
                                                currentChapterIndex = idx,
                                                initialPage = firstUnread.lastPageRead.toInt().coerceAtLeast(0),
                                                progressTracker = progressTracker,
                                            ),
                                        )
                                    }
                                },
                            )
                        LibraryDisplayMode.COMFORTABLE_GRID ->
                            LibraryGrid(
                                items = displayedItems,
                                minCardWidth = 160.dp,
                                comfortable = true,
                                selectionState = selectionState,
                                onContextMenu = { item -> contextMenuManga = item },
                                onItemClick = { item ->
                                    if (selectionState.isInSelectionMode) {
                                        selectionState.toggle(item.manga.id)
                                    } else {
                                        navigator.push(MangaDetailScreen(item.manga.id))
                                    }
                                },
                                onItemLongClick = { item -> selectionState.toggle(item.manga.id) },
                                onContinueReading = { item ->
                                    scope.launch {
                                        val chapters = chapterRepository.getChapterByMangaId(item.manga.id)
                                        val firstUnread = chapters
                                            .sortedBy { it.sourceOrder }
                                            .firstOrNull { !it.read }
                                            ?: chapters.maxByOrNull { it.sourceOrder }
                                            ?: return@launch
                                        val chapterRefs = chapters.sortedBy { it.sourceOrder }
                                            .map { ReaderChapterRef(id = it.id, url = it.url, name = it.name) }
                                        val idx = ReaderNavigator.indexForId(chapterRefs, firstUnread.id)
                                        navigator.push(
                                            DesktopReaderScreen(
                                                chapterTitle = firstUnread.name,
                                                mangaTitle = item.manga.title,
                                                pageUrls = emptyList(),
                                                isWebtoon = false,
                                                sourceId = item.manga.source,
                                                chapterUrl = firstUnread.url,
                                                chapterId = firstUnread.id,
                                                chapters = chapterRefs,
                                                currentChapterIndex = idx,
                                                initialPage = firstUnread.lastPageRead.toInt().coerceAtLeast(0),
                                                progressTracker = progressTracker,
                                            ),
                                        )
                                    }
                                },
                            )
                        LibraryDisplayMode.LIST ->
                            LibraryList(
                                items = displayedItems,
                                selectionState = selectionState,
                                onContextMenu = { item -> contextMenuManga = item },
                                onItemClick = { item ->
                                    if (selectionState.isInSelectionMode) {
                                        selectionState.toggle(item.manga.id)
                                    } else {
                                        navigator.push(MangaDetailScreen(item.manga.id))
                                    }
                                },
                                onItemLongClick = { item -> selectionState.toggle(item.manga.id) },
                            )
                    }
                }
            }
        }
    }
}

// ── Toolbar ───────────────────────────────────────────────────────────────────

@Composable
private fun LibraryToolbar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortMode: SortMode,
    sortAscending: Boolean,
    onSortChange: (SortMode, Boolean) -> Unit,
    filterUnread: Boolean,
    filterStarted: Boolean,
    filterCompleted: Boolean,
    onFilterChange: (unread: Boolean, started: Boolean, completed: Boolean) -> Unit,
    isUpdating: Boolean,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    onManageCategories: () -> Unit,
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
                        onClick = { onFilterChange(!filterUnread, filterStarted, filterCompleted) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (filterStarted) "✓ Started" else "  Started") },
                        onClick = { onFilterChange(filterUnread, !filterStarted, filterCompleted) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (filterCompleted) "✓ Completed" else "  Completed") },
                        onClick = { onFilterChange(filterUnread, filterStarted, !filterCompleted) },
                    )
                }
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
        }
        if (activeFilters.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                activeFilters.forEach { label ->
                    FilterChip(
                        selected = true,
                        onClick = {
                            when (label) {
                                "Unread" -> onFilterChange(false, filterStarted, filterCompleted)
                                "Started" -> onFilterChange(filterUnread, false, filterCompleted)
                                "Completed" -> onFilterChange(filterUnread, filterStarted, false)
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
private fun SelectionActionBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
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
private fun LibraryGrid(
    items: List<LibraryManga>,
    minCardWidth: androidx.compose.ui.unit.Dp,
    comfortable: Boolean = false,
    selectionState: LibrarySelectionState,
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
private fun LibraryList(
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
private fun MangaCoverCard(
    item: LibraryManga,
    comfortable: Boolean,
    isSelected: Boolean,
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
private fun EmptyLibrary() {
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
private fun MangaContextMenu(
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
