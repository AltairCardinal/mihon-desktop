package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceMangaDetailScreenTest {

    /**
     * Extensions commonly return getMangaDetails() without setting url/title —
     * those are already known from the catalogue listing.
     * applyMangaDetails() must preserve them from the original.
     */
    @Test
    fun `applyMangaDetails preserves url from original when details omits it`() {
        val original = SManga.create().apply {
            url = "/manga/manhuagui-test"
            title = "Test Manga"
        }
        val details = SManga.create().apply {
            // url intentionally NOT set — this is the extension convention
            title = "Test Manga"
            author = "Author A"
            description = "A fine story"
        }

        val result = applyMangaDetails(original, details)

        assertEquals("/manga/manhuagui-test", result.url, "url must be preserved from original")
        assertEquals("Author A", result.author)
        assertEquals("A fine story", result.description)
    }

    @Test
    fun `applyMangaDetails preserves title from original when details omits it`() {
        val original = SManga.create().apply {
            url = "/manga/test"
            title = "Original Title"
        }
        val details = SManga.create().apply {
            // title intentionally NOT set
            description = "Details only"
        }

        val result = applyMangaDetails(original, details)

        assertEquals("Original Title", result.title)
        assertEquals("/manga/test", result.url)
    }

    @Test
    fun `applyMangaDetails keeps url and title from details when they are set`() {
        val original = SManga.create().apply {
            url = "/manga/old"
            title = "Old Title"
        }
        val details = SManga.create().apply {
            url = "/manga/old"
            title = "New Title From Details"
            description = "Updated"
        }

        val result = applyMangaDetails(original, details)

        assertEquals("/manga/old", result.url)
        assertEquals("New Title From Details", result.title)
    }
}
