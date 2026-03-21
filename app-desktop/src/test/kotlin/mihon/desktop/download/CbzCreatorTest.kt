package mihon.desktop.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class CbzCreatorTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `createCbz packages all images from dir into a zip`() {
        // Create a fake chapter download directory with images
        val chapterDir = File(tempDir, "chapter1").also { it.mkdirs() }
        File(chapterDir, "001.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // fake jpg
        File(chapterDir, "002.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        File(chapterDir, "003.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte())) // fake png

        val cbzFile = File(tempDir, "chapter1.cbz")
        CbzCreator.create(chapterDir, cbzFile)

        assertTrue(cbzFile.exists(), "CBZ file must be created")
        assertTrue(cbzFile.length() > 0)

        // Verify it's a valid zip
        val zip = ZipFile(cbzFile)
        val entries = zip.entries().toList()
        zip.close()
        assertEquals(3, entries.size, "CBZ must contain all 3 images")
    }

    @Test
    fun `createCbz returns false when source dir is empty`() {
        val emptyDir = File(tempDir, "empty").also { it.mkdirs() }
        val cbzFile = File(tempDir, "empty.cbz")
        val success = CbzCreator.create(emptyDir, cbzFile)
        assertEquals(false, success, "Should return false for empty directory")
    }

    @Test
    fun `cbz file name uses chapter dir name with cbz extension`() {
        val chapterDir = File(tempDir, "Vol 1 Ch 5").also { it.mkdirs() }
        File(chapterDir, "001.jpg").writeBytes(ByteArray(10))
        val cbzFile = CbzCreator.defaultOutputFile(chapterDir)
        assertEquals("Vol 1 Ch 5.cbz", cbzFile.name)
    }

    @Test
    fun `createCbz does not include non-image files`() {
        val chapterDir = File(tempDir, "chapter2").also { it.mkdirs() }
        File(chapterDir, "001.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        File(chapterDir, "metadata.json").writeText("{}")

        val cbzFile = File(tempDir, "chapter2.cbz")
        CbzCreator.create(chapterDir, cbzFile)

        val zip = ZipFile(cbzFile)
        val entries = zip.entries().toList()
        zip.close()
        assertEquals(1, entries.size, "CBZ must only contain image files")
        assertEquals("001.jpg", entries[0].name)
    }
}
