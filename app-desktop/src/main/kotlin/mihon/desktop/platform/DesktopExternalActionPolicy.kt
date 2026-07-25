package mihon.desktop.platform

import mihon.desktop.test.state.applicationState

object DesktopExternalActionPolicy {
    private val platformAcceptanceDepth = ThreadLocal.withInitial { 0 }

    fun isSuppressed(): Boolean =
        isSuppressed(
            gradleWorkerId = System.getProperty("org.gradle.test.worker"),
            testMode = applicationState.testMode,
        )

    internal fun isShareSuppressed(): Boolean =
        platformAcceptanceDepth.get() == 0 && isSuppressed()

    internal fun isSuppressed(
        gradleWorkerId: String?,
        testMode: Boolean,
    ): Boolean = gradleWorkerId != null || testMode

    internal fun <T> allowSinglePlatformAcceptance(block: () -> T): T {
        val previous = platformAcceptanceDepth.get()
        platformAcceptanceDepth.set(previous + 1)
        return try {
            block()
        } finally {
            platformAcceptanceDepth.set(previous)
        }
    }

    fun requireAllowed(action: String) {
        check(!isSuppressed()) {
            "$action is disabled in automated test contexts"
        }
    }
}
