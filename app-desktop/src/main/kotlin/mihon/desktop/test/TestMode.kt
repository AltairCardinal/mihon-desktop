package mihon.desktop.test

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.desktop.platform.DesktopShareService
import mihon.desktop.network.DesktopNetworkMaintenancePort
import mihon.desktop.security.DesktopPassphraseVerifier
import mihon.desktop.test.http.createPlatformAcceptanceController
import mihon.desktop.test.http.BrowseSearchTestModeBridge
import mihon.desktop.test.http.BrowseSearchTestModeController
import mihon.desktop.test.http.BackupTestModeBridge
import mihon.desktop.test.http.BackupTestModeController
import mihon.desktop.test.http.DownloadTestModeBridge
import mihon.desktop.test.http.DownloadTestModeController
import mihon.desktop.test.http.HistoryTestModeBridge
import mihon.desktop.test.http.HistoryTestModeController
import mihon.desktop.test.http.SettingsTestModeBridge
import mihon.desktop.test.http.SettingsTestModeController
import mihon.desktop.test.http.TrackingTestBridge
import mihon.desktop.test.http.UpdatesTestModeBridge
import mihon.desktop.test.http.UpdatesTestModeController
import mihon.desktop.test.http.testHttpServer
import mihon.desktop.test.screenshot.ScreenshotService
import mihon.desktop.test.state.applicationState
import mihon.desktop.tracking.TrackingTestModeController
import mihon.desktop.ui.settings.SecuritySettingsController
import org.slf4j.LoggerFactory
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerServiceRegistry
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
    private var browseController: BrowseSearchTestModeController? = null
    private var backupController: BackupTestModeController? = null
    private var downloadController: DownloadTestModeController? = null
    private var updatesController: UpdatesTestModeController? = null
    private var historyController: HistoryTestModeController? = null
    private var settingsController: SettingsTestModeController? = null
    private var trackingController: TrackingTestModeController? = null

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
        val browse = BrowseSearchTestModeController(
            coordinator = mihon.desktop.ui.browse.DesktopGlobalSearchCoordinator(Injekt.get()),
            sourcesProvider = { Injekt.get<tachiyomi.domain.source.service.SourceManager>().getCatalogueSources() },
            saveSourceMangaForDetails = Injekt.get(),
            loginSessionFactory = Injekt.get(),
        )
        BrowseSearchTestModeBridge.install(browse)
        synchronized(lifecycleLock) {
            browseController = browse
        }
        val backup = BackupTestModeController(Injekt.get())
        BackupTestModeBridge.install(backup)
        synchronized(lifecycleLock) {
            backupController = backup
        }
        val downloads = DownloadTestModeController(Injekt.get())
        DownloadTestModeBridge.install(downloads)
        synchronized(lifecycleLock) {
            downloadController = downloads
        }
        val updates = UpdatesTestModeController(mihon.desktop.updates.UpdatesScreenModelFactory.create())
        UpdatesTestModeBridge.install(updates)
        synchronized(lifecycleLock) {
            updatesController = updates
        }
        val history = HistoryTestModeController(mihon.desktop.history.HistoryScreenModelFactory.create())
        HistoryTestModeBridge.install(history)
        synchronized(lifecycleLock) {
            historyController = history
        }
        val settings = SettingsTestModeController(
            security = SecuritySettingsController(
                Injekt.get<SecurityPreferences>(),
                Injekt.get<DesktopPassphraseVerifier>(),
            ),
            networkMaintenance = Injekt.get<DesktopNetworkMaintenancePort>(),
        )
        SettingsTestModeBridge.install(settings)
        synchronized(lifecycleLock) {
            settingsController = settings
        }
        val tracking = TrackingTestModeController(
            repository = Injekt.get<TrackRepository>(),
            chapterRepository = Injekt.get<ChapterRepository>(),
            registry = Injekt.get<TrackerServiceRegistry>(),
        )
        TrackingTestBridge.install(tracking)
        synchronized(lifecycleLock) {
            trackingController = tracking
        }

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
                "browse_search",
                "browse_select",
                "source_login_start",
                "source_login_complete",
                "source_login_cancel",
                "downloads_pause_all",
                "downloads_resume_all",
                "downloads_cancel",
                "downloads_cancel_all",
                "downloads_clear_errors",
                "downloads_retry_errors",
                "downloads_reorder",
                "downloads_sort",
                "downloads_reverse",
                "updates_refresh",
                "updates_mark_all_read",
                "updates_filter",
                "updates_clear_filters",
                "updates_open_upcoming",
                "updates_select",
                "updates_download",
                "updates_mark_read",
                "history_search",
                "history_clear_all",
                "history_remove",
                "history_select",
                "backup_create",
                "backup_restore",
                "backup_cancel",
                "setting_search",
                "setting_search_select",
                "setting_security_enable",
                "setting_security_disable",
                "setting_security_delay",
                "setting_security_change_passphrase",
                "setting_import_cloudflare_cookie",
                "setting_clear_cookies",
                "setting_clear_network_cache",
                "setting_open_crash_logs",
                "setting_cancel",
                "setting_change",
                "setting_reset",
                "tracking_login",
                "tracking_logout",
                "tracking_search",
                "tracking_bind",
                "tracking_update",
                "tracking_cancel",
            ),
        )

        // Start HTTP server
        startHttpServer(args, run, updates, history)

        isStarted = true
        logger.info("Test mode started successfully on port ${args.httpPort}")
    }

    /**
     * Start the HTTP test server.
     */
    private fun startHttpServer(
        args: TestArguments,
        run: TestModeRun,
        updates: UpdatesTestModeController,
        history: HistoryTestModeController,
    ) {
        val platformAcceptance = createPlatformAcceptanceController(
            args = args,
            evidenceRoot = Path.of(System.getProperty("java.io.tmpdir"), "mihon", "platform-acceptance"),
        ) { Injekt.get<DesktopShareService>() }
        val job = serverScope.launch {
            var startedServer: ApplicationEngine? = null
            try {
                hydrateTimelineTestModeOwners(updates, history)
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
        val activeBrowse = synchronized(lifecycleLock) {
            browseController.also { browseController = null }
        }
        val activeBackup = synchronized(lifecycleLock) {
            backupController.also { backupController = null }
        }
        val activeDownloads = synchronized(lifecycleLock) {
            downloadController.also { downloadController = null }
        }
        val activeUpdates = synchronized(lifecycleLock) {
            updatesController.also { updatesController = null }
        }
        val activeHistory = synchronized(lifecycleLock) {
            historyController.also { historyController = null }
        }
        val activeSettings = synchronized(lifecycleLock) {
            settingsController.also { settingsController = null }
        }
        val activeTracking = synchronized(lifecycleLock) {
            trackingController.also { trackingController = null }
        }
        completeTestModeStop(
            run,
            { activeBrowse?.close() },
            { activeBackup?.close() },
            { activeDownloads?.close() },
            { activeUpdates?.close() },
            { activeHistory?.close() },
            { activeSettings?.close() },
            {
                activeTracking?.let {
                    runBlocking {
                        it.closeAndJoin()
                    }
                    TrackingTestBridge.clear(it)
                }
            },
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

internal suspend fun hydrateTimelineTestModeOwners(
    updates: UpdatesTestModeController,
    history: HistoryTestModeController,
) {
    updates.hydrate()
    history.hydrate()
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
