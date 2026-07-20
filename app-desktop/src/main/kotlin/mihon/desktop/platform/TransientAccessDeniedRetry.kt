package mihon.desktop.platform

import java.nio.file.AccessDeniedException

internal fun <T> retryTransientAccessDenied(operation: () -> T): T {
    var lastFailure: AccessDeniedException? = null
    repeat(TRANSIENT_ACCESS_DENIED_ATTEMPTS) { attempt ->
        try {
            return operation()
        } catch (error: AccessDeniedException) {
            lastFailure = error
            if (attempt + 1 < TRANSIENT_ACCESS_DENIED_ATTEMPTS) {
                Thread.sleep(TRANSIENT_ACCESS_DENIED_RETRY_DELAY_MILLIS)
            }
        }
    }
    throw checkNotNull(lastFailure)
}

private const val TRANSIENT_ACCESS_DENIED_ATTEMPTS = 3
private const val TRANSIENT_ACCESS_DENIED_RETRY_DELAY_MILLIS = 10L
