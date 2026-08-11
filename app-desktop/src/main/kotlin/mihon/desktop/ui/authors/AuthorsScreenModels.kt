package mihon.desktop.ui.authors

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.desktop.domain.CreatorDiscoveryScheduler
import mihon.desktop.domain.CreatorDiscoveryTaskState
import mihon.desktop.domain.SaveSourceMangaForDetails
import tachiyomi.domain.creator.interactor.CreatorDetails
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.SourceCheckpoint
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.model.WorkMatchState
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.WorkMatchInput
import tachiyomi.domain.creator.service.WorkMatchScore
import tachiyomi.domain.creator.service.WorkMatchScorer
import tachiyomi.domain.creator.service.CreatorLibraryIndexState
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import mihon.desktop.DesktopUiDependencies

internal object AuthorsScreenModelFactory {
    fun root(dependencies: DesktopUiDependencies): AuthorsRootScreenModel = AuthorsRootScreenModel(
        dependencies.getCreators,
        requireNotNull(dependencies.creatorLibraryIndexer),
    )

    fun detail(
        creatorId: Long,
        collectOnOpen: Boolean,
        dependencies: DesktopUiDependencies,
    ): AuthorDetailScreenModel {
        val archive = requireNotNull(dependencies.creatorArchiveRepository)
        return AuthorDetailScreenModel(
            creatorId = creatorId,
            collectOnOpen = collectOnOpen,
            getCreatorDetails = dependencies.getCreatorDetails,
            getCreators = dependencies.getCreators,
            setCreatorFollow = dependencies.setCreatorFollow,
            discoveryScheduler = dependencies.creatorDiscoveryScheduler,
            archiveRepository = archive,
            identityActions = AuthorIdentityActions(
                requireNotNull(dependencies.manageCreatorIdentity),
            ),
        )
    }

    fun compare(candidateId: Long, creatorId: Long, dependencies: DesktopUiDependencies): WorkCompareScreenModel =
        WorkCompareScreenModel(
            candidateId = candidateId,
            creatorId = creatorId,
            getCreatorDetails = dependencies.getCreatorDetails,
            creatorRepository = requireNotNull(dependencies.creatorRepository),
            archiveRepository = requireNotNull(dependencies.creatorArchiveRepository),
            saveSourceMangaForDetails = dependencies.saveSourceMangaForDetails,
        )
}

data class AuthorsRootState(
    val creators: List<Creator> = emptyList(),
    val followedIds: Set<Long> = emptySet(),
    val query: String = "",
    val indexState: CreatorLibraryIndexState = CreatorLibraryIndexState.Idle,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val filteredCreators: List<Creator>
        get() = creators.filter { it.displayName.contains(query, ignoreCase = true) }
}

class AuthorsRootScreenModel(
    getCreators: GetCreators,
    private val indexer: CreatorLibraryIndexer,
) : ScreenModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val mutableState = MutableStateFlow(AuthorsRootState(indexState = indexer.state.value))
    val state: StateFlow<AuthorsRootState> = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(getCreators.subscribe(), getCreators.subscribeFollowed(), indexer.state) { creators, followed, index ->
                Triple(creators, followed.mapTo(mutableSetOf()) { it.creatorId }, index)
            }.catch { error -> mutableState.update { it.copy(loading = false, error = error.message) } }
                .collect { (creators, followed, index) ->
                    mutableState.update {
                        it.copy(creators = creators, followedIds = followed, indexState = index, loading = false, error = null)
                    }
                }
        }
    }

    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }

    fun retryIndex() = indexer.retry()

    override fun onDispose() = scope.cancel()
}

data class AuthorDetailState(
    val details: CreatorDetails = CreatorDetails(null, emptyList(), emptyList()),
    val allCreators: List<Creator> = emptyList(),
    val followed: Boolean = false,
    val manualAliases: List<String> = emptyList(),
    val discovery: CreatorDiscoveryTaskState? = null,
    val checkpoints: List<SourceCheckpoint> = emptyList(),
    val loading: Boolean = true,
    val actionRunning: Boolean = false,
    val followFeedback: Boolean? = null,
    val error: String? = null,
)

