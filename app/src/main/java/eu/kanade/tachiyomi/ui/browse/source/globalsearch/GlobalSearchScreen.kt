package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.GlobalSearchScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import tachiyomi.domain.source.service.SourceMetadataSnapshot
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.domain.source.service.toSourceAppError
import tachiyomi.presentation.core.screens.LoadingScreen

class GlobalSearchScreen(
    val searchQuery: String = "",
    private val extensionFilter: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel {
            GlobalSearchScreenModel(
                initialQuery = searchQuery,
                initialExtensionFilter = extensionFilter,
            )
        }
        val state by screenModel.state.collectAsState()
        var showSingleLoadingScreen by remember {
            mutableStateOf(searchQuery.isNotEmpty() && !extensionFilter.isNullOrEmpty() && state.total == 1)
        }

        if (showSingleLoadingScreen) {
            LoadingScreen()

            LaunchedEffect(state.items) {
                when (val result = state.items.values.singleOrNull()) {
                    SearchItemResult.Loading -> return@LaunchedEffect
                    is SearchItemResult.Success -> {
                        val manga = result.result.singleOrNull()
                        if (manga != null) {
                            navigator.replace(MangaScreen(manga.id, true))
                        } else {
                            // Backoff to result screen
                            showSingleLoadingScreen = false
                        }
                    }
                    else -> showSingleLoadingScreen = false
                }
            }
        } else {
            GlobalSearchScreen(
                state = state,
                navigateUp = navigator::pop,
                onChangeSearchQuery = screenModel::updateSearchQuery,
                onSearch = { screenModel.search() },
                getManga = { screenModel.getManga(it) },
                onChangeSearchFilter = screenModel::setSourceFilter,
                onToggleResults = screenModel::toggleFilterResults,
                onClickSource = { source ->
                    state.metadata.firstOrNull { it.source === source }?.sourceId?.let { id ->
                        navigator.push(BrowseSourceScreen(id, state.searchQuery))
                    }
                },
                onClickItem = { navigator.push(MangaScreen(it.id, true)) },
                onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
                onErrorAction = recovery@{ source, pageError ->
                    when (pageError.recoveryAction) {
                        SourceRecoveryAction.Retry -> screenModel.retry()
                        SourceRecoveryAction.OpenLogin -> {
                            if (!screenModel.isCurrentSourceError(source, pageError, state.generation)) return@recovery
                            val metadata = state.metadata.firstOrNull { it.source === source } ?: return@recovery
                            val target = try {
                                globalSearchRecoveryScreen(source, pageError, metadata)
                            } catch (error: Exception) {
                                error.rethrowSourceControlFailure()
                                screenModel.reportSourceRecoveryFailure(
                                    source,
                                    pageError,
                                    state.generation,
                                    error.toSourceAppError(),
                                )
                                null
                            }
                            if (screenModel.isCurrentSourceError(source, pageError, state.generation)) {
                                target?.let(navigator::push)
                            }
                        }
                        SourceRecoveryAction.None -> Unit
                    }
                },
            )
        }
    }
}

internal fun globalSearchRecoveryScreen(source: Source, pageError: SourcePageError): Screen? {
    if (pageError.recoveryAction != SourceRecoveryAction.OpenLogin || source !is HttpSource) return null
    val id = guardSourceOperation(SourceOperationPhase.METADATA) { source.id }
    val name = guardSourceOperation(SourceOperationPhase.METADATA) { source.name }
    return globalSearchRecoveryScreen(source, pageError, SourceMetadataSnapshot(source, id, name, null, null))
}

internal fun globalSearchRecoveryScreen(
    source: Source,
    pageError: SourcePageError,
    metadata: SourceMetadataSnapshot,
): Screen? {
    if (pageError.recoveryAction != SourceRecoveryAction.OpenLogin || source !is HttpSource ||
        metadata.source !== source || metadata.sourceId == null || metadata.error != null
    ) {
        return null
    }
    val url = guardSourceOperation(SourceOperationPhase.METADATA) { source.baseUrl }
    return WebViewScreen(url = url, initialTitle = metadata.name, sourceId = metadata.sourceId)
}
