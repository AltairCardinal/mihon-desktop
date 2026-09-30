package mihon.desktop.platform

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Clock

/** Clock/zone boundary for chapter dates, defaulting to the actual local calendar. */
internal val LocalDesktopDateClock = staticCompositionLocalOf { Clock.systemDefaultZone() }
