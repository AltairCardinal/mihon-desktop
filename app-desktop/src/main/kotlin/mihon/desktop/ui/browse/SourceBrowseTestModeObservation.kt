package mihon.desktop.ui.browse

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor

@Serializable
enum class SourceBrowseTestPhase { IDLE, LOADING, EMPTY, CONTENT, FAILURE }

@Serializable
enum class SourceBrowseTestQueryKind { POPULAR, LATEST, SEARCH }

@Serializable
enum class SourceBrowseTestRecovery { OPEN_LOGIN, RETRY, NONE }

@Serializable
enum class SourceBrowseTestLoginFeedback {
    INVALID_HEADER, BROWSER_UNAVAILABLE, TIMED_OUT, INVALID_COOKIES, COMMIT_FAILED
}

@Serializable
data class SourceBrowseTestRequest(
    val sourceId: Long,
    val page: Int,
    val generation: Long,
    val queryKind: SourceBrowseTestQueryKind,
    val queryText: String? = null,
)

@Serializable
data class SourceBrowseTestLogin(
    val host: String,
    val feedback: SourceBrowseTestLoginFeedback? = null,
    val terminal: Boolean,
    val attemptToken: String,
)

@Serializable
data class SourceBrowseTestSnapshot(
    val sourceId: Long,
    val phase: SourceBrowseTestPhase = SourceBrowseTestPhase.IDLE,
    val request: SourceBrowseTestRequest? = null,
    val itemCount: Int = 0,
    val loading: Boolean = false,
    val hasNextPage: Boolean? = null,
    val error: StoredAppError? = null,
    val recovery: SourceBrowseTestRecovery? = null,
    val login: SourceBrowseTestLogin? = null,
)

@Serializable
enum class SourceBrowseTestFailureCode {
    MISSING_TOKEN,
    NO_ACTIVE_LOGIN,
    ATTEMPT_MISMATCH,
    TERMINAL,
    OPERATION_REJECTED,
    PORT_CLOSED,
}

@Serializable
data class SourceBrowseTestActionResult(
    val success: Boolean,
    val snapshot: SourceBrowseTestSnapshot,
    val failureCode: SourceBrowseTestFailureCode? = null,
)

