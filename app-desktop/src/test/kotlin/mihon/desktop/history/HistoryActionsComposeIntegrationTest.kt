package mihon.desktop.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import mihon.desktop.ui.history.HistoryRootScreen
import mihon.desktop.ui.library.MangaDetailScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date

@Isolated
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HistoryActionsComposeIntegrationTest {
    @Test
    fun `cover pushes exact detail`(@TempDir directory: File) = scenario(directory, true)

    @Test
    fun `delete first opens confirmation without writing and cancel preserves history`(
        @TempDir directory: File,
    ) = scenario(directory, false)

    private fun scenario(directory: File, openCover: Boolean) = runBlocking {
        val context = initDesktopDIForTest(directory, inMemoryDesktopPreferenceStore())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = ImageComposeScene(700, 600, coroutineContext = coroutineContext) {}
        var navigator: Navigator? = null
        try {
            val manga = Injekt.get<SaveSourceMangaForDetails>().await(
                SManga.create().apply {
                    url = "/actions"
                    title = "History actions"
                },
                42,
                listOf(
                    SChapter.create().apply {
                        url = "/chapter"
                        name = "Chapter"
                    },
                ),
            )
            val chapter = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single()
            Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(), 1))
            val item = Injekt.get<GetHistory>().subscribe("").first().single()
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    Navigator(HistoryRootScreen()) { nav ->
                        navigator = nav
                        CurrentScreen()
                    }
                }
            }
            withTimeout(10_000) {
                while (nodes(scene).none { tag(it) == "history_cover_${item.id}" }) {
                    scene.render().close()
                    delay(10)
                }
            }
            if (openCover) {
                val cover = nodes(scene).single { tag(it) == "history_cover_${item.id}" }.config
                assertEquals(
                    listOf(manga.title),
                    cover.getOrElse(SemanticsProperties.ContentDescription) {
                        emptyList()
                    },
                )
                assertEquals(MR.strings.action_show_manga.localized(), cover[SemanticsActions.OnClick].label)
                assertTrue(cover[SemanticsActions.OnClick].action != null)
                pointerClick(scene, "history_cover_${item.id}")
                withTimeout(10_000) {
                    while (navigator?.lastItem is HistoryRootScreen) {
                        scene.render().close()
                        delay(10)
                    }
                }
                assertEquals(MangaDetailScreen(manga.id), navigator?.lastItem)
                assertEquals(2, requireNotNull(navigator).items.size)
                requireNotNull(navigator).pop()
                scene.render().close()
            }
            pointerClick(scene, "history_delete_${item.id}")
            withTimeout(10_000) {
                while (nodes(scene).none { tag(it) == "history_delete_confirm" }) {
                    scene.render().close()
                    kotlinx.coroutines.yield()
                }
            }
            assertEquals(1, Injekt.get<GetHistory>().await(manga.id).count { (it.readAt?.time ?: 0) > 0 })
            assertTrue(nodes(scene).any { tag(it) == "history_delete_confirm" })
            assertEquals(1, requireNotNull(navigator).items.size)
            if (!openCover) {
                fun checkbox() = nodes(scene).single { tag(it) == "history_delete_all_chapters" }.config
                assertEquals(
                    listOf(MR.strings.dialog_with_checkbox_reset.localized()),
                    checkbox().getOrElse(SemanticsProperties.ContentDescription) { emptyList() },
                )
                assertEquals(Role.Checkbox, checkbox()[SemanticsProperties.Role])
                assertEquals(ToggleableState.Off, checkbox()[SemanticsProperties.ToggleableState])
                pointerClick(scene, "history_delete_all_chapters")
                withTimeout(5_000) {
                    while (checkbox()[SemanticsProperties.ToggleableState] != ToggleableState.On) {
                        scene.render().close()
                        kotlinx.coroutines.yield()
                    }
                }
                assertEquals(ToggleableState.On, checkbox()[SemanticsProperties.ToggleableState])
            }
            click(scene, "history_delete_cancel")
            withTimeout(10_000) {
                while (nodes(scene).any { tag(it) == "history_delete_confirm" }) {
                    scene.render().close()
                    kotlinx.coroutines.yield()
                }
            }
            assertEquals(1, Injekt.get<GetHistory>().await(manga.id).count { (it.readAt?.time ?: 0) > 0 })
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
        }
    }

    private fun pointerClick(scene: ImageComposeScene, name: String) {
        val node = nodes(scene).single { tag(it) == name }
        val point = node.boundsInRoot.center
        scene.sendPointerEvent(
            androidx.compose.ui.input.pointer.PointerEventType.Press,
            point,
            button = androidx.compose.ui.input.pointer.PointerButton.Primary,
        )
        scene.sendPointerEvent(
            androidx.compose.ui.input.pointer.PointerEventType.Release,
            point,
            button = androidx.compose.ui.input.pointer.PointerButton.Primary,
        )
    }

    private fun click(scene: ImageComposeScene, tag: String) {
        val node = nodes(scene).single { tag(it) == tag }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action)())
    }
    private fun tag(
        node: SemanticsNode,
    ) = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null
    private fun nodes(
        scene: ImageComposeScene,
    ): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
