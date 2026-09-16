package eu.kanade.tachiyomi.ui.browse.extension

import eu.kanade.presentation.browse.extensionInstallErrorMessage
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR

class ExtensionInstallErrorMessageTest {
    @Test
    fun `installation errors select safe localizable reasons without exposing exception details`() {
        val secret = IllegalStateException("/private/path?token=secret https://user:pass@repo.invalid")
        val cases = mapOf(
            AppError.Authentication(secret) to MR.strings.extension_install_error_verification,
            AppError.Challenge(secret) to MR.strings.extension_install_error_verification,
            AppError.Network(secret) to MR.strings.extension_install_error_network,
            AppError.RateLimited(10, secret) to MR.strings.extension_install_error_rate_limit,
            AppError.Server(500, secret) to MR.strings.extension_install_error_server,
            AppError.Permission(secret) to MR.strings.extension_install_error_permission,
            AppError.MalformedData(secret) to MR.strings.extension_install_error_package,
            AppError.NoResults to MR.strings.extension_install_error_package,
            AppError.Storage(secret) to MR.strings.extension_install_error_recovery,
            AppError.PartialFailure(listOf(AppError.Storage(secret))) to MR.strings.extension_install_error_recovery,
            AppError.Unknown(secret) to MR.strings.extension_install_error_unknown,
        )
        cases.forEach { (error, message) -> assertEquals(message, extensionInstallErrorMessage(error)) }
        assertNotEquals(MR.strings.login, extensionInstallErrorMessage(AppError.Authentication(secret)))
        assertNull(extensionInstallErrorMessage(AppError.Cancelled))
    }
}
