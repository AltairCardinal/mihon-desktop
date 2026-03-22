package mihon.desktop.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSourceReaderTest {

    @TempDir
    lateinit var tmpDir: Path

    private val root: File get() = tmpDir.toFile()

    // ─── helper ────────────────────────────────────────────────────────────────

    private fun createImage(dir: File, name: String): File =
        File(dir, name).also { it.writeBytes(ByteArray(10)) }

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

    // ─── discoverManga ─────────────────────────────────────────────────────────

    @Test
    fun `discoverManga returns subdirectories as manga entries`() {
        createZip(File(File(root, "MangaA").also { it.mkdirs() }, "ch.cbz"), listOf("001.jpg"))
        createZip(File(File(root, "MangaB").also { it.mkdirs() }, "ch.cbz"), listOf("001.jpg"))
        File(root, "not_a_manga.txt").createNewFile()

        val result = LocalSourceReader.discoverManga(root)

        assertEquals(2, result.size)
        val names = result.map { it.name }.toSet()
        assertTrue("MangaA" in names)
        assertTrue("MangaB" in names)
    }

    @Test
    fun `discoverManga returns empty list for empty directory`() {
        val result = LocalSourceReader.discoverManga(root)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `discoverManga is sorted alphabetically`() {
        for (name in listOf("Zorro", "Alpha", "Middle")) {
            createZip(File(File(root, name).also { it.mkdirs() }, "ch.cbz"), listOf("001.jpg"))
        }

        val names = LocalSourceReader.discoverManga(root).map { it.name }
        assertEquals(listOf("Alpha", "Middle", "Zorro"), names)
    }

    @Test
    fun `discoverManga excludes empty directories`() {
        File(root, "EmptyDir").mkdirs()
        val validDir = File(root, "ValidManga").also { it.mkdirs() }
        createZip(File(validDir, "ch01.cbz"), listOf("001.jpg"))

        val names = LocalSourceReader.discoverManga(root).map { it.name }

        assertTrue("ValidManga" in names)
        assertTrue("EmptyDir" !in names, "Empty directory should be excluded")
    }

    @Test
    fun `discoverManga excludes directories with only non-manga files`() {
        val justImages = File(root, "JustImages").also { it.mkdirs() }
        File(justImages, "photo.jpg").writeBytes(ByteArray(10))  // loose image, not a chapter

        val result = LocalSourceReader.discoverManga(root)

        assertTrue(result.isEmpty(), "Directory with only loose images but no chapter structure should be excluded")
    }

    @Test
    fun `discoverManga excludes directories whose chapter subdirs contain no images`() {
        // MangaDir has a subdirectory "Chapter 1" but it's empty — not actually readable
        val mangaDir = File(root, "FakeManga").also { it.mkdirs() }
        File(mangaDir, "Chapter 1").mkdirs()  // chapter dir exists but has no images

        val result = LocalSourceReader.discoverManga(root)

        assertTrue(result.isEmpty(), "Manga with chapter dirs that contain no images should be excluded")
    }

    @Test
    fun `discoverManga excludes directories whose archive chapters contain no images`() {
        // Simulates a game download directory: contains a zip but it has no images inside
        val gameDir = File(root, "tetris_codex_gpt5.4_1").also { it.mkdirs() }
        createZip(File(gameDir, "game_data.zip"), listOf("readme.txt", "data.bin"))

        val result = LocalSourceReader.discoverManga(root)

        assertTrue(result.isEmpty(), "Directory whose archive chapters contain no images should be excluded")
    }

    @Test
    fun `discoverManga includes directories whose archive chapters contain images`() {
        val mangaDir = File(root, "RealManga").also { it.mkdirs() }
        createZip(File(mangaDir, "Chapter1.cbz"), listOf("001.jpg", "002.jpg"))

        val result = LocalSourceReader.discoverManga(root)

        assertEquals(1, result.size)
        assertEquals("RealManga", result[0].name)
    }

    @Test
    fun `discoverManga excludes root-level archive containing no images`() {
        // A zip at root that has no image content should NOT appear as manga
        createZip(File(root, "game_data.zip"), listOf("readme.txt", "data.bin"))

        val result = LocalSourceReader.discoverManga(root)

        assertTrue(result.isEmpty(), "Root-level archive with no images should be excluded")
    }

    @Test
    fun `discoverManga includes root-level archive containing images`() {
        createZip(File(root, "OnePiece.cbz"), listOf("001.jpg", "002.jpg"))

        val result = LocalSourceReader.discoverManga(root)

        assertEquals(1, result.size)
        assertEquals("OnePiece", result[0].name)
    }

    @Test
    fun `discoverManga includes directories whose chapter subdirs have images`() {
        val mangaDir = File(root, "RealManga").also { it.mkdirs() }
        val chapterDir = File(mangaDir, "Chapter 1").also { it.mkdirs() }
        File(chapterDir, "001.jpg").writeBytes(ByteArray(10))

        val names = LocalSourceReader.discoverManga(root).map { it.name }

        assertTrue("RealManga" in names)
    }

    @Test
    fun `discoverManga includes archive files in root as single-chapter manga`() {
        createZip(File(root, "OnePiece.cbz"), listOf("001.jpg"))
        createZip(File(root, "Naruto.zip"), listOf("001.jpg"))
        File(root, "readme.txt").createNewFile()  // excluded

        val result = LocalSourceReader.discoverManga(root)

        assertEquals(2, result.size)
        val names = result.map { it.name }.toSet()
        assertTrue("OnePiece" in names, "cbz should appear as manga")
        assertTrue("Naruto" in names, "zip should appear as manga")
    }

    @Test
    fun `discoverManga mixes directories and archives sorted together`() {
        val batmanDir = File(root, "Batman").also { it.mkdirs() }
        createZip(File(batmanDir, "ch.cbz"), listOf("001.jpg"))
        createZip(File(root, "Akira.cbz"), listOf("001.jpg"))

        val names = LocalSourceReader.discoverManga(root).map { it.name }

        assertEquals(listOf("Akira", "Batman"), names)
    }

    @Test
    fun `discoverChapters returns single chapter when manga is an archive file`() {
        val cbzFile = File(root, "OnePiece.cbz").also { createZip(it, listOf("001.jpg")) }

        val chapters = LocalSourceReader.discoverChapters(cbzFile)

        assertEquals(1, chapters.size)
        assertEquals("OnePiece", chapters[0].name)
        assertEquals(cbzFile, chapters[0].file)
    }

    // ─── discoverChapters ──────────────────────────────────────────────────────

    @Test
    fun `discoverChapters finds subdirectories as chapters`() {
        val mangaDir = File(root, "Manga").also { it.mkdirs() }
        File(mangaDir, "Chapter 1").mkdirs()
        File(mangaDir, "Chapter 2").mkdirs()

        val result = LocalSourceReader.discoverChapters(mangaDir)

        assertEquals(2, result.size)
        val names = result.map { it.name }.toSet()
        assertTrue("Chapter 1" in names)
        assertTrue("Chapter 2" in names)
    }

    @Test
    fun `discoverChapters finds cbz and zip archives as chapters`() {
        val mangaDir = File(root, "Manga").also { it.mkdirs() }
        createZip(File(mangaDir, "ch01.cbz"), listOf("001.jpg"))
        createZip(File(mangaDir, "ch02.zip"), listOf("001.jpg"))
        File(mangaDir, "cover.jpg").createNewFile()  // should be excluded

        val result = LocalSourceReader.discoverChapters(mangaDir)

        assertEquals(2, result.size)
        val names = result.map { it.name }.toSet()
        assertTrue("ch01" in names || "ch01.cbz" in names)
        assertTrue("ch02" in names || "ch02.zip" in names)
    }

    @Test
    fun `discoverChapters uses natural sort order`() {
        val mangaDir = File(root, "Manga").also { it.mkdirs() }
        File(mangaDir, "Chapter 10").mkdirs()
        File(mangaDir, "Chapter 2").mkdirs()
        File(mangaDir, "Chapter 1").mkdirs()

        val names = LocalSourceReader.discoverChapters(mangaDir).map { it.name }

        // Natural sort: Chapter 1, Chapter 2, Chapter 10
        assertEquals("Chapter 1", names[0])
        assertEquals("Chapter 2", names[1])
        assertEquals("Chapter 10", names[2])
    }

    // ─── readDirectory ─────────────────────────────────────────────────────────

    @Test
    fun `readDirectory returns image files sorted by name`() {
        val chapterDir = File(root, "ch1").also { it.mkdirs() }
        createImage(chapterDir, "003.jpg")
        createImage(chapterDir, "001.png")
        createImage(chapterDir, "002.webp")
        chapterDir.resolve("notes.txt").createNewFile()  // excluded

        val pages = LocalSourceReader.readDirectory(chapterDir)

        assertEquals(3, pages.size)
        assertEquals("001.png", pages[0].name)
        assertEquals("002.webp", pages[1].name)
        assertEquals("003.jpg", pages[2].name)
    }

    @Test
    fun `readDirectory excludes non-image files`() {
        val chapterDir = File(root, "ch1").also { it.mkdirs() }
        createImage(chapterDir, "001.jpg")
        chapterDir.resolve("ComicInfo.xml").createNewFile()
        chapterDir.resolve("thumb.db").createNewFile()

        val pages = LocalSourceReader.readDirectory(chapterDir)
        assertEquals(1, pages.size)
        assertEquals("001.jpg", pages[0].name)
    }

    @Test
    fun `readDirectory returns empty list for directory with no images`() {
        val emptyDir = File(root, "empty").also { it.mkdirs() }
        assertTrue(LocalSourceReader.readDirectory(emptyDir).isEmpty())
    }

    // ─── readArchive ───────────────────────────────────────────────────────────

    @Test
    fun `readArchive returns entries from zip sorted by name`() {
        val cbzFile = File(root, "chapter.cbz")
        createZip(cbzFile, listOf("003.jpg", "001.jpg", "002.png", "metadata.xml"))

        val pages = LocalSourceReader.readArchive(cbzFile)

        assertEquals(3, pages.size, "Should exclude non-image metadata.xml")
        assertEquals("001.jpg", pages[0].name)
        assertEquals("002.png", pages[1].name)
        assertEquals("003.jpg", pages[2].name)
    }

    @Test
    fun `readArchive returns empty list for archive with no images`() {
        val emptyZip = File(root, "empty.cbz")
        createZip(emptyZip, listOf("metadata.xml"))

        assertTrue(LocalSourceReader.readArchive(emptyZip).isEmpty())
    }

    @Test
    fun `readArchive sets archiveEntry on pages`() {
        val cbzFile = File(root, "ch.cbz")
        createZip(cbzFile, listOf("001.jpg"))

        val pages = LocalSourceReader.readArchive(cbzFile)
        assertEquals("001.jpg", pages[0].archiveEntry)
    }

    // ─── RAR / CBR support ─────────────────────────────────────────────────────

    @Test
    fun `discoverChapters includes cbr files as chapters`() {
        val mangaDir = File(root, "Manga").also { it.mkdirs() }
        File(mangaDir, "ch01.cbr").createNewFile()
        File(mangaDir, "ch02.cbz").createNewFile()

        val names = LocalSourceReader.discoverChapters(mangaDir).map { it.name }
        assertTrue("ch01" in names, "cbr should be discovered")
        assertTrue("ch02" in names, "cbz should be discovered")
    }

    @Test
    fun `discoverChapters includes rar files as chapters`() {
        val mangaDir = File(root, "Manga").also { it.mkdirs() }
        File(mangaDir, "ch01.rar").createNewFile()

        val names = LocalSourceReader.discoverChapters(mangaDir).map { it.name }
        assertTrue("ch01" in names, "rar should be discovered")
    }

    @Test
    fun `readZipArchive works for cbz (regression)`() {
        val cbzFile = File(root, "chapter.cbz")
        createZip(cbzFile, listOf("001.jpg", "002.jpg"))

        val pages = LocalSourceReader.readArchive(cbzFile)
        assertEquals(2, pages.size)
    }

    // ─── RAR5 / auto-format detection ─────────────────────────────────────────

    /**
     * sevenzipjbinding opens archives by content (null format = auto-detect),
     * so a ZIP file named .rar should be readable as a zip, not fail silently.
     * This test is RED with junrar (returns empty) and GREEN with sevenzipjbinding.
     */
    @Test
    fun `readRarArchive auto-detects format from content not extension`() {
        val fakeRar = File(root, "chapter.rar")
        createZip(fakeRar, listOf("001.jpg", "002.jpg", "notes.xml"))

        val pages = LocalSourceReader.readRarArchive(fakeRar)

        // sevenzipjbinding detects ZIP content → reads images; junrar returns []
        assertEquals(2, pages.size, "Should read images from auto-detected archive format")
        assertTrue(pages.any { it.name.endsWith("001.jpg") })
        assertTrue(pages.any { it.name.endsWith("002.jpg") })
    }

    @Test
    fun `readRarArchive sets archiveEntry on pages from auto-detected archive`() {
        val fakeRar = File(root, "ch.cbr")
        createZip(fakeRar, listOf("001.jpg"))

        val pages = LocalSourceReader.readRarArchive(fakeRar)
        assertEquals(1, pages.size)
        assertNotNull(pages[0].archiveEntry)
    }

    // ─── resolveCover ──────────────────────────────────────────────────────────

    @Test
    fun `resolveCover returns cover jpg from directory manga`() {
        val mangaDir = File(root, "Manga").also { it.mkdirs() }
        val coverFile = File(mangaDir, "cover.jpg").also { it.writeBytes(ByteArray(10)) }

        val result = LocalSourceReader.resolveCover(LocalMangaEntry("Manga", mangaDir))

        assertEquals(coverFile, result)
    }

    @Test
    fun `resolveCover returns null when no cover file in directory`() {
        val mangaDir = File(root, "Manga").also { it.mkdirs() }
        File(mangaDir, "001.jpg").writeBytes(ByteArray(10))

        assertNull(LocalSourceReader.resolveCover(LocalMangaEntry("Manga", mangaDir)))
    }

    @Test
    fun `resolveCover returns sidecar image for archive manga`() {
        val cbzFile = File(root, "OnePiece.cbz").also { createZip(it, listOf("001.jpg")) }
        val sidecar = File(root, "OnePiece.jpg").also { it.writeBytes(ByteArray(10)) }

        val result = LocalSourceReader.resolveCover(LocalMangaEntry("OnePiece", cbzFile))

        assertEquals(sidecar, result)
    }

    @Test
    fun `resolveCover extracts first image from zip when no sidecar`() {
        val cbzFile = File(root, "OnePiece.cbz").also { createZip(it, listOf("001.jpg", "002.jpg")) }

        val result = LocalSourceReader.resolveCover(LocalMangaEntry("OnePiece", cbzFile))

        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(result.length() > 0)
    }

    @Test
    fun `resolveCover returns null for empty zip with no sidecar`() {
        val emptyZip = File(root, "Empty.cbz").also { createZip(it, emptyList()) }

        assertNull(LocalSourceReader.resolveCover(LocalMangaEntry("Empty", emptyZip)))
    }

    @Test
    fun `discoverManga populates coverFile for directory manga with cover`() {
        val mangaDir = File(root, "Manga").also { it.mkdirs() }
        File(mangaDir, "cover.jpg").writeBytes(ByteArray(10))
        createZip(File(mangaDir, "ch.cbz"), listOf("001.jpg"))  // need a chapter to show up

        val result = LocalSourceReader.discoverManga(root)

        assertNotNull(result.single().coverFile)
    }

    @Test
    fun `discoverManga populates coverFile for archive manga via sidecar`() {
        createZip(File(root, "OnePiece.cbz"), listOf("001.jpg"))
        File(root, "OnePiece.jpg").writeBytes(ByteArray(10))

        val result = LocalSourceReader.discoverManga(root)

        assertNotNull(result.single().coverFile)
    }

    // ─── LocalPage data class ──────────────────────────────────────────────────

    @Test
    fun `LocalPage can hold file reference`() {
        val f = File(root, "001.jpg")
        val page = LocalPage(name = "001.jpg", file = f)
        assertEquals(f, page.file)
        assertEquals(null, page.archiveEntry)
    }

    @Test
    fun `LocalPage can hold archive entry reference`() {
        val page = LocalPage(name = "001.jpg", archiveEntry = "001.jpg")
        assertEquals(null, page.file)
        assertEquals("001.jpg", page.archiveEntry)
    }
}
