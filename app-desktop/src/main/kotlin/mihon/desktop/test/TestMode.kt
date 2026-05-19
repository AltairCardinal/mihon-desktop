package mihon.desktop.test

import mihon.desktop.test.state.applicationState
import org.slf4j.LoggerFactory

/**
 * Manages test mode lifecycle for the desktop application.
 */
object TestMode {

    private val logger = LoggerFactory.getLogger(TestMode::class.java)

    private var isStarted = false

    /**
     * Start test mode with the given configuration.
     */
    fun start(args: TestArguments) {
        if (isStarted) {
            logger.warn("Test mode already started")
            return
        }

        logger.info("Starting test mode with config: httpPort=${args.httpPort}, headless=${args.headless}")

        // Initialize test state
        applicationState.testMode = true

        // Register available screens
        applicationState.registerScreens(
            listOf(
                "LibraryTab",
                "UpdatesTab",
                "HistoryTab",
                "BrowseTab",
                "MoreTab",
                "SettingsScreen",
                "MigrationSearchScreen",
                "ExtensionListScreen",
            ),
        )

        // Register available actions
        applicationState.registerActions(
            listOf(
                "search",
                "filter",
                "sort",
                "click",
                "select",
                "scroll",
            ),
        )

        isStarted = true
        logger.info("Test mode started successfully")
    }

    /**
     * Stop test mode and release resources.
     */
    fun stop() {
        if (!isStarted) {
            logger.warn("Test mode not started")
            return
        }

        logger.info("Stopping test mode")

        // Reset state
        applicationState.testMode = false
        applicationState.reset()

        isStarted = false
        logger.info("Test mode stopped")
    }

    /**
     * Check if test mode is active.
     */
    fun isActive(): Boolean = isStarted
}
