package mihon.desktop.test.http

import java.io.File
import mihon.desktop.source.LocalChapterEntry
import mihon.desktop.source.LocalSourceReader
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderTestModeControllerTest {

    @Test
    fun `test mode fixtures are real readable directories and CBZ archives`() {
        ReaderTestModeController().use { controller ->
            val directory = controller.createFixture(ReaderTestFixtureKind.DOWNLOADED_DIRECTORY, pageCount = 3)
            val archive = controller.createFixture(ReaderTestFixtureKind.CBZ, pageCount = 2)

            assertEquals(3, readPages(directory).size)
            assertEquals(2, readPages(archive).size)
            assertTrue(File(directory.localChapterPath).isDirectory)
            assertTrue(File(archive.localChapterPath).isFile)
        }
    }

    @Test
    fun `bridge is disabled outside test mode and exposes production events while installed`() {
        val controller = ReaderTestModeController()
        try {
            assertFalse(ReaderIoTestModeBridge.enabled)
            ReaderIoTestModeBridge.install(controller)
            assertTrue(ReaderIoTestModeBridge.enabled)

            ReaderIoTestModeBridge.record(
                ReaderIoEvent(
                    type = ReaderIoEventType.FIRST_PAGE_PRESENTED,
                    monotonicNanos = 9L,
                    chapterId = ReaderChapterId(7L),
                    pageId = ReaderPageId(ReaderChapterId(7L), 0),
                    generation = 1L,
                    purpose = ReaderIoPurpose.FIRST_PRESENTATION,
                ),
            )

            assertEquals(listOf("FIRST_PAGE_PRESENTED"), controller.snapshot().map(ReaderIoTestEvent::type))
        } finally {
            ReaderIoTestModeBridge.clear(controller)
            controller.close()
        }
        assertFalse(ReaderIoTestModeBridge.enabled)
    }

    @Test
    fun `bound runtime cannot publish late events into a new scenario`() {
        ReaderTestModeController().use { controller ->
            ReaderIoTestModeBridge.install(controller)
            try {
                val oldRuntimeProbe = ReaderIoTestModeBridge.bind()
                controller.beginScenario()

                oldRuntimeProbe.record(firstPresentationEvent())
                ReaderIoTestModeBridge.bind().record(firstPresentationEvent())

                assertEquals(1, controller.snapshot().size)
            } finally {
                ReaderIoTestModeBridge.clear(controller)
            }
        }
    }

    private fun firstPresentationEvent() = ReaderIoEvent(
        type = ReaderIoEventType.FIRST_PAGE_PRESENTED,
        monotonicNanos = 9L,
        chapterId = ReaderChapterId(7L),
        pageId = ReaderPageId(ReaderChapterId(7L), 0),
        generation = 1L,
        purpose = ReaderIoPurpose.FIRST_PRESENTATION,
    )

    private fun readPages(fixture: ReaderTestFixture) = LocalSourceReader.readChapter(
        LocalChapterEntry("Fixture", File(fixture.localChapterPath)),
    )
}
