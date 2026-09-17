package eu.kanade.tachiyomi.ui.browse.author

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.TabContent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
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
        actions = persistentListOf(),
        content = { padding, _ ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = model::search,
                    label = { Text(stringResource(MR.strings.desktop_ui_search_authors)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.loading) CircularProgressIndicator()

                state.error?.let { Text(it) }
                if (!state.loading &&
                    state.visible.isEmpty()
                ) {
                    Text(stringResource(MR.strings.desktop_ui_no_authors_indexed_yet))
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(state.visible, key = Creator::id) { creator ->
                        Row(
                            Modifier.fillMaxWidth().clickable { navigator.push(AndroidAuthorDetailScreen(creator.id)) }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(creator.displayName)
                                Text(creator.aliases.joinToString())
                            }
                            if (creator.id in state.followed) Text(stringResource(MR.strings.desktop_ui_followed))
                        }
                    }
                }
            }
        },
    )
}

private data class AuthorsState(
    val creators: List<Creator> = emptyList(),
    val followed: Set<Long> = emptySet(),
    val query: String = "",
    val loading: Boolean = true,
    val error: String? = null,
) {
    val visible get() = creators.filter {
        it.displayName.contains(query, true) ||
            it.aliases.any { alias -> alias.contains(query, true) }
    }
}

private class AndroidAuthorsScreenModel(getCreators: GetCreators = Injekt.get()) : ScreenModel {
    private val mutableState = MutableStateFlow(AuthorsState())
    val state: StateFlow<AuthorsState> = mutableState.asStateFlow()
    init {
        screenModelScope.launch {
            combine(getCreators.subscribe(), getCreators.subscribeFollowed()) { creators, followed ->
                creators to
                    followed
            }
                .collect { (creators, followed) ->
                    mutableState.update {
                        it.copy(
                            creators = creators,
                            followed = followed.mapTo(mutableSetOf()) { row ->
                                row.creatorId
                            },
                            loading = false,
                        )
                    }
                }
        }
    }
    fun search(query: String) = mutableState.update { it.copy(query = query) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
data class AndroidAuthorDetailScreen(val creatorId: Long) : Screen {
    @Composable override fun Content() {
        val model = rememberScreenModel { AndroidAuthorDetailScreenModel(creatorId) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var confirmUnfollow by remember { mutableStateOf(false) }
        LaunchedEffect(model) {
            model.openManga.collect { navigator.push(eu.kanade.tachiyomi.ui.manga.MangaScreen(it)) }
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

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) CircularProgressIndicator()
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageCertainty.entries.forEach { certainty ->
                    FilterChip(selected = state.language == certainty, onClick = {
                        model.filter(certainty)
                    }, label = { Text(certainty.name) })
                }
            }
            CreatorWorkFilters(
                state.workFilter.query,
                state.workFilter.sourceId,
                (state.archive.works.flatMap { it.versions } + state.archive.pending + state.archive.rejected)
                    .associate { it.naturalKey.sourceId to model.sourceName(it) },
                model::searchWorks,
                model::filterSource,
            )
            if (state.visibleArchive.works.isEmpty() && state.visibleArchive.pending.isEmpty() &&
                state.visibleArchive.rejected.isEmpty()
            ) {
                Text(stringResource(MR.strings.creator_work_filter_empty))
            }
            LazyColumn {
                state.visibleArchive.works.forEach { work ->
                    item(key = "work-${work.workId}") {
                        CreatorArchiveWorkRow(work.title, work.versions.firstOrNull()?.thumbnailUrl) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                work.versions.forEach { version -> CreatorVersionButton(version, model) }
                            }
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
) {
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
) : ScreenModel {
    private val mutableState = MutableStateFlow(AuthorState())
    val state: StateFlow<AuthorState> = mutableState.asStateFlow()
    private val mutableOpenManga = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openManga = mutableOpenManga.asSharedFlow()
    fun openVersion(version: SourceWorkArchiveVersion) = screenModelScope.launch {
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
        }.onFailure(::fail)
    }
    fun isSourceMissing(version: SourceWorkArchiveVersion): Boolean = sources.get(version.naturalKey.sourceId) == null
    fun sourceName(version: SourceWorkArchiveVersion): String = sources.getOrStub(version.naturalKey.sourceId).name
    val identityEditor = CreatorIdentityEditor(creatorId, identity, screenModelScope)
    private val activeCreatorId: Long get() = identityEditor.state.value.identity?.id ?: creatorId
    init {
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
