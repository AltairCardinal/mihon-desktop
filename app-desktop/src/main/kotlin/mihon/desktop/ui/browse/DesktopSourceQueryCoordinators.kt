package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.network.SourceFailureIdentity
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.desktop.extension.InstalledExtension
import mihon.domain.error.AppError
import tachiyomi.domain.source.service.GlobalSearchSourceFilter
import tachiyomi.domain.source.service.SOURCE_COMPATIBILITY_LOG_TAG
import tachiyomi.domain.source.service.SourceFilterResult
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceMetadataSnapshot
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourcePageResult
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryReducer
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.domain.source.service.prepareSourceFilters
import tachiyomi.domain.source.service.prepareSourceMetadata
import tachiyomi.domain.source.service.recordSourceFailureDiagnostics
import tachiyomi.domain.source.service.sourceRecoveryAction
import tachiyomi.domain.source.service.toSourceAppError
import tachiyomi.domain.source.service.withSourceFailureIdentity
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

internal fun logSourceCompatibility(message: String) {
    System.err.println("[$SOURCE_COMPATIBILITY_LOG_TAG] $message")
}

sealed interface DesktopSourceRecoveryIntent {
    data class Retry(val request: SourcePageRequest) : DesktopSourceRecoveryIntent
    data class OpenLogin(
        val url: String,
        val request: SourcePageRequest,
    ) : DesktopSourceRecoveryIntent
    data object None : DesktopSourceRecoveryIntent
}

internal fun desktopSourceRecoveryActionLabel(
    recoveryAction: SourceRecoveryAction,
    locale: Locale = Locale.getDefault(),
): String? = when (recoveryAction) {
    SourceRecoveryAction.OpenLogin -> MR.strings.login.localized(locale)
    SourceRecoveryAction.Retry -> MR.strings.action_retry.localized(locale)
    SourceRecoveryAction.None -> null
}

internal data class StampedSourceQueryState(
    val ordinal: Long,
    val state: SourceQueryState?,
)

internal class SourceQueryStatePublisher {
    private val publications = MutableStateFlow(StampedSourceQueryState(0, null))
    val states: Flow<SourceQueryState?> = publications.map { it.state }.distinctUntilChanged()
    val current: StampedSourceQueryState
        get() = publications.value
    val subscriberCount: Int get() = publications.subscriptionCount.value

    fun publish(candidate: StampedSourceQueryState) {
        publications.update { current -> if (candidate.ordinal > current.ordinal) candidate else current }
    }
}

