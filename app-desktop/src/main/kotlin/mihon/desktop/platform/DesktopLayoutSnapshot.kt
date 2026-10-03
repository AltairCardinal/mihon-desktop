package mihon.desktop.platform

import eu.kanade.domain.ui.model.TabletUiMode

/** Immutable launch state: saving a preference never rebuilds the current window's owners. */
data class DesktopLayoutSnapshot(val tabletUiMode: TabletUiMode)
