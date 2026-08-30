package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.ReaderChapterLoadState
import okhttp3.OkHttpClient
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderRuntimeLifecycleLinearizationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `session generation immediately fences non cooperative full decode while next chapter is loading then empty`() =
        runTest {
            val decodeEntered = CompletableDeferred<Unit>()
            val releaseDecode = CompletableDeferred<Unit>()
            val decodeReturned = CompletableDeferred<Unit>()
            val disposeCalls = AtomicInteger()
            var runtime: DesktopReaderRuntime? = null
            var lateAcquire: Deferred<DesktopReaderImageAssetLease?>? = null

            val decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeEntered.complete(Unit)
                withContext(NonCancellable) { releaseDecode.await() }
                imageAsset(disposeCalls).also { decodeReturned.complete(Unit) }
            }

            try {
                val currentDirectory = imageDirectory("current")
                val emptyNextDirectory = tempDir.resolve("empty-next").also { it.mkdirs() }
                val createdRuntime = runtimeFactory(decoder).createRuntime(localContext(1L, currentDirectory), this)
                runtime = createdRuntime
                advanceUntilIdle()

                val firstSnapshot = createdRuntime.session.state.value.snapshot
                val firstPage = firstSnapshot.activeChapter.pages.single()
                val firstRef = requireNotNull(firstPage.encodedPageRef)
                val firstKey = ReaderPageDecodeKey(
                    contentKey = ReaderPageContentOpenRequest(
                        pageId = firstPage.id,
                        generation = firstSnapshot.generation,
                        encodedPageRef = firstRef,
                    ),
                    purpose = PageDecodePurpose.FULL_PAGE,
                    maxWidth = 2_048,
                    maxHeight = 2_048,
                )
                val pendingAcquire = async { createdRuntime.pageImagePipeline.acquire(firstKey) }
                lateAcquire = pendingAcquire
                decodeEntered.await()

                createdRuntime.session.activate(localContext(2L, emptyNextDirectory))
                val loadingSnapshot = createdRuntime.session.state.value.snapshot
                val secondGeneration = loadingSnapshot.generation
                val pipelineGenerationAtPublication = createdRuntime.pageImagePipeline.snapshot().minimumGeneration
                val stalePresentation = runCatching {
                    createdRuntime.presentationImageOwner.createHolder(
                        identity = DesktopReaderPresentationImageSlotIdentity(
                            pageId = firstPage.id,
                            generation = firstSnapshot.generation,
                        ),
                        decodeKey = firstKey,
                    )
                }
                stalePresentation.getOrNull()?.close()

                val terminalSnapshot = withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        createdRuntime.session.state.first { state ->
                            state.snapshot.generation == secondGeneration &&
                                state.snapshot.activeChapter.loadState !is ReaderChapterLoadState.LoadingPageList
                        }.snapshot
                    }
                }
                releaseDecode.complete(Unit)
                decodeReturned.await()
                runCurrent()
                val lateResult = runCatching { pendingAcquire.await() }.getOrNull()
                lateResult?.close()

                assertAll(
                    {
                        assertTrue(
                            loadingSnapshot.activeChapter.loadState is ReaderChapterLoadState.LoadingPageList,
                            "The generation fence must advance from the synchronously published loading snapshot",
                        )
                    },
                    { assertTrue(secondGeneration > firstSnapshot.generation) },
                    {
                        assertEquals(
                            secondGeneration,
                            pipelineGenerationAtPublication,
                            "Runtime wiring must advance the pipeline before the replacement page list is ready",
                        )
                    },
                    {
                        assertTrue(
                            stalePresentation.exceptionOrNull() is IllegalStateException,
                            "The presentation owner must reject generation-one holders as soon as generation two is published",
                        )
                    },
                    {
                        assertFalse(
                            terminalSnapshot.activeChapter.loadState is ReaderChapterLoadState.LoadingPageList,
                        )
                    },
                    { assertTrue(terminalSnapshot.activeChapter.pages.isEmpty()) },
                    { assertNull(lateResult, "A non-cooperative old-generation decode must not publish a caller lease") },
                    { assertEquals(1, disposeCalls.get(), "The rejected decoded asset must be released exactly once") },
                    {
                        assertFalse(
                            createdRuntime.pageImagePipeline.snapshot().cache.keys.any {
                                it.generation < secondGeneration
                            },
                            "An empty replacement chapter must not leave the previous generation cached",
                        )
                    },
                )
            } finally {
                releaseDecode.complete(Unit)
                lateAcquire?.let { pending -> runCatching { pending.await()?.close() } }
                runtime?.close()
            }
        }

    @Test
    fun `runtime close continues every stage and suppresses later failures behind the first`() {
        val fixture = closeFixture()
        val firstFailure = IllegalStateException("presentation-close")
        val laterFailure = IllegalArgumentException("pipeline-close")
        every { fixture.presentationImageOwner.close() } throws firstFailure
        every { fixture.pageImagePipeline.close() } throws laterFailure

        val thrown = assertThrows(IllegalStateException::class.java) { fixture.runtime.close() }

        assertSame(firstFailure, thrown)
        assertEquals(listOf(laterFailure), thrown.suppressed.toList())
        verifyOrder {
            fixture.prefetchPreferenceJob.cancel()
            fixture.presentationImageOwner.close()
            fixture.pageImagePipeline.close()
            fixture.session.close()
            fixture.pageContentOwner.close()
            fixture.contentAdapter.close()
        }
        verify(exactly = 1) { fixture.prefetchPreferenceJob.cancel() }
        fixture.closeStages.forEach { stage -> verify(exactly = 1) { stage.close() } }
    }

    @Test
    fun `cached asset disposer failure still closes production session store and content adapter`() = runTest {
        val disposerFailure = IllegalStateException("decoded-asset-dispose")
        val disposeCalls = AtomicInteger()
        var runtime: DesktopReaderRuntime? = null

        val decoder = DesktopReaderPageImageDecoder { _, _ ->
            imageAsset(disposeCalls) { throw disposerFailure }
        }

        try {
            val directory = imageDirectory("throwing-disposer")
            val createdRuntime = runtimeFactory(decoder).createRuntime(localContext(11L, directory), this)
            runtime = createdRuntime
            advanceUntilIdle()
            val snapshot = createdRuntime.session.state.value.snapshot
            val page = snapshot.activeChapter.pages.single()
            val ref = requireNotNull(page.encodedPageRef)
            val ownedRef = createdRuntime.encodedPageStore.cacheRef(
                pageId = page.id,
                discriminator = "inactive-session-check",
            )
            val callerLease = requireNotNull(
                createdRuntime.pageImagePipeline.acquire(
                    ReaderPageDecodeKey(
                        contentKey = ReaderPageContentOpenRequest(page.id, snapshot.generation, ref),
                        purpose = PageDecodePurpose.FULL_PAGE,
                        maxWidth = 2_048,
                        maxHeight = 2_048,
                    ),
                ),
            )
            callerLease.close()

            val thrown = assertThrows(IllegalStateException::class.java) { createdRuntime.close() }
            val storeError = runCatching {
                createdRuntime.encodedPageStore.store(ownedRef) { 0L }
            }.exceptionOrNull()

            assertAll(
                { assertSame(disposerFailure, thrown) },
                { assertEquals(1, disposeCalls.get()) },
                { assertTrue(createdRuntime.pageImagePipeline.snapshot().closed) },
                {
                    assertThrows(IllegalStateException::class.java) {
                        createdRuntime.session.activate(localContext(12L, tempDir.resolve("after-close")))
                    }
                },
                { assertTrue(storeError is IllegalStateException, "The encoded-store runtime lease must be ended") },
                {
                    assertThrows(IllegalStateException::class.java) {
                        createdRuntime.contentAdapter.reserveChapter(chapterId = 12L, leaseGeneration = 1L)
                    }
                },
            )
        } finally {
            runCatching { runtime?.close() }
        }
    }

    @Test
    fun `concurrent and repeated runtime close invokes every owned stage exactly once`() = runTest {
        val fixture = closeFixture()

        coroutineScope {
            List(16) {
                async(Dispatchers.Default) { fixture.runtime.close() }
            }.awaitAll()
        }
        fixture.runtime.close()

        verify(exactly = 1) { fixture.prefetchPreferenceJob.cancel() }
        fixture.closeStages.forEach { stage -> verify(exactly = 1) { stage.close() } }
    }

    @Test
    fun `concurrent close waits for the owner and observes the same failure without reentrant deadlock`() = runTest {
        val fixture = closeFixture()
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondReturned = CountDownLatch(1)
        val closeFailure = IllegalStateException("gated-close")
        every { fixture.presentationImageOwner.close() } answers {
            fixture.runtime.close()
            closeEntered.countDown()
            check(releaseClose.await(5, TimeUnit.SECONDS))
            throw closeFailure
        }

        val first = async(Dispatchers.Default) {
            runCatching { fixture.runtime.close() }.exceptionOrNull()
        }
        assertTrue(withContext(Dispatchers.IO) { closeEntered.await(5, TimeUnit.SECONDS) })
        val second = async(Dispatchers.Default) {
            secondStarted.countDown()
            runCatching { fixture.runtime.close() }.exceptionOrNull().also { secondReturned.countDown() }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { secondStarted.await(5, TimeUnit.SECONDS) })
            assertFalse(
                withContext(Dispatchers.IO) { secondReturned.await(200, TimeUnit.MILLISECONDS) },
                "A concurrent close caller must not return before the owning close attempt completes",
            )
        } finally {
            releaseClose.countDown()
        }

        assertSame(closeFailure, first.await())
        assertSame(closeFailure, second.await())
        verify(exactly = 1) { fixture.prefetchPreferenceJob.cancel() }
        fixture.closeStages.forEach { stage -> verify(exactly = 1) { stage.close() } }
    }

    private fun runtimeFactory(
        decoder: DesktopReaderPageImageDecoder = SkiaDesktopReaderPageImageDecoder(),
    ) = DesktopReaderRuntimeFactory(
        prefs = ReaderPreferences(),
        downloadProvider = DesktopDownloadProvider(tempDir.resolve("downloads")),
        sourceManager = mockk<SourceManager>(relaxed = true),
        networkHelper = NetworkHelper(OkHttpClient()),
        progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
        mangaRepository = null,
        encodedCacheDirectory = tempDir.resolve("encoded"),
        pageImageDecoder = decoder,
    )

    private fun imageDirectory(name: String): File = tempDir.resolve(name).also { directory ->
        directory.mkdirs()
        directory.resolve("001.png").writeBytes(pngBytes())
    }

    private fun localContext(chapterId: Long, directory: File) = DesktopReaderChapterContext(
        chapterId = chapterId,
        sourceId = 42L,
        chapterUrl = directory.absolutePath,
        mangaTitle = "Manga",
        chapterTitle = "Chapter $chapterId",
        chapterNumber = chapterId.toDouble(),
        chapterIndex = chapterId.toInt() - 1,
        initialPage = 0,
        wasRead = false,
        localChapterPath = directory.absolutePath,
    )

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        return ByteArrayOutputStream().also { output -> ImageIO.write(image, "png", output) }.toByteArray()
    }

    private fun imageAsset(
        disposeCalls: AtomicInteger,
        afterDispose: () -> Unit = {},
    ): DesktopReaderImageAsset {
        val bitmap = Bitmap().apply { check(allocN32Pixels(2, 2)) }
        return DesktopReaderImageAsset(
            bitmap = bitmap.asComposeImageBitmap(),
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            estimatedBytes = bitmap.width.toLong() * bitmap.height * 4L,
            sampled = false,
            disposer = {
                bitmap.close()
                disposeCalls.incrementAndGet()
                afterDispose()
            },
        )
    }

    private fun closeFixture(): RuntimeCloseFixture {
        val pageImagePipeline = mockk<DesktopReaderPageImagePipeline>(relaxed = true)
        val presentationImageOwner = mockk<DesktopReaderPresentationImageOwner>(relaxed = true)
        val pageContentOwner = mockk<DesktopReaderPageContentOwner>(relaxed = true)
        val session = mockk<DesktopReaderSession>(relaxed = true)
        val encodedPageStore = mockk<DesktopReaderEncodedPageStore>(relaxed = true)
        val prefetchPreferenceJob = mockk<Job>(relaxed = true)
        val contentAdapter = mockk<DesktopReaderContentAdapter>(relaxed = true)
        val runtime = DesktopReaderRuntime(
            prefs = ReaderPreferences(),
            pageImagePipeline = pageImagePipeline,
            presentationImageOwner = presentationImageOwner,
            pageContentOwner = pageContentOwner,
            session = session,
            encodedPageStore = encodedPageStore,
            prefetchPreferenceJob = prefetchPreferenceJob,
            contentAdapter = contentAdapter,
        )
        return RuntimeCloseFixture(
            runtime = runtime,
            pageImagePipeline = pageImagePipeline,
            presentationImageOwner = presentationImageOwner,
            pageContentOwner = pageContentOwner,
            session = session,
            prefetchPreferenceJob = prefetchPreferenceJob,
            contentAdapter = contentAdapter,
        )
    }

    private data class RuntimeCloseFixture(
        val runtime: DesktopReaderRuntime,
        val pageImagePipeline: DesktopReaderPageImagePipeline,
        val presentationImageOwner: DesktopReaderPresentationImageOwner,
        val pageContentOwner: DesktopReaderPageContentOwner,
        val session: DesktopReaderSession,
        val prefetchPreferenceJob: Job,
        val contentAdapter: DesktopReaderContentAdapter,
    ) {
        val closeStages: List<AutoCloseable> = listOf(
            presentationImageOwner,
            pageImagePipeline,
            session,
            pageContentOwner,
            contentAdapter,
        )
    }
}
