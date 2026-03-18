package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Semi-transparent bottom bar with a page counter, an optional "Adjust Spread"
 * button, and a progress slider.
 *
 * Layout when [isDualPage] is true:
 * ```
 * [page counter] [adjust button] ────[Slider]────
 * ```
 *
 * Layout when [isDualPage] is false (button hidden):
 * ```
 * [page counter] ────[Slider]────
 * ```
 *
 * ──────────────────────────────────────────────────────────
 * RTL design note
 * ──────────────────────────────────────────────────────────
 * The [Slider] is wrapped in [CompositionLocalProvider]([LocalLayoutDirection])
 * instead of inverting the value.  This lets Material3's Slider mirror its own
 * thumb position and filled-track direction automatically.
 *
 * @param currentPage      0-based index of the page currently being viewed.
 * @param totalPages       Total number of pages in the chapter.
 * @param onPageChange     Called when the user drags the slider to a new position.
 * @param isRtl            When true, the slider renders right-to-left.
 * @param isDualPage       When true, the "Adjust Spread" button is shown.
 * @param onAdjustSpread   Called when the user presses the "Adjust Spread" button.
 *                         The button forces the current page to display alone,
 *                         shifting all subsequent dual-page pairings.
 * @param modifier         Applied to the outer [Column]; use to position the bar.
 */
@Composable
internal fun ReaderBottomBar(
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    isRtl: Boolean = false,
    isDualPage: Boolean = false,
    onAdjustSpread: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${currentPage + 1} / $totalPages",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )

            // "Adjust Spread" button — shifts dual-page pairing offset by
            // forcing the current page to display alone.
            if (isDualPage && onAdjustSpread != null) {
                IconButton(
                    onClick = onAdjustSpread,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = "Adjust Spread",
                        tint = Color.White,
                    )
                }
            }

            if (totalPages > 1) {
                // Wrap in the appropriate layout direction so Material3 Slider mirrors
                // itself: thumb and filled-track start from the right edge in RTL mode.
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Slider(
                        value = currentPage.toFloat(),
                        onValueChange = { onPageChange(it.toInt().coerceIn(0, totalPages - 1)) },
                        valueRange = 0f..(totalPages - 1).toFloat(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
