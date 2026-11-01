package tachiyomi.presentation.feedback

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import tachiyomi.domain.feedback.FeedbackScreenshot

@Composable
actual fun FeedbackScreenshotPreview(image: FeedbackScreenshot, modifier: Modifier) {
    val bitmap = remember(image) {
        val bytes = image.bytes()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
    Image(bitmap.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit)
}
