package mihon.desktop.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSourceReaderRecursiveTest {

    @TempDir
    lateinit var tmpDir: Path

    private val root: File get() = tmpDir.toFile()

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

    private fun createMangaDir(parent: File, name: String): File {
        val dir = File(parent, name).also { it.mkdirs() }
        val chapterDir = File(dir, "Chapter 1").also { it.mkdirs() }
        File(chapterDir, "001.jpg").writeBytes(ByteArray(10))
        return dir
    }

    // ─── flat structure ─────────────────────────────────────────────────────────

    @Test
    fun `flat structure returns manga at depth 1`() {
        createMangaDir(root, "Manga")

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertEquals(1, result.size)
        assertEquals("Manga", result[0].name)
    }

    @Test
    fun `flat structure includes root-level archives`() {
        createZip(File(root, "OnePiece.cbz"), listOf("001.jpg"))

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertEquals(1, result.size)
        assertEquals("OnePiece", result[0].name)
    }

    // ─── nested structures ──────────────────────────────────────────────────────

    @Test
    fun `finds manga 2 levels deep`() {
        val group = File(root, "Shounen").also { it.mkdirs() }
        createMangaDir(group, "Naruto")

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertEquals(1, result.size)
        assertEquals("Naruto", result[0].name)
    }

    @Test
    fun `finds manga 3 levels deep`() {
        val a = File(root, "A").also { it.mkdirs() }
        val b = File(a, "B").also { it.mkdirs() }
        createMangaDir(b, "DeepManga")

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertEquals(1, result.size)
        assertEquals("DeepManga", result[0].name)
    }

    @Test
    fun `maxDepth limits scanning depth`() {
        // Manga at depth 4 — should NOT be found with maxDepth=3
        val a = File(root, "A").also { it.mkdirs() }
        val b = File(a, "B").also { it.mkdirs() }
        val c = File(b, "C").also { it.mkdirs() }
        createMangaDir(c, "TooDeep")

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertTrue(result.isEmpty(), "Manga at depth 4 should not be found when maxDepth=3")
    }

    // ─── recursion stops at manga ───────────────────────────────────────────────

    @Test
    fun `does not recurse into manga directories`() {
        // Manga has chapter subdirs — those should NOT appear as separate manga
        val mangaDir = createMangaDir(root, "Manga")
        // Add a second chapter that also has images
        val ch2 = File(mangaDir, "Chapter 2").also { it.mkdirs() }
        File(ch2, "001.jpg").writeBytes(ByteArray(10))

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertEquals(1, result.size, "Should find Manga only, not Chapter 1 or Chapter 2")
        assertEquals("Manga", result[0].name)
    }

    // ─── archives at intermediate levels ────────────────────────────────────────

    @Test
    fun `directory with archive chapters is treated as manga`() {
        // Group/ contains standalone.cbz → discoverChapters treats it as a chapter
        // So "Group" is the manga, not "standalone"
        val group = File(root, "Group").also { it.mkdirs() }
        createZip(File(group, "standalone.cbz"), listOf("001.jpg"))

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertEquals(1, result.size)
        assertEquals("Group", result[0].name)
    }

    // ─── mixed and sorted ───────────────────────────────────────────────────────

    @Test
    fun `result is flat and sorted by natural order`() {
        // Manga at various depths, should all be in one flat list, sorted
        createMangaDir(root, "Zeta")
        val group = File(root, "group").also { it.mkdirs() }
        createMangaDir(group, "Alpha")
        createZip(File(root, "Middle.cbz"), listOf("001.jpg"))

        val names = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3).map { it.name }

        assertEquals(listOf("Alpha", "Middle", "Zeta"), names)
    }

    @Test
    fun `returns coverFile as null for deferred resolution`() {
        createMangaDir(root, "Manga")

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertEquals(1, result.size)
        assertEquals(null, result[0].coverFile, "coverFile should be null for deferred resolution")
    }

    // ─── no-image archives not added ────────────────────────────────────────────

    @Test
    fun `archives with no images are not added as manga`() {
        // Simulates a game/software download directory inside the manga root
        val gameDir = File(root, "tetris_codex_gpt5.4_1").also { it.mkdirs() }
        createZip(File(gameDir, "game_data.zip"), listOf("readme.txt", "data.bin"))

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertTrue(result.isEmpty(), "Game directory with no-image archives should not appear as manga")
    }

    @Test
    fun `root-level archive with no images is not added as manga`() {
        createZip(File(root, "game_archive.zip"), listOf("readme.txt"))

        val result = LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3)

        assertTrue(result.isEmpty(), "Root-level archive with no images should not appear as manga")
    }

    // ─── empty/invalid structures ───────────────────────────────────────────────

    @Test
    fun `empty root returns empty list`() {
        assertTrue(LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3).isEmpty())
    }

    @Test
    fun `nested empty directories return empty list`() {
        val a = File(root, "A").also { it.mkdirs() }
        File(a, "B").mkdirs()

        assertTrue(LocalSourceReader.discoverMangaRecursive(root, maxDepth = 3).isEmpty())
    }
}
