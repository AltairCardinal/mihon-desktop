package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import mihon.core.archive.ArchiveEntry
import mihon.core.archive.ArchiveReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.util.system.ImageUtil
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReaderImagePageListProductionWiringTest {

    @Test
    fun `directory loader uses upstream probe policy and local natural order`() = runTest {
        withSuccessfulImageProbe {
            val file10 = uniFile("10.jpg", marker = 10)
            val file2 = uniFile("2.jpg", marker = 2)
            val unknown3 = uniFile("3.bin", marker = 3)
            val empty4 = uniFile("4.jpg", marker = null)
            val jp2 = uniFile("5.jp2", marker = 5)
            val jpx = uniFile("6.jpx", marker = 6)
            val nullName = uniFile(null, marker = 99)
            val directory = mockk<UniFile>()
            every { directory.listFiles() } returns arrayOf(file10, jpx, nullName, empty4, unknown3, jp2, file2)

            val pages = DirectoryPageLoader(directory).getPages()

            assertEquals(6, pages.size)
            assertTrue(pages.all { it.status == Page.State.Ready })
            verify(exactly = 0) { file10.openInputStream() }
            verify(exactly = 0) { file2.openInputStream() }
            verify(exactly = 0) { empty4.openInputStream() }
            verify(exactly = 0) { jp2.openInputStream() }
            verify(exactly = 0) { jpx.openInputStream() }
            verify(exactly = 1) { unknown3.openInputStream() }
            verify(exactly = 0) { nullName.openInputStream() }
            assertEquals(listOf(2, 3, null, 5, 6, 10), pages.map(::streamMarker))
        }
    }

    @Test
    fun `archive loader uses upstream probe policy and local natural order`() = runTest {
        withSuccessfulImageProbe {
            val entries = listOf(
                ArchiveEntry("10.jpg", isFile = true),
                ArchiveEntry("folder", isFile = false),
                ArchiveEntry("4.jpg", isFile = true),
                ArchiveEntry("3.bin", isFile = true),
                ArchiveEntry("5.jp2", isFile = true),
                ArchiveEntry("6.jpx", isFile = true),
                ArchiveEntry("2.jpg", isFile = true),
            )
            val markers = mapOf(
                "10.jpg" to 10,
                "2.jpg" to 2,
                "3.bin" to 3,
                "4.jpg" to null,
                "5.jp2" to 5,
                "6.jpx" to 6,
            )
            val reader = mockk<ArchiveReader>()
            every { reader.useEntries<List<ReaderPage>>(any()) } answers {
                firstArg<(Sequence<ArchiveEntry>) -> List<ReaderPage>>()(entries.asSequence())
            }
            every { reader.getInputStream(any()) } answers {
                markerStream(markers.getValue(firstArg()))
            }

            val pages = ArchivePageLoader(reader).getPages()

            assertEquals(6, pages.size)
            assertTrue(pages.all { it.status == Page.State.Ready })
            verify(exactly = 0) { reader.getInputStream("10.jpg") }
            verify(exactly = 0) { reader.getInputStream("2.jpg") }
            verify(exactly = 0) { reader.getInputStream("4.jpg") }
            verify(exactly = 0) { reader.getInputStream("5.jp2") }
            verify(exactly = 0) { reader.getInputStream("6.jpx") }
            verify(exactly = 1) { reader.getInputStream("3.bin") }
            verify(exactly = 0) { reader.getInputStream("folder") }
            assertEquals(listOf(2, 3, null, 5, 6, 10), pages.map(::streamMarker))
        }
    }

    private fun uniFile(name: String?, marker: Int?): UniFile = mockk<UniFile>().also { file ->
        every { file.name } returns name
        every { file.isDirectory } returns false
        every { file.isFile } returns true
        every { file.openInputStream() } answers { markerStream(marker) }
    }

    private fun markerStream(marker: Int?): InputStream = ByteArrayInputStream(
        marker?.let { byteArrayOf(it.toByte()) } ?: byteArrayOf(),
    )

    private fun streamMarker(page: ReaderPage): Int? = page.stream
        ?.invoke()
        ?.use { input -> input.readBytes().lastOrNull()?.toInt() }

    private inline fun <T> withSuccessfulImageProbe(block: () -> T): T {
        mockkObject(ImageUtil)
        every { ImageUtil.findImageType(any<() -> InputStream>()) } answers {
            firstArg<() -> InputStream>()().use { }
            ImageUtil.ImageType.JPEG
        }
        return try {
            block()
        } finally {
            unmockkObject(ImageUtil)
        }
    }
}
