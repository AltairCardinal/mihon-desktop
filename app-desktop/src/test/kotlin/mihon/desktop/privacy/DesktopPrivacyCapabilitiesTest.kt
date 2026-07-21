package mihon.desktop.privacy

import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.updates.UpdatesScreenModelFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.updates.interactor.GetUpdates
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Isolated
class DesktopPrivacyCapabilitiesTest {
    @Test
    fun `production capabilities report only real desktop integrations`() {
        val capabilities = DesktopPrivacyCapabilities.production

        assertCapability(
            capabilities.nativeSystemNotifications,
            DesktopCapabilitySupport.Unsupported,
            "native_system_notifications_unavailable",
        )
        assertCapability(capabilities.inAppFeedback, DesktopCapabilitySupport.Supported, "in_app_feedback_available")
        assertCapability(capabilities.telemetryRuntime, DesktopCapabilitySupport.Unsupported, "telemetry_runtime_not_included")
        assertCapability(capabilities.systemWidgetProvider, DesktopCapabilitySupport.Unsupported, "system_widget_provider_unavailable")
        assertCapability(capabilities.sharedUpdatesData, DesktopCapabilitySupport.Supported, "shared_updates_data_available")
    }

    @Test
    fun `desktop DI and UI share capabilities without registering unsupported privacy runtime`(@TempDir tempDir: File) =
        runBlocking {
            val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore())
            try {
                val capabilities = Injekt.get<DesktopPrivacyCapabilities>()
                val uiDependencies = DesktopUiDependencies.fromInjekt()

                assertSame(capabilities, uiDependencies.privacyCapabilities)
                assertSame(DesktopPrivacyCapabilities.production, capabilities)
                assertThrows<Throwable> { Injekt.get<PrivacyPreferences>() }
                assertNotNull(Injekt.get<GetUpdates>())
                assertNotNull(UpdatesScreenModelFactory.create())
                assertFalse(capabilities.systemWidgetProvider.isSupported)
                assertTrue(capabilities.sharedUpdatesData.isSupported)
            } finally {
                context.closeAndJoin()
            }
        }

    private fun assertCapability(
        capability: DesktopCapability,
        support: DesktopCapabilitySupport,
        reasonSlug: String,
    ) {
        assertEquals(support, capability.support)
        assertEquals(reasonSlug, capability.reasonSlug)
    }
}