class SourceBrowseQueryCoordinator(
    private val service: SourceMangaSearchService,
    private val failureIdentity: SourceFailureIdentity?,
    private val sourceFailureLog: (String) -> Unit,
    initialMetadata: SourceMetadataSnapshot?,
) {
    constructor(
        service: SourceMangaSearchService,
        failureIdentity: SourceFailureIdentity?,
        sourceFailureLog: (String) -> Unit,
    ) :
        this(service, failureIdentity, sourceFailureLog, null)
    constructor(service: SourceMangaSearchService) : this(service, null, ::logSourceCompatibility)
    constructor(service: SourceMangaSearchService, failureIdentity: SourceFailureIdentity?) :
        this(service, failureIdentity, ::logSourceCompatibility)

    private data class SourceLoadKey(
        val sourceId: Long,
        val generation: Long,
        val page: Int,
    )

    private val reducer = SourceQueryReducer()
    private val lock = Any()
    private var generation = 0L
    private var publicationOrdinal = 0L
    private var authoritativeState: SourceQueryState? = null
    private var failedFilterRequest: SourcePageRequest? = null
    private val pendingLoads = mutableMapOf<SourceLoadKey, CompletableDeferred<SourceQueryState>>()
    private val publisher = SourceQueryStatePublisher()
    private val firstMetadata = AtomicReference(initialMetadata)
    private val preparedMetadata = AtomicReference(initialMetadata)
    internal val metadata: SourceMetadataSnapshot? get() = preparedMetadata.get()
    private val hasPreparedMetadata = initialMetadata != null

    val states: Flow<SourceQueryState?> = publisher.states
    val state: SourceQueryState?
        get() = synchronized(lock) { authoritativeState }
    internal val subscriberCount: Int get() = publisher.subscriberCount

    suspend fun load(
        source: Source,
        page: Int,
        query: SourceQuery,
        onStarted: (SourceQueryState) -> Unit = {},
    ): SourceQueryState = loadPrepared(source, page, query, null, onStarted)

    internal suspend fun loadSearch(
        source: Source,
        query: String,
        onStarted: (SourceQueryState) -> Unit,
    ): SourceQueryState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val metadata = if (hasPreparedMetadata) {
            firstMetadata.getAndSet(null) ?: prepareSourceMetadata(source, failureIdentity?.sourceId)
        } else {
            null
        }
        preparedMetadata.set(metadata)
        val prepared = metadata?.error?.let(SourceFilterResult::Failure) ?: prepareSourceFilters(source)
        loadPrepared(
            source,
            1,
            SourceQuery.Search(
                query,
                (prepared as? SourceFilterResult.Content)?.filters
                    ?: eu.kanade.tachiyomi.source.model.FilterList(),
            ),
            (prepared as? SourceFilterResult.Failure)?.error,
            onStarted,
        )
    }

    private suspend fun loadPrepared(
        source: Source,
        page: Int,
        query: SourceQuery,
        preparationError: AppError?,
        onStarted: (SourceQueryState) -> Unit,
    ): SourceQueryState {
        val pending = CompletableDeferred<SourceQueryState>()
        var shared: CompletableDeferred<SourceQueryState>? = null
        var previous: SourceQueryState? = null
        lateinit var request: SourcePageRequest
        lateinit var key: SourceLoadKey
        val sourceId = failureIdentity?.sourceId ?: guardSourceOperation(SourceOperationPhase.METADATA) { source.id }
        val started = synchronized(lock) {
            if (page == 1) generation += 1
            request = SourcePageRequest(sourceId, page, generation, query)
            key = request.loadKey()
            pendingLoads[key]?.let {
                if (!matchesCurrentRequestLocked(request)) return requireNotNull(authoritativeState)
                shared = it
                return@synchronized null
            }
            if (page > 1 && !canAppendLocked(request)) return requireNotNull(authoritativeState)
            previous = authoritativeState
            failedFilterRequest = request.takeIf { preparationError != null }
            pendingLoads[key] = pending
            commitLocked(reducer.start(request, authoritativeState))
        }
        shared?.let { return it.await() }
        val owned = requireNotNull(started)
        return try {
            publisher.publish(owned)
            onStarted(requireNotNull(owned.state))
            currentCoroutineContext().ensureActive()
            completeLoad(source, request, preparationError).also(pending::complete)
        } catch (error: Throwable) {
            val restored = synchronized(lock) {
                val current = authoritativeState
                if (current?.isLoading == true && current.request.loadKey() == key) restoreLocked(previous) else null
            }
            restored?.let(publisher::publish)
            pending.completeExceptionally(error)
            throw error
        } finally {
            synchronized(lock) { pendingLoads.remove(key, pending) }
        }
    }

    private fun SourcePageRequest.loadKey() = SourceLoadKey(sourceId, generation, page)

    private fun matchesCurrentRequestLocked(request: SourcePageRequest): Boolean =
        authoritativeState?.request?.let { it.loadKey() == request.loadKey() && it.query == request.query } == true

    private fun canAppendLocked(request: SourcePageRequest): Boolean {
        val current = authoritativeState as? SourceQueryState.Content ?: return false
        return !current.isLoading && current.pageError == null && current.hasNextPage &&
            current.request.sourceId == request.sourceId && current.request.generation == request.generation &&
            current.request.query == request.query && current.request.page + 1 == request.page
    }

    private suspend fun completeLoad(
        source: Source,
        request: SourcePageRequest,
        preparationError: AppError? = null,
    ): SourceQueryState {
        val loaded = if (preparationError != null) {
            SourcePageResult.Failure(request, preparationError, preparationError.sourceRecoveryAction())
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                service.loadPageResult(source, request)
            }
        }
        val result = if (loaded is SourcePageResult.Failure) {
            loaded.copy(
                error = loaded.error.withSourceFailureIdentity(
                    (failureIdentity ?: SourceFailureIdentity()).copy(sourceId = request.sourceId),
                ),
            )
        } else {
            loaded
        }
        currentCoroutineContext().ensureActive()
        val current = synchronized(lock) {
            authoritativeState?.takeIf { it.request == request }
        } ?: return requireNotNull(state)
        // Reduction reads extension-owned returned URLs; never run those getters under the host lock.
        val reduced = reducer.reduce(current, result).withFailureIdentity(request.sourceId)
        currentCoroutineContext().ensureActive()
        val completed = synchronized(lock) {
            if (authoritativeState !== current) return@synchronized null
            commitLocked(reduced)
        }
        completed?.state?.failureError()?.recordSourceFailureDiagnostics(sourceFailureLog)
        completed?.let(publisher::publish)
        return completed?.state ?: requireNotNull(state)
    }

    suspend fun retry(
        source: Source,
        request: SourcePageRequest,
    ): SourceQueryState? = restart(source, request, SourceRecoveryAction.Retry)

    suspend fun retryAfterLogin(
        source: Source,
        request: SourcePageRequest,
    ): SourceQueryState? = restart(source, request, SourceRecoveryAction.OpenLogin)

    private suspend fun restart(
        source: Source,
        request: SourcePageRequest,
        expectedAction: SourceRecoveryAction,
    ): SourceQueryState? {
        var prepareFiltersAgain = false
        var previous: SourceQueryState? = null
        val previousMetadata = preparedMetadata.get()
        val started = synchronized(lock) {
            val current = authoritativeState
            if (current?.request != request || current.recoveryAction() != expectedAction) {
                return@synchronized null
            }
            previous = current
            prepareFiltersAgain = failedFilterRequest == request
            commitLocked(reducer.start(request, current))
        }
        if (started == null) return null
        publisher.publish(started)
        if (prepareFiltersAgain) {
            return try {
                loadSearch(source, (request.query as SourceQuery.Search).query) {}
            } catch (error: Throwable) {
                val restored = synchronized(lock) {
                    if (authoritativeState !== started.state) return@synchronized null
                    failedFilterRequest = request
                    preparedMetadata.set(previousMetadata)
                    restoreLocked(previous)
                }
                restored?.let(publisher::publish)
                throw error
            }
        }
        return completeLoad(source, request)
    }

    suspend fun retry(
        source: Source,
    ): SourceQueryState {
        val request = synchronized(lock) {
            requireNotNull(state?.request) { "No source request to retry" }
        }
        return requireNotNull(retry(source, request)) { "Current source error is not retryable" }
    }

    private fun commitLocked(state: SourceQueryState): StampedSourceQueryState {
        authoritativeState = state
        return StampedSourceQueryState(++publicationOrdinal, state)
    }

    private fun restoreLocked(state: SourceQueryState?): StampedSourceQueryState {
        authoritativeState = state
        return StampedSourceQueryState(++publicationOrdinal, state)
    }

    fun recoveryIntent(source: Source): DesktopSourceRecoveryIntent = recoveryIntent(source, state?.request)

    internal fun recoveryIntent(source: Source, expectedRequest: SourcePageRequest?): DesktopSourceRecoveryIntent {
        val captured = state ?: return DesktopSourceRecoveryIntent.None
        if (captured.request != expectedRequest || metadata?.source?.let { it !== source } == true) {
            return DesktopSourceRecoveryIntent.None
        }
        if (captured.recoveryAction() != SourceRecoveryAction.OpenLogin) {
            return desktopSourceRecoveryIntent(captured)
        }
        val http = source as? HttpSource ?: return DesktopSourceRecoveryIntent.None
        val url = try {
            guardSourceOperation(SourceOperationPhase.METADATA) { http.baseUrl }
        } catch (error: Exception) {
            error.rethrowSourceControlFailure()
            val appError = error.toSourceAppError().withSourceFailureIdentity(
                (failureIdentity ?: SourceFailureIdentity()).copy(sourceId = captured.request.sourceId),
            )
            val reduced = reducer.reduce(
                captured,
                SourcePageResult.Failure(captured.request, appError, appError.sourceRecoveryAction()),
            )
            val failed = synchronized(lock) {
                if (authoritativeState !== captured) return@synchronized null
                commitLocked(reduced)
            }
            if (failed != null) {
                appError.recordSourceFailureDiagnostics(sourceFailureLog)
                publisher.publish(failed)
            }
            return DesktopSourceRecoveryIntent.None
        }
        return if (state === captured && url.startsWith("http")) {
            DesktopSourceRecoveryIntent.OpenLogin(url, captured.request)
        } else {
            DesktopSourceRecoveryIntent.None
        }
    }

    private fun SourceQueryState.withFailureIdentity(sourceId: Long): SourceQueryState {
        val identity = (failureIdentity ?: SourceFailureIdentity()).copy(sourceId = sourceId)
        return when (this) {
            is SourceQueryState.Failure -> copy(error = error.withSourceFailureIdentity(identity))
            is SourceQueryState.Content -> copy(
                pageError = pageError?.let {
                    it.copy(error = it.error.withSourceFailureIdentity(identity))
                },
            )
            else -> this
        }
    }

    private fun SourceQueryState.failureError(): AppError? = when (this) {
        is SourceQueryState.Failure -> error
        is SourceQueryState.Content -> pageError?.error
        else -> null
    }

    private fun recoveryAction(): SourceRecoveryAction? = when (val current = state) {
        is SourceQueryState.Content -> current.pageError?.recoveryAction
        is SourceQueryState.Failure -> current.recoveryAction
        else -> null
    }
}

