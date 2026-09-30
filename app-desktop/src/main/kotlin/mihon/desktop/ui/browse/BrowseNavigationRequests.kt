package mihon.desktop.ui.browse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Compatibility route requests consumed by the real Browse root, including before it mounts. */
internal object BrowseNavigationRequests {
    private val pending = MutableStateFlow<Int?>(null)
    val section = pending.asStateFlow()

    fun selectForLegacyRoute(route: String) {
        pending.value = if (route.removeSuffix("Tab").removeSuffix("Screen") == "Authors") BROWSE_AUTHORS_SECTION else null
    }

    fun acknowledge(section: Int) { pending.compareAndSet(section, null) }
}
