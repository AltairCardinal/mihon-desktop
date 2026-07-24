package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import mihon.desktop.reader.ReaderColorFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderColorMatrixTest {

    @Test
    fun `disabled grayscale invert and combined color matrices transform pixels`() {
        assertNull(readerColorMatrix(ReaderColorFilter()))

        val grayscale = requireNotNull(readerColorMatrix(ReaderColorFilter(grayscaleEnabled = true)))
        assertFloatArrayEquals(
            floatArrayOf(
                0.213f, 0.715f, 0.072f, 0f, 0f,
                0.213f, 0.715f, 0.072f, 0f, 0f,
                0.213f, 0.715f, 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
            grayscale.values,
        )
        assertFloatArrayEquals(floatArrayOf(142.95f, 142.95f, 142.95f, 40f), transform(grayscale.values, 100f, 150f, 200f, 40f))

        val invert = requireNotNull(readerColorMatrix(ReaderColorFilter(invertEnabled = true)))
        assertFloatArrayEquals(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
            invert.values,
        )
        assertFloatArrayEquals(floatArrayOf(245f, 235f, 225f, 40f), transform(invert.values, 10f, 20f, 30f, 40f))

        val combined = requireNotNull(
            readerColorMatrix(ReaderColorFilter(grayscaleEnabled = true, invertEnabled = true)),
        )
        assertFloatArrayEquals(floatArrayOf(112.05f, 112.05f, 112.05f, 40f), transform(combined.values, 100f, 150f, 200f, 40f))
    }

    @Test
    fun `mounted reader viewport color layer renders disabled grayscale and invert pixels`() = runTest {
        assertPixel(renderColorLayer(ReaderColorFilter()), 100, 150, 200)
        assertPixel(renderColorLayer(ReaderColorFilter(grayscaleEnabled = true)), 143, 143, 143)
        assertPixel(renderColorLayer(ReaderColorFilter(invertEnabled = true)), 155, 105, 55)
    }

    private fun transform(matrix: FloatArray, red: Float, green: Float, blue: Float, alpha: Float): FloatArray {
        val input = floatArrayOf(red, green, blue, alpha)
        return FloatArray(4) { row ->
            (0 until 4).sumOf { column -> matrix[row * 5 + column].toDouble() * input[column] }.toFloat() +
                matrix[row * 5 + 4]
        }
    }

    private fun assertFloatArrayEquals(expected: FloatArray, actual: FloatArray, tolerance: Float = 0.001f) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertTrue(
                abs(expected[index] - actual[index]) <= tolerance,
                "index $index expected=${expected[index]} actual=${actual[index]}",
            )
        }
    }

    private suspend fun renderColorLayer(colorFilter: ReaderColorFilter): Int {
        val scene = ImageComposeScene(16, 16, coroutineContext = currentCoroutineContext()) {}
        return try {
            scene.setContent {
                ReaderViewportColorLayer(colorFilter) {
                    Box(Modifier.fillMaxSize().background(Color(100, 150, 200)))
                }
            }
            scene.render().toComposeImageBitmap().asSkiaBitmap().getColor(8, 8)
        } finally {
            scene.close()
        }
    }

    private fun assertPixel(color: Int, red: Int, green: Int, blue: Int) {
        assertEquals(red, color shr 16 and 0xFF)
        assertEquals(green, color shr 8 and 0xFF)
        assertEquals(blue, color and 0xFF)
    }
}
