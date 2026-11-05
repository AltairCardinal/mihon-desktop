package mihon.desktop.extension

import mihon.desktop.ui.source.desktopSourceErrorMessage
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import java.util.Locale

class SourceCompatibilityPresentationTest {
    @Test
    fun `compatibility is localizable and does not expose private linkage details`() {
        val error = AppError.ExtensionIncompatible(NoSuchMethodError("private-token"))
        listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE).forEach { locale ->
            val message = desktopSourceErrorMessage(error, locale)
            assertEquals(MR.strings.source_error_extension_incompatible.localized(locale), message)
            assertFalse(message.contains("private-token"))
        }
    }
}
