package mihon.desktop.ui.extension

import mihon.domain.error.AppError
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.presentation.ExtensionPresentationResult
import mihon.domain.extension.presentation.ExtensionPresentationSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import java.util.Locale

class ExtensionListCopyContractTest {
    @Test
    fun `projection consumes shared groups search options and failure identity`() {
        val update = available("pkg.update", "Update").item()
        val installed = available("pkg.installed", "Installed").item()
        val visible = available("pkg.visible", "Different name").item().let {
            it.copy(presentation = it.presentation.copy(sources = listOf(ExtensionPresentationSource(7, "en", "Needle source"))))
        }
        val filtered = available("pkg.filtered", "Filtered by options").item()
        val failure = RepositoryCatalogFailure(RepositoryIdentity("https://failed", "Failed", "key"), AppError.Network())
        val state = DesktopExtensionsState(
            projection = DesktopExtensionProjection(listOf(update, installed), listOf(update, visible, filtered), listOf(failure)),
            presentation = ExtensionPresentationResult(listOf(update), listOf(installed), listOf(visible), emptyList()),
            options = ExtensionPresentationOptions(false, setOf("en")),
        )

        val projection = state.toExtensionListUiProjection("")

        assertEquals(listOf(update, installed), projection.installed)
        assertEquals(listOf(visible), projection.available)
        assertEquals(listOf(update), projection.updates)
        assertSame(failure, projection.failures.single())
        assertEquals(listOf(visible), state.toExtensionListUiProjection("pkg.visible").available)
        assertEquals(listOf(visible), state.toExtensionListUiProjection("Needle").available)
    }

    @Test
    fun `base and Chinese list plus action copy load through generated resources`() {
        val base = extensionListCopy(Locale.ENGLISH)
        val chinese = extensionListCopy(Locale.SIMPLIFIED_CHINESE)
        val actions = listOf(
            MR.strings.desktop_extension_installing_message,
            MR.strings.desktop_extension_installed_message,
            MR.strings.desktop_extension_install_failed,
            MR.strings.desktop_extension_android_only,
            MR.strings.desktop_extension_conversion_failed,
            MR.strings.desktop_extension_trust_changed,
            MR.strings.desktop_extension_open_link_failed,
            MR.strings.desktop_extension_updating_message,
            MR.strings.desktop_extension_updated_message,
        )

        assertNotEquals(base.emptyAvailable, chinese.emptyAvailable)
        assertFalse(base.emptyAvailable.contains("JVM", ignoreCase = true))
        assertFalse(base.emptyAvailable.contains("Android-only", ignoreCase = true))
        assertTrue(actions.all { it.localized(Locale.ENGLISH).isNotBlank() && it.localized(Locale.SIMPLIFIED_CHINESE).isNotBlank() })
        assertTrue(actions.all { it.localized(Locale.ENGLISH) != it.localized(Locale.SIMPLIFIED_CHINESE) })
    }

    private fun available(pkg: String, name: String) = mihon.desktop.extension.DesktopAvailableExtension(
        name = name,
        pkgName = pkg,
        versionName = "1.4.0",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        jarUrl = "https://repo/$pkg.jar",
        iconUrl = "",
        repoUrl = "https://repo",
    )
}
