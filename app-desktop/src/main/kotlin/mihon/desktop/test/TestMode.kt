package mihon.desktop.test

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.test.http.createPlatformAcceptanceController
import mihon.desktop.test.http.testHttpServer
import mihon.desktop.test.screenshot.ScreenshotService
import mihon.desktop.test.state.applicationState
import org.slf4j.LoggerFactory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Path
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
    private var server: ApplicationEngine? = null
    private val serverScope = CoroutineScope(Dispatchers.Default)
    private val lifecycleLock = Any()
    private var activeRun: TestModeRun? = null

    /**
     * Start test mode with the given configuration.
     */
    fun start(args: TestArguments) {
        if (isStarted) {
            logger.warn("Test mode already started")
            return
        }

        logger.info("Starting test mode with config: httpPort=${args.httpPort}, headless=${args.headless}")
        val run = TestModeRun()
        synchronized(lifecycleLock) {
            activeRun = run
        }

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
                "detail_categories",
                "detail_chapter",
                "detail_cover",
                "setting_change",
                "setting_reset",
            ),
        )

        // Start HTTP server
        startHttpServer(args, run)

        isStarted = true
        logger.info("Test mode started successfully on port ${args.httpPort}")
    }

    /**
     * Start the HTTP test server.
     */
    private fun startHttpServer(args: TestArguments, run: TestModeRun) {
        val platformAcceptance = createPlatformAcceptanceController(
            args = args,
            evidenceRoot = Path.of(System.getProperty("java.io.tmpdir"), "mihon", "platform-acceptance"),
        ) { Injekt.get<DesktopShareService>() }
        val job = serverScope.launch {
            var startedServer: ApplicationEngine? = null
            try {
                startedServer = embeddedServer(Netty, host = TEST_MODE_HOST, port = args.httpPort) {
                    testHttpServer(platformAcceptanceController = platformAcceptance)
                }.start(wait = false)

                val belongsToActiveRun = synchronized(lifecycleLock) {
                    if (activeRun === run) {
                        server = startedServer
                        true
                    } else {
                        false
                    }
                }
                if (!belongsToActiveRun) {
                    startedServer.stop(SERVER_STOP_GRACE_MS, SERVER_STOP_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                startedServer?.runCatching {
                    stop(SERVER_STOP_GRACE_MS, SERVER_STOP_TIMEOUT_MS)
                }
                logger.error("HTTP test server failed to start", e)
                run.terminate()
            }
        }
        synchronized(lifecycleLock) {
            if (activeRun === run) {
                serverJob = job
            } else {
                job.cancel()
            }
        }

        logger.info("HTTP test server started on $TEST_MODE_HOST:${args.httpPort}")
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

        // Stop HTTP server and release only this start generation.
        val (run, activeServer, activeJob) = synchronized(lifecycleLock) {
            val currentRun = activeRun ?: return
            activeRun = null
            val currentServer = server
            val currentJob = serverJob
            server = null
            serverJob = null
            Triple(currentRun, currentServer, currentJob)
        }
        completeTestModeStop(
            run,
            { activeServer?.stop(SERVER_STOP_GRACE_MS, SERVER_STOP_TIMEOUT_MS) },
            { activeJob?.cancel() },
            ScreenshotService::disable,
            { applicationState.testMode = false },
            applicationState::reset,
            { isStarted = false },
        )
        logger.info("Test mode stopped")
    }

    /**
     * Check if test mode is active.
     */
    fun isActive(): Boolean = isStarted

    /**
     * Keep a headless test process alive for as long as its HTTP server is running.
     */
    fun awaitTermination() {
        synchronized(lifecycleLock) { activeRun }?.awaitTermination()
    }

    /**
     * Get the HTTP server port.
     */
    fun getHttpPort(): Int = 8080

    private const val SERVER_STOP_GRACE_MS = 100L
    private const val SERVER_STOP_TIMEOUT_MS = 1_000L
    internal const val TEST_MODE_HOST = "127.0.0.1"
}

internal class TestModeRun {
    private val termination = CountDownLatch(1)

    fun awaitTermination() = termination.await()

    fun terminate() = termination.countDown()
}

internal fun completeTestModeStop(run: TestModeRun, vararg cleanupSteps: () -> Unit) {
    var failure: Throwable? = null
    cleanupSteps.forEach { step ->
        try {
            step()
        } catch (caught: Throwable) {
            if (failure == null) {
                failure = caught
            } else if (failure !== caught) {
                failure.addSuppressed(caught)
            }
        }
    }
    run.terminate()
    failure?.let { throw it }
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
