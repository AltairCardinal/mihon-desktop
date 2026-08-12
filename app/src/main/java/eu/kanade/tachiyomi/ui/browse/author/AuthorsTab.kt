package eu.kanade.tachiyomi.ui.browse.author

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.WorkDecisionState
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

data class AndroidAuthorDetailScreen(val creatorId: Long) : Screen {
    @Composable override fun Content() {
        val model = rememberScreenModel { AndroidAuthorDetailScreenModel(creatorId) }
        val state by model.state.collectAsState()
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) CircularProgressIndicator()
            Text(state.details.creator?.displayName.orEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = model::toggleFollow) {
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
            state.error?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageCertainty.entries.forEach { certainty ->
                    FilterChip(selected = state.language == certainty, onClick = {
                        model.filter(certainty)
                    }, label = { Text(certainty.name) })
                }
            }
            LazyColumn {
                state.archive.works.forEach { work ->
                    item(key = "work-${work.workId}") { Text(work.title, Modifier.padding(vertical = 8.dp)) }
                    items(
                        work.versions.filter {
                            state.language == null ||
                                it.readingLanguage.certainty == state.language
                        },
                        key = { it.sourceWorkId },
                    ) { version ->
                        Text(
                            "${version.title} · ${version.readingLanguage.tag} · ${version.chapterCount}",
                            Modifier.padding(start = 16.dp, bottom = 8.dp),
                        )
                    }
                }
                items(state.archive.pending, key = { "pending-${it.sourceWorkId}" }) { version ->
                    Text(
                        "Review: ${version.title}",
                        Modifier.clickable { model.openReview(version) }.padding(vertical = 8.dp),
                    )
                }
                items(state.archive.rejected, key = { "rejected-${it.sourceWorkId}" }) { Text("Rejected: ${it.title}") }
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

private data class AuthorState(
    val details: CreatorDetails = CreatorDetails(null, emptyList(), emptyList()),
    val archive: CreatorWorkArchive = CreatorWorkArchive(emptyList(), emptyList(), emptyList()),
    val followed: Boolean = false,
    val language: LanguageCertainty? = null,
    val loading: Boolean = true,
    val running: Boolean = false,
    val error: String? = null,
    val reviewing: SourceWorkArchiveVersion? = null,
    val languageTag: String = "",
)

private class AndroidAuthorDetailScreenModel(
    private val creatorId: Long,
    private val details: GetCreatorDetails = Injekt.get(),
    private val creators: GetCreators = Injekt.get(),
    private val follow: SetCreatorFollow = Injekt.get(),
    private val discovery: DiscoverCreatorWorks = Injekt.get(),
    private val archive: CreatorArchive = Injekt.get(),
    private val sources: SourceManager = Injekt.get(),
) : ScreenModel {
    private val mutableState = MutableStateFlow(AuthorState())
    val state: StateFlow<AuthorState> = mutableState.asStateFlow()
    init {
        screenModelScope.launch {
            archive.observe(creatorId).collect { value -> mutableState.update { it.copy(archive = value) } }
        }
        screenModelScope.launch {
            creators.subscribeFollowed().collect { rows ->
                mutableState.update {
                    it.copy(
                        followed = rows.any { row ->
                            row.creatorId ==
                                creatorId
                        },
                    )
                }
            }
        }
        load()
    }
    fun toggleFollow() = screenModelScope.launch {
        runCatching { follow.await(creatorId, !state.value.followed) }.onFailure(::fail)
    }
    fun scan() = screenModelScope.launch {
        mutableState.update { it.copy(running = true, error = null) }
        runCatching {
            discovery.await(creatorId, sources.getCatalogueSources())
        }.onSuccess { mutableState.update { state -> state.copy(details = it) } }.onFailure(::fail)
        mutableState.update { it.copy(running = false) }
    }
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
            val workId = version.decision?.workId ?: archive.createWork(version.title, creatorId, null).id
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
                tachiyomi.domain.creator.model.ArchiveLanguageSubject.SourceWork(version.naturalKey),
                LanguageDimension.READING,
                tag,
                System.currentTimeMillis(),
            )
        }.onSuccess { closeReview() }.onFailure(::fail)
    }
    private fun load() = screenModelScope.launch {
        runCatching {
            details.await(creatorId)
        }.onSuccess { value -> mutableState.update { it.copy(details = value, loading = false) } }.onFailure(::fail)
    }
    private fun fail(
        error: Throwable,
    ) = mutableState.update { it.copy(error = error.message ?: error::class.simpleName, loading = false) }
}
