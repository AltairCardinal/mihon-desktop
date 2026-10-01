package mihon.desktop.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.ui.history.HistoryRootScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HistoryReaderComposeIntegrationTest {
    @Test
    fun `real history click pushes middle chapter with all directory refs`(@TempDir folder: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-history-compose-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = ImageComposeScene(700, 600, coroutineContext = coroutineContext) {}
        var destination: Screen? = null
        try {
            val manga = Injekt.get<SaveSourceMangaForDetails>().await(
                SManga.create().apply {
                    url = "/three"
                    title = "Three chapter history"
                },
                42,
                listOf(3, 2, 1).map { n ->
                    SChapter.create().apply {
                        url = "/$n"
                        name = "Chapter $n"
                        chapter_number = n.toFloat()
                    }
                },
            )
            val middle = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single { it.url == "/2" }
            Injekt.get<UpsertHistory>().await(HistoryUpdate(middle.id, Date(), 1))
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    Navigator(HistoryRootScreen()) { nav ->
                        destination = nav.lastItem
                        CurrentScreen()
                    }
                }
            }
            withTimeout(10_000) {
                while (nodes(scene).none { it.config.contains(SemanticsActions.OnClick) && labels(it).contains(manga.title) }) {
                    scene.render().close()
                    delay(10)
                }
            }
            val row = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && labels(it).contains(manga.title) }
            assertTrue(requireNotNull(row.config[SemanticsActions.OnClick].action).invoke())
            withTimeout(10_000) {
                while (destination !is DesktopReaderScreen) {
                    scene.render().close()
                    delay(10)
                }
            }
            val reader = destination as DesktopReaderScreen
            assertEquals(listOf("/3", "/2", "/1"), reader.chapters.map { it.url })
            assertEquals(1, reader.currentChapterIndex)
            assertEquals(middle.id, reader.chapterId)
            assertEquals(2.0, reader.chapterNumber)
            assertNull(reader.progressTracker)
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun labels(node: SemanticsNode): List<String> =
        (if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()) +
            (if (node.config.contains(SemanticsProperties.ContentDescription)) node.config[SemanticsProperties.ContentDescription] else emptyList())
}