sealed interface AuthorDetailEffect {
    data class OpenManga(val mangaId: Long) : AuthorDetailEffect
    data class OpenCreator(val creatorId: Long) : AuthorDetailEffect
    data class OpenWorkCompare(val candidateId: Long, val creatorId: Long) : AuthorDetailEffect
    data object IdentityMerged : AuthorDetailEffect
}

internal class AuthorDetailScreenModel(
    private val creatorId: Long,
    private val collectOnOpen: Boolean,
    private val getCreatorDetails: GetCreatorDetails,
    getCreators: GetCreators,
    private val setCreatorFollow: SetCreatorFollow,
    private val discoveryScheduler: CreatorDiscoveryScheduler?,
    private val archiveRepository: CreatorArchiveRepository?,
    private val identityActions: AuthorIdentityActions,
) : ScreenModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(AuthorDetailState())
    val state: StateFlow<AuthorDetailState> = mutableState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<AuthorDetailEffect>(extraBufferCapacity = 4)
    val effects = mutableEffects.asSharedFlow()

    init {
        scope.launch {
            getCreators.subscribe().collect { creators -> mutableState.update { it.copy(allCreators = creators) } }
        }
        scope.launch {
            getCreators.subscribeFollowed().collect { followed ->
                mutableState.update { it.copy(followed = followed.any { watch -> watch.creatorId == creatorId }) }
            }
        }
        discoveryScheduler?.let { scheduler ->
            scope.launch { scheduler.state.collect { task -> mutableState.update { it.copy(discovery = task) } } }
        }
        archiveRepository?.let { repository ->
            scope.launch {
                repository.observeSourceCheckpoints(creatorId).collect { checkpoints ->
                    mutableState.update { it.copy(checkpoints = checkpoints) }
                }
            }
        }
        scope.launch {
            load()
            val details = mutableState.value.details
            if (shouldCollectAuthorOnOpen(collectOnOpen, details.candidates, details.mangaLinks)) refreshDiscovery()
        }
    }

    fun refreshDiscovery() {
        if (mutableState.value.actionRunning) return
        scope.launch {
            runAction {
                discoveryScheduler?.runForCreator(creatorId)?.join()
                load()
            }
        }
    }

    fun cancelDiscovery() = discoveryScheduler?.cancel()

    fun toggleFollow() = scope.launch {
        val target = !mutableState.value.followed
        runAction {
            setCreatorFollow.await(creatorId, target)
            mutableState.update { it.copy(followFeedback = target) }
        }
    }

    fun addAlias(alias: String) = scope.launch {
        runAction { identityActions.addAlias(creatorId, alias); load() }
    }

    fun removeAlias(alias: String) = scope.launch {
        runAction { identityActions.removeAlias(creatorId, alias); load() }
    }

    fun merge(targetId: Long) = scope.launch {
        runAction {
            identityActions.merge(creatorId, targetId)
            mutableEffects.emit(AuthorDetailEffect.IdentityMerged)
        }
    }

    fun split(mangaIds: Set<Long>, name: String) = scope.launch {
        runAction {
            val newCreatorId = identityActions.split(creatorId, mangaIds, name)
            mutableEffects.emit(AuthorDetailEffect.OpenCreator(newCreatorId))
        }
    }

    fun openCandidate(candidate: DiscoveryCandidate) {
        mutableEffects.tryEmit(AuthorDetailEffect.OpenWorkCompare(candidate.id, creatorId))
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    override fun onDispose() = scope.cancel()

    private suspend fun load() {
        runCatching {
            val details = getCreatorDetails.await(creatorId)
            val aliases = identityActions.getManualAliases(creatorId)
            details to aliases
        }.onSuccess { (details, aliases) ->
            mutableState.update { it.copy(details = details, manualAliases = aliases, loading = false, error = null) }
        }.onFailure { error ->
            mutableState.update { it.copy(loading = false, error = error.message ?: error::class.simpleName) }
        }
    }

    private suspend fun runAction(action: suspend () -> Unit) {
        mutableState.update { it.copy(actionRunning = true, error = null) }
        runCatching { action() }
            .onFailure { error -> mutableState.update { it.copy(error = error.message ?: error::class.simpleName) } }
        mutableState.update { it.copy(actionRunning = false) }
    }
}

data class WorkComparisonSuggestion(
    val mangaId: Long,
    val title: String,
    val score: WorkMatchScore,
)

