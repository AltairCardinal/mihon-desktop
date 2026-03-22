package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import mihon.desktop.source.LocalSourceReader
import mihon.desktop.source.LocalMangaEntry
import mihon.desktop.ui.browse.LocalChapterScreen
import mihon.desktop.ui.browse.LocalMangaBrowseScreen
import mihon.desktop.ui.browse.LocalSourceSettingsScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSourceNavigationContractTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun createZip(dest: File, entries: List<String>): File {
        ZipOutputStream(dest.outputStream()).use { zip ->
            for (name in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(10))
                zip.closeEntry()
            }
        }
        return dest
    }

    // ── Direct-open archive tests ────────────────────────────────────────────

    @Test
    fun `root-level archive entry has isFile true (triggers direct-open path)`() {
        val archive = createZip(File(tmpDir.toFile(), "OneShot.cbz"), listOf("001.jpg"))
        val entry = LocalMangaEntry(name = "OneShot", directory = archive)
        assertTrue(entry.directory.isFile, "Archive LocalMangaEntry.directory should be isFile")
    }

    @Test
    fun `discoverChapters on archive returns exactly one chapter whose file is the archive`() {
        val archive = createZip(File(tmpDir.toFile(), "OneShot.cbz"), listOf("001.jpg", "002.jpg"))
        val chapters = LocalSourceReader.discoverChapters(archive)
        assertEquals(1, chapters.size, "Archive should have exactly one chapter")
        assertEquals(archive.absolutePath, chapters[0].file.absolutePath)
    }

    @Test
    fun `directory manga entry has isFile false (triggers chapter list path)`() {
        val mangaDir = File(tmpDir.toFile(), "LongManga").also { it.mkdirs() }
        File(mangaDir, "Chapter 1").also { it.mkdirs() }
        val entry = LocalMangaEntry(name = "LongManga", directory = mangaDir)
        assertTrue(!entry.directory.isFile, "Directory LocalMangaEntry.directory should NOT be isFile")
    }

    @Test
    fun `LocalMangaBrowseScreen implements Screen`() {
        assertInstanceOf(Screen::class.java, LocalMangaBrowseScreen())
    }

    @Test
    fun `LocalChapterScreen implements Screen with path parameter`() {
        val screen = LocalChapterScreen(
            mangaDirPath = "/home/user/manga/One Piece",
            mangaName = "One Piece",
        )
        assertInstanceOf(Screen::class.java, screen)
        assertNotNull(screen)
    }

    @Test
    fun `LocalMangaBrowseScreen can be instantiated with no parameters`() {
        val screen = LocalMangaBrowseScreen()
        assertNotNull(screen)
    }

    @Test
    fun `LocalSourceSettingsScreen implements Screen`() {
        assertInstanceOf(Screen::class.java, LocalSourceSettingsScreen())
    }

    @Test
    fun `LocalSourceSettingsScreen can be instantiated with no parameters`() {
        val screen = LocalSourceSettingsScreen()
        assertNotNull(screen)
    }
}
