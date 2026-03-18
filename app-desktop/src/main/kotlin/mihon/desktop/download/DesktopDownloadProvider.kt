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
        return dir.isDirectory && dir.listFiles()?.any { it.isImageFile() } == true
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
            ?.filter { it.isImageFile() }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

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

    private fun File.isImageFile(): Boolean {
        val ext = extension.lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    }
}
