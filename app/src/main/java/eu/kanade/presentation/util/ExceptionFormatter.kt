package eu.kanade.presentation.util

import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.system.isOnline
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.i18n.MR
import java.net.UnknownHostException

/** Shared by source search and the reader so access denial never suggests a login. */
fun sourceErrorMessageResource(error: AppError): StringResource? = when (error) {
    is AppError.Authentication -> MR.strings.source_error_authentication
    is AppError.Forbidden -> MR.strings.source_error_forbidden
    else -> null
}

context(context: Context)
val Throwable.formattedMessage: String
    get() {
        if (this is AppErrorException) {
            sourceErrorMessageResource(error)?.let { return context.stringResource(it) }
        }
        when (this) {
            is HttpException -> return when (code) {
                401 -> context.stringResource(MR.strings.source_error_authentication)
                403 -> context.stringResource(MR.strings.source_error_forbidden)
                else -> context.stringResource(MR.strings.exception_http, code)
            }
            is UnknownHostException -> {
                return if (!context.isOnline()) {
                    context.stringResource(MR.strings.exception_offline)
                } else {
                    context.stringResource(MR.strings.exception_unknown_host, message ?: "")
                }
            }

            is NoResultsException -> return context.stringResource(MR.strings.no_results_found)
            is SourceNotInstalledException -> return context.stringResource(MR.strings.loader_not_implemented_error)
        }
        return when (val className = this::class.simpleName) {
            "Exception", "IOException" -> message ?: className
            else -> "$className: $message"
        }
    }
