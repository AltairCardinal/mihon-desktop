package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import mihon.domain.reader.NavigationPreset

open class LNavigation : ViewerNavigation() {
    override val preset = NavigationPreset.L
}
