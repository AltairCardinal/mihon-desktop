package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.ui.browse.localReaderScreen
import mihon.desktop.ui.reader.ReaderLifecycleEffect
import mihon.desktop.ui.reader.ReaderModeState
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageId
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderRuntimeFactoryTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `production runtime follows persisted next chapter prefetch changes`() = runTest {
        val legacy = Preferences.userRoot().node("/mihon/runtime-prefetch/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val factory = DesktopReaderRuntimeFactory(
            prefs = prefs,
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-prefetch-mode")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-prefetch-mode"),
        )
        val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 61L,
                sourceId = 42L,
                chapterUrl = "/chapter/61",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 61",
                chapterNumber = 61.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )
        try {
            assertEquals(NextChapterPrefetchMode.OFF, runtime.session.currentNextChapterPrefetchMode)

            prefs.nextChapterPrefetchMode = NextChapterPrefetchMode.FIRST_VIEWPORT
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.FIRST_VIEWPORT, runtime.session.currentNextChapterPrefetchMode)
        } finally {
            runtime.close()
            legacy.removeNode()
        }
    }

    @Test
    fun `production runtime preference changes drive off first viewport and full request sets`() = runTest {
        val legacy = Preferences.userRoot().node("/mihon/runtime-prefetch-requests/${System.nanoTime()}")
        val prefs = ReaderPreferences(InMemoryPreferenceStore(), legacy).apply {
            nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
        }
        val currentDirectory = tempDir.resolve("runtime-prefetch-current").also(File::mkdirs).apply {
            resolve("001.png").writeBytes(byteArrayOf(1, 2, 3))
        }
        val nextArchive = archive("runtime-prefetch-next.cbz", pageCount = 4)
        val laterArchive = archive("runtime-prefetch-later.cbz", pageCount = 3)
        val factory = DesktopReaderRuntimeFactory(
            prefs = prefs,
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-prefetch-requests")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-prefetch-requests"),
        )
        val runtime = factory.createRuntime(localContext(71L, currentDirectory), this)
        try {
            advanceUntilIdle()
            val opening = runtime.session.state.value.snapshot
            runtime.session.onFirstPagePresented(opening.activeChapter.pages.single().id, opening.generation)
            advanceUntilIdle()
            runtime.session.updateNextChapter(localContext(72L, nextArchive), firstViewportPageCount = 2)
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.OFF, runtime.session.currentNextChapterPrefetchMode)
            assertEquals(0, runtime.encodedPageStore.diagnostics().refs.size)

            prefs.nextChapterPrefetchMode = NextChapterPrefetchMode.FIRST_VIEWPORT
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.FIRST_VIEWPORT, runtime.session.currentNextChapterPrefetchMode)
            assertEquals(2, runtime.encodedPageStore.diagnostics().refs.size)

            prefs.nextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.FULL_NEXT_CHAPTER, runtime.session.currentNextChapterPrefetchMode)
            assertEquals(4, runtime.encodedPageStore.diagnostics().refs.size)

            prefs.nextChapterPrefetchMode = NextChapterPrefetchMode.OFF
            advanceUntilIdle()
            runtime.session.updateNextChapter(localContext(73L, laterArchive), firstViewportPageCount = 2)
            advanceUntilIdle()

            assertEquals(NextChapterPrefetchMode.OFF, runtime.session.currentNextChapterPrefetchMode)
            assertEquals(4, runtime.encodedPageStore.diagnostics().refs.size)
        } finally {
            runtime.close()
            legacy.removeNode()
        }
    }

    @Test
    fun `local reader session identity never becomes a durable chapter progress id`() = runTest {
        val localChapter = tempDir.resolve("local/Manga/Chapter 1").also { directory ->
            directory.mkdirs()
            directory.resolve("001.png").writeBytes(byteArrayOf(1, 2, 3))
        }
        val screen = localReaderScreen(localChapter, mangaName = "Manga", chapterTitle = "Chapter 1")
        val progressTracker = mockk<ReaderProgressTracker>(relaxed = true)
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-local-progress")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = progressTracker,
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-local-progress"),
        )
        val runtime = factory.createRuntime(screen.initialContext(), this)
        try {
            advanceUntilIdle()
            val pageId = runtime.session.state.value.snapshot.activeChapter.pages.single().id

            runtime.session.settleViewport(setOf(pageId), pageId)
            advanceUntilIdle()

            coVerify(exactly = 0) { progressTracker.track(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            runtime.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `leaving composition does not close the screen-owned reader runtime`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-lifecycle")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-lifecycle"),
        )
        val context = DesktopReaderChapterContext(
            chapterId = 31L,
            sourceId = 42L,
            chapterUrl = "/chapter/31",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 31",
            chapterNumber = 31.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
        )
        val runtime = factory.createRuntime(context, this)
        val scene = ImageComposeScene(320, 240, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent { ReaderLifecycleEffect(runtime) }
            scene.render()
            assertEquals(true, ReaderModeState.isInReaderMode)

            scene.close()

            assertEquals(false, ReaderModeState.isInReaderMode)
            assertDoesNotThrow {
                runtime.session.activate(
                    context.copy(chapterId = 32L, chapterUrl = "/chapter/32", chapterTitle = "Chapter 32"),
                )
            }
        } finally {
            runCatching(scene::close)
            runtime.close()
        }
    }

    @Test
    fun `production screen model owns its runtime until Voyager disposal`() {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-owner")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-owner"),
        )
        val context = DesktopReaderChapterContext(
            chapterId = 41L,
            sourceId = 42L,
            chapterUrl = "/chapter/41",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 41",
            chapterNumber = 41.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
        )

        lateinit var runtime: DesktopReaderRuntime
        var productionClosedCallbacks = 0
        val model = factory.createScreenModel(
            initialContext = context,
            isWebtoon = false,
            mangaViewerFlags = 0L,
            dualPageOverride = null,
            onProductionClosed = {
                assertThrows(IllegalStateException::class.java) { runtime.session.activate(context) }
                productionClosedCallbacks += 1
            },
        )
        runtime = requireNotNull(model.runtime)
        assertDoesNotThrow {
            runtime.session.activate(
                context.copy(chapterId = 42L, chapterUrl = "/chapter/42", chapterTitle = "Chapter 42"),
            )
        }

        model.onDispose()

        assertThrows(IllegalStateException::class.java) { runtime.session.activate(context) }
        assertEquals(1, productionClosedCallbacks)
    }

    @Test
    fun `production factory creates one shared core and exposes its canonical state to the model`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded"),
        )
        val context = DesktopReaderChapterContext(
            chapterId = 17L,
            sourceId = 42L,
            chapterUrl = "/chapter/17",
            mangaTitle = "Manga",
            chapterTitle = "Chapter 17",
            chapterNumber = 17.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = false,
        )

        val runtime = factory.createRuntime(context, this)
        try {
            val sessionState = runtime.session.state.value
            val model = factory.createModel(
                runtime = runtime,
                isWebtoon = false,
                mangaViewerFlags = 0L,
                dualPageOverride = null,
            )

            assertNotNull(runtime.session.core)
            assertSame(runtime.session.core.snapshot, sessionState.snapshot)
            assertEquals(sessionState, mihon.desktop.reader.DesktopReaderSessionState(model.state.value.context, model.state.value.session))
            assertInstanceOf(
                ReaderChapterLoadState.LoadingPageList::class.java,
                model.state.value.session.activeChapter.loadState,
            )

            val target = context.copy(
                chapterId = 18L,
                chapterUrl = "/chapter/18",
                chapterTitle = "Chapter 18",
                chapterNumber = 18.0,
            )
            val core = runtime.session.core
            model.activateChapter(target)

            assertSame(core, runtime.session.core)
            assertEquals(target, model.state.value.context)
            assertEquals(ReaderChapterId(18L), model.state.value.session.activeChapter.id)
            assertInstanceOf(
                ReaderChapterLoadState.LoadingPageList::class.java,
                model.state.value.session.activeChapter.loadState,
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `production runtime image pipeline reads its own encoded page store`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-preloader-wiring")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-preloader-wiring"),
        )
        val localDirectory = tempDir.resolve("preloader-wiring-local").also { directory ->
            directory.mkdirs()
            directory.resolve("001.png").writeBytes(pngBytes())
        }
        val context = localContext(81L, localDirectory)
        val runtime = factory.createRuntime(context, this)
        try {
            advanceUntilIdle()
            val snapshot = runtime.session.state.value.snapshot
            val page = snapshot.activeChapter.pages.single()
            val ref = requireNotNull(page.encodedPageRef)

            val lease = requireNotNull(
                runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        contentKey = ReaderPageContentOpenRequest(page.id, snapshot.generation, ref),
                        purpose = PageDecodePurpose.FULL_PAGE,
                        maxWidth = 2_048,
                        maxHeight = 2_048,
                    ),
                ),
            )
            lease.close()
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `production visible consumers share one image pipeline open and decode`() = runTest {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val now = AtomicLong()
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-content-owner")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-content-owner"),
            readerIoProbe = ReaderIoProbe(events::add),
            readerMonotonicClock = ReaderMonotonicClock(now::incrementAndGet),
        )
        val bytes = pngBytes()
        val localDirectory = tempDir.resolve("content-owner-local").also { directory ->
            directory.mkdirs()
            directory.resolve("001.png").writeBytes(bytes)
        }
        val runtime = factory.createRuntime(localContext(83L, localDirectory), this)
        try {
            advanceUntilIdle()
            val snapshot = runtime.session.state.value.snapshot
            val page = snapshot.activeChapter.pages.single()
            val ref = requireNotNull(page.encodedPageRef)
            val firstVisibleLease = requireNotNull(
                runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        contentKey = ReaderPageContentOpenRequest(page.id, snapshot.generation, ref),
                        purpose = PageDecodePurpose.FULL_PAGE,
                        maxWidth = 2_048,
                        maxHeight = 2_048,
                    ),
                ),
            )
            val secondVisibleLease = requireNotNull(
                runtime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        contentKey = ReaderPageContentOpenRequest(page.id, snapshot.generation, ref),
                        purpose = PageDecodePurpose.FULL_PAGE,
                        maxWidth = 2_048,
                        maxHeight = 2_048,
                    ),
                ),
            )
            try {
                assertSame(firstVisibleLease.asset.bitmap, secondVisibleLease.asset.bitmap)
                assertEquals(
                    1,
                    events.count { event ->
                        event.type == ReaderIoEventType.OPEN_PAGE &&
                            event.pageId == page.id &&
                            event.generation == snapshot.generation
                        },
                )
                assertEquals(
                    1,
                    events.count { event ->
                        event.type == ReaderIoEventType.DECODE &&
                            event.pageId == page.id &&
                            event.generation == snapshot.generation
                    },
                )
            } finally {
                secondVisibleLease.close()
                firstVisibleLease.close()
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `production runtime binds the independent 512 MiB encoded cache policy`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-encoded-budget")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-budget"),
        )
        val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 82L,
                sourceId = 42L,
                chapterUrl = "/chapter/82",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 82",
                chapterNumber = 82.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )

        try {
            assertEquals(512L * 1024L * 1024L, runtime.encodedPageStore.diagnostics().maxBytes)
            assertEquals(
                DesktopReaderEncodedPageStore.DEFAULT_MAX_BYTES,
                runtime.encodedPageStore.diagnostics().maxBytes,
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `production runtime binds and closes the default unified image memory policy`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-decoded-budget")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-decoded-budget"),
        )
        val runtime = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 84L,
                sourceId = 42L,
                chapterUrl = "/chapter/84",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 84",
                chapterNumber = 84.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )

        try {
            val openSnapshot = runtime.pageImagePipeline.snapshot()
            assertEquals(DesktopReaderPageImagePipeline.DEFAULT_CACHE_ENTRIES, openSnapshot.cache.maxEntries)
            assertEquals(DesktopReaderPageImagePipeline.DEFAULT_TOTAL_IMAGE_BYTES, openSnapshot.cache.maxBytes)
            assertEquals(DesktopReaderPageImagePipeline.DEFAULT_TILE_CACHE_ENTRIES, openSnapshot.tileCache.maxEntries)
            assertEquals(DesktopReaderPageImagePipeline.DEFAULT_TOTAL_IMAGE_BYTES, openSnapshot.tileCache.maxBytes)
            assertEquals(
                DesktopReaderPageImagePipeline.DEFAULT_TOTAL_IMAGE_BYTES,
                openSnapshot.memory.maxBytes,
            )
        } finally {
            runtime.close()
        }

        val closedSnapshot = runtime.pageImagePipeline.snapshot()
        assertTrue(closedSnapshot.closed)
        assertTrue(closedSnapshot.cache.keys.isEmpty())
        assertEquals(0, closedSnapshot.cache.entryCount)
    }

    @Test
    fun `production factory coordinates encoded cache across concurrent reader runtimes`() = runTest {
        val factory = DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads-shared-cache")),
            sourceManager = mockk<SourceManager>(relaxed = true),
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
            mangaRepository = null,
            encodedCacheDirectory = tempDir.resolve("encoded-shared-cache"),
        )
        val first = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 51L,
                sourceId = 42L,
                chapterUrl = "/chapter/51",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 51",
                chapterNumber = 51.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )
        val second = factory.createRuntime(
            DesktopReaderChapterContext(
                chapterId = 52L,
                sourceId = 42L,
                chapterUrl = "/chapter/52",
                mangaTitle = "Manga",
                chapterTitle = "Chapter 52",
                chapterNumber = 52.0,
                chapterIndex = 0,
                initialPage = 0,
                wasRead = false,
            ),
            this,
        )
        try {
            assertEquals(true, first.encodedPageStore.sharesCoordinatorWith(second.encodedPageStore))
        } finally {
            first.close()
            second.close()
        }
    }

    private fun archive(name: String, pageCount: Int): File = tempDir.resolve(name).also { archive ->
        ZipOutputStream(archive.outputStream()).use { output ->
            repeat(pageCount) { index ->
                output.putNextEntry(ZipEntry("${(index + 1).toString().padStart(3, '0')}.png"))
                output.write(byteArrayOf(1, 2, 3, index.toByte()))
                output.closeEntry()
            }
        }
    }

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.BLUE
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().also { output -> ImageIO.write(image, "png", output) }.toByteArray()
    }

    private fun localContext(chapterId: Long, path: File) = DesktopReaderChapterContext(
        chapterId = chapterId,
        sourceId = 42L,
        chapterUrl = path.absolutePath,
        mangaTitle = "Manga",
        chapterTitle = "Chapter $chapterId",
        chapterNumber = chapterId.toDouble(),
        chapterIndex = 0,
        initialPage = 0,
        wasRead = false,
        localChapterPath = path.absolutePath,
    )
}
