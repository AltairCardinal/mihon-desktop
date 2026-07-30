package mihon.desktop.tracking

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
import mihon.desktop.ui.tracking.mangaTrackingDestination
import mihon.desktop.ui.tracking.pushMangaTracking
import mihon.desktop.ui.tracking.pushTrackingSettings
import mihon.desktop.ui.tracking.TrackingSettingsScreen
import mihon.desktop.ui.tracking.trackingSettingsDestination
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.settings.MoreRootScreen
import mihon.desktop.ui.settings.DesktopSettingsCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackingNavigationContractTest {
    @Test
    fun `tracking catalog route preserves fixed main and desktop tail ordering`() {
        val screens = DesktopSettingsCatalog.screens()
        assertEquals(
            listOf(
                "AppearanceSettingsScreen", "LibrarySettingsScreen", "ReaderSettingsScreen",
                "DownloadSettingsScreen", "TrackingSettingsScreen", "BackupSettingsScreen",
                "SecuritySettingsScreen", "AdvancedSettingsScreen",
            ),
            screens.take(8).map { it.route::class.simpleName },
        )
        assertTrue(screens[4].route is TrackingSettingsScreen)
        assertEquals(
            listOf("GeneralSettingsScreen", "ExtensionRepoScreen", "AboutScreen"),
            screens.drop(8).map { it.route::class.simpleName },
        )
    }

    @Test
    fun `settings and manga tracking destinations are regular Screens`() {
        val settings = trackingSettingsDestination()
        val manga = mangaTrackingDestination(42, "Manga title", 12)

        assertTrue(settings is Screen)
        assertFalse(settings is Tab)
        assertTrue(manga is Screen)
        assertEquals(42, manga.mangaId)
        assertEquals("Manga title", manga.mangaTitle)
        assertEquals(12, manga.totalChapters)
    }

    @Test
    fun `production tracking push helpers use a nested regular Screen navigator`() = runTest {
        val root = NavigatorProbeScreen()
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitTestApplier(), recomposer)
        val job = launch(frameClock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        composition.setContent { Navigator(root) { CurrentScreen() } }
        frameClock.sendFrame(0)
        recomposer.awaitIdle()

        pushTrackingSettings(root.navigator)
        pushMangaTracking(root.navigator, 42, "Manga title", 12)

        assertEquals(listOf(root, trackingSettingsDestination(), mangaTrackingDestination(42, "Manga title", 12)), root.navigator.items)
        assertTrue(root.navigator.items.all { it is Screen && it !is Tab })
        composition.dispose()
        recomposer.close()
        job.cancelAndJoin()
    }

    @Test
    fun `actual More and Manga detail screen callbacks push tracking destinations`() = runTest {
        val root = NavigatorProbeScreen()
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitTestApplier(), recomposer)
        val job = launch(frameClock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        composition.setContent { Navigator(root) { CurrentScreen() } }
        frameClock.sendFrame(0)
        recomposer.awaitIdle()

        MoreRootScreen().onTracking(root.navigator)
        MangaDetailScreen(42).onTracking(root.navigator, "Manga title", 12)

        assertEquals(
            listOf(root, trackingSettingsDestination(), mangaTrackingDestination(42, "Manga title", 12)),
            root.navigator.items,
        )
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
