package mihon.desktop.ui.library

import tachiyomi.i18n.MR

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
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.migration.BatchMigrationRequest
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.interactor.LibraryFilter
import mihon.desktop.ui.library.pickRandomMangaId
import mihon.desktop.domain.SortMode
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.migration.MigrationBatchQueueScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

internal fun libraryBatchMigrationDestination(
    selectedManga: List<Manga>,
    submit: (List<BatchMigrationRequest>) -> String,
): MigrationBatchQueueScreen? {
    val requests = selectedManga
        .filter { it.source != 0L }
        .map { BatchMigrationRequest(it.id, it.title) }
    return requests.takeIf { it.isNotEmpty() }?.let { MigrationBatchQueueScreen(submit(it)) }
}

internal data class LibrarySelectionActions(
    val download: (MangaDetailDownloadAction) -> Unit,
    val migrate: () -> Unit,
)

internal fun librarySelectionActions(
    selected: () -> List<LibraryManga>,
    queue: () -> List<mihon.desktop.download.DownloadItem>,
    launch: (suspend () -> Unit) -> Unit,
    enqueue: suspend (List<LibraryManga>, MangaDetailDownloadAction, List<mihon.desktop.download.DownloadItem>) -> Unit,
    submit: (List<BatchMigrationRequest>) -> String,
    navigate: (Screen) -> Unit,
    clear: () -> Unit,
) = LibrarySelectionActions(
    download = { action -> launch { enqueue(selected(), action, queue()); clear() } },
    migrate = {
        libraryBatchMigrationDestination(selected().map { it.manga }, submit)?.let {
            clear()
            navigate(it)
        }
    },
)

object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.CollectionsBookmark)
            return remember {
                TabOptions(
                    index = 0u,
                    title = MR.strings.label_library.localized(),
                    icon = icon,
                )
            }
        }

    @Composable
    override fun Content() {
        LibraryTabContent(LocalLibraryNavigationHost.current)
    }
}

