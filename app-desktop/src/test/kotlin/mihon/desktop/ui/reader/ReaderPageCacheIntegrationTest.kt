package mihon.desktop.ui.reader

import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.PagePreloader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ReaderPageCacheIntegrationTest {

    @Test
    fun `late preload revision replaces the ordinary full image request with a cache hit`() = runTest {
        val bytes = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val preloader = PagePreloader(fetcher = { bytes }, windowSize = 0)
        val url = "https://example.invalid/page.png"
        val initialRevision = preloader.cacheRevision.value

        assertEquals(url, readerPagePainterModel(url, preloader.get(0)))

        preloader.preload(0, listOf(url))

        assertTrue(preloader.cacheRevision.value > initialRevision)
        assertNull(readerPagePainterModel(url, preloader.get(0)))
    }
}
