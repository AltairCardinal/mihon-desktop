package mihon.desktop.platform

import mihon.desktop.test.state.applicationState

object DesktopExternalActionPolicy {

    fun isSuppressed(): Boolean =
        isSuppressed(
            gradleWorkerId = System.getProperty("org.gradle.test.worker"),
            testMode = applicationState.testMode,
        )

    internal fun isSuppressed(
        gradleWorkerId: String?,
        testMode: Boolean,
    ): Boolean = gradleWorkerId != null || testMode

    fun requireAllowed(action: String) {
        check(!isSuppressed()) {
            "$action is disabled in automated test contexts"
        }
    }
}
