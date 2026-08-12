package mihon.desktop.ui.authors

import tachiyomi.i18n.MR

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
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
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import mihon.desktop.domain.CreatorDiscoveryRunScope
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.domain.task.TaskStatus
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.service.CreatorLibraryIndexState
import tachiyomi.domain.source.service.SourceManager
import java.util.Locale

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
        Navigator(AuthorsRootScreen()) {
            CurrentScreen()
        }
    }
}

class AuthorsRootScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val dependencies = LocalDesktopUiDependencies.current
        val model = rememberScreenModel { AuthorsScreenModelFactory.root(dependencies) }
        val state by model.state.collectAsState()
        val indexPresentation = authorIndexPresentation(state.indexState, state.creators.size)

        Scaffold(
            topBar = {
                TopAppBar(title = { Text(MR.strings.desktop_ui_authors.localized()) })
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = model::setQuery,
                    placeholder = { Text(MR.strings.desktop_ui_search_authors.localized()) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )

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

                if (state.creators.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when (indexPresentation) {
                                AuthorIndexPresentation.EmptyLibrary ->
                                    MR.strings.desktop_ui_author_index_empty_library.localized()
                                AuthorIndexPresentation.NoAuthorMetadata ->
                                    MR.strings.desktop_ui_author_index_no_metadata.localized()
                                is AuthorIndexPresentation.Failed,
                                is AuthorIndexPresentation.Indexing,
                                AuthorIndexPresentation.Content,
                                -> MR.strings.desktop_ui_no_authors_indexed_yet.localized()
                            },
                        )
                    }
                } else if (state.filteredCreators.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(MR.strings.no_results_found.localized())
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        if (state.followedIds.isNotEmpty()) {
                            item {
                                Text(
                                    text = MR.strings.desktop_ui_followed.localized(),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(state.filteredCreators.filter { it.id in state.followedIds }, key = { "followed-${it.id}" }) {
                                AuthorListItem(it, followed = true) { navigator.push(AuthorDetailScreen(it.id)) }
                            }
                            item { HorizontalDivider() }
                        }

                        item {
                            Text(
                                text = MR.strings.desktop_ui_all_authors.localized(),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(state.filteredCreators, key = { it.id }) {
                            AuthorListItem(it, followed = it.id in state.followedIds) {
                                navigator.push(AuthorDetailScreen(it.id))
                            }
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
        val discoveryState = state.discovery
        val sourceCheckpoints = state.checkpoints
        val allCreators = state.allCreators
        val manualAliases = state.manualAliases
        val identityActionError = state.error
        val identityActionRunning = state.actionRunning
        var showAliasDialog by remember { mutableStateOf(false) }
        var aliasInput by remember { mutableStateOf("") }
        var aliasPendingRemoval by remember { mutableStateOf<String?>(null) }
        var showMergePicker by remember { mutableStateOf(false) }
        var mergeTarget by remember { mutableStateOf<Creator?>(null) }
        var showSplitDialog by remember { mutableStateOf(false) }
        var splitName by remember { mutableStateOf("") }
        var splitMangaIds by remember { mutableStateOf(emptySet<Long>()) }
        LaunchedEffect(model) {
            model.effects.collect { effect ->
                when (effect) {
                    is AuthorDetailEffect.OpenManga -> navigator.push(MangaDetailScreen(effect.mangaId))
                    is AuthorDetailEffect.OpenCreator -> navigator.replace(AuthorDetailScreen(effect.creatorId))
                    is AuthorDetailEffect.OpenWorkCompare -> navigator.push(
                        WorkCompareScreen(effect.candidateId, effect.creatorId),
                    )
                    AuthorDetailEffect.IdentityMerged -> navigator.pop()
                }
            }
        }

        val isCurrentCreatorDiscovery = discoveryState?.scope == CreatorDiscoveryRunScope.Creator &&
            discoveryState?.creatorId == creatorId
        val isDiscoveryBusy = discoveryState?.status in setOf(TaskStatus.Pending, TaskStatus.Running)
        val isManualDiscoveryRunning = discoveryState?.status == TaskStatus.Running && isCurrentCreatorDiscovery

        val isFollowed = state.followed

        if (showAliasDialog) {
            AlertDialog(
                onDismissRequest = { showAliasDialog = false },
                title = { Text(MR.strings.desktop_ui_add_author_alias.localized()) },
                text = {
                    OutlinedTextField(
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        label = { Text(MR.strings.desktop_ui_author_alias.localized()) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = aliasInput.isNotBlank() && !identityActionRunning,
                        onClick = {
                            val alias = aliasInput
                            showAliasDialog = false
                            model.addAlias(alias)
                            aliasInput = ""
                        },
                    ) { Text(MR.strings.action_add.localized()) }
                },
                dismissButton = {
                    TextButton(onClick = { showAliasDialog = false }) {
                        Text(MR.strings.action_cancel.localized())
                    }
                },
            )
        }

        aliasPendingRemoval?.let { alias ->
            AlertDialog(
                onDismissRequest = { aliasPendingRemoval = null },
                title = { Text(MR.strings.desktop_ui_remove_author_alias.localized()) },
                text = {
                    Text(
                        MR.strings.desktop_ui_remove_author_alias_summary.localized(
                            Locale.getDefault(),
                            alias,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !identityActionRunning,
                        onClick = {
                            aliasPendingRemoval = null
                            model.removeAlias(alias)
                        },
                    ) { Text(MR.strings.action_remove.localized()) }
                },
                dismissButton = {
                    TextButton(onClick = { aliasPendingRemoval = null }) {
                        Text(MR.strings.action_cancel.localized())
                    }
                },
            )
        }

        if (showMergePicker) {
            val targets = allCreators.filter { it.id != creatorId }
            AlertDialog(
                onDismissRequest = { showMergePicker = false },
                title = { Text(MR.strings.desktop_ui_merge_author_identity.localized()) },
                text = {
                    if (targets.isEmpty()) {
                        Text(MR.strings.desktop_ui_no_other_author_identities.localized())
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(targets, key = Creator::id) { target ->
                                ListItem(
                                    headlineContent = { Text(target.displayName) },
                                    supportingContent = {
                                        if (target.aliases.isNotEmpty()) {
                                            Text(target.aliases.joinToString())
                                        }
                                    },
                                    modifier = Modifier.clickable {
                                        mergeTarget = target
                                        showMergePicker = false
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showMergePicker = false }) {
                        Text(MR.strings.action_cancel.localized())
                    }
                },
            )
        }

        mergeTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { mergeTarget = null },
                title = { Text(MR.strings.desktop_ui_merge_author_identity.localized()) },
                text = {
                    Text(
                        MR.strings.desktop_ui_merge_author_identity_summary.localized(
                            Locale.getDefault(),
                            creator?.displayName ?: MR.strings.unknown_author.localized(),
                            target.displayName,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !identityActionRunning,
                        onClick = {
                            mergeTarget = null
                            model.merge(target.id)
                        },
                    ) { Text(MR.strings.desktop_ui_merge_author_identity.localized()) }
                },
                dismissButton = {
                    TextButton(onClick = { mergeTarget = null }) {
                        Text(MR.strings.action_cancel.localized())
                    }
                },
            )
        }

        if (showSplitDialog) {
            val uniqueMangaIds = mangaLinks.map(MangaCreator::mangaId).distinct()
            AlertDialog(
                onDismissRequest = { showSplitDialog = false },
                title = { Text(MR.strings.desktop_ui_split_author_identity.localized()) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(MR.strings.desktop_ui_split_author_identity_summary.localized())
                        OutlinedTextField(
                            value = splitName,
                            onValueChange = { splitName = it },
                            label = { Text(MR.strings.desktop_ui_new_identity_name.localized()) },
                            singleLine = true,
                        )
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(uniqueMangaIds, key = { it }) { mangaId ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        splitMangaIds = if (mangaId in splitMangaIds) {
                                            splitMangaIds - mangaId
                                        } else {
                                            splitMangaIds + mangaId
                                        }
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = mangaId in splitMangaIds,
                                        onCheckedChange = { checked ->
                                            splitMangaIds = if (checked) splitMangaIds + mangaId else splitMangaIds - mangaId
                                        },
                                    )
                                    Text(
                                        mangaTitles[mangaId]
                                            ?: MR.strings.desktop_ui_manga_number.localized(Locale.getDefault(), mangaId),
                                    )
                                }
                            }
                        }
                        if (splitMangaIds.isEmpty()) {
                            Text(
                                MR.strings.desktop_ui_select_at_least_one_manga.localized(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = splitName.isNotBlank() && splitMangaIds.isNotEmpty() && !identityActionRunning,
                        onClick = {
                            val selectedIds = splitMangaIds
                            val newName = splitName
                            showSplitDialog = false
                            model.split(selectedIds, newName)
                            splitMangaIds = emptySet()
                            splitName = ""
                        },
                    ) { Text(MR.strings.desktop_ui_split_author_identity.localized()) }
                },
                dismissButton = {
                    TextButton(onClick = { showSplitDialog = false }) {
                        Text(MR.strings.action_cancel.localized())
                    }
                },
            )
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
            topBar = {
                TopAppBar(
                    title = { Text(creator?.displayName ?: MR.strings.author.localized()) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = MR.strings.action_bar_up_description.localized())
                        }
                    },
                    actions = {
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(creator?.displayName ?: MR.strings.unknown_author.localized(), style = MaterialTheme.typography.titleLarge)
                        Text(
                            MR.strings.desktop_ui_discovered_candidate_count.localized(Locale.getDefault(), candidates.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            MR.strings.desktop_ui_archived_source_link_count.localized(Locale.getDefault(), mangaLinks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = model::toggleFollow,
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
                    state.scope != CreatorDiscoveryRunScope.Creator || state.creatorId == creatorId
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

                creator?.aliases?.takeIf { it.isNotEmpty() }?.let { aliases ->
                    Text(
                        MR.strings.desktop_ui_author_aliases.localized(
                            Locale.getDefault(),
                            aliases.joinToString(),
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                manualAliases.takeIf { it.isNotEmpty() }?.let { aliases ->
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        aliases.forEach { alias ->
                            TextButton(
                                enabled = !identityActionRunning,
                                onClick = { aliasPendingRemoval = alias },
                            ) {
                                Text(
                                    MR.strings.desktop_ui_remove_named_author_alias.localized(
                                        Locale.getDefault(),
                                        alias,
                                    ),
                                )
                            }
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        enabled = !identityActionRunning,
                        onClick = { showAliasDialog = true },
                    ) { Text(MR.strings.desktop_ui_add_author_alias.localized()) }
                    TextButton(
                        enabled = !identityActionRunning && allCreators.any { it.id != creatorId },
                        onClick = { showMergePicker = true },
                    ) { Text(MR.strings.desktop_ui_merge_author_identity.localized()) }
                    TextButton(
                        enabled = !identityActionRunning && mangaLinks.isNotEmpty(),
                        onClick = {
                            splitName = creator?.displayName.orEmpty()
                            splitMangaIds = emptySet()
                            showSplitDialog = true
                        },
                    ) { Text(MR.strings.desktop_ui_split_author_identity.localized()) }
                }

                HorizontalDivider()

                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val summary = state.languageSummary
                    listOf(
                        LanguageArchiveFilter.ALL to MR.strings.all.localized(),
                        LanguageArchiveFilter.CONFIRMED to
                            MR.strings.desktop_ui_language_confirmed_count.localized(Locale.getDefault(), summary.confirmed),
                        LanguageArchiveFilter.PROBABLE to
                            MR.strings.desktop_ui_language_possible_count.localized(Locale.getDefault(), summary.probable),
                        LanguageArchiveFilter.NEEDS_REVIEW to
                            MR.strings.desktop_ui_language_needs_review_count.localized(Locale.getDefault(), summary.needsReview),
                    ).forEach { (filter, label) ->
                        FilterChip(
                            selected = state.languageFilter == filter,
                            onClick = { model.setLanguageFilter(filter) },
                            label = { Text(label) },
                        )
                    }
                }

                if (workArchive.works.isEmpty() && workArchive.pending.isEmpty() && workArchive.rejected.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.workArchive.works.isEmpty() &&
                                state.workArchive.pending.isEmpty() &&
                                state.workArchive.rejected.isEmpty()
                            ) {
                                MR.strings.desktop_ui_no_discovered_works_yet.localized()
                            } else {
                                MR.strings.desktop_ui_language_filter_empty.localized()
                            },
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (workArchive.works.isNotEmpty()) {
                            item {
                                Text(
                                    text = MR.strings.desktop_ui_canonical_works.localized(),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            workArchive.works.forEach { work ->
                                item(key = "work-${work.workId}") {
                                    ListItem(
                                        headlineContent = { Text(work.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = {
                                            Text(
                                                MR.strings.desktop_ui_source_versions.localized(
                                                    Locale.getDefault(),
                                                    work.versions.size,
                                                ),
                                            )
                                        },
                                        leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                                    )
                                }
                                items(work.versions, key = { "version-${it.sourceWorkId}" }) { version ->
                                    ArchiveVersionListItem(version, desktopDependencies.sourceManager) {
                                        navigator.push(WorkCompareScreen(version.sourceWorkId, creatorId))
                                    }
                                }
                                item(key = "work-divider-${work.workId}") { HorizontalDivider() }
                            }
                        }
                        if (workArchive.pending.isNotEmpty()) {
                            item {
                                Text(
                                    text = MR.strings.desktop_ui_pending_work_suggestions.localized(),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(workArchive.pending, key = { "pending-${it.sourceWorkId}" }) { version ->
                                ArchiveVersionListItem(version, desktopDependencies.sourceManager) {
                                    navigator.push(WorkCompareScreen(version.sourceWorkId, creatorId))
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
                                ArchiveVersionListItem(version, desktopDependencies.sourceManager) {
                                    navigator.push(WorkCompareScreen(version.sourceWorkId, creatorId))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveVersionListItem(
    version: tachiyomi.domain.creator.model.SourceWorkArchiveVersion,
    sourceManager: SourceManager,
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
    ListItem(
        headlineContent = { Text(version.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                MR.strings.desktop_ui_archive_version_evidence.localized(
                    Locale.getDefault(),
                    sourceName,
                    availability,
                    version.readingLanguage.tag.uppercase(),
                    version.readingLanguage.certainty.name.lowercase(),
                    version.chapterCount,
                    if (version.inLibrary) {
                        MR.strings.desktop_ui_in_library.localized()
                    } else {
                        MR.strings.desktop_ui_not_in_library.localized()
                    },
                    checkResult,
                    version.lastSuccessAt?.toString() ?: MR.strings.unknown.localized(),
                ),
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
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
                    Text(MR.strings.desktop_ui_chapter_count.localized(Locale.getDefault(), item.chapterCount))
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
                                    ) { Text(MR.strings.desktop_ui_confirm_same_work.localized()) }
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

@Composable
private fun AuthorListItem(creator: Creator, followed: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(creator.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(if (followed) MR.strings.desktop_ui_followed.localized() else MR.strings.not_selected.localized())
        },
        leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
