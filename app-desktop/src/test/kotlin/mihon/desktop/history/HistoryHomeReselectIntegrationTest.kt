package mihon.desktop.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.test.http.ProductionReaderTestModeBridge
import mihon.desktop.ui.home.HomeScreen
import mihon.desktop.ui.theme.DesktopTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date

@Isolated
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HistoryHomeReselectIntegrationTest {
    @Test
    fun `first Home history click selects tab and reselect resumes global latest outside search`(@TempDir directory: File) = runBlocking {
        val context = initDesktopDIForTest(directory, inMemoryDesktopPreferenceStore())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ProductionReaderTestModeBridge.reset()
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            val manga = Injekt.get<SaveSourceMangaForDetails>().await(
                SManga.create().apply {
                    url = "/reselect"
                    title = "Recent hidden by search"
                },
                42,
                (3 downTo 1).map { number ->
                    SChapter.create().apply {
                        url = "/$number"
                        name = "Chapter $number"
                        chapter_number = number.toFloat()
                    }
                },
            )
            val chapter = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single { it.url == "/2" }
            Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(), 1))
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    DesktopTheme { Navigator(HomeScreen()) }
                }
            }
            settle(scene) { nodes(scene).any { it.config.contains(SemanticsProperties.Selected) && labels(it).contains(MR.strings.history.localized()) } }
            clickNavigation(scene)
            settle(scene) { nodes(scene).any { it.config.contains(SemanticsActions.SetText) || tag(it) == "history_search_open" } }
            assertNull(ProductionReaderTestModeBridge.binding)
            click(scene, "history_search_open")
            scene.render().close()
            val input = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            requireNotNull(input.config[SemanticsActions.SetText].action)(AnnotatedString("no match"))
            settle(scene) { nodes(scene).none { tag(it)?.startsWith("history_item_") == true } }
            clickNavigation(scene)
            settle(scene) { ProductionReaderTestModeBridge.binding != null }
            assertEquals(chapter.id, ProductionReaderTestModeBridge.snapshot()?.currentChapterId)
            assertEquals(3, ProductionReaderTestModeBridge.snapshot()?.chapterIds?.size)
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            ProductionReaderTestModeBridge.reset()
        }
    }

    private fun clickNavigation(scene: ImageComposeScene) {
        val node = nodes(scene).single { it.config.contains(SemanticsProperties.Selected) && it.config.contains(SemanticsActions.OnClick) && labels(it).contains(MR.strings.history.localized()) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action)())
    }
    private fun click(scene: ImageComposeScene, tag: String) = assertTrue(requireNotNull(nodes(scene).single { tag(it) == tag }.config[SemanticsActions.OnClick].action)())
    private suspend fun settle(scene: ImageComposeScene, ready: () -> Boolean) = withTimeout(10_000) {
        while (!ready()) {
            scene.render().close()
            delay(10)
        }
    }
    private fun tag(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null
    private fun labels(node: SemanticsNode): List<String> =
        (if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()) +
            (if (node.config.contains(SemanticsProperties.ContentDescription)) node.config[SemanticsProperties.ContentDescription] else emptyList()) + node.children.flatMap(::labels)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
