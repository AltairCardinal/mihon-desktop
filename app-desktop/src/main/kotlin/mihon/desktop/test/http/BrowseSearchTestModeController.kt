package mihon.desktop.test.http

import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.browse.DesktopGlobalSearchCoordinator
import mihon.desktop.ui.browse.DesktopSourceLoginAttempt
import mihon.desktop.ui.browse.DesktopSourceLoginController
import mihon.desktop.ui.browse.DesktopSourceLoginUiActions
import mihon.desktop.ui.browse.DesktopSourceLoginUiState
import mihon.desktop.ui.browse.DesktopSourceRecoveryIntent
import mihon.desktop.ui.browse.SourceBrowseRecoveryController
import mihon.desktop.ui.browse.SourceBrowseTestModeBridge
import mihon.desktop.ui.browse.SourceBrowseTestActionResult
import mihon.desktop.ui.browse.SourceBrowseTestModeObservationPort
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import tachiyomi.domain.source.service.GlobalSearchSourceFilter
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class BrowseSearchTestRow(
    val sourceId: Long,
    val sourceName: String,
    val index: Int,
    val title: String,
    val url: String,
)

@Serializable
data class BrowseSearchSourceSnapshot(
    val sourceId: Long,
    val sourceName: String,
    val loading: Boolean,
    val itemCount: Int,
    val error: StoredAppError?,
    val recovery: String?,
)

@Serializable
data class BrowseSearchTestSnapshot(
    val generation: Long,
    val query: String,
    val searching: Boolean,
    val rows: List<BrowseSearchTestRow>,
    val sources: List<BrowseSearchSourceSnapshot>,
    val selectedMangaId: Long? = null,
)

@Serializable
enum class BrowseSearchTestFailureCode {
    MISSING_PARAMETER,
    BLANK_QUERY,
    STALE_GENERATION,
    SOURCE_NOT_FOUND,
    ROW_NOT_FOUND,
    RECOVERY_UNAVAILABLE,
    OPERATION_REJECTED,
    OWNER_CLOSED,
    UNSUPPORTED_ACTION,
}

@Serializable
data class BrowseSearchTestActionResult(
    val success: Boolean,
    val snapshot: BrowseSearchTestSnapshot,
    val failureCode: BrowseSearchTestFailureCode? = null,
)

