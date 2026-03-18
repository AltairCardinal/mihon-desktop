package mihon.desktop.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import mihon.desktop.domain.DesktopCategoryManager
import mihon.desktop.domain.LibrarySearchFilter
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SortMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
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
        // Wrap in a nested Navigator so that push(MangaDetailScreen) works.
        // Inside TabNavigator, LocalNavigator only accepts Tab objects — the nested
        // Navigator provides a regular Screen stack for the Library tab.
        Navigator(LibraryRootScreen()) {
            CurrentScreen()
        }
    }
}

/** Root screen of the Library tab — shows the manga grid with filters/sort/categories. */
class LibraryRootScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val getLibraryManga = remember { Injekt.get<GetLibraryManga>() }
        val categoryManager = remember { Injekt.get<DesktopCategoryManager>() }
        val updateChecker = remember { Injekt.get<LibraryUpdateChecker>() }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val scope = rememberCoroutineScope()

        var allItems by remember { mutableStateOf<List<LibraryManga>>(emptyList()) }
        var categories by remember { mutableStateOf<List<Category>>(emptyList()) }

        // UI state
        var searchQuery by remember { mutableStateOf("") }
        var sortMode by remember { mutableStateOf(SortMode.TITLE) }
        var sortAscending by remember { mutableStateOf(true) }
        var filterUnread by remember { mutableStateOf(false) }
        var filterStarted by remember { mutableStateOf(false) }
        var filterCompleted by remember { mutableStateOf(false) }
        var selectedCategoryIndex by remember { mutableIntStateOf(0) } // 0 = "All"
        var isUpdating by remember { mutableStateOf(false) }
        var updateStatusText by remember { mutableStateOf<String?>(null) }
        var showCategoryDialog by remember { mutableStateOf(false) }

        // Load library + categories
        LaunchedEffect(Unit) {
            launch { getLibraryManga.subscribe().collect { allItems = it } }
            launch { categories = categoryManager.getAll() }
        }

        // Build the category list: "All" + user categories
        val categoryTabs = remember(categories) {
            listOf(null) + categories // null = "All"
        }

        // Apply filters/search/sort
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

        Column(Modifier.fillMaxSize()) {
            // ── Toolbar row: search + sort + filter + refresh ──────────
            LibraryToolbar(
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                sortMode = sortMode,
                sortAscending = sortAscending,
                onSortChange = { mode, asc -> sortMode = mode; sortAscending = asc },
                filterUnread = filterUnread,
                filterStarted = filterStarted,
                filterCompleted = filterCompleted,
                onFilterChange = { u, s, c -> filterUnread = u; filterStarted = s; filterCompleted = c },
                isUpdating = isUpdating,
                onManageCategories = { showCategoryDialog = true },
                onRefresh = {
                    scope.launch {
                        isUpdating = true
                        updateStatusText = "Checking for updates..."
                        var totalNew = 0
                        for (item in allItems) {
                            val source = sourceManager.getCatalogueSources()
                                .find { it.id == item.manga.source } ?: continue
                            val result = updateChecker.checkForUpdates(item.manga, source)
                            totalNew += result.newChapterCount
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

            // ── Category tabs ─────────────────────────────────────────
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

            // ── Update status ─────────────────────────────────────────
            if (updateStatusText != null) {
                Text(
                    text = updateStatusText!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // ── Grid / empty state ────────────────────────────────────
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
                LibraryGrid(displayedItems)
            }
        }
    }
}

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
    onManageCategories: () -> Unit,
    onRefresh: () -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search library...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            // Sort button
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
                                val arrow = if (mode == sortMode) {
                                    if (sortAscending) " ↑" else " ↓"
                                } else {
                                    ""
                                }
                                Text("$label$arrow")
                            },
                            onClick = {
                                if (mode == sortMode) {
                                    onSortChange(mode, !sortAscending)
                                } else {
                                    onSortChange(mode, true)
                                }
                                showSortMenu = false
                            },
                        )
                    }
                }
            }

            // Filter button
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

            // Manage Categories button
            IconButton(onClick = onManageCategories) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Manage categories")
            }

            // Refresh button
            if (isUpdating) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                }
            }
        }

        // Active filter chips
        val activeFilters = buildList {
            if (filterUnread) add("Unread")
            if (filterStarted) add("Started")
            if (filterCompleted) add("Completed")
        }
        if (activeFilters.isNotEmpty()) {
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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

@Composable
private fun LibraryGrid(items: List<LibraryManga>) {
    val navigator = LocalNavigator.currentOrThrow
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            MangaCoverCard(
                item = item,
                onClick = { navigator.push(MangaDetailScreen(item.manga.id)) },
            )
        }
    }
}

@Composable
private fun MangaCoverCard(item: LibraryManga, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = item.manga.thumbnailUrl,
                contentDescription = item.manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f),
            )
            // Gradient + title overlay at bottom
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
        }
    }
}
