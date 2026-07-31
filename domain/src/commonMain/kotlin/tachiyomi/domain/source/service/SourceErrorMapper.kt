package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import okio.IOException

/**
 * Converts every extension source failure to the shared application error taxonomy.
 *
 * Source entry points (search, details, chapters, pages and downloads) must use this mapper rather
 * than inventing operation-specific strings. Known network failures may be wrapped by extension or
 * reactive adapters, so the cause chain is inspected before treating the failure as malformed data.
 */
fun Throwable.toSourceAppError(): AppError {
    knownSourceAppError()?.let { return it }
    return AppError.MalformedData(this)
}

private fun Throwable.knownSourceAppError(): AppError? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        when (current) {
            is AppErrorException -> return current.error
            is CancellationException -> return AppError.Cancelled
            is HttpException -> return when (current.code) {
                401, 403 -> AppError.Authentication(current)
                429 -> AppError.RateLimited(cause = current)
                in 500..599 -> AppError.Server(current.code, current)
                else -> AppError.Unknown(current)
            }
            is IOException -> return AppError.Network(current)
        }
        current = current.cause
    }
    return null
}
