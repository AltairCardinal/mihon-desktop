package mihon.desktop.ui.authors

import tachiyomi.i18n.MR
import mihon.desktop.LocalDesktopUiDependencies
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import mihon.desktop.domain.CreatorDiscoveryRunScope
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.domain.task.TaskStatus
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.SourceDateQualityStatus
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkPresentationGroup
import tachiyomi.domain.creator.service.CreatorLibraryIndexState
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.library.model.LibraryDisplayMode
import mihon.desktop.image.desktopSourceImageModel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID




object AuthorsTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.Person)
            return remember {
                TabOptions(
                    index = 3u,
                    title = MR.strings.desktop_ui_authors.localized(),
                    icon = icon,
                )
            }
        }

    @Composable
    override fun Content() {
        val tabNavigator = LocalTabNavigator.current
        val selectedTab = tabNavigator.current
        var wasSelected by remember(tabNavigator) { mutableStateOf(selectedTab == AuthorsTab) }
        var activationToken by remember(tabNavigator) { mutableStateOf(UUID.randomUUID().toString()) }

        LaunchedEffect(selectedTab) {
            val isSelected = selectedTab == AuthorsTab
            if (isSelected && !wasSelected) activationToken = UUID.randomUUID().toString()
            wasSelected = isSelected
        }

        androidx.compose.runtime.key(activationToken) {
            Navigator(AuthorsRootScreen(activationToken)) {
                CurrentScreen()
            }
        }
    }
}

class AuthorsRootScreen(private val tabActivationToken: String = "initial") : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val model = rememberScreenModel { AuthorsScreenModelFactory.root(dependencies) }
        val state by model.state.collectAsState()
        val indexPresentation = authorIndexPresentation(state.indexState, state.cards.size)
        val snackbar = remember { androidx.compose.material3.SnackbarHostState() }

        LaunchedEffect(tabActivationToken) { model.onTabActivated(tabActivationToken) }

        val followedPosition = model.scrollPosition(followedOnly = true)
        val allAuthorsPosition = model.scrollPosition(followedOnly = false)
        val followedListState = androidx.compose.runtime.key(state.queryResetRevision) {
            androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = followedPosition.index,
                initialFirstVisibleItemScrollOffset = followedPosition.offset,
            )
        }
        val allAuthorsListState = androidx.compose.runtime.key(state.queryResetRevision) {
            androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = allAuthorsPosition.index,
                initialFirstVisibleItemScrollOffset = allAuthorsPosition.offset,
            )
        }
        val listState = if (state.followedOnly) followedListState else allAuthorsListState

        fun saveActiveScrollPosition() {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return
            model.saveScrollPosition(
                followedOnly = state.followedOnly,
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                lastVisibleIndex = lastVisibleIndex,
            )
        }

        LaunchedEffect(followedListState) {
            androidx.compose.runtime.snapshotFlow {
                followedListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { lastVisibleIndex ->
                    Triple(
                        followedListState.firstVisibleItemIndex,
                        followedListState.firstVisibleItemScrollOffset,
                        lastVisibleIndex,
                    )
                }
            }.distinctUntilChanged().collect { position ->
                position?.let { (index, offset, lastVisibleIndex) ->
                    model.saveScrollPosition(
                        followedOnly = true,
                        index = index,
                        offset = offset,
                        lastVisibleIndex = lastVisibleIndex,
                    )
                }
            }
        }
        LaunchedEffect(allAuthorsListState) {
            androidx.compose.runtime.snapshotFlow {
                allAuthorsListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { lastVisibleIndex ->
                    Triple(
                        allAuthorsListState.firstVisibleItemIndex,
                        allAuthorsListState.firstVisibleItemScrollOffset,
                        lastVisibleIndex,
                    )
                }
            }.distinctUntilChanged().collect { position ->
                position?.let { (index, offset, lastVisibleIndex) ->
                    model.saveScrollPosition(
                        followedOnly = false,
                        index = index,
                        offset = offset,
                        lastVisibleIndex = lastVisibleIndex,
                    )
                }
            }
        }
        LaunchedEffect(
            listState,
            state.cards.size,
            state.hasMore,
            state.loadingMore,
            state.query,
            state.followedOnly,
        ) {
            if (state.cards.isEmpty() || !state.hasMore) return@LaunchedEffect
            androidx.compose.runtime.snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            }.collect { lastVisibleIndex ->
                if (lastVisibleIndex >= state.cards.size - CREATOR_CARD_PREFETCH_DISTANCE) {
                    model.loadNextPage()
                }
            }
        }

        model.settingsEditor?.let { editor ->
            val settings by editor.state.collectAsState()
            androidx.compose.runtime.LaunchedEffect(settings.savedRevision) {
                if (settings.savedRevision > 0) snackbar.showSnackbar(MR.strings.creator_settings_saved.localized())
            }
        }

        Scaffold(
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.desktop_ui_authors.localized()) },
                    actions = { model.settingsEditor?.let { CreatorSettingsButton(it) } },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.followedOnly,
                        onClick = {
                            saveActiveScrollPosition()
                            model.showFollowing()
                        },
                        label = { Text(MR.strings.desktop_ui_followed.localized()) },
                        modifier = Modifier.testTag("creator-tab-following"),
                    )
                    FilterChip(
                        selected = !state.followedOnly,
                        onClick = {
                            saveActiveScrollPosition()
                            model.showAllAuthors()
                        },
                        label = { Text(MR.strings.desktop_ui_all_authors.localized()) },
                        modifier = Modifier.testTag("creator-tab-all"),
                    )
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = model::search,
                    placeholder = { Text(MR.strings.desktop_ui_search_authors.localized()) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.error?.let { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        Button(onClick = model::retry) { Text(MR.strings.action_retry.localized()) }
                    }
                }

                when (val presentation = indexPresentation) {
                    is AuthorIndexPresentation.Indexing -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                if (presentation.totalManga == 0) {
                                    MR.strings.desktop_ui_author_index_preparing.localized()
                                } else {
                                    MR.strings.desktop_ui_author_index_progress.localized(
                                        Locale.getDefault(),
                                        presentation.processedManga,
                                        presentation.totalManga,
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (presentation.totalManga > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        presentation.processedManga.toFloat() / presentation.totalManga.toFloat()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    is AuthorIndexPresentation.Failed -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                MR.strings.desktop_ui_author_index_failed.localized(
                                    Locale.getDefault(),
                                    presentation.message,
                                ),
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(onClick = model::retryIndex) { Text(MR.strings.action_retry.localized()) }
                        }
                    }
                    else -> Unit
                }

                if (state.cards.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("creator-author-list"),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(state.cards, key = { it.creator.id }) { card ->
                            CreatorCardRow(card, dependencies.customCoverStore) {
                                saveActiveScrollPosition()
                                navigator.push(AuthorDetailScreen(card.creator.id))
                            }
                        }
                        if (state.loadingMore) {
                            item(key = "creator-loading-more") {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp))
                            }
                        }
                    }
                } else if (!state.loading && state.error == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        when {
                            state.query.isNotBlank() -> Text(MR.strings.no_results_found.localized())
                            indexPresentation == AuthorIndexPresentation.EmptyLibrary ->
                                Text(MR.strings.desktop_ui_author_index_empty_library.localized())
                            indexPresentation is AuthorIndexPresentation.Failed -> Unit
                            state.followedOnly -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(MR.strings.creator_following_empty.localized())
                                    TextButton(onClick = model::showAllAuthors) {
                                        Text(MR.strings.creator_following_empty_action.localized())
                                    }
                                }
                            }
                            indexPresentation == AuthorIndexPresentation.NoAuthorMetadata ->
                                Text(MR.strings.desktop_ui_author_index_no_metadata.localized())
                            else -> Text(MR.strings.desktop_ui_no_authors_indexed_yet.localized())
                        }
                    }
                }
            }
        }
    }
}

