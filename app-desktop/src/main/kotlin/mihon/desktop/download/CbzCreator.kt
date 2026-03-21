package mihon.desktop.download

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")

/**
 * Packages a downloaded chapter directory into a `.cbz` (Comic Book Zip) file.
 *
 * CBZ is a standard ZIP archive containing only image files, compatible with
 * most comic readers (Mihon Android, Komga, Kavita, etc.).
 */
object CbzCreator {

    /**
     * Creates a CBZ file from all image files in [sourceDir].
     * @return true on success, false if [sourceDir] contains no images.
     */
    fun create(sourceDir: File, outputFile: File): Boolean {
        val imageFiles = sourceDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (imageFiles.isEmpty()) return false

        outputFile.parentFile?.mkdirs()
        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            for (image in imageFiles) {
                zip.putNextEntry(ZipEntry(image.name))
                image.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return true
    }

    /** Returns `<parentDir>/<sourceDir.name>.cbz`. */
    fun defaultOutputFile(sourceDir: File): File =
        File(sourceDir.parentFile, "${sourceDir.name}.cbz")
}
