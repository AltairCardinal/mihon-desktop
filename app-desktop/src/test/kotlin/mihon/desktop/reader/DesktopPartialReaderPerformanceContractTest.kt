package mihon.desktop.reader

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import mihon.desktop.download.DirectPartialPageReadLeaseSource
import mihon.desktop.download.DownloadIoEvent
import mihon.desktop.download.DownloadIoOperation
import mihon.desktop.download.DownloadIoProbe
import mihon.domain.reader.partial.PartialReaderPageCandidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopPartialReaderPerformanceContractTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `disabled operation probe short circuits before dispatch while the real local copy still succeeds`() = runTest {
        val source = directory.resolve("downloads/chapter_tmp/001.jpg").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val destination = directory.resolve("encoded/001.jpg")
        val disabledProbe = object : DownloadIoProbe {
            override val enabled: Boolean = false
            override fun onIo(event: DownloadIoEvent) = error("Disabled probe must not receive or construct an event")
        }

        val copied = DesktopReaderPartialPageFileCopyPort(
            leaseSource = DirectPartialPageReadLeaseSource,
            ioProbe = disabledProbe,
        ).copy(candidate(source), destination)

        assertEquals(source.length(), copied)
        assertEquals(source.readBytes().toList(), destination.readBytes().toList())
    }

    @Test
    fun `enabled probe observes one bounded probe open and sequential copy outside every download lock`() = runTest {
        val source = directory.resolve("downloads/chapter_tmp/001.jpg").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val events = CopyOnWriteArrayList<DownloadIoEvent>()
        val destination = directory.resolve("encoded/001.jpg")

        DesktopReaderPartialPageFileCopyPort(
            leaseSource = DirectPartialPageReadLeaseSource,
            ioProbe = DownloadIoProbe(events::add),
        ).copy(candidate(source), destination)

        assertEquals(
            listOf(
                DownloadIoOperation.PARTIAL_PAGE_PROBE,
                DownloadIoOperation.PARTIAL_PAGE_OPEN,
                DownloadIoOperation.PARTIAL_PAGE_COPY,
            ),
            events.map(DownloadIoEvent::operation),
        )
        assertTrue(events.all { event ->
            with(event.locks) {
                !queueStateLocked && !indexLocked && !coordinatorLocked && !lifecycleLocked
            }
        })
        assertFalse(destination.readBytes().isEmpty())
    }

    private fun candidate(source: File) = PartialReaderPageCandidate(
        attemptGeneration = 1L,
        readerOrdinal = 0,
        sourcePageIndex = 0,
        opaqueLocation = source.absolutePath,
        committedRevision = 1L,
    )
}
