package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.reader.PageDecodePurpose
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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RUA-04D1 owns animation source/frame lifetime within one chapter generation.
 * Same-generation Retry attempt invalidation is deliberately excluded and remains a RUA-04D3 contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReaderAnimatedPresentationOwnerTest {

    @Test
    fun `animated holder opens one source session and gives every frame a stable purpose identity`() = runTest {
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                asset(tag = key.frameIndex ?: -1)
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val holder = fixture.animatedHolder(generation = 1L)

            holder.requestFrame(0)
            awaitCondition { holder.snapshot().readyKey?.frameIndex == 0 }
            holder.requestFrame(1)
            awaitCondition { holder.snapshot().readyKey?.frameIndex == 1 }

            assertEquals(1, fixture.contentReads.get(), "One mounted animation must keep one encoded source session")
            assertEquals(1, fixture.events.count { it.type == ReaderIoEventType.OPEN_PAGE })
            assertEquals(
                listOf(
                    PageDecodePurpose.ANIMATION_FRAME to 0,
                    PageDecodePurpose.ANIMATION_FRAME to 1,
                ),
                decodedKeys.map { it.purpose to it.frameIndex },
            )
            assertEquals(decodedKeys[1], holder.snapshot().readyKey)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `replaced frame stays alive for an old draw only until its render lease closes`() = runTest {
        val disposals = mutableMapOf<Int, Int>()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                val frame = requireNotNull(key.frameIndex)
                asset(tag = frame, onDispose = { disposals[frame] = disposals.getOrDefault(frame, 0) + 1 })
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val holder = fixture.animatedHolder(generation = 1L)
            holder.requestFrame(0)
            awaitCondition { holder.snapshot().readyKey?.frameIndex == 0 }
            val frameZero = holder.snapshot().readyAsset
            val frameZeroKey = requireNotNull(holder.snapshot().readyKey)
            val renderLease = requireNotNull(holder.retainReadyFrameForRender(frameZeroKey))
            assertEquals(
                null,
                holder.retainReadyFrameForRender(frameZeroKey.copy(maxWidth = frameZeroKey.maxWidth + 1)),
            )

            holder.requestFrame(1)
            awaitCondition { holder.snapshot().readyKey?.frameIndex == 1 }

            assertSame(frameZero, renderLease.asset)
            assertEquals(0, disposals.getOrDefault(0, 0), "The old Compose draw must pin its replaced frame")
            renderLease.close()
            assertEquals(1, disposals.getOrDefault(0, 0))

            holder.close()
            assertEquals(1, disposals.getOrDefault(1, 0))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `non cooperative old frame cannot publish after a new generation becomes ready`() = runTest {
        val oldDecodeEntered = CompletableDeferred<Unit>()
        val releaseOldDecode = CompletableDeferred<Unit>()
        val oldDisposals = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                if (key.generation == 1L) {
                    oldDecodeEntered.complete(Unit)
                    withContext(NonCancellable) { releaseOldDecode.await() }
                    asset(tag = 1, onDispose = { oldDisposals.incrementAndGet() })
                } else {
                    asset(tag = 2)
                }
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val oldHolder = fixture.animatedHolder(generation = 1L)
            oldHolder.requestFrame(0)
            oldDecodeEntered.await()

            assertTrue(fixture.owner.beginGeneration(2L))
            val newHolder = fixture.animatedHolder(generation = 2L)
            newHolder.requestFrame(0)
            awaitCondition { newHolder.snapshot().readyKey?.generation == 2L }
            releaseOldDecode.complete(Unit)
            awaitCondition { oldDisposals.get() == 1 }

            assertTrue(oldHolder.snapshot().closed)
            assertFalse(oldHolder.snapshot().readyKey?.generation == 1L)
            assertEquals(2L, newHolder.snapshot().readyKey?.generation)
            assertEquals(1, oldDisposals.get(), "The non-cooperative stale frame must be disposed exactly once")
        } finally {
            releaseOldDecode.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun `presentation detach and owner close release the last mounted frame lease`() = runTest {
        val disposeCalls = AtomicInteger()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, _ ->
                asset(tag = 1, onDispose = { disposeCalls.incrementAndGet() })
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val detached = fixture.animatedHolder(generation = 1L)
            val runtimeOwned = fixture.animatedHolder(generation = 1L)
            detached.requestFrame(0)
            runtimeOwned.requestFrame(0)
            awaitCondition {
                detached.snapshot().readyKey != null && runtimeOwned.snapshot().readyKey != null
            }

            detached.close()
            fixture.owner.close()

            assertTrue(detached.snapshot().closed)
            assertTrue(runtimeOwned.snapshot().closed)
            assertEquals(1, disposeCalls.get(), "Detach and runtime owner close must release the shared frame exactly once")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `detaching the first same-key holder does not cancel the surviving holder shared frame`() = runTest {
        val openEntered = CompletableDeferred<Unit>()
        val releaseOpen = CompletableDeferred<Unit>()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                asset(tag = requireNotNull(key.frameIndex))
            },
            encodedPageReader = {
                openEntered.complete(Unit)
                releaseOpen.await()
                ANIMATED_BYTES
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val detached = fixture.animatedHolder(generation = 1L)
            val surviving = fixture.animatedHolder(generation = 1L)
            detached.requestFrame(0)
            openEntered.await()
            surviving.requestFrame(0)

            detached.close()
            releaseOpen.complete(Unit)
            awaitCondition { surviving.snapshot().readyKey?.frameIndex == 0 }

            assertTrue(detached.snapshot().closed)
            assertFalse(surviving.snapshot().closed)
            assertEquals(1, fixture.contentReads.get())
            assertEquals(0, surviving.snapshot().readyKey?.frameIndex)
        } finally {
            releaseOpen.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun `missing next frame stops playback and keeps the last ready frame`() = runTest {
        val decodedFrames = CopyOnWriteArrayList<Int>()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                val frameIndex = requireNotNull(key.frameIndex)
                decodedFrames += frameIndex
                if (frameIndex == 0) asset(tag = frameIndex) else null
            },
        )
        val metadata = DesktopReaderAnimationMetadata(
            frames = listOf(0, 1).map {
                DesktopReaderAnimationFrameMetadata(
                    durationMillis = 10,
                    bounds = PixelBounds(0, 0, 2, 2),
                )
            },
            repeatCount = null,
        )

        try {
            fixture.owner.beginGeneration(1L)
            val holder = fixture.animatedHolder(generation = 1L)
            holder.startPlayback(metadata)
            awaitCondition { holder.snapshot().readyKey?.frameIndex == 0 }

            advanceTimeBy(10)
            runCurrent()
            awaitCondition { !holder.snapshot().running }

            assertEquals(listOf(0, 1), decodedFrames)
            assertEquals(0, holder.snapshot().readyKey?.frameIndex)
            assertFalse(holder.snapshot().closed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ordinary static holder dispatches only one full page decode and never creates an animation loader`() = runTest {
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val fixture = fixture(
            scope = this,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                decodedKeys += key
                asset(tag = 1)
            },
        )

        try {
            fixture.owner.beginGeneration(1L)
            val holder = fixture.owner.createHolder(identity(1L), fullPageKey(1L))
            holder.acquire()
            awaitCondition { holder.state.value is DesktopReaderPresentationImageState.Ready }

            assertEquals(1, fixture.contentReads.get())
            assertEquals(listOf(PageDecodePurpose.FULL_PAGE), decodedKeys.map(ReaderPageDecodeKey::purpose))
            assertTrue(decodedKeys.none { it.purpose == PageDecodePurpose.ANIMATION_FRAME })
            assertEquals(1, fixture.events.count { it.type == ReaderIoEventType.OPEN_PAGE })
            assertEquals(1, fixture.events.count { it.type == ReaderIoEventType.DECODE })
        } finally {
            fixture.close()
        }
    }

    private inner class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        decoder: DesktopReaderPageImageDecoder,
        encodedPageReader: suspend () -> ByteArray,
    ) : AutoCloseable {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val contentReads = AtomicInteger()
        private val now = AtomicLong()
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe(events::add),
            clock = ReaderMonotonicClock(now::incrementAndGet),
        )
        private val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = {
                contentReads.incrementAndGet()
                encodedPageReader()
            },
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
            pageIoObserver = ReaderPageIoObserver(reporter),
        )

        fun animatedHolder(generation: Long) = owner.createAnimatedHolder(
            identity = identity(generation),
            contentKey = contentKey(generation),
            maxWidth = 2_048,
            maxHeight = 2_048,
        )

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        decoder: DesktopReaderPageImageDecoder,
        encodedPageReader: suspend () -> ByteArray = { ANIMATED_BYTES },
    ) = Fixture(scope, decoder, encodedPageReader)

    private fun identity(generation: Long) = DesktopReaderPresentationImageSlotIdentity(
        pageId = PAGE_ID,
        generation = generation,
    )

    private fun contentKey(generation: Long) = ReaderPageContentOpenRequest(
        pageId = PAGE_ID,
        generation = generation,
        encodedPageRef = ENCODED_REF,
    )

    private fun fullPageKey(generation: Long) = ReaderPageDecodeKey(
        contentKey = contentKey(generation),
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

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private companion object {
        val PAGE_ID = ReaderPageId(ReaderChapterId(1L), 0)
        val ENCODED_REF = EncodedPageRef("opaque://animated-page")
        val ANIMATED_BYTES = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
    }
}
