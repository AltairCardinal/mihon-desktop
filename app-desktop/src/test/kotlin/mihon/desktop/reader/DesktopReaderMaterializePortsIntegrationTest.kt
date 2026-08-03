package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.extension.ExtensionClassLoader
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.error.AppError
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderMaterializePortsIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `failed encoded write removes partial bytes before retry lookup`() = runTest {
        val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-partial"), maxBytes = 1_000_000L)
        store.beginSession(emptySet())
        val ref = store.cacheRef(ReaderPageId(ReaderChapterId(1L), 0), "partial")
        val destination = store.destinationFile(ref)

        val failure = runCatching {
            store.store(ref) {
                store.destinationFile(ref).writeBytes(byteArrayOf(1, 2, 3))
                error("interrupted")
            }
        }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, failure)
        assertFalse(destination.exists())
        assertFalse(store.contains(ref))
    }

    @Test
    fun `empty encoded write is rejected instead of publishing a ready blank page`() = runTest {
        val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-empty"), maxBytes = 1_000_000L)
        store.beginSession(emptySet())
        val ref = store.cacheRef(ReaderPageId(ReaderChapterId(1L), 0), "empty")
        val destination = store.destinationFile(ref)

        val failure = runCatching {
            store.store(ref) {
                store.destinationFile(ref).writeBytes(byteArrayOf())
                0L
            }
        }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, failure)
        assertFalse(destination.exists())
        assertFalse(store.contains(ref))
    }

    @Test
    fun `concurrent reader stores share staging quota and active ref leases`() = runTest {
        val cacheDirectory = tempDir.resolve("encoded-concurrent-readers")
        val coordinator = DesktopReaderEncodedPageStoreCoordinator(cacheDirectory, maxBytes = 6L)
        val firstStore = coordinator.openSessionStore()
        val secondStore = coordinator.openSessionStore()
        firstStore.beginSession(emptySet())
        val firstRef = firstStore.cacheRef(ReaderPageId(ReaderChapterId(1L), 0), "first-active")
        val writerStarted = CompletableDeferred<Unit>()
        val finishWriter = CompletableDeferred<Unit>()
        val firstWrite = async {
            firstStore.store(firstRef) {
                firstStore.destinationFile(firstRef).writeBytes(byteArrayOf(1, 2, 3, 4))
                writerStarted.complete(Unit)
                finishWriter.await()
                firstStore.destinationFile(firstRef).length()
            }
        }
        writerStarted.await()

        secondStore.beginSession(emptySet())
        finishWriter.complete(Unit)
        assertInstanceOf(EncodedPageStoreWriteResult.Stored::class.java, firstWrite.await())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), firstStore.read(firstRef))

        val secondRef = secondStore.cacheRef(ReaderPageId(ReaderChapterId(2L), 0), "second-active")
        val blockedByFirstLease = secondStore.store(secondRef) {
            secondStore.destinationFile(secondRef).writeBytes(byteArrayOf(5, 6, 7, 8))
            secondStore.destinationFile(secondRef).length()
        }
        assertInstanceOf(EncodedPageStoreWriteResult.RejectedQuota::class.java, blockedByFirstLease)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), firstStore.read(firstRef))

        firstStore.endSession()
        val storedAfterLeaseRelease = secondStore.store(secondRef) {
            secondStore.destinationFile(secondRef).writeBytes(byteArrayOf(5, 6, 7, 8))
            secondStore.destinationFile(secondRef).length()
        }
        assertInstanceOf(EncodedPageStoreWriteResult.Stored::class.java, storedAfterLeaseRelease)
        assertFalse(firstStore.contains(firstRef))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), secondStore.read(secondRef))
        secondStore.endSession()
    }

    @Test
    fun `a new store instance reconciles prior cache bytes and enforces one cross-session quota`() = runTest {
        val cacheDirectory = tempDir.resolve("encoded-cross-session")
        val firstStore = DesktopReaderEncodedPageStore(cacheDirectory, maxBytes = 6L)
        firstStore.beginSession(emptySet())
        val firstRef = firstStore.cacheRef(ReaderPageId(ReaderChapterId(1L), 0), "first")
        val firstDestination = firstStore.destinationFile(firstRef)
        firstStore.store(firstRef) {
            firstStore.destinationFile(firstRef).writeBytes(byteArrayOf(1, 2, 3, 4))
            firstStore.destinationFile(firstRef).length()
        }
        firstStore.endSession()

        val secondStore = DesktopReaderEncodedPageStore(cacheDirectory, maxBytes = 6L)
        val restored = secondStore.beginSession(emptySet())
        assertEquals(setOf(firstRef), restored.availableRefs)
        assertEquals(4L, secondStore.diagnostics().usedBytes)

        val secondRef = secondStore.cacheRef(ReaderPageId(ReaderChapterId(2L), 0), "second")
        val secondDestination = secondStore.destinationFile(secondRef)
        secondStore.store(secondRef) {
            secondStore.destinationFile(secondRef).writeBytes(byteArrayOf(5, 6, 7, 8))
            secondStore.destinationFile(secondRef).length()
        }

        assertFalse(firstDestination.exists())
        assertTrue(secondDestination.isFile)
        assertEquals(setOf(secondRef), secondStore.diagnostics().refs)
        assertEquals(4L, secondStore.diagnostics().usedBytes)
    }

    @Test
    fun `replacement bytes stay invisible until one atomic commit`() = runTest {
        val cacheDirectory = tempDir.resolve("encoded-atomic")
        val store = DesktopReaderEncodedPageStore(cacheDirectory, maxBytes = 1_000_000L)
        store.beginSession(emptySet())
        val ref = store.cacheRef(ReaderPageId(ReaderChapterId(1L), 0), "atomic")
        val original = "committed-original".toByteArray()
        val replacement = "committed-replacement".toByteArray()
        store.store(ref) {
            store.destinationFile(ref).writeBytes(original)
            original.size.toLong()
        }
        val writerStarted = CompletableDeferred<Unit>()
        val finishWriter = CompletableDeferred<Unit>()

        val write = async {
            store.store(ref) {
                val staging = store.destinationFile(ref)
                staging.writeBytes("partial".toByteArray())
                writerStarted.complete(Unit)
                finishWriter.await()
                staging.writeBytes(replacement)
                staging.length()
            }
        }
        writerStarted.await()
        try {
            assertArrayEquals(original, store.read(ref))
        } finally {
            finishWriter.complete(Unit)
        }
        write.await()

        assertArrayEquals(replacement, store.read(ref))
        assertTrue(cacheDirectory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `same ref has one writer and cancelling a stale writer cannot delete the committed file`() = runTest {
        val cacheDirectory = tempDir.resolve("encoded-single-writer")
        val store = DesktopReaderEncodedPageStore(cacheDirectory, maxBytes = 1_000_000L)
        store.beginSession(emptySet())
        val ref = store.cacheRef(ReaderPageId(ReaderChapterId(1L), 0), "single-writer")
        val original = "original".toByteArray()
        val current = "current".toByteArray()
        store.store(ref) {
            store.destinationFile(ref).writeBytes(original)
            original.size.toLong()
        }
        val staleStarted = CompletableDeferred<Unit>()
        val currentStarted = CompletableDeferred<Unit>()
        val stale = async {
            store.store(ref) {
                store.destinationFile(ref).writeBytes("stale-partial".toByteArray())
                staleStarted.complete(Unit)
                awaitCancellation()
            }
        }
        staleStarted.await()
        val currentWrite = async {
            store.store(ref) {
                currentStarted.complete(Unit)
                store.destinationFile(ref).writeBytes(current)
                current.size.toLong()
            }
        }
        runCurrent()

        assertFalse(currentStarted.isCompleted)
        assertArrayEquals(original, store.read(ref))
        stale.cancelAndJoin()
        currentWrite.await()

        assertArrayEquals(current, store.read(ref))
        assertTrue(cacheDirectory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `download and local directory page lists expose ready encoded refs without copying`() = runTest {
        val downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads"))
        val downloaded = downloadProvider.chapterDownloadDir(42L, "Manga", "Chapter 1")
            .resolve("001.png")
            .also { file -> file.parentFile.mkdirs(); file.writeBytes(pngBytes(Color.RED)) }
        val local = tempDir.resolve("local/Chapter 2/001.png")
            .also { file -> file.parentFile.mkdirs(); file.writeBytes(pngBytes(Color.BLUE)) }
        val sourceManager = FakeDesktopSourceManager(emptyList())

        val downloadedPages = DesktopReaderChapterContentPort(
            context = context(chapterId = 1L),
            downloadProvider = downloadProvider,
            sourceManager = sourceManager,
        ).loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(1L), generation = 1L))
        val localPages = DesktopReaderChapterContentPort(
            context = context(chapterId = 2L, localChapterPath = local.parentFile.absolutePath),
            downloadProvider = downloadProvider,
            sourceManager = sourceManager,
        ).loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(2L), generation = 1L))

        assertEquals(ReaderPageLoadState.Ready, downloadedPages.single().initialLoadState)
        assertEquals(downloaded.toURI().toString(), downloadedPages.single().encodedPageRef?.value)
        assertEquals(ReaderPageLoadState.Ready, localPages.single().initialLoadState)
        assertEquals(local.toURI().toString(), localPages.single().encodedPageRef?.value)
    }

    @Test
    fun `archive page is materialized per page into the shared encoded store`() = runTest {
        val bytes = pngBytes(Color.GREEN)
        val archive = tempDir.resolve("chapter.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("pages/001.png"))
            zip.write(bytes)
            zip.closeEntry()
        }
        val downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads"))
        val sourceManager = FakeDesktopSourceManager(emptyList())
        val context = context(chapterId = 3L, localChapterPath = archive.absolutePath)
        val descriptor = DesktopReaderChapterContentPort(context, downloadProvider, sourceManager)
            .loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(3L), generation = 1L))
            .single()
        val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded"), maxBytes = 1_000_000L)
        store.beginSession(emptySet())
        val events = mutableListOf<ReaderPageMaterializeEvent>()

        val result = CanonicalReaderMaterializeExecutor.materializePage(
            request = ReaderPageFetchRequest(
                pageId = ReaderPageId(ReaderChapterId(3L), 0),
                generation = 1L,
                url = descriptor.url,
                imageUrl = descriptor.imageUrl,
            ),
            port = DesktopReaderPageFetchPort(
                context = context,
                descriptor = descriptor,
                sourceManager = sourceManager,
                networkHelper = NetworkHelper(OkHttpClient()),
                encodedPageStore = store,
            ),
            publish = { events += it; true },
        )

        val ready = assertInstanceOf(ReaderPageMaterializeResult.Ready::class.java, result)
        assertArrayEquals(bytes, store.read(ready.encodedPageRef))
        assertEquals(
            listOf(
                ReaderPageMaterializeEvent.ResolvingImage,
                ReaderPageMaterializeEvent.Downloading(ready.imageUrl),
                ReaderPageMaterializeEvent.Ready(ready.imageUrl, ready.encodedPageRef),
            ),
            events,
        )
    }

    @Test
    fun `online page uses source client and headers then decode reads the same encoded ref`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = pngBytes(Color.MAGENTA)
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            var sourceRequests = 0
            val sourceClient = OkHttpClient.Builder().addInterceptor { chain ->
                sourceRequests++
                assertEquals("reader-test", chain.request().header("Referer"))
                chain.proceed(chain.request())
            }.build()
            val source = PageSource(
                pages = listOf(Page(0, imageUrl = server.url("/001.png").toString())),
                httpClient = sourceClient,
            )
            val fallback = OkHttpClient.Builder().addInterceptor {
                error("The global fallback client must not fetch an extension page")
            }.build()
            val networkHelper = NetworkHelper(fallback) { fallback }
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val context = context(chapterId = 4L)
            val descriptor = DesktopReaderChapterContentPort(
                context,
                DesktopDownloadProvider(tempDir.resolve("downloads")),
                sourceManager,
            ).loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(4L), 1L)).single()
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded"), maxBytes = 1_000_000L)
            store.beginSession(emptySet())

            val result = CanonicalReaderMaterializeExecutor.materializePage(
                ReaderPageFetchRequest(ReaderPageId(ReaderChapterId(4L), 0), 1L, descriptor.url, descriptor.imageUrl),
                DesktopReaderPageFetchPort(context, descriptor, sourceManager, networkHelper, store),
                publish = { true },
            )

            val ready = assertInstanceOf(ReaderPageMaterializeResult.Ready::class.java, result)
            assertEquals(1, sourceRequests)
            assertArrayEquals(bytes, store.read(ready.encodedPageRef))
            val preloader = PagePreloader(encodedPageReader = store::read, windowSize = 0)
            preloader.preloadEncoded(0, listOf(ready.encodedPageRef))
            assertTrue(preloader.get(0) != null)
        }
    }

    @Test
    fun `online page executes the source image request method body and per-page headers`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val bytes = pngBytes(Color.ORANGE)
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            val source = ImageRequestHttpSource(
                baseUrl = server.url("/").toString().removeSuffix("/"),
                httpClient = OkHttpClient(),
            )
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val readerContext = context(chapterId = 5L, sourceId = source.id)
            val descriptor = mihon.domain.reader.session.ReaderPageDescriptor(
                sourcePageIndex = 0,
                url = "page-token-5",
                imageUrl = server.url("/protected-image").toString(),
            )
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-image-request"), maxBytes = 1_000_000L)
            store.beginSession(emptySet())

            val result = CanonicalReaderMaterializeExecutor.materializePage(
                ReaderPageFetchRequest(
                    ReaderPageId(ReaderChapterId(5L), 0),
                    generation = 1L,
                    url = descriptor.url,
                    imageUrl = descriptor.imageUrl,
                ),
                DesktopReaderPageFetchPort(
                    readerContext,
                    descriptor,
                    sourceManager,
                    NetworkHelper(source.httpClient),
                    store,
                ),
                publish = { true },
            )

            assertInstanceOf(ReaderPageMaterializeResult.Ready::class.java, result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("page-token-5", request.headers["X-Page-Token"])
            assertEquals("source-default", request.headers["X-Source-Default"])
            assertEquals("signed-image-body", request.body?.utf8())
        }
    }

    @Test
    fun `child loaded source executes reflective getImage with its request semantics`() = runTest {
        val fixtureClassName = "readerfixture.ReflectiveImageSource"
        val classResource = fixtureClassName.replace('.', '/') + ".class"
        val extensionJar = tempDir.resolve("reflective-image-source.jar")
        JarOutputStream(extensionJar.outputStream()).use { output ->
            output.putNextEntry(JarEntry(classResource))
            requireNotNull(javaClass.classLoader.getResourceAsStream(classResource)).use { it.copyTo(output) }
            output.closeEntry()
        }

        ExtensionClassLoader(extensionJar.toURI().toURL(), javaClass.classLoader).use { classLoader ->
            val source = classLoader.loadClass(fixtureClassName).getDeclaredConstructor().newInstance() as CatalogueSource
            assertSame(classLoader, source.javaClass.classLoader)
            assertFalse(source is HttpSource)
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body(Buffer().write(pngBytes(Color.MAGENTA))).build())
                val descriptor = mihon.domain.reader.session.ReaderPageDescriptor(
                    sourcePageIndex = 0,
                    url = "child-page-token",
                    imageUrl = server.url("/child-protected-image").toString(),
                )
                val store = DesktopReaderEncodedPageStore(
                    tempDir.resolve("encoded-child-loader"),
                    maxBytes = 1_000_000L,
                )
                store.beginSession(emptySet())

                val result = CanonicalReaderMaterializeExecutor.materializePage(
                    ReaderPageFetchRequest(
                        ReaderPageId(ReaderChapterId(6L), 0),
                        generation = 1L,
                        url = descriptor.url,
                        imageUrl = descriptor.imageUrl,
                    ),
                    DesktopReaderPageFetchPort(
                        context(chapterId = 6L, sourceId = source.id),
                        descriptor,
                        FakeDesktopSourceManager(listOf(source)),
                        NetworkHelper(OkHttpClient()),
                        store,
                    ),
                    publish = { true },
                )

                assertInstanceOf(ReaderPageMaterializeResult.Ready::class.java, result)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("child-loader", request.headers["X-Reflection-Bridge"])
                assertEquals("child-page-token", request.headers["X-Page-Token"])
                assertEquals("child-loader-body", request.body?.utf8())
            }
        }
    }

    @Test
    fun `online failures retain shared empty authentication rate limit server and malformed errors`() = runTest {
        val emptySource = PageSource(emptyList(), OkHttpClient())
        val emptyPort = DesktopReaderChapterContentPort(
            context(),
            DesktopDownloadProvider(tempDir.resolve("downloads")),
            FakeDesktopSourceManager(listOf(emptySource)),
        )
        val empty = CanonicalReaderMaterializeExecutor.materializeChapter(
            ReaderChapterContentRequest(ReaderChapterId(1L), 1L),
            emptyPort,
        )
        assertEquals(AppError.NoResults, (empty as ReaderChapterMaterializeResult.Failed).error)

        MockWebServer().use { server ->
            server.start()
            val responses = listOf(403, 429, 500)
            responses.forEach { server.enqueue(MockResponse(code = it, body = "failure-$it")) }
            val source = PageSource(emptyList(), OkHttpClient())
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-errors"), maxBytes = 1_000_000L)
            store.beginSession(emptySet())
            val errors = responses.mapIndexed { index, _ ->
                val descriptor = mihon.domain.reader.session.ReaderPageDescriptor(
                    sourcePageIndex = index,
                    imageUrl = server.url("/page-$index").toString(),
                )
                CanonicalReaderMaterializeExecutor.materializePage(
                    ReaderPageFetchRequest(
                        ReaderPageId(ReaderChapterId(1L), index),
                        generation = 1L,
                        url = descriptor.url,
                        imageUrl = descriptor.imageUrl,
                    ),
                    DesktopReaderPageFetchPort(context(), descriptor, sourceManager, NetworkHelper(source.httpClient), store),
                    publish = { true },
                ) as ReaderPageMaterializeResult.Failed
            }.map { it.error }

            assertInstanceOf(AppError.Authentication::class.java, errors[0])
            assertInstanceOf(AppError.RateLimited::class.java, errors[1])
            assertInstanceOf(AppError.Server::class.java, errors[2])
        }

        val missingDescriptor = mihon.domain.reader.session.ReaderPageDescriptor(0, url = "/missing")
        val missingSource = PageSource(listOf(Page(0, url = "/missing")), OkHttpClient())
        val missingStore = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-missing"), maxBytes = 1_000_000L)
        missingStore.beginSession(emptySet())
        val malformed = CanonicalReaderMaterializeExecutor.materializePage(
            ReaderPageFetchRequest(ReaderPageId(ReaderChapterId(1L), 0), 1L, "/missing", null),
            DesktopReaderPageFetchPort(
                context(),
                missingDescriptor,
                FakeDesktopSourceManager(listOf(missingSource)),
                NetworkHelper(OkHttpClient()),
                missingStore,
            ),
            publish = { true },
        ) as ReaderPageMaterializeResult.Failed
        assertInstanceOf(AppError.MalformedData::class.java, malformed.error)
    }

    @Test
    fun `successful HTTP with a malformed image body fails before the encoded ref becomes ready`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .setHeader("Content-Type", "text/html")
                    .body("<html><body>not an image</body></html>")
                    .build(),
            )
            val source = PageSource(emptyList(), OkHttpClient())
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val store = DesktopReaderEncodedPageStore(tempDir.resolve("encoded-malformed-body"), maxBytes = 1_000_000L)
            store.beginSession(emptySet())
            val descriptor = mihon.domain.reader.session.ReaderPageDescriptor(
                sourcePageIndex = 0,
                imageUrl = server.url("/page-0").toString(),
            )

            val result = CanonicalReaderMaterializeExecutor.materializePage(
                ReaderPageFetchRequest(
                    ReaderPageId(ReaderChapterId(1L), 0),
                    generation = 1L,
                    url = descriptor.url,
                    imageUrl = descriptor.imageUrl,
                ),
                DesktopReaderPageFetchPort(
                    context(),
                    descriptor,
                    sourceManager,
                    NetworkHelper(source.httpClient),
                    store,
                ),
                publish = { true },
            )

            val failed = assertInstanceOf(ReaderPageMaterializeResult.Failed::class.java, result)
            assertInstanceOf(AppError.MalformedData::class.java, failed.error)
            assertTrue(store.diagnostics().refs.isEmpty())
            assertTrue(tempDir.resolve("encoded-malformed-body").walkTopDown().none { it.isFile })
        }
    }

    @Test
    fun `successful HTTP with a truncated pixel stream fails before the encoded ref becomes ready`() = runTest {
        val truncatedImage = truncatedPngPixelStream()
        assertTrue(
            SkiaImageDecoder.peekSize(truncatedImage) != null,
            "The regression fixture must retain a parseable image header",
        )
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(truncatedImage)).build())
            val source = PageSource(emptyList(), OkHttpClient())
            val sourceManager = FakeDesktopSourceManager(listOf(source))
            val store = DesktopReaderEncodedPageStore(
                tempDir.resolve("encoded-truncated-pixel-stream"),
                maxBytes = 1_000_000L,
            )
            store.beginSession(emptySet())
            val descriptor = mihon.domain.reader.session.ReaderPageDescriptor(
                sourcePageIndex = 0,
                imageUrl = server.url("/page-0").toString(),
            )

            val result = CanonicalReaderMaterializeExecutor.materializePage(
                ReaderPageFetchRequest(
                    ReaderPageId(ReaderChapterId(1L), 0),
                    generation = 1L,
                    url = descriptor.url,
                    imageUrl = descriptor.imageUrl,
                ),
                DesktopReaderPageFetchPort(
                    context(),
                    descriptor,
                    sourceManager,
                    NetworkHelper(source.httpClient),
                    store,
                ),
                publish = { true },
            )

            val failed = assertInstanceOf(ReaderPageMaterializeResult.Failed::class.java, result)
            assertInstanceOf(AppError.MalformedData::class.java, failed.error)
            assertTrue(store.diagnostics().refs.isEmpty())
            assertTrue(tempDir.resolve("encoded-truncated-pixel-stream").walkTopDown().none { it.isFile })
        }
    }

    private fun context(
        chapterId: Long = 1L,
        sourceId: Long = 42L,
        localChapterPath: String? = null,
    ) = DesktopReaderChapterContext(
        chapterId = chapterId,
        sourceId = sourceId,
        chapterUrl = "/chapter/$chapterId",
        mangaTitle = "Manga",
        chapterTitle = "Chapter $chapterId",
        chapterNumber = chapterId.toDouble(),
        chapterIndex = 0,
        initialPage = 0,
        wasRead = false,
        localChapterPath = localChapterPath,
    )

    private fun pngBytes(color: Color): ByteArray {
        val image = BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, color.rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun truncatedPngPixelStream(): ByteArray {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        repeat(image.height) { y ->
            repeat(image.width) { x ->
                image.setRGB(x, y, Color(x * 7 % 256, y * 11 % 256, (x * 13 + y * 17) % 256).rgb)
            }
        }
        val encoded = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
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

    private class PageSource(
        private val pages: List<Page>,
        val httpClient: OkHttpClient,
    ) : CatalogueSource {
        override val id = 42L
        override val name = "reader-pages"
        override val lang = "en"
        override val supportsLatest = false

        @Suppress("unused")
        fun getClient(): OkHttpClient = httpClient

        @Suppress("unused")
        fun getHeaders(): Headers = Headers.headersOf("Referer", "reader-test")

        @Suppress("unused")
        suspend fun getImageUrl(page: Page): String = page.imageUrl.orEmpty()

        override suspend fun getPageList(chapter: SChapter): List<Page> = pages
        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override fun getFilterList(): FilterList = FilterList()
    }

    private class ImageRequestHttpSource(
        override val baseUrl: String,
        val httpClient: OkHttpClient,
    ) : HttpSource() {
        override val name = "reader-image-request"
        override val lang = "en"
        override val supportsLatest = false
        override val client: OkHttpClient get() = httpClient

        override fun headersBuilder(): Headers.Builder =
            Headers.Builder().add("X-Source-Default", "source-default")

        override fun imageRequest(page: Page): Request = Request.Builder()
            .url(requireNotNull(page.imageUrl))
            .header("X-Source-Default", headers["X-Source-Default"]!!)
            .header("X-Page-Token", page.url)
            .post("signed-image-body".toRequestBody())
            .build()

        override fun popularMangaRequest(page: Int): Request = emptyRequest()
        override fun popularMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = emptyRequest()
        override fun searchMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)
        override fun latestUpdatesRequest(page: Int): Request = emptyRequest()
        override fun latestUpdatesParse(response: Response): MangasPage = MangasPage(emptyList(), false)
        override fun mangaDetailsParse(response: Response): SManga = SManga.create()
        override fun chapterListParse(response: Response): List<SChapter> = emptyList()
        override fun chapterPageParse(response: Response): SChapter = SChapter.create()
        override fun pageListParse(response: Response): List<Page> = emptyList()
        override fun imageUrlParse(response: Response): String = response.request.url.toString()

        private fun emptyRequest(): Request = Request.Builder().url("$baseUrl/unused").build()
    }
}
