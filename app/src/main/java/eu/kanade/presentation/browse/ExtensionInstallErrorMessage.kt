package eu.kanade.presentation.browse

import dev.icerock.moko.resources.StringResource
import mihon.domain.error.AppError
import tachiyomi.i18n.MR

/** Installation authentication concerns the package/repository, never a request to log in or trust a new signer. */
internal fun extensionInstallErrorMessage(error: AppError): StringResource? = when (error) {
    is AppError.Authentication, is AppError.Challenge -> MR.strings.extension_install_error_verification
    is AppError.Network -> MR.strings.extension_install_error_network
    is AppError.RateLimited -> MR.strings.extension_install_error_rate_limit
    is AppError.Server -> MR.strings.extension_install_error_server
    is AppError.Permission -> MR.strings.extension_install_error_permission
    is AppError.MalformedData, AppError.NoResults -> MR.strings.extension_install_error_package
    is AppError.Storage, is AppError.PartialFailure -> MR.strings.extension_install_error_recovery
    is AppError.Unknown -> MR.strings.extension_install_error_unknown
    AppError.Cancelled -> null
}
