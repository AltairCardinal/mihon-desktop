package mihon.desktop.ui.library

import tachiyomi.i18n.MR

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalUriHandler
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.BuildInfo
import mihon.desktop.migration.BatchMigrationRequest
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.interactor.LibraryFilter
import mihon.desktop.ui.library.pickRandomMangaId
import mihon.desktop.domain.SortMode
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.settings.LibrarySettingsScreen
import mihon.desktop.ui.migration.LibraryBatchMigrationConfigScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.projectLibraryToolbar
import tachiyomi.domain.manga.model.Manga
import tachiyomi.core.common.util.lang.launchNonCancellable
import mihon.desktop.domain.DesktopNotification
import mihon.desktop.domain.DesktopNotificationService

internal fun libraryBatchMigrationDestination(
    selectedManga: List<Manga>,
): LibraryBatchMigrationConfigScreen? {
    val requests = selectedManga.map { BatchMigrationRequest(it.id, it.title) }
    return requests.takeIf { it.isNotEmpty() }?.let { LibraryBatchMigrationConfigScreen(it) }
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
    navigate: (Screen) -> Unit,
    clear: () -> Unit,
) = LibrarySelectionActions(
    download = { action ->
        val selectedItems = selected()
        val activeQueue = queue()
        clear()
        launch { enqueue(selectedItems, action, activeQueue) }
    },
    migrate = {
        libraryBatchMigrationDestination(selected().map { it.manga })?.let {
            clear()
            navigate(it)
        }
    },
)

/** Captures a selection before launching work so leaving selection mode cannot change its target. */
internal fun clearSelectionBeforeAsync(
    selectedIds: Set<Long>,
    clear: () -> Unit,
    launch: ((suspend () -> Unit) -> Unit),
    operation: suspend (Set<Long>) -> Unit,
) {
    val snapshot = selectedIds.toSet()
    clear()
    launch { operation(snapshot) }
}

internal fun clearSelectionBeforeRemoval(
    items: List<LibraryManga>,
    clear: () -> Unit,
    launch: ((suspend () -> Unit) -> Unit),
    operation: suspend (List<Long>) -> Unit,
) {
    val snapshot = items.map { it.id }.distinct()
    clear()
    launch { operation(snapshot) }
}

internal fun CoroutineScope.launchAcceptedLibraryOperation(
    notificationService: DesktopNotificationService,
    feedback: () -> String?,
    operation: suspend () -> Unit,
) = launchNonCancellable {
    operation()
    if (!this@launchAcceptedLibraryOperation.isActive) {
        feedback()?.let { notificationService.post(DesktopNotification(title = "", message = it)) }
    }
}

