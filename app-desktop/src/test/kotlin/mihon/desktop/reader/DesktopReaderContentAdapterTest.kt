package mihon.desktop.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.content.ReaderImageSortMode
import mihon.domain.reader.session.ReaderPageLoadState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DesktopReaderContentAdapterTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `directory descriptors preserve route sort semantics and ready file refs`() {
        val directory = tempDir.resolve("directory").also(File::mkdirs)
        val page10 = directory.resolve("10.png").also { it.writeBytes(imageBytes(10)) }
        val page2 = directory.resolve("2.png").also { it.writeBytes(imageBytes(2)) }
        directory.resolve("notes.txt").writeText("not an image")
        val adapter = DesktopReaderContentAdapter()

        val local = adapter.directoryDescriptors(directory, ReaderImageSortMode.LOCAL_NATURAL_CASE_INSENSITIVE)
        val downloaded = adapter.directoryDescriptors(directory, ReaderImageSortMode.DOWNLOAD_LEXICAL_CASE_SENSITIVE)

        assertEquals(listOf("2.png", "10.png"), local.map { it.url })
        assertEquals(listOf("10.png", "2.png"), downloaded.map { it.url })
        assertEquals(listOf(page2, page10).map { it.toURI().toString() }, local.map { it.encodedPageRef?.value })
        assertTrue(local.all { it.initialLoadState == ReaderPageLoadState.Ready })
        adapter.close()
    }

    @Test
    fun `same path size and mtime replacement changes identity and rejects a stale descriptor`() = runTest {
        val archive = tempDir.resolve("replaceable.cbz")
        val firstBytes = imageBytes(1)
        val secondBytes = imageBytes(2)
        val fixedModifiedTime = FileTime.fromMillis(1_700_000_000_000L)
        createStoredZip(archive, listOf("001.png" to firstBytes))
        Files.setLastModifiedTime(archive.toPath(), fixedModifiedTime)
        val originalSize = archive.length()
        val adapter = DesktopReaderContentAdapter()
        val first = adapter.archiveDescriptors(19L, archive).single()

        createStoredZip(archive, listOf("001.png" to secondBytes))
        assertEquals(originalSize, archive.length())
        Files.setLastModifiedTime(archive.toPath(), fixedModifiedTime)
        val second = adapter.archiveDescriptors(19L, archive).single()

        assertNotEquals(first.url, second.url)
        val staleDestination = tempDir.resolve("stale.png")
        val staleFailure = runCatching {
            adapter.copyArchivePage(19L, 0, first.url, staleDestination)
        }.exceptionOrNull()
        assertNotNull(staleFailure)
        assertFalse(staleDestination.exists())

        val currentDestination = tempDir.resolve("current.png")
        adapter.copyArchivePage(19L, 0, second.url, currentDestination)
        assertArrayEquals(secondBytes, currentDestination.readBytes())
        adapter.close()
    }

    @Test
    fun `replacement identity stays fresh while another adapter still owns the old zip lease`() = runTest {
        val archive = tempDir.resolve("cross-adapter.cbz")
        val firstBytes = imageBytes(3)
        val secondBytes = imageBytes(4)
        val fixedModifiedTime = FileTime.fromMillis(1_700_000_000_000L)
        createStoredZip(archive, listOf("001.png" to firstBytes))
        Files.setLastModifiedTime(archive.toPath(), fixedModifiedTime)
        val originalSize = archive.length()
        val firstAdapter = DesktopReaderContentAdapter()
        val first = firstAdapter.archiveDescriptors(20L, archive).single()

        createStoredZip(archive, listOf("001.png" to secondBytes))
        assertEquals(originalSize, archive.length())
        Files.setLastModifiedTime(archive.toPath(), fixedModifiedTime)
        val secondAdapter = DesktopReaderContentAdapter()
        val second = secondAdapter.archiveDescriptors(20L, archive).single()

        assertNotEquals(first.url, second.url)
        val destination = tempDir.resolve("cross-adapter-current.png")
        secondAdapter.copyArchivePage(20L, 0, second.url, destination)
        assertArrayEquals(secondBytes, destination.readBytes())
        firstAdapter.close()
        secondAdapter.close()
    }

    @Test
    fun `replacement epub stays fresh while another adapter owns the old lease`() = runTest {
        val archive = tempDir.resolve("cross-adapter.epub")
        val firstBytes = imageBytes(5)
        val secondBytes = imageBytes(6)
        val fixedModifiedTime = FileTime.fromMillis(1_700_000_000_000L)
        createEpub(archive, firstBytes)
        Files.setLastModifiedTime(archive.toPath(), fixedModifiedTime)
        val originalSize = archive.length()
        val firstAdapter = DesktopReaderContentAdapter()
        val first = firstAdapter.archiveDescriptors(21L, archive, epub = true).single()

        createEpub(archive, secondBytes)
        assertEquals(originalSize, archive.length())
        Files.setLastModifiedTime(archive.toPath(), fixedModifiedTime)
        val secondAdapter = DesktopReaderContentAdapter()
        val second = secondAdapter.archiveDescriptors(21L, archive, epub = true).single()

        assertNotEquals(first.url, second.url)
        val destination = tempDir.resolve("cross-adapter-current-epub.png")
        secondAdapter.copyArchivePage(21L, 0, second.url, destination)
        assertArrayEquals(secondBytes, destination.readBytes())
        firstAdapter.close()
        secondAdapter.close()
    }

    @Test
    fun `epub svg spine resolves ordinary image href`() = runTest {
        val expected = imageBytes(7)
        val epub = createStoredZip(
            tempDir.resolve("svg-spine.epub"),
            listOf(
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
                        .encodeToByteArray(),
                "OEBPS/content.opf" to
                    """<package><manifest><item id="page1" href="pages/page.svg" media-type="image/svg+xml"/></manifest><spine><itemref idref="page1"/></spine></package>"""
                        .encodeToByteArray(),
                "OEBPS/pages/page.svg" to
                    """<svg xmlns="http://www.w3.org/2000/svg"><image href="../images/001.png"/></svg>"""
                        .encodeToByteArray(),
                "OEBPS/images/001.png" to expected,
            ),
        )
        val adapter = DesktopReaderContentAdapter()

        val descriptor = adapter.archiveDescriptors(22L, epub, epub = true).single()
        val destination = tempDir.resolve("svg-spine-page.png")
        adapter.copyArchivePage(22L, 0, descriptor.url, destination)

        assertArrayEquals(expected, destination.readBytes())
        adapter.close()
    }

    @Test
    fun `epub references decode percent paths and discard query and fragment`() = runTest {
        val expected = imageBytes(8)
        val epub = createStoredZip(
            tempDir.resolve("uri-paths.epub"),
            listOf(
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
                        .encodeToByteArray(),
                "OEBPS/content.opf" to
                    """<package><manifest><item id="page1" href="pages/page%201.xhtml#panel" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="page1"/></spine></package>"""
                        .encodeToByteArray(),
                "OEBPS/pages/page 1.xhtml" to
                    """<html><body><svg><image href="../images/page%201.png?variant=full#target"/></svg></body></html>"""
                        .encodeToByteArray(),
                "OEBPS/images/page 1.png" to expected,
            ),
        )
        val adapter = DesktopReaderContentAdapter()

        val descriptor = adapter.archiveDescriptors(23L, epub, epub = true).single()
        val destination = tempDir.resolve("uri-path-page.png")
        adapter.copyArchivePage(23L, 0, descriptor.url, destination)

        assertArrayEquals(expected, destination.readBytes())
        adapter.close()
    }

    @Test
    fun `zip epub and rar expose stable lazy entries and release their handles`() = runTest {
        val page2 = imageBytes(2)
        val page10 = imageBytes(10)
        val zip = createStoredZip(
            tempDir.resolve("chapter.cbz"),
            listOf("pages/10.png" to page10, "pages/2.png" to page2),
        )
        val epub = createEpub(tempDir.resolve("chapter.epub"), page2)
        val rar = createStoredRar(tempDir.resolve("chapter.rar"), listOf("pages/001.png" to page10))

        listOf(zip, epub, rar).forEachIndexed { offset, archive ->
            val chapterId = 30L + offset
            val adapter = DesktopReaderContentAdapter()
            val descriptors = adapter.archiveDescriptors(chapterId, archive, epub = archive.extension == "epub")
            val expected = when (archive.extension) {
                "cbz" -> listOf(page2, page10)
                "epub" -> listOf(page2)
                else -> listOf(page10)
            }

            descriptors.mapIndexed { index, descriptor ->
                val destination = tempDir.resolve("${archive.extension}-$index.bin")
                adapter.copyArchivePage(chapterId, index, descriptor.url, destination)
                assertArrayEquals(expected[index], destination.readBytes())
            }

            adapter.releaseChapter(chapterId)
            assertFalse(adapter.hasArchiveLease(chapterId))
            assertTrue(archive.delete(), "releaseChapter must close the ${archive.extension} handle")
            adapter.close()
        }
    }

    @Test
    fun `real rar operations stay serialized while concurrent pages retain natural order`() = runTest {
        val page2 = imageBytes(2)
        val page10 = imageBytes(10)
        val rar = createStoredRar(
            tempDir.resolve("concurrent.rar"),
            listOf("pages/10.png" to page10, "pages/2.png" to page2),
        )
        val activeOperations = AtomicInteger()
        val maximumOperations = AtomicInteger()
        val adapter = DesktopReaderContentAdapter(
            object : DesktopReaderArchiveOperationProbe {
                override fun onStart() {
                    val active = activeOperations.incrementAndGet()
                    maximumOperations.accumulateAndGet(active, ::maxOf)
                    Thread.sleep(25)
                }

                override fun onFinish() {
                    activeOperations.decrementAndGet()
                }
            },
        )
        val descriptors = adapter.archiveDescriptors(40L, rar)

        val destinations = descriptors.mapIndexed { index, descriptor ->
            async(Dispatchers.IO) {
                tempDir.resolve("rar-$index.bin").also { destination ->
                    adapter.copyArchivePage(40L, index, descriptor.url, destination)
                }
            }
        }.awaitAll()

        assertEquals(1, maximumOperations.get())
        assertArrayEquals(page2, destinations[0].readBytes())
        assertArrayEquals(page10, destinations[1].readBytes())
        adapter.close()
        assertTrue(rar.delete())
    }

    @Test
    fun `adapter close rejects a lease that finishes opening after close`() = runTest {
        val archive = createStoredZip(tempDir.resolve("late-close.cbz"), listOf("001.png" to imageBytes(60)))
        val snapshotStarted = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val adapter = DesktopReaderContentAdapter(
            object : DesktopReaderArchiveOperationProbe {
                override fun onStart() {
                    snapshotStarted.countDown()
                    releaseSnapshot.await()
                }

                override fun onFinish() = Unit
            },
        )
        adapter.reserveChapter(chapterId = 60L, leaseGeneration = 1L)
        val lateOpen = async(Dispatchers.IO) {
            runCatching { adapter.archiveDescriptors(60L, archive, leaseGeneration = 1L) }
        }
        assertTrue(snapshotStarted.await(2, TimeUnit.SECONDS))

        adapter.close()
        releaseSnapshot.countDown()

        assertNotNull(lateOpen.await().exceptionOrNull())
        assertFalse(adapter.hasArchiveLease(60L))
        assertTrue(archive.delete(), "A post-close archive open must release its own handle")
    }

    @Test
    fun `late old generation cannot replace a newer same chapter lease`() = runTest {
        val oldArchive = createStoredZip(tempDir.resolve("old-generation.cbz"), listOf("001.png" to imageBytes(61)))
        val newBytes = imageBytes(62)
        val newArchive = createStoredZip(tempDir.resolve("new-generation.cbz"), listOf("001.png" to newBytes))
        val firstSnapshotStarted = CountDownLatch(1)
        val releaseFirstSnapshot = CountDownLatch(1)
        val snapshotCount = AtomicInteger()
        val adapter = DesktopReaderContentAdapter(
            object : DesktopReaderArchiveOperationProbe {
                override fun onStart() {
                    if (snapshotCount.incrementAndGet() == 1) {
                        firstSnapshotStarted.countDown()
                        releaseFirstSnapshot.await()
                    }
                }

                override fun onFinish() = Unit
            },
        )
        adapter.reserveChapter(chapterId = 61L, leaseGeneration = 1L)
        val oldOpen = async(Dispatchers.IO) {
            runCatching { adapter.archiveDescriptors(61L, oldArchive, leaseGeneration = 1L) }
        }
        assertTrue(firstSnapshotStarted.await(2, TimeUnit.SECONDS))

        adapter.releaseChapter(chapterId = 61L, leaseGeneration = 1L)
        adapter.reserveChapter(chapterId = 61L, leaseGeneration = 2L)
        val current = adapter.archiveDescriptors(61L, newArchive, leaseGeneration = 2L).single()
        releaseFirstSnapshot.countDown()

        assertNotNull(oldOpen.await().exceptionOrNull())
        assertTrue(adapter.hasArchiveLease(61L))
        val destination = tempDir.resolve("new-generation-page.png")
        adapter.copyArchivePage(61L, 0, current.url, destination)
        assertArrayEquals(newBytes, destination.readBytes())
        assertTrue(oldArchive.delete())
        adapter.close()
        assertTrue(newArchive.delete())
    }

    @Test
    fun `released newer generation cannot be reactivated by a late older reserve`() {
        val archive = createStoredZip(tempDir.resolve("late-reserve.cbz"), listOf("001.png" to imageBytes(63)))
        val adapter = DesktopReaderContentAdapter()

        adapter.reserveChapter(chapterId = 63L, leaseGeneration = 3L)
        adapter.releaseChapter(chapterId = 63L, leaseGeneration = 3L)
        adapter.reserveChapter(chapterId = 63L, leaseGeneration = 2L)

        val staleFailure = runCatching {
            adapter.archiveDescriptors(63L, archive, leaseGeneration = 2L)
        }.exceptionOrNull()
        assertInstanceOf(CancellationException::class.java, staleFailure)
        assertFalse(adapter.hasArchiveLease(63L))

        adapter.reserveChapter(chapterId = 63L, leaseGeneration = 4L)
        assertEquals(1, adapter.archiveDescriptors(63L, archive, leaseGeneration = 4L).size)
        adapter.close()
        assertTrue(archive.delete())
    }

    @Test
    fun `empty and malformed archives are bounded and do not leak handles`() {
        val empty = tempDir.resolve("empty.cbz")
        ZipOutputStream(empty.outputStream()).use { }
        val malformed = tempDir.resolve("malformed.cbz").also { it.writeText("not a zip") }
        val adapter = DesktopReaderContentAdapter()

        assertTrue(adapter.archiveDescriptors(50L, empty).isEmpty())
        adapter.releaseChapter(50L)
        assertTrue(empty.delete())

        val failure = runCatching { adapter.archiveDescriptors(51L, malformed) }.exceptionOrNull()
        val appError = assertInstanceOf(AppErrorException::class.java, failure).error
        assertInstanceOf(AppError.Storage::class.java, appError)
        assertTrue(malformed.delete())
        adapter.close()
    }

    private fun imageBytes(marker: Int): ByteArray = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    ) + ByteArray(32) { marker.toByte() }

    private fun createStoredZip(file: File, entries: List<Pair<String, ByteArray>>): File = file.also { archive ->
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                        time = 0L
                    },
                )
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun createEpub(file: File, image: ByteArray): File = createStoredZip(
        file,
        listOf(
            "META-INF/container.xml" to
                """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
                    .encodeToByteArray(),
            "OEBPS/content.opf" to
                """<package><manifest><item id="page1" href="page1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="page1"/></spine></package>"""
                    .encodeToByteArray(),
            "OEBPS/page1.xhtml" to
                """<html><body><img src="images/001.png"/></body></html>""".encodeToByteArray(),
            "OEBPS/images/001.png" to image,
        ),
    )

    private fun createStoredRar(file: File, entries: List<Pair<String, ByteArray>>): File {
        val archive = ByteArrayOutputStream()
        archive.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        archive.writeRarHeader(type = 0x73, flags = 0) {
            writeShortLe(0)
            writeIntLe(0)
        }
        entries.forEach { (name, bytes) ->
            val encodedName = name.encodeToByteArray()
            val contentCrc = CRC32().apply { update(bytes) }.value.toInt()
            archive.writeRarHeader(type = 0x74, flags = 0x8000) {
                writeIntLe(bytes.size)
                writeIntLe(bytes.size)
                write(3)
                writeIntLe(contentCrc)
                writeIntLe(0)
                write(20)
                write(0x30)
                writeShortLe(encodedName.size)
                writeIntLe(0x20)
                write(encodedName)
            }
            archive.write(bytes)
        }
        archive.writeRarHeader(type = 0x7B, flags = 0x4000) { }
        file.writeBytes(archive.toByteArray())
        return file
    }

    private fun ByteArrayOutputStream.writeRarHeader(
        type: Int,
        flags: Int,
        body: ByteArrayOutputStream.() -> Unit,
    ) {
        val bodyBytes = ByteArrayOutputStream().apply(body).toByteArray()
        val header = ByteArrayOutputStream().apply {
            write(type)
            writeShortLe(flags)
            writeShortLe(7 + bodyBytes.size)
            write(bodyBytes)
        }.toByteArray()
        val headerCrc = CRC32().apply { update(header) }.value.toInt() and 0xFFFF
        writeShortLe(headerCrc)
        write(header)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write(value ushr 8 and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 24 and 0xFF)
    }
}
