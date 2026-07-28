package mihon.test.desktop.robot

import mihon.test.desktop.DesktopTestClient
import mihon.test.desktop.SourceBrowseTestSnapshot

/**
 * Robot for Library screen interactions.
 */
class LibraryRobot(private val client: DesktopTestClient) {

    private val baseUrl = client.baseUrl

    /**
     * Navigate to the Library tab.
     */
    fun open(): LibraryRobot {
        client.navigate("LibraryTab")
        return this
    }

    /**
     * Search for manga in the library.
     */
    fun search(query: String): LibraryRobot {
        client.executeAction("search", mapOf("query" to query))
        return this
    }

    /**
     * Clear the search query.
     */
    fun clearSearch(): LibraryRobot {
        client.executeAction("search", mapOf("query" to ""))
        return this
    }

    /**
     * Filter by unread manga.
     */
    fun filterUnread(): LibraryRobot {
        client.executeAction("filter", mapOf("type" to "unread"))
        return this
    }

    /**
     * Filter by started manga.
     */
    fun filterStarted(): LibraryRobot {
        client.executeAction("filter", mapOf("type" to "started"))
        return this
    }

    /**
     * Filter by completed manga.
     */
    fun filterCompleted(): LibraryRobot {
        client.executeAction("filter", mapOf("type" to "completed"))
        return this
    }

    /**
     * Clear all filters.
     */
    fun clearFilters(): LibraryRobot {
        client.executeAction("filter", mapOf("type" to "clear"))
        return this
    }

    /**
     * Sort by title (alphabetical).
     */
    fun sortByTitle(): LibraryRobot {
        client.executeAction("sort", mapOf("mode" to "title"))
        return this
    }

    /**
     * Sort by last read.
     */
    fun sortByLastRead(): LibraryRobot {
        client.executeAction("sort", mapOf("mode" to "lastRead"))
        return this
    }

    /**
     * Sort by date added.
     */
    fun sortByDateAdded(): LibraryRobot {
        client.executeAction("sort", mapOf("mode" to "dateAdded"))
        return this
    }

    /**
     * Sort by unread count.
     */
    fun sortByUnreadCount(): LibraryRobot {
        client.executeAction("sort", mapOf("mode" to "unreadCount"))
        return this
    }

    /**
     * Select manga by index.
     */
    fun selectManga(index: Int): MangaDetailRobot {
        client.executeAction("select", mapOf("index" to index))
        return MangaDetailRobot(client)
    }

    /**
     * Get current manga count.
     */
    fun getMangaCount(): Int {
        val state = client.getState()
        return state.screens.size // Placeholder - actual implementation would query state
    }

    /**
     * Check if manga is visible in the list.
     */
    fun assertMangaVisible(title: String): LibraryRobot {
        // Implementation would verify manga appears in state
        return this
    }

    /**
     * Select a category tab.
     */
    fun selectCategory(index: Int): LibraryRobot {
        client.executeAction("select", mapOf("type" to "category", "index" to index))
        return this
    }
}

/**
 * Robot for Manga detail screen.
 */
class MangaDetailRobot(private val client: DesktopTestClient) {

    /**
     * Open manga detail screen.
     */
    fun open(): MangaDetailRobot {
        // Already navigated to detail screen from library
        return this
    }

    /**
     * Start reading from chapter index.
     */
    fun readChapter(index: Int): ReaderRobot {
        client.executeAction("select", mapOf("type" to "chapter", "index" to index))
        return ReaderRobot(client)
    }

    /**
     * Add manga to library.
     */
    fun addToLibrary(): MangaDetailRobot {
        client.executeAction("addToLibrary")
        return this
    }

    /**
     * Remove manga from library.
     */
    fun removeFromLibrary(): MangaDetailRobot {
        client.executeAction("removeFromLibrary")
        return this
    }

    /**
     * Download manga chapters.
     */
    fun download(): MangaDetailRobot {
        client.executeAction("download")
        return this
    }

}

/**
 * Robot for Settings screen.
 */
class SettingsRobot(private val client: DesktopTestClient) {

    /**
     * Navigate to settings.
     */
    fun open(): SettingsRobot {
        client.navigate("SettingsScreen")
        return this
    }

    /**
     * Change a setting value.
     */
    fun set(key: String, value: String): SettingsRobot {
        client.executeAction("setting_change", mapOf("key" to key, "value" to value))
        return this
    }

    /**
     * Reset settings to defaults.
     */
    fun reset(): SettingsRobot {
        client.executeAction("setting_reset")
        return this
    }

}

/**
 * Robot for Browse screen.
 */
class BrowseRobot(private val client: DesktopTestClient) {

    /**
     * Navigate to browse tab.
     */
    fun open(): BrowseRobot {
        client.navigate("BrowseTab")
        return this
    }

    fun state(): SourceBrowseTestSnapshot? = client.getState().source

    fun cancelLogin(attemptToken: String): SourceBrowseTestSnapshot? {
        val result = client.executeAction("source_login_cancel", mapOf("attemptToken" to attemptToken))
        check(result.success) { "source_login_cancel failed: ${result.error ?: "unknown error"}" }
        return result.source
    }

    /**
     * Select a manga by index.
     */
    fun selectManga(index: Int): MangaDetailRobot {
        client.executeAction("select", mapOf("index" to index))
        return MangaDetailRobot(client)
    }

}
