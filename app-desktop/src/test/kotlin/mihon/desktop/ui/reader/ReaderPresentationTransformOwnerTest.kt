package mihon.desktop.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity
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
