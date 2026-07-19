package mihon.desktop.ui.extension

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.ui.browse.SourceBrowseScreen
import mihon.desktop.ui.settings.MoreRootScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SourceExtensionNavigationContractTest {
    @Test
    fun `source and extension destinations are regular Screens with preserved parameters`() {
        val destinations = listOf(
            extensionListDestination(),
            extensionDetailsDestination("C:/extensions/example.jar"),
            sourcePreferencesDestination(42L, "Example Source"),
            sourceBrowseDestination(42L),
        )

        assertTrue(destinations.all(Screen::class.java::isInstance))
        assertFalse(destinations.any { it is Tab })
        assertEquals("C:/extensions/example.jar", (destinations[1] as ExtensionDetailsScreen).jarPath)
        assertEquals(42L, (destinations[2] as SourcePreferencesScreen).sourceId)
        assertEquals("Example Source", (destinations[2] as SourcePreferencesScreen).sourceName)
        assertEquals(42L, (destinations[3] as SourceBrowseScreen).sourceId)
    }

    @Test
    fun `actual Source and Extension screen callbacks push production destinations`() = runTest {
        val root = NavigatorProbeScreen()
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitTestApplier(), recomposer)
        val job = launch(frameClock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        composition.setContent { Navigator(root) { CurrentScreen() } }
        frameClock.sendFrame(0)
        recomposer.awaitIdle()

        MoreRootScreen().onExtensions(root.navigator)
        ExtensionListScreen().onOpen(root.navigator, InstalledExtension(File("C:/extensions/example.jar"), emptyList()))
        ExtensionListScreen().onSettings(root.navigator, 42L, "Example Source")
        ExtensionDetailsScreen("C:/extensions/example.jar").onSettings(root.navigator, 43L, "Other Source")
        ExtensionDetailsScreen("C:/extensions/example.jar").onBrowse(root.navigator, 44L)

        assertEquals(root, root.navigator.items[0])
        assertTrue(root.navigator.items[1] is ExtensionListScreen)
        assertEquals(
            listOf(
                extensionDetailsDestination(File("C:/extensions/example.jar").absolutePath),
                sourcePreferencesDestination(42L, "Example Source"),
                sourcePreferencesDestination(43L, "Other Source"),
                sourceBrowseDestination(44L),
            ),
            root.navigator.items.drop(2),
        )
        assertTrue(root.navigator.items.all(Screen::class.java::isInstance))
        assertFalse(root.navigator.items.any { it is Tab })
        composition.dispose()
        recomposer.close()
        job.cancelAndJoin()
    }

    private class NavigatorProbeScreen : Screen {
        lateinit var navigator: Navigator

        @Composable
        override fun Content() {
            navigator = LocalNavigator.currentOrThrow
        }
    }

    private class UnitTestApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
        override fun remove(index: Int, count: Int) = Unit
    }
}
