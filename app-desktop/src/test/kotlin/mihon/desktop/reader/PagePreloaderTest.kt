package mihon.desktop.reader

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
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
        val started = Channel<String>(Channel.UNLIMITED)
        val releases = (0..3).associate { "page$it" to CompletableDeferred<Unit>() }
        val preloader = PagePreloader(
            fetcher = { url ->
                started.send(url)
                releases.getValue(url).await()
                bytes
            },
            windowSize = 2,
            requestScheduler = ReaderRequestScheduler(
                ReaderSchedulerPolicy(nearbyForward = 2, nearbyBackward = 0, maxConcurrentRequests = 1),
            ),
        )

        val preload = async { preloader.preload(currentPage = 1, pageUrls = releases.keys.toList()) }

        assertEquals("page1", started.receive())
        assertTrue(started.tryReceive().isFailure)
        releases.getValue("page1").complete(Unit)
        assertEquals("page2", started.receive())
        releases.getValue("page2").complete(Unit)
        assertEquals("page3", started.receive())
        releases.getValue("page3").complete(Unit)
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

    private fun makePreloader(windowSize: Int = 3): Pair<PagePreloader, MutableMap<String, ByteArray>> {
        val store = mutableMapOf<String, ByteArray>()
        val fetcher: suspend (String) -> ByteArray? = { url -> store[url] }
        return Pair(PagePreloader(fetcher = fetcher, windowSize = windowSize), store)
    }

    @Test
    fun `get returns null before preload`() {
        val (preloader, _) = makePreloader()
        assertNull(preloader.get(0))
    }

    @Test
    fun `preloaded pages are accessible via get`() = runTest {
        val (preloader, store) = makePreloader(windowSize = 2)
        val urls = (0..4).map { "page$it" }
        urls.forEachIndexed { i, url -> store[url] = makePngBytes(i) }
        val generationBeforeLoad = preloader.cacheGeneration.value

        preloader.preload(currentPage = 0, pageUrls = urls)

        // page 0 + up to 2 ahead should be cached
        assertNotNull(preloader.get(0))
        assertNotNull(preloader.get(1))
        assertNotNull(preloader.get(2))
        assertTrue(preloader.cacheGeneration.value > generationBeforeLoad)
    }

    @Test
    fun `pages outside window are evicted on preload advance`() = runTest {
        val (preloader, store) = makePreloader(windowSize = 2)
        val urls = (0..5).map { "page$it" }
        urls.forEachIndexed { i, url -> store[url] = makePngBytes(i) }

        preloader.preload(currentPage = 0, pageUrls = urls)
        assertNotNull(preloader.get(0))

        // Advance to page 3: symmetric window [1..5] (±windowSize=2); page 0 evicted
        preloader.preload(currentPage = 3, pageUrls = urls)
        assertNull(preloader.get(0))   // outside [1..5] → evicted
        assertNotNull(preloader.get(1)) // inside [1..5] → kept
        assertNotNull(preloader.get(3))
    }

    @Test
    fun `advancing beyond the whole window evicts every stale page`() = runTest {
        val (preloader, store) = makePreloader(windowSize = 2)
        val urls = (0..9).map { "page$it" }
        urls.forEachIndexed { i, url -> store[url] = makePngBytes(i) }

        preloader.preload(currentPage = 2, pageUrls = urls)
        assertEquals(setOf(0, 1, 2, 3, 4), preloader.cacheSnapshot().keys)

        preloader.preload(currentPage = 8, pageUrls = urls)

        assertEquals(setOf(6, 7, 8, 9), preloader.cacheSnapshot().keys)
    }

    @Test
    fun `cache size does not exceed window size times two plus one`() = runTest {
        val windowSize = 2
        val (preloader, store) = makePreloader(windowSize = windowSize)
        val urls = (0..9).map { "page$it" }
        urls.forEachIndexed { i, url -> store[url] = makePngBytes(i) }

        preloader.preload(currentPage = 5, pageUrls = urls)
        assertEquals(windowSize * 2 + 1, preloader.cacheSize())
    }

    @Test
    fun `clear empties the cache`() = runTest {
        val (preloader, store) = makePreloader()
        val urls = listOf("a", "b", "c")
        urls.forEachIndexed { i, url -> store[url] = makePngBytes(i) }

        preloader.preload(currentPage = 0, pageUrls = urls)
        assertNotNull(preloader.get(0))

        preloader.clear()
        assertNull(preloader.get(0))
        assertEquals(0, preloader.cacheSize())
    }

    @Test
    fun `missing URL is skipped without error`() = runTest {
        val (preloader, store) = makePreloader(windowSize = 2)
        val urls = listOf("present", "missing", "present2")
        store["present"] = makePngBytes(0)
        store["present2"] = makePngBytes(2)
        // "missing" intentionally absent

        preloader.preload(currentPage = 0, pageUrls = urls)

        assertNotNull(preloader.get(0))
        assertNull(preloader.get(1))   // not in store → skipped → null in cache
        assertNotNull(preloader.get(2))
    }

    @Test
    fun `large pages are downsampled before entering the ordinary cache`() = runTest {
        val bytes = makePngBytes(1)
        val preloader = PagePreloader(
            fetcher = { bytes },
            windowSize = 0,
            maxDecodedWidth = 2,
            maxDecodedHeight = 2,
            maxCacheBytes = 16,
            largeImagePixelThreshold = 4,
        )

        preloader.preload(0, listOf("large"))

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
            fetcher = { bytes },
            windowSize = 0,
            maxDecodedWidth = 200,
            maxDecodedHeight = 200,
            largeImagePixelThreshold = Long.MAX_VALUE,
        )

        preloader.preload(0, listOf("ordinary"))

        val cached = requireNotNull(preloader.get(0))
        assertTrue(cached.width <= 200)
        assertTrue(cached.height <= 200)
        assertTrue(preloader.cacheSnapshot().usedBytes <= 200L * 200L * 4L)
    }

    @Test
    fun `failed decode never pollutes the cache`() = runTest {
        val preloader = PagePreloader(fetcher = { "invalid".toByteArray() }, windowSize = 0)
        val generationBeforeLoad = preloader.cacheGeneration.value

        preloader.preload(0, listOf("broken"))

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
        val preloader = PagePreloader(
            fetcher = { url ->
                if (url == "old") {
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

        val oldPreload = async { preloader.preload(0, listOf("old", "new")) }
        oldRequestStarted.await()

        preloader.preload(1, listOf("old", "new"))
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
        val attempts = mutableMapOf<String, Int>()
        val bytes = makePngBytes(9)
        val preloader = PagePreloader(
            fetcher = { url ->
                val attempt = synchronized(attempts) {
                    attempts.getOrDefault(url, 0).plus(1).also { attempts[url] = it }
                }
                if (attempt == 1 && (url == "old0" || url == "old1")) {
                    val started = if (url == "old0") firstOld0Started else firstOld1Started
                    val finished = if (url == "old0") firstOld0Finished else firstOld1Finished
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
        val urls = listOf("old0", "old1", "new2")

        val oldPreload = async { preloader.preload(0, urls) }
        firstOld0Started.await()
        firstOld1Started.await()

        preloader.preload(2, urls)
        firstOld0Finished.await()
        firstOld1Finished.await()
        oldPreload.await()

        assertNull(preloader.get(0))
        assertNotNull(preloader.get(1))
        assertNotNull(preloader.get(2))
        assertEquals(2, attempts["old1"])
        assertEquals(setOf(1, 2), preloader.cacheSnapshot().keys)
    }
}
