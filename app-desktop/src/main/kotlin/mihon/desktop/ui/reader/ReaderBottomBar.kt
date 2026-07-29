package mihon.desktop.ui.reader

import tachiyomi.i18n.MR

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
 * Describes which button goes on which side of the bottom bar, based on reading direction.
 *
 * - LTR: left = SkipPrevious (prev chapter), right = SkipNext (next chapter)
 * - RTL: left = SkipNext (next chapter), right = SkipPrevious (prev chapter)
 */
internal data class ChapterNavLayout(
    /** True when the LEFT button navigates to the previous chapter. */
    val leftIsPrev: Boolean,
    val prevEnabled: Boolean,
    val nextEnabled: Boolean,
)

/** Pure logic — no Compose dependency. Used by the Composable and tested directly. */
internal fun chapterNavLayout(
    isRtl: Boolean,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
) = ChapterNavLayout(
    leftIsPrev = !isRtl,
    prevEnabled = hasPrevChapter,
    nextEnabled = hasNextChapter,
)

/**
 * Bottom bar with prev/next chapter buttons, page counter, optional "Adjust Spread"
 * button, and a progress slider.  Mirrors Android's ChapterNavigator layout.
 *
 * Layout:
 * ```
 * [SkipPrev] [page counter] [Adjust?] ──slider── [SkipNext]
 * ```
 * In RTL, SkipPrev and SkipNext swap physical positions.
 *
 * @param currentPage       0-based index of the page currently being viewed.
 * @param totalPages        Total number of pages in the chapter.
 * @param onPageChange      Called when the user drags the slider to a new position.
 * @param isRtl             When true, renders right-to-left (chapter buttons swap sides).
 * @param isDualPage        When true, the "Adjust Spread" button is shown.
 * @param hasPrevChapter    When false, the prev-chapter button is shown as disabled.
 * @param hasNextChapter    When false, the next-chapter button is shown as disabled.
 * @param onPrevChapter     Called when the user taps the prev-chapter button.
 * @param onNextChapter     Called when the user taps the next-chapter button.
 * @param onAdjustSpread    Called when the user presses "Adjust Spread"; null hides the button.
 * @param modifier          Applied to the outer Row; use to position the bar.
 */
@Composable
internal fun ReaderBottomBar(
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    isRtl: Boolean = false,
    isDualPage: Boolean = false,
    hasPrevChapter: Boolean = false,
    hasNextChapter: Boolean = false,
    onPrevChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    onAdjustSpread: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val nav = chapterNavLayout(isRtl, hasPrevChapter, hasNextChapter)
    val leftEnabled = if (nav.leftIsPrev) nav.prevEnabled else nav.nextEnabled
    val rightEnabled = if (nav.leftIsPrev) nav.nextEnabled else nav.prevEnabled
    val leftClick = if (nav.leftIsPrev) onPrevChapter else onNextChapter
    val rightClick = if (nav.leftIsPrev) onNextChapter else onPrevChapter
    // Icons are fixed regardless of RTL — SkipPrevious always on the left, SkipNext on the right.
    // In RTL mode only the functions swap, not the visual icons.

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Left chapter button
        FilledIconButton(
            onClick = leftClick,
            enabled = leftEnabled,
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.15f),
                contentColor = Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.05f),
                disabledContentColor = Color.White.copy(alpha = 0.3f),
            ),
        ) {
            Icon(Icons.Default.SkipPrevious, contentDescription = MR.strings.desktop_ui_previous_chapter.localized())
        }

        // Centre: page counter + optional Adjust Spread + slider
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${currentPage + 1} / $totalPages",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )

            if (isDualPage && onAdjustSpread != null) {
                IconButton(
                    onClick = onAdjustSpread,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = MR.strings.desktop_ui_adjust_spread.localized(),
                        tint = Color.White,
                    )
                }
            }

            if (totalPages > 1) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Slider(
                        value = currentPage.toFloat(),
                        onValueChange = { onPageChange(it.toInt().coerceIn(0, totalPages - 1)) },
                        valueRange = 0f..(totalPages - 1).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Right chapter button
        FilledIconButton(
            onClick = rightClick,
            enabled = rightEnabled,
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.15f),
                contentColor = Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.05f),
                disabledContentColor = Color.White.copy(alpha = 0.3f),
            ),
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = MR.strings.desktop_ui_next_chapter.localized())
        }
    }
}
