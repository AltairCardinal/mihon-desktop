package mihon.test.desktop.robot

import mihon.test.desktop.DesktopTestClient

/**
 * Robot for Download management screen interactions.
 */
class DownloadsRobot(private val client: DesktopTestClient) {

    private val baseUrl = client.baseUrl

    /**
     * Navigate to the Downloads screen.
     */
    fun open(): DownloadsRobot {
        client.navigate("DownloadsScreen")
        return this
    }

    /**
     * Pause all active downloads.
     */
    fun pauseAll(): DownloadsRobot {
        client.executeAction("downloads_pause_all")
        return this
    }

    /**
     * Resume all paused downloads.
     */
    fun resumeAll(): DownloadsRobot {
        client.executeAction("downloads_resume_all")
        return this
    }

    /**
     * Cancel a specific download by index.
     */
    fun cancelDownload(index: Int): DownloadsRobot {
        client.executeAction("downloads_cancel", mapOf("index" to index))
        return this
    }

    /**
     * Cancel all downloads in the queue.
     */
    fun cancelAll(): DownloadsRobot {
        client.executeAction("downloads_cancel_all")
        return this
    }

    /**
     * Clear all failed downloads.
     */
    fun clearErrors(): DownloadsRobot {
        client.executeAction("downloads_clear_errors")
        return this
    }

    /**
     * Retry all failed downloads.
     */
    fun retryErrors(): DownloadsRobot {
        client.executeAction("downloads_retry_errors")
        return this
    }

    /**
     * Move a download in the queue by index.
     */
    fun reorderDownload(fromIndex: Int, toIndex: Int): DownloadsRobot {
        client.executeAction("downloads_reorder", mapOf("from" to fromIndex, "to" to toIndex))
        return this
    }

    /**
     * Sort downloads in the queue.
     */
    fun sortQueue(by: String = "date_added"): DownloadsRobot {
        client.executeAction("downloads_sort", mapOf("by" to by))
        return this
    }

    /**
     * Reverse the download queue order.
     */
    fun reverseQueue(): DownloadsRobot {
        client.executeAction("downloads_reverse")
        return this
    }

    /**
     * Get current download queue size.
     */
    fun getQueueSize(): Int {
        val state = client.getState()
        return state.downloadQueueSize ?: 0
    }

    /**
     * Check if downloads are currently paused.
     */
    fun isPaused(): Boolean {
        val state = client.getState()
        return state.downloadsPaused ?: false
    }

}
