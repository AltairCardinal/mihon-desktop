package mihon.desktop.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

class DesktopReaderCbzFastPathTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `opening standard cbz does not initialize native archive library`() {
        val archive = tempDir.resolve("standard.cbz")
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("001.png"))
            it.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
            it.closeEntry()
        }
        // A separate loader gives the real production adapter fresh native-backend state even
        // when another test in this worker has already opened RAR/EPUB through SevenZip.
        val urls = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<java.net.URLClassLoader>()
            .flatMap { it.getURLs().asSequence() }
            .plus(sequenceOf(
                DesktopReaderContentAdapter::class.java,
                net.sf.sevenzipjbinding.SevenZip::class.java,
                org.apache.commons.compress.archivers.zip.ZipFile::class.java,
                org.apache.commons.io.build.AbstractStreamBuilder::class.java,
                org.apache.commons.lang3.ArrayUtils::class.java,
                org.apache.commons.codec.digest.DigestUtils::class.java,
                mihon.domain.reader.session.ReaderPageDescriptor::class.java,
                kotlinx.coroutines.CoroutineScope::class.java,
                org.jsoup.Jsoup::class.java,
                kotlin.Unit::class.java,
            ).mapNotNull { it.protectionDomain?.codeSource?.location })
            .distinct().toList().toTypedArray()
        java.net.URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val native = Class.forName("net.sf.sevenzipjbinding.SevenZip", true, loader)
            val initialized = native.getMethod("isInitializedSuccessfully")
            assertFalse(initialized.invoke(null) as Boolean)
            val type = Class.forName("mihon.desktop.reader.DesktopReaderContentAdapter", true, loader)
            val adapter = type.getConstructor().newInstance() as AutoCloseable
            adapter.use {
                val pages = type.getMethod(
                    "archiveDescriptors", java.lang.Long.TYPE, File::class.java,
                    java.lang.Boolean.TYPE, java.lang.Long.TYPE,
                ).invoke(adapter, 1L, archive, false, 0L) as List<*>
                assertEquals(1, pages.size)
                assertFalse(initialized.invoke(null) as Boolean, "Ordinary ZIP must not initialize the native backend")
            }
        }
    }

    @Test
    fun `zip fast path preserves native opaque identity and exact duplicate entry bytes`() = runBlocking {
        for (method in listOf(ZipEntry.STORED, ZipEntry.DEFLATED)) {
            val archive = tempDir.resolve("parity-$method.cbz")
            ZipArchiveOutputStream(archive).use { output ->
                val entries = listOf(
                    "folder/" to byteArrayOf(),
                    "folder/10.png" to image(10),
                    "folder/2.png" to image(2),
                    "folder/2.png" to image(22),
                    "中文.webp" to image(3),
                    "extensionless" to image(4),
                )
                entries.forEach { (name, bytes) ->
                    val entry = ZipArchiveEntry(name).apply {
                        this.method = method
                        size = bytes.size.toLong()
                        crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    }
                    output.putArchiveEntry(entry)
                    output.write(bytes)
                    output.closeArchiveEntry()
                }
            }
            assertParity(archive, verifyAllBytes = true)
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MIHON_CBZ_DIAGNOSTIC", matches = ".+")
    fun `real cbz preserves all native content identities and restored page bytes`() = runBlocking {
        assertParity(File(requireNotNull(System.getenv("MIHON_CBZ_DIAGNOSTIC"))), verifyAllBytes = false)
    }

    @Test
    fun `readable bytes with incorrect zip crc keep native failure or tolerance semantics`() = runBlocking {
        val archive = tempDir.resolve("wrong-crc.cbz")
        val bytes = image(7)
        ZipArchiveOutputStream(archive).use { output ->
            val entry = ZipArchiveEntry("001.png").apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = 0L
            }
            output.addRawArchiveEntry(entry, bytes.inputStream())
        }
        val results = listOf(true, false).map { native ->
            DesktopReaderContentAdapter(preferNativeZip = native).use { adapter ->
                val page = adapter.archiveDescriptors(42, archive).single()
                val destination = tempDir.resolve("crc-$native.png")
                val copied = runCatching { adapter.copyArchivePage(42, 0, page.url, destination) }
                if (copied.isSuccess) destination.readBytes() else null
            }
        }
        assertArrayEquals(results[0], results[1], "Do not weaken or tighten the established native CRC tolerance")
        println("CRC_PARITY nativeAccepted=${results[0] != null} fastAccepted=${results[1] != null}")
    }

    @Test
    fun `zip features outside the fast path keep native metadata and extraction`() = runBlocking {
        val archive = tempDir.resolve("bzip2-metadata.cbz")
        val metadata = "Reader metadata".toByteArray()
        val compressed = java.io.ByteArrayOutputStream().also { bytes ->
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(bytes).use {
                it.write(metadata)
            }
        }.toByteArray()
        ZipArchiveOutputStream(archive).use { output ->
            val extra = ZipArchiveEntry("metadata.txt").apply {
                method = 12
                size = metadata.size.toLong()
                compressedSize = compressed.size.toLong()
                crc = java.util.zip.CRC32().apply { update(metadata) }.value
            }
            output.addRawArchiveEntry(extra, compressed.inputStream())
            output.putArchiveEntry(ZipArchiveEntry("001.png"))
            output.write(image(5))
            output.closeArchiveEntry()
        }
        assertParity(archive, verifyAllBytes = true)
    }

    @Test
    fun `noncanonical zip names keep the original native identity and extraction`() = runBlocking {
        val names = listOf(
            "pages\\001.png", "/001.png", "C:/001.png", "./001.png", "pages/../001.png", "pages//001.png",
        )
        names.forEachIndexed { index, name ->
            val archive = tempDir.resolve("noncanonical-$index.cbz")
            ZipOutputStream(archive.outputStream()).use { output ->
                output.putNextEntry(ZipEntry(name))
                output.write(image(index))
                output.closeEntry()
            }
            assertParity(archive, verifyAllBytes = true)
        }
    }

    private suspend fun assertParity(archive: File, verifyAllBytes: Boolean) {
        DesktopReaderContentAdapter(preferNativeZip = true).use { native ->
            DesktopReaderContentAdapter().use { fast ->
                val expected = native.archiveDescriptors(42, archive)
                val actual = fast.archiveDescriptors(42, archive)
                assertEquals(expected.map { it.url }, actual.map { it.url }, "Opaque identities must survive backend selection")
                val indices = if (verifyAllBytes) expected.indices else listOf(4)
                for (index in indices) {
                    val oldFile = tempDir.resolve("native-$index.bin")
                    val newFile = tempDir.resolve("fast-$index.bin")
                    native.copyArchivePage(42, index, expected[index].url, oldFile)
                    fast.copyArchivePage(42, index, actual[index].url, newFile)
                    assertArrayEquals(oldFile.readBytes(), newFile.readBytes(), "Page $index must retain physical entry identity")
                }
            }
        }
    }

    private fun image(marker: Int) = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, marker.toByte(),
    )

}
