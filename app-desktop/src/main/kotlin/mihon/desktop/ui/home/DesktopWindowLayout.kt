package mihon.desktop.ui.home

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Automatic tablet rule shared by navigation, settings and detail containers. */
internal data class DesktopWindowLayout(
    val width: Dp,
    val height: Dp,
    val mode: eu.kanade.domain.ui.model.TabletUiMode = eu.kanade.domain.ui.model.TabletUiMode.AUTOMATIC,
) {
    val expanded: Boolean
        get() = when (mode) {
            eu.kanade.domain.ui.model.TabletUiMode.AUTOMATIC -> minOf(width, height) >= if (width > height) 600.dp else 700.dp
            eu.kanade.domain.ui.model.TabletUiMode.ALWAYS -> true
            eu.kanade.domain.ui.model.TabletUiMode.LANDSCAPE -> width > height
            eu.kanade.domain.ui.model.TabletUiMode.NEVER -> false
        }
}

internal val LocalDesktopWindowLayout = staticCompositionLocalOf<DesktopWindowLayout?> { null }
