package mihon.desktop.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import mihon.desktop.ui.browse.BrowseTab
import mihon.desktop.ui.history.HistoryTab
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.more.MoreTab
import mihon.desktop.ui.updates.UpdatesTab

internal val desktopRootTabs = listOf(LibraryTab, UpdatesTab, HistoryTab, BrowseTab, MoreTab)

internal class HomeNavigationState {
    private val childStacks = mutableStateMapOf<Navigator, Boolean>()
    val hasChild: Boolean get() = childStacks.values.any { it }
    fun update(navigator: Navigator) { childStacks[navigator] = navigator.size > 1 }
    fun remove(navigator: Navigator) { childStacks.remove(navigator) }
}

private val LocalHomeNavigationState = staticCompositionLocalOf<HomeNavigationState?> { null }

/** Every actual tab stack reports its depth; ordinary Screens never enter a TabNavigator. */
@Composable
internal fun ObserveHomeNavigationStack(navigator: Navigator) {
    val state = LocalHomeNavigationState.current
    val depth = navigator.size
    DisposableEffect(state, navigator, depth) {
        state?.update(navigator)
        onDispose { state?.remove(navigator) }
    }
}

@Composable
internal fun HomeNavigationHost(
    current: Tab,
    onSelect: (Tab) -> Unit,
    showNavigation: Boolean,
    badgeCount: Int,
    snackbar: @Composable () -> Unit = {},
    tabs: List<Tab> = desktopRootTabs,
    content: @Composable () -> Unit,
) {
    val state = remember { HomeNavigationState() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = DesktopWindowLayout(maxWidth, maxHeight)
        val visible = showNavigation && !state.hasChild
        CompositionLocalProvider(LocalDesktopWindowLayout provides layout, LocalHomeNavigationState provides state) {
            Scaffold(
                snackbarHost = snackbar,
                bottomBar = {
                    if (visible && !layout.expanded) {
                        DesktopNavigationBar(Modifier.testTag("desktop-root-bar")) {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = current == tab,
                                    onClick = { onSelect(tab) },
                                    icon = { RootIcon(tab, current == tab, if (tab == UpdatesTab) badgeCount else 0) },
                                    label = { Text(tab.options.title) },
                                    modifier = Modifier.testTag("desktop-root-${tab.key}"),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (visible && layout.expanded) {
                        DesktopNavigationRail(Modifier.fillMaxHeight().testTag("desktop-root-rail")) {
                            tabs.forEach { tab ->
                                NavigationRailItem(
                                    selected = current == tab,
                                    onClick = { onSelect(tab) },
                                    icon = { RootIcon(tab, current == tab, if (tab == UpdatesTab) badgeCount else 0) },
                                    label = { Text(tab.options.title) },
                                    modifier = Modifier.testTag("desktop-root-${tab.key}"),
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                }
            }
        }
    }
}

@Composable
private fun RootIcon(tab: Tab, selected: Boolean, count: Int) {
    val vector = when (tab) {
        LibraryTab -> if (selected) Icons.Filled.CollectionsBookmark else Icons.Outlined.CollectionsBookmark
        UpdatesTab -> if (selected) Icons.Filled.NewReleases else Icons.Outlined.NewReleases
        BrowseTab -> if (selected) Icons.Filled.Explore else Icons.Outlined.Explore
        else -> null
    }
    val painter = vector?.let { rememberVectorPainter(it) } ?: tab.options.icon
    painter?.let { icon ->
        if (count > 0) {
            BadgedBox(badge = { Badge { Text(count.coerceAtMost(99).toString()) } }) {
                Icon(icon, tab.options.title)
            }
        } else {
            Icon(icon, tab.options.title)
        }
    }
}
