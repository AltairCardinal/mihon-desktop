package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.error.AppError
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

internal fun desktopSourceErrorMessage(error: AppError, locale: Locale = Locale.getDefault()): String = when (error) {
    is AppError.Network -> MR.strings.exception_offline.localized(locale)
    is AppError.Authentication -> MR.strings.login.localized(locale)
    else -> MR.strings.unknown_error.localized(locale)
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

    fun publish(candidate: StampedSourceQueryState) {
        publications.update { current -> if (candidate.ordinal > current.ordinal) candidate else current }
    }
}

class SourceBrowseQueryCoordinator(
    private val service: SourceMangaSearchService,
) {
    private val reducer = SourceQueryReducer()
    private val lock = Any()
    private var generation = 0L
    private var publicationOrdinal = 0L
    private var authoritativeState: SourceQueryState? = null
    private val publisher = SourceQueryStatePublisher()

    val states: Flow<SourceQueryState?> = publisher.states
    val state: SourceQueryState?
        get() = synchronized(lock) { authoritativeState }

    suspend fun load(
        source: CatalogueSource,
        page: Int,
        query: SourceQuery,
    ): SourceQueryState {
        val started = synchronized(lock) {
            if (page == 1) generation += 1
            val request = SourcePageRequest(source.id, page, generation, query)
            commitLocked(reducer.start(request, authoritativeState))
        }
        publisher.publish(started)
        return completeLoad(source, requireNotNull(started.state).request)
    }

    private suspend fun completeLoad(
        source: CatalogueSource,
        request: SourcePageRequest,
    ): SourceQueryState {
        val result = service.loadPageResult(source, request)
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
) {
    private class SearchSession(
        val generation: Long,
        val coordinators: Map<Long, SourceBrowseQueryCoordinator>,
    ) {
        private class Retirement(val token: Any) : CancellationException()
        private val lock = Any()
        private val jobs = mutableListOf<Job>()
        private val retirementToken = Any()
        private val retirementCause = Retirement(retirementToken)
        private var retired = false

        fun register(job: Job) {
            val cause = synchronized(lock) {
                if (retired) retirementCause else {
                    jobs += job
                    null
                }
            }
            cause?.let(job::cancel)
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
            val active = synchronized(lock) {
                retired = true
                jobs.toList().also { jobs.clear() }
            }
            active.forEach { it.cancel(retirementCause) }
        }
    }

    private val lock = Any()
    private var publicationOrdinal = 0L
    private var authoritativeState = DesktopGlobalSearchState()
    private var activeSession: SearchSession? = null
    private val publications = MutableStateFlow(authoritativeState)
    val states: StateFlow<DesktopGlobalSearchState> = publications.asStateFlow()
    val state: DesktopGlobalSearchState get() = states.value

    fun coordinatorFor(sourceId: Long): SourceBrowseQueryCoordinator? = synchronized(lock) {
        activeSession?.coordinators?.get(sourceId)
    }

    suspend fun search(
        sources: List<CatalogueSource>,
        query: String,
        onState: (DesktopGlobalSearchState) -> Unit = {},
    ) {
        val (started, session, previous) = synchronized(lock) {
            val generation = authoritativeState.generation + 1
            val next = SearchSession(generation, sources.associate { it.id to SourceBrowseQueryCoordinator(service) })
            Triple(commitLocked(DesktopGlobalSearchState(generation, isSearching = true)), next, activeSession).also {
                activeSession = next
            }
        }
        previous?.retire()
        var completedNormally = false
        try {
            publish(started, onState)
            coroutineScope {
                sources.map { source ->
                    async(start = CoroutineStart.LAZY) {
                        val child = session.coordinators.getValue(source.id)
                        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                            child.states.filterNotNull().collect { candidate ->
                                aggregate(started.generation, source.id, child, candidate)?.let { publish(it, onState) }
                            }
                        }
                        val completed = child.load(source, 1, SourceQuery.Search(query, source.getFilterList()))
                        aggregate(started.generation, source.id, child, completed)?.let { publish(it, onState) }
                        collector.cancelAndJoin()
                    }.also(session::register)
                }.awaitAll()
            }
            completedNormally = true
        } catch (error: CancellationException) {
            if (!session.owns(error)) throw error
        } finally {
            session.retire()
            val completed = synchronized(lock) {
                if (activeSession !== session) return@synchronized null
                if (!completedNormally) activeSession = null
                commitLocked(authoritativeState.copy(isSearching = false))
            }
            completed?.let { publish(it, onState) }
        }
    }

    private fun aggregate(
        generation: Long,
        sourceId: Long,
        coordinator: SourceBrowseQueryCoordinator,
        state: SourceQueryState,
    ): DesktopGlobalSearchState? = synchronized(lock) {
        if (authoritativeState.generation != generation || activeSession?.coordinators?.get(sourceId) !== coordinator) return@synchronized null
        commitLocked(authoritativeState.copy(queryStates = authoritativeState.queryStates + (sourceId to state)))
    }

    private fun commitLocked(state: DesktopGlobalSearchState): DesktopGlobalSearchState =
        state.copy(publicationOrdinal = ++publicationOrdinal).also { authoritativeState = it }

    internal fun aggregateCandidate(generation: Long, sourceId: Long, coordinator: SourceBrowseQueryCoordinator, state: SourceQueryState) =
        aggregate(generation, sourceId, coordinator, state)

    internal fun publishCandidate(state: DesktopGlobalSearchState) = publish(state) {}

    private fun publish(state: DesktopGlobalSearchState, onState: (DesktopGlobalSearchState) -> Unit) {
        publications.update { current -> if (state.publicationOrdinal > current.publicationOrdinal) state else current }
        try {
            onState(states.value)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }

}
