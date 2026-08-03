package mihon.desktop.reader

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.EncodedPageRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PagePreloaderTest {

    @Test
    fun `production preloader consumes the shared scheduler serial policy`() = runTest {
        val bytes = makePngBytes(9)
        val started = Channel<EncodedPageRef>(Channel.UNLIMITED)
        val releases = (0..3).associate { EncodedPageRef("page$it") to CompletableDeferred<Unit>() }
        val preloader = PagePreloader(
            encodedPageReader = { ref ->
                started.send(ref)
                releases.getValue(ref).await()
                bytes
            },
            windowSize = 2,
            requestScheduler = ReaderRequestScheduler(
                ReaderSchedulerPolicy(nearbyForward = 2, nearbyBackward = 0, maxConcurrentRequests = 1),
            ),
        )

        val preload = async { preloader.preloadEncoded(currentPage = 1, encodedPageRefs = releases.keys.toList()) }

        assertEquals(EncodedPageRef("page1"), started.receive())
        assertTrue(started.tryReceive().isFailure)
        releases.getValue(EncodedPageRef("page1")).complete(Unit)
        assertEquals(EncodedPageRef("page2"), started.receive())
        releases.getValue(EncodedPageRef("page2")).complete(Unit)
        assertEquals(EncodedPageRef("page3"), started.receive())
        releases.getValue(EncodedPageRef("page3")).complete(Unit)
        preload.await()
        assertTrue(started.tryReceive().isFailure)
    }

    /** Minimal PNG bytes keyed by index — used as fake page "URLs" */
    private fun makePngBytes(tag: Int): ByteArray {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(tag % 256, 0, 0)
        g.fillRect(0, 0, 4, 4)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun makePreloader(windowSize: Int = 3): Pair<PagePreloader, MutableMap<EncodedPageRef, ByteArray>> {
        val store = mutableMapOf<EncodedPageRef, ByteArray>()
        val reader: suspend (EncodedPageRef) -> ByteArray? = store::get
        return Pair(PagePreloader(encodedPageReader = reader, windowSize = windowSize), store)
    }

    @Test
    fun `get returns null before preload`() {
        val (preloader, _) = makePreloader()
        assertNull(preloader.get(0))
    }

    @Test
    fun `preloaded pages are accessible via get`() = runTest {
        val (preloader, store) = makePreloader(windowSize = 2)
        val refs = (0..4).map { EncodedPageRef("page$it") }
        refs.forEachIndexed { i, ref -> store[ref] = makePngBytes(i) }
        val generationBeforeLoad = preloader.cacheGeneration.value

        preloader.preloadEncoded(currentPage = 0, encodedPageRefs = refs)

        // page 0 + up to 2 ahead should be cached
        assertNotNull(preloader.get(0))
        assertNotNull(preloader.get(1))
        assertNotNull(preloader.get(2))
        assertTrue(preloader.cacheGeneration.value > generationBeforeLoad)
    }

    @Test
    fun `pages outside window are evicted on preload advance`() = runTest {
        val (preloader, store) = makePreloader(windowSize = 2)
        val refs = (0..5).map { EncodedPageRef("page$it") }
        refs.forEachIndexed { i, ref -> store[ref] = makePngBytes(i) }

        preloader.preloadEncoded(currentPage = 0, encodedPageRefs = refs)
        assertNotNull(preloader.get(0))

        // Advance to page 3: symmetric window [1..5] (±windowSize=2); page 0 evicted
        preloader.preloadEncoded(currentPage = 3, encodedPageRefs = refs)
        assertNull(preloader.get(0))   // outside [1..5] → evicted
        assertNotNull(preloader.get(1)) // inside [1..5] → kept
        assertNotNull(preloader.get(3))
    }

    @Test
    fun `advancing beyond the whole window evicts every stale page`() = runTest {
        val (preloader, store) = makePreloader(windowSize = 2)
        val refs = (0..9).map { EncodedPageRef("page$it") }
        refs.forEachIndexed { i, ref -> store[ref] = makePngBytes(i) }

        preloader.preloadEncoded(currentPage = 2, encodedPageRefs = refs)
        assertEquals(setOf(0, 1, 2, 3, 4), preloader.cacheSnapshot().keys)

        preloader.preloadEncoded(currentPage = 8, encodedPageRefs = refs)

        assertEquals(setOf(6, 7, 8, 9), preloader.cacheSnapshot().keys)
    }

    @Test
    fun `cache size does not exceed window size times two plus one`() = runTest {
        val windowSize = 2
        val (preloader, store) = makePreloader(windowSize = windowSize)
        val refs = (0..9).map { EncodedPageRef("page$it") }
        refs.forEachIndexed { i, ref -> store[ref] = makePngBytes(i) }

        preloader.preloadEncoded(currentPage = 5, encodedPageRefs = refs)
        assertEquals(windowSize * 2 + 1, preloader.cacheSize())
    }

    @Test
    fun `clear empties the cache`() = runTest {
        val (preloader, store) = makePreloader()
        val refs = listOf("a", "b", "c").map(::EncodedPageRef)
        refs.forEachIndexed { i, ref -> store[ref] = makePngBytes(i) }

        preloader.preloadEncoded(currentPage = 0, encodedPageRefs = refs)
        assertNotNull(preloader.get(0))

        preloader.clear()
        assertNull(preloader.get(0))
        assertEquals(0, preloader.cacheSize())
    }

    @Test
    fun `missing encoded page is skipped without error`() = runTest {
        val (preloader, store) = makePreloader(windowSize = 2)
        val refs = listOf("present", "missing", "present2").map(::EncodedPageRef)
        store[refs[0]] = makePngBytes(0)
        store[refs[2]] = makePngBytes(2)
        // "missing" intentionally absent

        preloader.preloadEncoded(currentPage = 0, encodedPageRefs = refs)

        assertNotNull(preloader.get(0))
        assertNull(preloader.get(1))   // not in store → skipped → null in cache
        assertNotNull(preloader.get(2))
    }

    @Test
    fun `large pages are downsampled before entering the ordinary cache`() = runTest {
        val bytes = makePngBytes(1)
        val preloader = PagePreloader(
            encodedPageReader = { bytes },
            windowSize = 0,
            maxDecodedWidth = 2,
            maxDecodedHeight = 2,
            maxCacheBytes = 16,
            largeImagePixelThreshold = 4,
        )

        preloader.preloadEncoded(0, listOf(EncodedPageRef("large")))

        val bitmap = requireNotNull(preloader.get(0))
        assertEquals(2, bitmap.width)
        assertEquals(2, bitmap.height)
        assertEquals(16, preloader.cacheSnapshot().usedBytes)
        assertTrue(preloader.cacheSnapshot().usedBytes <= preloader.cacheSnapshot().maxBytes)
    }

    @Test
    fun `ordinary non large page path still enforces decoded dimension bounds`() = runTest {
        val bytes = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val preloader = PagePreloader(
            encodedPageReader = { bytes },
            windowSize = 0,
            maxDecodedWidth = 200,
            maxDecodedHeight = 200,
            largeImagePixelThreshold = Long.MAX_VALUE,
        )

        preloader.preloadEncoded(0, listOf(EncodedPageRef("ordinary")))

        val cached = requireNotNull(preloader.get(0))
        assertTrue(cached.width <= 200)
        assertTrue(cached.height <= 200)
        assertTrue(preloader.cacheSnapshot().usedBytes <= 200L * 200L * 4L)
    }

    @Test
    fun `failed decode never pollutes the cache`() = runTest {
        val preloader = PagePreloader(encodedPageReader = { "invalid".toByteArray() }, windowSize = 0)
        val generationBeforeLoad = preloader.cacheGeneration.value

        preloader.preloadEncoded(0, listOf(EncodedPageRef("broken")))

        assertNull(preloader.get(0))
        assertEquals(0, preloader.cacheSnapshot().usedBytes)
        assertEquals(generationBeforeLoad, preloader.cacheGeneration.value)
    }

    @Test
    fun `fast page change cancels stale preload and prevents a late cache write`() = runTest {
        val oldRequestStarted = CompletableDeferred<Unit>()
        val oldRequestReleased = CompletableDeferred<ByteArray?>()
        val oldRequestFinished = CompletableDeferred<Unit>()
        val newPageBytes = makePngBytes(2)
        val oldRef = EncodedPageRef("old")
        val newRef = EncodedPageRef("new")
        val refs = listOf(oldRef, newRef)
        val preloader = PagePreloader(
            encodedPageReader = { ref ->
                if (ref == oldRef) {
                    oldRequestStarted.complete(Unit)
                    try {
                        oldRequestReleased.await()
                    } finally {
                        oldRequestFinished.complete(Unit)
                    }
                } else {
                    newPageBytes
                }
            },
            windowSize = 0,
        )

        val oldPreload = async { preloader.preloadEncoded(0, refs) }
        oldRequestStarted.await()

        preloader.preloadEncoded(1, refs)
        oldRequestFinished.await()
        oldPreload.await()

        assertNull(preloader.get(0))
        assertNotNull(preloader.get(1))
        assertEquals(setOf(1), preloader.cacheSnapshot().keys)
    }

    @Test
    fun `page change cancels every active or queued old generation request`() = runTest {
        val firstOld0Started = CompletableDeferred<Unit>()
        val firstOld1Started = CompletableDeferred<Unit>()
        val firstOld0Finished = CompletableDeferred<Unit>()
        val firstOld1Finished = CompletableDeferred<Unit>()
        val attempts = mutableMapOf<EncodedPageRef, Int>()
        val bytes = makePngBytes(9)
        val refs = listOf("old0", "old1", "new2").map(::EncodedPageRef)
        val preloader = PagePreloader(
            encodedPageReader = { ref ->
                val attempt = synchronized(attempts) {
                    attempts.getOrDefault(ref, 0).plus(1).also { attempts[ref] = it }
                }
                if (attempt == 1 && ref in refs.take(2)) {
                    val started = if (ref == refs[0]) firstOld0Started else firstOld1Started
                    val finished = if (ref == refs[0]) firstOld0Finished else firstOld1Finished
                    started.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        finished.complete(Unit)
                    }
                }
                bytes
            },
            windowSize = 1,
        )

        val oldPreload = async { preloader.preloadEncoded(0, refs) }
        firstOld0Started.await()
        firstOld1Started.await()

        preloader.preloadEncoded(2, refs)
        firstOld0Finished.await()
        firstOld1Finished.await()
        oldPreload.await()

        assertNull(preloader.get(0))
        assertNotNull(preloader.get(1))
        assertNotNull(preloader.get(2))
        assertEquals(2, attempts[refs[1]])
        assertEquals(setOf(1, 2), preloader.cacheSnapshot().keys)
    }
}
