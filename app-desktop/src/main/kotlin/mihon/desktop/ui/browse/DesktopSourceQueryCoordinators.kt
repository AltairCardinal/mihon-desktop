package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
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
import mihon.domain.error.AppError
import tachiyomi.domain.source.service.GlobalSearchSourceFilter
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryReducer
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceRecoveryAction
import tachiyomi.i18n.MR
import java.util.Locale

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
) {
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
    private val pendingLoads = mutableMapOf<SourceLoadKey, CompletableDeferred<SourceQueryState>>()
    private val publisher = SourceQueryStatePublisher()

    val states: Flow<SourceQueryState?> = publisher.states
    val state: SourceQueryState?
        get() = synchronized(lock) { authoritativeState }
    internal val subscriberCount: Int get() = publisher.subscriberCount

    suspend fun load(
        source: CatalogueSource,
        page: Int,
        query: SourceQuery,
        onStarted: (SourceQueryState) -> Unit = {},
    ): SourceQueryState {
        val pending = CompletableDeferred<SourceQueryState>()
        var shared: CompletableDeferred<SourceQueryState>? = null
        var previous: SourceQueryState? = null
        lateinit var request: SourcePageRequest
        lateinit var key: SourceLoadKey
        val started = synchronized(lock) {
            if (page == 1) generation += 1
            request = SourcePageRequest(source.id, page, generation, query)
            key = request.loadKey()
            pendingLoads[key]?.let {
                if (!matchesCurrentRequestLocked(request)) return requireNotNull(authoritativeState)
                shared = it
                return@synchronized null
            }
            if (page > 1 && !canAppendLocked(request)) return requireNotNull(authoritativeState)
            previous = authoritativeState
            pendingLoads[key] = pending
            commitLocked(reducer.start(request, authoritativeState))
        }
        shared?.let { return it.await() }
        val owned = requireNotNull(started)
        return try {
            publisher.publish(owned)
            onStarted(requireNotNull(owned.state))
            currentCoroutineContext().ensureActive()
            completeLoad(source, request).also(pending::complete)
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
        source: CatalogueSource,
        request: SourcePageRequest,
    ): SourceQueryState {
        val result = service.loadPageResult(source, request)
        currentCoroutineContext().ensureActive()
        val completed = synchronized(lock) {
            val current = requireNotNull(authoritativeState)
            if (current.request != request) return@synchronized null
            commitLocked(reducer.reduce(current, result))
        }
        completed?.let(publisher::publish)
        return completed?.state ?: requireNotNull(state)
    }

    suspend fun retry(
        source: CatalogueSource,
        request: SourcePageRequest,
    ): SourceQueryState? = restart(source, request, SourceRecoveryAction.Retry)

    suspend fun retryAfterLogin(
        source: CatalogueSource,
        request: SourcePageRequest,
    ): SourceQueryState? = restart(source, request, SourceRecoveryAction.OpenLogin)

    private suspend fun restart(
        source: CatalogueSource,
        request: SourcePageRequest,
        expectedAction: SourceRecoveryAction,
    ): SourceQueryState? {
        val started = synchronized(lock) {
            val current = authoritativeState
            if (current?.request != request || current.recoveryAction() != expectedAction) {
                return@synchronized null
            }
            commitLocked(reducer.start(request, current))
        }
        if (started == null) return null
        publisher.publish(started)
        return completeLoad(source, request)
    }

    suspend fun retry(
        source: CatalogueSource,
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

    fun recoveryIntent(source: CatalogueSource): DesktopSourceRecoveryIntent =
        desktopSourceRecoveryIntent(source, state)

    private fun recoveryAction(): SourceRecoveryAction? = when (val current = state) {
        is SourceQueryState.Content -> current.pageError?.recoveryAction
        is SourceQueryState.Failure -> current.recoveryAction
        else -> null
    }
}

private fun desktopSourceRecoveryIntent(
    source: CatalogueSource,
    state: SourceQueryState?,
): DesktopSourceRecoveryIntent = when (state.recoveryAction()) {
    SourceRecoveryAction.Retry -> state?.request?.let(DesktopSourceRecoveryIntent::Retry)
        ?: DesktopSourceRecoveryIntent.None
    SourceRecoveryAction.OpenLogin -> (source as? HttpSource)?.baseUrl
        ?.takeIf { it.startsWith("http") }
        ?.let { url -> state?.request?.let { request -> DesktopSourceRecoveryIntent.OpenLogin(url, request) } }
        ?: DesktopSourceRecoveryIntent.None
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
)

class DesktopGlobalSearchCoordinator(
    private val service: SourceMangaSearchService,
) : AutoCloseable {
    private data class SearchIdentity(
        val query: String,
        val sourceFilter: GlobalSearchSourceFilter,
        val sourceIds: List<Long>,
    )

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
                if (retired) retirementCause else {
                    queryJobs += job
                    null
                }
            }
            cause?.let(job::cancel)
        }

        fun collect(block: suspend CoroutineScope.() -> Unit) {
            collectorScope.launch(start = CoroutineStart.UNDISPATCHED, block = block)
        }

        fun unbindCallback() { callback = null }

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
        sources: List<CatalogueSource>,
        query: String,
        onState: (DesktopGlobalSearchState) -> Unit = {},
    ) = search(sources, query, GlobalSearchSourceFilter.All, onState)

    suspend fun search(
        sources: List<CatalogueSource>,
        query: String,
        sourceFilter: GlobalSearchSourceFilter,
        onState: (DesktopGlobalSearchState) -> Unit = {},
    ) {
        val identity = SearchIdentity(query, sourceFilter, sources.map(CatalogueSource::id))
        val (started, session, previous) = synchronized(lock) {
            if (activeSession?.identity == identity) return
            val previousSession = activeSession
            val reusableStates = if (previousSession?.identity?.query == query) {
                authoritativeState.queryStates.filter { (sourceId, state) ->
                    sourceId in identity.sourceIds && !state.isLoading && previousSession.coordinators[sourceId] != null
                }
            } else {
                emptyMap()
            }
            val generation = authoritativeState.generation + 1
            val next = SearchSession(
                generation = generation,
                identity = identity,
                coordinators = sources.associate { source ->
                    source.id to (
                        previousSession?.coordinators?.get(source.id)
                            ?.takeIf { source.id in reusableStates }
                            ?: SourceBrowseQueryCoordinator(service)
                        )
                },
                onState = onState,
            )
            Triple(
                commitLocked(DesktopGlobalSearchState(generation, isSearching = true, queryStates = reusableStates)),
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
            coroutineScope {
                sources.map { source ->
                    val child = session.coordinators.getValue(source.id)
                    session.collect {
                        child.states.filterNotNull().collect { candidate ->
                            aggregate(started.generation, source.id, child, candidate)?.let { publish(it, session) }
                        }
                    }
                    async(start = CoroutineStart.LAZY) {
                        if (source.id in started.queryStates) return@async
                        val completed = sourceLoadPermits.withPermit {
                            loadSource(child, source, query) { candidate ->
                                aggregate(started.generation, source.id, child, candidate)?.let { publish(it, session) }
                            }
                        }
                        aggregate(started.generation, source.id, child, completed)?.let { publish(it, session) }
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
        if (authoritativeState.generation != generation || activeSession?.coordinators?.get(sourceId) !== coordinator) return@synchronized null
        if (coordinator.state != state) return@synchronized null
        if (authoritativeState.queryStates[sourceId] == state) return@synchronized null
        commitLocked(authoritativeState.copy(queryStates = authoritativeState.queryStates + (sourceId to state)))
    }

    private fun commitLocked(state: DesktopGlobalSearchState): DesktopGlobalSearchState =
        state.copy(publicationOrdinal = ++publicationOrdinal).also { authoritativeState = it }

    internal fun aggregateCandidate(generation: Long, sourceId: Long, coordinator: SourceBrowseQueryCoordinator, state: SourceQueryState) =
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
        source: CatalogueSource,
        query: String,
        onStarted: (SourceQueryState) -> Unit,
    ): SourceQueryState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        coordinator.load(source, 1, SourceQuery.Search(query, source.getFilterList()), onStarted)
    }

}
