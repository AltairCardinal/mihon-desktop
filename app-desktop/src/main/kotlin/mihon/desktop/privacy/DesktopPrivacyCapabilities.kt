package mihon.desktop.privacy

enum class DesktopCapabilitySupport { Supported, Unsupported }

data class DesktopCapability(
    val support: DesktopCapabilitySupport,
    val reasonSlug: String,
) {
    val isSupported: Boolean get() = support == DesktopCapabilitySupport.Supported
}

data class DesktopPrivacyCapabilities(
    val nativeSystemNotifications: DesktopCapability,
    val inAppFeedback: DesktopCapability,
    val telemetryRuntime: DesktopCapability,
    val systemWidgetProvider: DesktopCapability,
    val sharedUpdatesData: DesktopCapability,
) {
    companion object {
        val production = DesktopPrivacyCapabilities(
            nativeSystemNotifications = DesktopCapability(
                DesktopCapabilitySupport.Unsupported,
                "native_system_notifications_unavailable",
            ),
            inAppFeedback = DesktopCapability(
                DesktopCapabilitySupport.Supported,
                "in_app_feedback_available",
            ),
            telemetryRuntime = DesktopCapability(
                DesktopCapabilitySupport.Unsupported,
                "telemetry_runtime_not_included",
            ),
            systemWidgetProvider = DesktopCapability(
                DesktopCapabilitySupport.Unsupported,
                "system_widget_provider_unavailable",
            ),
            sharedUpdatesData = DesktopCapability(
                DesktopCapabilitySupport.Supported,
                "shared_updates_data_available",
            ),
        )
    }
}
