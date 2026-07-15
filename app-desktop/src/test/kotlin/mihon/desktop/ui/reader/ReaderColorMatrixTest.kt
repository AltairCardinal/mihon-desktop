package mihon.desktop.ui.reader

import java.io.File
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
    fun `reader color matrix production chain delegates through the tested helper`() {
        val visuals = productionSource("app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderVisualComponents.kt")
        val transform = bracedBlock(visuals, "internal fun Modifier.readerColorTransform(colorFilter: ReaderColorFilter)")
        assertEquals(1, occurrenceCount(transform, "readerColorMatrix(colorFilter)"))

        val screen = productionSource("app-desktop/src/main/kotlin/mihon/desktop/ui/reader/DesktopReaderScreen.kt")
        val readerViewport = bracedBlock(screen, "private fun ReaderViewport(")
        assertEquals(1, occurrenceCount(readerViewport, "readerColorTransform(state.colorFilter)"))
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

    private fun productionSource(path: String): String {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null && !current.resolve("settings.gradle.kts").isFile) current = current.parentFile
        return requireNotNull(current) { "Repository root not found" }.resolve(path).readText()
    }

    private fun bracedBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Missing production block: $marker" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed production block: $marker")
    }

    private fun occurrenceCount(source: String, marker: String): Int = Regex(Regex.escape(marker)).findAll(source).count()
}
