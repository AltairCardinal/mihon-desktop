package mihon.test.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.test.desktop.data.TestDataClient
import mihon.test.desktop.robot.BrowseRobot
import mihon.test.desktop.robot.DownloadsRobot
import mihon.test.desktop.robot.HistoryRobot
import mihon.test.desktop.robot.LibraryRobot
import mihon.test.desktop.robot.MoreRobot
import mihon.test.desktop.robot.ReaderRobot
import mihon.test.desktop.robot.SettingsRobot
import mihon.test.desktop.robot.UpdatesRobot
import mihon.test.desktop.visual.VisualTestClient
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Main test client for controlling Mihon Desktop application.
 */
class DesktopTestClient(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(DesktopTestClient::class.java)

    val http = HttpClient(OkHttp)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    val baseUrl = "http://$host:$port"

    // Robot modules - Library & Details
    val library = LibraryRobot(this)
    val reader = ReaderRobot(this)
    val settings = SettingsRobot(this)
    val browse = BrowseRobot(this)

    // Robot modules - Additional screens
    val downloads = DownloadsRobot(this)
    val updates = UpdatesRobot(this)
    val history = HistoryRobot(this)
    val more = MoreRobot(this)

    // Visual testing
    val visual = VisualTestClient(this)

    // Data management
    val data = TestDataClient(this)

    /**
     * Start the desktop application in test mode.
     */
    fun start(
        appPath: String = "/Applications/Mihon Desktop.app",
        headless: Boolean = false,
    ): DesktopTestClient {
        logger.info("Starting desktop app in test mode: $appPath")

        val args = buildList {
            add(appPath)
            add("--test-mode")
            add("--test-http-port=$port")
            if (headless) add("--headless")
        }.toTypedArray()

        Runtime.getRuntime().exec(args)

        // Wait for server to start
        Thread.sleep(2000)

        // Verify server is running
        if (!isServerRunning()) {
            throw IllegalStateException("Desktop app failed to start HTTP server")
        }

        logger.info("Desktop app started successfully")
        return this
    }

    /**
     * Stop the desktop application.
     */
    fun stop() {
        logger.info("Stopping desktop app")
        http.close()
    }

    /**
     * Check if the test server is running.
     */
    fun isServerRunning(): Boolean {
        return try {
            runBlocking {
                val response: HttpResponse = http.get("$baseUrl/test/health")
                response.status == HttpStatusCode.OK
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get current application state.
     */
    fun getState(): AppState {
        return runBlocking {
            val response = http.get("$baseUrl/test/state")
            if (response.status == HttpStatusCode.OK) {
                json.decodeFromString<AppState>(response.bodyAsText())
            } else {
                throw IllegalStateException("Failed to get app state: ${response.status}")
            }
        }
    }

    /**
     * Get list of available screens.
     */
    fun getScreens(): List<ScreenInfo> {
        return runBlocking {
            val response = http.get("$baseUrl/test/screens")
            if (response.status == HttpStatusCode.OK) {
                json.decodeFromString<List<ScreenInfo>>(response.bodyAsText())
            } else {
                emptyList()
            }
        }
    }

    /**
     * Navigate to a screen.
     */
    fun navigate(screen: String): NavigateResult {
        return runBlocking {
            val response = http.post("$baseUrl/test/navigate/$screen")
            if (response.status == HttpStatusCode.OK) {
                json.decodeFromString<NavigateResult>(response.bodyAsText())
            } else {
                NavigateResult(success = false, newScreen = null, error = response.status.toString())
            }
        }
    }

    /**
     * Execute a test action.
     */
    fun executeAction(action: String, params: Map<String, Any> = emptyMap()): ActionResult {
        return runBlocking {
            val body = params.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
            val response = http.post("$baseUrl/test/action/$action") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                json.decodeFromString<ActionResult>(response.bodyAsText())
            } else {
                ActionResult(success = false, action = action, error = response.status.toString())
            }
        }
    }

    /**
     * Capture a screenshot.
     */
    fun screenshot(name: String): ScreenshotResult {
        return runBlocking {
            val body = """{"name": "$name"}"""
            val response = http.post("$baseUrl/test/screenshot") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                json.decodeFromString<ScreenshotResult>(response.bodyAsText())
            } else {
                ScreenshotResult(success = false, error = response.status.toString())
            }
        }
    }

    /**
     * Reset test state.
     */
    fun reset() {
        runBlocking {
            http.post("$baseUrl/test/reset")
        }
    }

    override fun close() {
        stop()
    }

    companion object {
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 8080
    }
}

// Data classes for API responses
@Serializable
data class AppState(
    val currentScreen: String? = null,
    val isLoading: Boolean = false,
    val notifications: List<String> = emptyList(),
    val screens: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val testMode: Boolean = false,
    val timestamp: String = Instant.now().toString(),
    // Download state
    val downloadQueueSize: Int? = null,
    val downloadsPaused: Boolean? = null,
    // Updates state
    val updateCount: Int? = null,
    val hasUnreadUpdates: Boolean? = null,
    // History state
    val historyCount: Int? = null,
)

@Serializable
data class ScreenInfo(
    val id: String,
    val name: String,
)

@Serializable
data class NavigateResult(
    val success: Boolean,
    val newScreen: String? = null,
    val error: String? = null,
    val timestamp: String = Instant.now().toString(),
)

@Serializable
data class ActionResult(
    val success: Boolean,
    val action: String,
    val error: String? = null,
    val timestamp: String = Instant.now().toString(),
)

@Serializable
data class ScreenshotResult(
    val success: Boolean,
    val path: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val error: String? = null,
    val timestamp: String = Instant.now().toString(),
)
