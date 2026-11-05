package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceFailureIdentity
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.network.withSourceFailureIdentity
import kotlinx.coroutines.CancellationException
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import okio.IOException

/**
 * Converts every extension source failure to the shared application error taxonomy.
 *
 * Source entry points (search, details, chapters, pages and downloads) must use this mapper rather
 * than inventing operation-specific strings. Known network failures may be wrapped by extension or
 * reactive adapters, so the bounded cause chain is inspected before ordinary IO/HTTP mapping.
 * Fatal causes and unknown deep tails propagate; cancellation keeps the existing Cancelled result.
 */
fun Throwable.toSourceAppError(): AppError {
    val failure = inspectSourceFailure()
    failure.fatal?.let { throw it }
    if (!failure.complete) throw this
    if (failure.cancellation != null) return AppError.Cancelled
    if (failure.linkage != null) return AppError.ExtensionIncompatible(this)
    failure.causes.forEach { current ->
        current.knownSourceAppError()?.let { return it }
    }
    return AppError.MalformedData(this)
}

/** The caller captures the installed owner before invoking the extension, never on failure. */
fun AppError.withSourceFailureIdentity(identity: SourceFailureIdentity): AppError = when (this) {
    is AppError.ExtensionIncompatible -> copy(cause = cause?.withSourceFailureIdentity(identity))
    else -> this
}

private fun Throwable.knownSourceAppError(): AppError? {
    when (this) {
        is AppErrorException -> return error
        is CancellationException -> return AppError.Cancelled
        is HttpException -> return when (code) {
            401 -> AppError.Authentication(this)
            403 -> AppError.Forbidden(this)
            429 -> AppError.RateLimited(cause = this)
            in 500..599 -> AppError.Server(code, this)
            else -> AppError.Unknown(this)
        }
        is IOException -> return AppError.Network(this)
    }
    return null
}
