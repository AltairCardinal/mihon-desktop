package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Overlay shown at the start or end of a chapter, displaying the previous / next
 * chapter title and a navigation button.
 *
 * Mirrors the Android chapter transition shown by PagerTransitionHolder:
 *   app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt
 *
 * @param currentChapterTitle  Title of the chapter currently being read.
 * @param adjacentChapterTitle Title of the adjacent chapter, or null if none exists.
 * @param isEnd                True = shown at chapter end (next chapter); False = start (prev chapter).
 * @param onNavigate           Called when the user taps the navigation button.
 * @param onDismiss            Called when the user wants to stay on the current chapter.
 */
@Composable
internal fun ChapterTransitionPage(
    currentChapterTitle: String,
    adjacentChapterTitle: String?,
    isEnd: Boolean,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isEnd) {
            // ── End of chapter ────────────────────────────────────────────────
            Text(
                text = "Finished",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = currentChapterTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .padding(vertical = 24.dp),
                color = Color.White.copy(alpha = 0.2f),
            )

            if (adjacentChapterTitle != null) {
                Text(
                    text = "Next Chapter",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = adjacentChapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("  Stay", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(onClick = onNavigate) {
                        Text("Next Chapter  ", style = MaterialTheme.typography.labelMedium)
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else {
                Text(
                    text = "No more chapters",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDismiss) {
                    Text("Back to library")
                }
            }
        } else {
            // ── Start of chapter ──────────────────────────────────────────────
            Text(
                text = "Now Reading",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = currentChapterTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .padding(vertical = 24.dp),
                color = Color.White.copy(alpha = 0.2f),
            )

            if (adjacentChapterTitle != null) {
                Text(
                    text = "Previous Chapter",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = adjacentChapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onNavigate) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("  Prev Chapter", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Text("Stay  ", style = MaterialTheme.typography.labelMedium)
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else {
                Text(
                    text = "No previous chapters",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDismiss) {
                    Text("Continue reading")
                }
            }
        }
    }
}
