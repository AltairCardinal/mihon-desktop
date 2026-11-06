package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.paging.compose.LazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.CatalogResultFilterControl
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.CatalogResultFilter
import tachiyomi.domain.source.service.SourceMetadataSnapshot
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.domain.source.service.SourceResultMetadata
import tachiyomi.domain.source.service.toSourceAppError
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel { BrowseSourceScreenModel(sourceId, listingQuery) }
        val state by screenModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (screenModel.source is StubSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }
        val mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
        val isCurrentRecovery: (SourcePageError) -> Boolean = { expected ->
            val loadError = (mangaList.loadState.refresh as? androidx.paging.LoadState.Error)
                ?: (mangaList.loadState.append as? androidx.paging.LoadState.Error)
            val current = state.presentationError ?: (loadError?.error as? SourcePageException)?.pageError
            screenModel.isCurrentPresentation(state) && current === expected
        }
        val resultMetadata = mangaList.itemSnapshotList.items.map { item ->
            val manga by item.collectAsState()
            remember(manga) { SourceResultMetadata.from(manga) }
        }

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = f@{
            if (!screenModel.isCurrentPresentation(state)) return@f
            val metadata = state.sourceMetadata ?: return@f
            val target = try {
                browseSourceWebViewScreen(screenModel.source, metadata)
            } catch (error: Exception) {
                error.rethrowSourceControlFailure()
                screenModel.reportPresentationFailure(state, error.toSourceAppError())
                null
            }
            if (screenModel.isCurrentPresentation(state)) target?.let(navigator::push)
        }

        LaunchedEffect(screenModel.source, state.sourceMetadata) {
            assistUrl = null
            val metadata = state.sourceMetadata ?: return@LaunchedEffect
            val url = try {
                browseSourceWebUrl(screenModel.source, metadata)
            } catch (error: Exception) {
                error.rethrowSourceControlFailure()
                screenModel.reportPresentationFailure(state, error.toSourceAppError())
                null
            }
            if (screenModel.isCurrentPresentation(state)) assistUrl = url
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        source = screenModel.source,
                        sourceTitle = state.sourceMetadata?.name ?: stringResource(MR.strings.unknown),
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                        onSearch = screenModel::search,
                    )

                    BrowseSourceListingChips(
                        supportsLatest = state.supportsLatest,
                        listing = state.listing,
                        hasFilters = state.filters.isNotEmpty(),
                        onListingSelected = {
                            screenModel.resetFilters()
                            screenModel.setListing(it)
                        },
                        onFilterClick = screenModel::openFilterSheet,
                        catalogFilter = state.catalogFilter,
                        resultMetadata = resultMetadata,
                        onCatalogFilterChange = screenModel::setCatalogFilter,
                    )

                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            BrowseSourceScreenContent(
                onRetry = {
                    if (state.filterError != null || state.presentationError != null) {
                        screenModel.resetFilters()
                    } else {
                        mangaList.retry()
                    }
                },
                source = screenModel.source,
                presentationError = state.presentationError,
                onErrorAction = recovery@{ pageError ->
                    if (pageError.recoveryAction != SourceRecoveryAction.OpenLogin ||
                        !isCurrentRecovery(pageError)
                    ) {
                        return@recovery
                    }
                    val metadata = state.sourceMetadata ?: return@recovery
                    val target = try {
                        browseSourceRecoveryScreen(screenModel.source, pageError, metadata)
                    } catch (error: Exception) {
                        error.rethrowSourceControlFailure()
                        screenModel.reportPresentationFailure(state, error.toSourceAppError())
                        null
                    }
                    if (isCurrentRecovery(pageError)) target?.let(navigator::push)
                },
                mangaList = mangaList,
                navigator = navigator,
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                catalogFilter = state.catalogFilter,
                hideInLibraryItems = screenModel.hideInLibraryItems,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                onMangaLongClick = { manga ->
                    scope.launchIO {
                        val duplicates = screenModel.getDuplicateLibraryManga(manga)
                        when {
                            manga.favorite -> screenModel.setDialog(BrowseSourceScreenModel.Dialog.RemoveManga(manga))
                            duplicates.isNotEmpty() -> screenModel.setDialog(
                                BrowseSourceScreenModel.Dialog.AddDuplicateManga(manga, duplicates),
                            )
                            else -> screenModel.addFavorite(manga)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
            )
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                )
            }
            is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.changeMangaFavorite(dialog.manga)
                        screenModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            else -> {}
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

@Composable
internal fun BrowseSourceListingChips(
    source: Source? = null,
    listing: Listing,
    hasFilters: Boolean,
    onListingSelected: (Listing) -> Unit,
    onFilterClick: () -> Unit,
    catalogFilter: CatalogResultFilter = CatalogResultFilter(),
    resultMetadata: List<SourceResultMetadata> = emptyList(),
    onCatalogFilterChange: (CatalogResultFilter) -> Unit = {},
    supportsLatest: Boolean? = null,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        CatalogResultFilterControl(
            filter = catalogFilter,
            metadata = resultMetadata,
            onApply = onCatalogFilterChange,
        )
        FilterChip(
            selected = listing == Listing.Popular,
            onClick = {
                onListingSelected(Listing.Popular)
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Favorite,
                    contentDescription = null,
                    modifier = Modifier
                        .size(FilterChipDefaults.IconSize),
                )
            },
            label = {
                Text(text = stringResource(MR.strings.popular))
            },
        )
        val showLatest = supportsLatest ?: source?.let {
            guardSourceOperation(SourceOperationPhase.METADATA) { it.supportsLatest }
        } ?: false
        if (showLatest) {
            FilterChip(
                selected = listing == Listing.Latest,
                onClick = {
                    onListingSelected(Listing.Latest)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.NewReleases,
                        contentDescription = null,
                        modifier = Modifier
                            .size(FilterChipDefaults.IconSize),
                    )
                },
                label = {
                    Text(text = stringResource(MR.strings.latest))
                },
            )
        }
        if (hasFilters) {
            FilterChip(
                selected = listing is Listing.Search,
                onClick = onFilterClick,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = null,
                        modifier = Modifier
                            .size(FilterChipDefaults.IconSize),
                    )
                },
                label = {
                    Text(text = stringResource(MR.strings.action_filter))
                },
            )
        }
    }
}

