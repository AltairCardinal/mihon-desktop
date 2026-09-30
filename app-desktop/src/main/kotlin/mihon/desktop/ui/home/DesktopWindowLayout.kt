package mihon.desktop.ui.home

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Automatic tablet rule shared by navigation, settings and detail containers. */
internal data class DesktopWindowLayout(val width: Dp, val height: Dp) {
    val expanded: Boolean
        get() = minOf(width, height) >= if (width > height) 600.dp else 700.dp
}

internal val LocalDesktopWindowLayout = staticCompositionLocalOf<DesktopWindowLayout?> { null }
