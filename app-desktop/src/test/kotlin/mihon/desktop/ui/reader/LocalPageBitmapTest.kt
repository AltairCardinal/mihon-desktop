package mihon.desktop.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class LocalPageBitmapTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `reader decodes File toURI page without Coil or OkHttp`() {
        val page = File(tempDir, "page.png")
        ImageIO.write(BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB), "png", page)

        val bitmap = runCatching {
            val method = Class.forName("mihon.desktop.ui.reader.ZoomablePageBoxKt")
                .getDeclaredMethod("loadLocalPageBitmap", String::class.java)
                .also { it.isAccessible = true }
            method.invoke(null, page.toURI().toString()) as ImageBitmap?
        }.getOrNull()

        assertNotNull(bitmap, "Expected a local file URI to be decoded directly for the reader")
        assertEquals(32, bitmap?.width)
        assertEquals(24, bitmap?.height)
    }
}
