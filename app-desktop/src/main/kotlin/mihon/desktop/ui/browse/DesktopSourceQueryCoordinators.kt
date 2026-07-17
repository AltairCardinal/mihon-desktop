package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
)

class DesktopGlobalSearchCoordinator(
    private val service: SourceMangaSearchService,
) {
    private val reducer = SourceQueryReducer()
    private val lock = Any()

    @Volatile
    var state = DesktopGlobalSearchState()
        private set

    suspend fun search(
        sources: List<CatalogueSource>,
        query: String,
        onState: (DesktopGlobalSearchState) -> Unit = {},
    ) {
        val started = synchronized(lock) {
            val generation = state.generation + 1
            val queryStates = sources.associate { source ->
                val request = SourcePageRequest(
                    sourceId = source.id,
                    page = 1,
                    generation = generation,
                    query = SourceQuery.Search(query, source.getFilterList()),
                )
                source.id to reducer.start(request)
            }
            DesktopGlobalSearchState(generation, isSearching = true, queryStates = queryStates)
                .also { state = it }
        }
        onState(started)

        coroutineScope {
            sources.map { source ->
                async {
                    val request = started.queryStates.getValue(source.id).request
                    val result = service.loadPageResult(source, request)
                    val updated = synchronized(lock) {
                        if (state.generation != started.generation) return@synchronized null
                        val current = state.queryStates.getValue(source.id)
                        state.copy(
                            queryStates = state.queryStates + (source.id to reducer.reduce(current, result)),
                        ).also { state = it }
                    }
                    updated?.let(onState)
                }
            }.awaitAll()
        }

        val completed = synchronized(lock) {
            if (state.generation != started.generation) return@synchronized null
            state.copy(isSearching = false).also { state = it }
        }
        completed?.let(onState)
    }

}
