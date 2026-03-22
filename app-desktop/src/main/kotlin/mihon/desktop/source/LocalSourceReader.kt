package mihon.desktop.source

import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** A single page in a local chapter, backed either by a file or a zip entry. */
data class LocalPage(
    val name: String,
    val file: File? = null,
    val archiveEntry: String? = null,
)

/**
 * A manga discovered in a local root directory.
 *
 * [directory] points to either a directory (multi-chapter) or an archive file
 * (single-chapter). [coverFile] is the resolved cover image, or null if unavailable.
 */
data class LocalMangaEntry(
    val name: String,
    val directory: File,
    val coverFile: File? = null,
)

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
     * Cover images are resolved eagerly (runs on IO thread via discoverManga callers).
     * Results are sorted in natural order.
     */
    fun discoverManga(rootDir: File): List<LocalMangaEntry> =
        rootDir.listFiles()
            ?.filter { f ->
                when {
                    f.extension.lowercase() in ARCHIVE_EXTENSIONS -> archiveHasImages(f)
                    f.isDirectory -> discoverChapters(f).any { chapterHasContent(it) }
                    else -> false
                }
            }
            ?.sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
            ?.map { f ->
                val name = if (f.isFile) f.nameWithoutExtension else f.name
                val entry = LocalMangaEntry(name = name, directory = f)
                entry.copy(coverFile = resolveCover(entry))
            }
            ?: emptyList()

    /**
     * Recursively discovers manga in [rootDir] up to [maxDepth] directory levels.
     *
     * BFS traversal: at each directory, checks if it qualifies as a manga
     * (has chapters with actual image content). If yes, adds it to results and
     * **stops recursing** into it. If no, recurses into its subdirectories.
     *
     * Archive files at any level are always treated as single-chapter manga.
     *
     * Cover resolution is **not** performed here (returns `coverFile = null`)
     * so the caller can display the list immediately and resolve covers async.
     *
     * Results are sorted in natural order.
     */
    fun discoverMangaRecursive(rootDir: File, maxDepth: Int = 3): List<LocalMangaEntry> {
        val result = mutableListOf<LocalMangaEntry>()
        // BFS queue: (directory, currentDepth)
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(rootDir to 0)

        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue

            // Collect archive files at this level as single-chapter manga
            // Only add archives that actually contain image files
            children
                .filter { it.isFile && it.extension.lowercase() in ARCHIVE_EXTENSIONS && archiveHasImages(it) }
                .forEach { f ->
                    result += LocalMangaEntry(
                        name = f.nameWithoutExtension,
                        directory = f,
                    )
                }

            // Process subdirectories
            for (child in children) {
                if (!child.isDirectory) continue
                val isManga = discoverChapters(child).any { chapterHasContent(it) }
                if (isManga) {
                    result += LocalMangaEntry(name = child.name, directory = child)
                    // Do NOT recurse into manga directories
                } else if (depth + 1 < maxDepth) {
                    queue.add(child to depth + 1)
                }
            }
        }

        return result.sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
    }

    // ── Content validation ────────────────────────────────────────────────────

    /**
     * Returns true if [chapter] contains at least one readable image.
     *
     * For directory chapters: checks for image files directly inside.
     * For ZIP/CBZ archives: peeks at the central directory for image entries.
     * For RAR/CBR archives: checks via sevenzipjbinding (auto-detect format).
     */
    private fun chapterHasContent(chapter: LocalChapterEntry): Boolean =
        if (chapter.file.isDirectory) {
            chapter.file.listFiles()
                ?.any { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS } == true
        } else {
            archiveHasImages(chapter.file)
        }

    private fun archiveHasImages(archive: File): Boolean {
        if (archive.length() == 0L) return false
        return when (archive.extension.lowercase()) {
            in ZIP_EXTENSIONS -> zipHasImages(archive)
            in RAR_EXTENSIONS -> rarHasImages(archive)
            else -> false
        }
    }

    private fun zipHasImages(archive: File): Boolean =
        try {
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().any { entry ->
                    !entry.isDirectory &&
                        entry.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS
                }
            }
        } catch (_: Exception) { false }

    private fun rarHasImages(archive: File): Boolean {
        val raf = try { RandomAccessFile(archive, "r") } catch (_: Exception) { return false }
        val inArchive = try {
            SevenZip.openInArchive(null, RandomAccessFileInStream(raf))
        } catch (_: Exception) { raf.close(); return false }
        if (inArchive == null) { raf.close(); return false }
        return try {
            (0 until inArchive.numberOfItems).any { i ->
                val isFolder = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                if (isFolder) return@any false
                val path = inArchive.getStringProperty(i, PropID.PATH) ?: return@any false
                path.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS
            }
        } catch (_: Exception) { false } finally {
            try { inArchive.close() } catch (_: Exception) {}
            try { raf.close() } catch (_: Exception) {}
        }
    }

    // ── Cover resolution ──────────────────────────────────────────────────────

    private val COVER_NAMES = setOf("cover", "folder", "thumbnail")

    /**
     * Resolves the cover image for [entry].
     *
     * - **Directory manga**: looks for a file named `cover`/`folder`/`thumbnail`
     *   (any image extension) inside the manga directory.
     * - **Archive manga**: prefers a sidecar file with the same base name as the
     *   archive (e.g. `OnePiece.jpg` next to `OnePiece.cbz`); falls back to
     *   extracting the first image from the archive into the system temp directory.
     *
     * Returns `null` if no cover can be resolved.
     */
    fun resolveCover(entry: LocalMangaEntry): File? =
        if (entry.directory.isDirectory) resolveDirectoryCover(entry.directory)
        else resolveArchiveCover(entry.directory)

    private fun resolveDirectoryCover(dir: File): File? =
        dir.listFiles()?.firstOrNull { f ->
            f.isFile &&
                f.nameWithoutExtension.lowercase() in COVER_NAMES &&
                f.extension.lowercase() in IMAGE_EXTENSIONS
        }

    private fun resolveArchiveCover(archive: File): File? {
        // 1. Sidecar: same base name, any image extension
        val sidecar = IMAGE_EXTENSIONS.map { ext ->
            File(archive.parent, "${archive.nameWithoutExtension}.$ext")
        }.firstOrNull { it.exists() }
        if (sidecar != null) return sidecar

        // 2. Extract first image from the archive to temp dir
        return extractFirstImage(archive)
    }

    private fun extractFirstImage(archive: File): File? =
        when (archive.extension.lowercase()) {
            in ZIP_EXTENSIONS -> extractFirstImageFromZip(archive)
            in RAR_EXTENSIONS -> extractFirstImageFromRar(archive)
            else -> null
        }

    private fun extractFirstImageFromZip(archive: File): File? =
        try {
            ZipFile(archive).use { zip ->
                val entry = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
                    .sortedWith(Comparator { a, b -> naturalOrder.compare(a.name, b.name) })
                    .firstOrNull() ?: return null
                val ext = entry.name.substringAfterLast('.')
                val cache = coverCacheFile(archive, ext)
                if (!cache.exists()) {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(cache).use { output -> input.copyTo(output) }
                    }
                }
                cache
            }
        } catch (_: Exception) { null }

    private fun extractFirstImageFromRar(archive: File): File? {
        val raf = try { RandomAccessFile(archive, "r") } catch (_: Exception) { return null }
        val inArchive = try {
            SevenZip.openInArchive(null, RandomAccessFileInStream(raf))
        } catch (_: Exception) { raf.close(); return null }
        if (inArchive == null) { raf.close(); return null }
        return try {
            val item = inArchive.getSimpleInterface().archiveItems
                .filter { !it.isFolder && (it.path ?: "").substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
                .sortedWith(Comparator { a, b -> naturalOrder.compare(a.path ?: "", b.path ?: "") })
                .firstOrNull() ?: return null
            val ext = (item.path ?: return null).substringAfterLast('.')
            val cache = coverCacheFile(archive, ext)
            if (!cache.exists()) {
                FileOutputStream(cache).use { out ->
                    item.extractSlow(object : ISequentialOutStream {
                        override fun write(data: ByteArray): Int { out.write(data); return data.size }
                    })
                }
            }
            cache
        } catch (_: Exception) { null } finally {
            try { inArchive.close() } catch (_: Exception) {}
            try { raf.close() } catch (_: Exception) {}
        }
    }

    private fun coverCacheFile(archive: File, ext: String): File =
        File(
            System.getProperty("java.io.tmpdir"),
            "mihon_cover_${archive.nameWithoutExtension}_${archive.lastModified()}.$ext",
        )

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
