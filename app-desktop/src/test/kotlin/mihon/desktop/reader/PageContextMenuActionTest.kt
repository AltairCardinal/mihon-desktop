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
import tachiyomi.i18n.MR
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Locale

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
