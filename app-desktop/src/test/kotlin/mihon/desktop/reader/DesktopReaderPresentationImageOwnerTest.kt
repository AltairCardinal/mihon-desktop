package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO

class DesktopReaderPresentationImageOwnerTest {

    @Test
    fun `standard large JPEG reaches presentation Ready through the production pipeline`() = runTest {
        val fixture = fixture(
            scope = this,
            decoder = SkiaDesktopReaderPageImageDecoder(),
            encodedBytes = standardJpegBytes(),
        )

        try {
            fixture.owner.beginGeneration(1L)
            val holder = fixture.owner.createHolder(identity(), key())

            holder.acquire()

            val ready = holder.awaitReady()
            assertEquals(2_400, ready.asset.sourceWidth)
            assertEquals(3_500, ready.asset.sourceHeight)
            assertEquals(1_200, ready.asset.bitmap.width)
            assertEquals(1_750, ready.asset.bitmap.height)
            holder.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `edge matching consumer retains only cached full assets without opening or decoding`() = runTest {
        val decodeCalls = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                asset(tag = decodeCalls.incrementAndGet())
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val first = fixture.owner.createHolder(identity(pageIndex = 1), key(pageIndex = 1))
            val second = fixture.owner.createHolder(identity(pageIndex = 2), key(pageIndex = 2))
            first.acquire()
            second.acquire()
            first.awaitReady()
            second.awaitReady()
            first.close()
            second.close()
            val opensBeforeMatching = fixture.events.count { it.type == ReaderIoEventType.OPEN_PAGE }
            val decodesBeforeMatching = fixture.events.count { it.type == ReaderIoEventType.DECODE }

            val retained = fixture.owner.retainCachedFullPageAssets()
            try {
                assertEquals(setOf(1, 2), retained.keys)
                assertEquals(opensBeforeMatching, fixture.events.count { it.type == ReaderIoEventType.OPEN_PAGE })
                assertEquals(decodesBeforeMatching, fixture.events.count { it.type == ReaderIoEventType.DECODE })
                assertEquals(2, decodeCalls.get())
            } finally {
                retained.values.forEach(AutoCloseable::close)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `one holder acquire is idempotent`() = runTest {
        val decodeCalls = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                asset(tag = decodeCalls.incrementAndGet())
            },
        )
        val identity = identity()
        val key = key()

        try {
            assertTrue(fixture.owner.beginGeneration(identity.generation))
            val holder = fixture.owner.createHolder(identity, key)

            holder.acquire()
            holder.acquire()
            val ready = holder.awaitReady()

            assertEquals(identity, ready.identity)
            assertEquals(identity, holder.identity)
            assertEquals(key, holder.decodeKey)
            assertEquals(1, decodeCalls.get())
            assertEquals(1, fixture.events.count { it.matches(ReaderIoEventType.OPEN_PAGE, key) })
            assertEquals(1, fixture.events.count { it.matches(ReaderIoEventType.DECODE, key) })
            holder.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `split half holders share one full page decode and retain the same asset`() = runTest {
        val decodeEntered = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        val decodeCalls = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeCalls.incrementAndGet()
                decodeEntered.complete(Unit)
                releaseDecode.await()
                asset(tag = 1)
            },
        )
        val key = key()
        val leftIdentity = identity(
            splitHalf = PageSplitHalf.LEFT,
            sourceBounds = PixelBounds(x = 0, y = 0, width = 50, height = 100),
        )
        val rightIdentity = identity(
            splitHalf = PageSplitHalf.RIGHT,
            sourceBounds = PixelBounds(x = 50, y = 0, width = 50, height = 100),
        )

        try {
            fixture.owner.beginGeneration(key.generation)
            val left = fixture.owner.createHolder(leftIdentity, key)
            val right = fixture.owner.createHolder(rightIdentity, key)

            left.acquire()
            decodeEntered.await()
            right.acquire()
            releaseDecode.complete(Unit)

            val leftReady = left.awaitReady()
            val rightReady = right.awaitReady()
            assertNotEquals(left.identity, right.identity)
            assertEquals(left.decodeKey, right.decodeKey)
            assertEquals(PageDecodePurpose.FULL_PAGE, left.decodeKey.purpose)
            assertSame(leftReady.asset, rightReady.asset)
            assertEquals(1, decodeCalls.get())
            assertEquals(1, fixture.events.count { it.matches(ReaderIoEventType.OPEN_PAGE, key) })
            assertEquals(1, fixture.events.count { it.matches(ReaderIoEventType.DECODE, key) })

            left.close()
            right.close()
        } finally {
            releaseDecode.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun `holder close releases its presentation lease`() = runTest {
        val disposeCalls = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                asset(tag = 1, onDispose = { disposeCalls.incrementAndGet() })
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val holder = fixture.owner.createHolder(identity(), key())
            holder.acquire()
            holder.awaitReady()

            holder.close()
            holder.close()
            fixture.pipeline.clear()

            assertEquals(
                1,
                disposeCalls.get(),
                "Clearing the cache after holder close must release the asset's final lease",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ready holder retains action asset without another open or decode`() = runTest {
        val fixture = fixture(scope = this)
        val key = key()

        try {
            fixture.owner.beginGeneration(key.generation)
            val holder = fixture.owner.createHolder(identity(), key)

            assertNull(holder.retainReadyAsset(), "Idle holder must not expose an action asset")
            holder.acquire()
            holder.awaitReady()
            val eventsBeforeRetain = fixture.events.toList()

            val actionLease = holder.retainReadyAsset()

            assertNotNull(actionLease)
            assertEquals(
                eventsBeforeRetain.count { it.matches(ReaderIoEventType.OPEN_PAGE, key) },
                fixture.events.count { it.matches(ReaderIoEventType.OPEN_PAGE, key) },
                "Retaining the visible asset must not open encoded content again",
            )
            assertEquals(
                eventsBeforeRetain.count { it.matches(ReaderIoEventType.DECODE, key) },
                fixture.events.count { it.matches(ReaderIoEventType.DECODE, key) },
                "Retaining the visible asset must not decode the page again",
            )
            actionLease?.close()
            holder.close()
            assertNull(holder.retainReadyAsset(), "Closed holder must not expose an action asset")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `action asset lease remains valid after its holder closes`() = runTest {
        val disposeCalls = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                asset(tag = 1, onDispose = { disposeCalls.incrementAndGet() })
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val holder = fixture.owner.createHolder(identity(), key())
            holder.acquire()
            val ready = holder.awaitReady()
            val actionLease = requireNotNull(holder.retainReadyAsset())

            try {
                holder.close()
                fixture.pipeline.clear()

                assertSame(ready.asset, actionLease.asset)
                assertEquals(0, disposeCalls.get(), "The action lease must pin the asset across holder detach")
            } finally {
                actionLease.close()
            }
            assertEquals(1, disposeCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ready render lease pins the asset across holder generation close until draw completes`() = runTest {
        val disposeCalls = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                asset(tag = 1, onDispose = { disposeCalls.incrementAndGet() })
            },
        )
        val currentIdentity = identity(generation = 1L)

        try {
            fixture.owner.beginGeneration(currentIdentity.generation)
            val holder = fixture.owner.createHolder(currentIdentity, key(generation = 1L))
            holder.acquire()
            val ready = holder.awaitReady()

            val renderLease = requireNotNull(
                holder.retainReadyAssetForRender(ready.identity),
            ) { "A Ready state exposed to Compose draw must hand out an independent render lease" }
            try {
                assertTrue(fixture.owner.beginGeneration(2L))
                fixture.pipeline.clear()

                assertTrue(holder.state.value is DesktopReaderPresentationImageState.Closed)
                assertSame(ready.asset, renderLease.asset)
                assertEquals(
                    0,
                    disposeCalls.get(),
                    "Closing the old generation holder must not dispose an asset still used by Compose draw",
                )
            } finally {
                renderLease.close()
            }

            assertEquals(
                1,
                disposeCalls.get(),
                "The final render lease close must release the old generation asset exactly once",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `generation advance rejects and releases a non cooperative late holder result`() = runTest {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val decodeEntered = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val decodeReturned = CountDownLatch(1)
        val disposeCalls = AtomicInteger()
        val fixture = fixture(
            scope = ioScope,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                decodeEntered.countDown()
                check(releaseDecode.await(5, TimeUnit.SECONDS))
                asset(tag = 1, onDispose = { disposeCalls.incrementAndGet() })
                    .also { decodeReturned.countDown() }
            },
        )
        val staleIdentity = identity(generation = 1L)

        try {
            fixture.owner.beginGeneration(1L)
            val staleHolder = fixture.owner.createHolder(staleIdentity, key(generation = 1L))
            staleHolder.acquire()
            assertTrue(withContext(Dispatchers.IO) { decodeEntered.await(5, TimeUnit.SECONDS) })

            assertTrue(fixture.owner.beginGeneration(2L))
            releaseDecode.countDown()
            assertTrue(withContext(Dispatchers.IO) { decodeReturned.await(5, TimeUnit.SECONDS) })
            awaitCondition { fixture.pipeline.snapshot().inFlightCount == 0 && disposeCalls.get() == 1 }

            assertFalse(
                staleHolder.state.value is DesktopReaderPresentationImageState.Ready,
                "An old generation must never publish Ready after its decode returns",
            )
            assertEquals(1, disposeCalls.get())
            assertFalse(staleHolder.acknowledgeDraw(staleIdentity))
            staleHolder.close()
        } finally {
            releaseDecode.countDown()
            fixture.close()
            ioScope.cancel()
        }
    }

    @Test
    fun `draw acknowledgement accepts only the current ready identity exactly once`() = runTest {
        val presentations = CopyOnWriteArrayList<Pair<ReaderPageId, Long>>()
        val fixture = fixture(
            scope = this,
            onFirstPagePresented = { pageId, generation -> presentations += pageId to generation },
        )
        val currentIdentity = identity(
            splitHalf = PageSplitHalf.LEFT,
            sourceBounds = PixelBounds(x = 0, y = 0, width = 50, height = 100),
        )
        val otherSlotIdentity = currentIdentity.copy(
            splitHalf = PageSplitHalf.RIGHT,
            sourceBounds = PixelBounds(x = 50, y = 0, width = 50, height = 100),
        )

        try {
            fixture.owner.beginGeneration(currentIdentity.generation)
            val holder = fixture.owner.createHolder(currentIdentity, key())

            assertFalse(holder.acknowledgeDraw(currentIdentity), "Loading is not a drawn image")
            holder.acquire()
            holder.awaitReady()

            assertFalse(holder.acknowledgeDraw(otherSlotIdentity), "A stale slot cannot acknowledge this holder")
            assertTrue(holder.acknowledgeDraw(currentIdentity))
            assertFalse(holder.acknowledgeDraw(currentIdentity), "The same drawn asset is acknowledged once")
            assertEquals(listOf(currentIdentity.pageId to currentIdentity.generation), presentations)
            holder.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `generation advance cannot pass an in flight current draw acknowledgement`() = runTest {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val advanceStarted = CountDownLatch(1)
        val fixture = fixture(
            scope = this,
            onFirstPagePresented = { _, _ ->
                callbackEntered.countDown()
                check(releaseCallback.await(5, TimeUnit.SECONDS))
            },
        )
        val currentIdentity = identity()

        try {
            fixture.owner.beginGeneration(currentIdentity.generation)
            val holder = fixture.owner.createHolder(currentIdentity, key())
            holder.acquire()
            holder.awaitReady()

            val acknowledgement = async(Dispatchers.IO) {
                holder.acknowledgeDraw(currentIdentity)
            }
            assertTrue(withContext(Dispatchers.IO) { callbackEntered.await(5, TimeUnit.SECONDS) })
            val generationAdvance = async(Dispatchers.IO) {
                advanceStarted.countDown()
                fixture.owner.beginGeneration(2L)
            }
            assertTrue(withContext(Dispatchers.IO) { advanceStarted.await(5, TimeUnit.SECONDS) })
            delay(25)

            assertFalse(
                generationAdvance.isCompleted,
                "Generation fencing must include the observer report for the accepted draw",
            )

            releaseCallback.countDown()
            assertTrue(acknowledgement.await())
            assertTrue(generationAdvance.await())
        } finally {
            releaseCallback.countDown()
            fixture.close()
        }
    }

    @Test
    fun `owner close closes every registered holder`() = runTest {
        val disposeCalls = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, decodeKey ->
                asset(
                    tag = decodeKey.pageIndex + 1,
                    onDispose = { disposeCalls.incrementAndGet() },
                )
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val first = fixture.owner.createHolder(identity(pageIndex = 0), key(pageIndex = 0))
            val second = fixture.owner.createHolder(identity(pageIndex = 1), key(pageIndex = 1))
            first.acquire()
            second.acquire()
            first.awaitReady()
            second.awaitReady()

            fixture.owner.close()
            fixture.owner.close()
            fixture.pipeline.clear()

            assertFalse(first.state.value is DesktopReaderPresentationImageState.Ready)
            assertFalse(second.state.value is DesktopReaderPresentationImageState.Ready)
            assertEquals(
                2,
                disposeCalls.get(),
                "Owner close must release both holder leases before the cache is cleared",
            )
            first.close()
            second.close()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        decoder: DesktopReaderPageImageDecoder,
        onFirstPagePresented: (ReaderPageId, Long) -> Unit,
        encodedBytes: ByteArray,
    ) : AutoCloseable {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        private val now = AtomicLong()
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe(events::add),
            clock = ReaderMonotonicClock(now::incrementAndGet),
        )
        private val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = { encodedBytes },
            ioReporter = reporter,
        )
        val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = decoder,
        )
        val owner = DesktopReaderPresentationImageOwner(
            scope = scope,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter, onFirstPagePresented),
        )

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private fun fixture(
        scope: CoroutineScope,
        decoder: DesktopReaderPageImageDecoder = DesktopReaderPageImageDecoder { _, _ -> asset(tag = 1) },
        onFirstPagePresented: (ReaderPageId, Long) -> Unit = { _, _ -> },
        encodedBytes: ByteArray = ENCODED_BYTES,
    ): Fixture = Fixture(scope, decoder, onFirstPagePresented, encodedBytes)

    private fun standardJpegBytes(): ByteArray {
        val image = BufferedImage(2_400, 3_500, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().run {
            color = Color(244, 244, 244)
            fillRect(0, 0, image.width, image.height)
            dispose()
        }
        return ByteArrayOutputStream().also { output ->
            check(ImageIO.write(image, "jpeg", output))
        }.toByteArray()
    }

    private suspend fun DesktopReaderPresentationImageHolder.awaitReady(): DesktopReaderPresentationImageState.Ready =
        withTimeout(5_000) {
            state.filterIsInstance<DesktopReaderPresentationImageState.Ready>().first()
        }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private fun identity(
        pageIndex: Int = 0,
        generation: Long = 1L,
        splitHalf: PageSplitHalf? = null,
        sourceBounds: PixelBounds? = null,
    ) = DesktopReaderPresentationImageSlotIdentity(
        pageId = ReaderPageId(ReaderChapterId(1L), pageIndex),
        generation = generation,
        splitHalf = splitHalf,
        sourceBounds = sourceBounds,
    )

    private fun key(
        pageIndex: Int = 0,
        generation: Long = 1L,
    ) = ReaderPageDecodeKey(
        contentKey = ReaderPageContentOpenRequest(
            pageId = ReaderPageId(ReaderChapterId(1L), pageIndex),
            generation = generation,
            encodedPageRef = EncodedPageRef("opaque://page-$pageIndex"),
        ),
        purpose = PageDecodePurpose.FULL_PAGE,
        maxWidth = 2_048,
        maxHeight = 2_048,
    )

    private fun asset(
        tag: Int,
        onDispose: () -> Unit = {},
    ): DesktopReaderImageAsset {
        val width = 2 + (tag and 1)
        val bitmap = Bitmap().apply { allocN32Pixels(width, 2) }
        return DesktopReaderImageAsset(
            bitmap = bitmap.asComposeImageBitmap(),
            sourceWidth = width,
            sourceHeight = 2,
            estimatedBytes = width * 2L * 4L,
            sampled = false,
            disposer = {
                bitmap.close()
                onDispose()
            },
        )
    }

    private fun ReaderIoEvent.matches(type: ReaderIoEventType, key: ReaderPageDecodeKey): Boolean =
        this.type == type && pageId == key.contentKey.pageId && generation == key.generation

    private companion object {
        val ENCODED_BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
