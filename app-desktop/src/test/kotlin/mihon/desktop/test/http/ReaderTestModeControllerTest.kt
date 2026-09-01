package mihon.desktop.test.http

import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import mihon.desktop.download.CbzCreator
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DefaultDownloadFileOperations
import mihon.desktop.download.DownloadFileOperations
import mihon.desktop.download.DownloadIoEvent
import mihon.desktop.download.DownloadIoPageIdentity
import mihon.desktop.download.DownloadIoOperation
import mihon.desktop.download.DownloadLockState
import mihon.desktop.download.DownloadItem
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
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

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
            ReaderIoTestModeBridge.onIo(
                DownloadIoEvent(
                    operation = DownloadIoOperation.PARTIAL_PAGE_COPY,
                    locks = DownloadLockState(false, false, false, false),
                    page = DownloadIoPageIdentity(1L, 0, 0, 1L),
                ),
            )

            assertEquals(listOf("FIRST_PAGE_PRESENTED"), controller.snapshot().map(ReaderIoTestEvent::type))
            assertEquals(1, controller.partialPageCopyCount())
        } finally {
            ReaderIoTestModeBridge.clear(controller)
            controller.close()
        }
        assertFalse(ReaderIoTestModeBridge.enabled)
    }

    @Test
    fun `partial IO counters attribute the current page without counting nearby prefetch`() {
        ReaderTestModeController().use { controller ->
            controller.beginScenario(currentPageIndex = 0)
            listOf(0, 1).forEach { ordinal ->
                DownloadIoOperation.entries
                    .filter { it in setOf(
                        DownloadIoOperation.PARTIAL_PAGE_PROBE,
                        DownloadIoOperation.PARTIAL_PAGE_OPEN,
                        DownloadIoOperation.PARTIAL_PAGE_COPY,
                    ) }
                    .forEach { operation ->
                        controller.onIo(
                            DownloadIoEvent(
                                operation = operation,
                                locks = DownloadLockState(false, false, false, false),
                                page = DownloadIoPageIdentity(
                                    attemptGeneration = 7L,
                                    readerOrdinal = ordinal,
                                    sourcePageIndex = ordinal,
                                    committedRevision = ordinal + 1L,
                                ),
                            ),
                        )
                    }
            }

            assertEquals(0, controller.currentPageIndex())
            assertEquals(1, controller.partialPageProbeCount())
            assertEquals(1, controller.partialPageOpenCount())
            assertEquals(1, controller.partialPageCopyCount())
            assertEquals(2, controller.scenarioPartialPageCopyCount())
        }
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
    fun `partial fixture is built by the real manager and stops with indexed local pages plus missing online pages`() =
        runBlocking {
            val provider = DesktopDownloadProvider(tempDirectory.resolve("partial-fixture-downloads").toFile())
            lateinit var controller: ReaderTestModeController
            val manager = DesktopDownloadManager(
                provider = provider,
                httpClient = OkHttpClient(),
                workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                retryDelay = {},
                sourceResolver = { controller.onlineSource },
                fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                    override fun execute(client: OkHttpClient, url: String): Response = Response.Builder()
                        .request(Request.Builder().url(url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("GIF89aDATA".toResponseBody())
                        .build()
                },
            )
            controller = ReaderTestModeController(
                configuredDownloadProvider = provider,
                downloadManager = manager,
                baseUrl = "http://fixture.invalid",
            )
            manager.start()
            try {
                val fixture = controller.prepareFixtureAwait(
                    spec = ReaderTestFixtureSpec(
                        source = ReaderTestFixtureSource.PARTIAL_DOWNLOAD,
                        pageCount = 6,
                        width = 16,
                        height = 24,
                        format = ReaderTestImageFormat.JPEG,
                        partialPageCount = 2,
                    ),
                    mangaId = 101L,
                    chapterId = 201L,
                    chapterTitle = "Partial chapter",
                )
                val snapshot = checkNotNull(manager.snapshot(fixture.chapterId, fixture.identity()))

                assertEquals(2, snapshot.committedPages.size)
                assertEquals(snapshot.attemptGeneration, fixture.partialAttemptGeneration)
                assertEquals(
                    "http://fixture.invalid/test/reader/fixture-content/${fixture.token}/2.jpg",
                    controller.resolveOnlineImageUrl("${fixture.chapterUrl}/2"),
                )
            } finally {
                controller.close()
                manager.stopAndJoin()
            }
        }

    @Test
    fun `offline partial fixture seeds local pages before its active image endpoint goes offline`() = runBlocking {
        val provider = DesktopDownloadProvider(tempDirectory.resolve("offline-partial-downloads").toFile())
        lateinit var controller: ReaderTestModeController
        val manager = DesktopDownloadManager(
            provider = provider,
            httpClient = OkHttpClient(),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            retryDelay = {},
            sourceResolver = { controller.onlineSource },
            fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                override fun execute(client: OkHttpClient, url: String): Response {
                    val pageIndex = url.substringAfterLast('/').substringBefore('.').toInt()
                    val token = url.substringBeforeLast('/').substringAfterLast('/')
                    val bytes = controller.onlineImage(token, pageIndex)
                    return Response.Builder()
                        .request(Request.Builder().url(url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (bytes == null) 503 else 200)
                        .message(if (bytes == null) "Offline" else "OK")
                        .body((bytes ?: byteArrayOf()).toResponseBody())
                        .build()
                }
            },
        )
        controller = ReaderTestModeController(
            configuredDownloadProvider = provider,
            downloadManager = manager,
            baseUrl = "http://fixture.invalid",
        )
        manager.start()
        try {
            val fixture = withTimeout(2_000) {
                controller.prepareFixtureAwait(
                    spec = ReaderTestFixtureSpec(
                        source = ReaderTestFixtureSource.PARTIAL_DOWNLOAD,
                        pageCount = 6,
                        width = 16,
                        height = 24,
                        format = ReaderTestImageFormat.JPEG,
                        partialPageCount = 2,
                        offline = true,
                    ),
                    mangaId = 102L,
                    chapterId = 202L,
                    chapterTitle = "Offline partial chapter",
                )
            }

            assertEquals(2, checkNotNull(manager.snapshot(fixture.chapterId, fixture.identity())).committedPages.size)
            assertEquals(null, controller.onlineImage(fixture.token, 0), "ACTIVE offline fixture must reject image network")
        } finally {
            controller.close()
            manager.stopAndJoin()
        }
    }

    @Test
    fun `partial fixture rejects an existing chapter id without cancelling the foreign download`() = runBlocking {
        val provider = DesktopDownloadProvider(tempDirectory.resolve("foreign-downloads").toFile())
        val manager = DesktopDownloadManager(
            provider = provider,
            httpClient = OkHttpClient(),
            workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            retryDelay = {},
        )
        val foreignIdentity = DownloadChapterIdentity(
            sourceDisplayName = "Foreign source",
            mangaTitle = "Foreign manga",
            chapterName = "Foreign chapter",
            scanlator = null,
            chapterUrl = "/foreign/chapter",
            disallowNonAsciiFilenames = false,
        )
        manager.enqueue(
            DownloadItem(
                sourceId = 9L,
                mangaTitle = foreignIdentity.mangaTitle,
                chapterName = foreignIdentity.chapterName,
                chapterId = 203L,
                mangaId = 303L,
                chapterUrl = foreignIdentity.chapterUrl,
                pageUrls = listOf("https://fixture.invalid/foreign.jpg"),
                downloadIdentity = foreignIdentity,
            ),
        )
        val controller = ReaderTestModeController(configuredDownloadProvider = provider, downloadManager = manager)
        try {
            val failure = runCatching {
                withTimeout(250) {
                    controller.prepareFixtureAwait(
                        spec = ReaderTestFixtureSpec(
                            source = ReaderTestFixtureSource.PARTIAL_DOWNLOAD,
                            pageCount = 3,
                            width = 16,
                            height = 24,
                            format = ReaderTestImageFormat.JPEG,
                            partialPageCount = 1,
                        ),
                        mangaId = 303L,
                        chapterId = 203L,
                        chapterTitle = "Conflicting fixture",
                    )
                }
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException, "conflicting chapter id must fail before fixture ownership")
            assertEquals(foreignIdentity, manager.queue.value.single().downloadIdentity)
        } finally {
            controller.close()
            manager.stopAndJoin()
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
