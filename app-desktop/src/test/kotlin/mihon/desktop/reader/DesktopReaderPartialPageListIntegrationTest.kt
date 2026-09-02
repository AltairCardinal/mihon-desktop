package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.test.runTest
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.PartialDownloadArtifactLifecycleCoordinator
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.partial.DisabledPartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialCommittedPage
import mihon.domain.reader.partial.PartialDownloadSnapshot
import mihon.domain.reader.partial.PartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialPageTable
import mihon.domain.reader.partial.PartialPageTableEntry
import mihon.domain.reader.partial.PartialReaderPageCandidate
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderEncodedPageProvenance
import mihon.domain.reader.session.ReaderPageId
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopReaderPartialPageListIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `complete metadata returns the stable full chapter without a source page list call`() = runTest {
        val source = RecordingPageSource(
            listOf(Page(90, url = "/unexpected", imageUrl = "https://img/unexpected.jpg")),
        )
        val snapshot = completeSnapshot(chapterId = 7L)
        val lookup = RecordingSnapshotLookup(listOf(snapshot, snapshot))

        val pages = contentPort(context(7L), source, lookup)
            .loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(7L), generation = 1L))

        assertEquals(0, source.pageListCalls)
        assertEquals(2, lookup.calls)
        assertEquals(listOf(4, 19, 41), pages.map { it.sourcePageIndex })
        assertEquals(listOf("/page/first", "/page/middle", "/page/last"), pages.map { it.url })
        assertEquals(listOf("https://img/first.jpg", null, "https://img/last.jpg"), pages.map { it.imageUrl })
        assertEquals(5L, pages[0].partialPageCandidate?.committedRevision)
        assertTrue(pages.drop(1).all { it.partialPageCandidate == null })
    }

    @Test
    fun `same generation committed page growth remains a zero source call complete snapshot`() = runTest {
        val source = RecordingPageSource(
            listOf(Page(90, url = "/unexpected", imageUrl = "https://img/unexpected.jpg")),
        )
        val first = completeSnapshot(chapterId = 22L)
        val expanded = first.copy(
            committedPages = first.committedPages +
                PartialCommittedPage(1, 19, "opaque://partial/002.jpg", 6L),
        )

        val pages = contentPort(
            context(22L),
            source,
            RecordingSnapshotLookup(listOf(first, expanded)),
        ).loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(22L), generation = 1L))

        assertEquals(0, source.pageListCalls)
        assertEquals(listOf(4, 19, 41), pages.map { it.sourcePageIndex })
        assertEquals(listOf(5L, 6L, null), pages.map { it.partialPageCandidate?.committedRevision })
    }

    @Test
    fun `legacy metadata performs one source call and overlays only an exact mapping`() = runTest {
        val onlinePages = listOf(
            Page(4, url = "/page/first", imageUrl = "https://img/first.jpg"),
            Page(19, url = "/page/middle", imageUrl = "https://img/middle.jpg"),
            Page(41, url = "/page/last", imageUrl = "https://img/last.jpg"),
        )
        val legacy = legacySnapshot(chapterId = 8L)
        val exactSource = RecordingPageSource(onlinePages)
        val exactPages = contentPort(
            context(8L),
            exactSource,
            RecordingSnapshotLookup(listOf(legacy, legacy)),
        ).loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(8L), generation = 1L))

        val mismatchSource = RecordingPageSource(
            onlinePages.mapIndexed { index, page ->
                if (index == 1) {
                    Page(page.index, url = page.url, imageUrl = "https://img/replaced.jpg")
                } else {
                    page
                }
            },
        )
        val mismatch = legacy.copy(chapterId = 9L)
        val mismatchPages = contentPort(
            context(9L),
            mismatchSource,
            RecordingSnapshotLookup(listOf(mismatch, mismatch)),
        ).loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(9L), generation = 1L))

        assertEquals(1, exactSource.pageListCalls)
        assertEquals(listOf(4, 19, 41), exactPages.map { it.sourcePageIndex })
        assertEquals(19, exactPages[1].partialPageCandidate?.sourcePageIndex)
        assertEquals(1, mismatchSource.pageListCalls)
        assertEquals(listOf(0, 1, 2), mismatchPages.map { it.sourcePageIndex })
        assertTrue(mismatchPages.all { it.partialPageCandidate == null })
    }

    @Test
    fun `verified legacy non sequential source index materializes the committed file without an image request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(pngBytes(Color.BLUE))).build())
            val localBytes = pngBytes(Color.RED)
            val localFile = tempDir.resolve("legacy-non-sequential/001.png").also { file ->
                file.parentFile.mkdirs()
                file.writeBytes(localBytes)
            }
            val chapterId = 18L
            val imageUrl = server.url("/must-not-load.png").toString()
            val snapshot = PartialDownloadSnapshot(
                chapterId = chapterId,
                identity = identity(chapterId),
                attemptGeneration = 3L,
                queueStatus = DownloadQueueStatus.ERROR,
                pageTable = PartialPageTable.legacy(listOf(imageUrl)),
                committedPages = listOf(PartialCommittedPage(0, 0, localFile.absolutePath, 5L)),
            )
            val lifecycle = PartialDownloadArtifactLifecycleCoordinator().apply {
                assertTrue(
                    registerCommittedPage(
                        chapterId,
                        PartialReaderPageCandidate(3L, 0, 0, localFile.absolutePath, 5L),
                    ),
                )
            }
            val lookup = RecordingSnapshotLookup(listOf(snapshot, snapshot, snapshot))
            val source = RecordingPageSource(listOf(Page(41, url = imageUrl, imageUrl = imageUrl)))
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val descriptor = contentPort(context(chapterId), source, lookup)
                .loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(chapterId), generation = 1L))
                .single()
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("legacy-non-sequential-cache"), maxBytes = 1_000_000L)
            store.beginSession(emptySet())

            val result = CanonicalReaderMaterializeExecutor.materializePage(
                request = ReaderPageFetchRequest(
                    pageId = ReaderPageId(ReaderChapterId(chapterId), descriptor.sourcePageIndex),
                    generation = 1L,
                    url = descriptor.url,
                    imageUrl = descriptor.imageUrl,
                ),
                port = DesktopReaderPageFetchPort(
                    context = context(chapterId),
                    descriptor = descriptor,
                    sourceManager = sourceManager,
                    networkHelper = NetworkHelper(OkHttpClient()),
                    encodedPageStore = store,
                    partialDownloadSnapshotLookup = lookup,
                    partialPageCopyPort = DesktopReaderPartialPageFileCopyPort(lifecycle),
                ),
                publish = { true },
            )

            val ready = assertInstanceOf(ReaderPageMaterializeResult.Ready::class.java, result)
            assertInstanceOf(ReaderEncodedPageProvenance.Partial::class.java, ready.encodedPageProvenance)
            assertEquals(41, descriptor.sourcePageIndex)
            assertArrayEquals(localBytes, store.read(ready.encodedPageRef))
            assertEquals(1, source.pageListCalls)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `legacy recovery that appears during the source call is merged before descriptors return`() = runTest {
        val chapterId = 19L
        val imageUrl = "https://img/recovered-during-page-list.jpg"
        val recovered = PartialDownloadSnapshot(
            chapterId = chapterId,
            identity = identity(chapterId),
            attemptGeneration = 4L,
            queueStatus = DownloadQueueStatus.ERROR,
            pageTable = PartialPageTable.legacy(listOf(imageUrl)),
            committedPages = listOf(PartialCommittedPage(0, 0, "opaque://partial/001.jpg", 7L)),
        )
        val lookup = RecordingSnapshotLookup(listOf(null, recovered))
        val source = RecordingPageSource(listOf(Page(37, url = imageUrl, imageUrl = imageUrl)))

        val descriptor = contentPort(context(chapterId), source, lookup)
            .loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(chapterId), generation = 1L))
            .single()

        assertEquals(2, lookup.calls)
        assertEquals(1, source.pageListCalls)
        assertEquals(37, descriptor.sourcePageIndex)
        assertEquals(37, descriptor.partialPageCandidate?.sourcePageIndex)
        assertEquals(0, descriptor.partialPageCandidate?.readerOrdinal)
    }

    @Test
    fun `a generation change during page list resolution cannot publish old partial candidates`() = runTest {
        val original = legacySnapshot(chapterId = 10L)
        val newer = original.copy(attemptGeneration = original.attemptGeneration + 1)
        val source = RecordingPageSource(
            listOf(
                Page(4, url = "/page/first", imageUrl = "https://img/first.jpg"),
                Page(19, url = "/page/middle", imageUrl = "https://img/middle.jpg"),
                Page(41, url = "/page/last", imageUrl = "https://img/last.jpg"),
            ),
        )

        val pages = contentPort(
            context(10L),
            source,
            RecordingSnapshotLookup(listOf(original, newer)),
        ).loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(10L), generation = 1L))

        assertEquals(1, source.pageListCalls)
        assertEquals(listOf(0, 1, 2), pages.map { it.sourcePageIndex })
        assertTrue(pages.all { it.partialPageCandidate == null })
    }

    @Test
    fun `malformed incomplete and future metadata each perform one safe online fallback`() = runTest {
        val valid = completeSnapshot(16L)
        val malformed = listOf(
            valid.copy(pageTable = PartialPageTable.complete(emptyList()), committedPages = emptyList()),
            valid.copy(pageTable = PartialPageTable.partial(3, valid.pageTable.entries.take(1))),
            valid.copy(
                pageTable = valid.pageTable.copy(schemaVersion = valid.pageTable.schemaVersion + 1),
            ),
            valid.copy(pageTable = valid.pageTable.copy(totalPageCount = 4)),
            valid.copy(
                pageTable = PartialPageTable.complete(
                    listOf(
                        PartialPageTableEntry(0, 4, "/page/first", "https://img/first.jpg"),
                        PartialPageTableEntry(1, 4, "/page/last", "https://img/last.jpg"),
                    ),
                ),
            ),
        )

        malformed.forEachIndexed { index, snapshot ->
            val chapterId = 16L + index
            val keyedSnapshot = snapshot.copy(chapterId = chapterId, identity = identity(chapterId))
            val source = RecordingPageSource(
                listOf(
                    Page(4, url = "/online/first", imageUrl = "https://img/online-first.jpg"),
                    Page(41, url = "/online/last", imageUrl = null),
                ),
            )
            val pages = contentPort(
                context(chapterId),
                source,
                RecordingSnapshotLookup(listOf(keyedSnapshot, keyedSnapshot)),
            ).loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(chapterId), generation = 1L))

            assertEquals(1, source.pageListCalls)
            assertEquals(listOf(0, 1), pages.map { it.sourcePageIndex })
            assertTrue(pages.all { it.partialPageCandidate == null })
        }
    }

    @Test
    fun `complete download and every local route remain ahead of partial lookup`() = runTest {
        val provider = DesktopDownloadProvider(tempDir.resolve("downloads-priority"))
        val source = RecordingPageSource(listOf(Page(0, url = "/online")))
        val lookup = RecordingSnapshotLookup(listOf(completeSnapshot(11L)))
        val adapter = DesktopReaderContentAdapter()
        val downloaded = provider.chapterDownloadDir(42L, "Manga", "Chapter 11")
            .resolve("001.png")
            .also { file -> file.parentFile.mkdirs(); file.writeBytes(pngBytes(Color.RED)) }
        val localDirectory = tempDir.resolve("local-directory/001.png")
            .also { file -> file.parentFile.mkdirs(); file.writeBytes(pngBytes(Color.BLUE)) }
            .parentFile
        val localArchive = zip(
            tempDir.resolve("local.cbz"),
            mapOf("001.png" to pngBytes(Color.GREEN)),
        )
        val localEpub = zip(
            tempDir.resolve("local.epub"),
            mapOf(
                "META-INF/container.xml" to """
                    <container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>
                """.trimIndent().toByteArray(),
                "OEBPS/content.opf" to """
                    <package><manifest>
                      <item id="page" href="page.xhtml" media-type="application/xhtml+xml"/>
                    </manifest><spine><itemref idref="page"/></spine></package>
                """.trimIndent().toByteArray(),
                "OEBPS/page.xhtml" to "<html><body><img src=\"001.png\"/></body></html>".toByteArray(),
                "OEBPS/001.png" to pngBytes(Color.ORANGE),
            ),
        )

        try {
            val routes = listOf(
                context(11L),
                context(12L, localDirectory.absolutePath),
                context(13L, localArchive.absolutePath),
                context(14L, localEpub.absolutePath),
            )
            routes.forEach { routeContext ->
                DesktopReaderChapterContentPort(
                    context = routeContext,
                    downloadProvider = provider,
                    sourceManager = FakeDesktopSourceManager(listOf(source)),
                    contentAdapter = adapter,
                    partialDownloadSnapshotLookup = lookup,
                ).loadChapterContent(
                    ReaderChapterContentRequest(ReaderChapterId(routeContext.chapterId), generation = 1L),
                ).also { pages -> assertEquals(1, pages.size) }
            }

            assertTrue(downloaded.isFile)
            assertEquals(0, lookup.calls)
            assertEquals(0, source.pageListCalls)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `disabled lookup preserves the original online descriptor mapping`() = runTest {
        val source = RecordingPageSource(
            listOf(
                Page(4, url = "/page/first", imageUrl = "https://img/first.jpg"),
                Page(41, url = "/page/last", imageUrl = null),
            ),
        )

        val pages = contentPort(context(15L), source, DisabledPartialDownloadSnapshotLookup)
            .loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(15L), generation = 1L))

        assertEquals(1, source.pageListCalls)
        assertEquals(listOf(0, 1), pages.map { it.sourcePageIndex })
        assertEquals(listOf("/page/first", "/page/last"), pages.map { it.url })
        assertEquals(listOf("https://img/first.jpg", null), pages.map { it.imageUrl })
        assertTrue(pages.all { it.partialPageCandidate == null })
    }

    private fun contentPort(
        context: DesktopReaderChapterContext,
        source: CatalogueSource,
        lookup: PartialDownloadSnapshotLookup,
    ) = DesktopReaderChapterContentPort(
        context = context,
        downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-${context.chapterId}")),
        sourceManager = FakeDesktopSourceManager(listOf(source)),
        partialDownloadSnapshotLookup = lookup,
    )

    private fun context(chapterId: Long, localChapterPath: String? = null) = DesktopReaderChapterContext(
        chapterId = chapterId,
        sourceId = 42L,
        chapterUrl = "/chapter/$chapterId",
        mangaTitle = "Manga",
        chapterTitle = "Chapter $chapterId",
        chapterNumber = chapterId.toDouble(),
        chapterIndex = 0,
        initialPage = 0,
        wasRead = false,
        sourceDisplayName = "Source",
        localChapterPath = localChapterPath,
    )

    private fun completeSnapshot(chapterId: Long) = PartialDownloadSnapshot(
        chapterId = chapterId,
        identity = identity(chapterId),
        attemptGeneration = 3L,
        queueStatus = DownloadQueueStatus.DOWNLOADING,
        pageTable = PartialPageTable.complete(
            listOf(
                PartialPageTableEntry(0, 4, "/page/first", "https://img/first.jpg"),
                PartialPageTableEntry(1, 19, "/page/middle", null),
                PartialPageTableEntry(2, 41, "/page/last", "https://img/last.jpg"),
            ),
        ),
        committedPages = listOf(PartialCommittedPage(0, 4, "opaque://partial/001.jpg", 5L)),
    )

    private fun legacySnapshot(chapterId: Long) = PartialDownloadSnapshot(
        chapterId = chapterId,
        identity = identity(chapterId),
        attemptGeneration = 3L,
        queueStatus = DownloadQueueStatus.DOWNLOADING,
        pageTable = PartialPageTable.legacy(
            listOf("https://img/first.jpg", "https://img/middle.jpg", "https://img/last.jpg"),
        ),
        committedPages = listOf(PartialCommittedPage(1, 1, "opaque://partial/002.jpg", 2L)),
    )

    private fun identity(chapterId: Long) = DownloadChapterIdentity(
        sourceDisplayName = "Source",
        mangaTitle = "Manga",
        chapterName = "Chapter $chapterId",
        scanlator = null,
        chapterUrl = "/chapter/$chapterId",
        disallowNonAsciiFilenames = false,
    )

    private fun pngBytes(color: Color): ByteArray {
        val image = BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, color.rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun zip(file: File, entries: Map<String, ByteArray>): File = file.also { archive ->
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private class RecordingSnapshotLookup(snapshots: List<PartialDownloadSnapshot?>) : PartialDownloadSnapshotLookup {
        private val remaining = ArrayDeque(snapshots)
        private val terminal = snapshots.lastOrNull()
        var calls = 0
            private set

        override fun snapshot(chapterId: Long, identity: DownloadChapterIdentity): PartialDownloadSnapshot? {
            calls++
            return if (remaining.size > 1) remaining.removeFirst() else remaining.firstOrNull() ?: terminal
        }
    }

    private class RecordingPageSource(private val pages: List<Page>) : CatalogueSource {
        override val id = 42L
        override val name = "Source"
        override val lang = "en"
        override val supportsLatest = false
        var pageListCalls = 0
            private set

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            pageListCalls++
            return pages
        }

        override fun toString(): String = name

        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override fun getFilterList(): FilterList = FilterList()
    }
}
