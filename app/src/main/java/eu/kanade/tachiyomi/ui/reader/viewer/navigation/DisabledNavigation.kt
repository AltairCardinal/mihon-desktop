package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import mihon.domain.reader.NavigationPreset

class DisabledNavigation : ViewerNavigation() {
    override val preset = NavigationPreset.DISABLED
}
