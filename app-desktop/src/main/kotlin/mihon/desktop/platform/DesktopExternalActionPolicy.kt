package mihon.desktop.platform

import mihon.desktop.test.state.applicationState

object DesktopExternalActionPolicy {
    private val platformAcceptanceDepth = ThreadLocal.withInitial { 0 }
    private val websiteAcceptance = ThreadLocal<java.net.URI?>()

    internal fun <T> allowSingleWebsiteAcceptance(uri: java.net.URI, block: () -> T): T {
        check(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "[::1]") &&
            uri.port in 1..65535 && uri.userInfo == null && uri.fragment == null)
        check(websiteAcceptance.get() == null)
        websiteAcceptance.set(uri)
        return try { block() } finally { websiteAcceptance.remove() }
    }

    internal fun requireBrowserAllowed(uri: java.net.URI) {
        if (websiteAcceptance.get() == uri) {
            websiteAcceptance.remove()
            return
        }
        requireAllowed("System browser")
    }

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