@Composable
internal fun BrowseSourceScreenContent(
    source: Source,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    navigator: Navigator,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onLocalSourceHelpClick: () -> Unit,
    onMangaClick: (Manga) -> Unit = { navigator.push(MangaScreen(it.id, true)) },
    onMangaLongClick: (Manga) -> Unit,
    catalogFilter: CatalogResultFilter = CatalogResultFilter(),
    hideInLibraryItems: Boolean = false,
    onRetry: () -> Unit = mangaList::retry,
    presentationError: SourcePageError? = null,
    onErrorAction: (SourcePageError) -> Unit = { pageError ->
        browseSourceRecoveryScreen(source, pageError)?.let(navigator::push)
    },
) {
    BrowseSourceContent(
        source = source,
        mangaList = mangaList,
        columns = columns,
        displayMode = displayMode,
        snackbarHostState = snackbarHostState,
        contentPadding = contentPadding,
        onWebViewClick = onWebViewClick,
        onErrorAction = onErrorAction,
        presentationError = presentationError,
        onHelpClick = onHelpClick,
        onLocalSourceHelpClick = onLocalSourceHelpClick,
        onMangaClick = onMangaClick,
        onMangaLongClick = onMangaLongClick,
        catalogFilter = catalogFilter,
        hideInLibraryItems = hideInLibraryItems,
        onRetry = onRetry,
    )
}

internal fun browseSourceRecoveryScreen(
    source: Source,
    pageError: SourcePageError,
): Screen? {
    if (pageError.recoveryAction != SourceRecoveryAction.OpenLogin) return null
    if (source !is HttpSource) return null
    val id = guardSourceOperation(SourceOperationPhase.METADATA) { source.id }
    val name = guardSourceOperation(SourceOperationPhase.METADATA) { source.name }
    return browseSourceRecoveryScreen(source, pageError, SourceMetadataSnapshot(source, id, name, null, null))
}

internal fun browseSourceRecoveryScreen(
    source: Source,
    pageError: SourcePageError,
    metadata: SourceMetadataSnapshot,
): Screen? {
    if (pageError.recoveryAction != SourceRecoveryAction.OpenLogin) return null
    return browseSourceWebViewScreen(source, metadata)
}

private fun browseSourceWebUrl(source: Source, metadata: SourceMetadataSnapshot): String? {
    if (source !is HttpSource || metadata.source !== source || metadata.sourceId == null || metadata.error != null) {
        return null
    }
    return guardSourceOperation(SourceOperationPhase.METADATA) { source.baseUrl }
}

private fun browseSourceWebViewScreen(source: Source, metadata: SourceMetadataSnapshot): Screen? {
    val url = browseSourceWebUrl(source, metadata) ?: return null
    return WebViewScreen(url = url, initialTitle = metadata.name, sourceId = checkNotNull(metadata.sourceId))
}
