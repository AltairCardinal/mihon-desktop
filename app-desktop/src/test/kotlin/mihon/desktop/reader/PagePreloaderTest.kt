package mihon.desktop.reader

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PagePreloaderTest {

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

        preloader.preload(currentPage = 0, pageUrls = urls)

        // page 0 + up to 2 ahead should be cached
        assertNotNull(preloader.get(0))
        assertNotNull(preloader.get(1))
        assertNotNull(preloader.get(2))
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
}
