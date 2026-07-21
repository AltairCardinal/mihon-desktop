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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
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

    @Test
    fun `actions submitted before Navigator is ready are consumed once in FIFO order`() = runTest {
        val state = TestState()
        val controller = searchController(state = state)
        val fixture = navigatorFixture()

        controller.submit(ExternalActionInput.Search("A"))
        controller.submit(ExternalActionInput.Search("B"))
        val consumer = backgroundScope.launch { controller.consumeSignals(fixture.navigator) {} }
        testScheduler.runCurrent()

        assertEquals(listOf("A", "B"), fixture.pushedSearchQueries())
        assertEquals(listOf("ExternalActionSucceeded", "ExternalActionSucceeded"), state.terminalExternalActions())
        assertFalse(controller.hasPendingAction)
        consumer.cancel()
        fixture.close()
    }

    @Test
    fun `actions submitted while resolver is suspended retain order and each produce a result`() = runTest {
        val state = TestState()
        val resolverStarted = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        val resolved = mutableListOf<String>()
        val feedback = mutableListOf<String>()
        val controller = ExternalActionNavigator(
            resolveTarget = { input ->
                val query = (input as ExternalActionInput.Search).primaryQuery!!
                resolved += query
                if (query == "A") {
                    resolverStarted.complete(Unit)
                    releaseResolver.await()
                }
                when (query) {
                    "B" -> DesktopExternalActionTarget.Rejected(DesktopExternalActionTarget.Rejection.ParserRejected)
                    "C" -> error("resolver failed")
                    else -> DesktopExternalActionTarget.GlobalSearch(query)
                }
            },
            chapterDestination = { error("not a chapter") },
            testState = state,
        )
        val fixture = navigatorFixture()
        val consumer = backgroundScope.launch { controller.consumeSignals(fixture.navigator, feedback::add) }

        controller.submit(ExternalActionInput.Search("A"))
        testScheduler.runCurrent()
        resolverStarted.await()
        controller.submit(ExternalActionInput.Search("B"))
        controller.submit(ExternalActionInput.Search("C"))
        releaseResolver.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("A", "B", "C"), resolved)
        assertEquals(
            listOf("ExternalActionSucceeded", "ExternalActionRejected", "ExternalActionFailed"),
            state.terminalExternalActions(),
        )
        assertEquals(2, feedback.size)
        assertFalse(controller.hasPendingAction)
        consumer.cancel()
        fixture.close()
    }

    @Test
    fun `cancelling an in-flight action restores it ahead of later actions`() = runTest {
        val state = TestState()
        val resolverStarted = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        var firstAttempt = true
        val controller = searchController(state = state) { query ->
            attempts += query
            if (query == "A" && firstAttempt) {
                firstAttempt = false
                resolverStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val fixture = navigatorFixture()
        val firstConsumer = launch { controller.consumeSignals(fixture.navigator) {} }

        controller.submit(ExternalActionInput.Search("A"))
        testScheduler.runCurrent()
        resolverStarted.await()
        controller.submit(ExternalActionInput.Search("B"))
        controller.submit(ExternalActionInput.Search("C"))
        firstConsumer.cancelAndJoin()

        val nextConsumer = backgroundScope.launch { controller.consumeSignals(fixture.navigator) {} }
        testScheduler.runCurrent()

        assertEquals(listOf("A", "A", "B", "C"), attempts)
        assertEquals(listOf("A", "B", "C"), fixture.pushedSearchQueries())
        assertEquals(3, state.terminalExternalActions().size)
        assertFalse(controller.hasPendingAction)
        nextConsumer.cancel()
        fixture.close()
    }

    @Test
    fun `suspended rejection feedback does not block the next action`() = runTest {
        val state = TestState()
        val feedbackStarted = CompletableDeferred<Unit>()
        val controller = ExternalActionNavigator(
            resolveTarget = { input ->
                when ((input as ExternalActionInput.Search).primaryQuery) {
                    "A" -> DesktopExternalActionTarget.Rejected(DesktopExternalActionTarget.Rejection.ParserRejected)
                    else -> DesktopExternalActionTarget.GlobalSearch("B")
                }
            },
            chapterDestination = { error("not a chapter") },
            testState = state,
        )
        val fixture = navigatorFixture()
        var feedbackJob: kotlinx.coroutines.Job? = null
        val consumer = backgroundScope.launch {
            controller.consumeSignals(fixture.navigator) {
                feedbackJob = backgroundScope.launch {
                    feedbackStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        }

        try {
            controller.submit(ExternalActionInput.Search("A"))
            testScheduler.runCurrent()
            feedbackStarted.await()
            controller.submit(ExternalActionInput.Search("B"))
            testScheduler.runCurrent()

            assertEquals(listOf("ExternalActionRejected", "ExternalActionSucceeded"), state.terminalExternalActions())
            assertEquals(listOf("B"), fixture.pushedSearchQueries())
        } finally {
            feedbackJob?.cancel()
            consumer.cancel()
            fixture.close()
        }
    }

    @Test
    fun `cancelling rejection feedback does not replay its terminal action`() = runTest {
        val state = TestState()
        val feedbackStarted = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        val controller = ExternalActionNavigator(
            resolveTarget = { input ->
                val query = (input as ExternalActionInput.Search).primaryQuery!!
                attempts += query
                when (query) {
                    "A" -> DesktopExternalActionTarget.Rejected(DesktopExternalActionTarget.Rejection.ParserRejected)
                    else -> DesktopExternalActionTarget.GlobalSearch(query)
                }
            },
            chapterDestination = { error("not a chapter") },
            testState = state,
        )
        val fixture = navigatorFixture()
        var feedbackJob: kotlinx.coroutines.Job? = null
        val firstConsumer = launch {
            controller.consumeSignals(fixture.navigator) {
                feedbackJob = backgroundScope.launch {
                    feedbackStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        }

        controller.submit(ExternalActionInput.Search("A"))
        testScheduler.runCurrent()
        feedbackStarted.await()
        controller.submit(ExternalActionInput.Search("B"))
        testScheduler.runCurrent()
        feedbackJob?.cancelAndJoin()
        firstConsumer.cancelAndJoin()

        val nextConsumer = backgroundScope.launch { controller.consumeSignals(fixture.navigator) {} }
        testScheduler.runCurrent()

        assertEquals(listOf("A", "B"), attempts)
        assertEquals(listOf("ExternalActionRejected", "ExternalActionSucceeded"), state.terminalExternalActions())
        assertEquals(listOf("B"), fixture.pushedSearchQueries())
        nextConsumer.cancel()
        fixture.close()
    }

    private fun searchController(
        state: TestState,
        beforeResolve: suspend (String) -> Unit = {},
    ) = ExternalActionNavigator(
        resolveTarget = { input ->
            val query = (input as ExternalActionInput.Search).primaryQuery!!
            beforeResolve(query)
            DesktopExternalActionTarget.GlobalSearch(query)
        },
        chapterDestination = { error("not a chapter") },
        testState = state,
    )
}

private fun NavigatorFixture.pushedSearchQueries(): List<String> =
    navigator.items.drop(1).map { (it as GlobalSearchScreen).initialQuery }

private fun TestState.terminalExternalActions(): List<String> =
    actionHistory.value.map { it.action }.filterNot { it == "ExternalActionPending" }

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
