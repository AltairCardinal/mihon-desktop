package mihon.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.platform.DesktopExternalActionTarget
import mihon.desktop.test.state.TestState
import mihon.desktop.test.state.applicationState
import mihon.desktop.submitDesktopExternalAction
import mihon.desktop.ui.home.ExternalActionFeedbackDispatcher
import mihon.desktop.ui.home.HomeScreen
import mihon.desktop.ui.library.LibraryNavigationHost
import mihon.desktop.ui.library.ProvideLibraryNavigationHost
import mihon.desktop.ui.settings.ExtensionRepoScreen
import mihon.desktop.ui.theme.DesktopTheme
import mihon.domain.platform.ExternalActionInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Locale

@Isolated
@OptIn(ExperimentalComposeUiApi::class)
class ExternalActionFeedbackWiringTest {
    @Test
    fun `Home feedback dispatcher bounds pending messages and shows one at a time`() = runTest {
        val dispatcher = ExternalActionFeedbackDispatcher(capacity = 2)
        val releaseFirst = CompletableDeferred<Unit>()
        val shown = mutableListOf<String>()
        var activeShows = 0
        var maxConcurrentShows = 0
        val consumer = backgroundScope.launch {
            dispatcher.consume { message ->
                activeShows++
                maxConcurrentShows = maxOf(maxConcurrentShows, activeShows)
                shown += message
                try {
                    if (message == "first") releaseFirst.await()
                } finally {
                    activeShows--
                }
            }
        }

        assertTrue(dispatcher.tryPublish("first"))
        testScheduler.runCurrent()
        repeat(10) { assertTrue(dispatcher.tryPublish("message-$it")) }
        testScheduler.runCurrent()

        assertEquals(listOf("first"), shown)
        assertEquals(1, maxConcurrentShows)
        dispatcher.close()
        assertFalse(dispatcher.tryPublish("after-close"))
        releaseFirst.complete(Unit)
        testScheduler.runCurrent()
        consumer.join()

        assertEquals(listOf("first", "message-8", "message-9"), shown)
        assertEquals(1, maxConcurrentShows)
    }

