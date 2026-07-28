package mihon.test.desktop.robot

import mihon.test.desktop.DesktopTestClient

/**
 * Robot for History tab interactions.
 */
class HistoryRobot(private val client: DesktopTestClient) {

    private val baseUrl = client.baseUrl

    /**
     * Navigate to the History tab.
     */
    fun open(): HistoryRobot {
        client.navigate("HistoryTab")
        return this
    }

    /**
     * Search through history.
     */
    fun search(query: String): HistoryRobot {
        client.executeAction("history_search", mapOf("query" to query))
        return this
    }

    /**
     * Clear the search query.
     */
    fun clearSearch(): HistoryRobot {
        client.executeAction("history_search", mapOf("query" to ""))
        return this
    }

    /**
     * Clear all history.
     */
    fun clearAll(): HistoryRobot {
        client.executeAction("history_clear_all")
        return this
    }

    /**
     * Remove a single history entry by index.
     */
    fun removeEntry(index: Int): HistoryRobot {
        client.executeAction("history_remove", mapOf("index" to index))
        return this
    }

    /**
     * Select a history entry by index and continue reading.
     */
    fun selectEntry(index: Int): ReaderRobot {
        client.executeAction("history_select", mapOf("index" to index))
        return ReaderRobot(client)
    }

    /**
     * Get current history count.
     */
    fun getHistoryCount(): Int {
        val state = client.getState()
        return state.historyCount ?: 0
    }

    /**
     * Check if history is empty.
     */
    fun isEmpty(): Boolean {
        return getHistoryCount() == 0
    }

}
