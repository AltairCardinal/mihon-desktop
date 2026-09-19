package mihon.desktop.ui.authors

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
import tachiyomi.domain.creator.model.CreatorCardProjection
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.SourceCheckpoint
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.service.WorkMatchInput
import tachiyomi.domain.creator.service.WorkMatchScore
import tachiyomi.domain.creator.service.WorkMatchScorer
import tachiyomi.domain.creator.service.WorkMatchEvidenceKind
import tachiyomi.domain.creator.service.CreatorLibraryIndexState
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import tachiyomi.domain.creator.service.ChapterVariantInput
import tachiyomi.domain.creator.service.ChapterVariantNormalizer
import tachiyomi.domain.creator.service.ChapterVariantSummary
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.tachiyomi.source.CatalogueSource
import mihon.desktop.DesktopUiDependencies
import tachiyomi.domain.creator.service.OpenCreatorWorkVersion
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.NewCanonicalWorkDecision
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.service.CreatorIdentityEditor
import tachiyomi.domain.creator.model.WorkDecisionProjection
import tachiyomi.domain.creator.model.CreatorWorkArchiveFilter
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences



internal object AuthorsScreenModelFactory {
    fun root(dependencies: DesktopUiDependencies): AuthorsRootScreenModel = AuthorsRootScreenModel(
        getCreators = dependencies.getCreators,
        creatorArchive = requireNotNull(dependencies.creatorArchive),
        indexer = requireNotNull(dependencies.creatorLibraryIndexer),
        preferences = dependencies.creatorDiscoveryPreferences,
        preferredLanguages = dependencies.appPreferences.enabledLanguages::get,
        preferredLanguageChanges = dependencies.appPreferences.enabledLanguages.changes(),
        customCoverExists = dependencies.customCoverStore::customCoverExists,
        onSettingsSaved = {
            dependencies.creatorDiscoveryScheduler?.runIfDue()
        },
    )

    fun detail(
        creatorId: Long,
        collectOnOpen: Boolean,
        dependencies: DesktopUiDependencies,
    ): AuthorDetailScreenModel {
        val archive = requireNotNull(dependencies.creatorArchive)
        return AuthorDetailScreenModel(
            creatorId = creatorId,
            collectOnOpen = collectOnOpen,
            getCreatorDetails = dependencies.getCreatorDetails,
            getCreators = dependencies.getCreators,
            setCreatorFollow = dependencies.setCreatorFollow,
            discoveryScheduler = dependencies.creatorDiscoveryScheduler,
            creatorArchive = archive,
            saveSourceMangaForDetails = dependencies.saveSourceMangaForDetails,
            libraryPreferences = dependencies.libraryPreferences,
            identityActions = AuthorIdentityActions(
                requireNotNull(dependencies.manageCreatorIdentity),
            ),
        )
    }

    fun compare(candidateId: Long, creatorId: Long, dependencies: DesktopUiDependencies): WorkCompareScreenModel =
        WorkCompareScreenModel(
            candidateId = candidateId,
            creatorId = creatorId,
            creatorArchive = requireNotNull(dependencies.creatorArchive),
            saveSourceMangaForDetails = dependencies.saveSourceMangaForDetails,
            getChaptersByMangaId = dependencies.getChaptersByMangaId,
            sourceManager = dependencies.sourceManager,
        )
}

