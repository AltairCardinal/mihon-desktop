package tachiyomi.domain.feedback

enum class FeedbackPlatform {
    ANDROID,
    WINDOWS,
    MACOS,
    LINUX,
    UNKNOWN,
    ;

    companion object {
        fun fromOsName(osName: String): FeedbackPlatform = when {
            osName.startsWith("Windows", ignoreCase = true) -> WINDOWS
            osName.equals("Mac OS X", ignoreCase = true) || osName.equals("macOS", ignoreCase = true) -> MACOS
            osName.equals("Linux", ignoreCase = true) -> LINUX
            else -> UNKNOWN
        }
    }
}

class SafeFeedbackSummary(
    private val platform: FeedbackPlatform,
    private val appVersion: String,
    private val osVersion: String,
    private val architecture: String,
    private val runtimeVersion: String,
) {
    fun text(): String = listOf(
        "Application: ${if (platform == FeedbackPlatform.ANDROID) "Mihon" else "Mihon Desktop"}",
        "Version: ${safeVersion(appVersion)}",
        "OS: ${platformName()} ${safeVersion(osVersion)}",
        "Architecture: ${safeArchitecture()}",
        "Runtime: ${if (platform == FeedbackPlatform.ANDROID) "Android SDK" else "Java"} ${safeVersion(
            runtimeVersion,
        )}",
    ).joinToString("\n")

    private fun platformName(): String = when (platform) {
        FeedbackPlatform.ANDROID -> "Android"
        FeedbackPlatform.WINDOWS -> "Windows"
        FeedbackPlatform.MACOS -> "macOS"
        FeedbackPlatform.LINUX -> "Linux"
        FeedbackPlatform.UNKNOWN -> "Unknown"
    }

    private fun safeArchitecture(): String = when (architecture.lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64", "arm64-v8a" -> "arm64"
        "x86", "i386", "i686" -> "x86"
        "arm", "armv7l", "armeabi-v7a" -> "armv7"
        else -> "unknown"
    }

    private fun safeVersion(value: String): String = when {
        value.isEmpty() -> "unknown"
        !VERSION.matches(value) || SENSITIVE.containsMatchIn(value) -> "[redacted]"
        else -> value
    }

    companion object {
        // A fixed destination deliberately keeps diagnostics out of browser history and request URLs.
        const val FEEDBACK_FORM_URL =
            "https://altaircardinal.github.io/mihon-desktop/feedback/"
        private val VERSION = Regex("[0-9][A-Za-z0-9._+()-]{0,79}")
        private val SENSITIVE =
            Regex(
                "gh[pousr]_|github_pat_|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                RegexOption.IGNORE_CASE,
            )
    }
}
