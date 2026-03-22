package mihon.desktop.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** RED — DesktopDownloadProvider does not exist yet. */
class DownloadProviderTest {

    @TempDir
    lateinit var tempDir: File

    private fun provider() = DesktopDownloadProvider(baseDir = tempDir)

    @Test
    fun `chapter download dir uses sourceId mangaTitle chapterName`() {
        val dir = provider().chapterDownloadDir(
            sourceId = 42L,
            mangaTitle = "My Manga",
            chapterName = "Chapter 001",
        )
        assertEquals("42", dir.parentFile.parentFile.name)
        assertEquals("My Manga", dir.parentFile.name)
        assertEquals("Chapter 001", dir.name)
    }

    @Test
    fun `isChapterDownloaded returns false when dir missing`() {
        assertFalse(
            provider().isChapterDownloaded(
                sourceId = 1L,
                mangaTitle = "Test",
                chapterName = "Ch 1",
            ),
        )
    }

    @Test
    fun `isChapterDownloaded returns false when dir exists but has no images`() {
        val dir = provider().chapterDownloadDir(1L, "Test", "Ch 1")
        dir.mkdirs()
        assertFalse(provider().isChapterDownloaded(1L, "Test", "Ch 1"))
    }

    @Test
    fun `isChapterDownloaded returns true when dir has jpg files`() {
        val dir = provider().chapterDownloadDir(1L, "Test", "Ch 1")
        dir.mkdirs()
        File(dir, "001.jpg").writeBytes(ByteArray(10))
        assertTrue(provider().isChapterDownloaded(1L, "Test", "Ch 1"))
    }

    @Test
    fun `getDownloadedPages returns sorted image files`() {
        val dir = provider().chapterDownloadDir(1L, "Test", "Ch 1")
        dir.mkdirs()
        File(dir, "003.jpg").writeBytes(ByteArray(10))
        File(dir, "001.jpg").writeBytes(ByteArray(10))
        File(dir, "002.jpg").writeBytes(ByteArray(10))

        val pages = provider().getDownloadedPages(1L, "Test", "Ch 1")
        assertEquals(3, pages.size)
        assertEquals("001.jpg", pages[0].name)
        assertEquals("002.jpg", pages[1].name)
        assertEquals("003.jpg", pages[2].name)
    }

    @Test
    fun `getDownloadedPages returns empty list when not downloaded`() {
        val pages = provider().getDownloadedPages(99L, "None", "Ch 0")
        assertTrue(pages.isEmpty())
    }

    // ── hasMangaDownloads ─────────────────────────────────────────────────────

    @Test
    fun `hasMangaDownloads returns false when manga dir does not exist`() {
        assertFalse(provider().hasMangaDownloads(sourceId = 1L, mangaTitle = "Ghost"))
    }

    @Test
    fun `hasMangaDownloads returns false when manga dir exists but has no chapter subdirs`() {
        val mangaDir = File(tempDir, "1/My Manga")
        mangaDir.mkdirs()
        assertFalse(provider().hasMangaDownloads(sourceId = 1L, mangaTitle = "My Manga"))
    }

    @Test
    fun `hasMangaDownloads returns false when only tmp dirs exist`() {
        val tmpDir = provider().chapterTmpDir(1L, "My Manga", "Ch 1")
        tmpDir.mkdirs()
        File(tmpDir, "001.jpg").writeBytes(ByteArray(10))
        assertFalse(provider().hasMangaDownloads(sourceId = 1L, mangaTitle = "My Manga"))
    }

    @Test
    fun `hasMangaDownloads returns true when a chapter dir with images exists`() {
        val chDir = provider().chapterDownloadDir(1L, "My Manga", "Ch 1")
        chDir.mkdirs()
        File(chDir, "001.jpg").writeBytes(ByteArray(10))
        assertTrue(provider().hasMangaDownloads(sourceId = 1L, mangaTitle = "My Manga"))
    }

    @Test
    fun `sanitize removes illegal filename chars`() {
        val dir = provider().chapterDownloadDir(1L, "Manga: The?Series*", "Ch 1/Part 2")
        // Should not throw and path components should have illegal chars removed
        val mangaName = dir.parentFile.name
        assertFalse(mangaName.contains(':'))
        assertFalse(mangaName.contains('?'))
        assertFalse(mangaName.contains('*'))
        val chapterName = dir.name
        assertFalse(chapterName.contains('/'))
    }
}
