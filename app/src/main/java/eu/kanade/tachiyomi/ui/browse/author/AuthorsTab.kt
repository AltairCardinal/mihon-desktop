package eu.kanade.tachiyomi.ui.browse.author

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.CreatorDetails
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorCardProjection
import tachiyomi.domain.creator.model.CreatorCardWorkCandidate
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.CreatorWorkArchiveFilter
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.service.CreatorIdentityEditor
import tachiyomi.domain.creator.service.OpenCreatorWorkVersion
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AndroidMangaCreatorNavigator(
    private val extractCreators: ExtractCreatorsFromManga = ExtractCreatorsFromManga(),
    private val manageCreatorIdentity: ManageCreatorIdentity = Injekt.get(),
) {
    fun mentions(manga: Manga): List<CreatorMention> = extractCreators.await(manga)

    suspend fun resolve(manga: Manga, mention: CreatorMention): CreatorMentionResolution =
        manageCreatorIdentity.resolve(manga, mention)

    suspend fun select(manga: Manga, request: CreatorMentionResolution.Ambiguous, creatorId: Long) =
        manageCreatorIdentity.select(manga, request.mention, creatorId)

    suspend fun createDistinct(manga: Manga, request: CreatorMentionResolution.Ambiguous): Long =
        manageCreatorIdentity.createDistinct(manga, request.mention)
}

internal class AndroidCreatorOpenCoordinator(
    private val resolve: suspend (Manga, CreatorMention) -> CreatorMentionResolution,
    private val onResolved: (Long) -> Unit,
    private val onAmbiguous: (CreatorMentionResolution.Ambiguous) -> Unit,
    private val onFailure: suspend (Throwable) -> Boolean,
) {
    suspend fun open(manga: Manga, mention: CreatorMention) {
        while (true) {
            val resolution = try {
                resolve(manga, mention)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (onFailure(failure)) continue else return
            }
            when (resolution) {
                is CreatorMentionResolution.Resolved -> onResolved(resolution.creatorId)
                is CreatorMentionResolution.Ambiguous -> onAmbiguous(resolution)
            }
            return
        }
    }
}

@Composable
fun AndroidCreatorIdentityChooserDialog(
    request: CreatorMentionResolution.Ambiguous,
    onSelect: (Long) -> Unit,
    onCreateDistinct: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.desktop_ui_choose_author_identity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.options.forEach { option ->
                    TextButton(onClick = { onSelect(option.id) }) {
                        Text(option.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateDistinct) {
                Text(stringResource(MR.strings.desktop_ui_create_distinct_identity))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
fun Screen.authorsTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val model = rememberScreenModel { AndroidAuthorsScreenModel() }
    val state by model.state.collectAsState()
    return TabContent(
        titleRes = MR.strings.desktop_ui_authors,
        actions = persistentListOf(creatorSettingsAction(model.settingsEditor)),
        content = { padding, snackbar ->
            val settings by model.settingsEditor.state.collectAsState()
            val savedMessage = stringResource(MR.strings.creator_settings_saved)
            val followedPosition = remember(model) { model.scrollPosition(followedOnly = true) }
            val allAuthorsPosition = remember(model) { model.scrollPosition(followedOnly = false) }
            val followedListState = rememberLazyListState(
                initialFirstVisibleItemIndex = followedPosition.index,
                initialFirstVisibleItemScrollOffset = followedPosition.offset,
            )
            val allAuthorsListState = rememberLazyListState(
                initialFirstVisibleItemIndex = allAuthorsPosition.index,
                initialFirstVisibleItemScrollOffset = allAuthorsPosition.offset,
            )
            val listState = if (state.followedOnly) followedListState else allAuthorsListState
            var lastObservedQuery by remember { mutableStateOf(state.query) }
            LaunchedEffect(settings.savedRevision) {
                if (settings.savedRevision > 0) snackbar.showSnackbar(savedMessage)
            }
            LaunchedEffect(state.query) {
                if (state.query == lastObservedQuery) return@LaunchedEffect
                if (state.followedOnly) {
                    followedListState.scrollToItem(0)
                } else {
                    allAuthorsListState.scrollToItem(0)
                }
                model.resetScrollPosition(state.followedOnly)
                lastObservedQuery = state.query
            }
            LaunchedEffect(listState, state.followedOnly) {
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .distinctUntilChanged()
                    .collect { (index, offset) -> model.saveScrollPosition(state.followedOnly, index, offset) }
            }
            LaunchedEffect(state.cards.size, state.hasMore, state.loadingMore) {
                if (state.cards.isEmpty() || !state.hasMore || state.loadingMore) return@LaunchedEffect
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                    .distinctUntilChanged()
                    .collect { lastVisible ->
                        if (lastVisible != null && lastVisible >= state.cards.lastIndex - 4) {
                            model.loadNextPage()
                        }
                    }
            }
            CreatorSettingsDialog(model.settingsEditor)
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = model::search,
                    label = { Text(stringResource(MR.strings.desktop_ui_search_authors)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        modifier = Modifier.testTag("creator-tab-following"),
                        selected = state.followedOnly,
                        onClick = model::showFollowing,
                        label = { Text(stringResource(MR.strings.desktop_ui_followed)) },
                    )
                    FilterChip(
                        modifier = Modifier.testTag("creator-tab-all"),
                        selected = !state.followedOnly,
                        onClick = model::showAllAuthors,
                        label = { Text(stringResource(MR.strings.desktop_ui_all_authors)) },
                    )
                }
                if (state.loading && state.cards.isEmpty()) CircularProgressIndicator()

                if (state.loading &&
                    state.cards.isNotEmpty()
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.error?.let { error ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, modifier = Modifier.weight(1f))
                        TextButton(onClick = model::retry) {
                            Text(stringResource(MR.strings.action_retry))
                        }
                    }
                }
                if (state.cards.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("creator-author-list"),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(state.cards, key = { it.creator.id }) { card ->
                            CreatorCardRow(card) {
                                model.saveScrollPosition(
                                    state.followedOnly,
                                    listState.firstVisibleItemIndex,
                                    listState.firstVisibleItemScrollOffset,
                                )
                                navigator.push(AndroidAuthorDetailScreen(card.creator.id))
                            }
                        }
                        if (state.loadingMore) {
                            item(key = "creator-loading-more") { CircularProgressIndicator() }
                        }
                    }
                } else if (!state.loading && state.error == null) {
                    if (state.query.isNotBlank()) {
                        Text(stringResource(MR.strings.no_results_found))
                    } else if (state.followedOnly) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(MR.strings.creator_following_empty))
                            TextButton(onClick = model::showAllAuthors) {
                                Text(stringResource(MR.strings.creator_following_empty_action))
                            }
                        }
                    } else {
                        Text(stringResource(MR.strings.desktop_ui_no_authors_indexed_yet))
                    }
                }
            }
        },
    )
}

