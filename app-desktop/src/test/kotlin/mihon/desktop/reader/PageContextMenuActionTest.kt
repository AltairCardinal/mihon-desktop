package mihon.desktop.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import java.awt.image.BufferedImage
import java.util.Locale
import mihon.desktop.ui.reader.loadPageContextMenuImage
import mihon.desktop.ui.reader.pageContextMenuLabels
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.PixelBounds
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR

/** Tests page action naming and stable decoded-asset conversion independently from Compose. */
class PageContextMenuActionTest {

    // ── PageSaveHelper ────────────────────────────────────────────────────────

    @Test
    fun `buildSaveFileName produces manga-title-chapter-page format`() {
        val name = PageSaveHelper.buildSaveFileName(
            mangaTitle = "Chainsaw Man",
            chapterTitle = "Vol.1 Ch.1",
            pageIndex = 2,
        )
        // Should contain recognisable parts and end with an image extension
        assertTrue(name.contains("Chainsaw Man"), "expected manga title in filename, got: $name")
        assertTrue(name.contains("Ch.1") || name.contains("Ch_1"), "expected chapter in filename, got: $name")
        assertTrue(name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp"), "expected image extension, got: $name")
    }

    @Test
    fun `buildSaveFileName sanitises path-unsafe characters`() {
        val name = PageSaveHelper.buildSaveFileName(
            mangaTitle = "Manga: Title/With\\Slashes?And*Stars",
            chapterTitle = "Ch.1",
            pageIndex = 0,
        )
        // Must not contain filesystem-unsafe chars
        assertTrue('/' !in name, "slash should be removed, got: $name")
        assertTrue('\\' !in name, "backslash should be removed, got: $name")
        assertTrue('?' !in name, "? should be removed, got: $name")
        assertTrue('*' !in name, "* should be removed, got: $name")
        assertTrue(':' !in name, ": should be removed, got: $name")
    }

    @Test
    fun `defaultSaveDirectory returns a non-null path`() {
        val dir = PageSaveHelper.defaultSaveDirectory()
        assertNotNull(dir)
    }

    @Test
    fun `reader context menu image actions use Chinese labels in share copy save order`() {
        val locale = Locale.forLanguageTag("zh-CN")
        assertEquals(
            listOf(
                MR.strings.action_share.localized(locale),
                MR.strings.action_copy_to_clipboard.localized(locale),
                MR.strings.action_save.localized(locale),
                MR.strings.set_as_cover.localized(locale),
            ),
            pageContextMenuLabels(includeSetAsCover = true, locale = locale),
        )
        assertEquals(
            listOf(
                MR.strings.action_share.localized(locale),
                MR.strings.action_copy_to_clipboard.localized(locale),
                MR.strings.action_save.localized(locale),
            ),
            pageContextMenuLabels(includeSetAsCover = false, locale = locale),
        )
    }

    @Test
    fun `stable RGBA asset converts without reopening encoded content`() = withAsset(
        bitmapWidth = 1,
        bitmapHeight = 1,
        colorAt = { _, _ -> 0x80402010.toInt() },
    ) { asset ->
        val image = requireNotNull(loadPageContextMenuImage(asset = asset))

        assertEquals(1, image.width)
        assertEquals(1, image.height)
        assertEquals(0x80402010.toInt(), image.getRGB(0, 0))
    }

    @Test
    fun `stable asset keeps the complete page when no visible bounds are supplied`() = withAsset(
        bitmapWidth = 5,
        bitmapHeight = 3,
    ) { asset ->
        val image = requireNotNull(loadPageContextMenuImage(asset = asset))

        assertEquals(5, image.width)
        assertEquals(3, image.height)
        assertEquals(pixelColor(4, 2), image.getRGB(4, 2))
    }

    @Test
    fun `stable asset splits odd width without dropping the center pixel`() = withAsset(
        bitmapWidth = 5,
        bitmapHeight = 2,
    ) { asset ->
        val left = requireNotNull(
            loadPageContextMenuImage(asset = asset, splitHalf = PageSplitHalf.LEFT),
        )
        val right = requireNotNull(
            loadPageContextMenuImage(asset = asset, splitHalf = PageSplitHalf.RIGHT),
        )

        assertEquals(2, left.width)
        assertEquals(pixelColor(0, 0), left.getRGB(0, 0))
        assertEquals(pixelColor(1, 0), left.getRGB(1, 0))
        assertEquals(3, right.width)
        assertEquals(pixelColor(2, 0), right.getRGB(0, 0))
        assertEquals(pixelColor(4, 1), right.getRGB(2, 1))
    }

    @Test
    fun `source bounds take precedence over split hints`() = withAsset(
        bitmapWidth = 5,
        bitmapHeight = 3,
    ) { asset ->
        val image = requireNotNull(
            loadPageContextMenuImage(
                asset = asset,
                splitHalf = PageSplitHalf.RIGHT,
                sourceBounds = PixelBounds(x = 1, y = 1, width = 3, height = 2),
            ),
        )

        assertEquals(3, image.width)
        assertEquals(2, image.height)
        assertEquals(pixelColor(1, 1), image.getRGB(0, 0))
        assertEquals(pixelColor(3, 2), image.getRGB(2, 1))
    }

    @Test
    fun `stable asset rejects invalid and overflowing source bounds`() = withAsset(
        bitmapWidth = 5,
        bitmapHeight = 3,
    ) { asset ->
        assertNull(
            loadPageContextMenuImage(
                asset = asset,
                sourceBounds = PixelBounds(x = -1, y = 0, width = 1, height = 1),
            ),
        )
        assertNull(
            loadPageContextMenuImage(
                asset = asset,
                sourceBounds = PixelBounds(x = 0, y = 0, width = 0, height = 1),
            ),
        )
        assertNull(
            loadPageContextMenuImage(
                asset = asset,
                sourceBounds = PixelBounds(x = Int.MAX_VALUE, y = 0, width = 2, height = 1),
            ),
        )
        assertNull(
            loadPageContextMenuImage(
                asset = asset,
                sourceBounds = PixelBounds(x = 0, y = Int.MAX_VALUE, width = 1, height = 2),
            ),
        )
    }

    @Test
    fun `sampled stable asset maps original source bounds to decoded pixels`() = withAsset(
        bitmapWidth = 5,
        bitmapHeight = 3,
        sourceWidth = 10,
        sourceHeight = 6,
        sampled = true,
    ) { asset ->
        val image = requireNotNull(
            loadPageContextMenuImage(
                asset = asset,
                sourceBounds = PixelBounds(x = 2, y = 2, width = 4, height = 2),
            ),
        )

        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertEquals(pixelColor(1, 1), image.getRGB(0, 0))
        assertEquals(pixelColor(2, 1), image.getRGB(1, 0))
    }

    private fun withAsset(
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int = bitmapWidth,
        sourceHeight: Int = bitmapHeight,
        sampled: Boolean = false,
        colorAt: (Int, Int) -> Int = ::pixelColor,
        block: (DesktopReaderImageAsset) -> Unit,
    ) {
        val bitmap = Bitmap().apply { allocN32Pixels(bitmapWidth, bitmapHeight) }
        val canvas = Canvas(bitmap)
        val paint = Paint()
        try {
            repeat(bitmapHeight) { y ->
                repeat(bitmapWidth) { x ->
                    paint.color = colorAt(x, y)
                    canvas.drawRect(Rect.makeXYWH(x.toFloat(), y.toFloat(), 1f, 1f), paint)
                }
            }
        } finally {
            paint.close()
        }
        val asset = DesktopReaderImageAsset(
            bitmap = bitmap.asComposeImageBitmap(),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            estimatedBytes = bitmapWidth * bitmapHeight * 4L,
            sampled = sampled,
            disposer = bitmap::close,
        )
        try {
            block(asset)
        } finally {
            asset.close()
        }
    }

    private fun pixelColor(x: Int, y: Int): Int =
        0xFF000000.toInt() or (x shl 16) or (y shl 8) or ((x + y) and 0xFF)
}
