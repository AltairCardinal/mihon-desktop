package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.browse.components.catalogResultVisibleIndices
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.presentation.util.sourceErrorMessageResource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.browse.SourcePageException
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.StateFlow
import mihon.domain.error.AppError
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.CatalogResultFilter
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceContent(
    source: Source?,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onWebViewClick: () -> Unit,
    onErrorAction: (SourcePageError) -> Unit = {},
    onHelpClick: () -> Unit,
    onLocalSourceHelpClick: () -> Unit,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    catalogFilter: CatalogResultFilter = CatalogResultFilter(),
    hideInLibraryItems: Boolean = false,
) {
    val context = LocalContext.current

    val errorState = mangaList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: mangaList.loadState.append.takeIf { it is LoadState.Error }
    val pageError = (errorState as? LoadState.Error)?.error
        ?.let { it as? SourcePageException }
        ?.pageError

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        when (val error = (state.error as? SourcePageException)?.pageError?.error) {
            is AppError.Network -> context.stringResource(MR.strings.exception_offline)
            is AppError.Authentication, is AppError.Forbidden -> context.stringResource(
                checkNotNull(sourceErrorMessageResource(error)),
            )
            null -> with(context) { state.error.formattedMessage }
            else -> context.stringResource(MR.strings.unknown_error)
        }
    }

    LaunchedEffect(errorState) {
        if (mangaList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = when (pageError?.recoveryAction) {
                    SourceRecoveryAction.OpenLogin -> context.stringResource(MR.strings.login)
                    SourceRecoveryAction.None -> null
                    SourceRecoveryAction.Retry, null -> context.stringResource(MR.strings.action_retry)
                },
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> performBrowseSourceRecovery(
                    pageError = pageError,
                    retry = mangaList::retry,
                    openLogin = onErrorAction,
                )
            }
        }
    }

    if (mangaList.itemCount == 0 && mangaList.loadState.refresh is LoadState.Loading) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (mangaList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = if (source is LocalSource) {
                persistentListOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.local_source_help_guide,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                listOfNotNull(
                    when (pageError?.recoveryAction) {
                        SourceRecoveryAction.OpenLogin -> EmptyScreenAction(
                            stringRes = MR.strings.login,
                            icon = Icons.Outlined.Public,
                            onClick = {
                                performBrowseSourceRecovery(pageError, mangaList::retry, onErrorAction)
                            },
                        )
                        SourceRecoveryAction.None -> null
                        SourceRecoveryAction.Retry, null -> EmptyScreenAction(
                            stringRes = MR.strings.action_retry,
                            icon = Icons.Outlined.Refresh,
                            onClick = mangaList::retry,
                        )
                    },
                    EmptyScreenAction(
                        stringRes = MR.strings.action_open_in_web_view,
                        icon = Icons.Outlined.Public,
                        onClick = onWebViewClick,
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.label_help,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onHelpClick,
                    ),
                ).toPersistentList()
            },
        )

        return
    }

    val loadedManga = mangaList.itemSnapshotList.items.map { it.collectAsState().value }
    val visibleIndices = remember(loadedManga, catalogFilter, hideInLibraryItems) {
        catalogResultVisibleIndices(loadedManga, catalogFilter, hideInLibraryItems)
    }
    // Never send access hints while inspecting a filtered snapshot. The explicit action below
    // accesses the final raw item once, even when no items match or a page contains only duplicates.
    val manualPaging = catalogFilter.isActive || hideInLibraryItems || visibleIndices.size != loadedManga.size
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (manualPaging) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    stringResource(MR.strings.catalog_result_scope, loadedManga.size, visibleIndices.size),
                    modifier = Modifier.testTag("catalog-result-scope"),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (visibleIndices.isEmpty()) {
                    Text(
                        stringResource(MR.strings.catalog_result_no_match),
                        modifier = Modifier.testTag("catalog-result-no-match"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!mangaList.loadState.append.endOfPaginationReached) {
                    TextButton(
                        onClick = {
                            if (errorState is LoadState.Error) {
                                performBrowseSourceRecovery(pageError, mangaList::retry, onErrorAction)
                            } else {
                                mangaList[mangaList.itemCount - 1]
                            }
                        },
                        enabled = mangaList.loadState.append !is LoadState.Loading &&
                            mangaList.loadState.refresh !is LoadState.Loading &&
                            pageError?.recoveryAction != SourceRecoveryAction.None,
                        modifier = Modifier.testTag("catalog-result-continue"),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    pageError?.recoveryAction == SourceRecoveryAction.OpenLogin -> MR.strings.login
                                    errorState is LoadState.Error -> MR.strings.action_retry
                                    else -> MR.strings.catalog_result_continue
                                },
                            ),
                        )
                    }
                }
            }
        }
        when (displayMode) {
            LibraryDisplayMode.ComfortableGrid -> {
                BrowseSourceComfortableGrid(
                    mangaList = mangaList,
                    columns = columns,
                    contentPadding = PaddingValues(),
                    onMangaClick = onMangaClick,
                    onMangaLongClick = onMangaLongClick,
                    visibleIndices = visibleIndices,
                    automaticPaging = !manualPaging,
                )
            }
            LibraryDisplayMode.List -> {
                BrowseSourceList(
                    mangaList = mangaList,
                    contentPadding = PaddingValues(),
                    onMangaClick = onMangaClick,
                    onMangaLongClick = onMangaLongClick,
                    visibleIndices = visibleIndices,
                    automaticPaging = !manualPaging,
                )
            }
            LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
                BrowseSourceCompactGrid(
                    mangaList = mangaList,
                    columns = columns,
                    contentPadding = PaddingValues(),
                    onMangaClick = onMangaClick,
                    onMangaLongClick = onMangaLongClick,
                    visibleIndices = visibleIndices,
                    automaticPaging = !manualPaging,
                )
            }
        }
    }
}

internal fun performBrowseSourceRecovery(
    pageError: SourcePageError?,
    retry: () -> Unit,
    openLogin: (SourcePageError) -> Unit,
) {
    when (pageError?.recoveryAction) {
        SourceRecoveryAction.OpenLogin -> openLogin(pageError)
        SourceRecoveryAction.None -> Unit
        SourceRecoveryAction.Retry, null -> retry()
    }
}

@Composable
internal fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(MR.strings.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
