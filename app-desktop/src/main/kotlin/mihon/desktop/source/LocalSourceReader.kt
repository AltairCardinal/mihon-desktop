package mihon.desktop.source

import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** A single page in a local chapter, backed either by a file or a zip entry. */
data class LocalPage(
    val name: String,
    val file: File? = null,
    val archiveEntry: String? = null,
)

/** A manga discovered in a local root directory. */
data class LocalMangaEntry(val name: String, val directory: File)

/** A chapter discovered inside a manga directory — either a sub-directory or an archive file. */
data class LocalChapterEntry(val name: String, val file: File)

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "avif", "jxl", "heif", "heic", "bmp",
)

private val ZIP_EXTENSIONS = setOf("zip", "cbz")
private val RAR_EXTENSIONS = setOf("rar", "cbr")
private val ARCHIVE_EXTENSIONS = ZIP_EXTENSIONS + RAR_EXTENSIONS

/**
 * Utilities for discovering and reading locally-stored manga.
 *
 * Expected directory layout:
 * ```
 * <rootDir>/
 *   MangaName/
 *     Chapter 1/       ← directory chapter
 *       001.jpg
 *       002.jpg
 *     Chapter 2.cbz    ← zip/cbz archive chapter
 *     Chapter 3.cbr    ← rar/cbr archive chapter
 *     cover.jpg
 * ```
 */
object LocalSourceReader {

    // ── Natural sort comparator ───────────────────────────────────────────────

    /**
     * Sorts strings so that embedded numbers are compared numerically.
     * e.g. "Chapter 2" < "Chapter 10".
     */
    private val naturalOrder: Comparator<String> = Comparator { a, b ->
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        val len = minOf(tokensA.size, tokensB.size)
        for (i in 0 until len) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            val cmp = if (ta.first().isDigit() && tb.first().isDigit()) {
                ta.toLong().compareTo(tb.toLong())
            } else {
                ta.compareTo(tb, ignoreCase = true)
            }
            if (cmp != 0) return@Comparator cmp
        }
        tokensA.size - tokensB.size
    }

    private fun tokenize(s: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        var lastWasDigit = false
        for (ch in s) {
            val isDigit = ch.isDigit()
            if (buf.isNotEmpty() && isDigit != lastWasDigit) {
                result += buf.toString()
                buf.clear()
            }
            buf.append(ch)
            lastWasDigit = isDigit
        }
        if (buf.isNotEmpty()) result += buf.toString()
        return result
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    /**
     * Returns manga entries in [rootDir].
     *
     * Each subdirectory is a multi-chapter manga; each archive file is treated as a
     * single-chapter manga (the archive is both the manga container and its one chapter).
     * Results are sorted in natural order.
     */
    fun discoverManga(rootDir: File): List<LocalMangaEntry> =
        rootDir.listFiles()
            ?.filter { it.isDirectory || it.extension.lowercase() in ARCHIVE_EXTENSIONS }
            ?.sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
            ?.map { f ->
                val name = if (f.isFile) f.nameWithoutExtension else f.name
                LocalMangaEntry(name = name, directory = f)
            }
            ?: emptyList()

    /**
     * Returns chapters inside [mangaDir].
     *
     * If [mangaDir] is itself an archive file (single-chapter manga discovered at the
     * root level), it is returned as the one and only chapter.
     *
     * Otherwise chapters are the contents of the directory:
     * - subdirectories containing image files
     * - `.cbz` / `.zip` archives
     * - `.cbr` / `.rar` archives
     *
     * Results are sorted in natural order.
     */
    fun discoverChapters(mangaDir: File): List<LocalChapterEntry> {
        // Single-archive manga — the file itself is the sole chapter
        if (mangaDir.isFile && mangaDir.extension.lowercase() in ARCHIVE_EXTENSIONS) {
            return listOf(LocalChapterEntry(name = mangaDir.nameWithoutExtension, file = mangaDir))
        }
        return mangaDir.listFiles()
            ?.filter { f -> f.isDirectory || f.extension.lowercase() in ARCHIVE_EXTENSIONS }
            ?.sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
            ?.map { f ->
                val name = if (f.isFile) f.nameWithoutExtension else f.name
                LocalChapterEntry(name = name, file = f)
            }
            ?: emptyList()
    }

    // ── Page reading ──────────────────────────────────────────────────────────

    /**
     * Returns all image files in [dir] as pages, sorted by filename in natural order.
     */
    fun readDirectory(dir: File): List<LocalPage> =
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
            ?.map { LocalPage(name = it.name, file = it) }
            ?: emptyList()

    /**
     * Returns all image entries in a zip/cbz [archive] as pages, sorted by entry name.
     */
    fun readZipArchive(archive: File): List<LocalPage> {
        if (!archive.exists()) return emptyList()
        return ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
                .sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
                .map { LocalPage(name = it.name, archiveEntry = it.name) }
                .toList()
        }
    }

    /**
     * Returns all image entries in a rar/cbr [archive] as pages, sorted by entry name.
     *
     * Uses sevenzipjbinding (7-Zip JNI) which supports RAR4, RAR5, and other formats.
     * Format detection is by content (magic bytes), not file extension.
     */
    fun readRarArchive(archive: File): List<LocalPage> {
        if (!archive.exists()) return emptyList()
        val raf = try { RandomAccessFile(archive, "r") } catch (_: Exception) { return emptyList() }
        // openInArchive returns null when the format cannot be detected
        val inArchive = try {
            SevenZip.openInArchive(null, RandomAccessFileInStream(raf))
        } catch (_: Exception) {
            raf.close()
            return emptyList()
        }
        if (inArchive == null) {
            raf.close()
            return emptyList()
        }
        return try {
            val result = mutableListOf<LocalPage>()
            for (i in 0 until inArchive.numberOfItems) {
                val isFolder = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                if (isFolder) continue
                val path = inArchive.getStringProperty(i, PropID.PATH) ?: continue
                if (path.substringAfterLast('.').lowercase() !in IMAGE_EXTENSIONS) continue
                result += LocalPage(name = path, archiveEntry = path)
            }
            result.sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { inArchive.close() } catch (_: Exception) {}
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /**
     * Dispatches to the appropriate reader based on file type.
     */
    fun readArchive(archive: File): List<LocalPage> =
        when (archive.extension.lowercase()) {
            in ZIP_EXTENSIONS -> readZipArchive(archive)
            in RAR_EXTENSIONS -> readRarArchive(archive)
            else -> emptyList()
        }

    /**
     * Reads pages from a [chapter] entry, dispatching by chapter file type.
     */
    fun readChapter(chapter: LocalChapterEntry): List<LocalPage> =
        if (chapter.file.isDirectory) readDirectory(chapter.file)
        else readArchive(chapter.file)
}