data class WorkCompareState(
    val candidate: DiscoveryCandidate? = null,
    val suggestions: List<WorkComparisonSuggestion> = emptyList(),
    val currentDecision: tachiyomi.domain.creator.model.WorkDecisionProjection? = null,
    val loading: Boolean = true,
    val actionRunning: Boolean = false,
    val error: String? = null,
)

internal class WorkCompareScreenModel(
    private val candidateId: Long,
    private val creatorId: Long,
    private val getCreatorDetails: GetCreatorDetails,
    private val creatorRepository: CreatorRepository,
    private val archiveRepository: CreatorArchiveRepository,
    private val saveSourceMangaForDetails: SaveSourceMangaForDetails,
) : ScreenModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(WorkCompareState())
    val state: StateFlow<WorkCompareState> = mutableState.asStateFlow()

    init { scope.launch { load() } }

    fun confirm(target: WorkComparisonSuggestion? = null) = review(target, WorkDecisionState.CONFIRMED)

    fun reject(target: WorkComparisonSuggestion? = null) = review(target, WorkDecisionState.REJECTED)

    fun undo() = review(null, WorkDecisionState.SUGGESTED)

    override fun onDispose() = scope.cancel()

    private fun review(target: WorkComparisonSuggestion?, state: WorkDecisionState) {
        if (mutableState.value.actionRunning) return
        scope.launch {
            mutableState.update { it.copy(actionRunning = true, error = null) }
            runCatching {
                val candidate = checkNotNull(mutableState.value.candidate)
                val listed = saveSourceMangaForDetails.awaitListedForDetails(
                    authorCandidateSourceManga(candidate),
                    candidate.source,
                )
                val existing = mutableState.value.currentDecision
                val workId = existing?.workId ?: creatorRepository.createCanonicalWork(
                    primaryTitle = target?.title ?: candidate.title,
                    primaryCreatorId = creatorId,
                    originalLanguage = null,
                ).id
                if (target != null && existing == null) {
                    creatorRepository.upsertMangaWorkMatch(
                        mangaId = target.mangaId,
                        workId = workId,
                        confidence = target.score.value,
                        matchReason = target.score.reason,
                        state = WorkMatchState.CONFIRMED,
                        manuallyConfirmed = true,
                    )
                }
                creatorRepository.upsertMangaWorkMatch(
                    mangaId = listed.manga.id,
                    workId = workId,
                    confidence = target?.score?.value ?: 1.0,
                    matchReason = target?.score?.reason ?: "manual singleton review",
                    state = when (state) {
                        WorkDecisionState.SUGGESTED -> WorkMatchState.CANDIDATE
                        WorkDecisionState.CONFIRMED -> WorkMatchState.CONFIRMED
                        WorkDecisionState.REJECTED -> WorkMatchState.REJECTED
                    },
                    manuallyConfirmed = true,
                )
                load()
            }.onFailure { error ->
                mutableState.update { it.copy(error = error.message ?: error::class.simpleName) }
            }
            mutableState.update { it.copy(actionRunning = false) }
        }
    }

    private suspend fun load() {
        val details = getCreatorDetails.await(creatorId)
        val candidate = details.candidates.firstOrNull { it.id == candidateId }
            ?: creatorRepository.getDiscoveryCandidate(candidateId)
        if (candidate == null) {
            mutableState.value = WorkCompareState(loading = false)
            return
        }
        val candidateInput = WorkMatchInput(
            title = candidate.title,
            creators = listOfNotNull(candidate.authorText, candidate.artistText),
            language = candidate.languageTag,
        )
        val suggestions = details.mangaLinks.distinctBy { it.mangaId }.map { link ->
            val title = details.mangaTitles[link.mangaId] ?: "Manga ${link.mangaId}"
            WorkComparisonSuggestion(
                mangaId = link.mangaId,
                title = title,
                score = WorkMatchScorer.score(
                    candidateInput,
                    WorkMatchInput(title = title, creators = listOfNotNull(link.sourceText), language = null),
                ),
            )
        }.sortedByDescending { it.score.value }
        val decisions = archiveRepository.getWorkDecisions(SourceWorkNaturalKey(candidate.source, candidate.url))
        mutableState.value = WorkCompareState(
            candidate = candidate,
            suggestions = suggestions,
            currentDecision = decisions.firstOrNull(),
            loading = false,
        )
    }
}