class SourceBrowseTestModeObservationPort(
    private val sourceId: Long,
    private val coordinator: SourceBrowseQueryCoordinator,
    scope: CoroutineScope,
    private val currentLogin: () -> DesktopSourceLoginUiState?,
    private val setLogin: (DesktopSourceLoginUiState?) -> Unit,
    private val loginActions: DesktopSourceLoginUiActions,
) {
    private val lock = Any()
    private val dispatcher = requireNotNull(scope.coroutineContext[ContinuationInterceptor]) as CoroutineDispatcher
    private var tokenAttempt: DesktopSourceLoginAttempt? = null
    private var token: String? = null
    private var closed = false

    fun snapshot(): SourceBrowseTestSnapshot {
        val state = coordinator.state
        val loginState = if (synchronized(lock) { closed }) null else currentLogin()
        val pageError = when (state) {
            is SourceQueryState.Failure -> state.error to state.recoveryAction
            is SourceQueryState.Content -> state.pageError?.let { it.error to it.recoveryAction }
            else -> null
        }
        return SourceBrowseTestSnapshot(
            sourceId = sourceId,
            phase = when (state) {
                null -> SourceBrowseTestPhase.IDLE
                is SourceQueryState.Loading -> SourceBrowseTestPhase.LOADING
                is SourceQueryState.Empty -> SourceBrowseTestPhase.EMPTY
                is SourceQueryState.Content -> SourceBrowseTestPhase.CONTENT
                is SourceQueryState.Failure -> SourceBrowseTestPhase.FAILURE
            },
            request = state?.request?.let {
                SourceBrowseTestRequest(
                    it.sourceId,
                    it.page,
                    it.generation,
                    when (it.query) {
                        SourceQuery.Popular -> SourceBrowseTestQueryKind.POPULAR
                        SourceQuery.Latest -> SourceBrowseTestQueryKind.LATEST
                        is SourceQuery.Search -> SourceBrowseTestQueryKind.SEARCH
                    },
                    (it.query as? SourceQuery.Search)?.query,
                )
            },
            itemCount = state?.items?.size ?: 0,
            loading = state?.isLoading ?: false,
            hasNextPage = (state as? SourceQueryState.Content)?.hasNextPage,
            error = pageError?.first?.toStoredAppError(),
            recovery = pageError?.second?.let {
                when (it) {
                    tachiyomi.domain.source.service.SourceRecoveryAction.OpenLogin ->
                        SourceBrowseTestRecovery.OPEN_LOGIN
                    tachiyomi.domain.source.service.SourceRecoveryAction.Retry -> SourceBrowseTestRecovery.RETRY
                    tachiyomi.domain.source.service.SourceRecoveryAction.None -> SourceBrowseTestRecovery.NONE
                }
            },
            login = synchronized(lock) {
                if (closed || loginState == null) {
                    clearTokenLocked()
                    null
                } else {
                    SourceBrowseTestLogin(
                        loginState.host,
                        loginState.feedback?.toTestFeedback(),
                        loginState.terminal,
                        tokenForLocked(loginState.attempt),
                    )
                }
            },
        )
    }

    suspend fun cancel(attemptToken: String?): SourceBrowseTestActionResult {
        return withContext(dispatcher) {
            if (attemptToken.isNullOrBlank()) return@withContext failure(SourceBrowseTestFailureCode.MISSING_TOKEN)
            val login = currentLogin()
            val failureCode = synchronized(lock) {
                when {
                    closed -> SourceBrowseTestFailureCode.PORT_CLOSED
                    login == null -> SourceBrowseTestFailureCode.NO_ACTIVE_LOGIN
                    tokenAttempt !== login.attempt || token != attemptToken ->
                        SourceBrowseTestFailureCode.ATTEMPT_MISMATCH
                    login.terminal -> SourceBrowseTestFailureCode.TERMINAL
                    else -> null
                }
            }
            if (failureCode != null) return@withContext failure(failureCode)
            requireNotNull(login)
            if (
                loginActions.cancel(login) != null
            ) return@withContext failure(SourceBrowseTestFailureCode.OPERATION_REJECTED)
            setLogin(null)
            clearToken()
            SourceBrowseTestActionResult(true, snapshot())
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        tokenAttempt = null
        token = null
    }

    private fun tokenForLocked(attempt: DesktopSourceLoginAttempt): String {
        if (tokenAttempt !== attempt) {
            tokenAttempt = attempt
            token = UUID.randomUUID().toString()
        }
        return requireNotNull(token)
    }

    private fun clearToken() = synchronized(lock) {
        clearTokenLocked()
    }

    private fun clearTokenLocked() {
        tokenAttempt = null
        token = null
    }

    private fun failure(code: SourceBrowseTestFailureCode) = SourceBrowseTestActionResult(false, snapshot(), code)
}

private fun DesktopSourceLoginFeedback.toTestFeedback() = when (this) {
    DesktopSourceLoginFeedback.InvalidHeader -> SourceBrowseTestLoginFeedback.INVALID_HEADER
    DesktopSourceLoginFeedback.BrowserUnavailable -> SourceBrowseTestLoginFeedback.BROWSER_UNAVAILABLE
    DesktopSourceLoginFeedback.TimedOut -> SourceBrowseTestLoginFeedback.TIMED_OUT
    DesktopSourceLoginFeedback.InvalidCookies -> SourceBrowseTestLoginFeedback.INVALID_COOKIES
    DesktopSourceLoginFeedback.CommitFailed -> SourceBrowseTestLoginFeedback.COMMIT_FAILED
}

object SourceBrowseTestModeBridge {
    private val value = AtomicReference<SourceBrowseTestModeObservationPort?>()
    val port: SourceBrowseTestModeObservationPort? get() = value.get()
    fun install(port: SourceBrowseTestModeObservationPort) { value.set(port) }
    fun clear(expected: SourceBrowseTestModeObservationPort): Boolean = value.compareAndSet(expected, null)
}
