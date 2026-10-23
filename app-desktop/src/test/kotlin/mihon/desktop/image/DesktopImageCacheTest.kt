package mihon.desktop.image

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The covers are kept in a file of their own so the reads of the library grid run in
 * parallel. The HTTP cache they came from guards its index with a single lock, which is
 * what made the grid fill in one cover at a time.
 */
class DesktopImageCacheTest {

    @Test
    fun `an image is read back after it was written`(@TempDir tempDir: File) {
        val cache = DesktopImageCache(tempDir)

        cache.write("manga-cover:42:0:https://example.invalid/42.jpg", byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(1, 2, 3), cache.read("manga-cover:42:0:https://example.invalid/42.jpg"))
    }

    @Test
    fun `an image that was never written is not found`(@TempDir tempDir: File) {
        val cache = DesktopImageCache(tempDir)

        assertNull(cache.read("manga-cover:7:0:https://example.invalid/7.jpg"))
    }

    @Test
    fun `every image is kept in a file of its own`(@TempDir tempDir: File) {
        val cache = DesktopImageCache(tempDir)

        cache.write("manga-cover:1:0:first", byteArrayOf(1))
        cache.write("manga-cover:2:0:second", byteArrayOf(2))

        val files = tempDir.listFiles().orEmpty().filter { it.isFile }
        assertEquals(2, files.size, "every cover must have its own file: ${files.map { it.name }}")
        assertTrue(files.all { it.length() > 0 })
    }

    @Test
    fun `a removed image is gone`(@TempDir tempDir: File) {
        val cache = DesktopImageCache(tempDir)
        cache.write("manga-cover:3:0:third", byteArrayOf(9))

        cache.remove("manga-cover:3:0:third")

        assertNull(cache.read("manga-cover:3:0:third"))
    }
}
