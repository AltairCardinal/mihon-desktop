package tachiyomi.presentation.feedback

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import tachiyomi.domain.feedback.FeedbackScreenshot
import org.jetbrains.skia.Image as SkiaImage

@Composable
actual fun FeedbackScreenshotPreview(image: FeedbackScreenshot, modifier: Modifier) {
    val decoded = remember(image) { SkiaImage.makeFromEncoded(image.bytes()) }
    DisposableEffect(decoded) { onDispose { decoded.close() } }
    Image(decoded.toComposeImageBitmap(), null, modifier, contentScale = ContentScale.Fit)
}
