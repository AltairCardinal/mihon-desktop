package mihon.desktop.ui.reader

import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPresentationBitmapOwnershipTest {

    @Test
    fun `successful crop closes the replaced owned bounds but not the base or returned bitmap`() {
        val base = Bitmap().apply { allocN32Pixels(4, 4) }
        val bounded = Bitmap().apply { allocN32Pixels(3, 3) }
        val cropped = Bitmap().apply { allocN32Pixels(2, 2) }

        try {
            val selected = selectPresentationCropResult(
                base = base.asComposeImageBitmap(),
                bounded = bounded.asComposeImageBitmap(),
                cropped = cropped.asComposeImageBitmap(),
            )

            assertSame(cropped, selected.asSkiaBitmap())
            assertTrue(bounded.isClosed, "The intermediate bounds bitmap must be released immediately")
            assertFalse(base.isClosed, "The transform must not release its retained base asset")
            assertFalse(cropped.isClosed, "The returned bitmap remains owned by the transform lease")
        } finally {
            if (!base.isClosed) base.close()
            if (!bounded.isClosed) bounded.close()
            if (!cropped.isClosed) cropped.close()
        }
    }
}
