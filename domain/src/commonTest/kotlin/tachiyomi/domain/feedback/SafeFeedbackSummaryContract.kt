package tachiyomi.domain.feedback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class SafeFeedbackSummaryContract {
    @Test
    fun `summary contains only five allowlisted software fields`() {
        val summary = SafeFeedbackSummary(
            platform = FeedbackPlatform.WINDOWS,
            appVersion = "0.11.19.91.e0b8c6a",
            osVersion = "10.0",
            architecture = "amd64",
            runtimeVersion = "21.0.6+7-LTS",
        ).text()
        assertEquals(
            "Application: Mihon Desktop\nVersion: 0.11.19.91.e0b8c6a\n" +
                "OS: Windows 10.0\nArchitecture: x86_64\nRuntime: Java 21.0.6+7-LTS",
            summary,
        )
        assertFalse("Installation" in summary)
        assertFalse("profile" in summary)
    }

    @Test
    fun `platform name and architecture come from fixed values`() {
        assertEquals(FeedbackPlatform.WINDOWS, FeedbackPlatform.fromOsName("Windows 11"))
        assertEquals(FeedbackPlatform.MACOS, FeedbackPlatform.fromOsName("Mac OS X"))
        assertEquals(FeedbackPlatform.LINUX, FeedbackPlatform.fromOsName("Linux"))
        assertEquals(FeedbackPlatform.UNKNOWN, FeedbackPlatform.fromOsName("Private computer"))
        val summary = SafeFeedbackSummary(FeedbackPlatform.ANDROID, "0.19.0", "15", "arm64-v8a", "35").text()
        assertTrue("Application: Mihon\n" in summary)
        assertTrue("Architecture: arm64" in summary)
        assertTrue("Runtime: Android SDK 35" in summary)
    }

    @Test
    fun `paths URLs credentials control characters and oversized fields cannot escape the summary`() {
        val unsafe = listOf(
            "C:\\Users\\private\\profile",
            "/home/private/.mihon",
            "https://private.invalid?token=secret",
            "21\nInstallation ID: secret",
            "21\rAuthorization: Bearer secret",
            "21\tprivate",
            "21\u001b[31m",
            "21 ghp_privateSecret",
            "21 github_pat_privateSecret",
            "21 token=private",
            "21 123e4567-e89b-12d3-a456-426614174000",
            "1".repeat(200),
            "21\u202Eprivate",
            "21@example.invalid",
        )
        unsafe.forEach { value ->
            val summary = SafeFeedbackSummary(FeedbackPlatform.LINUX, value, value, value, value).text()
            assertFalse(value in summary, value)
            assertEquals(5, summary.lines().size)
            assertEquals(3, summary.split("[redacted]").size - 1)
            assertTrue("Architecture: unknown" in summary)
        }
    }

    @Test
    fun `feedback address is a fixed anonymous entry and never carries diagnostics`() {
        assertEquals(
            "https://altaircardinal.github.io/mihon-desktop/feedback/",
            SafeFeedbackSummary.FEEDBACK_FORM_URL,
        )
    }
}
