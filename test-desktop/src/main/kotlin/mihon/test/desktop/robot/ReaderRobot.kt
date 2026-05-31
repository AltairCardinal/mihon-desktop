package mihon.test.desktop.robot

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import mihon.test.desktop.DesktopTestClient

/**
 * Robot for controlling the manga reader.
 *
 * ## Usage
 * ```kotlin
 * val reader = client.reader
 * reader.nextPage()
 * reader.close()
 * ```
 */
class ReaderRobot(private val client: DesktopTestClient) {

    private val baseUrl = client.baseUrl

    /**
     * Navigate to the next page.
     */
    fun nextPage(): ReaderRobot {
        runBlocking {
            client.http.post("$baseUrl/test/reader/next_page")
        }
        return this
    }

    /**
     * Navigate to the previous page.
     */
    fun prevPage(): ReaderRobot {
        runBlocking {
            client.http.post("$baseUrl/test/reader/prev_page")
        }
        return this
    }

    /**
     * Go to a specific page.
     *
     * @param page Page number (0-indexed)
     */
    fun goToPage(page: Int): ReaderRobot {
        runBlocking {
            client.http.post("$baseUrl/test/reader/go_to_page") {
                setBody("""{"page":$page}""")
            }
        }
        return this
    }

    /**
     * Go to the first page.
     */
    fun goToFirstPage(): ReaderRobot = goToPage(0)

    /**
     * Go to the last page.
     */
    fun goToLastPage(): ReaderRobot {
        val state = getState()
        return goToPage(state.totalPages - 1)
    }

    /**
     * Go to next chapter.
     */
    fun nextChapter(): ReaderRobot {
        runBlocking {
            client.http.post("$baseUrl/test/reader/next_chapter")
        }
        return this
    }

    /**
     * Go to previous chapter.
     */
    fun prevChapter(): ReaderRobot {
        runBlocking {
            client.http.post("$baseUrl/test/reader/prev_chapter")
        }
        return this
    }

    /**
     * Close the reader and return to the previous screen.
     */
    fun close(): ReaderRobot {
        runBlocking {
            client.http.post("$baseUrl/test/reader/close")
        }
        return this
    }

    /**
     * Get current reader state.
     */
    fun getState(): ReaderStateResponse {
        return runBlocking {
            val response = client.http.get("$baseUrl/test/reader/state")
            val body = response.bodyAsText()
            ReaderStateResponse.fromJson(body)
        }
    }

    /**
     * Check if the reader is currently open.
     */
    fun isOpen(): Boolean = getState().isOpen

    /**
     * Get current page number (0-indexed).
     */
    fun currentPage(): Int = getState().currentPage

    /**
     * Get total number of pages.
     */
    fun totalPages(): Int = getState().totalPages

    /**
     * Get reading progress as a fraction (0.0 to 1.0).
     */
    fun progress(): Float = getState().progress
}

/**
 * Current reader state.
 */
data class ReaderStateResponse(
    val isOpen: Boolean,
    val currentPage: Int,
    val totalPages: Int,
    val currentChapterId: Long,
    val isWebtoon: Boolean,
    val mangaTitle: String,
    val chapterTitle: String,
    val hasNextChapter: Boolean,
    val hasPrevChapter: Boolean,
) {
    val progress: Float get() = if (totalPages > 0) (currentPage + 1).toFloat() / totalPages else 0f
    val hasNextPage: Boolean get() = currentPage < totalPages - 1
    val hasPrevPage: Boolean get() = currentPage > 0

    companion object {
        fun fromJson(json: String): ReaderStateResponse {
            val obj = mutableMapOf<String, Any>()
            val content = json.trim().removePrefix("{").removeSuffix("}")
            content.split(",").forEach { pair ->
                val parts = pair.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().removeSurrounding("\"").trim()
                    val value = parts[1].trim().removeSurrounding("\"").trim()
                    obj[key] = when (key) {
                        "isOpen", "isWebtoon", "hasNextChapter", "hasPrevChapter" -> value.toBoolean()
                        "currentPage", "totalPages", "currentChapterId" -> value.toLongOrNull() ?: 0L
                        else -> value
                    }
                }
            }
            return ReaderStateResponse(
                isOpen = obj["isOpen"] as? Boolean ?: false,
                currentPage = (obj["currentPage"] as? Number)?.toInt() ?: 0,
                totalPages = (obj["totalPages"] as? Number)?.toInt() ?: 0,
                currentChapterId = obj["currentChapterId"] as? Long ?: 0L,
                isWebtoon = obj["isWebtoon"] as? Boolean ?: false,
                mangaTitle = obj["mangaTitle"] as? String ?: "",
                chapterTitle = obj["chapterTitle"] as? String ?: "",
                hasNextChapter = obj["hasNextChapter"] as? Boolean ?: false,
                hasPrevChapter = obj["hasPrevChapter"] as? Boolean ?: false,
            )
        }
    }
}
