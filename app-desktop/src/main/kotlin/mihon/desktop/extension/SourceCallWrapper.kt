package mihon.desktop.extension

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Result of a [safeSourceCall] invocation.
 */
sealed interface SourceCallResult<out T> {
    data class Success<T>(val value: T) : SourceCallResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : SourceCallResult<Nothing>
    data object Timeout : SourceCallResult<Nothing>
}

/**
 * Wraps an extension API call with timeout protection and uniform exception handling.
 *
 * All source calls (getPopularManga, getMangaDetails, getPageList, …) that could hang or
 * throw should be wrapped with this function so that:
 *  - A slow/unresponsive extension never freezes the UI coroutine indefinitely.
 *  - All exceptions are logged and surfaced as [SourceCallResult.Error] rather than
 *    propagating as unhandled exceptions.
 *
 * @param timeoutMs Maximum wait time in milliseconds (default 30 s).
 * @param tag       Logcat tag — defaults to the calling class name when omitted.
 * @param block     The suspend extension call to execute.
 */
suspend fun <T> safeSourceCall(
    timeoutMs: Long = 30_000L,
    tag: String = "SourceCall",
    block: suspend () -> T,
): SourceCallResult<T> = try {
    withTimeout(timeoutMs) {
        SourceCallResult.Success(block())
    }
} catch (e: TimeoutCancellationException) {
    System.err.println("[$tag] Source call timed out after ${timeoutMs}ms")
    SourceCallResult.Timeout
} catch (e: Exception) {
    System.err.println("[$tag] Source call failed: ${e.message}")
    SourceCallResult.Error(e.message ?: e.javaClass.simpleName, e)
}

/**
 * Convenience extension: unwraps [SourceCallResult] to the value or returns [default].
 */
fun <T> SourceCallResult<T>.getOrDefault(default: T): T = when (this) {
    is SourceCallResult.Success -> value
    else -> default
}

/**
 * Convenience extension: returns the value or null on error/timeout.
 */
fun <T> SourceCallResult<T>.getOrNull(): T? = when (this) {
    is SourceCallResult.Success -> value
    else -> null
}
