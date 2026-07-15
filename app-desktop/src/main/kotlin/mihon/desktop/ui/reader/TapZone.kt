package mihon.desktop.ui.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import mihon.domain.reader.NavigationInversion
import mihon.domain.reader.NavigationPreset
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderNavigation
import mihon.domain.reader.ReaderNavigationCommand

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
 * [isRtl] supplies the reading direction for physical left/right regions. Logical regions in the
 * other presets retain their shared Previous/Next commands.
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
    val preset = when (mode) {
        NavigationMode.RightAndLeft -> NavigationPreset.RIGHT_AND_LEFT
        NavigationMode.L -> NavigationPreset.L
        NavigationMode.Kindle -> NavigationPreset.KINDLE
        NavigationMode.Edge -> NavigationPreset.EDGE
        NavigationMode.Disabled -> NavigationPreset.DISABLED
    }
    val command = ReaderNavigation.commandAt(
        x = x / width,
        y = y / height,
        preset = preset,
        inversion = NavigationInversion.NONE,
        direction = if (isRtl) ReaderDirection.RTL else ReaderDirection.LTR,
    )
    return when (command) {
        ReaderNavigationCommand.Previous -> TapNavRegion.PREV
        ReaderNavigationCommand.Next -> TapNavRegion.NEXT
        else -> TapNavRegion.MENU
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
