package mihon.desktop.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val IMAGE_EXTENSIONS = setOf("avif", "gif", "heif", "jpeg", "jpg", "jp2", "jpx", "jxl", "png", "webp")

/**
 * Packages a downloaded chapter directory into a `.cbz` (Comic Book Zip) file.
 *
 * CBZ is a standard ZIP archive containing only image files, compatible with
 * most comic readers (Mihon Android, Komga, Kavita, etc.).
 */
open class CbzPackagingException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class CbzPublishConflictException(
    val existingFinal: File,
) : CbzPackagingException("Existing CBZ conflicts with the complete private download: ${existingFinal.absolutePath}")

internal data class CbzCreationHooks(
    val afterArchiveWrite: (File) -> Unit = {},
    val beforePublish: (File) -> Unit = {},
    val afterTargetPreflight: (File) -> Unit = {},
)

object CbzCreator {

    /**
     * Creates a CBZ file from all image files in [sourceDir].
     * @return true on success, false if [sourceDir] contains no images.
     */
    fun create(sourceDir: File, outputFile: File): Boolean = create(
        sourceDir = sourceDir,
        outputFile = outputFile,
        hooks = CbzCreationHooks(),
        atomicMove = ::atomicMove,
    )

    internal fun create(
        sourceDir: File,
        outputFile: File,
        hooks: CbzCreationHooks = CbzCreationHooks(),
        atomicMove: (Path, Path) -> Unit = ::atomicMove,
        expectedImageFiles: List<File>? = null,
    ): Boolean {
        val imageFiles = expectedImageFiles
            ?.also { files -> validateExpectedFiles(sourceDir, files) }
            ?.sortedBy(File::getName)
            ?: sourceDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                ?.sortedBy { it.name }
                .orEmpty()

        if (imageFiles.isEmpty()) return false

        val outputDirectory = outputFile.parentFile
            ?: throw CbzPackagingException("CBZ output directory is unavailable")
        outputDirectory.mkdirs()
        val temporary = Files.createTempFile(
            outputDirectory.toPath(),
            ".${outputFile.name}.",
            ".cbz.tmp",
        ).toFile()
        var published = false
        try {
            writeArchive(imageFiles, temporary)
            hooks.afterArchiveWrite(temporary)
            validateArchive(imageFiles, temporary)
            hooks.beforePublish(temporary)
            if (adoptIdenticalOrThrow(temporary, outputFile)) return true
            hooks.afterTargetPreflight(outputFile)
            try {
                atomicMove(temporary.toPath(), outputFile.toPath())
                published = true
            } catch (error: Exception) {
                if (adoptIdenticalOrThrow(temporary, outputFile)) return true
                throw CbzPackagingException("Unable to atomically publish CBZ: ${outputFile.absolutePath}", error)
            }
            if (!outputFile.isFile || outputFile.length() <= 0L || temporary.exists()) {
                throw CbzPackagingException("Atomic CBZ publish did not produce exactly one final artifact")
            }
            validateArchive(imageFiles, outputFile)
            return true
        } catch (error: CbzPackagingException) {
            throw error
        } catch (error: Exception) {
            throw CbzPackagingException("Unable to package CBZ: ${outputFile.absolutePath}", error)
        } finally {
            if (!published) temporary.delete()
        }
    }

    /** Returns `<parentDir>/<sourceDir.name>.cbz`. */
    fun defaultOutputFile(sourceDir: File): File =
        File(sourceDir.parentFile, "${sourceDir.name}.cbz")

    private fun writeArchive(imageFiles: List<File>, temporary: File) {
        ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
            imageFiles.forEach { image ->
                val entry = ZipEntry(image.name).apply { time = 0L }
                zip.putNextEntry(entry)
                image.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun validateExpectedFiles(sourceDir: File, imageFiles: List<File>) {
        if (imageFiles.map(File::getName).distinct().size != imageFiles.size) {
            throw CbzPackagingException("Committed CBZ page names are not unique")
        }
        imageFiles.forEach { image ->
            if (
                image.parentFile?.absoluteFile?.normalize() != sourceDir.absoluteFile.normalize() ||
                !image.isFile ||
                image.extension.lowercase() !in IMAGE_EXTENSIONS
            ) {
                throw CbzPackagingException("Committed CBZ page is unavailable or outside the private chapter")
            }
        }
    }

    private fun validateArchive(imageFiles: List<File>, archive: File) {
        val expected = imageFiles.associate { image ->
            image.name to ExpectedEntry(
                size = image.length(),
                crc = image.inputStream().buffered().use { input ->
                    val checksum = CRC32()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        checksum.update(buffer, 0, read)
                    }
                    checksum.value
                },
            )
        }
        if (expected.isEmpty() || expected.values.any { it.size <= 0L }) {
            throw CbzPackagingException("CBZ source contains an empty image")
        }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size != expected.size || entries.map(ZipEntry::getName).toSet() != expected.keys) {
                    throw CbzPackagingException("CBZ entry set does not match the committed page set")
                }
                entries.forEach { entry ->
                    val expectedEntry = expected.getValue(entry.name)
                    val bytes = zip.getInputStream(entry).buffered().use { it.readBytes() }
                    val crc = CRC32().apply { update(bytes) }.value
                    if (
                        entry.isDirectory ||
                        bytes.isEmpty() ||
                        bytes.size.toLong() != expectedEntry.size ||
                        entry.size != expectedEntry.size ||
                        entry.crc != expectedEntry.crc ||
                        crc != expectedEntry.crc
                    ) {
                        throw CbzPackagingException("CBZ entry failed size or CRC validation: ${entry.name}")
                    }
                }
            }
        } catch (error: CbzPackagingException) {
            throw error
        } catch (error: Exception) {
            throw CbzPackagingException("CBZ central directory or CRC validation failed", error)
        }
    }

    private fun adoptIdenticalOrThrow(temporary: File, outputFile: File): Boolean {
        if (!outputFile.exists()) return false
        if (outputFile.isFile && Files.mismatch(temporary.toPath(), outputFile.toPath()) == -1L) {
            temporary.delete()
            return true
        }
        throw CbzPublishConflictException(outputFile)
    }

    private fun atomicMove(source: Path, target: Path) {
        // A Java ATOMIC_MOVE may replace a concurrently-created target (notably on Windows).
        // Publishing through an exclusive hard link is a single no-clobber namespace operation;
        // unsupported filesystems fail safely and leave the private chapter available for retry.
        Files.createLink(target, source)
        Files.delete(source)
    }

    private data class ExpectedEntry(
        val size: Long,
        val crc: Long,
    )
}