/** Root screen of the Library tab — shows the manga grid with filters/sort/categories. */
class LibraryRootScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow
        val desktopDependencies = LocalDesktopUiDependencies.current

        val screenModelFactory = LocalLibraryScreenModelFactory.current
        val model = rememberScreenModel { screenModelFactory() }
        val state by model.state.collectAsState()
        val selectionState = remember { LibrarySelectionState() }

        // Read aliases — immutable vals at all read sites, writes go through model
        val allItems = state.allItems
        val categories = state.categories
        val searchQuery = state.searchQuery
        val sortMode = state.sortMode
        val sortAscending = state.sortAscending
        val filter = state.filter
        val selectedCategoryIndex = state.selectedCategoryIndex
        val isUpdating = state.isUpdating
        val updateStatusText = state.updateStatusText
        val showCategoryDialog = state.showCategoryDialog
        val displayMode = state.displayMode
        val contextMenuManga = state.contextMenuManga
        val showBatchCategoryDialog = state.showBatchCategoryDialog
        val batchCategoryResultMessage = state.batchCategoryResultMessage

        LaunchedEffect(Unit) {
            launch { model.libraryMangaFlow().collect {} }
            launch { model.refreshCategories() }
        }

        val categoryTabs = remember(categories) { listOf(null) + categories }

        // Load per-category sort/display settings when the selected tab changes.
        LaunchedEffect(selectedCategoryIndex, categoryTabs) {
            val cat = categoryTabs.getOrNull(selectedCategoryIndex)
            model.applyCategoryPreferences(cat?.id)
        }

        val downloadedMangaIds = state.downloadedMangaIds

        val displayedItems = remember(
            allItems, searchQuery, sortMode, sortAscending,
            filter, state.downloadedMangaIds, state.localMangaIds,
            state.trackerIdsByManga,
            selectedCategoryIndex, categoryTabs,
        ) {
            val selectedCategory = categoryTabs.getOrNull(selectedCategoryIndex)
            libraryPageItems(model, selectedCategory?.id)
        }
        val pageSnapshot = LibraryPageSnapshot(
            availableTrackerIds = state.availableTrackerIds,
            visibleItemIds = displayedItems.map { it.manga.id },
        )
        val pageProbe = LocalLibraryPageProbe.current
        pageProbe?.invoke(pageSnapshot)
        if (pageProbe != null) return
        val onItemPrimaryClick: (LibraryManga, Boolean) -> Unit = { item, shiftPressed ->
            selectionState.handlePrimaryClick(displayedItems.map { it.manga.id }, item.manga.id, shiftPressed) {
                navigator.push(MangaDetailScreen(it))
            }
        }
        val selectionActions = librarySelectionActions(
            selected = { allItems.filter { it.id in selectionState.selectedIds } },
            queue = { desktopDependencies.downloadQueuePort.queue.value },
            launch = { task -> scope.launch { task() } },
            enqueue = { items, action, queue -> model.enqueueDownloads(items, action, queue) },
            submit = desktopDependencies.batchMigrationController::submit,
            navigate = navigator::push,
            clear = selectionState::clear,
        )

        if (showCategoryDialog) {
            CategoryManagementDialog(
                categories = categories,
                onCreate = model::createCategory,
                onRename = model::renameCategory,
                onDelete = model::deleteCategory,
                onReorder = model::reorderCategory,
                onDismiss = {
                    model.setShowCategoryDialog(false)
                },
            )
        }

        // Right-click context menu
        val ctxManga = contextMenuManga
        if (ctxManga != null) {
            MangaContextMenu(
                expanded = true,
                onDismiss = { model.setContextMenuManga(null) },
                onMarkAllRead = {
                    model.setContextMenuManga(null)
                    scope.launch {
                        model.markMangaRead(ctxManga.manga.id, read = true)
                    }
                },
                onMarkAllUnread = {
                    model.setContextMenuManga(null)
                    scope.launch {
                        model.markMangaRead(ctxManga.manga.id, read = false)
                    }
                },
                onRemoveFromLibrary = {
                    model.setContextMenuManga(null)
                    scope.launch {
                        model.removeFromLibrary(listOf(ctxManga.manga.id))
                    }
                },
                onDownload = {
                    model.setContextMenuManga(null)
                    scope.launch { model.enqueueNextUnreadDownload(ctxManga) }
                },
            )
        }

        // Batch category assignment dialog
        if (showBatchCategoryDialog) {
            BatchCategoryDialog(
                categories = categories,
                selectedMangaIds = selectionState.selectedIds.toList(),
                loadCategoryIds = model::categoryIdsForManga,
                onConfirm = { selectedCategoryIds ->
                    scope.launch {
                        model.setCategoriesForManga(selectionState.selectedIds.toList(), selectedCategoryIds)
                        model.setShowBatchCategoryDialog(false)
                        selectionState.clear()
                    }
                },
                onDismiss = { model.setShowBatchCategoryDialog(false) },
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
                        onInvertSelection = { selectionState.invertVisible(displayedItems.map { it.manga.id }) },
                        actions = selectionActions,
                        canMigrate = allItems.any { it.id in selectionState.selectedIds && it.manga.source != 0L },
                        onSetCategories = { model.setShowBatchCategoryDialog(true) },
                        onMarkRead = {
                            scope.launch {
                                model.markMangaRead(selectionState.selectedIds, read = true)
                                selectionState.clear()
                            }
                        },
                        onMarkUnread = {
                            scope.launch {
                                model.markMangaRead(selectionState.selectedIds, read = false)
                                selectionState.clear()
                            }
                        },
                        onRemoveFromLibrary = {
                            scope.launch {
                                model.removeFromLibrary(selectionState.selectedIds)
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
                    onSearchChange = { model.setSearchQuery(it) },
                    sortMode = sortMode,
                    sortAscending = sortAscending,
                    onSortChange = { mode, asc ->
                        val cat = categoryTabs.getOrNull(selectedCategoryIndex)
                        model.setSortModeAndDirectionForCategory(cat?.id, mode, asc)
                    },
                    filter = filter,
                    availableTrackerIds = pageSnapshot.availableTrackerIds,
                    onToggleFilter = model::toggleFilter,
                    onToggleTracking = model::toggleTrackingFilter,
                    onToggleGlobalDownloadedOnly = model::toggleGlobalDownloadedOnly,
                    onToggleSkipOutsideReleasePeriod = model::toggleSkipOutsideReleasePeriod,
                    isUpdating = isUpdating,
                    displayMode = displayMode,
                    onDisplayModeChange = {
                        val cat = categoryTabs.getOrNull(selectedCategoryIndex)
                        model.setDisplayModeForCategory(cat?.id, it)
                    },
                    onManageCategories = { model.setShowCategoryDialog(true) },
                    onRandomManga = {
                        val randomId = pickRandomMangaId(displayedItems.map { it.manga.id })
                        if (randomId != null) navigator.push(MangaDetailScreen(randomId))
                    },
                    onRefresh = {
                        scope.launch {
                            model.refreshLibrary(allItems)
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
                                onClick = { model.setSelectedCategoryIndex(index) },
                                text = { Text(cat?.name ?: MR.strings.all.localized()) },
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

                if (batchCategoryResultMessage != null) {
                    Text(
                        text = batchCategoryResultMessage,
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
                            MR.strings.desktop_ui_no_manga_match_your_filters.localized(),
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
                                downloadedMangaIds = downloadedMangaIds,
                                onContextMenu = { item -> model.setContextMenuManga(item) },
                                onItemClick = onItemPrimaryClick,
                                onItemLongClick = { item -> selectionState.toggle(item.manga.id) },
                                onContinueReading = { item ->
                                    scope.launch {
                                        val request = model.continueReadingRequest(item) ?: return@launch
                                        navigator.push(
                                            DesktopReaderScreen(
                                                chapterTitle = request.chapterTitle,
                                                mangaTitle = request.mangaTitle,
                                                isWebtoon = false,
                                                sourceId = request.sourceId,
                                                chapterUrl = request.chapterUrl,
                                                chapterId = request.chapterId,
                                                mangaId = request.mangaId,
                                                mangaViewerFlags = request.mangaViewerFlags,
                                                chapters = request.chapters,
                                                currentChapterIndex = request.currentChapterIndex,
                                                initialPage = request.initialPage,
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
                                downloadedMangaIds = downloadedMangaIds,
                                onContextMenu = { item -> model.setContextMenuManga(item) },
                                onItemClick = onItemPrimaryClick,
                                onItemLongClick = { item -> selectionState.toggle(item.manga.id) },
                                onContinueReading = { item ->
                                    scope.launch {
                                        val request = model.continueReadingRequest(item) ?: return@launch
                                        navigator.push(
                                            DesktopReaderScreen(
                                                chapterTitle = request.chapterTitle,
                                                mangaTitle = request.mangaTitle,
                                                isWebtoon = false,
                                                sourceId = request.sourceId,
                                                chapterUrl = request.chapterUrl,
                                                chapterId = request.chapterId,
                                                mangaId = request.mangaId,
                                                mangaViewerFlags = request.mangaViewerFlags,
                                                chapters = request.chapters,
                                                currentChapterIndex = request.currentChapterIndex,
                                                initialPage = request.initialPage,
                                            ),
                                        )
                                    }
                                },
                            )
                        LibraryDisplayMode.LIST ->
                            LibraryList(
                                items = displayedItems,
                                selectionState = selectionState,
                                onContextMenu = { item -> model.setContextMenuManga(item) },
                                onItemClick = onItemPrimaryClick,
                                onItemLongClick = { item -> selectionState.toggle(item.manga.id) },
                            )
                    }
                }
            }
        }
    }
}

/** Page-level projection keeps production UI on the ScreenModel's complete evaluation context. */
internal fun libraryPageItems(model: LibraryScreenModel, categoryId: Long?): List<LibraryManga> =
    model.visibleItems(categoryId)

// ── Toolbar ───────────────────────────────────────────────────────────────────
