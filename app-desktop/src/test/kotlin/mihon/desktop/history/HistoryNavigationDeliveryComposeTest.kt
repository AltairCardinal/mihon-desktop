package mihon.desktop.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.ui.history.HistoryRootScreen
import mihon.desktop.ui.library.MangaDetailScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File
import java.util.Date

@Isolated
@OptIn(ExperimentalComposeUiApi::class)
class HistoryNavigationDeliveryComposeTest {
    @Test fun `actual cover navigation revokes an in flight row before page disposal`(@TempDir folder: File) = scenario(folder, true)

    @Test fun `actual delete dialog revokes an in flight row without leaving history`(@TempDir folder: File) = scenario(folder, false)

    private fun scenario(folder: File, cover: Boolean) = runBlocking {
        val context = initDesktopDIForTest(folder, inMemoryDesktopPreferenceStore())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stored = FakeMangaRepository().apply { seed(Manga.create().copy(id = 10, source = 42, url = "/work", title = "Work")) }
        val repository = object : MangaRepository by stored {
            override suspend fun getMangaById(id: Long): Manga {
                entered.complete(Unit)
                release.await()
                return stored.getMangaById(id)
            }
        }
        val item = HistoryWithRelations(1, 2, 10, "Work", 2.0, Date(), 1, MangaCover(10, 42, true, null, 0))
        val history = FakeHistoryRepository().apply { addHistory(item) }
        val chapters = GetChaptersByMangaId(FakeChapterRepository().apply { seed(Chapter.create().copy(id = 2, mangaId = 10, url = "/2", name = "Chapter 2")) })
        val manga = GetManga(repository)
        val model = HistoryScreenModel(
            GetHistory(history),
            RemoveHistory(history),
            manga,
            getChapters = chapters,
            getNextChapters = GetNextChapters(chapters, manga, history),
            observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val scene = ImageComposeScene(700, 600, coroutineContext = coroutineContext + Dispatchers.Unconfined) {}
        val screen = HistoryRootScreen()
        var navigator: Navigator? = null
        try {
            model.loadHistory()
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    Navigator(screen) { nav ->
                        navigator = nav
                        screen.ContentWithModel(model)
                    }
                }
            }
            withTimeout(5_000) {
                while (nodes(scene).none { tag(it) == "history_item_1" }) {
                    scene.render().close()
                    delay(1)
                }
            }
            click(scene, "history_item_1")
            withTimeout(5_000) { entered.await() }
            click(scene, if (cover) "history_cover_1" else "history_delete_1")
            // No render between the navigation callback and release: disposal cannot be the guard.
            release.complete(Unit)
            assertEquals(if (cover) 2 else 1, requireNotNull(navigator).items.size)
            if (cover) {
                assertEquals(MangaDetailScreen(10), requireNotNull(navigator).lastItem)
            } else {
                assertTrue(requireNotNull(navigator).lastItem is HistoryRootScreen)
                withTimeout(5_000) {
                    while (nodes(scene).none { tag(it) == "history_delete_confirm" }) {
                        scene.render().close()
                        delay(1)
                    }
                }
            }
        } finally {
            release.complete(Unit)
            scene.close()
            model.onDispose()
            context.closeAndJoin()
        }
    }

    private fun click(scene: ImageComposeScene, name: String) = assertTrue(requireNotNull(nodes(scene).single { tag(it) == name }.config[SemanticsActions.OnClick].action)())
    private fun tag(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
