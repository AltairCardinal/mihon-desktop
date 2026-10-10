package mihon.data.sync.http

import kotlinx.serialization.SerializationException
import logcat.LogPriority
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.security.SyncSecureStoreException
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncInitializationStage
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.sql.SQLException
import javax.net.ssl.SSLException

internal enum class SyncFailurePhase {
    HTTP_CALL,
    HTTP_RESPONSE,
    HTTP_BODY,
    DISCOVERY,
    PENDING_ACCOUNT,
    PENDING_STORAGE,
    RESUME_LOAD,
    RESUME_ACCOUNT,
    RESUME_VERIFY_REPOSITORY,
    RESUME_INITIALIZE,
    RESUME_INITIALIZE_RESULT,
    RESUME_READ_SNAPSHOT,
    RESUME_BIND,
    RESUME_SAVE,
}

internal enum class SyncFailureKind {
    DNS,
    CONNECT,
    TLS,
    TIMEOUT,
    IO,
    HTTP,
    PARSE,
    VALIDATION,
    LOCAL,
    AUTH,
    DISCOVERY,
    UNKNOWN,
}

/** Fixed vocabulary only: never log exception messages, stack traces, URLs or response data. */
internal object SyncFailureDiagnostics {
    fun record(
        phase: SyncFailurePhase,
        error: Exception? = null,
        status: Int? = (error as? SyncHttpException)?.code,
        kind: SyncFailureKind = when (error) {
            is UnknownHostException -> SyncFailureKind.DNS
            is ConnectException -> SyncFailureKind.CONNECT
            is SSLException -> SyncFailureKind.TLS
            is InterruptedIOException -> SyncFailureKind.TIMEOUT
            is IOException -> SyncFailureKind.IO
            is SyncHttpException -> SyncFailureKind.HTTP
            is SerializationException -> SyncFailureKind.PARSE
            is SyncSecureStoreException, is SQLException -> SyncFailureKind.LOCAL
            is GitHubAuthException -> SyncFailureKind.AUTH
            is IllegalArgumentException -> SyncFailureKind.VALIDATION
            else -> SyncFailureKind.UNKNOWN
        },
        newSpace: Boolean? = null,
        stage: SyncInitializationStage? = null,
        result: SyncInitializationResult? = null,
    ) {
        val safeStatus = status?.takeIf { it in 100..599 }
        val resultKind = when (result) {
            is SyncInitializationResult.Initialized -> "INITIALIZED"
            is SyncInitializationResult.Adopted -> "ADOPTED"
            is SyncInitializationResult.NeedsExplicitAction -> "NEEDS_EXPLICIT_ACTION"
            is SyncInitializationResult.Failed -> "FAILED"
            null -> null
        }
        val reason = when (result) {
            is SyncInitializationResult.NeedsExplicitAction -> result.reason
            is SyncInitializationResult.Failed -> result.reason
            else -> null
        }
        val reasonKind = reason?.let {
            when (it) {
                "confirmed bootstrap changed" -> "BOOTSTRAP_CHANGED"
                "repository default branch changed" -> "DEFAULT_BRANCH_CHANGED"
                "repository identity changed" -> "REPOSITORY_CHANGED"
                else -> "OTHER"
            }
        }
        runCatching {
            logcat(LogPriority.WARN) {
                "sync_failure phase=${phase.name} class=${kind.name}" +
                    (safeStatus?.let { " status=$it" } ?: "") +
                    (newSpace?.let { " newSpace=$it" } ?: "") +
                    (stage?.let { " stage=${it.name}" } ?: "") +
                    (resultKind?.let { " result=$it" } ?: "") +
                    (reasonKind?.let { " reason=$it" } ?: "")
            }
        }
    }
}
