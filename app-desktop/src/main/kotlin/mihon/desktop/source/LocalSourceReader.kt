package mihon.desktop.source

import java.io.File
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

private val ARCHIVE_EXTENSIONS = setOf("zip", "cbz")

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
 *     Chapter 2.cbz    ← archive chapter
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
     * Returns all subdirectories of [rootDir] as manga entries, sorted alphabetically.
     */
    fun discoverManga(rootDir: File): List<LocalMangaEntry> =
        rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
            ?.map { LocalMangaEntry(name = it.name, directory = it) }
            ?: emptyList()

    /**
     * Returns chapters inside [mangaDir].
     *
     * A chapter can be:
     * - a subdirectory containing image files
     * - a `.cbz` or `.zip` archive file
     *
     * Results are sorted in natural order.
     */
    fun discoverChapters(mangaDir: File): List<LocalChapterEntry> {
        val entries = mangaDir.listFiles()
            ?.filter { f ->
                f.isDirectory || f.extension.lowercase() in ARCHIVE_EXTENSIONS
            }
            ?.sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
            ?.map { f ->
                val name = if (f.isFile) f.nameWithoutExtension else f.name
                LocalChapterEntry(name = name, file = f)
            }
            ?: emptyList()
        return entries
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
    fun readArchive(archive: File): List<LocalPage> {
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
     * Reads pages from a [chapter] entry, dispatching to [readDirectory] or [readArchive]
     * depending on the file type.
     */
    fun readChapter(chapter: LocalChapterEntry): List<LocalPage> =
        if (chapter.file.isDirectory) readDirectory(chapter.file)
        else readArchive(chapter.file)
}
