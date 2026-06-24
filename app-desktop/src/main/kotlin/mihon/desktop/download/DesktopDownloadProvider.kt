package mihon.desktop.download

import java.io.File

private val ILLEGAL_CHARS = Regex("""[/\\:*?"<>|]""")
private fun sanitize(name: String): String =
    ILLEGAL_CHARS.replace(name.trim(), "_").take(200)

/**
 * Resolves and queries the on-disk download directory structure:
 *   `<baseDir>/<sourceId>/<mangaTitle>/<chapterName>/`
 *
 * Follows Android Mihon's convention: in-progress downloads use a `_tmp`
 * suffix on the chapter directory. Only directories without the suffix
 * are considered "downloaded".
 */
class DesktopDownloadProvider(
    private val baseDir: File,
) {

    companion object {
        /** Suffix appended to chapter directories while downloading (mirrors Android Downloader.TMP_DIR_SUFFIX). */
        const val TMP_DIR_SUFFIX = "_tmp"
    }

    /** Returns the final (non-tmp) chapter directory path. */
    fun chapterDownloadDir(sourceId: Long, mangaTitle: String, chapterName: String): File =
        File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}/${sanitize(chapterName)}")

    /** Returns the temporary chapter directory used during downloads. */
    fun chapterTmpDir(sourceId: Long, mangaTitle: String, chapterName: String): File =
        File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}/${sanitize(chapterName)}$TMP_DIR_SUFFIX")

    /**
     * A chapter is considered downloaded only when the **final** directory
     * (without `_tmp` suffix) exists and contains at least one image file.
     * Temporary directories are explicitly excluded — matching Android's
     * DownloadCache behaviour.
     */
    fun isChapterDownloaded(sourceId: Long, mangaTitle: String, chapterName: String): Boolean {
        val dir = chapterDownloadDir(sourceId, mangaTitle, chapterName)
        return dir.isDirectory && dir.listFiles()?.any { it.isReadableImageFile() } == true
    }

    /** Returns true if a `_tmp` directory exists for this chapter (download in progress or abandoned). */
    fun isChapterDownloading(sourceId: Long, mangaTitle: String, chapterName: String): Boolean {
        val tmpDir = chapterTmpDir(sourceId, mangaTitle, chapterName)
        return tmpDir.isDirectory
    }

    fun getDownloadedPages(sourceId: Long, mangaTitle: String, chapterName: String): List<File> {
        val dir = chapterDownloadDir(sourceId, mangaTitle, chapterName)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isReadableImageFile() }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Returns true if the manga has at least one fully-downloaded chapter.
     * Temporary (`_tmp`) directories are excluded — same rule as [isChapterDownloaded].
     */
    fun hasMangaDownloads(sourceId: Long, mangaTitle: String): Boolean {
        val mangaDir = File(baseDir, "${sanitize(sourceId.toString())}/${sanitize(mangaTitle)}")
        if (!mangaDir.isDirectory) return false
        return mangaDir.listFiles()?.any { chapterDir ->
            chapterDir.isDirectory &&
                !chapterDir.name.endsWith(TMP_DIR_SUFFIX) &&
                chapterDir.listFiles()?.any { f -> f.isReadableImageFile() } == true
        } == true
    }

    /** Returns true when a downloaded image has a supported extension and a matching file signature. */
    fun isValidDownloadedImage(file: File): Boolean = file.isReadableImageFile()

    /** Deletes the chapter download directory and all its contents. */
    fun deleteChapterDownload(sourceId: Long, mangaTitle: String, chapterName: String) {
        val dir = chapterDownloadDir(sourceId, mangaTitle, chapterName)
        dir.deleteRecursively()
    }

    /** Deletes the temporary download directory for a chapter. */
    fun cleanupTmpDir(sourceId: Long, mangaTitle: String, chapterName: String) {
        val tmpDir = chapterTmpDir(sourceId, mangaTitle, chapterName)
        tmpDir.deleteRecursively()
    }

    /**
     * Renames the `_tmp` directory to the final chapter directory name.
     * Returns true on success.
     */
    fun renameTmpToFinal(sourceId: Long, mangaTitle: String, chapterName: String): Boolean {
        val tmpDir = chapterTmpDir(sourceId, mangaTitle, chapterName)
        val finalDir = chapterDownloadDir(sourceId, mangaTitle, chapterName)
        if (!tmpDir.isDirectory) return false
        // Remove any existing final directory first
        finalDir.deleteRecursively()
        return tmpDir.renameTo(finalDir)
    }

    private fun File.isReadableImageFile(): Boolean {
        val ext = extension.lowercase()
        if (ext !in setOf("jpg", "jpeg", "png", "webp", "gif", "avif")) return false
        if (!isFile || length() <= 0L) return false

        val header = inputStream().use { input ->
            ByteArray(32).also { bytes -> input.read(bytes) }
        }

        return when (ext) {
            "jpg", "jpeg" -> header.startsWith(0xFF, 0xD8)
            "png" -> header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            "gif" -> header.startsWith("GIF87a") || header.startsWith("GIF89a")
            "webp" -> header.startsWith("RIFF") && header.hasAsciiAt(8, "WEBP")
            "avif" -> header.hasAsciiAt(4, "ftyp") &&
                listOf("avif", "avis", "mif1", "msf1").any { brand -> header.containsAscii(brand) }
            else -> false
        }
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        bytes.withIndex().all { (index, byte) -> this.getOrNull(index) == byte.toByte() }

    private fun ByteArray.startsWith(ascii: String): Boolean = hasAsciiAt(0, ascii)

    private fun ByteArray.hasAsciiAt(offset: Int, ascii: String): Boolean =
        ascii.indices.all { index -> getOrNull(offset + index) == ascii[index].code.toByte() }

    private fun ByteArray.containsAscii(ascii: String): Boolean {
        if (ascii.isEmpty() || ascii.length > size) return false
        return indices.any { offset ->
            offset + ascii.length <= size && hasAsciiAt(offset, ascii)
        }
    }
}
