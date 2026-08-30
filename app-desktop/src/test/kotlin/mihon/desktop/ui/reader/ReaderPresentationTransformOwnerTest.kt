package mihon.desktop.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderImageMemoryAuthority
import mihon.desktop.reader.DesktopReaderImageMemoryKind
import mihon.desktop.reader.DesktopReaderImageMemoryRetention
import mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPresentationTransformOwnerTest {

    @Test
    fun `identity replacement hides the old bitmap before the new transform completes`() = runTest {
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val firstCloseCalls = AtomicInteger()
        val secondCloseCalls = AtomicInteger()
        val firstLease = derivedBitmapLease(firstCloseCalls)
        val secondLease = derivedBitmapLease(secondCloseCalls)
        val firstKey = transformKey(pageIndex = 0)
        val secondKey = transformKey(pageIndex = 1)
        val owner = ReaderPresentationTransformOwner(backgroundScope)

        owner.submit(firstKey) {
            firstRelease.await()
            firstLease
        }
        runCurrent()
        firstRelease.complete(Unit)
        runCurrent()

        val firstReady = assertInstanceOf(
            ReaderPresentationTransformState.Ready::class.java,
            owner.state.value,
        )
        assertEquals(firstKey, firstReady.key)
        assertSame(firstLease.bitmap, firstReady.bitmap)
        assertTrue(owner.acknowledgeDraw(firstKey))

        owner.submit(secondKey) {
            secondRelease.await()
            secondLease
        }

        val replacementLoading = assertInstanceOf(
            ReaderPresentationTransformState.Loading::class.java,
            owner.state.value,
        )
        assertEquals(secondKey, replacementLoading.key)
        assertFalse(owner.acknowledgeDraw(firstKey), "The replaced bitmap must not acknowledge the new slot")
        assertFalse(owner.acknowledgeDraw(secondKey), "Loading pixels have not been presented")
        assertEquals(1, firstCloseCalls.get(), "Replacing a Ready transform releases its owned bitmap")

        runCurrent()
        secondRelease.complete(Unit)
        runCurrent()

        val secondReady = assertInstanceOf(
            ReaderPresentationTransformState.Ready::class.java,
            owner.state.value,
        )
        assertEquals(secondKey, secondReady.key)
        assertSame(secondLease.bitmap, secondReady.bitmap)
        assertTrue(owner.acknowledgeDraw(secondKey))

        owner.close()
        assertEquals(1, secondCloseCalls.get(), "Unmount releases the current owned bitmap")
    }

    @Test
    fun `late non cooperative transform is disposed instead of becoming presentable`() = runTest {
        val staleEntered = CompletableDeferred<Unit>()
        val staleRelease = CompletableDeferred<Unit>()
        val freshRelease = CompletableDeferred<Unit>()
        val staleCloseCalls = AtomicInteger()
        val freshCloseCalls = AtomicInteger()
        val staleKey = transformKey(pageIndex = 0)
        val freshKey = transformKey(pageIndex = 1)
        val owner = ReaderPresentationTransformOwner(backgroundScope)

        owner.submit(staleKey) {
            staleEntered.complete(Unit)
            try {
                staleRelease.await()
            } catch (_: CancellationException) {
                withContext(NonCancellable) { staleRelease.await() }
            }
            derivedBitmapLease(staleCloseCalls)
        }
        runCurrent()
        assertTrue(staleEntered.isCompleted)

        owner.submit(freshKey) {
            freshRelease.await()
            derivedBitmapLease(freshCloseCalls)
        }
        staleRelease.complete(Unit)
        runCurrent()

        val loading = assertInstanceOf(
            ReaderPresentationTransformState.Loading::class.java,
            owner.state.value,
        )
        assertEquals(freshKey, loading.key)
        assertEquals(1, staleCloseCalls.get(), "A late owned result must be disposed exactly once")
        assertFalse(owner.acknowledgeDraw(staleKey))
        assertFalse(owner.acknowledgeDraw(freshKey))

        freshRelease.complete(Unit)
        runCurrent()
        assertEquals(
            freshKey,
            assertInstanceOf(
                ReaderPresentationTransformState.Ready::class.java,
                owner.state.value,
            ).key,
        )

        owner.close()
        assertEquals(1, freshCloseCalls.get())
    }

    @Test
    fun `clear releases the mounted derived bitmap exactly once`() = runTest {
        val closeCalls = AtomicInteger()
        val key = transformKey(pageIndex = 0, cropBorders = true)
        val owner = ReaderPresentationTransformOwner(backgroundScope)

        owner.submit(key) { derivedBitmapLease(closeCalls) }
        runCurrent()
        assertInstanceOf(ReaderPresentationTransformState.Ready::class.java, owner.state.value)

        owner.clear()

        assertInstanceOf(ReaderPresentationTransformState.Empty::class.java, owner.state.value)
        assertFalse(owner.acknowledgeDraw(key))
        assertEquals(1, closeCalls.get())

        owner.clear()
        owner.close()
        owner.close()
        assertEquals(1, closeCalls.get(), "Clear and close are idempotent for the released bitmap")
    }

    @Test
    fun `production transform lease registers derived memory and releases base before final detach`() = runTest {
        val authority = DesktopReaderImageMemoryAuthority(maxBytes = 1_024L)
        val baseDisposeCalls = AtomicInteger()
        val baseNative = Bitmap().apply { check(allocN32Pixels(4, 2)) }
        val baseAsset = DesktopReaderImageAsset(
            bitmap = baseNative.asComposeImageBitmap(),
            sourceWidth = 4,
            sourceHeight = 2,
            estimatedBytes = 32L,
            sampled = false,
            disposer = {
                baseNative.close()
                baseDisposeCalls.incrementAndGet()
            },
        )
        baseAsset.attachMemoryAuthority(
            authority = authority,
            kind = DesktopReaderImageMemoryKind.FULL,
            retention = DesktopReaderImageMemoryRetention.ACTIVE,
        )
        val identity = DesktopReaderPresentationImageSlotIdentity(
            pageId = ReaderPageId(ReaderChapterId(1L), 0),
            generation = 1L,
            splitHalf = PageSplitHalf.LEFT,
        )
        var transformedLease: ReaderPresentationBitmapLease? = null

        try {
            val readyLease = createReaderPresentationTransformLease(
                baseLease = baseAsset,
                identity = identity,
                cropBorders = false,
                memoryAuthority = authority,
            )
            transformedLease = readyLease
            val derivedNative = readyLease.bitmap.asSkiaBitmap()
            val readySnapshot = authority.snapshot()

            assertEquals(2, readyLease.bitmap.width)
            assertEquals(2, readyLease.bitmap.height)
            assertEquals(PixelBounds(0, 0, 2, 2), readyLease.renderedSourceBounds)
            assertEquals(1, baseDisposeCalls.get(), "A derived transform must release its FULL base lease")
            assertTrue(baseNative.isClosed)
            assertFalse(derivedNative.isClosed)
            assertEquals(16L, readySnapshot.residentBytes)
            assertEquals(0L, readySnapshot.byKind.getValue(DesktopReaderImageMemoryKind.FULL))
            assertEquals(16L, readySnapshot.byKind.getValue(DesktopReaderImageMemoryKind.DERIVED))
            assertEquals(16L, readySnapshot.pinnedBytes)

            readyLease.close()
            assertTrue(derivedNative.isClosed)
            assertEquals(0L, authority.snapshot().residentBytes)
        } finally {
            transformedLease?.close()
            baseAsset.close()
            authority.close()
        }
    }

    @Test
    fun `production transform registration failure closes derived and base allocations exactly once`() = runTest {
        val authority = DesktopReaderImageMemoryAuthority(maxBytes = 48L)
        val evictionFailure = IllegalStateException("cached-eviction-disposer")
        val evictionCalls = AtomicInteger()
        val derivedDisposeCalls = AtomicInteger()
        val baseDisposeCalls = AtomicInteger()
        val cached = authority.register(
            kind = DesktopReaderImageMemoryKind.TILE,
            estimatedBytes = 16L,
            retention = DesktopReaderImageMemoryRetention.ACTIVE,
            disposer = {
                evictionCalls.incrementAndGet()
                throw evictionFailure
            },
        )
        val cachedKey = ReaderPageDecodeKey(
            contentKey = ReaderPageContentOpenRequest(
                pageId = ReaderPageId(ReaderChapterId(2L), 0),
                generation = 1L,
                encodedPageRef = EncodedPageRef("opaque://derived-register-failure"),
            ),
            purpose = PageDecodePurpose.REGION_TILE,
            maxWidth = 2,
            maxHeight = 2,
            region = PixelBounds(0, 0, 2, 2),
        )
        assertTrue(authority.admitCache(cachedKey, cached) {})
        cached.close()

        val baseNative = Bitmap().apply { check(allocN32Pixels(4, 2)) }
        val baseAsset = DesktopReaderImageAsset(
            bitmap = baseNative.asComposeImageBitmap(),
            sourceWidth = 4,
            sourceHeight = 2,
            estimatedBytes = 32L,
            sampled = false,
            disposer = {
                baseNative.close()
                baseDisposeCalls.incrementAndGet()
            },
        )
        baseAsset.attachMemoryAuthority(
            authority = authority,
            kind = DesktopReaderImageMemoryKind.FULL,
            retention = DesktopReaderImageMemoryRetention.ACTIVE,
        )

        try {
            val failure = runCatching {
                createReaderPresentationTransformLease(
                    baseLease = baseAsset,
                    identity = DesktopReaderPresentationImageSlotIdentity(
                        pageId = ReaderPageId(ReaderChapterId(2L), 1),
                        generation = 1L,
                        splitHalf = PageSplitHalf.LEFT,
                    ),
                    cropBorders = false,
                    memoryAuthority = authority,
                    nativeBitmapDisposer = { bitmap ->
                        bitmap.close()
                        derivedDisposeCalls.incrementAndGet()
                    },
                )
            }.exceptionOrNull()

            assertSame(evictionFailure, failure)
            assertEquals(1, evictionCalls.get())
            assertEquals(1, derivedDisposeCalls.get(), "The rejected DERIVED native allocation must close once")
            assertEquals(1, baseDisposeCalls.get(), "The failed transform must release its FULL base once")
            assertEquals(0L, authority.snapshot().residentBytes)
        } finally {
            baseAsset.close()
            authority.close()
        }
    }

    private fun transformKey(
        pageIndex: Int,
        cropBorders: Boolean = false,
    ) = ReaderPresentationTransformKey(
        presentationIdentity = DesktopReaderPresentationImageSlotIdentity(
            pageId = ReaderPageId(ReaderChapterId(1L), pageIndex),
            generation = 1L,
        ),
        cropBorders = cropBorders,
    )

    private fun derivedBitmapLease(closeCalls: AtomicInteger): ReaderPresentationBitmapLease {
        val bitmap = Bitmap().apply { allocN32Pixels(2, 2) }
        return TrackingDerivedBitmapLease(
            nativeBitmap = bitmap,
            closeCalls = closeCalls,
        )
    }

    private class TrackingDerivedBitmapLease(
        private val nativeBitmap: Bitmap,
        private val closeCalls: AtomicInteger,
    ) : ReaderPresentationBitmapLease {
        private val closed = AtomicBoolean()

        override val bitmap: ImageBitmap = nativeBitmap.asComposeImageBitmap()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                nativeBitmap.close()
                closeCalls.incrementAndGet()
            }
        }
    }
}