data class AuthorDetailScreen(
    val creatorId: Long,
    val collectOnOpen: Boolean = false,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val desktopDependencies = LocalDesktopUiDependencies.current
        val model = rememberScreenModel {
            AuthorsScreenModelFactory.detail(creatorId, collectOnOpen, desktopDependencies)
        }
        val state by model.state.collectAsState()
        val creator = state.details.creator
        val candidates = state.details.candidates
        val mangaLinks = state.details.mangaLinks
        val mangaTitles = state.details.mangaTitles
        val workArchive = state.visibleWorkArchive
        val presentationCards = state.visiblePresentationCards
        val pendingPresentationVersions = state.visiblePendingVersions
        val discoveryState = state.discovery
        val sourceCheckpoints = state.checkpoints
        val allCreators = state.allCreators
        val manualAliases = state.manualAliases
        val identityActionError = state.error
        val identityActionRunning = state.actionRunning
        var showSplitDialog by remember { mutableStateOf(false) }
        var confirmUnfollow by remember { mutableStateOf(false) }
        var showDisplayModeMenu by remember { mutableStateOf(false) }
        var showSourceChooserFor by remember { mutableStateOf<String?>(null) }
        var pendingFocusGroupKey by remember { mutableStateOf<String?>(null) }
        var pendingFocusSourceKey by remember { mutableStateOf<SourceWorkNaturalKey?>(null) }
        val cardFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
        val returnFocusRequester = remember { FocusRequester() }
        val snackbarHostState = remember { SnackbarHostState() }
        val snackbarScope = rememberCoroutineScope()
        LaunchedEffect(pendingFocusGroupKey, pendingFocusSourceKey, presentationCards) {
            if (pendingFocusGroupKey != null || pendingFocusSourceKey != null) {
                withFrameNanos { }
                val groupKey = pendingFocusSourceKey?.let { sourceKey ->
                    presentationCards.firstOrNull { group -> group.members.any { it.naturalKey == sourceKey } }?.groupKey
                } ?: pendingFocusGroupKey
                (groupKey?.let(cardFocusRequesters::get) ?: returnFocusRequester).requestFocus()
                pendingFocusGroupKey = null
                pendingFocusSourceKey = null
            }
        }
        LaunchedEffect(model) {
            model.effects.collect { effect ->
                when (effect) {
                    is AuthorDetailEffect.OpenManga -> {
                        showSourceChooserFor = null
                        navigator.push(MangaDetailScreen(effect.mangaId))
                        model.markWorkSeenAfterNavigation(effect.creatorId, effect.sourceWork)
                    }
                    is AuthorDetailEffect.OpenCreator -> navigator.replace(AuthorDetailScreen(effect.creatorId))
                    is AuthorDetailEffect.OpenWorkCompare -> navigator.push(
                        WorkCompareScreen(effect.candidateId, effect.creatorId),
                    )
                    AuthorDetailEffect.IdentityMerged -> navigator.pop()
                }
            }
        }

        val isCurrentCreatorDiscovery = discoveryState?.scope == CreatorDiscoveryRunScope.Creator &&
            discoveryState?.creatorId == (creator?.id ?: creatorId)
        val isDiscoveryBusy = discoveryState?.status in setOf(TaskStatus.Pending, TaskStatus.Running)
        val isManualDiscoveryRunning = discoveryState?.status == TaskStatus.Running && isCurrentCreatorDiscovery

        val isFollowed = state.followed

        if (showSplitDialog) {
            AlertDialog(
                onDismissRequest = { showSplitDialog = false },
                text = { Text(MR.strings.creator_split_unavailable.localized()) },
                confirmButton = { TextButton(onClick = { showSplitDialog = false }) { Text(MR.strings.action_ok.localized()) } },
            )
        }
        if (confirmUnfollow) {
            AlertDialog(
                onDismissRequest = { confirmUnfollow = false },
                text = { Text(MR.strings.creator_unfollow_confirm.localized()) },
                confirmButton = { TextButton(onClick = { confirmUnfollow = false; model.toggleFollow() }) { Text(MR.strings.action_ok.localized()) } },
                dismissButton = { TextButton(onClick = { confirmUnfollow = false }) { Text(MR.strings.action_cancel.localized()) } },
            )
        }

        showSourceChooserFor?.let { groupKey ->
            state.presentationGroups.firstOrNull { it.groupKey == groupKey }?.let { group ->
                CreatorWorkSourceChooserDialog(
                    group = group,
                    sourceManager = desktopDependencies.sourceManager,
                    error = state.workOpenError,
                    opening = state.actionRunning,
                    onOpen = model::openVersion,
                    onSeparate = { version ->
                        snackbarScope.launch {
                            if (model.excludePresentationVersion(version)) {
                                pendingFocusSourceKey = version.naturalKey
                                showSourceChooserFor = null
                                if (snackbarHostState.showSnackbar(
                                        message = MR.strings.creator_work_separate_display_done.localized(),
                                        actionLabel = MR.strings.creator_work_separate_display_undo.localized(),
                                    ) == SnackbarResult.ActionPerformed
                                ) {
                                    model.restorePresentationVersion(version)
                                }
                            }
                        }
                    },
                    onDismiss = {
                        model.clearWorkOpenError()
                        pendingFocusGroupKey = groupKey
                        showSourceChooserFor = null
                    },
                )
            }
        }

        identityActionError?.let { error ->
            AlertDialog(
                onDismissRequest = model::clearError,
                title = { Text(MR.strings.desktop_ui_identity_action_failed.localized()) },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = model::clearError) {
                        Text(MR.strings.action_ok.localized())
                    }
                },
            )
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(creator?.displayName ?: MR.strings.author.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }, modifier = Modifier.focusRequester(returnFocusRequester)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
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
                                    contentDescription = MR.strings.action_display_grid.localized(),
                                )
                            }
                            DropdownMenu(
                                expanded = showDisplayModeMenu,
                                onDismissRequest = { showDisplayModeMenu = false },
                            ) {
                                listOf(
                                    LibraryDisplayMode.List to MR.strings.action_display_list.localized(),
                                    LibraryDisplayMode.ComfortableGrid to MR.strings.action_display_comfortable_grid.localized(),
                                    LibraryDisplayMode.CompactGrid to MR.strings.action_display_grid.localized(),
                                ).forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (state.effectiveWorkDisplayMode == mode) "✓ $label" else label,
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
                        IconButton(
                            enabled = !isDiscoveryBusy,
                            onClick = model::refreshDiscovery,
                        ) {
                            if (isManualDiscoveryRunning) {
                                CircularProgressIndicator()
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = MR.strings.desktop_ui_check_new_works.localized())
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.workOpenError?.let { error ->
                    Text(
                        MR.strings.desktop_ui_error_reason.localized(Locale.getDefault(), error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("creator-work-open-error"),
                    )
                }
                state.workDisplayModeError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("creator-display-mode-error"),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        CreatorIdentityHeader(model.identityEditor, creator?.displayName.orEmpty())
                        Text(
                        MR.strings.creator_work_version_count.localized(Locale.getDefault(),
                                state.presentationGroups.size + state.workArchive.rejected.size,
                                state.presentationGroups.sumOf { it.sourceCount } + state.workArchive.rejected.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { if (isFollowed) confirmUnfollow = true else model.toggleFollow() },
                    ) {
                        Text(if (isFollowed) MR.strings.desktop_ui_unfollow.localized() else MR.strings.desktop_ui_follow.localized())
                    }
                }
                state.followFeedback?.let { followed ->
                    Text(
                        if (followed) {
                            MR.strings.desktop_ui_author_follow_baseline.localized()
                        } else {
                            MR.strings.desktop_ui_author_unfollowed.localized()
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                discoveryState?.takeIf { state ->
                    state.scope != CreatorDiscoveryRunScope.Creator || state.creatorId == (creator?.id ?: creatorId)
                }?.let { state ->
                    when (state.status) {
                        TaskStatus.Pending -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    MR.strings.desktop_ui_author_discovery_waiting_network.localized(),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = model::cancelDiscovery) {
                                    Text(MR.strings.desktop_ui_author_discovery_cancel.localized())
                                }
                            }
                        }
                        TaskStatus.Running -> {
                            if (state.scope == CreatorDiscoveryRunScope.Creator) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        MR.strings.desktop_ui_author_check_running.localized(
                                            Locale.getDefault(),
                                            creator?.displayName ?: MR.strings.unknown_author.localized(),
                                        ),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    TextButton(onClick = model::cancelDiscovery) {
                                        Text(MR.strings.desktop_ui_author_discovery_cancel.localized())
                                    }
                                }
                                if (state.totalSources > 0) {
                                    LinearProgressIndicator(
                                        progress = { state.completedSources.toFloat() / state.totalSources },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    )
                                }
                                sourceCheckpoints.takeIf { it.isNotEmpty() }?.let { checkpoints ->
                                    Text(
                                        MR.strings.desktop_ui_author_discovery_sources.localized(
                                            Locale.getDefault(),
                                            checkpoints.count { it.lastCheckedAt != null },
                                            checkpoints.size,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            } else {
                                Text(
                                    MR.strings.desktop_ui_author_discovery_running.localized(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        TaskStatus.Failed -> {
                            Text(
                                MR.strings.desktop_ui_author_discovery_failed.localized(
                                    Locale.getDefault(),
                                    state.failureMessage ?: state.failedUnits.joinToString(),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        TaskStatus.Cancelled -> {
                            Text(
                                MR.strings.desktop_ui_author_discovery_cancelled.localized(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        TaskStatus.Completed -> {
                            if (state.lastFinishedAt != null) {
                                Text(
                                    MR.strings.desktop_ui_author_discovery_result.localized(
                                        Locale.getDefault(),
                                        state.newCandidateCount,
                                        state.errorCount,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                TextButton(onClick = { showSplitDialog = true }) {
                    Text(MR.strings.desktop_ui_split_author_identity.localized())
                }

                HorizontalDivider()

                CreatorWorkFilters(
                    state.workFilter.query, state.workFilter.sourceId,
                    (state.workArchive.works.flatMap { it.versions } + state.workArchive.pending + state.workArchive.rejected)
                        .map { it.naturalKey.sourceId }.distinct().sorted().associateWith {
                            desktopDependencies.sourceManager.getOrStub(it).name
                        },
                    model::searchWorks, model::filterSource,
                )
                Text(
                    MR.strings.creator_unique_work_count.localized(Locale.getDefault(), presentationCards.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("creator-visible-work-count"),
                )

                if (presentationCards.isEmpty() && pendingPresentationVersions.isEmpty() && workArchive.rejected.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.workArchive.works.isEmpty() &&
                                state.workArchive.pending.isEmpty() &&
                                state.workArchive.rejected.isEmpty()
                            ) {
                                MR.strings.desktop_ui_no_discovered_works_yet.localized()
                            } else {
                                MR.strings.creator_work_filter_empty.localized()
                            },
                        )
                    }
                } else {
                    val mode = state.effectiveWorkDisplayMode
                    if (mode == LibraryDisplayMode.List) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (presentationCards.isNotEmpty()) {
                                item {
                                    Text(
                                        text = MR.strings.desktop_ui_canonical_works.localized(),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                items(presentationCards, key = { "presentation-${it.groupKey}" }) { group ->
                                    val presentationCardKey = group.canonicalWorkId?.toString() ?: group.groupKey
                                    val focusRequester = remember(group.groupKey) { FocusRequester() }
                                    DisposableEffect(group.groupKey, focusRequester) {
                                        cardFocusRequesters[group.groupKey] = focusRequester
                                        onDispose {
                                            if (cardFocusRequesters[group.groupKey] === focusRequester) {
                                                cardFocusRequesters.remove(group.groupKey)
                                            }
                                        }
                                    }
                                    CreatorArchiveWorkCard(
                                        title = group.title,
                                        thumbnailUrl = group.representative.thumbnailUrl,
                                        sourceId = group.representative.naturalKey.sourceId,
                                        dateLabel = groupDateLabel(group),
                                        favorite = group.inLibrary,
                                        unread = group.unread,
                                        key = presentationCardKey,
                                        mode = mode,
                                        focusRequester = focusRequester,
                                        onClick = {
                                            showSourceChooserFor = group.groupKey
                                        },
                                        content = {
                                            CreatorCanonicalVersions(
                                                group,
                                                state.workFilter.sourceId,
                                                model,
                                                navigator,
                                                creator?.id ?: creatorId,
                                                desktopDependencies.sourceManager,
                                            )
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                            if (pendingPresentationVersions.isNotEmpty()) {
                                item {
                                    Text(
                                        text = MR.strings.desktop_ui_pending_work_suggestions.localized(),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                items(pendingPresentationVersions, key = { "pending-${it.sourceWorkId}" }) { version ->
                                    ArchiveVersionListItem(version, desktopDependencies.sourceManager, onOpen = { model.openVersion(version) }) {
                                        navigator.push(WorkCompareScreen(version.sourceWorkId, creator?.id ?: creatorId))
                                    }
                                }
                            }
                            if (workArchive.rejected.isNotEmpty()) {
                                item {
                                    Text(
                                        text = MR.strings.desktop_ui_separated_work_versions.localized(),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                items(workArchive.rejected, key = { "rejected-${it.sourceWorkId}" }) { version ->
                                    ArchiveVersionListItem(version, desktopDependencies.sourceManager, onOpen = { model.openVersion(version) }) {
                                        navigator.push(WorkCompareScreen(version.sourceWorkId, creator?.id ?: creatorId))
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (mode == LibraryDisplayMode.ComfortableGrid) 164.dp else 112.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (presentationCards.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = MR.strings.desktop_ui_canonical_works.localized(),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                    )
                                }
                                items(presentationCards, key = { "presentation-${it.groupKey}" }) { group ->
                                    val presentationCardKey = group.canonicalWorkId?.toString() ?: group.groupKey
                                    val focusRequester = remember(group.groupKey) { FocusRequester() }
                                    DisposableEffect(group.groupKey, focusRequester) {
                                        cardFocusRequesters[group.groupKey] = focusRequester
                                        onDispose {
                                            if (cardFocusRequesters[group.groupKey] === focusRequester) {
                                                cardFocusRequesters.remove(group.groupKey)
                                            }
                                        }
                                    }
                                    CreatorArchiveWorkCard(
                                        title = group.title,
                                        thumbnailUrl = group.representative.thumbnailUrl,
                                        sourceId = group.representative.naturalKey.sourceId,
                                        dateLabel = groupDateLabel(group),
                                        favorite = group.inLibrary,
                                        unread = group.unread,
                                        key = presentationCardKey,
                                        mode = mode,
                                        focusRequester = focusRequester,
                                        onClick = {
                                            showSourceChooserFor = group.groupKey
                                        },
                                        content = {
                                            CreatorCanonicalVersions(
                                                group,
                                                state.workFilter.sourceId,
                                                model,
                                                navigator,
                                                creator?.id ?: creatorId,
                                                desktopDependencies.sourceManager,
                                            )
                                        },
                                    )
                                }
                            }
                            if (pendingPresentationVersions.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = MR.strings.desktop_ui_pending_work_suggestions.localized(),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                                items(pendingPresentationVersions, span = { GridItemSpan(maxLineSpan) }, key = { "pending-${it.sourceWorkId}" }) { version ->
                                    ArchiveVersionListItem(version, desktopDependencies.sourceManager, onOpen = { model.openVersion(version) }) {
                                        navigator.push(WorkCompareScreen(version.sourceWorkId, creator?.id ?: creatorId))
                                    }
                                }
                            }
                            if (workArchive.rejected.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = MR.strings.desktop_ui_separated_work_versions.localized(),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                                items(workArchive.rejected, span = { GridItemSpan(maxLineSpan) }, key = { "rejected-${it.sourceWorkId}" }) { version ->
                                    ArchiveVersionListItem(version, desktopDependencies.sourceManager, onOpen = { model.openVersion(version) }) {
                                        navigator.push(WorkCompareScreen(version.sourceWorkId, creator?.id ?: creatorId))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun authorVersionLabel(
    version: tachiyomi.domain.creator.model.SourceWorkArchiveVersion,
    sourceManager: SourceManager,
): String {
    val name = sourceManager.getOrStub(version.naturalKey.sourceId).name
    return if (sourceManager.get(version.naturalKey.sourceId) == null) {
        "$name · ${MR.strings.desktop_ui_source_missing.localized()}"
    } else name
}

private fun earliestFirstSeenDate(
    versions: List<tachiyomi.domain.creator.model.SourceWorkArchiveVersion>,
): String? = versions.mapNotNull { it.firstSeenDate?.takeIf(String::isNotBlank) }.minOrNull()

private fun groupDateLabel(group: tachiyomi.domain.creator.model.WorkPresentationGroup): String? {
    val publishedDateAt = group.publishedDateAt
    return when {
    group.publishedDateQuality == SourceDateQualityStatus.TRUSTED && publishedDateAt != null ->
        MR.strings.creator_work_published_date.localized(
            Locale.getDefault(),
            sourceDateDisplayDate(publishedDateAt),
        )
    publishedDateAt != null -> MR.strings.creator_work_published_date_retained.localized(
        Locale.getDefault(),
        sourceDateDisplayDate(publishedDateAt),
    )
    else -> group.firstSeenDate?.let { date ->
        MR.strings.desktop_ui_first_seen.localized(Locale.getDefault(), date)
    }
}
}

private fun chapterCountLabel(version: SourceWorkArchiveVersion): String = when (version.chapterCompleteness) {
    ChapterCatalogCompleteness.UNKNOWN -> MR.strings.creator_work_chapters_unknown.localized()
    ChapterCatalogCompleteness.PARTIAL -> MR.strings.creator_work_chapters_fetched.localized(
        Locale.getDefault(),
        version.chapterCount,
    )
    ChapterCatalogCompleteness.COMPLETE -> MR.strings.desktop_ui_chapter_count.localized(
        Locale.getDefault(),
        version.chapterCount,
    )
}

internal fun latestChapterDateLabel(version: SourceWorkArchiveVersion): String {
    val dateAt = version.latestChapterAt
    return when {
        version.latestChapterDateQuality == SourceDateQualityStatus.SUSPECT ->
            MR.strings.creator_work_latest_date_pending.localized()
        version.latestChapterDateQuality == SourceDateQualityStatus.TRUSTED && dateAt != null ->
            MR.strings.creator_work_latest_date.localized(
                Locale.getDefault(),
                sourceDateDisplayDate(dateAt),
            )
        else -> MR.strings.creator_work_latest_date_unknown.localized()
    }
}

private fun publishedDateLabel(version: SourceWorkArchiveVersion): String {
    val dateAt = version.publishedDateAt
    val firstSeenDate = version.firstSeenDate
    return when {
        version.publishedDateQuality == SourceDateQualityStatus.TRUSTED && dateAt != null ->
            MR.strings.creator_work_published_date.localized(
                Locale.getDefault(),
                sourceDateDisplayDate(dateAt),
            )
        dateAt != null -> MR.strings.creator_work_published_date_retained.localized(
            Locale.getDefault(),
            sourceDateDisplayDate(dateAt),
        )
        !firstSeenDate.isNullOrBlank() -> MR.strings.desktop_ui_first_seen.localized(Locale.getDefault(), firstSeenDate)
        else -> MR.strings.creator_work_published_date_unknown.localized()
    }
}

private fun sourceDateDisplayDate(value: Long): String =
    Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

@Composable
private fun CreatorWorkSourceChooserDialog(
    group: WorkPresentationGroup,
    sourceManager: SourceManager,
    error: String?,
    opening: Boolean,
    onOpen: (SourceWorkArchiveVersion) -> Unit,
    onSeparate: ((SourceWorkArchiveVersion) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(group.title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    MR.strings.creator_work_all_source_versions.localized(),
                    style = MaterialTheme.typography.bodySmall,
                )
                group.members.forEach { version ->
                    val sourceName = sourceManager.getOrStub(version.naturalKey.sourceId).name
                    val sourceMissing = sourceManager.get(version.naturalKey.sourceId) == null
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("creator-source-version-${version.sourceWorkId}"),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !opening) { onOpen(version) }
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                            Box(Modifier.width(48.dp).height(68.dp)) {
                                coil3.compose.AsyncImage(
                                    model = mihon.desktop.image.desktopSourceImageModel(
                                        version.thumbnailUrl,
                                        version.naturalKey.sourceId,
                                    ),
                                    contentDescription = version.title,
                                    fallback = rememberVectorPainter(Icons.AutoMirrored.Filled.MenuBook),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (version.inLibrary) {
                                    Icon(
                                        creatorFavoriteBadgeIcon(),
                                        contentDescription = MR.strings.desktop_ui_in_library.localized(),
                                        modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(16.dp),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(sourceName, style = MaterialTheme.typography.labelLarge)
                                if (sourceMissing) {
                                    Text(
                                        MR.strings.desktop_ui_source_missing.localized(),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(version.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(chapterCountLabel(version), style = MaterialTheme.typography.bodySmall)
                                Text(publishedDateLabel(version), style = MaterialTheme.typography.bodySmall)
                                Text(latestChapterDateLabel(version), style = MaterialTheme.typography.bodySmall)
                            }
                            }
                            if (onSeparate != null && group.canonicalWorkId == null && group.members.size > 1) {
                                TextButton(
                                    onClick = { onSeparate(version) },
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text(MR.strings.creator_work_separate_display.localized())
                                }
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
                Text(MR.strings.action_cancel.localized())
            }
        },
    )
}

@Composable
private fun CreatorCanonicalVersions(
    work: WorkPresentationGroup,
    sourceId: Long?,
    model: AuthorDetailScreenModel,
    navigator: Navigator,
    creatorId: Long,
    sourceManager: SourceManager,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        work.members.filter { sourceId == null || it.naturalKey.sourceId == sourceId }.forEach { version ->
            var showVersionMenu by remember(version.sourceWorkId) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { model.openVersion(version) },
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag("creator-version-${version.sourceWorkId}"),
                ) {
                    Text(authorVersionLabel(version, sourceManager))
                }
                IconButton(onClick = { showVersionMenu = true }, modifier = Modifier.width(32.dp)) {
                    Icon(Icons.Default.MoreVert, MR.strings.action_edit.localized())
                }
                DropdownMenu(expanded = showVersionMenu, onDismissRequest = { showVersionMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(MR.strings.desktop_ui_pending_work_suggestions.localized()) },
                        onClick = {
                            showVersionMenu = false
                            navigator.push(WorkCompareScreen(version.sourceWorkId, creatorId))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreatorArchiveWorkCard(
    title: String,
    thumbnailUrl: String?,
    sourceId: Long,
    dateLabel: String?,
    favorite: Boolean,
    unread: Boolean,
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
                modifier = cardModifier.padding(16.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CreatorWorkCover(title, thumbnailUrl, sourceId, key, Modifier.width(64.dp).height(88.dp), favorite)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("creator-work-$key"))
                    unreadWorkLabel(unread, key)
                    dateLabel?.let { date ->
                        Text(
                            date,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("creator-work-first-seen-$key"),
                        )
                    }
                    content()
                }
            }
        }
        LibraryDisplayMode.ComfortableGrid -> {
            Card(modifier = cardModifier, colors = CardDefaults.cardColors()) {
                Column {
                    CreatorWorkCover(title, thumbnailUrl, sourceId, key, Modifier.fillMaxWidth().aspectRatio(0.7f), favorite)
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).testTag("creator-work-$key"),
                    )
                    unreadWorkLabel(unread, key)
                    dateLabel?.let { date ->
                        Text(
                            date,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp)
                                .testTag("creator-work-first-seen-$key"),
                        )
                    }
                    content()
                }
            }
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            Card(modifier = cardModifier, colors = CardDefaults.cardColors()) {
                Column {
                    CreatorWorkCover(title, thumbnailUrl, sourceId, key, Modifier.fillMaxWidth().aspectRatio(0.7f), favorite, compactTitle = true)
                    dateLabel?.let { date ->
                        Text(
                            date,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                .testTag("creator-work-first-seen-$key"),
                        )
                    }
                    unreadWorkLabel(unread, key)
                    content()
                }
            }
        }
    }
}

@Composable
private fun unreadWorkLabel(unread: Boolean, key: String) {
    if (unread) {
        Text(
            MR.strings.creator_new_work_unread.localized(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("creator-work-$key-unread"),
        )
    }
}

internal fun creatorFavoriteBadgeIcon() = Icons.Outlined.CollectionsBookmark

@Composable
private fun CreatorWorkCover(
    title: String,
    thumbnailUrl: String?,
    sourceId: Long,
    key: String,
    modifier: Modifier,
    favorite: Boolean,
    compactTitle: Boolean = false,
) {
    Box(modifier = modifier.testTag("creator-cover-$key")) {
        coil3.compose.AsyncImage(
            model = mihon.desktop.image.desktopSourceImageModel(thumbnailUrl, sourceId),
            contentDescription = title,
            fallback = rememberVectorPainter(Icons.AutoMirrored.Filled.MenuBook),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (favorite) 0.34f else 1f),
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
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).testTag("creator-work-$key"),
            )
        }
        if (favorite) {
            Icon(
                creatorFavoriteBadgeIcon(),
                contentDescription = MR.strings.desktop_ui_in_library.localized(),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(20.dp)
                    .testTag("creator-favorite-$key"),
            )
        }
    }
}

@Composable
private fun CreatorArchiveWorkRow(
    title: String,
    thumbnailUrl: String?,
    key: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        coil3.compose.AsyncImage(thumbnailUrl, null,
            fallback = rememberVectorPainter(Icons.AutoMirrored.Filled.MenuBook),
            modifier = Modifier.width(64.dp).height(88.dp).testTag("creator-cover-$key"),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("creator-work-$key"))
            content()
        }
    }
}

@Composable
private fun ArchiveVersionListItem(
    version: SourceWorkArchiveVersion,
    sourceManager: SourceManager,
    onOpen: () -> Unit,
    onClick: () -> Unit,
) {
    val sourceName = sourceManager.getOrStub(version.naturalKey.sourceId).name
    val availability = if (sourceManager.get(version.naturalKey.sourceId) != null) {
        MR.strings.desktop_ui_source_available.localized()
    } else {
        MR.strings.desktop_ui_source_missing.localized()
    }
    val checkResult = version.lastCheckResult?.name?.lowercase()
        ?: MR.strings.desktop_ui_source_not_checked.localized()
    CreatorArchiveWorkRow(version.title, version.thumbnailUrl, "version-${version.sourceWorkId}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen, modifier = Modifier.weight(1f, fill = false)
                .testTag("creator-version-${version.sourceWorkId}")) {
                Text(authorVersionLabel(version, sourceManager))
            }
            IconButton(onClick = onClick, modifier = Modifier.width(32.dp)) {
                Icon(Icons.Default.MoreVert, MR.strings.action_edit.localized())
            }
        }
            Text(
                MR.strings.desktop_ui_archive_version_evidence.localized(
                    Locale.getDefault(),
                    sourceName,
                    availability,
                    version.readingLanguage.tag.uppercase(),
                    version.readingLanguage.certainty.name.lowercase(),
                    chapterCountLabel(version),
                    if (version.inLibrary) {
                        MR.strings.desktop_ui_in_library.localized()
                    } else {
                        MR.strings.desktop_ui_not_in_library.localized()
                    },
                    checkResult,
                    version.lastSuccessAt?.toString() ?: MR.strings.unknown.localized(),
                ),
            )
    }

}

internal fun shouldCollectAuthorOnOpen(
    collectOnOpen: Boolean,
    candidates: List<DiscoveryCandidate>,
    mangaLinks: List<MangaCreator>,
): Boolean {
    return collectOnOpen && candidates.isEmpty() && mangaLinks.isEmpty()
}

internal fun authorCandidateSourceManga(candidate: DiscoveryCandidate): SManga {
    return SManga.create().apply {
        url = candidate.url
        title = candidate.title
        thumbnail_url = null
    }
}

data class WorkCompareScreen(val workId: Long, val creatorId: Long = -1L) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val model = rememberScreenModel { AuthorsScreenModelFactory.compare(workId, creatorId, dependencies) }
        val state by model.state.collectAsState()
        var languageDimension by remember { mutableStateOf<LanguageDimension?>(null) }
        var languageTag by remember { mutableStateOf("") }
        var showRawChapters by remember { mutableStateOf(false) }

        languageDimension?.let { dimension ->
            AlertDialog(
                onDismissRequest = { languageDimension = null },
                title = {
                    Text(
                        if (dimension == LanguageDimension.READING) {
                            MR.strings.desktop_ui_correct_reading_language.localized()
                        } else {
                            MR.strings.desktop_ui_correct_original_language.localized()
                        },
                    )
                },
                text = {
                    OutlinedTextField(
                        value = languageTag,
                        onValueChange = { languageTag = it },
                        label = { Text(MR.strings.desktop_ui_language_tag.localized()) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = languageTag.isNotBlank() && !state.actionRunning,
                        onClick = {
                            model.setLanguage(dimension, languageTag)
                            languageDimension = null
                            languageTag = ""
                        },
                    ) { Text(MR.strings.action_ok.localized()) }
                },
                dismissButton = {
                    TextButton(onClick = { languageDimension = null }) {
                        Text(MR.strings.action_cancel.localized())
                    }
                },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.version?.title ?: MR.strings.desktop_ui_work_comparison.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val item = state.version
                if (item == null) {
                    if (!state.loading) {
                        Text(MR.strings.desktop_ui_work_candidate_was_not_found.localized(), style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        MR.strings.desktop_ui_source_language_state.localized(
                            Locale.getDefault(),
                            dependencies.sourceManager.getOrStub(item.naturalKey.sourceId).name,
                            item.readingLanguage.tag.uppercase(),
                            item.decision?.decision?.state?.name?.lowercase()
                                ?: MR.strings.desktop_ui_pending_decision.localized(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text(
                        MR.strings.desktop_ui_language_evidence.localized(
                            Locale.getDefault(),
                            item.readingLanguage.evidenceKind.name.lowercase(),
                            item.readingLanguage.certainty.name.lowercase(),
                        ),
                    )
                    Text(
                        MR.strings.desktop_ui_original_language_evidence.localized(
                            Locale.getDefault(),
                            item.originalLanguage.tag.uppercase(),
                            item.originalLanguage.evidenceKind.name.lowercase(),
                            item.originalLanguage.certainty.name.lowercase(),
                        ),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = !state.actionRunning,
                            onClick = {
                                languageTag = item.readingLanguage.tag.takeUnless { it == "und" }.orEmpty()
                                languageDimension = LanguageDimension.READING
                            },
                        ) { Text(MR.strings.desktop_ui_correct_reading_language.localized()) }
                        TextButton(
                            enabled = !state.actionRunning,
                            onClick = {
                                languageTag = item.originalLanguage.tag.takeUnless { it == "und" }.orEmpty()
                                languageDimension = LanguageDimension.ORIGINAL
                            },
                        ) { Text(MR.strings.desktop_ui_correct_original_language.localized()) }
                        TextButton(
                            enabled = !state.actionRunning,
                            onClick = { model.undoLanguage(LanguageDimension.READING) },
                        ) { Text(MR.strings.desktop_ui_undo_reading_language.localized()) }
                        TextButton(
                            enabled = !state.actionRunning,
                            onClick = { model.undoLanguage(LanguageDimension.ORIGINAL) },
                        ) { Text(MR.strings.desktop_ui_undo_original_language.localized()) }
                    }
                    Text(MR.strings.desktop_ui_source_url.localized(Locale.getDefault(), item.naturalKey.stableSourceUrl))
                    Text(MR.strings.desktop_ui_last_seen.localized(Locale.getDefault(), item.lastSeenAt.toString()))
                    Text(chapterCountLabel(item))
                    Text(
                        MR.strings.desktop_ui_source_check_quality.localized(
                            Locale.getDefault(),
                            item.lastCheckResult?.name?.lowercase()
                                ?: MR.strings.desktop_ui_source_not_checked.localized(),
                            item.consecutiveFailures,
                            item.lastSuccessAt?.toString() ?: MR.strings.unknown.localized(),
                        ),
                    )
                    state.chapterError?.let { error ->
                        Text(
                            MR.strings.desktop_ui_chapter_compare_failed.localized(Locale.getDefault(), error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    state.chapterSummary?.let { summary ->
                        Text(
                            MR.strings.desktop_ui_chapter_variant_summary.localized(
                                Locale.getDefault(),
                                summary.regularChapterCount,
                                summary.splitChapterCount,
                                summary.decimalChapterCount,
                                summary.volumeCount,
                                summary.extraChapterCount,
                                summary.specialChapterCount,
                                summary.duplicateReleaseCount,
                                summary.unknownRawNames.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (summary.missingChapterNumbers.isNotEmpty()) {
                            Text(
                                MR.strings.desktop_ui_missing_chapters.localized(
                                    Locale.getDefault(),
                                    summary.missingChapterNumbers.joinToString(),
                                ),
                            )
                        }
                        TextButton(onClick = { showRawChapters = !showRawChapters }) {
                            Text(
                                if (showRawChapters) {
                                    MR.strings.desktop_ui_hide_raw_chapters.localized()
                                } else {
                                    MR.strings.desktop_ui_show_raw_chapters.localized()
                                },
                            )
                        }
                        if (showRawChapters) {
                            summary.variants.forEach { variant ->
                                Text(
                                    MR.strings.desktop_ui_raw_chapter_variant.localized(
                                        Locale.getDefault(),
                                        variant.rawName,
                                        variant.type.name.lowercase(),
                                        variant.evidence,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    val decision = state.currentDecision
                    Text(
                        decision?.let {
                            MR.strings.desktop_ui_work_decision.localized(
                                Locale.getDefault(),
                                it.decision.state.name.lowercase(),
                                it.workTitle,
                            )
                        } ?: MR.strings.desktop_ui_work_has_no_manual_decision.localized(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.suggestions.forEach { suggestion ->
                        ListItem(
                            headlineContent = { Text(suggestion.title) },
                            supportingContent = {
                                Text(
                                    MR.strings.desktop_ui_work_match_evidence.localized(
                                        Locale.getDefault(),
                                        suggestion.score.tier.name.lowercase(),
                                        suggestion.score.reason,
                                    ),
                                )
                            },
                            trailingContent = {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        enabled = !state.actionRunning,
                                        onClick = { model.confirm(suggestion) },
                                    ) {
                                        Text(MR.strings.desktop_ui_confirm_same_work.localized())
                                    }
                                    TextButton(
                                        enabled = !state.actionRunning,
                                        onClick = { model.reject(suggestion) },
                                    ) { Text(MR.strings.desktop_ui_reject_same_work.localized()) }
                                }
                            },
                        )
                    }
                    if (state.suggestions.isEmpty()) {
                        Button(enabled = !state.actionRunning, onClick = { model.confirm() }) {
                            Text(MR.strings.desktop_ui_confirm_singleton_work.localized())
                        }
                    }
                    if (decision != null) {
                        TextButton(enabled = !state.actionRunning, onClick = model::undo) {
                            Text(MR.strings.action_undo.localized())
                        }
                    }
                }
                TextButton(onClick = { navigator.pop() }) {
                    Text(MR.strings.action_bar_up_description.localized())
                }
            }
        }
    }
}

private const val CREATOR_CARD_PREFETCH_DISTANCE = 5

@Composable
private fun CreatorCardRow(
    card: tachiyomi.domain.creator.model.CreatorCardProjection,
    customCoverStore: mihon.desktop.domain.DesktopCustomCoverStore,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("creator-card-${card.creator.id}")
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(card.creator.displayName)
            Text(
                MR.strings.creator_unique_work_count.localized(Locale.getDefault(), card.uniqueWorkCount),
                style = MaterialTheme.typography.bodySmall,
            )
            if (card.unreadWorkCount > 0) {
                Text(
                    MR.strings.creator_new_work_count.localized(Locale.getDefault(), card.unreadWorkCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("creator-card-${card.creator.id}-new-work-count"),
                )
            }
            if (card.followed) {
                Text(MR.strings.desktop_ui_followed.localized(), modifier = Modifier.testTag("creator-card-${card.creator.id}-followed"))
            }
            if (card.creator.aliases.isNotEmpty()) {
                Text(card.creator.aliases.joinToString(), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (card.representativeWorks.isEmpty()) {
            Text(
                MR.strings.creator_no_representative_work.localized(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                card.representativeWorks.take(3).forEach { work ->
                    CreatorRepresentativeWorkItem(work, customCoverStore)
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun CreatorRepresentativeWorkItem(
    work: tachiyomi.domain.creator.model.CreatorCardWorkCandidate,
    customCoverStore: mihon.desktop.domain.DesktopCustomCoverStore,
) {
    val request = work.coverRequest
    val coverId = request.mangaId ?: -request.sourceWorkId
    val coverModel = request.mangaId?.let { customCoverStore.resolveModel(it, request.url) } ?: request.url
    val imageRequest = mihon.desktop.ui.library.rememberMangaCoverRequestState(
        mangaId = coverId,
        sourceId = request.sourceId,
        coverModel = coverModel,
        coverVersion = request.lastModifiedAt,
    ).request
    val fallbackPainter = rememberVectorPainter(Icons.AutoMirrored.Filled.MenuBook)
    Column(
        modifier = Modifier.width(72.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        coil3.compose.AsyncImage(
            model = imageRequest,
            contentDescription = work.title,
            placeholder = fallbackPainter,
            error = fallbackPainter,
            fallback = fallbackPainter,
            modifier = Modifier.width(64.dp).height(88.dp).testTag("creator-cover-${work.workKey}"),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Text(work.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}
