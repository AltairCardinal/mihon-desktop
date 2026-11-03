package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Preparation-only fixture validation; the actual reader still opens the original CBZ. */
object ReaderLatencyArchive {
    data class Manifest(val sha256: String, val bytes: Long, val imageNames: List<String>)

    fun verify(file: File, expectedHash: String, expectedImages: Int): Manifest {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        require(hash.equals(expectedHash, ignoreCase = true)) { "CBZ bytes differ from the supplied source" }
        val names = ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }
                .filter {
                    it.name.substringAfterLast('.').lowercase() in
                        setOf("jpg", "jpeg", "png", "webp", "avif", "gif")
                }
                .map { it.name }.sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }.toList()
        }
        require(names.size == expectedImages) { "Unexpected CBZ image count: ${names.size}" }
        return Manifest(hash, file.length(), names)
    }
}