internal data class AuthorsState(
    val cards: List<CreatorCardProjection> = emptyList(),
    val query: String = "",
    val followedOnly: Boolean = true,
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

internal data class CreatorListScrollPosition(val index: Int = 0, val offset: Int = 0)

internal class AndroidAuthorsScreenModel(
    private val creatorArchive: CreatorArchive = Injekt.get(),
    private val getCreators: GetCreators = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    preferences: tachiyomi.domain.creator.service.CreatorDiscoveryPreferences = Injekt.get(),
    onSettingsSaved: suspend () -> Unit = {
        if (creatorArchive.getDueWatchSources(System.currentTimeMillis(), 1).isNotEmpty()) {
            eu.kanade.tachiyomi.data.library.CreatorDiscoveryJob.enqueue(Injekt.get<android.app.Application>())
        }
    },
) : ScreenModel {
    val settingsEditor = tachiyomi.domain.creator.service.CreatorSettingsEditor(
        preferences,
        screenModelScope,
        onSettingsSaved,
    )
    private val mutableState = MutableStateFlow(AuthorsState())
    val state: StateFlow<AuthorsState> = mutableState.asStateFlow()
    private var pageGeneration = 0L
    private var pageJob: Job? = null
    private var followedScrollPosition = CreatorListScrollPosition()
    private var allAuthorsScrollPosition = CreatorListScrollPosition()

    init {
        loadFirstPage()
        screenModelScope.launch {
            var previousSnapshot: Pair<List<Creator>, Set<Long>>? = null
            combine(
                getCreators.subscribe(),
                getCreators.subscribeFollowed().map { rows -> rows.mapTo(mutableSetOf()) { it.creatorId } },
            ) { creators, followedIds -> creators to followedIds }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (previousSnapshot != null && previousSnapshot != snapshot) refreshLoadedPages()
                    previousSnapshot = snapshot
                }
        }
    }

    fun search(query: String) {
        if (query == state.value.query) return
        mutableState.update { it.copy(query = query) }
        loadFirstPage()
    }

    fun showFollowing() = showTab(followedOnly = true)

    fun showAllAuthors() = showTab(followedOnly = false)

    fun scrollPosition(followedOnly: Boolean): CreatorListScrollPosition =
        if (followedOnly) followedScrollPosition else allAuthorsScrollPosition

    fun saveScrollPosition(followedOnly: Boolean, index: Int, offset: Int) {
        val position = CreatorListScrollPosition(index.coerceAtLeast(0), offset.coerceAtLeast(0))
        if (followedOnly) followedScrollPosition = position else allAuthorsScrollPosition = position
    }

    fun resetScrollPosition(followedOnly: Boolean) {
        saveScrollPosition(followedOnly, 0, 0)
    }

    private fun showTab(followedOnly: Boolean) {
        if (followedOnly == state.value.followedOnly) return
        mutableState.update { it.copy(followedOnly = followedOnly) }
        loadFirstPage()
    }

    fun loadNextPage() {
        val current = state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        val generation = pageGeneration
        val offset = current.cards.size
        mutableState.update { it.copy(loadingMore = true, error = null) }
        pageJob = screenModelScope.launch {
            try {
                val page = getPage(
                    offset = offset,
                    followedOnly = current.followedOnly,
                    query = current.query,
                )
                if (generation == pageGeneration) {
                    mutableState.update { latest ->
                        if (latest.query != current.query || latest.followedOnly != current.followedOnly) {
                            latest
                        } else {
                            latest.copy(
                                cards = (latest.cards + page.creators).distinctBy { it.creator.id },
                                hasMore = page.hasMore,
                                loading = false,
                                loadingMore = false,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == pageGeneration) {
                    mutableState.update { it.copy(loading = false, loadingMore = false, error = failure.message) }
                }
            }
        }
    }

    fun retry() = refreshLoadedPages()

    private fun loadFirstPage() {
        requestPages(targetCount = 0, preserveCards = false)
    }

    private fun refreshLoadedPages() {
        requestPages(targetCount = state.value.cards.size, preserveCards = true)
    }

    private fun requestPages(targetCount: Int, preserveCards: Boolean) {
        pageJob?.cancel()
        val generation = ++pageGeneration
        val current = state.value
        mutableState.update {
            it.copy(
                cards = if (preserveCards) it.cards else emptyList(),
                hasMore = if (preserveCards) it.hasMore else false,
                loading = true,
                loadingMore = false,
                error = null,
            )
        }
        pageJob = screenModelScope.launch {
            try {
                val cards = mutableListOf<CreatorCardProjection>()
                var hasMore: Boolean
                do {
                    val page = getPage(offset = cards.size, followedOnly = current.followedOnly, query = current.query)
                    cards += page.creators
                    hasMore = page.hasMore
                } while (hasMore && cards.size < targetCount.coerceAtLeast(1))

                if (generation == pageGeneration) {
                    mutableState.update {
                        it.copy(cards = cards, hasMore = hasMore, loading = false, loadingMore = false)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == pageGeneration) {
                    mutableState.update { it.copy(loading = false, loadingMore = false, error = failure.message) }
                }
            }
        }
    }

    private suspend fun getPage(offset: Int, followedOnly: Boolean, query: String) =
        creatorArchive.getCreatorCardProjectionPage(
            offset = offset,
            limit = CREATOR_CARD_PAGE_SIZE,
            followedOnly = followedOnly,
            preferredLanguages = sourcePreferences.enabledLanguages().get(),
            customCoverExists = { mangaId -> coverCache.getCustomCoverFile(mangaId).exists() },
            query = query,
        )
}

private const val CREATOR_CARD_PAGE_SIZE = 50

@Composable
private fun CreatorCardRow(card: CreatorCardProjection, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("creator-card-${card.creator.id}")
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().testTag("creator-card-${card.creator.id}-heading")) {
            Text(card.creator.displayName)
            Text(stringResource(MR.strings.creator_unique_work_count, card.uniqueWorkCount))
            if (card.followed) {
                Text(
                    stringResource(MR.strings.desktop_ui_followed),
                    modifier = Modifier.testTag("creator-card-${card.creator.id}-followed"),
                )
            }
            if (card.creator.aliases.isNotEmpty()) Text(card.creator.aliases.joinToString())
        }
        val representativeWorks = card.representativeWorks.take(3)
        if (representativeWorks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("creator-card-${card.creator.id}-representative-shelf"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                representativeWorks.forEachIndexed { index, work ->
                    Column(Modifier.width(52.dp).testTag("creator-card-${card.creator.id}-work-$index")) {
                        MangaCover.Book(
                            data = work.toMangaCover(),
                            modifier = Modifier.width(48.dp),
                        )
                        Text(work.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun CreatorCardWorkCandidate.toMangaCover() =
    tachiyomi.domain.manga.model.MangaCover(
        mangaId = coverRequest.mangaId ?: -coverRequest.sourceWorkId,
        sourceId = coverRequest.sourceId,
        isMangaFavorite = inLibrary,
        url = coverRequest.url,
        lastModified = coverRequest.lastModifiedAt,
    )

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
data class AndroidAuthorDetailScreen(val creatorId: Long) : Screen {
    @Composable override fun Content() {
        val model = rememberScreenModel {
            AndroidAuthorDetailScreenModel(
                creatorId,
                libraryPreferences = Injekt.get(),
            )
        }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var confirmUnfollow by remember { mutableStateOf(false) }
        var showDisplayModeMenu by remember { mutableStateOf(false) }
        var showSourceChooserFor by remember { mutableStateOf<Long?>(null) }
        var sourceFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
        var pendingSourceFocusRestore by remember { mutableStateOf<FocusRequester?>(null) }
        LaunchedEffect(pendingSourceFocusRestore) {
            pendingSourceFocusRestore?.let { requester ->
                withFrameNanos { }
                requester.requestFocus()
                pendingSourceFocusRestore = null
            }
        }
        LaunchedEffect(model) {
            model.openManga.collect {
                showSourceChooserFor = null
                sourceFocusRequester = null
                navigator.push(eu.kanade.tachiyomi.ui.manga.MangaScreen(it))
            }
        }
        if (confirmUnfollow) {
            AlertDialog(
                onDismissRequest = { confirmUnfollow = false },
                text = { Text(stringResource(MR.strings.creator_unfollow_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUnfollow = false
                        model.toggleFollow()
                    }) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmUnfollow = false }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }

        showSourceChooserFor?.let { workId ->
            state.archive.works.firstOrNull { it.workId == workId }?.let { work ->
                AndroidCreatorWorkSourceChooserDialog(
                    work = work,
                    model = model,
                    error = state.workOpenError,
                    opening = state.workOpening,
                    onDismiss = {
                        model.clearWorkOpenError()
                        pendingSourceFocusRestore = sourceFocusRequester
                        sourceFocusRequester = null
                        showSourceChooserFor = null
                    },
                )
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(state.details.creator?.displayName ?: stringResource(MR.strings.desktop_ui_authors))
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.strings.action_bar_up_description),
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = { showDisplayModeMenu = true },
                                modifier = Modifier.testTag("creator-display-mode-button"),
                            ) {
                                Icon(
                                    Icons.Default.GridView,
                                    contentDescription = stringResource(MR.strings.action_display_grid),
                                )
                            }
                            DropdownMenu(
                                expanded = showDisplayModeMenu,
                                onDismissRequest = { showDisplayModeMenu = false },
                            ) {
                                listOf(
                                    LibraryDisplayMode.List to MR.strings.action_display_list,
                                    LibraryDisplayMode.ComfortableGrid to MR.strings.action_display_comfortable_grid,
                                    LibraryDisplayMode.CompactGrid to MR.strings.action_display_grid,
                                ).forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (state.effectiveWorkDisplayMode == mode) {
                                                    "✓ ${stringResource(label)}"
                                                } else {
                                                    stringResource(label)
                                                },
                                            )
                                        },
                                        onClick = {
                                            model.setWorkDisplayMode(mode)
                                            showDisplayModeMenu = false
                                        },
                                        modifier = Modifier.testTag("creator-display-mode-option-${mode.serialize()}"),
                                    )
                                }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading) CircularProgressIndicator()
                state.workDisplayModeError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("creator-display-mode-error"),
                    )
                }
                CreatorIdentityHeader(model.identityEditor, state.details.creator?.displayName.orEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (state.followed) confirmUnfollow = true else model.toggleFollow() }) {
                        Text(
                            stringResource(
                                if (state.followed) MR.strings.desktop_ui_unfollow else MR.strings.desktop_ui_follow,
                            ),
                        )
                    }
                    Button(onClick = model::scan, enabled = !state.running) {
                        Text(stringResource(MR.strings.desktop_ui_check_new_works))
                    }
                }
                Text(
                    stringResource(
                        MR.strings.creator_work_version_count,
                        state.archive.works.size + state.archive.pending.size + state.archive.rejected.size,
                        state.archive.works.sumOf { it.versions.size } + state.archive.pending.size +
                            state.archive.rejected.size,
                    ),
                )
                state.error?.let { Text(it) }
                CreatorWorkFilters(
                    state.workFilter.query,
                    state.workFilter.sourceId,
                    (state.archive.works.flatMap { it.versions } + state.archive.pending + state.archive.rejected)
                        .associate { it.naturalKey.sourceId to model.sourceName(it) },
                    model::searchWorks,
                    model::filterSource,
                )
                Text(
                    stringResource(MR.strings.creator_unique_work_count, state.visibleArchive.works.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("creator-visible-work-count"),
                )
                if (state.visibleArchive.works.isEmpty() && state.visibleArchive.pending.isEmpty() &&
                    state.visibleArchive.rejected.isEmpty()
                ) {
                    Text(stringResource(MR.strings.creator_work_filter_empty))
                }
                val mode = state.effectiveWorkDisplayMode
                if (mode == LibraryDisplayMode.List) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(state.visibleArchive.works, key = { "work-${it.workId}" }) { work ->
                            val allVersions = state.allVersionsForWork(work.workId)
                            val focusRequester = remember(work.workId) { FocusRequester() }
                            CreatorArchiveWorkCard(
                                title = work.title,
                                version = work.versions.firstOrNull(),
                                favorite = allVersions.any { it.inLibrary },
                                key = work.workId.toString(),
                                mode = mode,
                                focusRequester = focusRequester,
                                onClick = {
                                    sourceFocusRequester = focusRequester
                                    showSourceChooserFor = work.workId
                                },
                            ) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    work.versions.forEach { version -> CreatorVersionButton(version, model) }
                                }
                            }
                        }
                        items(state.visibleArchive.pending, key = { "pending-${it.sourceWorkId}" }) { version ->
                            CreatorArchiveWorkRow(version.title, version.thumbnailUrl) {
                                CreatorVersionButton(version, model)
                                TextButton(onClick = { model.openReview(version) }) {
                                    Text(stringResource(MR.strings.desktop_ui_pending_work_suggestions))
                                }
                            }
                        }
                        items(state.visibleArchive.rejected, key = { "rejected-${it.sourceWorkId}" }) { version ->
                            CreatorArchiveWorkRow(version.title, version.thumbnailUrl) {
                                CreatorVersionButton(version, model)
                                Text(stringResource(MR.strings.desktop_ui_separated_work_versions))
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(
                            if (mode == LibraryDisplayMode.ComfortableGrid) 136.dp else 112.dp,
                        ),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.visibleArchive.works, key = { "work-${it.workId}" }) { work ->
                            val allVersions = state.allVersionsForWork(work.workId)
                            val focusRequester = remember(work.workId) { FocusRequester() }
                            CreatorArchiveWorkCard(
                                title = work.title,
                                version = work.versions.firstOrNull(),
                                favorite = allVersions.any { it.inLibrary },
                                key = work.workId.toString(),
                                mode = mode,
                                focusRequester = focusRequester,
                                onClick = {
                                    sourceFocusRequester = focusRequester
                                    showSourceChooserFor = work.workId
                                },
                            ) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    work.versions.forEach { version -> CreatorVersionButton(version, model) }
                                }
                            }
                        }
                        items(
                            state.visibleArchive.pending,
                            span = { GridItemSpan(maxLineSpan) },
                            key = { "pending-${it.sourceWorkId}" },
                        ) { version ->
                            CreatorArchiveWorkRow(version.title, version.thumbnailUrl) {
                                CreatorVersionButton(version, model)
                                TextButton(onClick = { model.openReview(version) }) {
                                    Text(stringResource(MR.strings.desktop_ui_pending_work_suggestions))
                                }
                            }
                        }
                        items(
                            state.visibleArchive.rejected,
                            span = { GridItemSpan(maxLineSpan) },
                            key = { "rejected-${it.sourceWorkId}" },
                        ) { version ->
                            CreatorArchiveWorkRow(version.title, version.thumbnailUrl) {
                                CreatorVersionButton(version, model)
                                Text(stringResource(MR.strings.desktop_ui_separated_work_versions))
                            }
                        }
                    }
                }
            }
        }
        state.reviewing?.let { version ->
            AlertDialog(
                onDismissRequest = model::closeReview,
                title = { Text(version.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(MR.strings.desktop_ui_pending_work_suggestions))
                        OutlinedTextField(
                            value = state.languageTag,
                            onValueChange = model::languageTag,
                            label = { Text(stringResource(MR.strings.desktop_ui_language_tag)) },
                        )
                        Button(onClick = model::setReadingLanguage) {
                            Text(stringResource(MR.strings.desktop_ui_correct_reading_language))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { model.decide(WorkDecisionState.CONFIRMED) }) {
                        Text(stringResource(MR.strings.desktop_ui_confirm_same_work))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { model.decide(WorkDecisionState.REJECTED) }) {
                        Text(stringResource(MR.strings.desktop_ui_reject_same_work))
                    }
                },
            )
        }
    }
}

