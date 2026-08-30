package mihon.desktop.test.http

import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import mihon.desktop.download.CbzCreator
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.source.LocalChapterEntry
import mihon.desktop.source.LocalSourceReader
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReaderTestModeControllerTest {

    @TempDir
    lateinit var tempDirectory: Path

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

    @Test
    fun `close deletes only owned canonical fixtures and preserves preexisting siblings`() {
        val downloadRoot = tempDirectory.resolve("downloads")
        val provider = DesktopDownloadProvider(downloadRoot.toFile())
        val sentinelIdentity = DownloadChapterIdentity(
            sourceDisplayName = ReaderTestModeController.READER_TEST_SOURCE_NAME,
            mangaTitle = "Sentinel manga",
            chapterName = "Sentinel chapter",
            scanlator = null,
            chapterUrl = "/sentinel",
            disallowNonAsciiFilenames = false,
        )
        val canonicalSourceDirectory = provider.canonicalMangaDownloadDir(sentinelIdentity).parentFile.toPath()
        val siblingDirectory = canonicalSourceDirectory.resolve("preexisting-sibling-directory").createDirectories()
        val siblingFile = canonicalSourceDirectory.resolve("preexisting-sibling.txt")
        siblingFile.writeText("keep")

        val (directoryArtifact, cbzArtifact) =
            ReaderTestModeController(configuredDownloadProvider = provider).use { controller ->
                val directoryFixture = controller.prepareFixture(
                    spec = standardSpec(ReaderTestFixtureSource.DOWNLOADED_DIRECTORY),
                    mangaId = 101L,
                    chapterId = 201L,
                    chapterTitle = "Directory chapter",
                )
                val cbzFixture = controller.prepareFixture(
                    spec = standardSpec(ReaderTestFixtureSource.DOWNLOADED_CBZ),
                    mangaId = 102L,
                    chapterId = 202L,
                    chapterTitle = "CBZ chapter",
                )
                val directoryArtifact = provider.canonicalChapterDownloadDir(directoryFixture.identity())
                val cbzArtifact = CbzCreator.defaultOutputFile(provider.canonicalChapterDownloadDir(cbzFixture.identity()))

                assertTrue(directoryArtifact.isDirectory)
                assertTrue(cbzArtifact.isFile)
                directoryArtifact to cbzArtifact
            }

        assertFalse(directoryArtifact.exists())
        assertFalse(cbzArtifact.exists())
        assertTrue(siblingDirectory.toFile().isDirectory)
        assertEquals("keep", siblingFile.toFile().readText())
    }

    @Test
    fun `close reports owned CBZ deletion failure without deleting replacement content`() {
        val provider = DesktopDownloadProvider(tempDirectory.resolve("downloads-failure").toFile())
        val controller = ReaderTestModeController(configuredDownloadProvider = provider)
        val fixture = controller.prepareFixture(
            spec = standardSpec(ReaderTestFixtureSource.DOWNLOADED_CBZ),
            mangaId = 301L,
            chapterId = 401L,
            chapterTitle = "Deletion failure chapter",
        )
        val cbzArtifact = CbzCreator.defaultOutputFile(provider.canonicalChapterDownloadDir(fixture.identity()))
        assertTrue(cbzArtifact.delete())
        assertTrue(cbzArtifact.mkdir())
        val replacementSentinel = cbzArtifact.resolve("foreign-content.txt").apply { writeText("keep") }

        val failure = assertThrows(IllegalStateException::class.java) {
            controller.close()
        }

        assertTrue(failure.message.orEmpty().contains(cbzArtifact.absolutePath))
        assertEquals("keep", replacementSentinel.readText())
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

    private fun standardSpec(source: ReaderTestFixtureSource) = ReaderTestFixtureSpec(
        source = source,
        pageCount = 1,
        width = 16,
        height = 24,
        format = ReaderTestImageFormat.JPEG,
    )

    private fun ReaderTestFixtureDescriptor.identity() = DownloadChapterIdentity(
        sourceDisplayName = ReaderTestModeController.READER_TEST_SOURCE_NAME,
        mangaTitle = mangaTitle,
        chapterName = chapterTitle,
        scanlator = null,
        chapterUrl = chapterUrl,
        disallowNonAsciiFilenames = false,
    )
}
