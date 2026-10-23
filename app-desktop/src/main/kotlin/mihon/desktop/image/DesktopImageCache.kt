package mihon.desktop.image

import java.io.File
import java.security.MessageDigest

/**
 * A small cache for the cover images of the library.
 *
 * The covers are read through the HTTP cache, whose index is guarded by a single lock.
 * That serialises the reads: the library grid receives its covers one after the other
 * and fills in one cover at a time.
 *
 * This cache stores every cover in a file of its own, so the reads of the grid run in
 * parallel and the covers arrive together. It also keeps the covers out of the HTTP
 * cache, which is meant for the responses of the sources.
 */
internal class DesktopImageCache(private val directory: File) {

    init {
        directory.mkdirs()
    }

    /** The directory that holds the cached covers. */
    fun directory(): File = directory

    /** Reads the bytes of a cached image, or `null` when the image is not cached. */
    fun read(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /** Tells whether an image is cached, without reading it. */
    fun contains(key: String): Boolean = fileFor(key).isFile

    /** Stores the bytes of an image under the given key. */
    fun write(key: String, bytes: ByteArray) {
        runCatching {
            val file = fileFor(key)
            file.parentFile?.mkdirs()
            val staging = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
            staging.writeBytes(bytes)
            if (!staging.renameTo(file)) {
                staging.delete()
                file.writeBytes(bytes)
            }
        }
    }

    /** Removes a cached image. */
    fun remove(key: String) {
        runCatching { fileFor(key).delete() }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(directory, name)
    }
}
