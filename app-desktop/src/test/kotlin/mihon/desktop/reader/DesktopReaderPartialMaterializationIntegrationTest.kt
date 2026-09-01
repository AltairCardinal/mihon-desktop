package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.partial.PartialCommittedPage
import mihon.domain.reader.partial.PartialDownloadSnapshot
import mihon.domain.reader.partial.PartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialPageTable
import mihon.domain.reader.partial.PartialPageTableEntry
import mihon.domain.reader.partial.PartialReaderPageCandidate
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderEncodedPageProvenance
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import mihon.domain.reader.session.ReaderSessionCore
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderPartialMaterializationIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `committed page is copied locally while missing pages each use one image request and cache stays first`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val localBytes = pngBytes(Color.RED)
            val localFile = tempDir.resolve("downloads/_tmp/001.png").also { file ->
                file.parentFile.mkdirs()
                file.writeBytes(localBytes)
            }
            val networkBytes = listOf(pngBytes(Color.GREEN), pngBytes(Color.BLUE))
            networkBytes.forEach { bytes ->
                server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            }
            val urls = (0..2).map { index -> server.url("/image-$index").toString() }
            val source = ImageSource(server, urls)
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val identity = identity(source)
            val snapshot = snapshot(
                identity = identity,
                urls = urls,
                committed = mapOf(0 to CommittedFile(localFile, revision = 1L)),
            )
            val lookup = MutableSnapshotLookup(snapshot)
            val coordinator = DesktopReaderPartialPageFallbackCoordinator()
            val copyCalls = AtomicInteger()
            val copyPort = countingCopyPort(copyCalls)
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-priority"), maxBytes = 1_000_000L)
            store.beginSession(emptySet())

            val results = urls.mapIndexed { ordinal, url ->
                val descriptor = descriptor(
                    ordinal = ordinal,
                    url = url,
                    candidate = snapshot.candidateAt(ordinal),
                )
                materialize(
                    descriptor = descriptor,
                    source = source,
                    sourceManager = sourceManager,
                    lookup = lookup,
                    coordinator = coordinator,
                    copyPort = copyPort,
                    store = store,
                )
            }

            val localReady = assertInstanceOf(ReaderPageMaterializeResult.Ready::class.java, results[0])
            val localOrigin = assertInstanceOf(
                ReaderEncodedPageProvenance.Partial::class.java,
                localReady.encodedPageProvenance,
            )
            assertEquals(1L, localOrigin.candidate.committedRevision)
            assertArrayEquals(localBytes, store.read(localReady.encodedPageRef))
            assertNull(results.drop(1).filterIsInstance<ReaderPageMaterializeResult.Ready>()
                .firstOrNull()?.encodedPageProvenance)
            assertEquals(1, copyCalls.get())
            assertEquals(2, server.requestCount)

            val lookupThatMustNotRun = PartialDownloadSnapshotLookup { _, _ ->
                error("encoded cache hit must not query the partial snapshot")
            }
            val copyThatMustNotRun = DesktopReaderPartialPageCopyPort { _, _ ->
                error("encoded cache hit must not reopen the downloader file")
            }
            val cached = materialize(
                descriptor = descriptor(0, urls[0], snapshot.candidateAt(0)),
                source = source,
                sourceManager = sourceManager,
                lookup = lookupThatMustNotRun,
                coordinator = coordinator,
                copyPort = copyThatMustNotRun,
                store = store,
            )

            val cachedReady = assertInstanceOf(ReaderPageMaterializeResult.Ready::class.java, cached)
            assertEquals(localReady.encodedPageRef, cachedReady.encodedPageRef)
            assertEquals(localReady.encodedPageProvenance, cachedReady.encodedPageProvenance)
            assertEquals(1, copyCalls.get())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `tmp candidate is never opened and falls back to one network request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val networkBytes = pngBytes(Color.ORANGE)
            server.enqueue(MockResponse.Builder().body(Buffer().write(networkBytes)).build())
            val url = server.url("/image").toString()
            val source = ImageSource(server, listOf(url))
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val tmpFile = tempDir.resolve("downloads/_tmp/001.tmp").also { file ->
                file.parentFile.mkdirs()
                file.writeBytes(pngBytes(Color.BLACK))
            }
            val snapshot = snapshot(
                identity = identity(source),
                urls = listOf(url),
                committed = mapOf(0 to CommittedFile(tmpFile, revision = 1L)),
            )
            val copyCalls = AtomicInteger()
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-tmp"), maxBytes = 1_000_000L)
            store.beginSession(emptySet())

            val result = materialize(
                descriptor = descriptor(0, url, snapshot.candidateAt(0)),
                source = source,
                sourceManager = sourceManager,
                lookup = MutableSnapshotLookup(snapshot),
                coordinator = DesktopReaderPartialPageFallbackCoordinator(),
                copyPort = countingCopyPort(copyCalls),
                store = store,
            )

            val ready = assertInstanceOf(ReaderPageMaterializeResult.Ready::class.java, result)
            assertNull(ready.encodedPageProvenance)
            assertArrayEquals(networkBytes, store.read(ready.encodedPageRef))
            assertEquals(0, copyCalls.get())
            assertEquals(1, server.requestCount)
            assertTrue(tmpFile.isFile)
        }
    }

    @Test
    fun `concurrent same revision materialization shares one local copy`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = pngBytes(Color.MAGENTA)
            val file = tempDir.resolve("downloads/_tmp/001.png").also { target ->
                target.parentFile.mkdirs()
                target.writeBytes(bytes)
            }
            val url = server.url("/unused").toString()
            val source = ImageSource(server, listOf(url))
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val snapshot = snapshot(
                identity = identity(source),
                urls = listOf(url),
                committed = mapOf(0 to CommittedFile(file, revision = 7L)),
            )
            val descriptor = descriptor(0, url, snapshot.candidateAt(0))
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-single-flight"), maxBytes = 1_000_000L)
            store.beginSession(emptySet())
            val copyStarted = CompletableDeferred<Unit>()
            val releaseCopy = CompletableDeferred<Unit>()
            val copyCalls = AtomicInteger()
            val copyPort = DesktopReaderPartialPageCopyPort { candidate, destination ->
                copyCalls.incrementAndGet()
                copyStarted.complete(Unit)
                releaseCopy.await()
                val copied = File(candidate.opaqueLocation).readBytes()
                destination.writeBytes(copied)
                copied.size.toLong()
            }
            val coordinator = DesktopReaderPartialPageFallbackCoordinator()
            val lookup = MutableSnapshotLookup(snapshot)

            val first = async {
                materialize(descriptor, source, sourceManager, lookup, coordinator, copyPort, store)
            }
            copyStarted.await()
            val second = async {
                materialize(descriptor, source, sourceManager, lookup, coordinator, copyPort, store)
            }
            runCurrent()
            releaseCopy.complete(Unit)
            val results = listOf(first.await(), second.await())

            assertEquals(1, copyCalls.get())
            assertEquals(0, server.requestCount)
            assertEquals(
                1,
                results.filterIsInstance<ReaderPageMaterializeResult.Ready>()
                    .map(ReaderPageMaterializeResult.Ready::encodedPageRef)
                    .distinct()
                    .size,
            )
        }
    }

    @Test
    fun `concurrent missing page materialization shares one network request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(pngBytes(Color.CYAN))).build())
            val requestEntered = CountDownLatch(1)
            val releaseRequest = CountDownLatch(1)
            val sourceClient = OkHttpClient.Builder().addInterceptor { chain ->
                requestEntered.countDown()
                check(releaseRequest.await(5, TimeUnit.SECONDS))
                chain.proceed(chain.request())
            }.build()
            val url = server.url("/image").toString()
            val source = ImageSource(server, listOf(url), sourceClient)
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val descriptor = descriptor(0, url, candidate = null)
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-network-flight"), maxBytes = 1_000_000L)
            store.beginSession(emptySet())
            val lookup = PartialDownloadSnapshotLookup { _, _ -> null }
            val coordinator = DesktopReaderPartialPageFallbackCoordinator()
            val copyPort = DesktopReaderPartialPageCopyPort { _, _ -> error("Missing page must not copy locally") }

            val first = async {
                materialize(descriptor, source, sourceManager, lookup, coordinator, copyPort, store)
            }
            assertTrue(withContext(Dispatchers.IO) { requestEntered.await(5, TimeUnit.SECONDS) })
            val second = async {
                materialize(descriptor, source, sourceManager, lookup, coordinator, copyPort, store)
            }
            runCurrent()
            releaseRequest.countDown()
            val results = listOf(first.await(), second.await())

            assertEquals(1, server.requestCount)
            assertEquals(
                1,
                results.filterIsInstance<ReaderPageMaterializeResult.Ready>()
                    .map(ReaderPageMaterializeResult.Ready::encodedPageRef)
                    .distinct()
                    .size,
            )
        }
    }

    @Test
    fun `decode failure revalidates after a concurrent retry before rejecting or evicting`() = runTest {
        val candidateFile = tempDir.resolve("downloads/_tmp/stale-001.png").also { file ->
            file.parentFile.mkdirs()
            file.writeBytes(pngBytes(Color.RED))
        }
        val candidate = PartialReaderPageCandidate(
            attemptGeneration = 3L,
            readerOrdinal = 0,
            sourcePageIndex = 0,
            opaqueLocation = candidateFile.absolutePath,
            committedRevision = 1L,
        )
        val provenance = ReaderEncodedPageProvenance.Partial(candidate)
        val pageId = ReaderPageId(ReaderChapterId(CHAPTER_ID), 0)
        val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-stale-decode"), maxBytes = 1_000_000L)
        val staleRef = store.cacheRef(pageId, "partial-stale")
        val staleCacheFile = store.destinationFile(staleRef).also { file ->
            file.parentFile.mkdirs()
            file.writeBytes(pngBytes(Color.RED))
        }
        val coordinator = DesktopReaderPartialPageFallbackCoordinator()
        val commitEntered = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val session = DesktopReaderSession(
            initialContext = DesktopReaderChapterContext(
                chapterId = CHAPTER_ID,
                sourceId = 42L,
                chapterUrl = "/chapter/$CHAPTER_ID",
                mangaTitle = "Manga",
                chapterTitle = "Chapter $CHAPTER_ID",
                chapterNumber = 1.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            core = ReaderSessionCore(
                initialChapterId = ReaderChapterId(CHAPTER_ID),
                sessionId = "stale-decode-linearization",
                requestScheduler = ReaderRequestScheduler(
                    ReaderSchedulerPolicy(nearbyForward = 0, maxConcurrentRequests = 1),
                ),
            ),
            encodedPageStore = store,
            chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                ReaderChapterContentPort {
                    listOf(
                        ReaderPageDescriptor(
                            sourcePageIndex = 0,
                            url = "/page/0",
                            imageUrl = "https://img/page-0.png",
                            encodedPageRef = staleRef,
                            initialLoadState = ReaderPageLoadState.Ready,
                            partialPageCandidate = candidate,
                            partialPageOrdinal = 0,
                            encodedPageProvenance = provenance,
                        ),
                    )
                }
            },
            pageFetchPortFactory = DesktopReaderPageFetchPortFactory { _, _ ->
                object : mihon.domain.reader.materialize.ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String =
                        requireNotNull(request.imageUrl)

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest) = null

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest) =
                        store.cacheRef(request.pageId, "retry-replacement")
                }
            },
            progressPort = DesktopReaderProgressPort { _, _ -> },
            parentScope = this,
            partialPageFallbackCoordinator = coordinator,
            partialDecodeFailureCommitGate = {
                commitEntered.complete(Unit)
                allowCommit.await()
            },
        )

        try {
            session.start()
            advanceUntilIdle()
            val original = session.state.value.snapshot.activeChapter.pages.single()
            val request = original.decodeKey(session.state.value.snapshot.generation).contentKey
            val handling = async { session.handlePartialPageDecodeFailure(request) }
            commitEntered.await()

            session.retryPage(original.id)
            assertTrue(
                session.state.value.snapshot.activeChapter.pages.single().attemptGeneration >
                    original.attemptGeneration,
            )
            allowCommit.complete(Unit)

            assertFalse(handling.await())
            assertTrue(staleCacheFile.isFile)
            assertNull(coordinator.rejectedAttempt(CHAPTER_ID, candidate))
        } finally {
            allowCommit.complete(Unit)
            session.close()
        }
    }

    @Test
    fun `factory runtimes share a rejected revision and recover when another runtime evicts its ref`() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(3) { colorIndex ->
                val color = listOf(Color.GREEN, Color.BLUE, Color.ORANGE)[colorIndex]
                server.enqueue(MockResponse.Builder().body(Buffer().write(pngBytes(color))).build())
            }
            val url = server.url("/image").toString()
            val source = ImageSource(server, listOf(url))
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val corruptBytes = truncatedPngPixelStream()
            val corruptFile = tempDir.resolve("downloads-shared/_tmp/001.png").also { file ->
                file.parentFile.mkdirs()
                file.writeBytes(corruptBytes)
            }
            val partialSnapshot = snapshot(
                identity = identity(source),
                urls = listOf(url),
                committed = mapOf(0 to CommittedFile(corruptFile, revision = 1L)),
            )
            val productionDecoder = SkiaDesktopReaderPageImageDecoder()
            val decoder = DesktopReaderPageImageDecoder { encoded, key ->
                if (encoded.contentEquals(corruptBytes)) null else productionDecoder.decode(encoded, key)
            }
            val factory = DesktopReaderRuntimeFactory(
                prefs = ReaderPreferences(),
                downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-shared")),
                sourceManager = sourceManager,
                networkHelper = NetworkHelper(source.httpClient),
                progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
                mangaRepository = null,
                encodedCacheDirectory = tempDir.resolve("encoded-shared-runtimes"),
                pageImageDecoder = decoder,
                partialDownloadSnapshotLookup = MutableSnapshotLookup(partialSnapshot),
            )
            val runtimes = mutableListOf<DesktopReaderRuntime>()

            try {
                repeat(3) { runtimes += factory.createRuntime(context(source), this) }
                advanceUntilIdle()
                val partialPages = runtimes.map { runtime ->
                    val pageId = runtime.session.state.value.snapshot.activeChapter.pages.single().id
                    runtime.session.settleViewport(setOf(pageId), pageId)
                    runtime.session.awaitPage { page ->
                        page.loadState is ReaderPageLoadState.Ready &&
                            page.encodedPageProvenance is ReaderEncodedPageProvenance.Partial
                    }
                }
                assertEquals(1, partialPages.map(ReaderPageSession::encodedPageRef).distinct().size)

                val firstGeneration = runtimes[0].session.state.value.snapshot.generation
                assertNull(runtimes[0].pageImagePipeline.acquire(partialPages[0].decodeKey(firstGeneration)))
                val firstNetwork = runtimes[0].session.awaitPage { page ->
                    page.loadState is ReaderPageLoadState.Ready && page.encodedPageProvenance == null
                }
                assertNull(firstNetwork.encodedPageProvenance)

                runtimes[1].session.retryPage(partialPages[1].id)
                val secondRetry = runtimes[1].session.awaitPage { page ->
                    page.attemptGeneration > partialPages[1].attemptGeneration &&
                        (page.loadState is ReaderPageLoadState.Ready || page.loadState is ReaderPageLoadState.Error)
                }
                assertEquals(ReaderPageLoadState.Ready, secondRetry.loadState)
                assertNull(secondRetry.encodedPageProvenance, "A sibling runtime must observe the shared rejected revision")

                val thirdGeneration = runtimes[2].session.state.value.snapshot.generation
                assertNull(runtimes[2].pageImagePipeline.acquire(partialPages[2].decodeKey(thirdGeneration)))
                assertNull(
                    runtimes[2].session.state.value.snapshot.activeChapter.pages.single().encodedPageProvenance,
                    "A missing shared partial ref must enter the same decode fallback path",
                )
                val thirdNetwork = runtimes[2].session.awaitPage { page ->
                    page.loadState is ReaderPageLoadState.Ready && page.encodedPageProvenance == null
                }
                assertNull(thirdNetwork.encodedPageProvenance)
                assertArrayEquals(corruptBytes, corruptFile.readBytes())
                assertTrue(server.requestCount in 1..3)
            } finally {
                runtimes.asReversed().forEach(DesktopReaderRuntime::close)
            }
        }
    }

    @Test
    fun `decode failure rejects only that revision then retries network until a newer commit appears`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val firstNetwork = pngBytes(Color.CYAN)
            val retryNetwork = pngBytes(Color.YELLOW)
            server.enqueue(MockResponse.Builder().body(Buffer().write(firstNetwork)).build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(retryNetwork)).build())
            val url = server.url("/image").toString()
            val source = ImageSource(server, listOf(url), failImageUrlResolution = true)
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val corruptBytes = truncatedPngPixelStream()
            assertNotNull(SkiaImageDecoder.peekSize(corruptBytes))
            assertTrue(!SkiaImageDecoder.canDecodePixels(corruptBytes))
            val corruptFile = tempDir.resolve("downloads/_tmp/001.png").also { file ->
                file.parentFile.mkdirs()
                file.writeBytes(corruptBytes)
            }
            val identity = identity(source)
            val firstSnapshot = snapshot(
                identity = identity,
                urls = listOf(url),
                committed = mapOf(0 to CommittedFile(corruptFile, revision = 1L)),
            )
            val lookup = MutableSnapshotLookup(firstSnapshot)
            val coordinator = DesktopReaderPartialPageFallbackCoordinator()
            val copyCalls = AtomicInteger()
            val copyPort = countingCopyPort(copyCalls)
            val descriptor = descriptor(0, url, firstSnapshot.candidateAt(0))
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-decode-fallback"), maxBytes = 1_000_000L)
            val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })
            val contentOwner = DesktopReaderPageContentOwner(this, store::read, reporter)
            val productionDecoder = SkiaDesktopReaderPageImageDecoder()
            lateinit var session: DesktopReaderSession
            val pipeline = DesktopReaderPageImagePipeline(
                scope = this,
                pageContentOwner = contentOwner,
                ioReporter = reporter,
                decoder = DesktopReaderPageImageDecoder { encoded, key ->
                    if (encoded.contentEquals(corruptBytes)) null else productionDecoder.decode(encoded, key)
                },
                partialDecodeFailureHandler = { request -> session.handlePartialPageDecodeFailure(request) },
            )
            session = DesktopReaderSession(
                initialContext = context(source),
                core = ReaderSessionCore(
                    initialChapterId = ReaderChapterId(CHAPTER_ID),
                    sessionId = "partial-materialization",
                    requestScheduler = ReaderRequestScheduler(
                        ReaderSchedulerPolicy(nearbyForward = 0, maxConcurrentRequests = 2),
                    ),
                ),
                encodedPageStore = store,
                chapterContentPortFactory = DesktopReaderChapterContentPortFactory { _, _ ->
                    ReaderChapterContentPort { listOf(descriptor) }
                },
                pageFetchPortFactory = DesktopReaderPageFetchPortFactory { readerContext, pageDescriptor ->
                    DesktopReaderPageFetchPort(
                        context = readerContext,
                        descriptor = pageDescriptor,
                        sourceManager = sourceManager,
                        networkHelper = NetworkHelper(source.httpClient),
                        encodedPageStore = store,
                        partialDownloadSnapshotLookup = lookup,
                        partialPageFallbackCoordinator = coordinator,
                        partialPageCopyPort = copyPort,
                    )
                },
                progressPort = DesktopReaderProgressPort { _, _ -> },
                parentScope = this,
                partialPageFallbackCoordinator = coordinator,
            )

            try {
                session.start()
                advanceUntilIdle()
                assertEquals(0, copyCalls.get(), "Loading the page table must not eagerly copy committed pages")
                val pageId = session.state.value.snapshot.activeChapter.pages.single().id
                session.settleViewport(setOf(pageId), pageId)
                val localPage = session.awaitPage { page ->
                    (page.encodedPageProvenance as? ReaderEncodedPageProvenance.Partial)
                        ?.candidate
                        ?.committedRevision == 1L
                }
                val localOrigin = assertInstanceOf(
                    ReaderEncodedPageProvenance.Partial::class.java,
                    localPage.encodedPageProvenance,
                )
                assertEquals(1L, localOrigin.candidate.committedRevision)
                assertEquals(0, server.requestCount)
                assertEquals(1, copyCalls.get())

                val failedDecode = pipeline.acquire(localPage.decodeKey(session.state.value.snapshot.generation))
                assertNull(failedDecode)
                val networkPage = session.awaitPage { page ->
                    page.loadState is ReaderPageLoadState.Ready || page.loadState is ReaderPageLoadState.Error
                }
                assertEquals(ReaderPageLoadState.Ready, networkPage.loadState)
                assertNull(networkPage.encodedPageProvenance)
                assertEquals(0, source.imageUrlResolutionCalls.get())
                assertEquals(1, server.requestCount)
                assertEquals(1, copyCalls.get())
                assertArrayEquals(corruptBytes, corruptFile.readBytes())
                pipeline.acquire(networkPage.decodeKey(session.state.value.snapshot.generation))?.close()

                source.failImageUrlResolution = false
                session.retryPage(pageId)
                val sameRevisionRetry = session.awaitPage { page ->
                    (page.loadState is ReaderPageLoadState.Ready || page.loadState is ReaderPageLoadState.Error) &&
                        page.attemptGeneration > networkPage.attemptGeneration
                }
                assertEquals(ReaderPageLoadState.Ready, sameRevisionRetry.loadState)
                assertNull(sameRevisionRetry.encodedPageProvenance)
                assertEquals(2, server.requestCount)
                assertEquals(1, copyCalls.get(), "Retry must not reopen the rejected revision")

                val repairedBytes = pngBytes(Color.PINK)
                val repairedFile = tempDir.resolve("downloads/_tmp/001-repaired.png").also { it.writeBytes(repairedBytes) }
                lookup.current = snapshot(
                    identity = identity,
                    urls = listOf(url),
                    committed = mapOf(0 to CommittedFile(repairedFile, revision = 2L)),
                )
                session.retryPage(pageId)
                val repairedPage = session.awaitPage { page ->
                    (page.encodedPageProvenance as? ReaderEncodedPageProvenance.Partial)
                        ?.candidate
                        ?.committedRevision == 2L
                }
                val repairedOrigin = assertInstanceOf(
                    ReaderEncodedPageProvenance.Partial::class.java,
                    repairedPage.encodedPageProvenance,
                )
                assertEquals(2L, repairedOrigin.candidate.committedRevision)
                assertEquals(2, server.requestCount)
                assertEquals(2, copyCalls.get())
                assertArrayEquals(repairedBytes, store.read(checkNotNull(repairedPage.encodedPageRef)))

                val staleRequest = repairedPage
                    .decodeKey(session.state.value.snapshot.generation)
                    .contentKey
                    .copy(attemptGeneration = repairedPage.attemptGeneration - 1L)
                assertFalse(session.handlePartialPageDecodeFailure(staleRequest))
                assertNull(coordinator.rejectedAttempt(CHAPTER_ID, repairedOrigin.candidate))
                assertEquals(2, server.requestCount)
                assertEquals(2, copyCalls.get())
            } finally {
                pipeline.close()
                session.close()
                contentOwner.close()
            }
        }
    }

    private suspend fun materialize(
        descriptor: ReaderPageDescriptor,
        source: ImageSource,
        sourceManager: FakeDesktopSourceManager,
        lookup: PartialDownloadSnapshotLookup,
        coordinator: DesktopReaderPartialPageFallbackCoordinator,
        copyPort: DesktopReaderPartialPageCopyPort,
        store: DesktopReaderEncodedPageStore,
    ): ReaderPageMaterializeResult = CanonicalReaderMaterializeExecutor.materializePage(
        request = ReaderPageFetchRequest(
            pageId = ReaderPageId(ReaderChapterId(CHAPTER_ID), descriptor.sourcePageIndex),
            generation = 1L,
            url = descriptor.url,
            imageUrl = descriptor.imageUrl,
        ),
        port = DesktopReaderPageFetchPort(
            context = context(source),
            descriptor = descriptor,
            sourceManager = sourceManager,
            networkHelper = NetworkHelper(source.httpClient),
            encodedPageStore = store,
            partialDownloadSnapshotLookup = lookup,
            partialPageFallbackCoordinator = coordinator,
            partialPageCopyPort = copyPort,
        ),
        publish = { true },
    )

    private fun descriptor(
        ordinal: Int,
        url: String,
        candidate: PartialReaderPageCandidate?,
    ) = ReaderPageDescriptor(
        sourcePageIndex = ordinal,
        url = "/page/$ordinal",
        imageUrl = url,
        partialPageOrdinal = ordinal,
        partialPageCandidate = candidate,
    )

    private fun ReaderPageSession.decodeKey(generation: Long) = ReaderPageDecodeKey(
        contentKey = ReaderPageContentOpenRequest(
            pageId = id,
            generation = generation,
            encodedPageRef = checkNotNull(encodedPageRef),
            attemptGeneration = attemptGeneration,
            encodedPageProvenance = encodedPageProvenance,
        ),
        purpose = PageDecodePurpose.FULL_PAGE,
        maxWidth = 2_048,
        maxHeight = 2_048,
    )

    private suspend fun DesktopReaderSession.awaitPage(
        predicate: (ReaderPageSession) -> Boolean,
    ): ReaderPageSession = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(10_000L) {
            state.map { sessionState -> sessionState.snapshot.activeChapter.pages.singleOrNull() }
                .first { page -> page != null && predicate(page) }
                .let(::checkNotNull)
        }
    }

    private fun snapshot(
        identity: DownloadChapterIdentity,
        urls: List<String>,
        committed: Map<Int, CommittedFile>,
    ): PartialDownloadSnapshot {
        val entries = urls.mapIndexed { ordinal, url ->
            PartialPageTableEntry(
                readerOrdinal = ordinal,
                sourcePageIndex = ordinal,
                pageUrl = "/page/$ordinal",
                imageUrl = url,
            )
        }
        return PartialDownloadSnapshot(
            chapterId = CHAPTER_ID,
            identity = identity,
            attemptGeneration = 3L,
            queueStatus = DownloadQueueStatus.DOWNLOADING,
            pageTable = PartialPageTable.complete(entries),
            committedPages = committed.map { (ordinal, file) ->
                PartialCommittedPage(
                    readerOrdinal = ordinal,
                    sourcePageIndex = ordinal,
                    opaqueLocation = file.file.absolutePath,
                    committedRevision = file.revision,
                )
            },
        )
    }

    private fun PartialDownloadSnapshot.candidateAt(ordinal: Int): PartialReaderPageCandidate? = committedPages
        .singleOrNull { it.readerOrdinal == ordinal }
        ?.let { page ->
            PartialReaderPageCandidate(
                attemptGeneration = attemptGeneration,
                readerOrdinal = page.readerOrdinal,
                sourcePageIndex = page.sourcePageIndex,
                opaqueLocation = page.opaqueLocation,
                committedRevision = page.committedRevision,
            )
        }

    private fun context(source: ImageSource) = DesktopReaderChapterContext(
        chapterId = CHAPTER_ID,
        sourceId = source.id,
        sourceDisplayName = source.toString(),
        chapterUrl = "/chapter/$CHAPTER_ID",
        mangaTitle = "Manga",
        chapterTitle = "Chapter $CHAPTER_ID",
        chapterNumber = 1.0,
        chapterIndex = 0,
        initialPage = 0,
        wasRead = false,
    )

    private fun identity(source: ImageSource) = DownloadChapterIdentity(
        sourceDisplayName = source.toString(),
        mangaTitle = "Manga",
        chapterName = "Chapter $CHAPTER_ID",
        scanlator = null,
        chapterUrl = "/chapter/$CHAPTER_ID",
        disallowNonAsciiFilenames = false,
    )

    private fun countingCopyPort(copyCalls: AtomicInteger) = DesktopReaderPartialPageCopyPort { candidate, destination ->
        copyCalls.incrementAndGet()
        val bytes = File(candidate.opaqueLocation).readBytes()
        destination.writeBytes(bytes)
        bytes.size.toLong()
    }

    private fun pngBytes(color: Color): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        repeat(image.height) { y -> repeat(image.width) { x -> image.setRGB(x, y, color.rgb) } }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun truncatedPngPixelStream(): ByteArray {
        val encoded = pngBytes(Color(12, 34, 56))
        var offset = 8
        while (offset + 12 <= encoded.size) {
            val chunkLength = (encoded[offset].toInt() and 0xff shl 24) or
                (encoded[offset + 1].toInt() and 0xff shl 16) or
                (encoded[offset + 2].toInt() and 0xff shl 8) or
                (encoded[offset + 3].toInt() and 0xff)
            val chunkType = encoded.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (chunkType == "IDAT") {
                return encoded.copyOf(offset + 8 + (chunkLength / 2).coerceAtLeast(1))
            }
            offset += 12 + chunkLength
        }
        error("Generated PNG has no IDAT chunk")
    }

    private data class CommittedFile(val file: File, val revision: Long)

    private class MutableSnapshotLookup(
        @Volatile var current: PartialDownloadSnapshot?,
    ) : PartialDownloadSnapshotLookup {
        val calls = AtomicInteger()

        override fun snapshot(chapterId: Long, identity: DownloadChapterIdentity): PartialDownloadSnapshot? {
            calls.incrementAndGet()
            return current
        }
    }

    private class ImageSource(
        private val server: MockWebServer,
        private val imageUrls: List<String>,
        val httpClient: OkHttpClient = OkHttpClient(),
        var failImageUrlResolution: Boolean = false,
    ) : CatalogueSource {
        override val id = 42L
        override val name = "partial-reader-source"
        override val lang = "en"
        override val supportsLatest = false
        val imageUrlResolutionCalls = AtomicInteger()

        @Suppress("unused")
        fun getClient(): OkHttpClient = httpClient

        @Suppress("unused")
        fun getHeaders(): Headers = Headers.headersOf("Referer", server.url("/").toString())

        @Suppress("unused")
        suspend fun getImageUrl(page: Page): String {
            imageUrlResolutionCalls.incrementAndGet()
            check(!failImageUrlResolution) { "Known image URL must not be resolved again" }
            return imageUrls[page.index]
        }

        override suspend fun getPageList(chapter: SChapter): List<Page> = imageUrls.mapIndexed { index, url ->
            Page(index = index, url = "/page/$index", imageUrl = url)
        }

        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(emptyList(), false)

        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override fun getFilterList(): FilterList = FilterList()
    }

    private companion object {
        const val CHAPTER_ID = 1L
    }
}