private fun desktopSourceRecoveryIntent(
    state: SourceQueryState?,
): DesktopSourceRecoveryIntent = when (state.recoveryAction()) {
    SourceRecoveryAction.Retry -> state?.request?.let(DesktopSourceRecoveryIntent::Retry)
        ?: DesktopSourceRecoveryIntent.None
    SourceRecoveryAction.OpenLogin -> DesktopSourceRecoveryIntent.None
    SourceRecoveryAction.None, null -> DesktopSourceRecoveryIntent.None
}

private fun SourceQueryState?.recoveryAction(): SourceRecoveryAction? = when (this) {
    is SourceQueryState.Content -> pageError?.recoveryAction
    is SourceQueryState.Failure -> recoveryAction
    else -> null
}

data class DesktopGlobalSearchState(
    val generation: Long = 0,
    val isSearching: Boolean = false,
    val queryStates: Map<Long, SourceQueryState> = emptyMap(),
    internal val publicationOrdinal: Long = 0,
    val sourceMetadata: List<SourceMetadataSnapshot> = emptyList(),
    val sourceIdentities: Map<Long, SourceFailureIdentity> = emptyMap(),
)

class DesktopGlobalSearchCoordinator(
    private val service: SourceMangaSearchService,
    private val sourceFailureLog: (String) -> Unit,
) : AutoCloseable {
    constructor(service: SourceMangaSearchService) : this(service, ::logSourceCompatibility)

    private data class SearchIdentity(
        val query: String,
        val sourceFilter: GlobalSearchSourceFilter,
        val sourceIds: List<Long?>,
        val owners: List<Pair<Source, SourceFailureIdentity>>,
    ) {
        fun sameSearch(other: SearchIdentity): Boolean =
            query == other.query && sourceFilter == other.sourceFilter && sourceIds == other.sourceIds &&
                owners.size == other.owners.size && owners.zip(other.owners).all { (first, second) ->
                    first.first === second.first && first.second == second.second
                }

        fun containsOwner(owner: Pair<Source, SourceFailureIdentity>): Boolean =
            owners.any { it.first === owner.first && it.second == owner.second }
    }

    private class SearchSession(
        val generation: Long,
        val identity: SearchIdentity,
        val coordinators: Map<Long, SourceBrowseQueryCoordinator>,
        onState: (DesktopGlobalSearchState) -> Unit,
    ) {
        private class Retirement(val token: Any) : CancellationException()
        private val lock = Any()
        private val queryJobs = mutableListOf<Job>()
        private val collectorJob = SupervisorJob()
        private val collectorScope = CoroutineScope(collectorJob)
        private val retirementToken = Any()
        private val retirementCause = Retirement(retirementToken)
        private var retired = false

        @Volatile var callback: ((DesktopGlobalSearchState) -> Unit)? = onState
            private set

        fun register(job: Job) {
            val cause = synchronized(lock) {
                if (retired) {
                    retirementCause
                } else {
                    queryJobs += job
                    null
                }
            }
            cause?.let(job::cancel)
        }

        fun collect(block: suspend CoroutineScope.() -> Unit) {
            collectorScope.launch(start = CoroutineStart.UNDISPATCHED, block = block)
        }

        fun unbindCallback() {
            callback = null
        }

        fun owns(error: CancellationException): Boolean {
            val visited = mutableListOf<Throwable>()
            var cause: Throwable? = error
            while (cause != null && visited.none { it === cause }) {
                if (cause is Retirement && cause.token === retirementToken) return true
                visited += cause
                cause = cause.cause
            }
            return false
        }

        fun retire() {
            unbindCallback()
            val active = synchronized(lock) {
                retired = true
                queryJobs.toList().also { queryJobs.clear() }
            }
            active.forEach { it.cancel(retirementCause) }
            collectorJob.cancel(retirementCause)
        }
    }

    private val lock = Any()
    private var publicationOrdinal = 0L
    private var authoritativeState = DesktopGlobalSearchState()
    private var activeSession: SearchSession? = null
    private val sourceLoadPermits = Semaphore(5)
    private val publications = MutableStateFlow(authoritativeState)
    val states: StateFlow<DesktopGlobalSearchState> = publications.asStateFlow()
    val state: DesktopGlobalSearchState get() = states.value

    fun coordinatorFor(sourceId: Long): SourceBrowseQueryCoordinator? = synchronized(lock) {
        activeSession?.coordinators?.get(sourceId)
    }

    override fun close() {
        val (session, closed) = synchronized(lock) {
            val current = activeSession ?: return
            activeSession = null
            current to commitLocked(authoritativeState.copy(isSearching = false))
        }
        session.retire()
        publish(closed)
    }

    suspend fun search(
        sources: List<Source>,
        query: String,
        onState: (DesktopGlobalSearchState) -> Unit = {},
    ) = search(sources, query, GlobalSearchSourceFilter.All, onState)

    suspend fun search(
        sources: List<Source>,
        query: String,
        sourceFilter: GlobalSearchSourceFilter,
        onState: (DesktopGlobalSearchState) -> Unit = {},
    ) = search(sources, query, sourceFilter, emptyList(), onState)

    suspend fun search(
        sources: List<Source>,
        query: String,
        sourceFilter: GlobalSearchSourceFilter,
        installed: List<InstalledExtension>,
        onState: (DesktopGlobalSearchState) -> Unit = {},
    ) {
        val metadata = sources.map(::prepareLegacySearchSource)
        searchPrepared(metadata, query, sourceFilter, installed, onState)
    }

    internal suspend fun searchPrepared(
        metadata: List<SourceMetadataSnapshot>,
        query: String,
        sourceFilter: GlobalSearchSourceFilter = GlobalSearchSourceFilter.All,
        installed: List<InstalledExtension> = emptyList(),
        onState: (DesktopGlobalSearchState) -> Unit = {},
    ) {
        val owners = metadata.map { snapshot ->
            val source = snapshot.source
            val metadata = installed.firstOrNull { owner -> owner.sources.any { it === source } }?.metadataSnapshot
            source to SourceFailureIdentity(
                sourceId = snapshot.sourceId,
                extensionPackage = metadata?.pkgName,
                extensionName = metadata?.name,
                versionName = metadata?.versionName,
                versionCode = metadata?.versionCode,
            )
        }
        val prepared = metadata.mapIndexed { index, snapshot ->
            SourceMetadataSnapshot(
                snapshot.source,
                snapshot.sourceId,
                snapshot.name,
                snapshot.language,
                snapshot.error?.withSourceFailureIdentity(owners[index].second)
                    ?: if (snapshot.sourceId == null) AppError.Unknown() else null,
            )
        }
        val identity = SearchIdentity(query, sourceFilter, prepared.map { it.sourceId }, owners)
        val (started, session, previous) = synchronized(lock) {
            if (activeSession?.identity?.sameSearch(identity) == true && prepared.none { it.error != null } &&
                authoritativeState.sourceMetadata.none { it.error != null }
            ) {
                return
            }
            val previousSession = activeSession
            val reusableStates = if (previousSession?.identity?.query == query) {
                authoritativeState.queryStates.filter { (sourceId, state) ->
                    val owner = identity.sourceIds.indexOf(sourceId).takeIf { it >= 0 }?.let(owners::get)
                    owner != null && previousSession.identity.containsOwner(owner) &&
                        prepared.firstOrNull { it.sourceId == sourceId }?.error == null &&
                        authoritativeState.sourceMetadata.firstOrNull { it.sourceId == sourceId }?.error == null &&
                        !state.isLoading && previousSession.coordinators[sourceId] != null
                }
            } else {
                emptyMap()
            }
            val generation = authoritativeState.generation + 1
            val next = SearchSession(
                generation = generation,
                identity = identity,
                coordinators = prepared.mapNotNull { snapshot ->
                    val sourceId = snapshot.sourceId ?: return@mapNotNull null
                    sourceId to (
                        previousSession?.coordinators?.get(sourceId)
                            ?.takeIf { sourceId in reusableStates }
                            ?: SourceBrowseQueryCoordinator(
                                service,
                                owners.first { it.first === snapshot.source }.second,
                                sourceFailureLog,
                                snapshot,
                            )
                        )
                }.toMap(),
                onState = onState,
            )
            Triple(
                commitLocked(
                    DesktopGlobalSearchState(
                        generation,
                        isSearching = true,
                        queryStates = reusableStates,
                        sourceMetadata = prepared,
                        sourceIdentities = prepared.mapIndexedNotNull { index, snapshot ->
                            snapshot.sourceId?.let { it to owners[index].second }
                        }.toMap(),
                    ),
                ),
                next,
                previousSession,
            ).also {
                activeSession = next
            }
        }
        previous?.retire()
        var completedNormally = false
        try {
            publish(started, session)
            if (synchronized(lock) { activeSession === session }) {
                prepared.filter { it.sourceId == null }.forEach {
                    it.error?.recordSourceFailureDiagnostics(sourceFailureLog)
                }
            }
            coroutineScope {
                prepared.mapNotNull { snapshot ->
                    val source = snapshot.source
                    val sourceId = snapshot.sourceId ?: return@mapNotNull null
                    val child = session.coordinators.getValue(sourceId)
                    session.collect {
                        child.states.filterNotNull().collect { candidate ->
                            aggregate(started.generation, sourceId, child, candidate)?.let { publish(it, session) }
                        }
                    }
                    async(start = CoroutineStart.LAZY) {
                        if (sourceId in started.queryStates) return@async
                        val completed = sourceLoadPermits.withPermit {
                            loadSource(child, source, query) { candidate ->
                                aggregate(started.generation, sourceId, child, candidate)?.let { publish(it, session) }
                            }
                        }
                        aggregate(started.generation, sourceId, child, completed)?.let { publish(it, session) }
                    }.also(session::register)
                }.awaitAll()
            }
            completedNormally = true
        } catch (error: CancellationException) {
            if (!session.owns(error)) throw error
        } finally {
            if (!completedNormally) session.retire()
            val completed = synchronized(lock) {
                if (activeSession !== session) return@synchronized null
                if (!completedNormally) activeSession = null
                commitLocked(authoritativeState.copy(isSearching = false))
            }
            try {
                completed?.let { publish(it, session) }
            } finally {
                session.unbindCallback()
            }
        }
    }

    private fun aggregate(
        generation: Long,
        sourceId: Long,
        coordinator: SourceBrowseQueryCoordinator,
        state: SourceQueryState,
    ): DesktopGlobalSearchState? = synchronized(lock) {
        if (authoritativeState.generation != generation ||
            activeSession?.coordinators?.get(sourceId) !== coordinator
        ) {
            return@synchronized null
        }
        if (coordinator.state != state) return@synchronized null
        if (authoritativeState.queryStates[sourceId] == state) return@synchronized null
        commitLocked(
            authoritativeState.copy(
                queryStates = authoritativeState.queryStates + (sourceId to state),
                sourceMetadata = authoritativeState.sourceMetadata.map { snapshot ->
                    coordinator.metadata?.takeIf { it.source === snapshot.source }?.let {
                        SourceMetadataSnapshot(
                            it.source,
                            it.sourceId,
                            it.name,
                            it.language,
                            it.error?.withSourceFailureIdentity(
                                activeSession?.identity?.owners?.firstOrNull { owner ->
                                    owner.first === it.source
                                }?.second
                                    ?: SourceFailureIdentity(sourceId = sourceId),
                            ),
                        )
                    } ?: snapshot
                },
            ),
        )
    }

    private fun commitLocked(state: DesktopGlobalSearchState): DesktopGlobalSearchState =
        state.copy(publicationOrdinal = ++publicationOrdinal).also { authoritativeState = it }

    internal fun aggregateCandidate(
        generation: Long,
        sourceId: Long,
        coordinator: SourceBrowseQueryCoordinator,
        state: SourceQueryState,
    ) =
        aggregate(generation, sourceId, coordinator, state)

    internal fun publishCandidate(
        state: DesktopGlobalSearchState,
        onAccepted: ((DesktopGlobalSearchState) -> Unit)? = null,
    ) = publish(state, onAccepted = onAccepted)

    private fun publish(
        state: DesktopGlobalSearchState,
        session: SearchSession? = null,
        onAccepted: ((DesktopGlobalSearchState) -> Unit)? = null,
    ) {
        var current = publications.value
        while (state.publicationOrdinal > current.publicationOrdinal) {
            if (publications.compareAndSet(current, state)) {
                try {
                    (session?.callback ?: onAccepted)?.invoke(state)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
                return
            }
            current = publications.value
        }
    }

    private suspend fun loadSource(
        coordinator: SourceBrowseQueryCoordinator,
        source: Source,
        query: String,
        onStarted: (SourceQueryState) -> Unit,
    ): SourceQueryState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        coordinator.loadSearch(source, query, onStarted)
    }
}