    @Test
    fun `rejection and handler failure show localized feedback without partial navigation`() = runTest {
        val locale = Locale.forLanguageTag("bn")
        val previousLocale = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            val results = ArrayDeque<Result<DesktopExternalActionTarget>>().apply {
                add(Result.success(DesktopExternalActionTarget.Rejected(DesktopExternalActionTarget.Rejection.ParserRejected)))
                add(Result.failure(IllegalStateException("resolver failed")))
            }
            val state = TestState()
            val controller = ExternalActionNavigator(
                resolveTarget = { results.removeFirst().getOrThrow() },
                chapterDestination = { error("not a chapter") },
                testState = state,
            )
            val fixture = navigatorFixture()
            val feedback = mutableListOf<String>()
            repeat(2) {
                controller.submit(ExternalActionInput.Search("bad-$it"))
                controller.consumePending(fixture.navigator, feedback::add)
            }
            assertEquals(1, fixture.navigator.size)
            assertEquals(listOf(MR.strings.error_no_match.localized(locale), MR.strings.unknown_error.localized(locale)), feedback)
            assertEquals(listOf("ExternalActionRejected", "ExternalActionFailed"), state.actionHistory.value.map { it.action }.filterNot { it.endsWith("Pending") })
            fixture.close()
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `repository URL is consumed only by the first confirmation prompt`() {
        val screen = ExtensionRepoScreen("https://repo.example")
        assertEquals("https://repo.example", screen.initialCreatePrompt()?.initialUrl)
        assertEquals("", screen.freshCreatePrompt().initialUrl)
    }

    @Test
    fun `Home consumes cold start rejection and renders localized feedback`(@TempDir tempDir: File) = runTest {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(), startDownloadWorker = false)
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            applicationState.reset()
            val dependencies = DesktopUiDependencies.fromInjekt()
            submitDesktopExternalAction(arrayOf("unsupported://external"), dependencies.externalActionNavigator)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideLibraryNavigationHost(mockk<LibraryNavigationHost>(relaxed = true)) {
                        DesktopTheme { HomeScreen().Content() }
                    }
                }
            }
            scene.render()
            withTimeout(5_000) {
                applicationState.actionHistory.first { records -> records.any { it.action == "ExternalActionRejected" } }
            }
            val feedback = MR.strings.error_no_match.localized()
            withTimeout(5_000) {
                while (!scene.semanticsOwners.joinToString { semantics(it.rootSemanticsNode) }.contains(feedback)) {
                    scene.render()
                    yield()
                }
            }
        } finally {
            scene.close()
            applicationState.reset()
            context.closeAndJoin()
        }
    }

    @Test
    fun `Home consumes a successful action while rejection Snackbar remains visible`(@TempDir tempDir: File) = runTest {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(), startDownloadWorker = false)
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            applicationState.reset()
            val dependencies = DesktopUiDependencies.fromInjekt()
            submitDesktopExternalAction(arrayOf("unsupported://external"), dependencies.externalActionNavigator)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideLibraryNavigationHost(mockk<LibraryNavigationHost>(relaxed = true)) {
                        DesktopTheme { HomeScreen().Content() }
                    }
                }
            }
            scene.render()
            val feedback = MR.strings.error_no_match.localized()
            withTimeout(5_000) {
                while (!scene.semanticsOwners.joinToString { semantics(it.rootSemanticsNode) }.contains(feedback)) {
                    scene.render()
                    yield()
                }
            }

            dependencies.externalActionNavigator.submit(ExternalActionInput.Search("after-feedback"))
            withTimeout(1_000) {
                applicationState.actionHistory.first { records -> records.any { it.action == "ExternalActionSucceeded" } }
            }
            scene.render()

            assertTrue(scene.semanticsOwners.joinToString { semantics(it.rootSemanticsNode) }.contains(feedback))
        } finally {
            scene.close()
            applicationState.reset()
            context.closeAndJoin()
        }
    }

    @Test
    fun `Home drops oldest external feedback under sustained load`(@TempDir tempDir: File) = runTest {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(), startDownloadWorker = false)
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            applicationState.reset()
            val controller = ExternalActionNavigator(
                resolveTarget = { input ->
                    when ((input as ExternalActionInput.Search).primaryQuery) {
                        "initial", "message-0" -> DesktopExternalActionTarget.Rejected(
                            DesktopExternalActionTarget.Rejection.InvalidBackupPath,
                        )
                        "message-1" -> error("handler failed")
                        else -> DesktopExternalActionTarget.Rejected(
                            DesktopExternalActionTarget.Rejection.ParserRejected,
                        )
                    }
                },
                chapterDestination = { error("not a chapter") },
                testState = applicationState,
            )
            val dependencies = DesktopUiDependencies.fromInjekt().copy(externalActionNavigator = controller)
            controller.submit(ExternalActionInput.Search("initial"))
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideLibraryNavigationHost(mockk<LibraryNavigationHost>(relaxed = true)) {
                        DesktopTheme { HomeScreen().Content() }
                    }
                }
            }
            scene.render()
            val initialFeedback = MR.strings.invalid_backup_file.localized()
            withTimeout(5_000) {
                while (!scene.semanticsOwners.joinToString { semantics(it.rootSemanticsNode) }.contains(initialFeedback)) {
                    scene.render()
                    yield()
                }
            }

            repeat(10) { controller.submit(ExternalActionInput.Search("message-$it")) }
            withTimeout(1_000) {
                applicationState.actionHistory.first { records ->
                    records.count { !it.action.endsWith("Pending") } == 11
                }
            }
            testScheduler.advanceTimeBy(5_000)
            scene.render()

            assertTrue(
                scene.semanticsOwners
                    .joinToString { semantics(it.rootSemanticsNode) }
                    .contains(MR.strings.error_no_match.localized()),
            )
        } finally {
            scene.close()
            applicationState.reset()
            context.closeAndJoin()
        }
    }

    @Test
    fun `production DI chapter destination reuses the complete reader request`(@TempDir tempDir: File) = runTest {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(), startDownloadWorker = false)
        try {
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(Manga.create().copy(source = 9, url = "/manga", title = "Manga")),
            ).single()
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "Chapter 7")),
            ).single()
            val controller = DesktopUiDependencies.fromInjekt().externalActionNavigator
            val screen = controller.destination(DesktopExternalActionTarget.Chapter(manga.id, chapter.id)) as mihon.desktop.ui.reader.DesktopReaderScreen
            assertEquals(listOf(manga.id, chapter.id, manga.source), listOf(screen.mangaId, screen.chapterId, screen.sourceId))
            assertEquals(listOf(manga.title, chapter.name, chapter.url), listOf(screen.mangaTitle, screen.chapterTitle, screen.chapterUrl))
            assertEquals(chapter.id, screen.chapters.single().id)
        } finally {
            context.closeAndJoin()
        }
    }

    private fun semantics(node: SemanticsNode): String =
        node.config.toString() + node.children.joinToString(transform = ::semantics)
}
