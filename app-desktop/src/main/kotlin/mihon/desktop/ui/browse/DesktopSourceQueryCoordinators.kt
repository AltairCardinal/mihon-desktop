package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    data class OpenLogin(val url: String) : DesktopSourceRecoveryIntent
    data object None : DesktopSourceRecoveryIntent

    companion object {
        @Deprecated("Use OpenLogin; opening is delegated to the platform login flow")
        fun OpenExternalUrl(url: String): DesktopSourceRecoveryIntent = OpenLogin(url)
    }
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

class SourceBrowseQueryCoordinator(
    private val service: SourceMangaSearchService,
) {
    private val reducer = SourceQueryReducer()
    private val lock = Any()
    private var generation = 0L

    @Volatile
    var state: SourceQueryState? = null
        private set

    suspend fun load(
        source: CatalogueSource,
        page: Int,
        query: SourceQuery,
        onState: (SourceQueryState) -> Unit = {},
    ): SourceQueryState {
        val request = synchronized(lock) {
            if (page == 1) generation += 1
            SourcePageRequest(source.id, page, generation, query).also { request ->
                reducer.start(request, state).also {
                    state = it
                    onState(it)
                }
            }
        }
        return completeLoad(source, request, onState)
    }

    private suspend fun completeLoad(
        source: CatalogueSource,
        request: SourcePageRequest,
        onState: (SourceQueryState) -> Unit,
    ): SourceQueryState {
        val result = service.loadPageResult(source, request)
        return synchronized(lock) {
            val current = requireNotNull(state)
            if (current.request != request) return@synchronized current
            reducer.reduce(current, result).also {
                state = it
                onState(it)
            }
        }
    }

    suspend fun retry(
        source: CatalogueSource,
        request: SourcePageRequest,
        onState: (SourceQueryState) -> Unit = {},
    ): SourceQueryState? {
        val started = synchronized(lock) {
            val current = state
            if (current?.request != request || current.recoveryAction() != SourceRecoveryAction.Retry) {
                return@synchronized null
            }
            reducer.start(request, current).also {
                state = it
                onState(it)
            }
        }
        if (started == null) return null
        return completeLoad(source, request, onState)
    }

    suspend fun retry(
        source: CatalogueSource,
        onState: (SourceQueryState) -> Unit = {},
    ): SourceQueryState {
        val request = synchronized(lock) {
            requireNotNull(state?.request) { "No source request to retry" }
        }
        return requireNotNull(retry(source, request, onState)) { "Current source error is not retryable" }
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
        ?.let(DesktopSourceRecoveryIntent::OpenLogin)
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
