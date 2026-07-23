package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.Tab
import mihon.desktop.ui.settings.AboutScreen
import mihon.desktop.ui.settings.AppearanceSettingsScreen
import mihon.desktop.ui.settings.LibrarySettingsScreen
import mihon.desktop.ui.settings.LicenseDetailScreen
import mihon.desktop.ui.settings.LicenseListScreen
import mihon.desktop.ui.settings.MoreRootScreen
import mihon.desktop.ui.settings.ReaderSettingsScreen
import mihon.desktop.ui.settings.licenseDetailDestination
import mihon.desktop.ui.settings.licenseListDestination
import mihon.domain.license.model.DependencyNotice
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** RED — settings screens do not exist yet. */
class PhaseFNavigationContractTest {

    @Test
    fun `MoreRootScreen is a Screen not a Tab`() {
        val screen = MoreRootScreen()
        assertTrue(screen is Screen)
        assertFalse(screen is Tab)
    }

    @Test
    fun `AppearanceSettingsScreen is a Screen not a Tab`() {
        val screen = AppearanceSettingsScreen()
        assertTrue(screen is Screen)
        assertFalse(screen is Tab)
    }

    @Test
    fun `ReaderSettingsScreen is a Screen not a Tab`() {
        val screen = ReaderSettingsScreen()
        assertTrue(screen is Screen)
        assertFalse(screen is Tab)
    }

    @Test
    fun `LibrarySettingsScreen is a Screen not a Tab`() {
        val screen = LibrarySettingsScreen()
        assertTrue(screen is Screen)
        assertFalse(screen is Tab)
    }

    @Test
    fun `AboutScreen is a Screen not a Tab`() {
        val screen = AboutScreen()
        assertTrue(screen is Screen)
        assertFalse(screen is Tab)
    }

    @Test
    fun `license destinations are Screens not Tabs and preserve detail arguments`() {
        val notice = DependencyNotice("Library", "https://example.com", "License content")
        val list = licenseListDestination()
        val detail = licenseDetailDestination(notice)

        assertTrue(list is Screen)
        assertFalse(list is Tab)
        assertTrue(detail is Screen)
        assertFalse(detail is Tab)
        assertTrue(detail is LicenseDetailScreen)
        assertTrue(detail.name == notice.name && detail.website == notice.website && detail.license == notice.license)
    }

    @Test
    fun `all settings screens can be instantiated without DI`() {
        assertDoesNotThrow {
            MoreRootScreen()
            AppearanceSettingsScreen()
            ReaderSettingsScreen()
            LibrarySettingsScreen()
            AboutScreen()
            LicenseListScreen()
            LicenseDetailScreen("Library", null, null)
        }
    }
}
