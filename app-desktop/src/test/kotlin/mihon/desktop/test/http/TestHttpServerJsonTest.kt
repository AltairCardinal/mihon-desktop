package mihon.desktop.test.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestHttpServerJsonTest {

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, String> {
        val method = Class.forName("mihon.desktop.test.http.TestHttpServerKt")
            .getDeclaredMethod("parseJsonBody", String::class.java)
        method.isAccessible = true
        return method.invoke(null, body) as Map<String, String>
    }

    @Test
    fun `json body parser preserves urls colons and commas`() {
        val parsed = parse(
            """
            {
              "chapterUrl": "https://example.com/read/1?page=2,extra",
              "chapterTitle": "Chapter 1: The Start, Part A",
              "mangaId": 42
            }
            """.trimIndent(),
        )

        assertEquals("https://example.com/read/1?page=2,extra", parsed["chapterUrl"])
        assertEquals("Chapter 1: The Start, Part A", parsed["chapterTitle"])
        assertEquals("42", parsed["mangaId"])
    }
}
