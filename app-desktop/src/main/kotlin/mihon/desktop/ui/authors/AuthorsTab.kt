package mihon.desktop.ui.authors

import tachiyomi.i18n.MR

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
        val getCreators = LocalDesktopUiDependencies.current.getCreators
        val creators by getCreators.subscribe().collectAsState(emptyList())
        val followed by getCreators.subscribeFollowed().collectAsState(emptyList())
        var query by remember { mutableStateOf("") }

        val followedIds = remember(followed) { followed.map { it.creatorId }.toSet() }
        val filteredCreators = remember(creators, query) {
            creators.filter { it.displayName.contains(query, ignoreCase = true) }
        }

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

                if (filteredCreators.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(MR.strings.desktop_ui_no_authors_indexed_yet.localized())
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
        val scope = rememberCoroutineScope()
        val followed by desktopDependencies.getCreators.subscribeFollowed().collectAsState(emptyList())
        var creator by remember { mutableStateOf<Creator?>(null) }
        var candidates by remember { mutableStateOf(emptyList<DiscoveryCandidate>()) }
        var mangaLinks by remember { mutableStateOf(emptyList<MangaCreator>()) }
        var mangaTitles by remember { mutableStateOf(emptyMap<Long, String>()) }
        var checking by remember { mutableStateOf(false) }
        var openingCandidateId by remember { mutableStateOf<Long?>(null) }

        fun applyDetails(details: CreatorDetails) {
            creator = details.creator
            candidates = details.candidates
            mangaLinks = details.mangaLinks
        }

        LaunchedEffect(creatorId) {
            applyDetails(getCreatorDetails.await(creatorId))
            if (shouldCollectAuthorOnOpen(collectOnOpen, candidates, mangaLinks)) {
                checking = true
                applyDetails(discoverCreatorWorks.await(creatorId, sourceManager.getCatalogueSources()))
                checking = false
            }
            mangaTitles = mangaLinks.associate { link ->
                link.mangaId to runCatching { desktopDependencies.getMangaTitle(link.mangaId) }
                    .getOrDefault(MR.strings.desktop_ui_manga_number.localized(Locale.getDefault(), link.mangaId))
            }
        }

        val isFollowed = followed.any { it.creatorId == creatorId }

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
                                    applyDetails(discoverCreatorWorks.await(creatorId, sourceManager.getCatalogueSources()))
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
