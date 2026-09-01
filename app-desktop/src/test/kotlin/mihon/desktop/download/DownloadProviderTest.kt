package mihon.desktop.download

import mihon.domain.reader.content.DownloadArtifactLocator
import mihon.domain.reader.content.DownloadArtifactKind
import mihon.domain.reader.content.DownloadArtifactNamingPolicy
import mihon.domain.reader.content.DownloadChapterIdentity
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

    private fun jpegBytes() = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xD9.toByte(),
    )

    private fun heifBytes() = ByteArray(24).apply {
        this[3] = 24
        "ftyp".encodeToByteArray().copyInto(this, destinationOffset = 4)
        "heic".encodeToByteArray().copyInto(this, destinationOffset = 8)
        "heic".encodeToByteArray().copyInto(this, destinationOffset = 16)
    }

    private fun jxlBytes() = byteArrayOf(0xFF.toByte(), 0x0A)

    private fun jp2Bytes() = byteArrayOf(
        0x00,
        0x00,
        0x00,
        0x0C,
        0x6A,
        0x50,
        0x20,
        0x20,
        0x0D,
        0x0A,
        0x87.toByte(),
        0x0A,
    )

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
        File(dir, "001.jpg").writeBytes(jpegBytes())
        assertTrue(provider().isChapterDownloaded(1L, "Test", "Ch 1"))
    }

    @Test
    fun `isChapterDownloaded returns false when jpg file is not a readable image`() {
        val dir = provider().chapterDownloadDir(1L, "Test", "Ch 1")
        dir.mkdirs()
        File(dir, "001.jpg").writeText("<html>forbidden</html>")
        assertFalse(provider().isChapterDownloaded(1L, "Test", "Ch 1"))
    }

    @Test
    fun `getDownloadedPages returns sorted image files`() {
        val dir = provider().chapterDownloadDir(1L, "Test", "Ch 1")
        dir.mkdirs()
        File(dir, "003.jpg").writeBytes(jpegBytes())
        File(dir, "001.jpg").writeBytes(jpegBytes())
        File(dir, "002.jpg").writeBytes(jpegBytes())

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

    @Test
    fun `getDownloadedPages preserves upstream known extension and unknown signature policy`() {
        val dir = provider().chapterDownloadDir(1L, "Test", "Ch 1")
        dir.mkdirs()
        File(dir, "000.jpg").writeBytes(byteArrayOf())
        File(dir, "001.jpg").writeText("not an image")
        File(dir, "002.bin").writeBytes(jpegBytes())
        File(dir, "003.bin").writeBytes(heifBytes())
        File(dir, "004.bin").writeBytes(jxlBytes())
        File(dir, "005.jp2").writeText("not an image")
        File(dir, "006.jpx").writeBytes(byteArrayOf())
        File(dir, "007.bin").writeBytes(jp2Bytes())
        File(dir, "008.bin").writeBytes(byteArrayOf())

        val pages = provider().getDownloadedPages(1L, "Test", "Ch 1")

        assertEquals(
            listOf("000.jpg", "001.jpg", "002.bin", "003.bin", "004.bin", "005.jp2", "006.jpx", "007.bin"),
            pages.map(File::getName),
        )
    }

    @Test
    fun `current directory probe reports an empty old layout artifact without reading page content`() {
        val provider = provider()
        val directory = provider.chapterDownloadDir(1L, "Manga", "Chapter 1").also(File::mkdirs)
        val identity = DownloadChapterIdentity(
            sourceDisplayName = "Source",
            mangaTitle = "Manga",
            chapterName = "Chapter 1",
            scanlator = "Group",
            chapterUrl = "/chapter/1",
            disallowNonAsciiFilenames = false,
        )

        val match = DownloadArtifactLocator(provider.currentDirectoryArtifactProbe(1L)).locate(identity)

        assertEquals(directory.absolutePath, match?.opaqueLocation)
    }

    @Test
    fun `production artifact lookup checks every upstream candidate before old desktop fallback`() {
        val provider = provider()
        val identity = downloadIdentity()
        val candidates = DownloadArtifactNamingPolicy.chapterCandidates(identity)
        val canonicalManga = provider.canonicalMangaDownloadDir(identity).also(File::mkdirs)
        val oldDesktop = provider.chapterDownloadDir(42L, identity.mangaTitle, identity.chapterName)
            .also(File::mkdirs)
        val laterCanonical = canonicalManga.resolve(candidates[3].name).also { candidate ->
            candidate.parentFile.mkdirs()
            candidate.writeBytes(byteArrayOf(0x50, 0x4B))
        }

        val match = provider.downloadArtifactLookup(42L).locate(identity)

        assertEquals(laterCanonical.absolutePath, match?.opaqueLocation)
        assertEquals(candidates[3], match?.candidate)
        assertTrue(oldDesktop.isDirectory)
    }

    @Test
    fun `production artifact lookup reads all upstream directory and cbz candidates`() {
        val provider = provider()
        val identity = downloadIdentity()
        val canonicalManga = provider.canonicalMangaDownloadDir(identity).also(File::mkdirs)

        DownloadArtifactNamingPolicy.chapterCandidates(identity).distinct().forEach { candidate ->
            canonicalManga.deleteRecursively()
            canonicalManga.mkdirs()
            val artifact = canonicalManga.resolve(candidate.name)
            if (candidate.kind == mihon.domain.reader.content.DownloadArtifactKind.DIRECTORY) {
                artifact.mkdirs()
            } else {
                artifact.writeBytes(byteArrayOf(0x50, 0x4B))
            }

            val match = provider.downloadArtifactLookup(42L).locate(identity)

            assertEquals(candidate, match?.candidate)
            assertEquals(artifact.absolutePath, match?.opaqueLocation)
        }
    }

    @Test
    fun `production artifact lookup reads old desktop directory and sibling cbz after canonical candidates`() {
        val provider = provider()
        val identity = downloadIdentity()
        val oldDirectory = provider.chapterDownloadDir(42L, identity.mangaTitle, identity.chapterName)
            .also(File::mkdirs)

        assertEquals(oldDirectory.absolutePath, provider.downloadArtifactLookup(42L).locate(identity)?.opaqueLocation)

        oldDirectory.deleteRecursively()
        val oldCbz = File(oldDirectory.parentFile, "${oldDirectory.name}.cbz").also {
            it.writeBytes(byteArrayOf(0x50, 0x4B))
        }

        assertEquals(oldCbz.absolutePath, provider.downloadArtifactLookup(42L).locate(identity)?.opaqueLocation)
    }

    @Test
    fun `partial tmp candidates are finite canonical aliases followed by the historical desktop directory`() {
        val provider = provider()
        val identity = downloadIdentity()
        val expected = DownloadArtifactNamingPolicy.chapterCandidates(identity)
            .filter { it.kind == DownloadArtifactKind.DIRECTORY }
            .distinct()
            .map { provider.canonicalMangaDownloadDir(identity).resolve(it.name + DesktopDownloadProvider.TMP_DIR_SUFFIX) } +
            provider.chapterTmpDir(42L, identity.mangaTitle, identity.chapterName)

        assertEquals(
            expected.distinctBy(File::getAbsolutePath),
            provider.partialTmpDirectoryCandidates(42L, identity),
        )
        assertFalse(provider.isChapterDownloaded(42L, identity))
    }

    @Test
    fun `download page filename policy shares ordinal mapping and excludes staging names`() {
        assertEquals("001.jpg", DownloadPageFileNamingPolicy.committedFileName(0, "jpg"))
        assertEquals("100.png", DownloadPageFileNamingPolicy.committedFileName(99, ".PNG"))
        assertEquals("001.17.tmp", DownloadPageFileNamingPolicy.stagingFileName(0, 17L))
        assertEquals(0, DownloadPageFileNamingPolicy.readerOrdinal("001.jpg"))
        assertEquals(99, DownloadPageFileNamingPolicy.readerOrdinal("100.webp"))
        assertEquals(null, DownloadPageFileNamingPolicy.readerOrdinal("001.17.tmp"))
        assertEquals(null, DownloadPageFileNamingPolicy.readerOrdinal("000.jpg"))
        assertEquals(null, DownloadPageFileNamingPolicy.readerOrdinal("cover.jpg"))
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
        File(tmpDir, "001.jpg").writeBytes(jpegBytes())
        assertFalse(provider().hasMangaDownloads(sourceId = 1L, mangaTitle = "My Manga"))
    }

    @Test
    fun `hasMangaDownloads returns true when a chapter dir with images exists`() {
        val chDir = provider().chapterDownloadDir(1L, "My Manga", "Ch 1")
        chDir.mkdirs()
        File(chDir, "001.jpg").writeBytes(jpegBytes())
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

    private fun downloadIdentity() = DownloadChapterIdentity(
        sourceDisplayName = "Source 中文",
        mangaTitle = "Manga 中文",
        chapterName = "Chapter 1",
        scanlator = "Group",
        chapterUrl = "/chapter/1",
        disallowNonAsciiFilenames = false,
    )
}
