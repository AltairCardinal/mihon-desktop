package eu.kanade.presentation.more.settings.screen.about

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tachiyomi.domain.feedback.FeedbackImageSource
import tachiyomi.domain.feedback.FeedbackImageStage
import tachiyomi.domain.feedback.FeedbackJpegMetadata
import tachiyomi.domain.feedback.FeedbackScreenshot
import tachiyomi.domain.feedback.FeedbackScreenshotInput
import tachiyomi.domain.feedback.FeedbackScreenshotProcessor
import java.io.ByteArrayOutputStream

class AndroidFeedbackScreenshotProcessor : FeedbackScreenshotProcessor {
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
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            FeedbackImageSource.dimensions(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = feedbackScreenshotSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: FeedbackImageSource.invalid()
            try {
                stage(FeedbackImageStage.SCALE)
                val orientation = FeedbackImageSource.orientation(bytes)
                val (width, height) = FeedbackImageSource.outputDimensions(original.width, original.height, orientation)
                val final = Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888,
                    false,
                    ColorSpace.get(ColorSpace.Named.SRGB),
                )
                try {
                    val canvas = Canvas(final)
                    canvas.drawColor(Color.WHITE)
                    val matrix = orientationMatrix(orientation, original.width, original.height)
                    val swap = orientation in 5..8
                    matrix.postScale(
                        width.toFloat() / (if (swap) original.height else original.width),
                        height.toFloat() / (if (swap) original.width else original.height),
                    )
                    canvas.drawBitmap(original, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    stage(FeedbackImageStage.ENCODE)
                    var encoded: ByteArray? = null
                    for (quality in listOf(85, 75, 65)) {
                        currentCoroutineContext().ensureActive()
                        val output = ByteArrayOutputStream()
                        if (!final.compress(Bitmap.CompressFormat.JPEG, quality, output)) FeedbackImageSource.invalid()
                        val attempt = FeedbackJpegMetadata.strip(output.toByteArray())
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
                    final.recycle()
                }
            } finally {
                original.recycle()
            }
        }

    private fun orientationMatrix(orientation: Int, width: Int, height: Int): Matrix {
        val w = width.toFloat()
        val h = height.toFloat()
        val values = when (orientation) {
            2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        return Matrix().apply { setValues(values) }
    }
}

internal fun androidFeedbackScreenshotInputs(
    resolver: android.content.ContentResolver,
    uris: List<android.net.Uri>,
): List<FeedbackScreenshotInput> = uris.map { uri ->
    val size = resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { value -> value >= 0 } else null
    }
    FeedbackScreenshotInput(size) { resolver.openInputStream(uri) ?: error("Image unavailable") }
}

internal fun feedbackScreenshotSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= 1920) sample *= 2
    return sample
}
