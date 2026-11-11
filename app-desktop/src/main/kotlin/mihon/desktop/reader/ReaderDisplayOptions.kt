package mihon.desktop.reader

import mihon.desktop.ui.reader.NavigationMode
import mihon.domain.reader.NavigationInversion

enum class ReaderZoomStart { AUTO, LEFT, RIGHT, CENTER }
enum class ReaderFlashColor { BLACK, WHITE, WHITE_BLACK }
enum class ReaderRippleStyle { CLEAN, SHADOW_LINE, SMOOTH }

/** Global reader extensions. Hidden choices are retained independently of the work's mode. */
data class ReaderDisplayOptions(
    val pagerInversion: NavigationInversion = NavigationInversion.NONE,
    val webtoonNavigation: NavigationMode = NavigationMode.Default,
    val webtoonInversion: NavigationInversion = NavigationInversion.NONE,
    val zoomStart: ReaderZoomStart = ReaderZoomStart.AUTO,
    val landscapeZoom: Boolean = true,
    val navigateToPan: Boolean = true,
    val splitInvert: Boolean = false,
    val rotateWide: Boolean = false,
    val rotateInvert: Boolean = false,
    val webtoonPadding: Int = 0,
    val webtoonDoubleTapZoom: Boolean = true,
    val webtoonDisableZoomOut: Boolean = false,
    val showPageNumber: Boolean = true,
    val fullscreen: Boolean = false,
    val keepScreenOn: Boolean = true,
    val longTap: Boolean = true,
    val alwaysShowChapterTransition: Boolean = true,
    val pageTransitions: Boolean = true,
    val flash: Boolean = false,
    val flashMillis: Int = 100,
    val flashInterval: Int = 1,
    val flashColor: ReaderFlashColor = ReaderFlashColor.BLACK,
    val ripple: Boolean = false,
    val rippleStyle: ReaderRippleStyle = ReaderRippleStyle.SHADOW_LINE,
)