@Composable
private fun AndroidCreatorWorkSourceChooserDialog(
    work: tachiyomi.domain.creator.model.CanonicalWorkArchiveGroup,
    model: AndroidAuthorDetailScreenModel,
    error: String?,
    opening: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(work.title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(MR.strings.creator_work_all_source_versions),
                    style = MaterialTheme.typography.bodySmall,
                )
                work.versions.forEach { version ->
                    val sourceName = model.sourceName(version)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !opening) { model.openVersion(version) }
                            .testTag("creator-source-version-${version.sourceWorkId}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(Modifier.width(48.dp).height(68.dp)) {
                                MangaCover.Book(
                                    data = version.toMangaCover(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (version.inLibrary) {
                                    Icon(
                                        Icons.Outlined.CollectionsBookmark,
                                        contentDescription = stringResource(MR.strings.desktop_ui_in_library),
                                        modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(16.dp),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(sourceName, style = MaterialTheme.typography.labelLarge)
                                if (model.isSourceMissing(version)) {
                                    Text(
                                        stringResource(MR.strings.desktop_ui_source_missing),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(version.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (version.chapterCount > 0) {
                                        stringResource(MR.strings.desktop_ui_chapter_count, version.chapterCount)
                                    } else {
                                        stringResource(MR.strings.creator_work_chapters_unknown)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    stringResource(MR.strings.creator_work_latest_date_unknown),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("creator-source-open-error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("creator-source-cancel")) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun CreatorArchiveWorkCard(
    title: String,
    version: SourceWorkArchiveVersion?,
    favorite: Boolean,
    key: String,
    mode: LibraryDisplayMode,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .focusRequester(focusRequester)
        .focusable()
        .clickable(onClick = onClick)
        .testTag("creator-work-card-$key")
    when (mode) {
        LibraryDisplayMode.List -> {
            Row(
                modifier = cardModifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CreatorArchiveWorkCover(
                    version = version,
                    title = title,
                    favorite = favorite,
                    key = key,
                    modifier = Modifier.width(64.dp).height(88.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("creator-work-$key"),
                    )
                    content()
                }
            }
        }
        LibraryDisplayMode.ComfortableGrid -> {
            Card(modifier = cardModifier, colors = CardDefaults.cardColors()) {
                Column {
                    CreatorArchiveWorkCover(
                        version = version,
                        title = title,
                        favorite = favorite,
                        key = key,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("creator-work-$key"),
                    )
                    content()
                }
            }
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            Card(modifier = cardModifier, colors = CardDefaults.cardColors()) {
                Column {
                    CreatorArchiveWorkCover(
                        version = version,
                        title = title,
                        favorite = favorite,
                        key = key,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                        compactTitle = true,
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun CreatorArchiveWorkCover(
    version: SourceWorkArchiveVersion?,
    title: String,
    favorite: Boolean,
    key: String,
    modifier: Modifier,
    compactTitle: Boolean = false,
) {
    Box(modifier = modifier.testTag("creator-cover-$key")) {
        version?.let {
            MangaCover.Book(
                data = it.toMangaCover(),
                modifier = Modifier.fillMaxSize().alpha(if (favorite) 0.34f else 1f),
            )
        } ?: Text(
            title,
            modifier = Modifier.align(Alignment.Center).padding(8.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (compactTitle) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))),
                ),
            )
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .testTag("creator-work-$key"),
            )
        }
        if (favorite) {
            Icon(
                Icons.Outlined.CollectionsBookmark,
                contentDescription = stringResource(MR.strings.desktop_ui_in_library),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(16.dp)
                    .testTag("creator-favorite-$key"),
            )
        }
    }
}

private fun SourceWorkArchiveVersion.toMangaCover() = tachiyomi.domain.manga.model.MangaCover(
    mangaId = mangaId ?: -sourceWorkId,
    sourceId = naturalKey.sourceId,
    isMangaFavorite = inLibrary,
    url = naturalKey.stableSourceUrl,
    lastModified = detailsFetchedAt ?: 0L,
)

@Composable
private fun CreatorArchiveWorkRow(
    title: String,
    thumbnailUrl: String?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        coil3.compose.AsyncImage(
            thumbnailUrl,
            null,
            modifier = Modifier.width(64.dp).height(88.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CreatorVersionButton(version: SourceWorkArchiveVersion, model: AndroidAuthorDetailScreenModel) {
    TextButton(onClick = { model.openVersion(version) }) {
        Text(
            model.sourceName(version) + if (model.isSourceMissing(version)) {
                " · " + stringResource(MR.strings.desktop_ui_source_missing)
            } else {
                ""
            },
        )
    }
}

internal data class AuthorState(
    val details: CreatorDetails = CreatorDetails(null, emptyList(), emptyList()),
    val archive: CreatorWorkArchive = CreatorWorkArchive(emptyList(), emptyList(), emptyList()),
    val followed: Boolean = false,
    val language: LanguageCertainty? = null,
    val workFilter: CreatorWorkArchiveFilter = CreatorWorkArchiveFilter(),
    val loading: Boolean = true,
    val running: Boolean = false,
    val error: String? = null,
    val reviewing: SourceWorkArchiveVersion? = null,
    val languageTag: String = "",
    val workDisplayModeOverride: LibraryDisplayMode? = null,
    val shelfDisplayMode: LibraryDisplayMode = LibraryDisplayMode.default,
    val workDisplayModeError: String? = null,
    val workOpenError: String? = null,
    val workOpening: Boolean = false,
) {
    fun allVersionsForWork(workId: Long): List<SourceWorkArchiveVersion> =
        archive.works.firstOrNull { it.workId == workId }?.versions.orEmpty()

    val effectiveWorkDisplayMode: LibraryDisplayMode
        get() = (workDisplayModeOverride ?: shelfDisplayMode).let { mode ->
            if (mode == LibraryDisplayMode.CoverOnlyGrid) LibraryDisplayMode.CompactGrid else mode
        }

    val visibleArchive: CreatorWorkArchive get() = workFilter.apply(archive).let { filtered ->
        fun matches(version: SourceWorkArchiveVersion) =
            language == null || version.readingLanguage.certainty == language
        filtered.copy(
            works = filtered.works.mapNotNull { work ->
                work.copy(versions = work.versions.filter(::matches)).takeIf { it.versions.isNotEmpty() }
            },
            pending = filtered.pending.filter(::matches),
            rejected = filtered.rejected.filter(::matches),
        )
    }
}

internal class AndroidAuthorDetailScreenModel(
    private val creatorId: Long,
    private val details: GetCreatorDetails = Injekt.get(),
    private val creators: GetCreators = Injekt.get(),
    private val follow: SetCreatorFollow = Injekt.get(),
    private val discovery: DiscoverCreatorWorks = Injekt.get(),
    private val archive: CreatorArchive = Injekt.get(),
    private val sources: SourceManager = Injekt.get(),
    identity: ManageCreatorIdentity = Injekt.get(),
    private val networkToLocal: NetworkToLocalManga = Injekt.get(),
    private val libraryPreferences: LibraryPreferences? = null,
) : ScreenModel {
    private val mutableState = MutableStateFlow(
        AuthorState(
            workDisplayModeOverride = libraryPreferences?.creatorWorkDisplayModeOverride()?.get(),
            shelfDisplayMode = libraryPreferences?.displayMode()?.get() ?: LibraryDisplayMode.default,
        ),
    )
    val state: StateFlow<AuthorState> = mutableState.asStateFlow()
    private val mutableOpenManga = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openManga = mutableOpenManga.asSharedFlow()
    fun openVersion(version: SourceWorkArchiveVersion) = screenModelScope.launch {
        if (mutableState.value.workOpening) return@launch
        mutableState.update { it.copy(workOpening = true, workOpenError = null) }
        runCatching {
            val opener = OpenCreatorWorkVersion { listed ->
                networkToLocal(
                    Manga.create().copy(
                        source = listed.naturalKey.sourceId,
                        url = listed.naturalKey.stableSourceUrl,
                        title = listed.title,
                        thumbnailUrl = listed.thumbnailUrl,
                    ),
                ).id
            }
            mutableOpenManga.emit(opener.await(version))
        }.onFailure { failure ->
            mutableState.update { it.copy(workOpenError = failure.message ?: failure::class.simpleName) }
        }
        mutableState.update { it.copy(workOpening = false) }
    }

    fun clearWorkOpenError() = mutableState.update { it.copy(workOpenError = null) }
    fun isSourceMissing(version: SourceWorkArchiveVersion): Boolean = sources.get(version.naturalKey.sourceId) == null
    fun sourceName(version: SourceWorkArchiveVersion): String = sources.getOrStub(version.naturalKey.sourceId).name
    val identityEditor = CreatorIdentityEditor(creatorId, identity, screenModelScope)
    private val activeCreatorId: Long get() = identityEditor.state.value.identity?.id ?: creatorId
    init {
        libraryPreferences?.let { preferences ->
            screenModelScope.launch {
                preferences.creatorWorkDisplayModeOverride().changes().collect { mode ->
                    mutableState.update { it.copy(workDisplayModeOverride = mode) }
                }
            }
            screenModelScope.launch {
                preferences.displayMode().changes().collect { mode ->
                    mutableState.update { it.copy(shelfDisplayMode = mode) }
                }
            }
        }
        screenModelScope.launch {
            identityEditor.state.map { it.identity }.distinctUntilChanged().collect { snapshot ->
                snapshot?.let { value ->
                    mutableState.update { it.copy(followed = value.followed) }
                    load()
                }
            }
        }
        screenModelScope.launch {
            archive.observe(creatorId).collect { value -> mutableState.update { it.copy(archive = value) } }
        }
        screenModelScope.launch {
            creators.subscribeFollowed().collect { rows ->
                mutableState.update {
                    it.copy(
                        followed = rows.any { row ->
                            row.creatorId ==
                                activeCreatorId
                        },
                    )
                }
            }
        }
        load()
    }
    fun toggleFollow() = screenModelScope.launch {
        runCatching { follow.await(activeCreatorId, !state.value.followed) }.onFailure(::fail)
    }
    fun scan() = screenModelScope.launch {
        mutableState.update { it.copy(running = true, error = null) }
        runCatching {
            discovery.await(activeCreatorId, sources.getCatalogueSources())
        }.onSuccess { mutableState.update { state -> state.copy(details = it) } }.onFailure(::fail)
        mutableState.update { it.copy(running = false) }
    }
    fun searchWorks(query: String) = mutableState.update { it.copy(workFilter = it.workFilter.copy(query = query)) }
    fun filterSource(
        sourceId: Long?,
    ) = mutableState.update { it.copy(workFilter = it.workFilter.copy(sourceId = sourceId)) }
    fun filter(
        value: LanguageCertainty,
    ) = mutableState.update {
        it.copy(
            language = value.takeUnless { current ->
                current ==
                    it.language
            },
        )
    }
    fun setWorkDisplayMode(mode: LibraryDisplayMode) {
        val preference = libraryPreferences?.creatorWorkDisplayModeOverride()
        if (preference == null) {
            mutableState.update { it.copy(workDisplayModeError = "Display mode preference is unavailable") }
            return
        }
        val previousMode = mutableState.value.workDisplayModeOverride
        runCatching { preference.set(mode) }
            .onSuccess {
                mutableState.update { it.copy(workDisplayModeOverride = mode, workDisplayModeError = null) }
            }
            .onFailure { error ->
                mutableState.update {
                    it.copy(
                        workDisplayModeOverride = previousMode,
                        workDisplayModeError = error.message ?: error::class.simpleName,
                    )
                }
            }
    }
    fun clearWorkDisplayModeError() = mutableState.update { it.copy(workDisplayModeError = null) }
    fun openReview(version: SourceWorkArchiveVersion) = mutableState.update { it.copy(reviewing = version) }
    fun closeReview() = mutableState.update { it.copy(reviewing = null, languageTag = "") }
    fun languageTag(value: String) = mutableState.update { it.copy(languageTag = value) }
    fun decide(state: WorkDecisionState) = screenModelScope.launch {
        val version = mutableState.value.reviewing ?: return@launch
        val now = System.currentTimeMillis()
        runCatching {
            val workId = version.decision?.workId ?: archive.createWork(version.title, activeCreatorId, null).id
            archive.decide(
                sourceWork = version.naturalKey,
                workId = workId,
                state = state,
                expectedDecidedAt = version.decision?.decidedAt,
                score = 1.0,
                evidence = "android-manual-review",
                decidedAt = now,
                idempotencyKey = "android-review:${version.sourceWorkId}:$workId:$now",
            )
        }.onSuccess { closeReview() }.onFailure(::fail)
    }
    fun setReadingLanguage() = screenModelScope.launch {
        val version = mutableState.value.reviewing ?: return@launch
        val tag = mutableState.value.languageTag.trim().takeIf(String::isNotEmpty) ?: return@launch
        runCatching {
            archive.setLanguage(
                ArchiveLanguageSubject.SourceWork(version.naturalKey),
                LanguageDimension.READING,
                tag,
                System.currentTimeMillis(),
            )
        }.onSuccess { closeReview() }.onFailure(::fail)
    }
    private fun load() = screenModelScope.launch {
        val requestedId = activeCreatorId
        runCatching {
            details.await(requestedId)
        }.onSuccess { value ->
            if (activeCreatorId == requestedId) mutableState.update { it.copy(details = value, loading = false) }
        }.onFailure(::fail)
    }
    private fun fail(
        error: Throwable,
    ) = mutableState.update { it.copy(error = error.message ?: error::class.simpleName, loading = false) }
}
