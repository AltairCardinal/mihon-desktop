package mihon.test.desktop.robot

import mihon.test.desktop.DesktopTestClient
import mihon.test.desktop.ScreenshotResult

/**
 * Robot for Updates tab interactions.
 */
class UpdatesRobot(private val client: DesktopTestClient) {

    private val baseUrl = client.baseUrl

    /**
     * Navigate to the Updates tab.
     */
    fun open(): UpdatesRobot {
        client.navigate("UpdatesTab")
        return this
    }

    /**
     * Refresh updates from sources.
     */
    fun refresh(): UpdatesRobot {
        client.executeAction("updates_refresh")
        return this
    }

    /**
     * Mark all updates as read.
     */
    fun markAllAsRead(): UpdatesRobot {
        client.executeAction("updates_mark_all_read")
        return this
    }

    /**
     * Filter by unread updates.
     */
    fun filterUnread(enabled: Boolean = true): UpdatesRobot {
        client.executeAction("updates_filter", mapOf("type" to "unread", "enabled" to enabled.toString()))
        return this
    }

    /**
     * Filter by downloaded updates.
     */
    fun filterDownloaded(enabled: Boolean = true): UpdatesRobot {
        client.executeAction("updates_filter", mapOf("type" to "downloaded", "enabled" to enabled.toString()))
        return this
    }

    /**
     * Filter by started updates.
     */
    fun filterStarted(enabled: Boolean = true): UpdatesRobot {
        client.executeAction("updates_filter", mapOf("type" to "started", "enabled" to enabled.toString()))
        return this
    }

    /**
     * Filter by bookmarked updates.
     */
    fun filterBookmarked(enabled: Boolean = true): UpdatesRobot {
        client.executeAction("updates_filter", mapOf("type" to "bookmarked", "enabled" to enabled.toString()))
        return this
    }

    /**
     * Clear all filters.
     */
    fun clearFilters(): UpdatesRobot {
        client.executeAction("updates_clear_filters")
        return this
    }

    /**
     * Open upcoming calendar screen.
     */
    fun openUpcoming(): UpdatesRobot {
        client.executeAction("updates_open_upcoming")
        return this
    }

    /**
     * Select an update by index and start reading.
     */
    fun readUpdate(index: Int): ReaderRobot {
        client.executeAction("updates_select", mapOf("index" to index))
        return ReaderRobot(client)
    }

    /**
     * Download an update by index.
     */
    fun downloadUpdate(index: Int): UpdatesRobot {
        client.executeAction("updates_download", mapOf("index" to index))
        return this
    }

    /**
     * Mark a single update as read by index.
     */
    fun markAsRead(index: Int): UpdatesRobot {
        client.executeAction("updates_mark_read", mapOf("index" to index))
        return this
    }

    /**
     * Get current update count.
     */
    fun getUpdateCount(): Int {
        val state = client.getState()
        return state.updateCount ?: 0
    }

    /**
     * Check if there are unread updates.
     */
    fun hasUnread(): Boolean {
        val state = client.getState()
        return (state.updateCount ?: 0) > 0
    }

    /**
     * Capture screenshot of the updates screen.
     */
    fun capture(name: String = "updates"): ScreenshotResult {
        return client.screenshot(name)
    }
}
