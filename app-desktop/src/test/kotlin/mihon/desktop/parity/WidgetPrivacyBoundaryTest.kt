package mihon.desktop.parity

import mihon.desktop.privacy.DesktopCapabilitySupport
import mihon.desktop.privacy.DesktopPrivacyCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetPrivacyBoundaryTest {
    @Test
    fun `desktop exposes shared updates but no system widget provider`() {
        val capabilities = DesktopPrivacyCapabilities.production

        assertEquals(DesktopCapabilitySupport.Unsupported, capabilities.systemWidgetProvider.support)
        assertEquals("system_widget_provider_unavailable", capabilities.systemWidgetProvider.reasonSlug)
        assertFalse(capabilities.systemWidgetProvider.isSupported)
        assertTrue(capabilities.sharedUpdatesData.isSupported)
    }
}
