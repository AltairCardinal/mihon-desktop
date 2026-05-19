package mihon.test.desktop.data

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import mihon.test.desktop.DesktopTestClient

/**
 * Client for managing test data.
 */
class TestDataClient(private val client: DesktopTestClient) {

    private val baseUrl = client.baseUrl

    /**
     * Add a test manga.
     */
    fun addManga(manga: TestManga): Long {
        runBlocking {
            client.http.post("$baseUrl/test/data/manga") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "operation": "add",
                        "data": {
                            "title": "${manga.title}",
                            "url": "${manga.url}",
                            "author": "${manga.author}",
                            "description": "${manga.description}"
                        }
                    }
                    """.trimIndent(),
                )
            }
        }

        // Parse response to get ID
        return 1L // Placeholder
    }

    /**
     * Add multiple test manga.
     */
    fun addMangaList(mangaList: List<TestManga>) {
        mangaList.forEach { addManga(it) }
    }

    /**
     * Delete all manga.
     */
    fun deleteAllManga() {
        runBlocking {
            client.http.post("$baseUrl/test/data/manga") {
                contentType(ContentType.Application.Json)
                setBody("""{"operation": "delete_all"}""")
            }
        }
    }

    /**
     * Create a category.
     */
    fun createCategory(name: String): Long {
        runBlocking {
            client.http.post("$baseUrl/test/data/category") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "operation": "create",
                        "name": "$name"
                    }
                    """.trimIndent(),
                )
            }
        }

        return 1L // Placeholder
    }

    /**
     * Delete all categories.
     */
    fun deleteAllCategories() {
        runBlocking {
            client.http.post("$baseUrl/test/data/category") {
                contentType(ContentType.Application.Json)
                setBody("""{"operation": "delete_all"}""")
            }
        }
    }

    /**
     * Set a setting value.
     */
    fun setSetting(key: String, value: String) {
        runBlocking {
            client.http.post("$baseUrl/test/data/setting") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "operation": "set",
                        "key": "$key",
                        "value": "$value"
                    }
                    """.trimIndent(),
                )
            }
        }
    }

    /**
     * Reset all settings.
     */
    fun resetSettings() {
        runBlocking {
            client.http.post("$baseUrl/test/data/setting") {
                contentType(ContentType.Application.Json)
                setBody("""{"operation": "reset"}""")
            }
        }
    }

    /**
     * Clear all test data.
     */
    fun clearAll() {
        deleteAllManga()
        deleteAllCategories()
        resetSettings()
    }

    /**
     * Setup standard test data.
     */
    fun setupFixtures() {
        // Create categories
        createCategory("Action")
        createCategory("Comedy")
        createCategory("Drama")

        // Add test manga
        addManga(
            TestManga(
                title = "Test Manga One",
                url = "https://test.example.com/manga/1",
                author = "Test Author",
                description = "A test manga",
            ),
        )

        addManga(
            TestManga(
                title = "Test Manga Two",
                url = "https://test.example.com/manga/2",
                author = "Test Author 2",
                description = "Another test manga",
            ),
        )
    }
}

/**
 * Test manga data.
 */
data class TestManga(
    val title: String,
    val url: String,
    val author: String = "Test Author",
    val description: String = "",
    val thumbnailUrl: String = "",
)

/**
 * Test chapter data.
 */
data class TestChapter(
    val name: String,
    val url: String,
    val scanlator: String = "Test Group",
    val read: Boolean = false,
)

/**
 * Test category data.
 */
data class TestCategory(
    val name: String,
)
