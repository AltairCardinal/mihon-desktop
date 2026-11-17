package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceMangaDetailsMergeTest {

    @Test
    fun `canonical URL projection and its copy preserve request identity without mutating extension`() {
        val original = SManga.create().apply {
            url = "/work?id=1"
            title = "Original"
        }
        val remote = SManga.create().apply {
            url = "/work"
            title = "Remote"
        }
        val merged = mergeSourceMangaDetails(original, remote)
        assertEquals(original.url, merged.url)
        assertEquals(original.url, merged.copy().url)
        assertEquals("/work", remote.url)
        assertEquals("Remote", merged.title)
    }

    /**
     * Extensions commonly return getMangaDetails() without setting url/title;
     * those are already known from the catalogue listing.
     */
    @Test
    fun `merge preserves url from original when details omits it`() {
        val original = SManga.create().apply {
            url = "/manga/manhuagui-test"
            title = "Test Manga"
        }
        val details = SManga.create().apply {
            title = "Test Manga"
            author = "Author A"
            description = "A fine story"
        }

        val result = mergeSourceMangaDetails(original, details)

        assertEquals("/manga/manhuagui-test", result.url)
        assertEquals("Author A", result.author)
        assertEquals("A fine story", result.description)
    }

    @Test
    fun `merge preserves title from original when details omits it`() {
        val original = SManga.create().apply {
            url = "/manga/test"
            title = "Original Title"
        }
        val details = SManga.create().apply {
            description = "Details only"
        }

        val result = mergeSourceMangaDetails(original, details)

        assertEquals("Original Title", result.title)
        assertEquals("/manga/test", result.url)
    }

    @Test
    fun `merge keeps url and title from details when they are set`() {
        val original = SManga.create().apply {
            url = "/manga/old"
            title = "Old Title"
        }
        val details = SManga.create().apply {
            url = "/manga/old"
            title = "New Title From Details"
            description = "Updated"
        }

        val result = mergeSourceMangaDetails(original, details)

        assertEquals("/manga/old", result.url)
        assertEquals("New Title From Details", result.title)
    }
}
