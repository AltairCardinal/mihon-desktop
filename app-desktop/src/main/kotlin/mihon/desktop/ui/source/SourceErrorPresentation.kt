package mihon.desktop.ui.source

import mihon.domain.error.AppError
import tachiyomi.i18n.MR
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** Shared user-facing presentation for failures raised by extension source calls. */
internal fun desktopSourceErrorMessage(
    error: AppError,
    locale: Locale = Locale.getDefault(),
): String = when (error) {
    is AppError.Network -> when {
        error.cause.hasCause { it.isTimeoutMarker() } -> MR.strings.desktop_source_network_timeout.localized(locale)
        error.cause.hasCause { it is UnknownHostException } -> MR.strings.desktop_source_network_dns.localized(locale)
        error.cause.hasCause { it is SSLException } -> MR.strings.desktop_source_network_tls.localized(locale)
        error.cause.hasCause { it.isConnectionRefused() } -> MR.strings.desktop_source_network_refused.localized(locale)
        error.cause.hasCause { it.isConnectionReset() } -> MR.strings.desktop_source_network_reset.localized(locale)
        else -> MR.strings.desktop_source_network_error.localized(locale)
    }
    is AppError.Authentication -> MR.strings.login.localized(locale)
    is AppError.RateLimited -> error.retryAfterSeconds?.let {
        MR.strings.desktop_ui_download_rate_limited_seconds.localized(locale, it)
    } ?: MR.strings.desktop_ui_download_rate_limited.localized(locale)
    is AppError.Server -> MR.strings.desktop_ui_download_server_error.localized(locale, error.statusCode)
    is AppError.MalformedData -> MR.strings.desktop_ui_download_malformed_error.localized(locale)
    AppError.NoResults -> MR.strings.no_results_found.localized(locale)
    else -> MR.strings.unknown_error.localized(locale)
}

private fun Throwable?.hasCause(predicate: (Throwable) -> Boolean): Boolean =
    generateSequence(this) { it.cause }.any(predicate)

private fun Throwable.isTimeoutMarker(): Boolean = when (this) {
    is SocketTimeoutException, is TimeoutException -> true
    is InterruptedIOException -> message.orEmpty().let {
        it.contains("timeout", ignoreCase = true) || it.contains("timed out", ignoreCase = true)
    }
    else -> false
}

private fun Throwable.isConnectionRefused(): Boolean =
    this is ConnectException && message.orEmpty().contains("refused", ignoreCase = true)

private fun Throwable.isConnectionReset(): Boolean =
    this is SocketException && message.orEmpty().let { message ->
        message.contains("reset", ignoreCase = true) ||
            message.contains("broken pipe", ignoreCase = true) ||
            message.contains("abort", ignoreCase = true)
    }
