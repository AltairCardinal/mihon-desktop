package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.session.ReaderChapterId
import okhttp3.OkHttpClient
import org.apache.commons.compress.archivers.zip.UnrecognizedExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipShort
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@EnabledOnOs(OS.WINDOWS)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopReaderWindowsMinizTest {
    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun loadPackagedLibrary() = runBlocking {
        DesktopWindowsMiniz.startAsync()
        withTimeout(10_000) {
            while (DesktopWindowsMiniz.library.state == DesktopWindowsMinizLibrary.State.LOADING) delay(10)
        }
        assertEquals(DesktopWindowsMinizLibrary.State.READY, DesktopWindowsMiniz.library.state)
    }

    @Test
    fun `download cbz uses miniz with real generation and preserves reference page identity and bytes`() = runBlocking {
        val provider = DesktopDownloadProvider(tempDir.resolve("downloads"))
        val directory = provider.chapterDownloadDir(42L, "Manga", "Chapter 1")
        directory.parentFile.mkdirs()
        val archive = File(directory.parentFile, "${directory.name}.cbz")
        val bytes = byteArrayOf(82, 73, 70, 70, 0, 0, 0, 0, 87, 69, 66, 80, 17)
        ZipOutputStream(archive.outputStream()).use { output ->
            listOf("002.webp", "001.webp").forEach { name ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        val context = DesktopReaderChapterContext(
            chapterId = 1L, sourceId = 42L, chapterUrl = "/chapter/1", mangaTitle = "Manga",
            chapterTitle = "Chapter 1", chapterNumber = 1.0, chapterIndex = 0, initialPage = 0, wasRead = false,
        )
        DesktopReaderContentAdapter().use { adapter ->
            adapter.reserveChapter(1L, 7L)
            val pages = DesktopReaderChapterContentPort(
                context,
                provider,
                FakeDesktopSourceManager(emptyList()),
                contentAdapter = adapter,
                leaseGeneration = 7L,
            ).loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(1L), 7L))
            assertEquals("MinizZipArchiveLease", adapter.archiveBackend(1L))
            assertEquals(7L, adapter.archiveLeaseGeneration(1L))
            DesktopReaderContentAdapter(preferNativeZip = true).use { reference ->
                assertEquals(reference.archiveDescriptors(1L, archive), pages)
            }
            val destination = tempDir.resolve("target.webp")
            adapter.copyArchivePage(1L, 1, pages[1].url, destination)
            assertArrayEquals(bytes, destination.readBytes())
        }
    }

    @Test
    fun `real download runtime publishes accepted nonzero generation backed by miniz`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("runtime-downloads"))
        val directory = provider.chapterDownloadDir(42L, "Manga", "Chapter 1")
        directory.parentFile.mkdirs()
        val archive = File(directory.parentFile, "${directory.name}.cbz")
        writeArchive(archive, listOf("001.webp" to image(7)))
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = provider,
            sourceManager = FakeDesktopSourceManager(emptyList()),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("runtime-cache"),
        )
        val runtime = factory.createRuntime(context(), this)
        try {
            advanceUntilIdle()
            val loaded = runtime.session.state.value.snapshot
            assertEquals(mihon.domain.reader.session.ReaderChapterLoadState.Loaded, loaded.activeChapter.loadState)
            assertTrue(loaded.generation > 0L)
            assertEquals("MinizZipArchiveLease", runtime.contentAdapter.archiveBackend(1L))
            assertTrue(requireNotNull(runtime.contentAdapter.archiveLeaseGeneration(1L)) > 0L)
            assertEquals(1, loaded.activeChapter.pages.size)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `store deflate and archive order retain every original descriptor and byte`() = runBlocking {
        for (method in listOf(ZipEntry.STORED, ZipEntry.DEFLATED)) {
            val archive = tempDir.resolve("method-$method.cbz")
            writeArchive(
                archive,
                listOf(
                    "002.webp" to image(2),
                    "ComicInfo.xml" to "<ComicInfo/>".toByteArray(),
                    "001.webp" to image(1),
                ),
                method,
            )
            assertParity(archive, "MinizZipArchiveLease")
        }
    }

    @Test
    fun `unsupported names duplicate metadata directories and unicode extras use existing reader`() = runBlocking {
        val names = listOf(
            listOf("1.webp", "02.webp"),
            listOf("001.png"),
            listOf("001.WEBP"),
            listOf("pages/001.webp"),
            listOf("001.webp", "001.webp"),
            listOf("ComicInfo.xml", "ComicInfo.xml", "001.webp"),
            listOf("中文.webp"),
            listOf("folder/", "001.webp"),
        )
        names.forEachIndexed { index, entries ->
            val archive = tempDir.resolve("fallback-$index.cbz")
            writeArchive(archive, entries.mapIndexed { i, name -> name to image(i) })
            assertParity(archive, "CommonsZipArchiveLease")
        }
        val extra = tempDir.resolve("unknown-extra.cbz")
        ZipArchiveOutputStream(extra).use { output ->
            output.putArchiveEntry(
                ZipArchiveEntry("001.webp").apply {
                    addExtraField(
                        UnrecognizedExtraField().apply {
                            headerId = ZipShort(0x7075)
                            setLocalFileDataData(byteArrayOf(1, 2, 3, 4, 5))
                            setCentralDirectoryData(byteArrayOf(1, 2, 3, 4, 5))
                        },
                    )
                },
            )
            output.write(image(5))
            output.closeArchiveEntry()
        }
        assertParity(extra, "CommonsZipArchiveLease")
    }

    @Test
    fun `numeric names above nineteen digits retain Commons unsigned ordering`() = runBlocking {
        val archive = tempDir.resolve("unsigned-overflow.cbz")
        writeArchive(archive, listOf("18446744073709551616.webp" to image(1), "00000000000000000002.webp" to image(2)))
        assertParity(archive, "CommonsZipArchiveLease")
    }

    @Test
    fun `image signatures in ComicInfo retain the page and unsupported headers stay metadata`() = runBlocking {
        val signatures = listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
            byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10),
            "GIF87a".toByteArray(), "GIF89a".toByteArray(), image(7),
            byteArrayOf(0xFF.toByte(), 10),
            byteArrayOf(0, 0, 0, 12, 74, 88, 76, 32, 13, 10, 0x87.toByte(), 10),
            byteArrayOf(0, 0, 0, 12, 106, 80, 32, 32, 13, 10, 0x87.toByte(), 10),
            byteArrayOf(0xFF.toByte(), 79, 0xFF.toByte(), 81),
        ) + listOf(
            "avif", "avis", "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1",
        ).flatMap { brand ->
            listOf(8, 16, 20, 24, 28).map { offset ->
                ByteArray(32).also { header ->
                    "ftyp".toByteArray().copyInto(header, 4)
                    brand.toByteArray().copyInto(header, offset)
                }
            }
        }
        val nonImages = listOf("<ComicInfo/>".toByteArray(), "RIFFxxxxWAVE".toByteArray(), byteArrayOf())
        for (method in listOf(ZipEntry.STORED, ZipEntry.DEFLATED)) {
            (signatures + nonImages).forEachIndexed { index, header ->
                val archive = tempDir.resolve("comic-info-$method-$index.cbz")
                writeArchive(archive, listOf("001.webp" to image(1), "ComicInfo.xml" to header), method)
                val isImage = DesktopReaderImageSignature.matches(header.inputStream())
                assertParity(archive, if (isImage) "CommonsZipArchiveLease" else "MinizZipArchiveLease")
            }
        }
    }

    @Test
    fun `central directory allocation exceeding budget falls back without retaining a handle`() = runBlocking {
        val archive = tempDir.resolve("large-directory.cbz")
        ZipArchiveOutputStream(archive).use { output ->
            repeat(280) { index ->
                output.putArchiveEntry(
                    ZipArchiveEntry("%03d.webp".format(index)).apply {
                        comment = "x".repeat(60_000)
                        size = image(index).size.toLong()
                        crc = CRC32().apply { update(image(index)) }.value
                    },
                )
                output.write(image(index))
                output.closeArchiveEntry()
            }
        }
        val handle = DesktopWindowsMinizNative.open(archive.absolutePath)
        try {
            assertEquals(0L, handle, "Native initialization must reject the directory before unbounded allocation")
        } finally {
            if (handle != 0L) DesktopWindowsMinizNative.close(handle)
        }
        assertParity(archive, "CommonsZipArchiveLease")
        val ordinary = tempDir.resolve("after-budget.cbz")
        writeArchive(ordinary, listOf("001.webp" to image(1)))
        assertParity(ordinary, "MinizZipArchiveLease")
    }

    @Test
    fun `accepted near budget directory retains space for deflate page extraction`() = runBlocking {
        val archive = tempDir.resolve("near-budget-directory.cbz")
        val bytes = ByteArray(256 * 1024).also { java.util.Random(19).nextBytes(it) }
        image(1).copyInto(bytes)
        ZipArchiveOutputStream(archive).use { output ->
            repeat(279) { index ->
                val page = if (index == 0) bytes else image(index)
                output.putArchiveEntry(
                    ZipArchiveEntry("%03d.webp".format(index)).apply {
                        comment = "x".repeat(60_000)
                        size = page.size.toLong()
                        crc = CRC32().apply { update(page) }.value
                    },
                )
                output.write(page)
                output.closeArchiveEntry()
            }
        }
        assertParity(archive, "MinizZipArchiveLease")
    }

    @Test
    fun `not started loading and failed library fall back immediately and ready later selects miniz`() = runBlocking {
        val archive = tempDir.resolve("loading.cbz")
        writeArchive(archive, listOf("001.webp" to image(1)))
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val library = DesktopWindowsMinizLibrary(resourceDirectory = {
            File(System.getProperty("compose.application.resources.dir"))
        }, load = {
            entered.countDown()
            check(finish.await(10, TimeUnit.SECONDS))
        })
        DesktopReaderContentAdapter(minizLibrary = library).use { adapter ->
            adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true)
            assertEquals("CommonsZipArchiveLease", adapter.archiveBackend(1L))
            library.startAsync()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            try {
                adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true)
                assertEquals("CommonsZipArchiveLease", adapter.archiveBackend(1L))
            } finally {
                finish.countDown()
            }
            awaitLoaded(library)
            adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true)
            assertEquals("MinizZipArchiveLease", adapter.archiveBackend(1L))
        }
        val unavailable = DesktopWindowsMinizLibrary(resourceDirectory = { tempDir.resolve("missing") })
        unavailable.startAsync()
        awaitLoaded(unavailable)
        assertEquals(DesktopWindowsMinizLibrary.State.UNAVAILABLE, unavailable.state)
        DesktopReaderContentAdapter(minizLibrary = unavailable).use { adapter ->
            adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true)
            assertEquals("CommonsZipArchiveLease", adapter.archiveBackend(1L))
        }
    }

    @Test
    fun `ordinary and explicit native callers retain their original selection`() {
        val archive = tempDir.resolve("ordinary.cbz")
        writeArchive(archive, listOf("001.webp" to image(1)))
        DesktopReaderContentAdapter().use { adapter ->
            adapter.archiveDescriptors(1L, archive)
            assertEquals("CommonsZipArchiveLease", adapter.archiveBackend(1L))
        }
        DesktopReaderContentAdapter(preferNativeZip = true).use { adapter ->
            adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true)
            assertEquals("SevenZipArchiveLease", adapter.archiveBackend(1L))
        }
    }

    @Test
    fun `same length and mtime replacement keeps old handle and rejects stale identity after reopen`() = runBlocking {
        val archive = tempDir.resolve("replace.cbz")
        val replacement = tempDir.resolve("replacement.cbz")
        writeArchive(archive, listOf("001.webp" to image(1)), ZipEntry.STORED)
        writeArchive(replacement, listOf("001.webp" to image(2)), ZipEntry.STORED)
        assertEquals(archive.length(), replacement.length())
        replacement.setLastModified(archive.lastModified())
        DesktopReaderContentAdapter().use { adapter ->
            adapter.reserveChapter(1L, 1L)
            val old = adapter.archiveDescriptors(1L, archive, leaseGeneration = 1L, preferWindowsMiniz = true).single()
            Files.move(replacement.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val oldOutput = tempDir.resolve("old.bin")
            adapter.copyArchivePage(1L, 0, old.url, oldOutput)
            assertArrayEquals(image(1), oldOutput.readBytes())
            adapter.reserveChapter(1L, 2L)
            val current = adapter.archiveDescriptors(
                1L,
                archive,
                leaseGeneration = 2L,
                preferWindowsMiniz = true,
            ).single()
            assertTrue(old.url != current.url)
            val stale = runCatching { adapter.copyArchivePage(1L, 0, old.url, oldOutput) }
            assertTrue(stale.isFailure)
            adapter.copyArchivePage(1L, 0, current.url, oldOutput)
            assertArrayEquals(image(2), oldOutput.readBytes())
        }
    }

    @Test
    fun `release during in flight copy waits for operation before closing old handle`() = runBlocking {
        val archive = tempDir.resolve("inflight.cbz")
        writeArchive(archive, listOf("001.webp" to image(9)))
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val copying = AtomicBoolean(false)
        val probe = object : DesktopReaderArchiveOperationProbe {
            override fun onStart() {
                if (copying.get()) {
                    entered.countDown()
                    check(finish.await(10, TimeUnit.SECONDS))
                }
            }
            override fun onFinish() = Unit
        }
        DesktopReaderContentAdapter(archiveOperationProbe = probe).use { adapter ->
            adapter.reserveChapter(1L, 1L)
            val page = adapter.archiveDescriptors(1L, archive, leaseGeneration = 1L, preferWindowsMiniz = true).single()
            copying.set(true)
            val destination = tempDir.resolve("inflight.webp")
            val task = async(Dispatchers.IO) { adapter.copyArchivePage(1L, 0, page.url, destination) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                adapter.releaseChapter(1L, 1L)
                assertFalse(adapter.hasArchiveLease(1L))
            } finally {
                finish.countDown()
            }
            task.await()
            assertArrayEquals(image(9), destination.readBytes())
        }
    }

    @Test
    fun `CRC and destination write failures remove uncommitted page`() = runBlocking {
        val archive = tempDir.resolve("wrong-crc.cbz")
        val bytes = image(3)
        ZipArchiveOutputStream(archive).use { output ->
            output.addRawArchiveEntry(
                ZipArchiveEntry("001.webp").apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = 0L
                },
                bytes.inputStream(),
            )
        }
        DesktopReaderContentAdapter().use { adapter ->
            val page = adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true).single()
            assertEquals("MinizZipArchiveLease", adapter.archiveBackend(1L))
            val destination = tempDir.resolve("bad.webp")
            assertTrue(runCatching { adapter.copyArchivePage(1L, 0, page.url, destination) }.isFailure)
            assertFalse(destination.exists())
        }
        writeArchive(archive, listOf("001.webp" to bytes))
        DesktopReaderContentAdapter().use { adapter ->
            val page = adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true).single()
            val parentFile = tempDir.resolve("file-parent").apply { writeText("occupied") }
            val destination = parentFile.resolve("page.webp")
            assertTrue(runCatching { adapter.copyArchivePage(1L, 0, page.url, destination) }.isFailure)
            assertFalse(destination.exists())
        }
    }

    @Test
    fun `streaming copy accepts image above prototype heap size limit`() = runBlocking {
        val archive = tempDir.resolve("large.cbz")
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("001.webp"))
            repeat(65) {
                output.write(chunk)
                digest.update(chunk)
            }
            output.closeEntry()
        }
        DesktopReaderContentAdapter().use { adapter ->
            val page = adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true).single()
            assertEquals("MinizZipArchiveLease", adapter.archiveBackend(1L))
            val destination = tempDir.resolve("large.webp")
            assertEquals(65L * 1024 * 1024, adapter.copyArchivePage(1L, 0, page.url, destination))
            val actual = MessageDigest.getInstance("SHA-256")
            destination.inputStream().use { input ->
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    actual.update(chunk, 0, count)
                }
            }
            assertArrayEquals(digest.digest(), actual.digest())
        }
    }

    @Test
    fun `native callback observes interruption and adapter removes temporary destination`() = runBlocking {
        val archive = tempDir.resolve("cancelled.cbz")
        writeArchive(archive, listOf("001.webp" to image(4)))
        val copying = AtomicBoolean(false)
        val probe = object : DesktopReaderArchiveOperationProbe {
            override fun onStart() {
                if (copying.get()) Thread.currentThread().interrupt()
            }
            override fun onFinish() = Unit
        }
        DesktopReaderContentAdapter(archiveOperationProbe = probe).use { adapter ->
            val page = adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true).single()
            copying.set(true)
            val destination = tempDir.resolve("cancelled.webp")
            val failure = async(Dispatchers.IO) {
                try {
                    runCatching { adapter.copyArchivePage(1L, 0, page.url, destination) }.exceptionOrNull()
                } finally {
                    Thread.interrupted()
                }
            }.await()
            assertTrue(failure is kotlinx.coroutines.CancellationException, "$failure")
            assertFalse(destination.exists())
        }
    }

    @Test
    fun `native registry rejects closed handles and invalid page indices`() {
        val archive = tempDir.resolve("handle.cbz")
        writeArchive(archive, listOf("001.webp" to image(4)))
        val handle = DesktopWindowsMinizNative.open(archive.absolutePath)
        assertTrue(handle > 0L)
        try {
            assertTrue(
                runCatching {
                    DesktopWindowsMinizNative.copy(handle, -1, tempDir.resolve("bad.webp").absolutePath)
                }.isFailure,
            )
        } finally {
            DesktopWindowsMinizNative.close(handle)
        }
        DesktopWindowsMinizNative.close(handle)
        assertTrue(runCatching { DesktopWindowsMinizNative.snapshot(handle) }.exceptionOrNull() is java.io.IOException)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MIHON_CBZ_DIAGNOSTIC", matches = ".+")
    fun `real archive keeps all 187 descriptors and target SHA`() = runBlocking {
        val archive = File(requireNotNull(System.getenv("MIHON_CBZ_DIAGNOSTIC")))
        DesktopReaderContentAdapter().use { adapter ->
            adapter.reserveChapter(1L, 3L)
            val pages = adapter.archiveDescriptors(1L, archive, leaseGeneration = 3L, preferWindowsMiniz = true)
            assertEquals("MinizZipArchiveLease", adapter.archiveBackend(1L))
            assertEquals(187, pages.size)
            DesktopReaderContentAdapter(preferNativeZip = true).use { reference ->
                assertEquals(reference.archiveDescriptors(1L, archive), pages)
                val nativePage = tempDir.resolve("native-page.webp")
                val referencePage = tempDir.resolve("reference-page.webp")
                val archiveDigest = MessageDigest.getInstance("SHA-256")
                var totalBytes = 0L
                pages.forEachIndexed { index, page ->
                    totalBytes += adapter.copyArchivePage(1L, index, page.url, nativePage)
                    reference.copyArchivePage(1L, index, page.url, referencePage)
                    val actual = fileDigest(nativePage)
                    assertArrayEquals(fileDigest(referencePage), actual, "Real archive page $index")
                    archiveDigest.update(actual)
                }
                println(
                    "MINIZ_REAL_ARCHIVE verified=187/187 totalBytes=$totalBytes imageSha256Chain=" +
                        archiveDigest.digest().joinToString("") { "%02x".format(it) },
                )
            }
            val destination = tempDir.resolve("map.webp")
            adapter.copyArchivePage(1L, 4, pages[4].url, destination)
            assertEquals(
                "3b43ed0beb3f6f1b832e57ac5ad7c830c00850c8b79fa0c9a708ebf08ccbce14",
                MessageDigest.getInstance("SHA-256").digest(destination.readBytes()).joinToString("") {
                    "%02x".format(it)
                },
            )
        }
    }

    private suspend fun assertParity(archive: File, backend: String) {
        DesktopReaderContentAdapter().use { adapter ->
            val actual = adapter.archiveDescriptors(1L, archive, preferWindowsMiniz = true)
            assertEquals(backend, adapter.archiveBackend(1L))
            DesktopReaderContentAdapter(preferNativeZip = backend == "MinizZipArchiveLease").use { reference ->
                val expected = reference.archiveDescriptors(1L, archive)
                assertEquals(expected, actual, archive.name)
                actual.indices.forEach { index ->
                    val a = tempDir.resolve("a-$index.bin")
                    val b = tempDir.resolve("b-$index.bin")
                    adapter.copyArchivePage(1L, index, actual[index].url, a)
                    reference.copyArchivePage(1L, index, expected[index].url, b)
                    assertArrayEquals(b.readBytes(), a.readBytes())
                }
            }
        }
    }

    private fun writeArchive(file: File, entries: List<Pair<String, ByteArray>>, method: Int = ZipEntry.DEFLATED) {
        ZipArchiveOutputStream(file).use { output ->
            entries.forEach { (name, bytes) ->
                output.putArchiveEntry(
                    ZipArchiveEntry(name).apply {
                        this.method = method
                        time = 1_700_000_000_000L
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    },
                )
                output.write(bytes)
                output.closeArchiveEntry()
            }
        }
    }

    private fun context() = DesktopReaderChapterContext(
        chapterId = 1L, sourceId = 42L, chapterUrl = "/chapter/1", mangaTitle = "Manga",
        chapterTitle = "Chapter 1", chapterNumber = 1.0, chapterIndex = 0, initialPage = 0, wasRead = false,
    )

    private fun image(marker: Int) = byteArrayOf(82, 73, 70, 70, 0, 0, 0, 0, 87, 69, 66, 80, marker.toByte())

    private fun fileDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private suspend fun awaitLoaded(library: DesktopWindowsMinizLibrary) {
        withTimeout(10_000) { while (library.state == DesktopWindowsMinizLibrary.State.LOADING) delay(10) }
    }
}
