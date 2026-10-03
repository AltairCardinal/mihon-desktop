package mihon.desktop.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.ui.history.HistoryRootScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date

@Isolated
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HistoryDeletePersistenceIntegrationTest {
    @Test
    fun `single reset resurfaces earlier history and keeps chapter progress`(@TempDir directory: File) = delete(directory, false)

    @Test
    fun `all chapters reset removes the manga history and keeps chapter progress`(@TempDir directory: File) = delete(directory, true)

    private fun delete(directory: File, all: Boolean) = runBlocking {
        val context = initDesktopDIForTest(directory, inMemoryDesktopPreferenceStore())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = ImageComposeScene(700, 600, coroutineContext = coroutineContext) {}
        try {
            val manga = Injekt.get<SaveSourceMangaForDetails>().await(
                SManga.create().apply {
                    url = "/delete-scope"
                    title = "Delete scope"
                },
                42,
                (1..2).map {
                    SChapter.create().apply {
                        url = "/chapter/$it"
                        name = "Chapter $it"
                    }
                },
            )
            val chapters = Injekt.get<ChapterRepository>()
            val ordered = chapters.getChapterByMangaId(manga.id).sortedBy { it.chapterNumber }
            ordered.forEachIndexed { index, chapter ->
                chapters.update(ChapterUpdate(chapter.id, read = true, bookmark = true, lastPageRead = 7))
                Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(1000L + index), 5))
            }
            val before = chapters.getChapterByMangaId(manga.id)
            val latest = Injekt.get<GetHistory>().subscribe("").first().single()
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    Navigator(HistoryRootScreen()) { CurrentScreen() }
                }
            }
            settle(scene) { nodes(scene).any { tag(it) == "history_delete_${latest.id}" } }
            click(scene, "history_delete_${latest.id}")
            settle(scene) { nodes(scene).any { tag(it) == "history_delete_confirm" } }
            if (all) {
                click(scene, "history_delete_all_chapters")
                settle(scene) { nodes(scene).single { tag(it) == "history_delete_all_chapters" }.config[SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.On }
            }
            click(scene, "history_delete_confirm")
            val remaining = withTimeout(10_000) { Injekt.get<GetHistory>().subscribe("").first { rows -> if (all) rows.isEmpty() else rows.singleOrNull()?.chapterId == ordered.first().id } }
            assertEquals(if (all) emptyList<Long>() else listOf(ordered.first().id), remaining.map { it.chapterId })
            assertEquals(before, chapters.getChapterByMangaId(manga.id))
            assertTrue(Injekt.get<GetHistory>().await(manga.id).any { it.chapterId == latest.chapterId && (it.readAt?.time ?: 0L) == 0L })
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
        }
    }

    private suspend fun settle(scene: ImageComposeScene, ready: () -> Boolean) = withTimeout(10_000) {
        do {
            scene.render().close()
            yield()
        } while (!ready())
    }
    private fun click(scene: ImageComposeScene, name: String) {
        assertTrue(requireNotNull(nodes(scene).single { tag(it) == name }.config[SemanticsActions.OnClick].action)())
    }
    private fun tag(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
