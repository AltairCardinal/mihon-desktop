package mihon.desktop.feedback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tachiyomi.domain.feedback.FeedbackImageSource
import tachiyomi.domain.feedback.FeedbackImageStage
import tachiyomi.domain.feedback.FeedbackScreenshot
import tachiyomi.domain.feedback.FeedbackScreenshotInput
import tachiyomi.domain.feedback.FeedbackScreenshotProcessor
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

class DesktopFeedbackScreenshotProcessor : FeedbackScreenshotProcessor {
    override suspend fun process(
        input: FeedbackScreenshotInput,
        progress: (FeedbackImageStage) -> Unit,
    ): FeedbackScreenshot =
        withContext(Dispatchers.IO) {
            suspend fun stage(value: FeedbackImageStage) {
                currentCoroutineContext().ensureActive()
                progress(value)
            }
            stage(FeedbackImageStage.READ)
            val bytes = FeedbackImageSource.read(input)
            stage(FeedbackImageStage.DECODE)
            val original = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) FeedbackImageSource.invalid()
                val reader = readers.next()
                try {
                    reader.setInput(stream, true, true)
                    FeedbackImageSource.dimensions(reader.getWidth(0), reader.getHeight(0))
                    reader.read(0) ?: FeedbackImageSource.invalid()
                } finally {
                    reader.dispose()
                }
            }
            try {
                stage(FeedbackImageStage.SCALE)
                val orientation = FeedbackImageSource.orientation(bytes)
                val (width, height) = FeedbackImageSource.outputDimensions(original.width, original.height, orientation)
                val final = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                try {
                    val graphics = final.createGraphics()
                    try {
                        graphics.color = Color.WHITE
                        graphics.fillRect(0, 0, width, height)
                        graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                        )
                        val swap = orientation in 5..8
                        graphics.scale(
                            width.toDouble() / (if (swap) original.height else original.width),
                            height.toDouble() / (if (swap) original.width else original.height),
                        )
                        graphics.drawImage(
                            original,
                            orientationTransform(orientation, original.width, original.height),
                            null,
                        )
                    } finally {
                        graphics.dispose()
                    }
                    stage(FeedbackImageStage.ENCODE)
                    var encoded: ByteArray? = null
                    for (quality in listOf(0.85f, 0.75f, 0.65f)) {
                        currentCoroutineContext().ensureActive()
                        val attempt = encode(final, quality)
                        if (attempt.size <= 524288) {
                            encoded = attempt
                            break
                        }
                    }
                    stage(FeedbackImageStage.CHECK)
                    val result = FeedbackScreenshot(width, height, encoded ?: FeedbackImageSource.tooLarge())
                    stage(FeedbackImageStage.COMPLETE)
                    result
                } finally {
                    final.flush()
                }
            } finally {
                original.flush()
            }
        }

    private fun encode(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                val parameters = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                    progressiveMode = ImageWriteParam.MODE_DISABLED
                }
                writer.write(null, IIOImage(image, null, null), parameters)
            }
            return bytes.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    private fun orientationTransform(orientation: Int, width: Int, height: Int): AffineTransform {
        val w = width.toDouble()
        val h = height.toDouble()
        return when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
            else -> AffineTransform()
        }
    }
}
