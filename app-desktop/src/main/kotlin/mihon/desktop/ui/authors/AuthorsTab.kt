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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.ui.library.MangaDetailScreen
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.service.CreatorLibraryIndexState
import tachiyomi.domain.creator.interactor.CreatorDetails
import tachiyomi.domain.manga.repository.MangaRepository
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
        val getCreators = dependencies.getCreators
        val indexer = requireNotNull(dependencies.creatorLibraryIndexer) {
            "CreatorLibraryIndexer is required by AuthorsRootScreen"
        }
        val creators by getCreators.subscribe().collectAsState(emptyList())
        val followed by getCreators.subscribeFollowed().collectAsState(emptyList())
        val indexState by indexer.state.collectAsState()
        var query by remember { mutableStateOf("") }

        val followedIds = remember(followed) { followed.map { it.creatorId }.toSet() }
        val filteredCreators = remember(creators, query) {
            creators.filter { it.displayName.contains(query, ignoreCase = true) }
        }
        val indexPresentation = authorIndexPresentation(indexState, creators.size)

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
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
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
                            Button(onClick = indexer::retry) { Text(MR.strings.action_retry.localized()) }
                        }
                    }
                    else -> Unit
                }

                if (creators.isEmpty()) {
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
                } else if (filteredCreators.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(MR.strings.no_results_found.localized())
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        if (followedIds.isNotEmpty()) {
                            item {
                                Text(
                                    text = MR.strings.desktop_ui_followed.localized(),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(filteredCreators.filter { it.id in followedIds }, key = { "followed-${it.id}" }) {
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
                        items(filteredCreators, key = { it.id }) {
                            AuthorListItem(it, followed = it.id in followedIds) {
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
        val getCreatorDetails = desktopDependencies.getCreatorDetails
        val discoverCreatorWorks = desktopDependencies.discoverCreatorWorks
        val setCreatorFollow = desktopDependencies.setCreatorFollow
        val sourceManager = desktopDependencies.sourceManager
        val saveSourceMangaForDetails = desktopDependencies.saveSourceMangaForDetails
        val manageCreatorIdentity = requireNotNull(desktopDependencies.manageCreatorIdentity) {
            "ManageCreatorIdentity is required by AuthorDetailScreen"
        }
        val identityActions = remember(manageCreatorIdentity) { AuthorIdentityActions(manageCreatorIdentity) }
        val scope = rememberCoroutineScope()
        val allCreators by desktopDependencies.getCreators.subscribe().collectAsState(emptyList())
        val followed by desktopDependencies.getCreators.subscribeFollowed().collectAsState(emptyList())
        var creator by remember { mutableStateOf<Creator?>(null) }
        var candidates by remember { mutableStateOf(emptyList<DiscoveryCandidate>()) }
        var mangaLinks by remember { mutableStateOf(emptyList<MangaCreator>()) }
        var mangaTitles by remember { mutableStateOf(emptyMap<Long, String>()) }
        var checking by remember { mutableStateOf(false) }
        var openingCandidateId by remember { mutableStateOf<Long?>(null) }
        var showAliasDialog by remember { mutableStateOf(false) }
        var aliasInput by remember { mutableStateOf("") }
        var manualAliases by remember { mutableStateOf(emptyList<String>()) }
        var aliasPendingRemoval by remember { mutableStateOf<String?>(null) }
        var showMergePicker by remember { mutableStateOf(false) }
        var mergeTarget by remember { mutableStateOf<Creator?>(null) }
        var showSplitDialog by remember { mutableStateOf(false) }
        var splitName by remember { mutableStateOf("") }
        var splitMangaIds by remember { mutableStateOf(emptySet<Long>()) }
        var identityActionError by remember { mutableStateOf<String?>(null) }
        var identityActionRunning by remember { mutableStateOf(false) }

        fun applyDetails(details: CreatorDetails) {
            creator = details.creator
            candidates = details.candidates
            mangaLinks = details.mangaLinks
        }

        LaunchedEffect(creatorId) {
            applyDetails(getCreatorDetails.await(creatorId))
            runCatching { identityActions.getManualAliases(creatorId) }
                .onSuccess { manualAliases = it }
                .onFailure { identityActionError = it.message ?: it::class.simpleName.orEmpty() }
            if (shouldCollectAuthorOnOpen(collectOnOpen, candidates, mangaLinks)) {
                checking = true
                applyDetails(discoverCreatorWorks.await(creatorId))
                checking = false
            }
            mangaTitles = mangaLinks.associate { link ->
                link.mangaId to runCatching { desktopDependencies.getMangaTitle(link.mangaId) }
                    .getOrDefault(MR.strings.desktop_ui_manga_number.localized(Locale.getDefault(), link.mangaId))
            }
        }

        val isFollowed = followed.any { it.creatorId == creatorId }

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
                            scope.launch {
                                identityActionRunning = true
                                runCatching { identityActions.addAlias(creatorId, alias) }
                                    .onSuccess {
                                        creator = getCreatorDetails.await(creatorId).creator
                                        manualAliases = identityActions.getManualAliases(creatorId)
                                        aliasInput = ""
                                    }
                                    .onFailure { identityActionError = it.message ?: it::class.simpleName.orEmpty() }
                                identityActionRunning = false
                            }
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
                            scope.launch {
                                identityActionRunning = true
                                runCatching { identityActions.removeAlias(creatorId, alias) }
                                    .onSuccess {
                                        creator = getCreatorDetails.await(creatorId).creator
                                        manualAliases = identityActions.getManualAliases(creatorId)
                                    }
                                    .onFailure { identityActionError = it.message ?: it::class.simpleName.orEmpty() }
                                identityActionRunning = false
                            }
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
                            scope.launch {
                                identityActionRunning = true
                                runCatching { identityActions.merge(creatorId, target.id) }
                                    .onSuccess { navigator.pop() }
                                    .onFailure { identityActionError = it.message ?: it::class.simpleName.orEmpty() }
                                identityActionRunning = false
                            }
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
                            scope.launch {
                                identityActionRunning = true
                                runCatching { identityActions.split(creatorId, selectedIds, newName) }
                                    .onSuccess { newCreatorId ->
                                        splitMangaIds = emptySet()
                                        splitName = ""
                                        navigator.replace(AuthorDetailScreen(newCreatorId))
                                    }
                                    .onFailure { identityActionError = it.message ?: it::class.simpleName.orEmpty() }
                                identityActionRunning = false
                            }
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
                onDismissRequest = { identityActionError = null },
                title = { Text(MR.strings.desktop_ui_identity_action_failed.localized()) },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { identityActionError = null }) {
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
                            enabled = !checking,
                            onClick = {
                                scope.launch {
                                    checking = true
                                    applyDetails(discoverCreatorWorks.await(creatorId))
                                    mangaTitles = mangaLinks.associate { link ->
                                        link.mangaId to runCatching { desktopDependencies.getMangaTitle(link.mangaId) }
                                            .getOrDefault(MR.strings.desktop_ui_manga_number.localized(Locale.getDefault(), link.mangaId))
                                    }
                                    checking = false
                                }
                            },
                        ) {
                            if (checking) {
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
                        onClick = {
                            scope.launch {
                                if (isFollowed) {
                                    setCreatorFollow.await(creatorId, followed = false)
                                } else {
                                    setCreatorFollow.await(creatorId, followed = true)
                                }
                            }
                        },
                    ) {
                        Text(if (isFollowed) MR.strings.desktop_ui_unfollow.localized() else MR.strings.desktop_ui_follow.localized())
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

                if (candidates.isEmpty() && mangaLinks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(MR.strings.desktop_ui_no_discovered_works_yet.localized())
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (candidates.isNotEmpty()) {
                            item {
                                Text(
                                    text = MR.strings.desktop_ui_discovered_works.localized(),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(candidates, key = { it.id }) { candidate ->
                                ListItem(
                                    headlineContent = {
                                        Text(candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    supportingContent = {
                                        Text(
                                            MR.strings.desktop_ui_source_id.localized(
                                                Locale.getDefault(),
                                                candidate.languageTag.uppercase(),
                                                candidate.source,
                                            ),
                                        )
                                    },
                                    leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                                    modifier = Modifier.clickable {
                                        val source = sourceManager.getCatalogueSources()
                                            .find { it.id == candidate.source }
                                            ?: return@clickable
                                        if (openingCandidateId != null) return@clickable
                                        openingCandidateId = candidate.id
                                        scope.launch {
                                            val listedManga = authorCandidateSourceManga(candidate)
                                            val details = saveSourceMangaForDetails.awaitListedForDetails(
                                                sManga = listedManga,
                                                sourceId = candidate.source,
                                            )
                                            val saved = details.manga
                                            navigator.push(MangaDetailScreen(saved.id))
                                            if (details.needsRefresh) {
                                                saveSourceMangaForDetails.refreshFromSource(
                                                    source = source,
                                                    listedManga = listedManga,
                                                )
                                            }
                                            openingCandidateId = null
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                        if (mangaLinks.isNotEmpty()) {
                            item {
                                Text(
                                    text = MR.strings.desktop_ui_archived_works.localized(),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(mangaLinks, key = { "${it.mangaId}-${it.role}" }) { link ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            mangaTitles[link.mangaId]
                                                ?: MR.strings.desktop_ui_manga_number.localized(Locale.getDefault(), link.mangaId),
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            MR.strings.desktop_ui_role_confidence.localized(
                                                Locale.getDefault(),
                                                link.role.name.lowercase(),
                                                link.evidence,
                                                link.confidence.toString(),
                                            ),
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        navigator.push(MangaDetailScreen(link.mangaId))
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
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

data class WorkCompareScreen(val workId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val getCreatorDetails = LocalDesktopUiDependencies.current.getCreatorDetails
        val sourceManager = LocalDesktopUiDependencies.current.sourceManager
        var candidate by remember { mutableStateOf<DiscoveryCandidate?>(null) }

        LaunchedEffect(workId) {
            candidate = getCreatorDetails.awaitCandidate(workId)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(candidate?.title ?: MR.strings.desktop_ui_work_comparison.localized()) },
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
                val item = candidate
                if (item == null) {
                    Text(MR.strings.desktop_ui_work_candidate_was_not_found.localized(), style = MaterialTheme.typography.titleLarge)
                } else {
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        MR.strings.desktop_ui_source_language_state.localized(
                            Locale.getDefault(),
                            sourceManager.getOrStub(item.source).name,
                            item.languageTag.uppercase(),
                            item.state.name.lowercase(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text(MR.strings.desktop_ui_author_value.localized(Locale.getDefault(), item.authorText ?: MR.strings.unknown.localized()))
                    Text(MR.strings.desktop_ui_artist_value.localized(Locale.getDefault(), item.artistText ?: MR.strings.unknown.localized()))
                    Text(MR.strings.desktop_ui_language_evidence.localized(Locale.getDefault(), item.languageEvidence, item.languageConfidence.toString()))
                    Text(MR.strings.desktop_ui_source_url.localized(Locale.getDefault(), item.url))
                    Text(MR.strings.desktop_ui_first_seen.localized(Locale.getDefault(), item.firstSeenAt.toString()))
                    Text(MR.strings.desktop_ui_last_seen.localized(Locale.getDefault(), item.lastSeenAt.toString()))
                    Text(
                        MR.strings.desktop_ui_chapter_grouping_and_confirmed_cross_source_versions_wil.localized(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
