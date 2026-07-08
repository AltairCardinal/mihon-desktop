package mihon.desktop.ui.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType

enum class TapZone { LEFT, CENTER, RIGHT }

/** Region a 2D tap falls into for chapter navigation. */
enum class TapNavRegion { PREV, NEXT, MENU }

/**
 * Five navigation layout modes, matching Android's reader navigation options.
 *
 * - [RightAndLeft]: Left third = PREV, Right third = NEXT, center = MENU.
 * - [L]: Left column + top row = PREV, Right column + bottom row = NEXT, center = MENU.
 * - [Kindle]: Top row = MENU; bottom half — left half = PREV, right half = NEXT.
 * - [Edge]: Outer edges (~20%) = PREV/NEXT, large center = MENU.
 * - [Disabled]: All taps open the MENU.
 */
enum class NavigationMode(val displayName: String) {
    RightAndLeft("Right and Left"),
    L("L"),
    Kindle("Kindle-ish"),
    Edge("Edge"),
    Disabled("Disabled"),
}

/**
 * Maps a 2D tap at ([x], [y]) within a component of size ([width], [height]) to a
 * [TapNavRegion] using the given [mode].
 *
 * Returns [TapNavRegion.MENU] when [width] or [height] is ≤ 0 to avoid division by zero.
 * In [NavigationMode.RightAndLeft], [isRtl] reverses the PREV/NEXT assignment.
 */
fun tapNavRegion(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    mode: NavigationMode,
    isRtl: Boolean = false,
): TapNavRegion {
    if (width <= 0f || height <= 0f) return TapNavRegion.MENU
    val nx = x / width  // normalised x  [0, 1]
    val ny = y / height // normalised y  [0, 1]
    return when (mode) {
        NavigationMode.Disabled -> TapNavRegion.MENU

        NavigationMode.RightAndLeft -> when {
            nx < 0.33f -> if (isRtl) TapNavRegion.NEXT else TapNavRegion.PREV
            nx > 0.66f -> if (isRtl) TapNavRegion.PREV else TapNavRegion.NEXT
            else -> TapNavRegion.MENU
        }

        NavigationMode.L -> when {
            ny < 0.33f -> TapNavRegion.PREV           // top row (overrides columns)
            ny > 0.66f -> TapNavRegion.NEXT           // bottom row (overrides columns)
            nx < 0.33f -> TapNavRegion.PREV           // left column, middle band
            nx > 0.66f -> TapNavRegion.NEXT           // right column, middle band
            else -> TapNavRegion.MENU
        }

        NavigationMode.Kindle -> when {
            ny < 0.33f -> TapNavRegion.MENU           // top row always opens menu
            nx < 0.5f -> TapNavRegion.PREV            // left half of non-top area
            else -> TapNavRegion.NEXT                 // right half of non-top area
        }

        NavigationMode.Edge -> when {
            nx < 0.2f -> TapNavRegion.PREV            // left edge
            nx > 0.8f -> TapNavRegion.NEXT            // right edge
            ny < 0.2f -> TapNavRegion.PREV            // top edge
            ny > 0.8f -> TapNavRegion.NEXT            // bottom edge
            else -> TapNavRegion.MENU
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun PointerEvent.isReaderPrimaryPress(): Boolean =
    type == PointerEventType.Press && isReaderPrimaryClick(button)

@OptIn(ExperimentalComposeUiApi::class)
fun isReaderPrimaryClick(button: PointerButton?): Boolean = button == PointerButton.Primary

@OptIn(ExperimentalComposeUiApi::class)
fun tapNavRegionForPointerButton(
    button: PointerButton?,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    mode: NavigationMode,
    isRtl: Boolean = false,
): TapNavRegion? =
    if (isReaderPrimaryClick(button)) {
        tapNavRegion(x, y, width, height, mode, isRtl)
    } else {
        null
    }

/**
 * Returns the tap zone for a tap at horizontal position [x] within a
 * component of [width] pixels.
 *
 * Layout (LTR physical coordinates):
 *   [0, width/3)        → LEFT
 *   [width/3, 2*width/3] → CENTER
 *   (2*width/3, width]  → RIGHT
 *
 * Used by [ZoomablePageBox] to trigger page navigation on single taps
 * when the reader is at the default zoom level (not panned/zoomed in).
 */
fun tapZoneFor(x: Float, width: Float): TapZone {
    if (width <= 0f) return TapZone.CENTER
    return when {
        x < width / 3f -> TapZone.LEFT
        x > width * 2f / 3f -> TapZone.RIGHT
        else -> TapZone.CENTER
    }
}

/** Result of a tap-left or tap-right action in the pager viewer. */
sealed interface PageNavAction {
    /** Scroll the pager to this index. */
    data class ScrollTo(val pagerIndex: Int) : PageNavAction
    /** Already at the first position — switch to the previous chapter. */
    data object PrevChapter : PageNavAction
    /** Already at the last position — switch to the next chapter. */
    data object NextChapter : PageNavAction
}

/**
 * Returns the navigation action for a left tap at [currentPagerIndex].
 * If already at 0, returns [PageNavAction.PrevChapter].
 */
fun tapLeftAction(currentPagerIndex: Int): PageNavAction {
    val prev = currentPagerIndex - 1
    return if (prev >= 0) PageNavAction.ScrollTo(prev) else PageNavAction.PrevChapter
}

/**
 * Returns the navigation action for a right tap at [currentPagerIndex]
 * within a pager that has [groupCount] total groups/pages.
 * If already at the last group, returns [PageNavAction.NextChapter].
 */
fun tapRightAction(currentPagerIndex: Int, groupCount: Int): PageNavAction {
    val next = currentPagerIndex + 1
    return if (next < groupCount) PageNavAction.ScrollTo(next) else PageNavAction.NextChapter
}

/**
 * Returns the chapter navigation triggered by a physical left tap, or null if
 * the tap stays within the chapter.
 *
 * In **RTL** mode the pager physically runs left-to-right but the reading
 * direction is right-to-left.  The physical left boundary (pager index 0)
 * corresponds to the *end* of the chapter, so crossing it means going
 * *forward* in the reading order (→ next/newer chapter).
 *
 * In **LTR** mode the physical left boundary is the *start* of the chapter,
 * so crossing it means going *backward* (→ previous/older chapter).
 */
fun chapterNavForTapLeft(currentPagerIndex: Int, isRtl: Boolean): PageNavAction? =
    when (tapLeftAction(currentPagerIndex)) {
        PageNavAction.PrevChapter -> if (isRtl) PageNavAction.NextChapter else PageNavAction.PrevChapter
        else -> null
    }

/**
 * Returns the chapter navigation triggered by a physical right tap, or null if
 * the tap stays within the chapter.
 *
 * In **RTL** mode the physical right boundary (pager index max) is the *start*
 * of the chapter, so crossing it means going *backward* (→ prev/older chapter).
 *
 * In **LTR** mode the physical right boundary is the *end* of the chapter,
 * so crossing it means going *forward* (→ next/newer chapter).
 */
fun chapterNavForTapRight(currentPagerIndex: Int, groupCount: Int, isRtl: Boolean): PageNavAction? =
    when (tapRightAction(currentPagerIndex, groupCount)) {
        PageNavAction.NextChapter -> if (isRtl) PageNavAction.PrevChapter else PageNavAction.NextChapter
        else -> null
    }
