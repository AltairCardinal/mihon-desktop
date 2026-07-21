package mihon.desktop.ui

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
import mihon.desktop.desktopExternalActionInput
import mihon.desktop.submitDesktopExternalAction
import mihon.desktop.platform.DesktopExternalActionTarget
import mihon.desktop.test.state.TestState
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.settings.BackupSettingsScreen
import mihon.desktop.ui.settings.ExtensionRepoScreen
import mihon.domain.platform.ExternalActionInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class ExternalActionNavigationTest {
    @Test
    fun `cold start input ignores test flags and remains raw`() {
        val uri = "tachiyomi://add-repo?url=https%3A%2F%2Frepo.example"
        assertEquals(ExternalActionInput.ViewUri(uri), desktopExternalActionInput(arrayOf("--test-mode", uri, "--headless")))
        assertNull(desktopExternalActionInput(arrayOf("--test-mode", "--headless")))
    }

    @Test
    fun `all targets push existing regular Screens through ordinary Navigator`() = runTest {
        val backup = File("library.tachibk")
        val targets = listOf(
            DesktopExternalActionTarget.GlobalSearch("query"),
            DesktopExternalActionTarget.Manga(42),
            DesktopExternalActionTarget.Chapter(42, 7),
            DesktopExternalActionTarget.Backup(backup),
            DesktopExternalActionTarget.ExtensionRepo("https://repo.example"),
        )
        val fixture = navigatorFixture()
        var next = 0
        val controller = ExternalActionNavigator(
            resolveTarget = { targets[next++] },
            chapterDestination = {
                DesktopReaderScreen(chapterTitle = "Chapter", mangaId = it.mangaId, chapterId = it.chapterId)
            },
            testState = TestState(),
        )
        repeat(targets.size) {
            controller.submit(ExternalActionInput.Search("action-$it"))
            controller.consumePending(fixture.navigator) {}
        }
        val destinations = fixture.navigator.items.drop(1)
        assertInstanceOf(GlobalSearchScreen::class.java, destinations[0])
        assertInstanceOf(MangaDetailScreen::class.java, destinations[1])
        assertInstanceOf(DesktopReaderScreen::class.java, destinations[2])
        assertEquals("query", (destinations[0] as GlobalSearchScreen).initialQuery)
        assertEquals(42, (destinations[1] as MangaDetailScreen).mangaId)
        assertEquals(42L to 7L, (destinations[2] as DesktopReaderScreen).let { it.mangaId to it.chapterId })
        assertEquals(backup, (destinations[3] as BackupSettingsScreen).initialBackup)
        assertEquals("https://repo.example", (destinations[4] as ExtensionRepoScreen).initialUrl)
        destinations.forEach { destination ->
            assertInstanceOf(Screen::class.java, destination)
            assertFalse(destination is Tab)
        }
        fixture.close()
    }

    @Test
    fun `pending action is consumed once when Navigator becomes ready`() = runTest {
        val state = TestState()
        var resolves = 0
        val controller = ExternalActionNavigator(
            resolveTarget = {
                resolves++
                DesktopExternalActionTarget.GlobalSearch("once")
            },
            chapterDestination = { error("not a chapter") },
            testState = state,
        )
        val fixture = navigatorFixture()
        val consumer = backgroundScope.launch { controller.consumeSignals(fixture.navigator) {} }
        submitDesktopExternalAction(arrayOf("tachiyomi://once"), controller)
        testScheduler.runCurrent()
        controller.consumePending(fixture.navigator) {}
        assertEquals(1, resolves)
        assertEquals(2, fixture.navigator.size)
        assertFalse(controller.hasPendingAction)
        assertEquals("ExternalActionSucceeded", state.actionHistory.value.last().action)
        consumer.cancel()
        fixture.close()
    }
}

internal suspend fun navigatorFixture(): NavigatorFixture {
    val root = NavigatorProbeScreen()
    val frameClock = BroadcastFrameClock()
    val recomposer = Recomposer(kotlin.coroutines.coroutineContext + frameClock)
    val composition = Composition(UnitTestApplier(), recomposer)
    val job = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).launch(
        frameClock,
        start = CoroutineStart.UNDISPATCHED,
    ) { recomposer.runRecomposeAndApplyChanges() }
    composition.setContent { Navigator(root) { CurrentScreen() } }
    frameClock.sendFrame(0)
    recomposer.awaitIdle()
    return NavigatorFixture(root.navigator) {
        composition.dispose()
        recomposer.close()
        job.cancelAndJoin()
    }
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

internal class NavigatorFixture(val navigator: Navigator, private val closeAction: suspend () -> Unit) {
    suspend fun close() = closeAction()
}
