package mihon.desktop.test

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mihon.desktop.test.http.testHttpServer
import mihon.desktop.test.screenshot.ScreenshotService
import mihon.desktop.test.state.applicationState
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

/**
 * Manages test mode lifecycle for the desktop application.
 *
 * Test mode enables:
 * - HTTP API server for test control (port 8080)
 * - Screenshot capture service
 * - Navigation tracking
 * - Application state inspection
 */
object TestMode {

    private val logger = LoggerFactory.getLogger(TestMode::class.java)

    private var isStarted = false
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(Dispatchers.Default)
    private var termination = CountDownLatch(0)

    /**
     * Start test mode with the given configuration.
     */
    fun start(args: TestArguments) {
        if (isStarted) {
            logger.warn("Test mode already started")
            return
        }

        logger.info("Starting test mode with config: httpPort=${args.httpPort}, headless=${args.headless}")
        termination = CountDownLatch(1)

        // Initialize screenshot service
        ScreenshotService.initialize(args.screenshotDir)

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
                "HomeScreen",
            ),
        )

        // Register available actions
        applicationState.registerActions(
            listOf(
                "search",
                "filter",
                "sort",
                "select",
                "scroll",
                "navigate",
                "click",
                "reader_next_page",
                "reader_prev_page",
                "reader_next_chapter",
                "reader_prev_chapter",
                "reader_mode",
                "reader_zoom",
                "addToLibrary",
                "removeFromLibrary",
                "download",
                "setting_change",
                "setting_reset",
            ),
        )

        // Start HTTP server
        startHttpServer(args.httpPort)

        isStarted = true
        logger.info("Test mode started successfully on port ${args.httpPort}")
    }

    /**
     * Start the HTTP test server.
     */
    private fun startHttpServer(port: Int) {
        serverJob = serverScope.launch {
            try {
                embeddedServer(Netty, port = port) {
                    testHttpServer()
                }.start(wait = true)
            } catch (e: Exception) {
                logger.error("HTTP test server failed to start", e)
            } finally {
                termination.countDown()
            }
        }

        logger.info("HTTP test server started on port $port")
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

        // Stop HTTP server
        serverJob?.cancel()
        serverJob = null
        termination.countDown()

        // Disable screenshot service
        ScreenshotService.disable()

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

    /**
     * Keep a headless test process alive for as long as its HTTP server is running.
     */
    fun awaitTermination() = termination.await()

    /**
     * Get the HTTP server port.
     */
    fun getHttpPort(): Int = 8080
}

/**
 * DSL for configuring test mode.
 */
fun testMode(block: TestArgumentsBuilder.() -> Unit): TestArguments {
    val builder = TestArgumentsBuilder()
    builder.block()
    return builder.build()
}

/**
 * Builder for TestArguments.
 */
class TestArgumentsBuilder {
    var testMode: Boolean = false
    var httpPort: Int = TestArguments.DEFAULT_HTTP_PORT
    var jmxPort: Int = TestArguments.DEFAULT_JMX_PORT
    var headless: Boolean = false
    var screenshotDir: String = TestArguments.DEFAULT_SCREENSHOT_DIR

    fun build(): TestArguments = TestArguments(
        testMode = testMode,
        httpPort = httpPort,
        jmxPort = jmxPort,
        headless = headless,
        screenshotDir = screenshotDir,
    )
}
