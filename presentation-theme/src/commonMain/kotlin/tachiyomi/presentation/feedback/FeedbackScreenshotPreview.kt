package tachiyomi.presentation.feedback

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tachiyomi.domain.feedback.FeedbackScreenshot

@Composable
expect fun FeedbackScreenshotPreview(image: FeedbackScreenshot, modifier: Modifier)