data class AuthorsRootState(
    val cards: List<CreatorCardProjection> = emptyList(),
    val query: String = "",
    val queryResetRevision: Long = 0,
    val followedOnly: Boolean = true,
    val hasMore: Boolean = false,
    val indexState: CreatorLibraryIndexState = CreatorLibraryIndexState.Idle,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

internal data class CreatorListScrollPosition(
    val index: Int = 0,
    val offset: Int = 0,
    val lastVisibleIndex: Int = index,
)

class AuthorsRootScreenModel(
    private val getCreators: GetCreators,
    private val creatorArchive: CreatorArchive,
    private val indexer: CreatorLibraryIndexer,
    preferences: tachiyomi.domain.creator.service.CreatorDiscoveryPreferences? = null,
    private val preferredLanguages: () -> Set<String> = { emptySet() },
    preferredLanguageChanges: Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet()),
    private val customCoverExists: (Long) -> Boolean = { false },
    onSettingsSaved: suspend () -> Unit = {},
) : ScreenModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsEditor = preferences?.let { tachiyomi.domain.creator.service.CreatorSettingsEditor(it, scope, onSettingsSaved) }
    private val mutableState = MutableStateFlow(AuthorsRootState(indexState = indexer.state.value))
    val state: StateFlow<AuthorsRootState> = mutableState.asStateFlow()
    private var pageGeneration = 0L
    private var pageJob: Job? = null
    private var followedScrollPosition = CreatorListScrollPosition()
    private var allAuthorsScrollPosition = CreatorListScrollPosition()
    private var lastTabActivationToken: String? = null

    init {
        loadFirstPage()
        scope.launch {
            var previousSnapshot: CreatorListRefreshSnapshot? = null
            combine(
                getCreators.subscribe(),
                getCreators.subscribeFollowed().map { rows -> rows.mapTo(mutableSetOf()) { it.creatorId } },
                preferredLanguageChanges,
                creatorArchive.observeUnreadWorks(1_000L).map { works ->
                    works.flatMap { work -> work.creatorIds.map { creatorId -> "$creatorId:${work.workKey}" } }.sorted()
                },
            ) { creators, followedIds, languages, unreadWorks ->
                CreatorListRefreshSnapshot(creators, followedIds, languages, unreadWorks)
            }.distinctUntilChanged().collect { snapshot ->
                if (previousSnapshot != null && previousSnapshot != snapshot) refreshLoadedPages()
                previousSnapshot = snapshot
            }
        }
        scope.launch {
            indexer.state.collect { index ->
                mutableState.update { it.copy(indexState = index) }
            }
        }
    }

    fun search(query: String) {
        val current = state.value
        if (query == current.query) return
        resetScrollPosition(current.followedOnly)
        requestPages(
            targetCount = 0,
            preserveCards = false,
            query = query,
            queryResetRevision = current.queryResetRevision + 1,
        )
    }

    fun showFollowing() = showTab(followedOnly = true)

    fun showAllAuthors() = showTab(followedOnly = false)

    fun onTabActivated(activationToken: String) {
        if (activationToken == lastTabActivationToken) return
        lastTabActivationToken = activationToken
        showFollowing()
    }

    internal fun scrollPosition(followedOnly: Boolean): CreatorListScrollPosition =
        if (followedOnly) followedScrollPosition else allAuthorsScrollPosition

    fun saveScrollPosition(followedOnly: Boolean, index: Int, offset: Int, lastVisibleIndex: Int = index) {
        val boundedIndex = index.coerceAtLeast(0)
        val position = CreatorListScrollPosition(
            index = boundedIndex,
            offset = offset.coerceAtLeast(0),
            lastVisibleIndex = lastVisibleIndex.coerceAtLeast(boundedIndex),
        )
        if (followedOnly) followedScrollPosition = position else allAuthorsScrollPosition = position
    }

    fun resetScrollPosition(followedOnly: Boolean) {
        saveScrollPosition(followedOnly, 0, 0)
    }

    fun loadNextPage() {
        val current = state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        val generation = pageGeneration
        mutableState.update { it.copy(loadingMore = true, error = null) }
        pageJob = scope.launch {
            try {
                val page = getPage(
                    offset = current.cards.size,
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

    fun retryIndex() = indexer.retry()

    private fun showTab(followedOnly: Boolean) {
        if (followedOnly == state.value.followedOnly) return
        val restorePosition = if (followedOnly) followedScrollPosition else allAuthorsScrollPosition
        mutableState.update { it.copy(followedOnly = followedOnly) }
        requestPages(targetCount = restorePosition.lastVisibleIndex + 1, preserveCards = false)
    }

    private fun loadFirstPage() {
        requestPages(targetCount = 0, preserveCards = false)
    }

    private fun refreshLoadedPages() {
        requestPages(targetCount = state.value.cards.size, preserveCards = true)
    }

    private fun requestPages(
        targetCount: Int,
        preserveCards: Boolean,
        query: String = state.value.query,
        queryResetRevision: Long = state.value.queryResetRevision,
    ) {
        pageJob?.cancel()
        val generation = ++pageGeneration
        val current = state.value.copy(query = query, queryResetRevision = queryResetRevision)
        mutableState.update {
            it.copy(
                query = query,
                queryResetRevision = queryResetRevision,
                cards = if (preserveCards) it.cards else emptyList(),
                hasMore = if (preserveCards) it.hasMore else false,
                loading = true,
                loadingMore = false,
                error = null,
            )
        }
        pageJob = scope.launch {
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
                        it.copy(cards = cards, hasMore = hasMore, loading = false, loadingMore = false, error = null)
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
            preferredLanguages = preferredLanguages(),
            customCoverExists = customCoverExists,
            query = query,
        )

    override fun onDispose() = scope.cancel()
}

private data class CreatorListRefreshSnapshot(
    val creators: List<Creator>,
    val followedIds: Set<Long>,
    val preferredLanguages: Set<String>,
    val unreadWorks: List<String>,
)

private const val CREATOR_CARD_PAGE_SIZE = 50

data class AuthorDetailState(
    val details: CreatorDetails = CreatorDetails(null, emptyList(), emptyList()),
    val allCreators: List<Creator> = emptyList(),
    val followed: Boolean = false,
    val manualAliases: List<String> = emptyList(),
    val discovery: CreatorDiscoveryTaskState? = null,
    val checkpoints: List<SourceCheckpoint> = emptyList(),
    val workArchive: CreatorWorkArchive = CreatorWorkArchive(emptyList(), emptyList(), emptyList()),
    val languageFilter: LanguageArchiveFilter = LanguageArchiveFilter.ALL,
    val workFilter: CreatorWorkArchiveFilter = CreatorWorkArchiveFilter(),
    val loading: Boolean = true,
    val actionRunning: Boolean = false,
    val followFeedback: Boolean? = null,
    val error: String? = null,
    val workDisplayModeOverride: LibraryDisplayMode? = null,
    val shelfDisplayMode: LibraryDisplayMode = LibraryDisplayMode.default,
    val workDisplayModeError: String? = null,
    val workOpenError: String? = null,
) {
    fun allVersionsForWork(workId: Long): List<SourceWorkArchiveVersion> =
        workArchive.works.firstOrNull { it.workId == workId }?.versions.orEmpty()

    val effectiveWorkDisplayMode: LibraryDisplayMode
        get() = (workDisplayModeOverride ?: shelfDisplayMode).let { mode ->
            if (mode == LibraryDisplayMode.CoverOnlyGrid) LibraryDisplayMode.CompactGrid else mode
        }

    val languageSummary: LanguageFilterSummary
        get() = LanguageFilterSummary.from(
            workArchive.works.flatMap { it.versions }.map { it.readingLanguage.certainty } +
                workArchive.pending.map { it.readingLanguage.certainty } +
                workArchive.rejected.map { it.readingLanguage.certainty },
        )

    val visibleWorkArchive: CreatorWorkArchive
        get() = workFilter.apply(CreatorWorkArchive(
            works = workArchive.works.mapNotNull { work ->
                work.copy(versions = work.versions.filter { languageFilter.accepts(it.readingLanguage.certainty) })
                    .takeIf { it.versions.isNotEmpty() }
            },
            pending = workArchive.pending.filter { languageFilter.accepts(it.readingLanguage.certainty) },
            rejected = workArchive.rejected.filter { languageFilter.accepts(it.readingLanguage.certainty) },
        ))
}

sealed interface AuthorDetailEffect {
    data class OpenManga(val mangaId: Long, val sourceWork: SourceWorkNaturalKey) : AuthorDetailEffect
    data class OpenCreator(val creatorId: Long) : AuthorDetailEffect
    data class OpenWorkCompare(val candidateId: Long, val creatorId: Long) : AuthorDetailEffect
    data object IdentityMerged : AuthorDetailEffect
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class AuthorDetailScreenModel(
    private val creatorId: Long,
    private val collectOnOpen: Boolean,
    private val getCreatorDetails: GetCreatorDetails,
    getCreators: GetCreators,
    private val setCreatorFollow: SetCreatorFollow,
    private val discoveryScheduler: CreatorDiscoveryScheduler?,
    private val creatorArchive: CreatorArchive?,
    private val identityActions: AuthorIdentityActions,
    private val saveSourceMangaForDetails: SaveSourceMangaForDetails? = null,
    private val libraryPreferences: LibraryPreferences? = null,
) : ScreenModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(
        AuthorDetailState(
            workDisplayModeOverride = libraryPreferences?.creatorWorkDisplayModeOverride()?.get(),
            shelfDisplayMode = libraryPreferences?.displayMode()?.get() ?: LibraryDisplayMode.default,
        ),
    )
    val state: StateFlow<AuthorDetailState> = mutableState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<AuthorDetailEffect>(extraBufferCapacity = 4)
    val effects = mutableEffects.asSharedFlow()

    val identityEditor = CreatorIdentityEditor(
        creatorId, identityActions.manageCreatorIdentity, scope,
    )
    private val activeCreatorId: Long get() = identityEditor.state.value.identity?.id ?: creatorId

    init {
        libraryPreferences?.let { preferences ->
            scope.launch {
                preferences.creatorWorkDisplayModeOverride().changes().collect { mode ->
                    mutableState.update { it.copy(workDisplayModeOverride = mode) }
                }
            }
            scope.launch {
                preferences.displayMode().changes().collect { mode ->
                    mutableState.update { it.copy(shelfDisplayMode = mode) }
                }
            }
        }
        scope.launch {
            identityEditor.state.map { it.identity }.distinctUntilChanged().collect { snapshot ->
                snapshot?.let { identity ->
                    mutableState.update { it.copy(followed = identity.followed) }
                    load()
                }
            }
        }
        scope.launch {
            getCreators.subscribe().collect { creators -> mutableState.update { it.copy(allCreators = creators) } }
        }
        scope.launch {
            getCreators.subscribeFollowed().collect { followed ->
                mutableState.update { it.copy(followed = followed.any { watch -> watch.creatorId == activeCreatorId }) }
            }
        }
        discoveryScheduler?.let { scheduler ->
            scope.launch { scheduler.state.collect { task -> mutableState.update { it.copy(discovery = task) } } }
        }
        creatorArchive?.let { archive ->
            scope.launch {
                archive.observe(creatorId).collect { workArchive ->
                    mutableState.update { it.copy(workArchive = workArchive) }
                }
            }
            scope.launch {
                identityEditor.state.map { it.identity?.id ?: creatorId }.distinctUntilChanged()
                    .flatMapLatest { archive.observeCheckpoints(it) }.collect { checkpoints ->
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
                discoveryScheduler?.runForCreator(activeCreatorId)?.join()
                load()
            }
        }
    }

    fun cancelDiscovery() = discoveryScheduler?.cancel()

    fun toggleFollow() = scope.launch {
        val target = !mutableState.value.followed
        runAction {
            setCreatorFollow.await(activeCreatorId, target)
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

    fun openVersion(version: SourceWorkArchiveVersion) = scope.launch {
        if (mutableState.value.actionRunning) return@launch
        mutableState.update { it.copy(actionRunning = true, workOpenError = null) }
        runCatching {
            val opener = OpenCreatorWorkVersion { listed ->
                requireNotNull(saveSourceMangaForDetails).awaitListedForDetails(
                    authorArchiveVersionSourceManga(listed), listed.naturalKey.sourceId,
                ).manga.id
            }
            mutableEffects.emit(AuthorDetailEffect.OpenManga(opener.await(version), version.naturalKey))
        }.onFailure { error ->
            mutableState.update { it.copy(workOpenError = error.message ?: error::class.simpleName) }
        }
        mutableState.update { it.copy(actionRunning = false) }
    }

    fun clearWorkOpenError() = mutableState.update { it.copy(workOpenError = null) }

    suspend fun markWorkSeenAfterNavigation(sourceWork: SourceWorkNaturalKey) {
        try {
            creatorArchive?.markWorkSeen(sourceWork, System.currentTimeMillis())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(workOpenError = error.message ?: error::class.simpleName) }
        }
    }

    fun openCandidate(candidate: DiscoveryCandidate) {
        mutableEffects.tryEmit(AuthorDetailEffect.OpenWorkCompare(candidate.id, activeCreatorId))
    }

    fun searchWorks(query: String) = mutableState.update { it.copy(workFilter = it.workFilter.copy(query = query)) }
    fun filterSource(sourceId: Long?) = mutableState.update { it.copy(workFilter = it.workFilter.copy(sourceId = sourceId)) }

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

    fun setLanguageFilter(filter: LanguageArchiveFilter) = mutableState.update { it.copy(languageFilter = filter) }

    fun clearError() = mutableState.update { it.copy(error = null) }

    override fun onDispose() = scope.cancel()

    private suspend fun load() {
        val requestedId = activeCreatorId
        runCatching {
            val details = getCreatorDetails.await(requestedId)
            val aliases = identityActions.getManualAliases(requestedId)
            details to aliases
        }.onSuccess { (details, aliases) ->
            if (activeCreatorId != requestedId) return@onSuccess
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
    val version: SourceWorkArchiveVersion,
    val canonicalWorkId: Long?,
    val score: WorkMatchScore,
) {
    val mangaId: Long get() = checkNotNull(version.mangaId)
    val title: String get() = version.title
    val isScriptVariant: Boolean get() = score.evidence.any { it.kind == WorkMatchEvidenceKind.TITLE_SCRIPT_VARIANT }
}

data class WorkCompareState(
    val version: SourceWorkArchiveVersion? = null,
    val suggestions: List<WorkComparisonSuggestion> = emptyList(),
    val currentDecision: WorkDecisionProjection? = null,
    val chapterSummary: ChapterVariantSummary? = null,
    val chapterError: String? = null,
    val loading: Boolean = true,
    val actionRunning: Boolean = false,
    val error: String? = null,
)

internal class WorkCompareScreenModel(
    private val candidateId: Long,
    private val creatorId: Long,
    private val creatorArchive: CreatorArchive,
    private val saveSourceMangaForDetails: SaveSourceMangaForDetails,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val sourceManager: SourceManager,
) : ScreenModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(WorkCompareState())
    val state: StateFlow<WorkCompareState> = mutableState.asStateFlow()

    init { scope.launch { load() } }

    fun confirm(target: WorkComparisonSuggestion? = null) = review(target, WorkDecisionState.CONFIRMED)

    fun reject(target: WorkComparisonSuggestion? = null) = review(target, WorkDecisionState.REJECTED)

    fun undo() = review(null, WorkDecisionState.SUGGESTED)

    fun setLanguage(dimension: LanguageDimension, tag: String) {
        if (mutableState.value.actionRunning || tag.isBlank()) return
        scope.launch {
            mutableState.update { it.copy(actionRunning = true, error = null) }
            runCatching {
                val version = checkNotNull(mutableState.value.version)
                creatorArchive.setLanguage(
                    ArchiveLanguageSubject.SourceWork(version.naturalKey),
                    dimension,
                    tag,
                    System.currentTimeMillis(),
                )
                load()
            }.onFailure { error ->
                mutableState.update { it.copy(error = error.message ?: error::class.simpleName) }
            }
            mutableState.update { it.copy(actionRunning = false) }
        }
    }

    fun undoLanguage(dimension: LanguageDimension) {
        if (mutableState.value.actionRunning) return
        scope.launch {
            mutableState.update { it.copy(actionRunning = true, error = null) }
            runCatching {
                val version = checkNotNull(mutableState.value.version)
                creatorArchive.withdrawLanguage(
                    ArchiveLanguageSubject.SourceWork(version.naturalKey),
                    dimension,
                    System.currentTimeMillis(),
                )
                load()
            }.onFailure { error ->
                mutableState.update { it.copy(error = error.message ?: error::class.simpleName) }
            }
            mutableState.update { it.copy(actionRunning = false) }
        }
    }

    override fun onDispose() = scope.cancel()

    private fun review(target: WorkComparisonSuggestion?, state: WorkDecisionState) {
        if (mutableState.value.actionRunning) return
        scope.launch {
            mutableState.update { it.copy(actionRunning = true, error = null) }
            runCatching {
                val version = checkNotNull(mutableState.value.version)
                val listed = saveSourceMangaForDetails.awaitListedForDetails(
                    authorArchiveVersionSourceManga(version),
                    version.naturalKey.sourceId,
                )
                val existing = mutableState.value.currentDecision
                val now = System.currentTimeMillis()
                val targetDecision = target?.version?.decision
                val targetWorkId = target?.canonicalWorkId
                    ?: targetDecision
                        ?.takeUnless { it.decision.state == WorkDecisionState.REJECTED }
                        ?.workId
                if (target != null && targetWorkId == null && targetDecision == null && existing == null) {
                    creatorArchive.createCanonicalWorkWithUserWorkDecisions(
                        primaryTitle = target.title,
                        creatorId = creatorId,
                        decisions = listOf(
                            NewCanonicalWorkDecision(
                                sourceWork = target.version.naturalKey,
                                expectedDecidedAt = target.version.decision?.decidedAt,
                                score = target.score.value,
                                evidence = target.score.reason,
                                decidedAt = now,
                                idempotencyKey = "desktop-script-variant:${target.version.sourceWorkId}:$now",
                            ),
                            NewCanonicalWorkDecision(
                                sourceWork = version.naturalKey,
                                expectedDecidedAt = version.decision?.decidedAt,
                                score = target.score.value,
                                evidence = target.score.reason,
                                decidedAt = now + 1,
                                idempotencyKey = "desktop-script-variant:${version.sourceWorkId}:$now",
                            ),
                        ),
                    )
                    load()
                    return@runCatching
                }
                val workId = targetWorkId ?: existing?.workId ?: creatorArchive.createWork(
                    title = target?.title ?: version.title,
                    creatorId = creatorId,
                    originalLanguage = null,
                ).id
                if (
                    target != null &&
                    target.canonicalWorkId == null &&
                    targetDecision?.decision?.state == WorkDecisionState.SUGGESTED
                ) {
                    creatorArchive.decide(
                        sourceWork = target.version.naturalKey,
                        workId = workId,
                        state = WorkDecisionState.CONFIRMED,
                        expectedDecidedAt = targetDecision.decidedAt,
                        score = target.score.value,
                        evidence = target.score.reason,
                        decidedAt = now,
                        idempotencyKey = "desktop-review:${target.version.sourceWorkId}:$workId:$now",
                    )
                }
                creatorArchive.decide(
                    sourceWork = version.naturalKey,
                    workId = workId,
                    state = state,
                    expectedDecidedAt = existing?.takeIf { it.workId == workId }?.decidedAt,
                    score = target?.score?.value ?: 1.0,
                    evidence = target?.score?.reason ?: "manual-singleton-review",
                    decidedAt = now + 1,
                    idempotencyKey = "desktop-review:${listed.manga.id}:$workId:${now + 1}",
                )
                load()
            }.onFailure { error ->
                mutableState.update { it.copy(error = error.message ?: error::class.simpleName) }
            }
            mutableState.update { it.copy(actionRunning = false) }
        }
    }

    private suspend fun load() {
        val archive = creatorArchive.get(creatorId)
        val versions = archive.works.flatMap { it.versions } + archive.pending + archive.rejected
        val version = versions.firstOrNull { it.sourceWorkId == candidateId }
        if (version == null) {
            mutableState.value = WorkCompareState(loading = false)
            return
        }
        val candidateInput = WorkMatchInput(
            title = version.title,
            creators = emptyList(),
            language = version.readingLanguage.tag.takeUnless { it == "und" },
            chapterCount = chapterCountForWorkMatching(version),
        )
        val canonicalWorkIds = archive.works.flatMap { work -> work.versions.map { it.sourceWorkId to work.workId } }.toMap()
        val currentCanonicalWorkId = canonicalWorkIds[version.sourceWorkId]
        val suggestions = versions.filter { other ->
            other.sourceWorkId != version.sourceWorkId &&
                other.mangaId != null &&
                other.decision?.decision?.state != WorkDecisionState.REJECTED &&
                (currentCanonicalWorkId == null || canonicalWorkIds[other.sourceWorkId] != currentCanonicalWorkId)
        }.map { other ->
            WorkComparisonSuggestion(
                version = other,
                canonicalWorkId = canonicalWorkIds[other.sourceWorkId],
                score = WorkMatchScorer.score(
                    candidateInput,
                    WorkMatchInput(
                        title = other.title,
                        creators = emptyList(),
                        language = other.readingLanguage.tag.takeUnless { it == "und" },
                        chapterCount = chapterCountForWorkMatching(other),
                    ),
                ),
            )
        }.sortedByDescending { it.score.value }
        val chapterResult = runCatching { loadChapterSummary(version) }
        mutableState.value = WorkCompareState(
            version = version,
            suggestions = suggestions,
            currentDecision = version.decision,
            chapterSummary = chapterResult.getOrNull(),
            chapterError = chapterResult.exceptionOrNull()?.message,
            loading = false,
        )
    }

    private suspend fun loadChapterSummary(
        version: SourceWorkArchiveVersion,
    ): ChapterVariantSummary {
        val cached = creatorArchive.getChapterVariants(version.naturalKey)
        if (cached.isNotEmpty()) {
            return ChapterVariantNormalizer.summarize(
                cached.map { ChapterVariantInput(it.naturalKey, it.rawName, it.chapterNumber ?: -1.0, it.scanlator) },
            )
        }
        val listed = authorArchiveVersionSourceManga(version)
        var saved = saveSourceMangaForDetails.awaitListedForDetails(listed, version.naturalKey.sourceId)
        if (saved.needsRefresh) {
            val source = sourceManager.get(version.naturalKey.sourceId) as? CatalogueSource
            if (source != null) {
                saved = saved.copy(manga = saveSourceMangaForDetails.awaitFromSource(source, listed), needsRefresh = false)
            }
        }
        val inputs = getChaptersByMangaId.await(saved.manga.id).map { chapter ->
            ChapterVariantInput(
                naturalKey = chapter.url,
                rawName = chapter.name,
                recognizedChapterNumber = chapter.chapterNumber,
                scanlator = chapter.scanlator,
            )
        }
        val summary = ChapterVariantNormalizer.summarize(inputs)
        if (summary.variants.isNotEmpty()) {
            creatorArchive.replaceChapterVariants(version.naturalKey, summary.variants, System.currentTimeMillis())
        }
        return summary
    }
}

internal fun chapterCountForWorkMatching(version: SourceWorkArchiveVersion): Int? =
    version.chapterCount.takeUnless { version.chapterCompleteness == ChapterCatalogCompleteness.UNKNOWN }?.toInt()

internal fun authorArchiveVersionSourceManga(version: SourceWorkArchiveVersion): SManga =
    SManga.create().apply {
        url = version.naturalKey.stableSourceUrl
        title = version.title
        thumbnail_url = version.thumbnailUrl
    }
