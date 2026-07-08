package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import mihon.desktop.ui.reader.pageContextMenuLabels
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files

/**
 * RED — PageSaveHelper does not exist yet.
 * Tests will fail until the production class is implemented.
 *
 * Tests the page save / copy logic independent of the UI layer.
 */
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
    fun `saveImageToFile writes PNG to the given path`() {
        val img = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val tmpFile = Files.createTempFile("page-save-test", ".png").toFile()
        try {
            PageSaveHelper.saveImageToFile(img, tmpFile)
            assertTrue(tmpFile.exists())
            assertTrue(tmpFile.length() > 0)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `defaultSaveDirectory returns a non-null path`() {
        val dir = PageSaveHelper.defaultSaveDirectory()
        assertNotNull(dir)
    }

    @Test
    fun `reader context menu image actions use Chinese labels`() {
        assertEquals(
            listOf("保存图片", "复制到剪贴板", "设为封面"),
            pageContextMenuLabels(includeSetAsCover = true),
        )
        assertEquals(
            listOf("保存图片", "复制到剪贴板"),
            pageContextMenuLabels(includeSetAsCover = false),
        )
    }

    @Test
    fun `loadImage decodes webp page files used by the reader`() {
        val tmpFile = Files.createTempFile("page-save-webp", ".webp").toFile()
        try {
            tmpFile.writeBytes(testWebpBytes())

            val img = PageSaveHelper.loadImage(tmpFile.toURI().toURL().toString())

            assertNotNull(img)
            assertEquals(2, img!!.width)
            assertEquals(2, img.height)
        } finally {
            tmpFile.delete()
        }
    }

    private fun testWebpBytes(): ByteArray {
        val bitmap = Bitmap().apply {
            allocN32Pixels(2, 2)
        }
        Canvas(bitmap).clear(Color.RED)
        return Image.makeFromBitmap(bitmap)
            .encodeToData(EncodedImageFormat.WEBP, 90)!!
            .bytes
    }
}