object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.CollectionsBookmark)
            val localeTag = mihon.desktop.platform.LocalDesktopLocaleTag.current
            return remember(localeTag) {
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
        val rootFocusRequester = remember { FocusRequester() }
        val navigator = LocalNavigator.currentOrThrow
        val desktopDependencies = LocalDesktopUiDependencies.current
        val libraryNavigationHost = LocalLibraryNavigationHost.current
        var showFilterMenu by remember { mutableStateOf(false) }
        DisposableEffect(libraryNavigationHost) {
            val unregister = libraryNavigationHost.registerReselectHandler { showFilterMenu = true }
            onDispose(unregister)
        }

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
        val selectedCategoryId = categories.getOrNull(selectedCategoryIndex)?.id
        val isUpdating = state.isUpdating
        val updateStatusText = state.updateStatusText
        val displayMode = state.displayMode
        val contextMenuManga = state.contextMenuManga
        val showBatchCategoryDialog = state.showBatchCategoryDialog
        LaunchedEffect(selectionState.isInSelectionMode) {
            if (selectionState.isInSelectionMode) rootFocusRequester.requestFocus()
        }
        val batchCategoryResultMessage = state.batchCategoryResultMessage
        val operationFeedback = state.operationFeedback
        var removalTarget by remember { mutableStateOf<List<LibraryManga>?>(null) }
        var batchCategoryTarget by remember { mutableStateOf<List<Long>?>(null) }

        LaunchedEffect(Unit) {
            model.syncBackgroundUpdate()
            launch { model.libraryMangaFlow().collect {} }
            launch { model.observeCategories() }
            launch { model.observeLibraryPreferences() }
        }

        val categoryTabs = remember(categories) { categories }
        val selectedCategory = categoryTabs.getOrNull(selectedCategoryIndex)
        // Load per-category sort/display settings when the selected tab changes.
        LaunchedEffect(selectedCategoryIndex, categoryTabs) {
            model.applyCategoryPreferences(categoryTabs.getOrNull(selectedCategoryIndex)?.id)
        }

        val downloadedMangaIds = state.downloadedMangaIds

        val displayedItems = remember(
            allItems, searchQuery, sortMode, sortAscending,
            filter, state.downloadedMangaIds, state.localMangaIds,
            state.downloadCountsByManga, state.trackerIdsByManga, state.trackerMeansByManga,
            state.sourceLanguagesByManga,
            selectedCategoryIndex, categoryTabs,
        ) {
            val selectedCategory = categoryTabs.getOrNull(selectedCategoryIndex)
            libraryPageItems(model, selectedCategory?.id)
        }
        val toolbarProjection = projectLibraryToolbar(
            libraryTitle = MR.strings.label_library.localized(),
            defaultCategoryTitle = MR.strings.label_default.localized(),
            categoryName = selectedCategory?.name,
            isSystemCategory = selectedCategory?.isSystemCategory == true,
            showCategoryTabs = state.showCategoryTabs,
            showMangaCount = state.showCategoryItemCounts,
            categoryCount = displayedItems.size,
            libraryCount = allItems.size,
        )
        val toolbarTitle = toolbarProjection.count?.let { "${toolbarProjection.title} ($it)" } ?: toolbarProjection.title
        val pageSnapshot = LibraryPageSnapshot(
            availableTrackerIds = state.availableTrackerIds,
            visibleItemIds = displayedItems.map { it.manga.id },
        )
        val pageProbe = LocalLibraryPageProbe.current
        pageProbe?.invoke(pageSnapshot)
        val onItemPrimaryClick: (LibraryManga, Boolean) -> Unit = { item, shiftPressed ->
            selectionState.handlePrimaryClick(
                visibleIds = displayedItems.map { it.manga.id },
                targetId = item.manga.id,
                shiftPressed = shiftPressed,
                categoryId = selectedCategoryId,
            ) {
                navigator.push(MangaDetailScreen(it))
            }
        }
        val onContinueReading: (LibraryManga) -> Unit = { item ->
            if (selectionState.isInSelectionMode) {
                selectionState.toggle(item.manga.id, selectedCategoryId)
            } else {
                scope.launch {
                    val request = model.continueReadingRequest(item)
                    if (request != null) navigator.push(request.toDesktopReaderScreen())
                }
            }
        }
        val selectionActions = librarySelectionActions(
            selected = { allItems.filter { it.id in selectionState.selectedIds } },
            queue = { desktopDependencies.downloadQueuePort.queue.value },
            launch = { task ->
                model.clearOperationResults()
                scope.launchAcceptedLibraryOperation(
                    desktopDependencies.notificationService,
                    feedback = { model.state.value.batchCategoryResultMessage },
                    operation = task,
                )
            },
            enqueue = { items, action, queue -> model.enqueueDownloads(items, action, queue) },
            navigate = navigator::push,
            clear = selectionState::clear,
        )

        removalTarget?.let { items ->
            LibraryRemovalDialog(
                items = items,
                onDismiss = { removalTarget = null },
                onConfirm = { removeFromLibrary, deleteDownloads ->
                    removalTarget = null
                    clearSelectionBeforeRemoval(
                        items = items,
                        clear = selectionState::clear,
                        launch = { task ->
                            model.clearOperationResults()
                            scope.launchAcceptedLibraryOperation(
                                desktopDependencies.notificationService,
                                feedback = { model.state.value.operationFeedback },
                                operation = task,
                            )
                        },
                    ) { ids ->
                        model.removeFromLibrary(
                            mangaIds = ids,
                            deleteDownloads = deleteDownloads,
                            removeFromLibrary = removeFromLibrary,
                        )
                    }
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
                    model.clearOperationResults()
                    scope.launchAcceptedLibraryOperation(
                        desktopDependencies.notificationService,
                        feedback = { model.state.value.operationFeedback },
                    ) {
                        model.markMangaRead(ctxManga.manga.id, read = true)
                    }
                },
                onMarkAllUnread = {
                    model.setContextMenuManga(null)
                    model.clearOperationResults()
                    scope.launchAcceptedLibraryOperation(
                        desktopDependencies.notificationService,
                        feedback = { model.state.value.operationFeedback },
                    ) {
                        model.markMangaRead(ctxManga.manga.id, read = false)
                    }
                },
                onRemoveFromLibrary = {
                    model.setContextMenuManga(null)
                    removalTarget = listOf(ctxManga)
                },
                onDownload = {
                    model.setContextMenuManga(null)
                    model.clearOperationResults()
                    scope.launchAcceptedLibraryOperation(
                        desktopDependencies.notificationService,
                        feedback = { model.state.value.operationFeedback },
                    ) { model.enqueueNextUnreadDownload(ctxManga) }
                },
                canDownload = ctxManga.manga.source != 0L,
            )
        }

        // Batch category assignment dialog
        if (showBatchCategoryDialog) {
            val targetIds = batchCategoryTarget.orEmpty()
            BatchCategoryDialog(
                categories = categories,
                selectedMangaIds = targetIds,
                loadCategoryIds = model::categoryIdsForManga,
                onConfirm = { delta ->
                    model.setShowBatchCategoryDialog(false)
                    batchCategoryTarget = null
                    selectionState.clear()
                    model.clearOperationResults()
                    scope.launchAcceptedLibraryOperation(
                        desktopDependencies.notificationService,
                        feedback = { model.state.value.batchCategoryResultMessage },
                    ) {
                        model.updateCategoriesForManga(
                            mangaIds = targetIds,
                            addCategoryIds = delta.addCategoryIds,
                            removeCategoryIds = delta.removeCategoryIds,
                        )
                    }
                },
                onDismiss = {
                    model.setShowBatchCategoryDialog(false)
                    batchCategoryTarget = null
                },
                onEditCategories = {
                    selectionState.clear()
                    navigator.push(CategoryManagementScreen())
                },
            )
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            // ── Selection action bar ───────────────────────────────────────
            bottomBar = {
                if (selectionState.isInSelectionMode) {
                    val selectedItems = allItems.filter { it.id in selectionState.selectedIds }
                    val remoteSelection = selectedItems.isNotEmpty() && selectedItems.all { it.manga.source != 0L }
                    SelectionActionBar(
                        actions = selectionActions,
                        canDownload = remoteSelection,
                        canMigrate = selectedItems.isNotEmpty(),
                        onSetCategories = {
                            batchCategoryTarget = selectionState.selectedIds.toList()
                            model.setShowBatchCategoryDialog(true)
                        },
                        onMarkRead = {
                            clearSelectionBeforeAsync(
                                selectedIds = selectionState.selectedIds,
                                clear = selectionState::clear,
                                launch = { task ->
                                    model.clearOperationResults()
                                    scope.launchAcceptedLibraryOperation(
                                        desktopDependencies.notificationService,
                                        feedback = { model.state.value.operationFeedback },
                                        operation = task,
                                    )
                                },
                            ) { ids -> model.markMangaRead(ids, read = true) }
                        },
                        onMarkUnread = {
                            clearSelectionBeforeAsync(
                                selectedIds = selectionState.selectedIds,
                                clear = selectionState::clear,
                                launch = { task ->
                                    model.clearOperationResults()
                                    scope.launchAcceptedLibraryOperation(
                                        desktopDependencies.notificationService,
                                        feedback = { model.state.value.operationFeedback },
                                        operation = task,
                                    )
                                },
                            ) { ids -> model.markMangaRead(ids, read = false) }
                        },
                        onRemoveFromLibrary = {
                            removalTarget = selectedItems.toList()
                        },
                    )
                }
            },
        ) { scaffoldPadding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
                    .focusRequester(rootFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return@onPreviewKeyEvent false
                        when {
                            removalTarget != null -> {
                                removalTarget = null
                                true
                            }
                            selectionState.isInSelectionMode -> {
                                selectionState.clear()
                                true
                            }
                            searchQuery != null -> {
                                model.setSearchQuery(null)
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                if (selectionState.isInSelectionMode) {
                    LibrarySelectionTopBar(
                        selectedCount = selectionState.selectedIds.size,
                        onClose = selectionState::clear,
                        onSelectAll = { selectionState.selectAll(displayedItems.map { it.manga.id }, selectedCategoryId) },
                        onInvertSelection = { selectionState.invertVisible(displayedItems.map { it.manga.id }, selectedCategoryId) },
                    )
                    LibraryFilterDropdown(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false },
                        filter = filter,
                        availableTrackerIds = pageSnapshot.availableTrackerIds,
                        showIntervalCustomFilter = showIntervalCustomFilter(
                            BuildInfo.IS_NON_RELEASE_BUILD,
                            state.filter.skipOutsideReleasePeriod,
                        ),
                        onToggleFilter = model::toggleFilter,
                        onToggleTracking = model::toggleTrackingFilter,
                    )
                } else LibraryToolbar(
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
                    isUpdating = isUpdating,
                    displayMode = displayMode,
                    onDisplayModeChange = {
                        val cat = categoryTabs.getOrNull(selectedCategoryIndex)
                        model.setDisplayModeForCategory(cat?.id, it)
                    },
                    onOpenGlobalSearch = { navigator.push(GlobalSearchScreen(requireNotNull(searchQuery))) },
                    onOpenSettings = { navigator.push(LibrarySettingsScreen()) },
                    categories = categoryTabs,
                    selectedCategoryIndex = selectedCategoryIndex,
                    showCategoryTabs = state.showCategoryTabs,
                    showCategoryItemCounts = state.showCategoryItemCounts,
                    onCategoryChange = model::setSelectedCategoryIndex,
                    showIntervalCustomFilter = showIntervalCustomFilter(
                        BuildInfo.IS_NON_RELEASE_BUILD,
                        state.filter.skipOutsideReleasePeriod,
                    ),
                    showFilterMenu = showFilterMenu,
                    onShowFilterMenuChange = { showFilterMenu = it },
                    toolbarTitle = toolbarTitle,
                    onRandomManga = {
                        val randomId = pickRandomMangaId(displayedItems.map { it.manga.id })
                        if (randomId != null) {
                            navigator.push(MangaDetailScreen(randomId))
                        } else {
                            model.setOperationFeedback(MR.strings.desktop_ui_no_manga_match_your_filters.localized())
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            model.refreshLibrary(allItems, categoryTabs.getOrNull(selectedCategoryIndex)?.id)
                        }
                    },
                    onRefreshAll = {
                        scope.launch {
                            model.refreshLibrary(allItems)
                        }
                    },
                )

                if (categoryTabs.any { !it.isSystemCategory } && (state.showCategoryTabs || !searchQuery.isNullOrEmpty())) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedCategoryIndex,
                        modifier = Modifier.fillMaxWidth(),
                        edgePadding = 8.dp,
                    ) {
                        categoryTabs.forEachIndexed { index, cat ->
                            Tab(
                                selected = index == selectedCategoryIndex,
                                onClick = { model.setSelectedCategoryIndex(index) },
                                text = {
                                    Text(
                                        if (state.showCategoryItemCounts || !searchQuery.isNullOrEmpty()) {
                                            "${cat.name} (${model.visibleItems(cat.id).size})"
                                        } else {
                                            cat.name
                                        },
                                    )
                                },
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

                if (operationFeedback != null) {
                    Text(
                        text = operationFeedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                if (state.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (state.loadError != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            state.loadError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (allItems.isEmpty() && searchQuery.isNullOrEmpty() && !state.hasActiveFilters) {
                    val uriHandler = LocalUriHandler.current
                    EmptyLibrary(onGettingStarted = { uriHandler.openUri(GETTING_STARTED_URL) })
                } else if (displayedItems.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                !searchQuery.isNullOrEmpty() -> MR.strings.no_results_found.localized()
                                state.hasActiveFilters -> MR.strings.error_no_match.localized()
                                else -> MR.strings.information_no_manga_category.localized()
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else BoxWithConstraints(Modifier.fillMaxSize()) {
                    key(
                        selectedCategoryId, displayMode, displayedItems.map { it.id }, state.portraitColumns,
                        state.landscapeColumns, maxWidth, maxHeight,
                    ) {
                        val viewport = rememberLibraryViewportState(model, selectedCategoryId, displayedItems, displayMode)
                        when (displayMode) {
                            LibraryDisplayMode.COMPACT_GRID ->
                                LibraryGrid(
                                    items = displayedItems,
                                    scrollState = viewport.grid,
                                    minCardWidth = 120.dp,
                                    portraitColumns = state.portraitColumns,
                                    landscapeColumns = state.landscapeColumns,
                                    selectionState = selectionState,
                                    downloadedMangaIds = downloadedMangaIds,
                                    downloadCountsByManga = state.downloadCountsByManga,
                                    sourceLanguagesByManga = state.sourceLanguagesByManga,
                                    showDownloadBadge = state.showDownloadBadge,
                                    showUnreadBadge = state.showUnreadBadge,
                                    showLocalBadge = state.showLocalBadge,
                                    showLanguageBadge = state.showLanguageBadge,
                                    showContinueReadingButton = state.showContinueReadingButton,
                                    syncedResumeMangaIds = state.syncedResumeMangaIds,
                                    continueReadingMangaIds = state.continueReadingMangaIds,
                                    resolveCoverModel = desktopDependencies.customCoverStore::resolveModel,
                                    localMangaIds = state.localMangaIds,
                                    onContextMenu = { item -> model.setContextMenuManga(item) },
                                    onItemClick = onItemPrimaryClick,
                                    onItemLongClick = { item -> selectionState.toggle(item.manga.id, selectedCategoryId) },
                                    onContinueReading = onContinueReading,
                                )
                            LibraryDisplayMode.COMFORTABLE_GRID ->
                                LibraryGrid(
                                    items = displayedItems,
                                    scrollState = viewport.grid,
                                    minCardWidth = 160.dp,
                                    comfortable = true,
                                    portraitColumns = state.portraitColumns,
                                    landscapeColumns = state.landscapeColumns,
                                    selectionState = selectionState,
                                    downloadedMangaIds = downloadedMangaIds,
                                    downloadCountsByManga = state.downloadCountsByManga,
                                    sourceLanguagesByManga = state.sourceLanguagesByManga,
                                    showDownloadBadge = state.showDownloadBadge,
                                    showUnreadBadge = state.showUnreadBadge,
                                    showLocalBadge = state.showLocalBadge,
                                    showLanguageBadge = state.showLanguageBadge,
                                    showContinueReadingButton = state.showContinueReadingButton,
                                    syncedResumeMangaIds = state.syncedResumeMangaIds,
                                    continueReadingMangaIds = state.continueReadingMangaIds,
                                    resolveCoverModel = desktopDependencies.customCoverStore::resolveModel,
                                    localMangaIds = state.localMangaIds,
                                    onContextMenu = { item -> model.setContextMenuManga(item) },
                                    onItemClick = onItemPrimaryClick,
                                    onItemLongClick = { item -> selectionState.toggle(item.manga.id, selectedCategoryId) },
                                    onContinueReading = onContinueReading,
                                )
                            LibraryDisplayMode.LIST ->
                                LibraryList(
                                    items = displayedItems,
                                    scrollState = viewport.list,
                                    selectionState = selectionState,
                                    downloadedMangaIds = downloadedMangaIds,
                                    downloadCountsByManga = state.downloadCountsByManga,
                                    sourceLanguagesByManga = state.sourceLanguagesByManga,
                                    showDownloadBadge = state.showDownloadBadge,
                                    showUnreadBadge = state.showUnreadBadge,
                                    showLocalBadge = state.showLocalBadge,
                                    showLanguageBadge = state.showLanguageBadge,
                                    showContinueReadingButton = state.showContinueReadingButton,
                                    syncedResumeMangaIds = state.syncedResumeMangaIds,
                                    continueReadingMangaIds = state.continueReadingMangaIds,
                                    resolveCoverModel = desktopDependencies.customCoverStore::resolveModel,
                                    localMangaIds = state.localMangaIds,
                                    onContextMenu = { item -> model.setContextMenuManga(item) },
                                    onItemClick = onItemPrimaryClick,
                                    onItemLongClick = { item -> selectionState.toggle(item.manga.id, selectedCategoryId) },
                                    onContinueReading = onContinueReading,
                                )
                            LibraryDisplayMode.COVER_ONLY_GRID ->
                                LibraryGrid(
                                    items = displayedItems,
                                    scrollState = viewport.grid,
                                    minCardWidth = 120.dp,
                                    coverOnly = true,
                                    portraitColumns = state.portraitColumns,
                                    landscapeColumns = state.landscapeColumns,
                                    selectionState = selectionState,
                                    downloadedMangaIds = downloadedMangaIds,
                                    downloadCountsByManga = state.downloadCountsByManga,
                                    sourceLanguagesByManga = state.sourceLanguagesByManga,
                                    showDownloadBadge = state.showDownloadBadge,
                                    showUnreadBadge = state.showUnreadBadge,
                                    showLocalBadge = state.showLocalBadge,
                                    showLanguageBadge = state.showLanguageBadge,
                                    showContinueReadingButton = state.showContinueReadingButton,
                                    syncedResumeMangaIds = state.syncedResumeMangaIds,
                                    continueReadingMangaIds = state.continueReadingMangaIds,
                                    resolveCoverModel = desktopDependencies.customCoverStore::resolveModel,
                                    localMangaIds = state.localMangaIds,
                                    onContextMenu = { item -> model.setContextMenuManga(item) },
                                    onItemClick = onItemPrimaryClick,
                                    onItemLongClick = { item -> selectionState.toggle(item.manga.id, selectedCategoryId) },
                                    onContinueReading = onContinueReading,
                                )
                        }
                    }
                }
            }
        }
    }
}

private const val GETTING_STARTED_URL = "https://mihon.app/docs/guides/getting-started"

/** Page-level projection keeps production UI on the ScreenModel's complete evaluation context. */
internal fun libraryPageItems(model: LibraryScreenModel, categoryId: Long?): List<LibraryManga> =
    model.visibleItems(categoryId)

private fun LibraryReaderRequest.toDesktopReaderScreen() = DesktopReaderScreen(
    resumeSnapshot = resumeSnapshot,
    chapterTitle = chapterTitle,
    mangaTitle = mangaTitle,
    isWebtoon = false,
    sourceId = sourceId,
    chapterUrl = chapterUrl,
    chapterId = chapterId,
    mangaId = mangaId,
    mangaViewerFlags = mangaViewerFlags,
    chapters = chapters,
    currentChapterIndex = currentChapterIndex,
    initialPage = initialPage,
)

// ── Toolbar ───────────────────────────────────────────────────────────────────
