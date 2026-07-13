package mihon.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mihon.desktop.ui.authors.AuthorDetailScreen
import mihon.desktop.ui.library.LibraryNavigationHost
import mihon.desktop.ui.library.LibraryScreenStack
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.library.ProvideLibraryNavigationHost
import mihon.desktop.ui.library.VoyagerLibraryNavigationHost
import mihon.desktop.ui.library.authorDetailScreenOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Navigation contract tests.
 *
 * These verify that screens used in navigator.push() inside a Tab
 * are compatible with the navigation context they live in.
 *
 * A TabNavigator only accepts Tab children. Screens pushed inside a Tab
 * must use a nested Navigator (regular Screen stack), never the TabNavigator.
 *
 * These tests catch ClassCastException bugs at compile-time-equivalent level.
 */
class NavigationContractTest {

    @Test
    fun `author entry creates a regular Screen target for the nested navigator`() {
        val target = authorDetailScreenOrNull("  Jane Doe  ", creatorId = 42L)
        assertTrue(target is AuthorDetailScreen)
        assertTrue(target is Screen)
        assertFalse(target is Tab)
    }

    @Test
    fun `blank author does not create a navigation target`() {
        assertTrue(authorDetailScreenOrNull("   ", creatorId = 42L) == null)
    }

    @Test
    fun `MangaDetailScreen is a Screen not a Tab`() {
        val screen = MangaDetailScreen(mangaId = 1L)
        assertTrue(screen is Screen, "MangaDetailScreen must implement Screen")
        assertFalse(screen is Tab, "MangaDetailScreen must NOT implement Tab — it is pushed inside a nested Navigator")
    }

    @Test
    fun `MangaDetailScreen key includes manga id`() {
        val first = MangaDetailScreen(mangaId = 1L)
        val second = MangaDetailScreen(mangaId = 2L)

        assertTrue(
            first.key != second.key,
            "MangaDetailScreen key must include mangaId so Voyager does not reuse the previous manga detail model",
        )
    }

    @Test
    fun `LibraryTab content consumes a nested Screen navigator for detail and author targets`() = runTest {
        val host = RecordingLibraryNavigationHost()
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitTestApplier(), recomposer)
        val recomposerJob = launch(frameClock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        composition.setContent {
            ProvideLibraryNavigationHost(host) {
                LibraryTab.Content()
            }
        }
        frameClock.sendFrame(0L)
        recomposer.awaitIdle()

        assertEquals(1, host.renderCount, "LibraryTabContent must invoke the injected navigation host")
        assertTrue(host.root is Screen)
        assertFalse(host.root is Tab, "The nested navigator root must be a regular Screen")

        host.stack.push(MangaDetailScreen(7L))
        host.stack.push(AuthorDetailScreen(creatorId = 9L))
        assertEquals(
            listOf(host.root, MangaDetailScreen(7L), AuthorDetailScreen(9L)),
            host.stack.items,
            "Detail and author screens must enter the nested Screen stack, not the TabNavigator",
        )

        composition.dispose()
        recomposer.close()
        recomposerJob.cancelAndJoin()
    }

    @Test
    fun `production host exposes the same nested stack consumed from LocalNavigator`() = runTest {
        val root = LocalNavigatorProbeScreen()
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitTestApplier(), recomposer)
        val recomposerJob = launch(frameClock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        composition.setContent { VoyagerLibraryNavigationHost.Content(root) }
        frameClock.sendFrame(0L)
        recomposer.awaitIdle()
        composition.dispose()

        root.localNavigator.push(MangaDetailScreen(11L))
        root.localNavigator.push(AuthorDetailScreen(creatorId = 12L))
        assertEquals(
            root.localNavigator.items,
            VoyagerLibraryNavigationHost.currentStack?.items,
            "The host contract must consume the Navigator supplied through LocalNavigator",
        )
        assertEquals(listOf(root, MangaDetailScreen(11L), AuthorDetailScreen(12L)), root.localNavigator.items)

        recomposer.close()
        recomposerJob.cancelAndJoin()
    }

    private class RecordingLibraryNavigationHost : LibraryNavigationHost {
        lateinit var root: Screen
        val stack = RecordingScreenStack()
        var renderCount = 0

        override val currentStack: LibraryScreenStack
            get() = stack

        @Composable
        override fun Content(root: Screen) {
            renderCount++
            this.root = root
            stack.items += root
        }
    }

    private class RecordingScreenStack : LibraryScreenStack {
        override val items = mutableListOf<Screen>()

        override fun push(screen: Screen) {
            items += screen
        }
    }

    private class LocalNavigatorProbeScreen : Screen {
        lateinit var localNavigator: Navigator

        @Composable
        override fun Content() {
            localNavigator = LocalNavigator.currentOrThrow
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
