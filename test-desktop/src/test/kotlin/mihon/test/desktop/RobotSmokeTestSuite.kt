package mihon.test.desktop

import mihon.test.desktop.robot.BrowseRobot
import mihon.test.desktop.robot.DownloadsRobot
import mihon.test.desktop.robot.HistoryRobot
import mihon.test.desktop.robot.LibraryRobot
import mihon.test.desktop.robot.MangaDetailRobot
import mihon.test.desktop.robot.MoreRobot
import mihon.test.desktop.robot.ReaderRobot
import mihon.test.desktop.robot.SettingsRobot
import mihon.test.desktop.robot.UpdatesRobot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Smoke tests for Robot classes.
 *
 * These tests verify that robot classes can be instantiated and have
 * the expected method signatures. They do NOT connect to a server.
 */
class RobotSmokeTestSuite {

    // ── Library Robot Tests ───────────────────────────────────────────────────

    @Test
    fun `library robot can be instantiated`() {
        val client = createMockClient()
        val robot = LibraryRobot(client)

        assertNotNull(robot)
    }

    @Test
    fun `manga detail robot can be instantiated`() {
        val client = createMockClient()
        val robot = MangaDetailRobot(client)

        assertNotNull(robot)
    }

    @Test
    fun `reader robot can be instantiated`() {
        val client = createMockClient()
        val robot = ReaderRobot(client)

        assertNotNull(robot)
    }

    @Test
    fun `settings robot can be instantiated`() {
        val client = createMockClient()
        val robot = SettingsRobot(client)

        assertNotNull(robot)
    }

    @Test
    fun `browse robot can be instantiated`() {
        val client = createMockClient()
        val robot = BrowseRobot(client)

        assertNotNull(robot)
    }

    @Test
    fun `downloads robot can be instantiated`() {
        val client = createMockClient()
        val robot = DownloadsRobot(client)

        assertNotNull(robot)
    }

    @Test
    fun `updates robot can be instantiated`() {
        val client = createMockClient()
        val robot = UpdatesRobot(client)

        assertNotNull(robot)
    }

    @Test
    fun `history robot can be instantiated`() {
        val client = createMockClient()
        val robot = HistoryRobot(client)

        assertNotNull(robot)
    }

    @Test
    fun `more robot can be instantiated`() {
        val client = createMockClient()
        val robot = MoreRobot(client)

        assertNotNull(robot)
    }

    // ── Test Client Tests ───────────────────────────────────────────────────

    @Test
    fun `desktop test client has all robot properties`() {
        val client = createMockClient()

        assertNotNull(client.library)
        assertNotNull(client.reader)
        assertNotNull(client.settings)
        assertNotNull(client.browse)
        assertNotNull(client.downloads)
        assertNotNull(client.updates)
        assertNotNull(client.history)
        assertNotNull(client.more)
    }

    @Test
    fun `app state has all expected fields`() {
        val state = AppState(
            currentScreen = "LibraryTab",
            isLoading = false,
            testMode = true,
            downloadQueueSize = 5,
            downloadsPaused = false,
            updateCount = 10,
            hasUnreadUpdates = true,
            historyCount = 25,
        )

        assertEquals("LibraryTab", state.currentScreen)
        assertFalse(state.isLoading)
        assertTrue(state.testMode)
        assertEquals(5, state.downloadQueueSize)
        assertFalse(state.downloadsPaused!!)
        assertEquals(10, state.updateCount)
        assertTrue(state.hasUnreadUpdates!!)
        assertEquals(25, state.historyCount)
    }

    @Test
    fun `screenshot result has expected fields`() {
        val result = ScreenshotResult(
            success = true,
            path = "/tmp/screenshot.png",
            width = 1920,
            height = 1080,
        )

        assertTrue(result.success)
        assertEquals("/tmp/screenshot.png", result.path)
        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
    }

    @Test
    fun `action result has expected fields`() {
        val result = ActionResult(
            success = true,
            action = "search",
        )

        assertTrue(result.success)
        assertEquals("search", result.action)
    }

    @Test
    fun `navigate result has expected fields`() {
        val result = NavigateResult(
            success = true,
            newScreen = "LibraryTab",
        )

        assertTrue(result.success)
        assertEquals("LibraryTab", result.newScreen)
    }

    // ── Helper Methods ───────────────────────────────────────────────────────

    private fun createMockClient(): DesktopTestClient {
        return DesktopTestClient("localhost", 0)
    }
}
