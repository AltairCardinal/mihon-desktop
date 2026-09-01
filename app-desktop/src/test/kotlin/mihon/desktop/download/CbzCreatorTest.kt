package mihon.desktop.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread

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
        ZipFile(cbzFile).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(3, entries.size, "CBZ must contain all 3 images")
            assertEquals(listOf("001.jpg", "002.jpg", "003.png"), entries.map { it.name })
            entries.forEach { entry ->
                assertTrue(entry.size > 0L)
                assertTrue(entry.crc >= 0L)
                assertTrue(zip.getInputStream(entry).use { it.readBytes().isNotEmpty() })
            }
        }
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

    @Test
    fun `createCbz retains every upstream known image extension`() {
        val chapterDir = File(tempDir, "known-extensions").also { it.mkdirs() }
        listOf("avif", "gif", "heif", "jpg", "jp2", "jpx", "jxl", "png", "webp").forEach { extension ->
            File(chapterDir, "page.$extension").writeBytes(byteArrayOf(1))
        }
        val cbzFile = CbzCreator.defaultOutputFile(chapterDir)

        assertTrue(CbzCreator.create(chapterDir, cbzFile))

        ZipFile(cbzFile).use { archive ->
            assertEquals(9, archive.entries().asSequence().count())
        }
    }

    @Test
    fun `atomic CBZ remains invisible until a validated archive is published`() {
        val source = File(tempDir, "atomic-source_tmp").apply { mkdirs() }
        File(source, "001.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1))
        File(source, "002.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte(), 2))
        val target = File(tempDir, "atomic-source.cbz")
        val beforePublish = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        var packagingFailure: Throwable? = null

        val worker = thread(name = "cbz-atomic-publish") {
            try {
                CbzCreator.create(
                    sourceDir = source,
                    outputFile = target,
                    hooks = CbzCreationHooks(
                        beforePublish = {
                            beforePublish.countDown()
                            check(releasePublish.await(5, TimeUnit.SECONDS))
                        },
                    ),
                )
            } catch (error: Throwable) {
                packagingFailure = error
            }
        }

        try {
            assertTrue(beforePublish.await(5, TimeUnit.SECONDS))
            assertFalse(target.exists(), "The complete locator must not observe a transient final CBZ")
            assertTrue(source.isDirectory, "The private source directory remains the partial Reader authority")
            assertTrue(tempDir.listFiles().orEmpty().any { it.isFile && it.name.endsWith(".cbz.tmp") })
        } finally {
            releasePublish.countDown()
            worker.join(5_000)
        }

        assertFalse(worker.isAlive)
        assertEquals(null, packagingFailure)
        assertTrue(target.isFile)
        ZipFile(target).use { archive -> assertEquals(2, archive.size()) }
    }

    @Test
    fun `atomic move failure preserves every source page and exposes no final CBZ`() {
        val source = File(tempDir, "move-failure_tmp").apply { mkdirs() }
        val page = File(source, "001.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7)) }
        val target = File(tempDir, "move-failure.cbz")

        val error = assertThrows(CbzPackagingException::class.java) {
            CbzCreator.create(
                sourceDir = source,
                outputFile = target,
                atomicMove = { from, to ->
                    throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "fixture")
                },
            )
        }

        assertTrue(error.cause is AtomicMoveNotSupportedException)
        assertTrue(source.isDirectory)
        assertTrue(page.isFile)
        assertFalse(target.exists())
    }

    @Test
    fun `archive validation failure preserves source pages and never publishes corrupt target`() {
        val source = File(tempDir, "validation-failure_tmp").apply { mkdirs() }
        val page = File(source, "001.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9)) }
        val target = File(tempDir, "validation-failure.cbz")

        assertThrows(CbzPackagingException::class.java) {
            CbzCreator.create(
                sourceDir = source,
                outputFile = target,
                hooks = CbzCreationHooks(afterArchiveWrite = { temporary -> temporary.writeText("not a zip") }),
            )
        }

        assertTrue(source.isDirectory)
        assertTrue(page.isFile)
        assertFalse(target.exists())
    }

    @Test
    fun `identical existing CBZ is adopted while conflicting target and source are preserved`() {
        val source = File(tempDir, "idempotent-source_tmp").apply { mkdirs() }
        File(source, "001.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 11))
        val target = File(tempDir, "idempotent-source.cbz")
        assertTrue(CbzCreator.create(source, target))
        val published = target.readBytes()
        var atomicMoveCalls = 0

        assertTrue(
            CbzCreator.create(
                sourceDir = source,
                outputFile = target,
                atomicMove = { _, _ -> atomicMoveCalls++ },
            ),
        )
        assertEquals(0, atomicMoveCalls, "An identical existing final must be adopted without replacement")
        assertTrue(published.contentEquals(target.readBytes()))

        val conflictingTarget = File(tempDir, "conflicting.cbz").apply { writeText("keep-existing") }
        assertThrows(CbzPublishConflictException::class.java) {
            CbzCreator.create(source, conflictingTarget)
        }
        assertEquals("keep-existing", conflictingTarget.readText())
        assertTrue(source.isDirectory)
        assertTrue(File(source, "001.jpg").isFile)
    }

    @Test
    fun `CBZ final arriving after target preflight is never replaced`() {
        val source = File(tempDir, "late-final-source_tmp").apply { mkdirs() }
        File(source, "001.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 13))
        val target = File(tempDir, "late-final-source.cbz")
        val lateFinalBytes = "late-final-must-survive".toByteArray()

        val error = assertThrows(CbzPublishConflictException::class.java) {
            CbzCreator.create(
                sourceDir = source,
                outputFile = target,
                hooks = CbzCreationHooks(
                    afterTargetPreflight = {
                        target.writeBytes(lateFinalBytes)
                    },
                ),
            )
        }

        assertEquals(target.absoluteFile, error.existingFinal.absoluteFile)
        assertTrue(lateFinalBytes.contentEquals(target.readBytes()))
        assertTrue(source.isDirectory)
        assertTrue(File(source, "001.jpg").isFile)
    }
}
