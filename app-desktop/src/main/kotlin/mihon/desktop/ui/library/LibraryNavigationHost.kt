package mihon.desktop.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator

/** A regular-Screen stack owned by the Library tab's nested navigator. */
internal interface LibraryScreenStack {
    val items: List<Screen>

    fun push(screen: Screen)
}

/** Executable host contract used by [LibraryTab.Content], with a CompositionLocal test seam. */
internal interface LibraryNavigationHost {
    @Composable
    fun Content(root: Screen)
}

internal class VoyagerLibraryNavigationHost(
    private val onStackAttached: (LibraryScreenStack) -> Unit = {},
    private val onStackDetached: (LibraryScreenStack) -> Unit = {},
) : LibraryNavigationHost {

    @Composable
    override fun Content(root: Screen) {
        Navigator(root) { navigator ->
            val stack = remember(navigator) { VoyagerLibraryScreenStack(navigator) }
            DisposableEffect(stack) {
                onStackAttached(stack)
                onDispose { onStackDetached(stack) }
            }
            CurrentScreen()
        }
    }
}

private class VoyagerLibraryScreenStack(
    private val navigator: Navigator,
) : LibraryScreenStack {
    override val items: List<Screen>
        get() = navigator.items

    override fun push(screen: Screen) {
        navigator.push(screen)
    }
}

internal val LocalLibraryNavigationHost = staticCompositionLocalOf<LibraryNavigationHost> {
    VoyagerLibraryNavigationHost()
}

@Composable
internal fun ProvideLibraryNavigationHost(
    host: LibraryNavigationHost,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLibraryNavigationHost provides host, content = content)
}

@Composable
internal fun LibraryTabContent(host: LibraryNavigationHost) {
    host.Content(LibraryRootScreen())
}