class BrowseSearchTestModeController(
    private val coordinator: DesktopGlobalSearchCoordinator,
    private val sourcesProvider: () -> List<CatalogueSource>,
    private val saveSourceMangaForDetails: SaveSourceMangaForDetails,
    private val loginSessionFactory: DesktopSourceLoginSessionFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val closed = AtomicBoolean(false)
    private val loginLock = Any()
    private var searchJob: Job? = null
    private var loginJob: Job? = null
    private var loginGeneration: Long? = null
    private var query = ""
    private var selectedMangaId: Long? = null
    private var sourcesById = emptyMap<Long, CatalogueSource>()
    private var sourcePort: SourceBrowseTestModeObservationPort? = null

    fun snapshot(): BrowseSearchTestSnapshot {
        val state = coordinator.state
        val rows = state.queryStates.entries.flatMap { (sourceId, queryState) ->
            val source = sourcesById[sourceId]
            queryState.items.mapIndexed { index, item ->
                BrowseSearchTestRow(
                    sourceId = sourceId,
                    sourceName = source?.name.orEmpty(),
                    index = index,
                    title = runCatching { item.title }.getOrDefault(""),
                    url = runCatching { item.url }.getOrDefault(""),
                )
            }
        }
        return BrowseSearchTestSnapshot(
            generation = state.generation,
            query = query,
            searching = state.isSearching,
            rows = rows,
            sources = state.queryStates.map { (sourceId, queryState) ->
                val error = when (queryState) {
                    is SourceQueryState.Failure -> queryState.error
                    is SourceQueryState.Content -> queryState.pageError?.error
                    else -> null
                }
                val recovery = when (queryState) {
                    is SourceQueryState.Failure -> queryState.recoveryAction
                    is SourceQueryState.Content -> queryState.pageError?.recoveryAction
                    else -> null
                }
                BrowseSearchSourceSnapshot(
                    sourceId = sourceId,
                    sourceName = sourcesById[sourceId]?.name.orEmpty(),
                    loading = queryState.isLoading,
                    itemCount = queryState.items.size,
                    error = error?.toStoredAppError(),
                    recovery = recovery?.name,
                )
            },
            selectedMangaId = selectedMangaId,
        )
    }

    suspend fun execute(action: String, params: Map<String, String>): BrowseSearchTestActionResult {
        if (closed.get()) return failure(BrowseSearchTestFailureCode.OWNER_CLOSED)
        val failure = when (action) {
            "browse_search" -> search(params)
            "browse_select" -> select(params)
            "source_login_start" -> startLogin(params)
            else -> BrowseSearchTestFailureCode.UNSUPPORTED_ACTION
        }
        return failure?.let(::failure) ?: BrowseSearchTestActionResult(true, snapshot())
    }

    suspend fun executeSourceLogin(
        action: String,
        params: Map<String, String>,
    ): SourceBrowseTestActionResult? {
        val port = synchronized(loginLock) { sourcePort } ?: return null
        return when (action) {
            "source_login_complete" ->
                port.submit(
                    attemptToken = params["attemptToken"],
                    cookieHeader = params["cookieHeader"],
                )
            "source_login_cancel" -> port.cancel(params["attemptToken"])
            else -> null
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        searchJob?.cancel()
        retireLogin()
        coordinator.close()
        scope.cancel()
        BrowseSearchTestModeBridge.clear(this)
    }

    suspend fun closeAndJoin() {
        val jobs = listOfNotNull(searchJob, loginJob)
        close()
        jobs.joinAll()
    }

    private fun search(params: Map<String, String>): BrowseSearchTestFailureCode? {
        val requested = params["query"] ?: return BrowseSearchTestFailureCode.MISSING_PARAMETER
        if (requested.isBlank()) return BrowseSearchTestFailureCode.BLANK_QUERY
        val sources = sourcesProvider()
        retireLogin()
        query = requested
        selectedMangaId = null
        sourcesById = sources.associateBy(CatalogueSource::id)
        searchJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.search(sources, requested, GlobalSearchSourceFilter.All)
        }
        return null
    }

    private suspend fun select(params: Map<String, String>): BrowseSearchTestFailureCode? {
        val generation = params["generation"]?.toLongOrNull()
            ?: return BrowseSearchTestFailureCode.MISSING_PARAMETER
        val state = coordinator.state
        if (generation != state.generation) return BrowseSearchTestFailureCode.STALE_GENERATION
        val sourceId = params["sourceId"]?.toLongOrNull()
            ?: return BrowseSearchTestFailureCode.MISSING_PARAMETER
        val source = sourcesById[sourceId] ?: return BrowseSearchTestFailureCode.SOURCE_NOT_FOUND
        val index = params["index"]?.toIntOrNull()
            ?: return BrowseSearchTestFailureCode.MISSING_PARAMETER
        val item = state.queryStates[sourceId]?.items?.getOrNull(index)
            ?: return BrowseSearchTestFailureCode.ROW_NOT_FOUND
        val manga = try {
            saveSourceMangaForDetails.awaitListed(item, source.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return BrowseSearchTestFailureCode.OPERATION_REJECTED
        }
        if (coordinator.state.generation != generation) return BrowseSearchTestFailureCode.STALE_GENERATION
        selectedMangaId = manga.id
        TestNavigationController.navigateToMangaDetail(manga.id)
        return null
    }

    private suspend fun startLogin(params: Map<String, String>): BrowseSearchTestFailureCode? {
        val generation = params["generation"]?.toLongOrNull()
            ?: return BrowseSearchTestFailureCode.MISSING_PARAMETER
        if (generation != coordinator.state.generation) return BrowseSearchTestFailureCode.STALE_GENERATION
        val sourceId = params["sourceId"]?.toLongOrNull()
            ?: return BrowseSearchTestFailureCode.MISSING_PARAMETER
        val source = sourcesById[sourceId] ?: return BrowseSearchTestFailureCode.SOURCE_NOT_FOUND
        val child = coordinator.coordinatorFor(sourceId) ?: return BrowseSearchTestFailureCode.RECOVERY_UNAVAILABLE
        val intent = child.recoveryIntent(source) as? DesktopSourceRecoveryIntent.OpenLogin
            ?: return BrowseSearchTestFailureCode.RECOVERY_UNAVAILABLE
        val generationClaimed = synchronized(loginLock) {
            when {
                closed.get() || coordinator.state.generation != generation -> false
                loginGeneration != null -> false
                else -> true.also { loginGeneration = generation }
            }
        }
        if (!generationClaimed) {
            return if (coordinator.state.generation != generation) {
                BrowseSearchTestFailureCode.STALE_GENERATION
            } else {
                BrowseSearchTestFailureCode.OPERATION_REJECTED
            }
        }

        val login = AtomicReference<DesktopSourceLoginUiState?>()
        val controller = DesktopSourceLoginController(loginSessionFactory, child)
        val recovery = SourceBrowseRecoveryController(child, controller)
        val actions = DesktopSourceLoginUiActions(recovery::submitCookies, recovery::cancel)
        val started = CompletableDeferred<Unit>()
        val acceptedAttempt = AtomicReference<DesktopSourceLoginAttempt?>()
        val launched = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = recovery.recover(source, intent) { attempt ->
                val state = actions.open(attempt, intent.url)
                login.set(state)
                val next = SourceBrowseTestModeObservationPort(
                    sourceId,
                    child,
                    scope,
                    login::get,
                    login::set,
                    actions,
                    runIfGenerationCurrent = { operation ->
                        synchronized(loginLock) {
                            if (isLoginGenerationCurrentLocked(generation)) {
                                operation()
                                true
                            } else {
                                false
                            }
                        }
                    },
                )
                if (publishSourcePort(generation, next)) {
                    acceptedAttempt.set(attempt)
                } else {
                    next.close()
                    actions.cancel(state)
                    login.set(null)
                }
                started.complete(Unit)
            }
            acceptedAttempt.get()?.let { attempt ->
                login.get()?.let { current -> login.set(actions.complete(current, attempt, result)) }
            }
            if (!started.isCompleted) started.complete(Unit)
        }
        val cancelLaunched = synchronized(loginLock) {
            if (isLoginGenerationCurrentLocked(generation)) {
                loginJob = launched
                false
            } else {
                true
            }
        }
        if (cancelLaunched) launched.cancel()
        withTimeoutOrNull(LOGIN_START_TIMEOUT_MILLIS) { started.await() }
            ?: return BrowseSearchTestFailureCode.OPERATION_REJECTED
        if (!isLoginGenerationCurrent(generation)) return BrowseSearchTestFailureCode.STALE_GENERATION
        return if (acceptedAttempt.get() == null) BrowseSearchTestFailureCode.OPERATION_REJECTED else null
    }

    private fun publishSourcePort(
        generation: Long,
        next: SourceBrowseTestModeObservationPort,
    ): Boolean = synchronized(loginLock) {
        if (!isLoginGenerationCurrentLocked(generation)) return@synchronized false
        sourcePort?.let { current ->
            current.close()
            SourceBrowseTestModeBridge.clear(current)
        }
        sourcePort = next
        SourceBrowseTestModeBridge.install(next)
        true
    }

    private fun retireLogin() {
        val job = synchronized(loginLock) {
            loginGeneration = null
            sourcePort?.let { port ->
                port.close()
                SourceBrowseTestModeBridge.clear(port)
            }
            loginJob
        }
        job?.cancel()
    }

    private fun isLoginGenerationCurrent(generation: Long): Boolean =
        synchronized(loginLock) { isLoginGenerationCurrentLocked(generation) }

    private fun isLoginGenerationCurrentLocked(generation: Long): Boolean =
        !closed.get() &&
            loginGeneration == generation &&
            coordinator.state.generation == generation

    private fun failure(code: BrowseSearchTestFailureCode) =
        BrowseSearchTestActionResult(false, snapshot(), code)

    private companion object {
        const val LOGIN_START_TIMEOUT_MILLIS = 2_000L
    }
}

object BrowseSearchTestModeBridge {
    private val value = AtomicReference<BrowseSearchTestModeController?>()
    val controller: BrowseSearchTestModeController? get() = value.get()
    fun install(controller: BrowseSearchTestModeController) { value.set(controller) }
    fun clear(expected: BrowseSearchTestModeController): Boolean = value.compareAndSet(expected, null)
}
