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

    fun onReselect() = Unit

    fun registerReselectHandler(handler: () -> Unit): () -> Unit = { }

    fun onCtrlReleased() = Unit

    fun registerCtrlReleaseHandler(handler: () -> Unit): () -> Unit = { }
}

internal class VoyagerLibraryNavigationHost(
    private val onStackAttached: (LibraryScreenStack) -> Unit = {},
    private val onStackDetached: (LibraryScreenStack) -> Unit = {},
) : LibraryNavigationHost {
    private var reselectHandler: (() -> Unit)? = null
    private var ctrlReleaseHandler: (() -> Unit)? = null

    @Composable
    override fun Content(root: Screen) {
        Navigator(root) { navigator ->
            mihon.desktop.ui.home.ObserveHomeNavigationStack(navigator)
            val stack = remember(navigator) { VoyagerLibraryScreenStack(navigator) }
            DisposableEffect(stack) {
                onStackAttached(stack)
                onDispose {
                    onStackDetached(stack)
                }
            }
            CurrentScreen()
        }
    }

    override fun onReselect() {
        reselectHandler?.invoke()
    }

    override fun registerReselectHandler(handler: () -> Unit): () -> Unit {
        reselectHandler = handler
        return {
            if (reselectHandler === handler) reselectHandler = null
        }
    }

    override fun onCtrlReleased() {
        ctrlReleaseHandler?.invoke()
    }

    override fun registerCtrlReleaseHandler(handler: () -> Unit): () -> Unit {
        ctrlReleaseHandler = handler
        return {
            if (ctrlReleaseHandler === handler) ctrlReleaseHandler = null
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
